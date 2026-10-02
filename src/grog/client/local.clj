(ns grog.client.local
  "In-process adapter for `grog.client`.

  Owns the session-registry domain: each session's ECA connection, prompt queue
  + worker thread, model/trust setters, approval answers, stop/disconnect. The
  view passes in only what is genuinely view-side — `:console` (the transcript
  writer factory), `:trace-fn` (debug tracer) — and subscribes to the session's
  `grog.chat` event stream to paint.

  Every error (`ECA connect failed`, `not connected — message not
  sent`, ECA in-band errors, model/trust changes) is published as a
  `:line`/`:model`/`:trust` event instead — the same seam `grog-server`
  publishes over the wire. **Seam check:** zero windowing-toolkit `:import`
  forms (`grep -cE '^\\s*\\(:import' src/grog/client/local.clj` → the only
  import is the queue).

  Connect is lazy (first send or explicit `connect!`), a failed prompt keeps
  `running?` down and says so loudly, `/yolo` toggles through ECA's trust,
  steer-vs-queue is the view's call (it reads `running?` from the shared
  state)."
  (:require [grog.chat :as chat]
            [grog.client :as client]
            [grog.config :as config]
            [grog.eca :as eca]
            [grog.eca-config :as ecacfg]
            [grog.models :as models]
            [grog.project-dialog :as project-dialog]
            [grog.projects :as projects]
            [grog.session :as session]
            [grog.cancel :as cancel])
  (:import (java.util.concurrent LinkedBlockingQueue)))

(defn- dbg!
  "Same stderr shape as the GUI's private dbg!, so the per-instance log reads
  identically whether a line came from view or adapter."
  [& xs]
  (.println System/err (str "[grog-debug] " (apply str (interpose " " (map str xs))))))

(def ^:private !sessions
  "Registry: session-id -> session map. The session-id doubles as the ECA
  connection id (`<project>#<nanoTime>`), exactly as minted in build-session!."
  (atom {}))

(defn- sess
  "The session for `id`, or a clear ex-info — a wrong id is a programming
  error, not a runtime condition to paper over."
  [id]
  (or (get @!sessions id)
      (throw (ex-info "grog.client.local: unknown session" {:id id}))))

(defn- line!
  "Publish a transcript status line through the session's stream."
  [state text]
  (chat/publish! state {:type :line :text (str text)}))

(defn- make-session!
  "Build one session: state, queue, ECA handlers, connection, worker. Starts
  the worker; does NOT connect (lazy)."
  [{:keys [project console quit! trace-fn on-state]}]
  (let [project (or project "default")
        ;; unique per-tab id; doubles as the ECA connection id. Multiple tabs
        ;; = multiple `eca server` processes, one connection each (a single
        ;; shared connection would make a second tab fail "already connected").
        id (str project "#" (System/nanoTime))
        queue (LinkedBlockingQueue.)
        state (chat/make-state
                {:project project
                 :chat-id (projects/active-project-chat-id)
                 :model (models/qualify-eca-model (config/eca-model)
                                                  nil
                                                  (try (config/llm-url) (catch Exception _ nil)))})
        {:keys [connected running? last-sent pending-steer]} state
        ;; escape hatch for adapters that must capture the live state (the
        ;; server wires its console publisher before open! returns)
        _ (when on-state (on-state state))
        event-handler
        (chat/make-event-handler
         state id
         {:resend-steer
          (fn [s]
            ;; steer was dropped (run finished before ECA consumed it):
            ;; re-issue as a normal prompt
            (line! state (str "[grog] resending as a prompt: " s))
            (reset! last-sent (str s))
            (.put ^LinkedBlockingQueue queue (str s)))})
        ;; `config-stamp` as of the last successful connect (see connect-fn!)
        cfg-stamp (atom -1)
        connect-fn!
        (fn []
          ;; The ECA child — and every MCP server under it — bakes its config in
          ;; at startup; editing odoo-instances.edn (or similar) cannot reach a
          ;; running one. If those files moved since we connected, stop ECA so
          ;; the code below respawns it. Without this the session silently keeps
          ;; serving the OLD config, stale tool surface included.
          (when (and @connected (> (ecacfg/config-stamp) @cfg-stamp))
            (dbg! "startup config changed since ECA started -> restarting ECA")
            (try (eca/disconnect! id) (catch Throwable _ nil))
            (reset! connected false))
          (when-not @connected
            (try
              (let [cfg (ecacfg/generate-config! (ecacfg/default-eca-config-path)
                                                 (ecacfg/session-config-path project)
                                                 project)
                    ws (projects/workspace-folders project)
                    _ (dbg! "ECA starting: config=" cfg
                            " model=" (or @(:model state) "(none)")
                            " chatId=" @(:chat-id state)
                            " workspace=" (pr-str ws))
                    init (eca/connect! id ws
                                       :event-handler event-handler
                                       :request-handler (chat/make-request-handler state)
                                       :eca-binary (config/eca-binary)
                                       :args ["--config-file" cfg]
                                       :env (chat/provider-env)
                                       :log-fn (fn [line] (dbg! "eca:" line))
                                       :trace-fn trace-fn)]
                (dbg! "ECA started ok, init model=" (get-in init [:ok :model])))
              (reset! connected true)
              (reset! cfg-stamp (ecacfg/config-stamp))
              (catch Throwable e
                (line! state (str "[grog] ECA connect failed: " (.getMessage e)))
                (reset! running? false)))))
        send-fn
        (fn [history text]
          (connect-fn!)
          (if-not @connected
            (do (reset! running? false)
                ;; ECA is down after a send attempt — don't silently swallow
                ;; the user's message. Make it obvious in the transcript AND
                ;; the log (the log is the dif for reproducing what happened).
                (let [msg (str "[grog] not connected to ECA — message not sent: " text)]
                  (dbg! msg)
                  (line! state msg))
                history)
            (do
              (reset! running? true)
              (cancel/clear!)
              ;; new prompt -> clear the per-turn usage accumulator
              (swap! (:usage state) assoc :turn-tokens 0 :turn-cost 0.0)
              (reset! last-sent (str text))
              ;; persist the user's message to the project dialog (best effort)
              (try
                (project-dialog/append-turn! :user (str text))
                (catch Throwable e
                  (dbg! "dialog append user error:" (.getMessage e))))
              (dbg! "send-fn: connected, about to prompt -> " (pr-str (str text))
                    " model-next=" (pr-str @(:model state)))
              (let [url (try (config/llm-url) (catch Exception _ nil))
                    ;; ECA needs an explicit model or it fails with "No
                    ;; available model found"; fall back to grog's configured
                    ;; model when the footer model is unset.
                    model (or (some-> @(:model state)
                                      (models/qualify-eca-model nil url))
                              (models/qualify-eca-model (config/eca-model) nil url))]
                (try
                  (let [resp (eca/prompt! id text {:chatId @(:chat-id state)
                                                   :model model
                                                   :trust @(:trust state)})
                        ;; ECA reports model/backend failures IN-BAND as
                        ;; {:ok {:model "error" :status "error"}} (no JSON-RPC
                        ;; :error key) — surface those instead of swallowing.
                        e (:error resp)
                        o (:ok resp)]
                    (cond
                      e
                      (line! state (str "[grog] " (or (:message e) (pr-str e))))

                      (= "error" (some-> o :status str))
                      (line! state
                             (str "[grog] ECA error: "
                                  (or (some-> o :message str (not-empty))
                                      (some-> o :model str (not-empty))
                                      (pr-str o))))

                      :else
                      (dbg! "eca prompt ok: model=" (:model o) " status=" (:status o))))
                  (catch Throwable e
                    (dbg! "eca prompt error:" (.getMessage e))
                    (reset! running? false)
                    (line! state (str "[grog] " (.getMessage e))))))
              (conj history {:user text}))))
        stop-fn!
        (fn []
          (when @connected (eca/stop! id @(:chat-id state)))
          (cancel/cancel!)
          (reset! running? false))
        set-model-fn
        (fn [nm source]
          (let [mid (models/qualify-eca-model nm
                                              source
                                              (try (config/llm-url) (catch Exception _ nil)))
                ;; Persisting is best-effort: a read-only / unwritable config
                ;; home (packaged AppImage, per-machine install) must NOT stop
                ;; the LIVE switch. It used to throw here — before the UI event
                ;; below — so the model looked like it never changed and the
                ;; only clue was a "Read-only file system" error.
                save-err (when mid
                           (try (models/save-eca-model! mid) nil
                                (catch Throwable e (.getMessage e))))]
            (when (and @connected mid)
              (eca/selected-model! id mid {:chatId @(:chat-id state)}))
            (reset! (:model state) mid)
            (try (config/reload!) (catch Throwable _ nil))
            ;; the view reacts to this event to update the footer + status line
            (chat/publish! state {:type :model
                                  :value mid
                                  :text (str "model: " mid)})
            (when save-err
              (line! state (str "model switched for this session, but could not be saved: "
                                save-err)))))
        ;; slash-command seam: /eca-model has no transport, so it qualifies with
        ;; source=nil (the url/config heuristics decide).
        set-model-1 (fn [nm] (set-model-fn nm nil))
        set-trust-fn
        (fn [on?]
          (let [next (if (nil? on?) (not @(:trust state)) on?)]
            (reset! (:trust state) next)
            (when @connected
              (eca/set-trust! id @(:chat-id state) next))
            (chat/publish! state {:type :trust :value next})
            (line! state (str "trust (yolo) mode: "
                              (if next "ON — tool calls auto-approved" "off")))))
        disconnect-fn!
        (fn []
          (try (eca/disconnect! id) (catch Throwable _ nil))
          (reset! connected false)
          (session/release! project)
          (swap! !sessions dissoc id)
          nil)
        sess-map
        {:id id
         :project project
         :state state
         :queue queue
         :connect! connect-fn!
         :disconnect! disconnect-fn!
         :stop! stop-fn!
         :set-model! set-model-fn
         :set-trust! set-trust-fn}]
    ;; start worker — the headless core owns the queue loop and slash-command
    ;; routing; :console/:quit! are the view-provided seams
    (.start (chat/chat-worker! state queue
                               {:send send-fn
                                :set-model! set-model-1
                                :set-yolo! set-trust-fn
                                :console console
                                :quit! quit!}))
    sess-map))

(defrecord LocalClient []
  client/Client
  (open [_ opts]
    (let [s (make-session! opts)]
      (swap! !sessions assoc (:id s) s)
      {:id (:id s) :project (:project s) :state (:state s)}))
  (close [_ id]
    (when-let [s (get @!sessions id)]
      ((:disconnect! s))))
  (sessions [_]
    (mapv (fn [s] (assoc (chat/snapshot (:state s)) :id (:id s)))
          (vals @!sessions)))
  (session [_ id]
    (when-let [s (get @!sessions id)]
      (assoc (chat/snapshot (:state s)) :id (:id s))))
  (connect! [_ id] ((:connect! (sess id))))
  (disconnect! [_ id]
    (if-let [s (get @!sessions id)]
      ((:disconnect! s))
      nil))
  (prompt! [_ id text]
    (.put ^LinkedBlockingQueue (:queue (sess id)) (str text)))
  (steer! [_ id text]
    (let [s (sess id)
          st (:state s)]
      (reset! (:pending-steer st) (str text))
      (reset! (:last-sent st) (str text))
      (when @(:connected st)
        (eca/steer! id @(:chat-id st) (str text)))))
  (stop! [_ id] ((:stop! (sess id))))
  (answer! [_ id {:keys [approval-id decision]}]
    ;; tolerant: a tab closed (or a duplicate click) must never blow up a turn
    (when-let [s (get @!sessions id)]
      (chat/answer-approval! (:state s) approval-id decision)))
  (set-model! [_ id model] ((:set-model! (sess id)) model nil))
  (set-model! [_ id model source] ((:set-model! (sess id)) model source))
  (set-trust! [_ id on?] ((:set-trust! (sess id)) on?))
  (subscribe! [_ id f] (chat/subscribe! (:state (sess id)) f))
  (unsubscribe! [_ id f] (chat/unsubscribe! (:state (sess id)) f)))

(defn install!
  "Install the local adapter as the active `grog.client` implementation.
  Called lazily by `grog.client` on first use; call it explicitly to force a
  reset back to local (e.g. after experimenting with a remote adapter)."
  []
  (client/set-impl! (->LocalClient)))