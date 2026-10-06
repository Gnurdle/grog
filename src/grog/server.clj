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
    providers       {}                                  -> {:providers [catalogue ..]
                                                            :current {:url .. :provider ..}}
                                        the shipped provider catalogue (grog.providers)
                                        plus what :llm currently points at.
    set-provider    {:id? .. :url? .. :model? ..}       -> {:url .. :model ..
                                                            :restart-required true}
                                        write :llm :url (+ :model) for a catalogue
                                        :id (or an explicit :url). GLOBAL change,
                                        applied on the NEXT start.
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
    running  {:sessionId .. :value ..}  a turn STARTED or FINISHED. The server
                                        owns the turn lifecycle; clients use
                                        this for the busy indicator (tab dot /
                                        status bar) instead of inferring it.

  stdout is the RPC channel in stdio mode: on startup the Clojure process
  streams are redirected to stderr so no stray println can corrupt the stream.
  In socket mode stdout is free, and everything (logs included) goes to stderr."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [grog.chat :as chat]
            [grog.bootstrap :as bootstrap]
            [grog.client :as client]
            [grog.client.local :as local]
            [grog.config :as config]
            [grog.docs :as docs]
            [grog.mcp-http :as mcp-http]
            [grog.models :as models]
            [grog.projects :as projects]
            [grog.providers :as providers]
            [grog.soul :as soul]
            [grog.version :as version])
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

(def ^:private ^Class char-array-class
  "Class of a primitive char array - `write(char[])` is the third one-arg
  overload a Writer proxy cannot tell apart from the others."
  (Class/forName "[C"))

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
        ;; java.io.Writer has TWO one-arg overloads - write(int c) and
        ;; write(String s) - and a proxy dispatches on ARITY alone, so the int
        ;; has to be decoded by hand. Clojure's println separates its arguments
        ;; with write(32): with the naive (str x) every multi-arg println in a
        ;; slash command reached the transcript with the separator rendered as
        ;; the literal text "32" (e.g. "·  32BRAVE_SEARCH_API32— 32Brave …").
        ([x]
         (.append sb (cond
                       (integer? x) (char (int x))
                       (string? x) ^String x
                       (instance? char-array-class x) (String. ^chars x)
                       :else (str x))))
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

(defn- eca-config-provider-urls
  "Provider base URLs ECA already knows, read from ECA's OWN config
  (`${XDG_CONFIG_HOME:-~/.config}/eca/config.json`).

  This exists because grog is not the only home of a key: ECA can hold a
  provider (and its key) that grog never sees, so a key missing from grog's
  secret store does NOT mean grog cannot reach a model — a working developer box
  is exactly that case, and misreading it would hide a live transcript behind the
  onboarding page."
  []
  (try
    (let [base (or (some-> (System/getenv "XDG_CONFIG_HOME") str str/trim not-empty)
                   (str (System/getProperty "user.home") "/.config"))
          f (File. base "eca/config.json")]
      (when (.exists f)
        (->> (:providers (json/parse-string (slurp f :encoding "UTF-8") true))
             vals
             (keep #(some-> (:url %) str str/trim not-empty)))))
    (catch Throwable _ nil)))

(def ^:private local-url-re
  #"(?i)(localhost|127\.0\.0\.1|\[::1\]|0\.0\.0\.0)")

(defn- llm-configured?
  "True when grog is plausibly able to reach a model: a model AND a URL are
  configured, and a key is available from somewhere grog can see.

  A key counts if it resolves in grog's own store, the endpoint is local (no key
  needed), `:llm :api-key` is explicitly `false`, or ECA's own config already
  declares a provider for the same URL. Requiring a GROG-side key would be wrong:
  ECA can hold the key itself, and then a working setup would be misread as
  unconfigured and hidden behind the 'Job 1' page.

  Without a model or URL, no turn can work at all — that is the real 'Job 1'.
  Every accessor is wrapped: `llm-url`/`eca-model` THROW when unset, and
  `llm-api-key` may consult the OS keyring. This must never throw."
  []
  (let [url   (try (some-> (config/llm-url) str str/trim not-empty) (catch Throwable _ nil))
        model (try (some-> (config/eca-model) str str/trim not-empty) (catch Throwable _ nil))
        norm  (fn [u] (str/replace (str/lower-case (str u)) #"/+$" ""))]
    (boolean
     (and url model
          (or (try (some-> (config/llm-api-key) str str/trim not-empty) (catch Throwable _ nil))
              (false? (get-in (config/grog) [:llm :api-key]))
              (boolean (re-find local-url-re (str url)))
              (some #(= (norm %) (norm url)) (eca-config-provider-urls)))))))

(defn- bootstrap-info
  "The `:bootstrap` block the client needs to decide between the onboarding page
  and the normal splash.

  `:needed?` is true when no LLM is reachable (the real 'Job 1'), OR when this
  launch was started by an EXPLICIT onboarding request — a factory reset asks for
  the getting-started landing even though ECA's own config (which a reset does
  NOT touch) may still hold a working key. `:configured?` reports the raw
  reachability so that landing can say so honestly instead of pretending there is
  no brain. `:docs-dir` lets the offline page point at the shipped docs."
  []
  (let [configured? (llm-configured?)]
    {:needed? (or (bootstrap/onboarding-requested-this-session?) (not configured?))
     :configured? configured?
     :docs-dir (some-> (docs/docs-dir) .getPath)}))

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
                       ;; Remember the SELECTED project as last-used — the
                       ;; deterministic signal that survives a relaunch (and a
                       ;; crash). An explicit close refines it via
                       ;; `disconnect-fn!`; a shutdown does not (see the guard).
                       (when-let [p (some-> (:project snap) str str/trim not-empty)]
                         (projects/write-last-used! p))
                       ;; banner = the startup snark line; the renderer seeds
                       ;; it as the first transcript line. :version rides along
                       ;; so every client can show which build it is talking to
                       ;; (nil when the running spine carries no build stamp).
                       (assoc snap
                              :banner (soul/startup-snark-line)
                              :version (version/label)
                              ;; which model sources the picker should offer:
                              ;; eca + whatever provider :llm :url implies +
                              ;; ollama. Never a hard-coded provider list.
                              :model-sources (models/model-sources)
                              ;; whether an LLM is actually reachable (else the
                              ;; client shows the Job 1 onboarding page) + where
                              ;; the docs are.
                              :bootstrap (bootstrap-info)))
              ;; Which project the client should OPEN at launch, resolved by the
              ;; spine (so no client hard-codes a project name — the renderer
              ;; used to open a literal "grog", which is the developer's own
              ;; project and exists on nobody else's machine). On a genuinely
              ;; fresh install this seeds the getting-started project.
              "startup" (merge (bootstrap/startup-info)
                               {:bootstrap (bootstrap-info)})
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
              ;; :contexts is ECA's ChatContext list (inline images as
              ;; {type image mediaType base64}, files as {type file path}, …).
              "prompt" (do (client/prompt! (:id params) (:text params) (:contexts params)) nil)
              "steer" (do (client/steer! (:id params) (:text params)) nil)
              "stop" (do (client/stop! (:id params)) nil)
              "answer" (do (client/answer! (:id params)
                                           {:approval-id (:approval-id params)
                                            :decision (keyword (:decision params))})
                           nil)
              ;; Model catalogue for the settings picker. Answers from cache and
              ;; refreshes the source on a BACKGROUND thread, because a network
              ;; fetch here would block this single request loop (and so every
              ;; other client) for up to the HTTP timeout. The refreshed list
              ;; arrives as a `models` broadcast. `:source eca` is ECA's own
              ;; catalogue (populated on connect) and needs no fetch.
              ;; Any source `models` knows how to fetch is allowed — that is the
              ;; configured provider by name, not just openrouter/ollama.
              "models" (let [src (keyword (or (:source params) "eca"))
                             known (models/catalogue)]
                         (when (or (boolean (:force params))
                                   (nil? (get known src)))
                           (models/fetch-async!
                            src
                            (fn [s ms]
                              (broadcast! "models" {:source (name s) :models ms}))))
                         known)
              ;; The picker sends a PLACE ("local"/"remote"); qualification needs
              ;; a PROVIDER. Translate here, where the config is known — losing
              ;; that signal made remote picks fall through to the ollama
              ;; catalogue whenever the id also existed locally.
              "set-model" (do (client/set-model! (:id params) (:model params)
                                                 (models/picker-source->provider
                                                  (:source params)))
                              nil)
              "set-trust" (do (client/set-trust! (:id params) (boolean (:on params))) nil)
              ;; The settings provider picker. `providers` is the shipped
              ;; catalogue (grog.providers), plus what :llm currently points at.
              ;; `set-provider` writes :llm :url (+ :model) into grog.edn — a
              ;; GLOBAL change, applied on the next start (config is snapshotted
              ;; per session); the client is told that and says so.
              "providers" (let [llm (models/llm-config)]
                            {:providers (providers/entries)
                             :current {:url (:url llm) :model (:model llm)
                                       :provider (models/provider-prefix-for-url (:url llm))}})
              "set-provider"
              (let [{:keys [id url model]} params
                    entry (when id (providers/by-id id))
                    new-url (or url (:url entry))
                    new-model (or model (:sample entry))]
                (when (str/blank? (str new-url))
                  (throw (ex-info "set-provider needs :id or :url" {:id id})))
                (models/save-fields! (cond-> {:url new-url}
                                       (and new-model (not (str/blank? (str new-model))))
                                       (assoc :model new-model)))
                (let [llm (models/llm-config)]
                  (assoc llm
                         :provider (models/provider-prefix-for-url (:url llm))
                         :restart-required true)))
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
                (local/mark-shutting-down!)
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
            (local/mark-shutting-down!)
            (try (doseq [{:keys [id]} (client/sessions)] (client/close! id))
                 (catch Throwable _ nil))
            (try (mcp-http/stop!) (catch Throwable _ nil))
            (System/exit 0)))))))
