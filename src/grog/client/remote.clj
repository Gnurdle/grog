(ns grog.client.remote
  "Remote adapter: implements `grog.client/Client` by speaking NDJSON
  JSON-RPC to a `grog-server` process (see `grog.server`).

  The GUI is untouched by remote mode: `open!` returns a LOCAL `grog.chat`
  state map whose atoms form a *shadow* of the server-side session, kept in
  sync from incoming `event` notifications (status/trust/model from events;
  running?/usage optimistic at call time — where the local adapter sets them).
  The status bar, tab dots, and subscriber all bind those atoms unchanged.
  Swapping local for remote is therefore an install call.

  Non-serializable opts (`:console`, `:trace-fn`, `:on-state`) are dropped at
  the boundary — the server owns its console publisher and tracer; slash
  output comes back as `:line` events.

  Question round trip mirrors local wedge-safety: the event's `:answer`
  callback is only honored while the fan-out is still running; later is too
  late and cancels — so the server's bounded wait can never hang.

  Process lifecycle: spawned lazily on first call, stderr drained to our
  stderr (server logs stay visible), stdin EOF / listener death marks the
  connection dead — subsequent calls throw instead of silently respawning
  (a respawn could double-claim project locks)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [grog.chat :as chat]
            [grog.client :as client])
  (:import (java.io BufferedReader File InputStreamReader)))

(def ^:private call-timeout-ms 300000)

(defonce ^:private !sessions (atom {}))   ; id -> {:state .. :subs ..}
(defonce ^:private !conn (atom nil))      ; connection map or {:dead <err>}
(def ^:private !cmd* (atom nil))
(def ^:private !dir* (atom nil))

;; --- process plumbing ------------------------------------------------------

(defn- drain-stderr!
  "Copy the server's stderr to ours, line by line, so its logs stay visible."
  [^Process p]
  (future
    (try
      (with-open [r (BufferedReader. (InputStreamReader. (.getErrorStream p) "UTF-8"))]
        (loop []
          (when-let [line (.readLine r)]
            (binding [*out* *err*]
              (println "[grog-server]" line))
            (recur))))
      (catch Throwable _ nil))))

(declare handle-notification!)

(defn- listener!
  "Read stdout lines forever: responses resolve pending promises; server
  notifications (event / question) dispatch. EOF = server gone."
  [{:keys [^BufferedReader in pending] :as conn}]
  (future
    (try
      (loop []
        (when-let [line (.readLine in)]
          (when-not (str/blank? line)
            (try
              (let [msg (json/parse-string line true)]
                (if-let [id (:id msg)]
                  (when-let [p (get @pending id)]
                    (deliver p msg))
                  (handle-notification! conn msg)))
              (catch Throwable e
                (binding [*out* *err*]
                  (println "[grog-client] bad server frame:" (.getMessage e))))))
          (recur)))
      (catch Throwable _ nil))
    ;; EOF / crash: fail everything in flight and mark dead
    (let [err (ex-info "grog-server disconnected" {})]
      (swap! !conn (fn [c] (if (and c (not (:dead c)))
                             (do (doseq [p (vals @pending)]
                                   (deliver p {:error {:message "grog-server disconnected"}}))
                                 {:dead err})
                             c)))
      (doseq [[_ {:keys [state]}] @!sessions]
        (when state
          (reset! (:connected state) false)
          (reset! (:running? state) false)
          (chat/publish! state {:type :line
                                :text "[grog] grog-server disconnected"}))))))

(defn- ensure-conn!
  "The live connection, spawning the server process on first use."
  [cmd dir]
  (let [c @!conn]
    (cond
      (:dead c) (throw (:dead c))
      c c
      :else
      (locking !conn
        (or (let [c2 @!conn]
              (when (and c2 (not (:dead c2))) c2))
            (let [pb (ProcessBuilder. ^java.util.List (vec cmd))]
              (when dir (.directory pb (File. (str dir))))
              ;; this child is self-managed: it speaks stdio and must NOT claim
              ;; the public rendezvous socket — that belongs to the systemd
              ;; daemon (Electron's transport). Without this the two fight over
              ;; $XDG_RUNTIME_DIR/grog-$USER.sock, and the daemon's bind is
              ;; FATAL, so it exits and the service "tanks".
              (doto (.environment pb)
                (.put "GROG_SERVER_NO_SOCKET" "1"))
              (let [p (.start pb)
                    conn {:process p
                          :in (BufferedReader. (InputStreamReader. (.getInputStream p) "UTF-8"))
                          :out (java.io.PrintWriter. (java.io.BufferedWriter.
                                                      (java.io.OutputStreamWriter. (.getOutputStream p) "UTF-8")))
                          :pending (atom {})
                          :next-id (atom 0)}]
                (drain-stderr! p)
                (listener! conn)
                (reset! !conn conn)
                conn)))))))

(defn- write-msg!
  [{:keys [^java.io.PrintWriter out]} obj]
  (locking out
    (.print out (json/generate-string obj))
    (.print out "\n")
    (.flush out)))

(defn- call
  "Send a request, block for the response (bounded). Returns :result or throws
  the server's error."
  [method params]
  (let [c (ensure-conn! @!cmd* @!dir*)
        id (swap! (:next-id c) inc)
        p (promise)]
    (swap! (:pending c) assoc id p)
    (write-msg! c {:jsonrpc "2.0" :id id :method method :params params})
    (let [resp (deref p call-timeout-ms ::timeout)]
      (swap! (:pending c) dissoc id)
      (cond
        (= ::timeout resp)
        (throw (ex-info (str "grog-server: timeout on " method) {:method method}))

        (:error resp)
        (throw (ex-info (str "grog-server: " (or (get-in resp [:error :message]) "error"))
                        {:method method :error (:error resp)}))

        :else (:result resp)))))

;; --- incoming notifications ------------------------------------------------

(defn- sync-shadow!
  "Fold one event's state changes into the local shadow atoms (the pieces the
  status bar / tab dots read). Everything else is paint — the subscriber
  handles it."
  [state ev]
  (case (:type ev)
    :status (let [v (:value ev)]
              (reset! (:status state) (str v))
              (reset! (:running? state) (not= "idle" (str v))))
    :trust (reset! (:trust state) (boolean (:value ev)))
    :model (reset! (:model state) (:value ev))
    nil))

(defn- fan-out!
  "Deliver `ev` to the session's subscribers (already-stamped server events —
  no re-stamp locally)."
  [id ev]
  (when-let [{:keys [subs]} (get @!sessions id)]
    (doseq [f @subs]
      (try (f ev) (catch Throwable _ nil)))))

(defn- normalize-event
  "JSON has no keyword VALUES: the server's `:type` (`:user`, `:status`, …)
  arrives as a string. Re-keywordize it (and only it — text payloads must
  stay strings) so `case` dispatch behaves exactly as in-process."
  [ev]
  (cond-> ev (string? (:type ev)) (update :type keyword)))

(defn- handle-notification!
  [_conn {:keys [method params]}]
  (case method
    "event"
    (let [{:keys [sessionId] :as wire} params
          ev (normalize-event (dissoc wire :sessionId))]
      (when-let [state (:state (get @!sessions sessionId))]
        (sync-shadow! state ev))
      (fan-out! sessionId ev))

    "question"
    (let [{:keys [sessionId questionId] :as wire} params
          base (normalize-event (dissoc wire :sessionId :questionId))
          p (promise)
          ev (assoc base :answer (fn [res] (deliver p res)))]
      (fan-out! sessionId ev)
      ;; wedge-safety: an answer delivered DURING the fan-out wins; later is
      ;; too late (identical to chat/make-request-handler's local rule)
      (when-let [c (when-not (:dead @!conn) @!conn)]
        (write-msg! c {:jsonrpc "2.0"
                       :id (swap! (:next-id c) inc)
                       :method "answer-question"
                       :params {:question-id questionId
                                :result (if (realized? p)
                                          (deref p)
                                          {:cancelled true :answer nil})}})))

    nil))

;; --- the adapter -----------------------------------------------------------

(defn- shadow-state
  "A local grog.chat state seeded from the server's open snapshot."
  [{:keys [project chat-id model status trust usage connected running?]}]
  (let [st (chat/make-state {:project project :chat-id chat-id :model model})]
    (when status (reset! (:status st) (str status)))
    (reset! (:trust st) (boolean trust))
    (when usage (reset! (:usage st) usage))
    (reset! (:connected st) (boolean connected))
    (reset! (:running? st) (boolean running?))
    st))

(defrecord RemoteClient []
  client/Client
  (open [_ opts]
    (let [snap (call "open" {:project (:project opts)})
          id (:id snap)
          state (shadow-state snap)]
      (swap! !sessions assoc id {:state state :subs (atom #{})})
      {:id id :project (:project snap) :state state}))
  (close [_ id]
    (try (call "close" {:id id}) (catch Throwable _ nil))
    (swap! !sessions dissoc id)
    nil)
  (sessions [_] (vec (or (call "sessions" {}) [])))
  (session [_ id] (call "session" {:id id}))
  (connect! [_ id]
    (call "connect" {:id id})
    (when-let [st (:state (get @!sessions id))]
      (reset! (:connected st) true)))
  (disconnect! [this id] (client/close this id))
  (prompt! [_ id text]
    (when-let [st (:state (get @!sessions id))]
      (reset! (:running? st) true)
      (swap! (:usage st) assoc :turn-tokens 0 :turn-cost 0.0))
    (call "prompt" {:id id :text text}))
  (steer! [_ id text]
    (when-let [st (:state (get @!sessions id))]
      (reset! (:pending-steer st) (str text))
      (reset! (:last-sent st) (str text))
      (reset! (:running? st) true))
    (call "steer" {:id id :text text}))
  (stop! [_ id]
    (call "stop" {:id id})
    (when-let [st (:state (get @!sessions id))]
      (reset! (:running? st) false)))
  (answer! [_ id ans]
    ;; no throw on a vanished session — same tolerance as the local adapter
    (when (get @!sessions id)
      (let [d (:decision ans)]
        (call "answer" {:id id
                        :approval-id (:approval-id ans)
                        :decision (if (keyword? d) (name d) (str d))}))))
  (set-model! [_ id model] (call "set-model" {:id id :model model}))
  (set-trust! [_ id on?] (call "set-trust" {:id id :on (boolean on?)}))
  (subscribe! [_ id f]
    (when-let [{:keys [subs]} (get @!sessions id)]
      (swap! subs (fnil conj #{}) f)))
  (unsubscribe! [_ id f]
    (when-let [{:keys [subs]} (get @!sessions id)]
      (swap! subs disj f))))

(defn install!
  "Install the remote adapter. `cmd` is the server launch vector (e.g.
  `[\"clojure\" \"-M\" \"-m\" \"grog.server\"]`), `dir` its working directory
  (where deps.edn lives)."
  [{:keys [cmd dir] :or {dir "."}}]
  (reset! !cmd* (vec cmd))
  (reset! !dir* dir)
  (client/set-impl! (->RemoteClient)))