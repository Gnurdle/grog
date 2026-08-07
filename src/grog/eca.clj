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

(defn- next-id! ^long [^AtomicLong al] (.incrementAndGet al))

(defn- pending-key [rid]
  (cond (number? rid) (str (long rid))
        (string? rid) rid
        :else nil))

(defn- respond! [^OutputStreamWriter out lock id result error]
  (write-frame! out lock
                (cond-> {:jsonrpc "2.0" :id id}
                  (some? error) (assoc :error error)
                  (some? result) (assoc :result result))))

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
        (respond! (:out conn) (:write-lock conn) (long id) result error))

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
            (try (handle-frame! conn frame)
                 (catch Exception e
                   (when-let [lf (:log-fn conn)]
                     (lf (str "eca handle error: " (.getMessage e)))))))
          (recur))))))

(defn- make-connection!
  "Spawn `eca server` and wire up streams. Does NOT handshake."
  [opts]
  (let [eca-binary      (or (:eca-binary opts) "eca")
        args            (:args opts)
        env             (:env opts)
        cwd             (:cwd opts)
        log-fn          (:log-fn opts)
        on-stop         (:on-stop opts)
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
