(ns grog.babashka
  "Opt-in `run_babashka` tool: run Babashka in an empty temp cwd with a trimmed environment.
  Contract (per SOUL): script should treat input as stdin and emit results on stdout only."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.config :as cfg])
  (:import [java.io File IOException]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(defn- parse-json-args [arguments]
  (cond (map? arguments) arguments
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

(defn tool-spec
  []
  {:type "function"
   :function
   {:name "run_babashka"
    :description
    (str "Run **Babashka** (`bb`) on a short Clojure script in an **isolated empty directory** with a **reduced environment** "
         "(PATH, JAVA_HOME, temp vars; HOME set to that directory). **Contract:** read problem input from **stdin** "
         "(Grog passes `stdin` as the pipe contents; in code use `(slurp *in*)` or `*in*`), write the answer to **stdout** only; "
         "use stderr sparingly for errors. **Do not** rely on workspace files, network, or mutating the host — SOUL forbids "
         "\"mutating the universe\"; prefer pure data transforms. **Python is off-limits** — Babashka/Clojure only. "
         "Enable in grog.edn: `:babashka {:enabled true}`.")
    :parameters
    {:type "object"
     :required ["script"]
     :properties
     {:script {:type "string"
               :description "Babashka/Clojure source (e.g. read stdin, print result)."}
      :stdin {:type "string"
              :description "UTF-8 text written to the process stdin (often empty)."}
      :timeout_seconds {:type "integer"
                        :description (str "Wall-clock seconds (optional; default "
                                        (cfg/babashka-default-timeout-sec) ", max "
                                        (cfg/babashka-max-timeout-sec) ").")}}}}})

(defn tool-log-summary
  [args]
  (let [m (parse-json-args args)
        s (str-trim (or (:script m) (get m "script")))]
    (str (count s) " chars script")))

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

(defn run-babashka!
  [arguments]
  (if-not (cfg/babashka-configured?)
    (json/generate-string
     {:error "run_babashka is disabled"
      :hint "Set :babashka {:enabled true} in grog.edn (see resources/grog.edn example)."})
    (let [m (parse-json-args arguments)
          script (str-trim (or (:script m) (get m "script")))
          stdin-str (str (or (:stdin m) (get m "stdin") ""))
          max-script (cfg/babashka-max-script-chars)
          max-out (cfg/babashka-max-stdout-chars)
          max-err (cfg/babashka-max-stderr-chars)
          timeout-sec (let [x (or (:timeout_seconds m) (get m "timeout_seconds"))]
                        (if (and (number? x) (pos? (long x)))
                          (min (cfg/babashka-max-timeout-sec) (long x))
                          (cfg/babashka-default-timeout-sec)))
          bb-cmd (cfg/babashka-command)]
      (cond
        (str/blank? script)
        (json/generate-string {:error "script is required"})

        (> (count script) max-script)
        (json/generate-string {:error "script too long"
                               :max_chars max-script
                               :chars (count script)})

        :else
        (let [sandbox ^File (.toFile (Files/createTempDirectory "grog-bb-" (into-array java.nio.file.attribute.FileAttribute [])))
              script-file (io/file sandbox "script.clj")]
          (try
            (spit script-file script :encoding "UTF-8")
            (run-bb-process! bb-cmd script-file sandbox stdin-str timeout-sec max-out max-err)
            (finally
              (delete-tree! sandbox))))))))

(defn startup-status-line
  []
  (if (cfg/babashka-configured?)
    (str "run_babashka: enabled — command " (pr-str (cfg/babashka-command))
         " (empty temp cwd, reduced env; :babashka in grog.edn)")
    "run_babashka: off — set :babashka {:enabled true} in grog.edn"))
