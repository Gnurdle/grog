(ns grog.server
  "grog-server — headless JSON-RPC 2.0 endpoint over stdio AND a Unix-domain
  socket.

  Transport is NDJSON: one JSON object per line; requests/responses on the
  inbound/outbound channel, server-initiated notifications broadcast to every
  attached client. The server runs the FULL local stack (ECA child, MCP,
  projects, keys, locks) through `grog.client.local`; the thin clients hold
  none of it.

  Two transports, one hub:

    stdio        — a client spawns this process as a child and speaks over its
                   stdin/stdout (how the desktop app runs it).
    unix socket  — `GROG_SERVER_SOCKET` (or `$XDG_RUNTIME_DIR/grog-$USER.sock`)
                   is bound and MANY clients may attach at once. Notifications
                   fan out to all of them; responses go only to the caller.

  The socket is bound best-effort in stdio mode too, so a client that spawned a
  server can also host another. A bind failure (another server already owns the
  path) is logged, never fatal.

  Client -> server methods (params keywordized JSON objects):

    open            {:project ..}                       -> session snapshot (with :id)
    close           {:id ..}                            -> nil
    sessions        {}                                  -> [snapshot ..]
    projects        {}                                  -> [{:name .. :description ..} ..]
    session         {:id ..}                            -> snapshot | nil
    connect         {:id ..}                            -> nil  (spawns the ECA child)
    prompt          {:id .. :text ..}                   -> nil  (queued on the worker)
    steer           {:id .. :text ..}                   -> nil
    stop            {:id ..}                            -> nil
    answer          {:id .. :approval-id .. :decision ..} -> nil
    set-model       {:id .. :model .. :source? ..}      -> nil
                                        (:source is the picker transport —
                                        `openrouter`/`ollama` — so an id is
                                        qualified exactly, never mistaken for a
                                        native provider of the same name)
    set-trust       {:id .. :on ..}                     -> nil
    models          {:source? .. :force? ..}            -> {:eca [..]
                                                            :openrouter [..]
                                                            :ollama [..]
                                                            :loading [..]}
                                        the settings-dialog model catalogue.
                                        Answers from cache; an uncached (or
                                        :force'd) remote source is fetched on a
                                        BACKGROUND thread and arrives later as a
                                        `models` notification, so a slow network
                                        never blocks this request loop.
    answer-question {:question-id .. :result ..}        -> nil
    projects        {}                                  -> [{:name .. :description ..} ..]
    create-project  {:name .. :description ..}          -> nil

  `open` is IDEMPOTENT PER PROJECT: if a session for that project already
  exists it is returned as-is, so every client shares the one session the server
  owns. Creating a second session for a project would double the ECA child and
  the working set for no reason.

  Server -> client notifications (broadcast):

    event    {:sessionId .. <stamped envelope keys>}  a stamped grog.chat
                                        envelope tagged with the session id
    question {:sessionId .. :question-id .. :prompt .. :options .. :allowFreeform? ..}
                                        asked via chat/askQuestion; the ECA
                                        reader waits up to `question-timeout-ms`
                                        for answer-question, then cancels — the
                                        wire version of the local wedge-safety
                                        rule (a missing client can't hang a turn)
    models   {:source .. :models [..]}  a background model-catalogue refresh
                                        completing (see `models` above)

  stdout is the RPC channel in stdio mode: on startup the Clojure process
  streams are redirected to stderr so no stray println can corrupt the stream.
  In socket mode stdout is free, and everything (logs included) goes to stderr."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [grog.chat :as chat]
            [grog.client :as client]
            [grog.client.local :as local]
            [grog.config :as config]
            [grog.mcp-http :as mcp-http]
            [grog.models :as models]
            [grog.projects :as projects]
            [grog.soul :as soul])
  (:import (java.io BufferedReader BufferedWriter File InputStreamReader
                    OutputStreamWriter PrintWriter)
           (java.net StandardProtocolFamily UnixDomainSocketAddress)
           (java.nio.channels Channels ServerSocketChannel SocketChannel)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files Paths)
           (java.sql Connection DriverManager ResultSet)
           (com.github.javakeyring Keyring)))

(def ^:private question-timeout-ms
  "Bound on the ECA reader wait for a remote chat/askQuestion answer:
  wedge-safety over the wire."
  600000)

(defn- jni-selftest
  "Exercise the two JNI-backed libraries the server links but no normal RPC
  reaches: sqlite-jdbc (org.sqlite.JDBC loads a native lib on first connection)
  and java-keyring (com.github.javakeyring, JNA -> OS secret service).

  Exposed as the `debug/jni` method so a freshly built (native) image can prove
  its JNI works instead of failing at first real use. JSON-serialisable map."
  []
  {:sqlite
   (try
     (Class/forName "org.sqlite.JDBC")
     (with-open [^Connection c (DriverManager/getConnection "jdbc:sqlite::memory:")]
       (let [st (.createStatement c)]
         (.execute st "create table t(x integer)")
         (.execute st "insert into t values (42)")
         (let [^ResultSet rs (.executeQuery st "select x from t")]
           (when (.next rs) {:ok true :value (.getInt rs 1)}))))
     (catch Throwable e {:error (str (class e) ": " (.getMessage e))}))

   :keyring
   (let [f (future
             (try
               (with-open [^Keyring kr (Keyring/create)]
                 {:ok true :llm-key-present (some? (.getPassword kr "grog" "LLM_API_KEY"))})
               (catch Throwable e {:error (str (class e) ": " (.getMessage e))})))]
     (deref f 5000 {:error "timeout (keyring backend did not respond)"}))})

(defn- log! [& xs]
  (binding [*out* *err*]
    (apply println xs)))

;; --- stdout writer ---------------------------------------------------------

(defn- out-writer
  "Buffered NDJSON writer on stdout."
  ^java.io.Writer []
  (java.io.PrintWriter. (java.io.BufferedWriter.
                         (java.io.OutputStreamWriter. System/out "UTF-8"))))

(defn- write-line!
  "Write one JSON-RPC object as a line. Locking on the writer keeps
  interleaved notifications atomic — a partial line would poison the stream."
  [^java.io.Writer w obj]
  (locking w
    (.write w ^String (json/generate-string obj))
    (.write w "\n")
    (.flush w)))

(defn- notify!
  [^java.io.Writer w method params]
  (write-line! w {:jsonrpc "2.0" :method method :params params}))

;; --- connection hub --------------------------------------------------------
;;
;; One hub per server process. Every attached transport (stdio writer, or one
;; writer per socket connection) registers here. Notifications broadcast to
;; all of them; request responses are written straight back to their origin.

(defn- make-hub
  "Returns {:add! :remove! :broadcast!}. Writers are compared by identity, so
  each connection is distinct even if two share a writer class."
  []
  (let [!conns (atom #{})]
    {:add!       (fn [w] (swap! !conns conj w) w)
     :remove!    (fn [w] (swap! !conns disj w))
     :broadcast! (fn [method params]
                   (doseq [w @!conns]
                     (try (notify! w method params)
                          (catch Throwable _ nil))))}))

;; --- console publisher -----------------------------------------------------

(defn- console-publisher
  "Server-side stand-in for the GUI's console-writer: buffers *out*/*err*
  output for one turn and emits it as a `:line` event on flush/close, so slash
  command output lands in the remote transcript through the stream."
  ^java.io.Writer [state-atom]
  (let [sb (StringBuilder.)
        emit! (fn []
                (let [s (str sb)]
                  (.setLength sb 0)
                  (when-let [state @state-atom]
                    (when (seq (str/trim s))
                      (chat/publish! state {:type :line :text s})))))]
    (proxy [java.io.Writer] []
      (write
        ([x]
         (.append sb (str x)))
        ([x off len]
         (.append sb (if (string? x)
                       (subs ^String x (int off) (int (+ (long off) (long len))))
                       (String. ^chars x (int off) (int len))))))
      (flush [] (emit!))
      (close [] (emit!)))))

;; --- sessions --------------------------------------------------------------

(defn- session-by-project
  "The existing session snapshot for `project`, or nil. Sessions are
  server-global, so an attaching client finds the one already running."
  [project]
  (some #(when (= (str project) (str (:project %))) %) (client/sessions)))

(defn- open-session!
  "open + wire the broadcast event forwarder. The console publisher resolves the
  state lazily through the :on-state hook (open! creates the state).

  `questions` is the server-global registry of pending chat/askQuestion
  promises, keyed by the question id that goes out on the wire; the ECA reader
  thread blocks (inside the subscriber) on the promise until `answer-question`
  arrives or `question-timeout-ms` elapses."
  [broadcast! questions {:keys [project]}]
  (let [st (atom nil)
        opened (client/open! {:project project
                              :on-state (fn [state] (reset! st state))
                              :console (fn [] (console-publisher st))})
        id (:id opened)]
    (client/subscribe!
     id
     (fn [ev]
       (if (= :question (:type ev))
         (let [qid (str (java.util.UUID/randomUUID))
               answer-fn (:answer ev)
               p (promise)]
           (swap! questions assoc qid p)
           (broadcast! "question"
                       (assoc (dissoc ev :answer)
                              :sessionId id
                              :questionId qid))
           (let [res (deref p question-timeout-ms ::timeout)]
             (swap! questions dissoc qid)
             (answer-fn (if (= ::timeout res)
                          {:cancelled true :answer nil}
                          res))))
         (broadcast! "event" (assoc ev :sessionId id)))))
    (client/session id)))

;; --- request handling ------------------------------------------------------

(defn- handle-request
  "Dispatch one JSON-RPC request. `send!` writes the response back to the
  originating connection; `broadcast!` fans notifications out to all."
  [send! broadcast! questions {:keys [id method params]}]
  (let [params (or params {})
        respond! (fn [result error]
                   (send! (cond-> {:jsonrpc "2.0" :id id}
                            (some? result) (assoc :result result)
                            error (assoc :error error))))]
    (try
      (let [result
            (case method
              "open" (let [snap (or (session-by-project (:project params))
                                    (open-session! broadcast! questions params))]
                       ;; banner = the startup snark line; the renderer seeds
                       ;; it as the first transcript line
                       (assoc snap :banner (soul/startup-snark-line)))
              "close" (do (client/close! (:id params)) nil)
              "sessions" (client/sessions)
              "projects" (mapv (fn [n]
                                 (let [m (projects/manifest-for n)
                                       d (some-> (:description m) str str/trim not-empty)]
                                   (cond-> {:name n} d (assoc :description d))))
                               (projects/list-project-names))
              "create-project" (do (projects/create-project! (:name params)
                                                             (:description params))
                                   nil)
              "session" (client/session (:id params))
              "connect" (do (client/connect! (:id params)) nil)
              "prompt" (do (client/prompt! (:id params) (:text params)) nil)
              "steer" (do (client/steer! (:id params) (:text params)) nil)
              "stop" (do (client/stop! (:id params)) nil)
              "answer" (do (client/answer! (:id params)
                                           {:approval-id (:approval-id params)
                                            :decision (keyword (:decision params))})
                           nil)
              ;; Model catalogue for the settings picker. Answers from cache and
              ;; refreshes OpenRouter/Ollama on a BACKGROUND thread, because a
              ;; network fetch here would block this single request loop (and so
              ;; every other client) for up to the HTTP timeout. The refreshed
              ;; list arrives as a `models` broadcast. `:source eca` is ECA's own
              ;; catalogue (populated on connect) and needs no fetch.
              "models" (let [src (keyword (or (:source params) "eca"))
                             known (models/catalogue)]
                         (when (and (#{:openrouter :ollama} src)
                                    (or (boolean (:force params))
                                        (nil? (get known src))))
                           (models/fetch-async!
                            src
                            (fn [s ms]
                              (broadcast! "models" {:source (name s) :models ms}))))
                         known)
              "set-model" (do (client/set-model! (:id params) (:model params)
                                                 (:source params))
                              nil)
              "set-trust" (do (client/set-trust! (:id params) (boolean (:on params))) nil)
              "answer-question"
              (do (when-let [p (get @questions (:question-id params))]
                    (deliver p (or (:result params) {:cancelled true :answer nil})))
                  nil)
              "debug/jni" (jni-selftest)
              (throw (ex-info (str "unknown method: " method) {:method method})))]
        ;; JSON-RPC: a null result is still a result — always respond
        (respond! (if (nil? result) nil result) nil))
      (catch Throwable e
        (respond! nil {:code -32603
                       :message (or (.getMessage e) (str (class e)))
                       :data {:method method}})))))

;; --- transports ------------------------------------------------------------

(defn- serve-stdio!
  "Serve the stdio transport until stdin EOF, then detach every
  session and return. `send!` is the stdout writer; notifications go through
  the hub."
  [hub questions]
  (let [w (out-writer)
        rdr (java.io.BufferedReader. (java.io.InputStreamReader. System/in "UTF-8"))
        send! (fn [obj] (write-line! w obj))]
    ((:add! hub) w)
    (try
      (loop []
        (when-let [line (.readLine rdr)]
          (when-not (str/blank? line)
            (try
              (handle-request send! (:broadcast! hub) questions
                              (json/parse-string line true))
              (catch Throwable e
                (log! "[grog-server] bad request:" (.getMessage e)))))
          (recur)))
      (finally
        ((:remove! hub) w)))))

(defn- serve-connection!
  "Read NDJSON lines from one socket connection until it closes. The connection
  joins the hub for the whole read loop, so it receives every notification."
  [^SocketChannel ch hub questions]
  (let [w (PrintWriter.
           (BufferedWriter.
            (OutputStreamWriter. (Channels/newOutputStream ch) StandardCharsets/UTF_8)))
        rdr (BufferedReader.
             (InputStreamReader. (Channels/newInputStream ch) StandardCharsets/UTF_8))
        send! (fn [obj] (write-line! w obj))]
    ((:add! hub) w)
    (try
      (loop []
        (when-let [line (.readLine rdr)]
          (when-not (str/blank? line)
            (try
              (handle-request send! (:broadcast! hub) questions
                              (json/parse-string line true))
              (catch Throwable e
                (log! "[grog-server] bad request:" (.getMessage e)))))
          (recur)))
      (catch Throwable _ nil)
      (finally
        ((:remove! hub) w)
        (try (.close ch) (catch Throwable _ nil))))))

(defn- serve-socket!
  "Blocking accept loop. Each accepted connection gets its own thread."
  [^ServerSocketChannel ssc hub questions]
  (loop []
    (let [ch (.accept ssc)]
      (doto (Thread. #(serve-connection! ch hub questions))
        (.setDaemon true)
        (.start))
      (recur))))

(defn- server-bind
  "TCP interface to bind: GROG_SERVER_BIND, else config :server :bind, else
  0.0.0.0 (internal network; no auth)."
  ^String []
  (or (let [s (System/getenv "GROG_SERVER_BIND")] (when-not (str/blank? s) s))
      (get-in (config/grog) [:server :bind])
      "0.0.0.0"))

(defn- server-port
  "TCP port: GROG_SERVER_PORT, else config :server :tcp-port, else 9640.
  A KNOWN port — that is the point: other machines and the systemd unit all
  find the SAME server on it."
  ^long []
  (long (or (some-> (System/getenv "GROG_SERVER_PORT") parse-long)
            (get-in (config/grog) [:server :tcp-port])
            9640)))

(defn- bind-tcp!
  "Open + bind the TCP listener (INET family). Returns the channel; the accept
  loop and per-connection hub are family-agnostic, so nothing else changes."
  ^ServerSocketChannel [^String host ^long port]
  (let [ssc (ServerSocketChannel/open)]
    (.bind ssc (java.net.InetSocketAddress. host (int port)))
    ssc))

;; --- main ------------------------------------------------------------------

(defn- mcp-base-port
  "The MCP endpoint base port: env override, else config, else nil."
  []
  (some-> (or (System/getenv "GROG_MCP_BASE_PORT")
              (get-in (config/grog) [:server :mcp-base-port]))
          str parse-long))

(defn- start-mcp-http! []
  ;; The Streamable-HTTP tool endpoint belongs to the standalone daemon. A
  ;; client-owned spine (the desktop app's backend) does not want it at all: its
  ;; ECA gets the tools from the generated config, which spawns the MCP jar per
  ;; server id. GROG_MCP_HTTP=0 turns it off regardless of config, so a packaged
  ;; client can be explicit instead of relying on the config file it may not own.
  (when (and (not= false (get-in (config/grog) [:server :mcp-http]))
             (not (contains? #{"0" "false" "no"} (some-> (System/getenv "GROG_MCP_HTTP")
                                                          clojure.string/lower-case))))
    (mcp-http/start! (cond-> {}
                       (mcp-base-port) (assoc :base-port (mcp-base-port))))))

(defn -main
  "Run the server.

  Two modes, chosen by whether a socket path is configured:

    socket-daemon (GROG_SERVER_SOCKET set, or GROG_SERVER_DAEMON=1)
      — bind the socket REQUIRED, no stdio, stay alive until killed.
    stdio (default) — serve stdin/stdout until EOF, and bind the socket
      best-effort so an attaching client can join the same server.

  stdin EOF in stdio mode = the spawning client is gone: detach every session
  (drop ECA children, release project locks) and exit."
  [& _]
  ;; stdout is the RPC channel in stdio mode — route the Clojure process streams
  ;; to stderr so stray printlns (Java or Clojure) can't corrupt frames.
  (alter-var-root #'*out* (constantly *err*))
  (client/set-impl! (local/->LocalClient))
  (try (start-mcp-http!)
       (catch Throwable e (log! "[grog-server] mcp-http start failed:" (.getMessage e))))
  (let [hub (make-hub)
        questions (atom {})
        daemon? (or (let [s (System/getenv "GROG_SERVER_SOCKET")] (not (str/blank? s)))
                    (= "1" (System/getenv "GROG_SERVER_DAEMON")))
        host (server-bind)
        port (server-port)
        disable-socket? (= "1" (System/getenv "GROG_SERVER_NO_SOCKET"))
        ssc (when-not disable-socket?
              (try
                (let [c (bind-tcp! host port)]
                  (log! "[grog-server] listening on tcp" host port)
                  c)
                (catch Throwable e
                  (if daemon?
                    (do (log! "[grog-server] FATAL: socket bind failed:" (.getMessage e))
                        (System/exit 2))
                    (do (log! "[grog-server] socket bind skipped:" (.getMessage e))
                        nil)))))]
    ;; clean up the socket file + sessions on the way out
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. (fn []
                (try (doseq [{:keys [id]} (client/sessions)] (client/close! id))
                     (catch Throwable _ nil))
                (try (mcp-http/stop!) (catch Throwable _ nil))
                (when ssc
                  (try (.close ^ServerSocketChannel ssc) (catch Throwable _ nil))))))
    (if daemon?
      ;; socket daemon: accept loop owns the main thread; no stdio at all.
      (serve-socket! ssc hub questions)
      ;; stdio mode: stdin owns the main thread; the socket (if bound)
      ;; is served on a daemon thread that dies with the process.
      (do
        (when ssc
          (doto (Thread. #(serve-socket! ssc hub questions))
            (.setDaemon true)
            (.start)))
        (try
          (serve-stdio! hub questions)
          (finally
            (try (doseq [{:keys [id]} (client/sessions)] (client/close! id))
                 (catch Throwable _ nil))
            (try (mcp-http/stop!) (catch Throwable _ nil))
            (System/exit 0)))))))
