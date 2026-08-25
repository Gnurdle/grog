(ns grog-babashka.main
  "grog-babashka — a standalone MCP server (over stdio) exposing **run_babashka** so
  an ECA-driven agent loop can execute short Clojure/Babashka scripts in an isolated,
  host-neutral sandbox.

  This restores grog's `run_babashka` tool on the ECA/MCP surface by porting
  `grog.babashka` (same contract: script reads problem input from **stdin**, writes
  the answer to **stdout**, must not mutate the host; Python is off-limits).

  * Babashka is a **given** — always enabled (no config toggle). `bb` must be on
    PATH (override the command with env `GROG_BABASHKA_CMD`).
  * Tool: `run_babashka` — `script` (required), `stdin`, `timeout_seconds`.

  ECA discovers this over stdio like the other grog servers:
    :mcpServers
      {\"grog-babashka\"
        {:command \"bash\" :args [\"-lc\" \"cd '<root>/grog-babashka' && clojure -M:mcp -m grog-babashka.main\"]}}
  "
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File IOException]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.util.concurrent TimeUnit]
           [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
           [io.modelcontextprotocol.server McpServer]
           [io.modelcontextprotocol.server McpServerFeatures$AsyncToolSpecification]
           [io.modelcontextprotocol.spec
            McpSchema$ServerCapabilities McpSchema$Tool McpSchema$CallToolResult
            McpSchema$TextContent]
           [reactor.core.publisher Mono]
           [com.fasterxml.jackson.databind ObjectMapper]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Configuration (ported from grog.config babashka-*)
;; ---------------------------------------------------------------------------

(defn- babashka-command []
  (or (some-> (System/getenv "GROG_BABASHKA_CMD") str str/trim not-empty)
      "bb"))

(def ^:private default-timeout-sec 120)
(def ^:private max-timeout-sec 600)
(def ^:private max-script-chars 50000)
(def ^:private max-stdout-chars 500000)
(def ^:private max-stderr-chars 200000)

;; ---------------------------------------------------------------------------
;; Sandbox execution (ported from grog.babashka)
;; ---------------------------------------------------------------------------

(defn- parse-json-args [arguments]
  (cond (map? arguments) arguments
        (instance? java.util.Map arguments)
        (into {} (map (fn [[k v]] [(str k) v])) arguments)
        (string? arguments) (try (json/parse-string arguments true) (catch Exception _ {}))
        :else {}))

(defn- str-trim [x] (str/trim (str (or x ""))))

(defn- delete-tree! [^File root]
  (when (.exists root)
    (doseq [f (reverse (file-seq root))]
      (io/delete-file f true))))

(defn- slurp-limited
  ^String [^java.io.InputStream is ^long max-bytes]
  (let [buf (byte-array 16384)
        baos (java.io.ByteArrayOutputStream.)]
    (try
      (loop [total 0]
        (if (>= total max-bytes)
          (str (String. (.toByteArray baos) StandardCharsets/UTF_8) "\n… [truncated]")
          (let [to-read (int (min (alength buf) (- max-bytes total)))
                n (.read is buf 0 to-read)]
            (if (neg? n)
              (String. (.toByteArray baos) StandardCharsets/UTF_8)
              (do (.write baos buf 0 n)
                  (recur (+ total n)))))))
      (catch Exception e
        (str "[stream read error: " (.getMessage e) "]")))))

(defn- configure-bb-env!
  [^java.lang.ProcessBuilder pb ^File sandbox]
  (let [env (.environment pb)]
    (.clear env)
    (doseq [k ["PATH" "PATHEXT" "JAVA_HOME" "LANG" "LC_ALL" "LC_MESSAGES"
                "SYSTEMROOT" "WINDIR" "HOMEDRIVE" "USERDOMAIN" "USERNAME"]]
      (when-let [v (System/getenv k)]
        (.put env k v)))
    (let [home (.getCanonicalPath sandbox)]
      (.put env "HOME" home)
      (.put env "USERPROFILE" home)
      (.put env "TEMP" home)
      (.put env "TMP" home))))

(defn- run-bb-process!
  [bb-cmd ^File script-file ^File sandbox stdin-str timeout-sec max-out max-err]
  (let [cmd (into-array String [bb-cmd (.getCanonicalPath script-file)])
        pb (doto (ProcessBuilder. ^"[Ljava.lang.String;" cmd)
             (.directory sandbox))]
    (configure-bb-env! pb sandbox)
    (try
      (let [^java.lang.Process proc (.start pb)
            out-future (future (slurp-limited (.getInputStream proc) max-out))
            err-future (future (slurp-limited (.getErrorStream proc) max-err))
            stdin-err
            (try
              (with-open [os (.getOutputStream proc)]
                (when-not (str/blank? stdin-str)
                  (.write os (.getBytes ^String stdin-str StandardCharsets/UTF_8))))
              nil
              (catch IOException e
                (.destroyForcibly proc)
                (json/generate-string {:error "failed writing stdin"
                                       :detail (.getMessage e)})))]
        (if (some? stdin-err)
          stdin-err
          (let [finished (.waitFor proc (long timeout-sec) TimeUnit/SECONDS)]
            (when-not finished
              (.destroyForcibly proc))
            (let [stdout (try @out-future (catch Exception e (str "[stdout: " (.getMessage e) "]")))
                  stderr (try @err-future (catch Exception e (str "[stderr: " (.getMessage e) "]")))]
              (json/generate-string
               (cond-> {:exit_code (if finished (.exitValue proc) -1)
                        :timed_out (not finished)
                        :timeout_seconds timeout-sec
                        :stdout stdout
                        :stderr stderr}
                 (not finished) (assoc :note "process killed after timeout")))))))
      (catch IOException e
        (json/generate-string {:error "could not start babashka"
                               :command bb-cmd
                               :detail (.getMessage e)
                               :hint "Install Babashka and ensure `bb` is on PATH, or set :babashka :command in grog.edn."})))))

(defn- run-babashka!
  [arguments]
  (let [m (parse-json-args arguments)
        script (str-trim (or (:script m) (get m "script")))
        stdin-str (str (or (:stdin m) (get m "stdin") ""))
        timeout-sec (if-let [x (or (:timeout_seconds m) (get m "timeout_seconds"))]
                      (if (and (number? x) (pos? (long x)))
                        (min max-timeout-sec (long x))
                        default-timeout-sec)
                      default-timeout-sec)
        bb-cmd (babashka-command)]
    (cond
      (str/blank? script)
      (json/generate-string {:error "script is required"})

      (> (count script) max-script-chars)
      (json/generate-string {:error "script too long"
                             :max_chars max-script-chars
                             :chars (count script)})

      :else
      (let [sandbox ^File (.toFile (Files/createTempDirectory "grog-bb-" (into-array java.nio.file.attribute.FileAttribute [])))
            script-file (io/file sandbox "script.clj")]
        (try
          (spit script-file script :encoding "UTF-8")
          (run-bb-process! bb-cmd script-file sandbox stdin-str
                           timeout-sec max-stdout-chars max-stderr-chars)
          (finally
            (delete-tree! sandbox)))))))

;; ---------------------------------------------------------------------------
;; MCP server wiring (same pattern as grog-search / grog-memory)
;; ---------------------------------------------------------------------------

(defn- text-content [^String s] (McpSchema$TextContent. s))
(defn- text-result [^String s] (McpSchema$CallToolResult. [(text-content s)] false))
(defn- error-result [^String s] (McpSchema$CallToolResult. [(text-content s)] true))

(defn- tool
  [{:keys [name description schema fn]}]
  (McpServerFeatures$AsyncToolSpecification.
    (McpSchema$Tool. name description schema)
    (reify java.util.function.BiFunction
      (apply [_ _exchange arguments]
        (Mono/create
          (reify java.util.function.Consumer
            (accept [_ sink]
              (try
                (.success sink (text-result (fn arguments)))
                (catch Throwable t
                  (.success sink (error-result
                                  (str "Error executing tool " name ": "
                                       (or (:message (ex-data t)) (.getMessage t))))))))))))))

(defn- tool-spec []
  {:name "run_babashka"
   :description (str "Run Babashka (bb) on a short Clojure script in an isolated empty directory with a "
                     "reduced environment. Contract: read problem input from stdin in the script "
                     "(e.g. (slurp *in*)), write the answer to stdout only; use stderr sparingly. "
                     "Do not rely on repo files, network, or mutating the host — pure data transforms. "
                     "Python is off-limits.")
   :schema (json/generate-string {:type :object
                            :properties {:script {:type :string
                                                  :description "Babashka/Clojure source (e.g. read stdin, print result)."}
                                         :stdin {:type :string
                                                 :description "UTF-8 text written to the process stdin (often empty)."}
                                         :timeout_seconds {:type :integer
                                                           :description (str "Wall-clock seconds (optional; default "
                                                                           default-timeout-sec ", max "
                                                                           max-timeout-sec ").")}}
                            :required ["script"]})
   :fn run-babashka!})

(defn mcp-server []
  (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "grog-babashka" "0.1.0")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                      (.tools true)
                                      (.build)))
                   (.build))]
    (-> (.addTool server (tool (tool-spec))) (.subscribe))
    server))

(defn -main [& _args]
  (mcp-server)
  (loop []
    (Thread/sleep 1000)
    (recur)))
