(ns grog.server
  "grog-server — headless JSON-RPC 2.0 endpoint over stdio (Phase 3).

  Transport is NDJSON: one JSON object per line; requests/responses on
  stdin/stdout, server-initiated notifications on stdout. The server runs the
  FULL local stack (ECA child, MCP, projects, keys, locks) through
  `grog.client.local`; the thin client holds none of it — the whole point of
  the split (doc/server-and-client.md §2).

  Client -> server methods (params keywordized JSON objects):

    open            {:project ..}                       -> session snapshot (with :id)
    close           {:id ..}                            -> nil
    sessions        {}                                  -> [snapshot ..]
    session         {:id ..}                            -> snapshot | nil
    connect         {:id ..}                            -> nil  (spawns the ECA child)
    prompt          {:id .. :text ..}                   -> nil  (queued on the worker)
    steer           {:id .. :text ..}                   -> nil
    stop            {:id ..}                            -> nil
    answer          {:id .. :approval-id .. :decision ..} -> nil
    set-model       {:id .. :model ..}                  -> nil
    set-trust       {:id .. :on ..}                     -> nil
    answer-question {:question-id .. :result ..}        -> nil

  Server -> client notifications:

    event    {:sessionId .. :event ..}  a stamped grog.chat envelope tagged
                                        with the session id for routing (the
                                        envelope's own :session is the project)
    question {:sessionId .. :question-id .. :prompt .. :options .. :allowFreeform? ..}
                                        asked via chat/askQuestion; the ECA
                                        reader waits up to `question-timeout-ms`
                                        for answer-question, then cancels — the
                                        wire version of the local wedge-safety
                                        rule (a missing client can't hang a turn)

  stdout is the RPC channel: on startup the Clojure process streams are
  redirected to stderr so no stray println can corrupt the stream. Slash
  command output reaches the GUI as `:line` events via the per-session console
  publisher (same buffer-until-flush semantics as
  `grog.ui.transcript/console-writer`)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [grog.chat :as chat]
            [grog.client :as client]
            [grog.client.local :as local]))

(def ^:private question-timeout-ms
  "Bound on the ECA reader wait for a remote chat/askQuestion answer:
  wedge-safety over the wire."
  600000)

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
    (.write w (json/generate-string obj))
    (.write w "\n")
    (.flush w)))

(defn- notify! [^java.io.Writer w method params]
  (write-line! w {:jsonrpc "2.0" :method method :params params}))

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

(defn- open-session!
  "open + wire the event forwarder. The console publisher resolves the state
  lazily through the :on-state hook (open! creates the state)."
  [^java.io.Writer w questions {:keys [project]}]
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
           (notify! w "question"
                    (assoc (dissoc ev :answer)
                           :sessionId id
                           :questionId qid))
           (let [res (deref p question-timeout-ms ::timeout)]
             (swap! questions dissoc qid)
             (answer-fn (if (= ::timeout res)
                          {:cancelled true :answer nil}
                          res))))
         (notify! w "event" (assoc ev :sessionId id)))))
    (client/session id)))

(defn- handle-request
  "Dispatch one JSON-RPC request; write the response (or error)."
  [^java.io.Writer w questions {:keys [id method params]}]
  (let [params (or params {})
        respond! (fn [result error]
                   (write-line! w (cond-> {:jsonrpc "2.0" :id id}
                                    (some? result) (assoc :result result)
                                    error (assoc :error error))))]
    (try
      (let [result
            (case method
              "open" (open-session! w questions params)
              "close" (do (client/close! (:id params)) nil)
              "sessions" (client/sessions)
              "session" (client/session (:id params))
              "connect" (do (client/connect! (:id params)) nil)
              "prompt" (do (client/prompt! (:id params) (:text params)) nil)
              "steer" (do (client/steer! (:id params) (:text params)) nil)
              "stop" (do (client/stop! (:id params)) nil)
              "answer" (do (client/answer! (:id params)
                                           {:approval-id (:approval-id params)
                                            :decision (keyword (:decision params))})
                           nil)
              "set-model" (do (client/set-model! (:id params) (:model params)) nil)
              "set-trust" (do (client/set-trust! (:id params) (boolean (:on params))) nil)
              "answer-question"
              (do (when-let [p (get @questions (:question-id params))]
                    (deliver p (or (:result params) {:cancelled true :answer nil})))
                  nil)
              (throw (ex-info (str "unknown method: " method) {:method method})))]
        ;; JSON-RPC: a null result is still a result — always respond
        (respond! (if (nil? result) nil result) nil))
      (catch Throwable e
        (respond! nil {:code -32603
                       :message (or (.getMessage e) (str (class e)))
                       :data {:method method}})))))

;; --- main ------------------------------------------------------------------

(defn -main
  "Run the server: stdin = JSON-RPC requests (NDJSON), stdout = responses +
  event/question notifications. stdin EOF = client gone: detach every session
  (drop ECA children, release project locks) and exit."
  [& _]
  ;; stdout is the RPC channel — send the process streams to stderr so stray
  ;; printlns (Java or Clojure) land in the log instead of corrupting frames.
  (alter-var-root #'*out* (constantly *err*))
  (client/set-impl! (local/->LocalClient))
  (let [w (out-writer)
        questions (atom {})
        rdr (java.io.BufferedReader. (java.io.InputStreamReader. System/in "UTF-8"))]
    (try
      (loop []
        (when-let [line (.readLine rdr)]
          (when-not (str/blank? line)
            (try
              (handle-request w questions (json/parse-string line true))
              (catch Throwable e
                ;; a poisoned line must not kill the server
                (binding [*out* *err*]
                  (println "[grog-server] bad request:" (.getMessage e))))))
          (recur)))
      (finally
        (try
          (doseq [{:keys [id]} (client/sessions)] (client/close! id))
          (catch Throwable _ nil))
        (System/exit 0)))))
