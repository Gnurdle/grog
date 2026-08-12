(ns grog.eca
  "JSON-RPC 2.0 over stdio client for the ECA server (`eca server`).

  ECA speaks LSP-style framing (NOT NDJSON): each message is
  `Content-Length: <byte-count>\\r\\n\\r\\n<json-rpc-body>`, exactly like LSP.
  This namespace spawns `eca server` as a child process, performs the
  `initialize` / `initialized` handshake, then lets the caller drive the chat
  loop through `chat/prompt` and render the `chat/contentReceived` stream.

  The reader runs on a daemon thread and dispatches every inbound frame:
    - a message with `:id` but no `:method`  -> response to one of our requests
    - a message with `:id` and `:method`    -> server->client request, responds
    - a message with neither                -> notification (event handler)

  Notifications (especially `chat/contentReceived`) are routed to a pluggable
  event handler so the Swing UI can render them. Requests are routed to a
  pluggable request handler so it can answer `chat/askQuestion` / `editor/getDiagnostics`
  (responds with safe defaults if unset)."
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream InputStream OutputStreamWriter]
           [java.lang ProcessBuilder Process ProcessHandle]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent ConcurrentHashMap]
           [java.util.concurrent.atomic AtomicLong]))

(def ^:private rpc-timeout-ms 120000)

;; ---------------------------------------------------------------------------
;; LSP framing
;; ---------------------------------------------------------------------------

(defn- read-ascii-line!
  "Read one ASCII line (terminated by `\\n`, `\\r` stripped). Returns nil on clean
  EOF (no more frames)."
  ^String [^InputStream in]
  (let [baos (ByteArrayOutputStream.)]
    (loop []
      (let [b (.read in)]
        (cond
          (neg? b)
          (when (pos? (.size baos))
            (throw (ex-info "ECA: EOF mid-header" {})))

          (= b 10)
          (let [raw (String. (.toByteArray baos) StandardCharsets/US_ASCII)]
            (if (str/ends-with? raw "\r")
              (subs raw 0 (dec (count raw)))
              raw))

          :else
          (do (.write baos (bit-and b 0xff))
              (recur)))))))

(defn- read-n-bytes!
  "Read exactly `n` bytes as a UTF-8 string."
  ^String [^InputStream in ^long n]
  (let [buf (byte-array n)
        off (atom 0)]
    (loop []
      (if (>= @off n)
        (String. buf StandardCharsets/UTF_8)
        (let [r (.read in buf @off (- n @off))]
          (if (neg? r)
            nil
            (do (swap! off + r) (recur))))))))

(defn- read-frame!
  "Read one LSP frame and parse its JSON body. Returns nil on clean EOF."
  [^InputStream in]
  (let [content-length (atom nil)]
    (loop []
      (let [line (read-ascii-line! in)]
        (when line
          (if (str/blank? line)
            ;; blank line terminates the header block
            (when-some [n @content-length]
              (read-n-bytes! in n))
            (do
              (let [[k v] (str/split (str line) #":" 2)
                    lk (str/lower-case (str/trim k))]
                (when (and v (= lk "content-length"))
                  (reset! content-length (Long/parseLong (str/trim v)))))
              (recur))))))))

(defn- parse-json [^String s]
  (when (seq s)
    (try (json/parse-string s true)
         (catch Exception _ nil))))

(defn- write-frame! [^OutputStreamWriter w lock obj]
  (locking lock
    (let [^String s      (json/generate-string obj)
          ^bytes bytes   (.getBytes s StandardCharsets/UTF_8)
          n              (alength bytes)]
      (.write w (str "Content-Length: " n "\r\n\r\n"))
      (.write w s)
      (.flush w))))

;; ---------------------------------------------------------------------------
;; Subprocess + connection
;; ---------------------------------------------------------------------------

(defn- stderr-drain! [^Process proc log-fn]
  (doto (Thread.
          (fn []
            (try
              (let [in (.getErrorStream proc)
                    baos (ByteArrayOutputStream.)]
                (loop []
                  (let [b (.read in)]
                    (when (>= b 0)
                      (if (= b 10)
                        (do (let [raw (String. (.toByteArray baos) StandardCharsets/UTF_8)]
                              (when log-fn
                                (binding [*out* *err*]
                                  (log-fn (str/trim raw)))))
                            (.reset baos))
                        (.write baos (bit-and b 0xff)))
                      (recur)))))
              (catch Exception _ nil)))
          "grog-eca-stderr")
    (.setDaemon true)
    (.start)))

;; ---------------------------------------------------------------------------
;; ECA binary resolution
;; ---------------------------------------------------------------------------
;; On Linux (and typical dev setups) `eca` sits on the global PATH, so the bare
;; name works. On Windows that is NOT the case: the ECA server ships as
;; `eca.exe` bundled inside the VS Code extension ("editor-code-assistant.eca-*")
;; and is normally started by VS Code with an explicit absolute path, so the bare
;; name isn't resolvable here. We therefore resolve the binary in order:
;;   1. an explicit `:eca-binary` path the caller supplied (if it exists),
;;   2. the bare name via the OS PATH lookup,
;;   3. well-known install locations (VS Code extension dirs, ~/.local, /usr/local)
;; so `clojure -M:gui` / grog-ui works on Windows too.

(defn- path-executable?
  "True if `name` (optionally with a platform executable extension) resolves to
  an executable file on the OS PATH."
  [name]
  (try
    (let [win? (str/includes? (str/lower-case (System/getProperty "os.name")) "win")
          exts (if win? ["exe" "cmd" "bat" ""] [""])
          sep  (System/getProperty "path.separator")
          dirs (remove str/blank? (str/split (or (System/getenv "PATH") "") #(java.util.regex.Pattern/quote sep)))]
      (boolean
        (some (fn [d]
                (some (fn [ext]
                        (let [f (java.io.File. d (str name (when (seq ext) (str "." ext))))]
                          (and (.isFile f) (.canExecute f))))
                      exts))
              dirs)))
    (catch Throwable _ false)))

(defn- vscode-extension-dirs
  "Candidate ECA directories under a VS Code install (`.../data/extensions`):
  every `editor-code-assistant.eca-*/` folder and its `bin/` subfolder."
  [^java.io.File vscode-home]
  (try
    (let [ext (java.io.File. vscode-home "data/extensions")]
      (when (.isDirectory ext)
        (->> (.listFiles ext)
             (filter #(and (.isDirectory ^java.io.File %)
                           (str/starts-with? (.getName ^java.io.File %) "editor-code-assistant")))
             (mapcat (fn [^java.io.File d]
                       [d (java.io.File. d "bin")])))))
    (catch Throwable _ nil)))

(defn- known-eca-candidates
  "Absolute File objects for likely ECA binaries across OSes."
  []
  (let [home (System/getProperty "user.home")]
    (sequence cat
      (map (fn [d]
             (when d
               [(java.io.File. d "eca")
                (java.io.File. d "eca.exe")]))
           (concat
             ;; VS Code: %USERPROFILE%\scoop\apps\vscode\{version,current}
             (when-let [scoop-vscode (java.io.File. (System/getenv "USERPROFILE") "scoop/apps/vscode")]
               (when (.isDirectory scoop-vscode)
                 (mapcat vscode-extension-dirs (.listFiles scoop-vscode))))
             ;; ~/.local/bin and /usr/local/bin on POSIX
             [(java.io.File. home ".local/bin")
              (java.io.File. "/usr/local/bin")
              (java.io.File. "/usr/bin")])))))

(defn- resolve-eca-binary!
  "Resolve the ECA server binary to an absolute path (or a PATH-resolvable name).
  Prefers an explicit, existing `eca-binary`; else a PATH-hit for the bare name;
  else the first existing/candidate found in known install locations. Falls back
  to the caller's value.
  `extra-args` are the args grog will pass after `server`; a `--config-file` arg
  is inspected only to keep this simple — returns just the binary."
  [^String eca-binary]
  (let [explicit (when (seq eca-binary)
                   (let [f (java.io.File. eca-binary)]
                     (and (.isFile f) f)))]
    (cond
      explicit (.getAbsolutePath explicit)

      (or (nil? (seq eca-binary)) (path-executable? eca-binary))
      (if (seq eca-binary) eca-binary "eca")

      :else
      (or (some (fn [^java.io.File f] (and (.isFile f) (.getAbsolutePath f)))
                (known-eca-candidates))
          (if (seq eca-binary) eca-binary "eca")))))

(defn- next-id! ^long [^AtomicLong al] (.incrementAndGet al))

(defn- pending-key [rid]
  (cond (number? rid) (str (long rid))
        (string? rid) rid
        :else nil))

(defn- trace-frame!
  "Feed a raw JSON-RPC `frame` (one side of the ECA<->grog conversation) to the
  connection's trace fn, labelled by `dir` (either :out or :in)."
  [conn dir frame]
  (when-let [tf (:trace-fn conn)]
    (tf dir frame)))

(defn- respond! [conn id result error]
  (let [obj (cond-> {:jsonrpc "2.0" :id id}
              (some? error) (assoc :error error)
              (some? result) (assoc :result result))]
    (write-frame! (:out conn) (:write-lock conn) obj)
    (trace-frame! conn :out obj)))

(defn- handle-frame!
  "Dispatch a single parsed frame on the connection."
  [conn frame]
  (let [{:keys [id method params]} frame
        event-handler   (:event-handler conn)
        request-handler (:request-handler conn)]
    (cond
      ;; response to one of our requests
      (and (some? id) (not method))
      (when-some [k (pending-key id)]
        (when-some [p (.remove ^ConcurrentHashMap (:pending conn) k)]
          (deliver p frame)))

      ;; server->client request: must respond
      (and (some? id) method)
      (let [resp (try (request-handler method params)
                      (catch Exception _
                        {:error {:code -32601 :message (str "unhandled: " method)}}))
            result (:result resp)
            error  (:error resp)]
        (respond! conn (long id) result error))

      ;; notification
      :else
      (event-handler method (or params {})))))

(defn- reader-loop!
  "Continuously read frames off the child's stdout and dispatch. Runs on a
  daemon thread. Returns when EOF or the connection is closed."
  [conn]
  (loop []
    (let [body (try (read-frame! (:in conn))
                    (catch Exception _ nil))]
      (if (nil? body)
        nil
        (let [frame (parse-json body)]
          (when frame
            (trace-frame! conn :in frame)
            (try (handle-frame! conn frame)
                 (catch Exception e
                   (when-let [lf (:log-fn conn)]
                     (lf (str "eca handle error: " (.getMessage e)))))))
          (recur))))))

(defn- make-connection!
  "Spawn `eca server` and wire up streams. Does NOT handshake."
  [opts]
  (let [eca-binary      (resolve-eca-binary! (or (:eca-binary opts) "eca"))
        args            (:args opts)
        env             (:env opts)
        cwd             (:cwd opts)
        log-fn          (:log-fn opts)
        on-stop         (:on-stop opts)
        trace-fn        (:trace-fn opts)
        event-handler   (or (:event-handler opts)
                            (fn [_ _] nil))
        request-handler (or (:request-handler opts)
                            (fn [method _]
                              (case method
                                "chat/askQuestion" {:result {:answer nil :cancelled true}}
                                "editor/getDiagnostics" {:result {:diagnostics []}}
                                {:result {}})))
        pb (ProcessBuilder. ^java.util.List (into [eca-binary "server"] (map str (or args []))))
        pending (ConcurrentHashMap.)
        al (AtomicLong. 0)
        write-lock (Object.)
        _ (when cwd (.directory pb (java.io.File. cwd)))
        _ (when env
            (let [^java.util.Map m (.environment pb)]
              (doseq [[k v] env]
                (.put m (str k) (str v)))))
        _ (.redirectErrorStream pb false)
        proc (.start pb)
        in (.getInputStream proc)
        out (OutputStreamWriter. (.getOutputStream proc) StandardCharsets/UTF_8)
        conn {:process proc
              :in in
              :out out
              :write-lock write-lock
              :pending pending
              :req-seq al
              :event-handler event-handler
              :request-handler request-handler
              :log-fn log-fn
              :trace-fn trace-fn
              :on-stop on-stop}]
    (stderr-drain! proc log-fn)
    (doto (Thread. ^Runnable #(reader-loop! conn) "grog-eca-reader")
      (.setDaemon true)
      (.start))
    conn))

;; ---------------------------------------------------------------------------
;; Public connection API
;; ---------------------------------------------------------------------------

(defonce ^:private !conn (atom nil))

(defn connected? [] (boolean @!conn))

(defn process []
  (:process @!conn))

(defn alive?
  "True if the ECA subprocess is running and we hold a connection."
  []
  (let [p (when-let [c @!conn] (:process c))]
    (and p (.isAlive ^Process p))))

(defn- check-conn!
  "Return the live connection or throw."
  []
  (or (when (alive?) @!conn)
      (throw (ex-info "ECA client not connected" {}))))

(defn send-request!
  "Send a JSON-RPC request `method`/`params` and wait for its response.
  Returns {:ok result} or {:error err}."
  [method params]
  (let [conn (check-conn!)
        id   (long (next-id! (:req-seq conn)))
        kid  (str id)
        p    (promise)
        _    (.put ^ConcurrentHashMap (:pending conn) kid p)]
    (try
      (write-frame! (:out conn) (:write-lock conn)
                    (cond-> {:jsonrpc "2.0" :id id :method method}
                      params (assoc :params params)))
      (trace-frame! conn :out (cond-> {:jsonrpc "2.0" :id id :method method}
                                params (assoc :params params)))
      (let [res (deref p rpc-timeout-ms ::timeout)]
        (if (= ::timeout res)
          {:error {:code -32000 :message (str "ECA request timeout: " method)}}
          (if-let [err (:error res)]
            {:error err}
            {:ok (:result res)})))
      (finally
        (.remove ^ConcurrentHashMap (:pending conn) kid)))))

(defn send-notify!
  "Fire-and-forget JSON-RPC notification."
  [method params]
  (let [conn (check-conn!)]
    (write-frame! (:out conn) (:write-lock conn)
                  (cond-> {:jsonrpc "2.0" :method method}
                    params (assoc :params params)))
    (trace-frame! conn :out (cond-> {:jsonrpc "2.0" :method method}
                              params (assoc :params params)))))

(declare disconnect!)

(defn connect!
  "Spawn `eca server`, run the `initialize`/`initialized` handshake, and store
  the connection. `workspace-folders` is a seq of {:uri ... :name ...}.

  Options:
    :event-handler  (fn [method params])   for notifications (chat/contentReceived …)
    :request-handler (fn [method params])  for server->client requests; may return
                                           {:result …} or {:error …}
    :on-stop        (fn [])                called when the child's stdout closes
    :log-fn         (fn [line])            stderr lines from eca
    :trace-fn       (fn [dir frame])       every JSON-RPC frame in/out; dir is :out or :in
    :eca-binary     binary name/path (default \"eca\")
    :args           extra args to `eca server` (e.g. [\"--config-file\" \"...\"])
    :env            extra env vars
    :cwd            working directory

  Returns the initialize response map on success, or throws."
  [workspace-folders & {:as opts}]
  (when @!conn
    (throw (ex-info "ECA client already connected; disconnect! first" {})))
  (let [conn (make-connection! opts)]
    ;; Register the connection before the handshake so send-request! / send-notify!
    ;; (which require a live connection) work during connect!.
    (reset! !conn conn)
    (try
      (let [init (send-request! "initialize"
                                {:processId (long (try (.pid (ProcessHandle/current)) (catch Exception _ 0)))
                                 :clientInfo {:name "grog" :version "0.1.0"}
                                 :capabilities {:codeAssistant {:chat true :rewrite false}}
                                 :workspaceFolders (vec workspace-folders)})]
        (if (:error init)
          (do (disconnect!)
              (throw (ex-info (str "ECA initialize failed: " (:error init)) {})))
          (do (send-notify! "initialized" {})
              init)))
      (catch Exception e
        (disconnect!)
        (throw e)))))

(defn disconnect!
  "Politely shut down (shutdown -> exit) and kill the child process."
  []
  (when-let [conn @!conn]
    (try (send-request! "shutdown" nil)
         (catch Exception _))
    (try (send-notify! "exit" {})
         (catch Exception _))
    (try (.destroy ^Process (:process conn))
         (catch Exception _))
    (reset! !conn nil))
  nil)

;; ---------------------------------------------------------------------------
;; Chat API
;; ---------------------------------------------------------------------------

(defn prompt!
  "Start/continue a chat. `message` is required; opts may include :chatId,
  :model, :agent, :variant, :trust, :contexts. Returns {:ok map} or {:error err}."
  ([message] (prompt! message {}))
  ([message {:keys [chatId model agent variant trust contexts]}]
   (send-request! "chat/prompt"
                  (cond-> {:message message}
                    chatId   (assoc :chatId chatId)
                    model    (assoc :model model)
                    agent    (assoc :agent agent)
                    variant  (assoc :variant variant)
                    (some? trust) (assoc :trust trust)
                    contexts (assoc :contexts contexts)))))

(defn stop!
  "Stop the running prompt for `chatId`."
  [chatId] (send-notify! "chat/promptStop" {:chatId chatId}))

(defn approve!
  "Approve a tool call (toolCallRun with manualApproval)."
  ([chatId toolCallId] (approve! chatId toolCallId {}))
  ([chatId toolCallId {:keys [save]}]
   (send-notify! "chat/toolCallApprove"
                 (cond-> {:chatId chatId :toolCallId toolCallId}
                   save (assoc :save save)))))

(defn reject!
  "Reject a tool call."
  [chatId toolCallId]
  (send-notify! "chat/toolCallReject" {:chatId chatId :toolCallId toolCallId}))

(defn steer! [chatId message]
  (send-notify! "chat/promptSteer" {:chatId chatId :message message}))

(defn selected-model! [model & {:keys [chatId variant]}]
  (send-notify! "chat/selectedModelChanged"
                (cond-> {:model model}
                  chatId  (assoc :chatId chatId)
                  variant (assoc :variant variant))))

(defn selected-agent! [agent & {:keys [chatId]}]
  (send-notify! "chat/selectedAgentChanged"
                (cond-> {:agent agent}
                  chatId (assoc :chatId chatId))))

(defn set-trust!
  "Persist trust (yolo) mode for `chatId`: when on, tool calls that would
  normally require manual approval are auto-accepted (deny rules still win).
  Applies immediately to subsequent tool calls in the active prompt."
  [chatId on?]
  (send-request! "chat/update" {:chatId chatId :trust (boolean on?)}))
