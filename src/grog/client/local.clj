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
  (:require [clojure.string :as str]
            [grog.chat :as chat]
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

;; Set true just before the process exits and closes every session. On exit the
;; spine closes ALL sessions in arbitrary order, so per-session close must NOT
;; rewrite the last-used marker from whichever tab happens to be last — that is
;; how a session got stuck reopening `ouroboros`.
(defonce ^:private !shutting-down (atom false))

(defn mark-shutting-down!
  "Tell the local adapter the process is exiting (see `!shutting-down`). Called by
  the server right before it closes every session on shutdown."
  []
  (reset! !shutting-down true))

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

;; --- big/small model slots --------------------------------------------------
;;
;; A session runs one of two slots. `:model` (in the chat state) is the
;; EFFECTIVE model — what `send-fn` hands to ECA; `:big-model`/`:small-model`
;; hold each slot's resolved id and `:model-slot` says which is active.
;;
;; Resolution (big/small are independent, Global → Project):
;;   big   = project manifest `:model`          ?? global `config/eca-model`
;;   small = project manifest `:small-model`    ?? global `config/eca-small-model`
;; and the small slot is opt-in: with no small model anywhere the session
;; ALWAYS runs big ("if small model not selected, then it's always running big").

(defn- slot-models
  "Resolve the big/small model ids for `project`, provider-qualified for ECA.
  Returns `{:big <id-or-nil> :small <id-or-nil>}` (nil = nothing configured)."
  [project]
  (let [url (try (config/llm-url) (catch Exception _ nil))
        q (fn [m] (when (some-> m str str/trim not-empty)
                    (models/qualify-eca-model m nil url)))]
    {:big   (q (or (projects/project-model project) (config/eca-model)))
     :small (q (or (projects/project-small-model project) (config/eca-small-model)))}))

(defn- slot-model
  "The model a session RUNS for `slot`, given its resolved slot atoms: the small
  model only when the SMALL slot is selected AND one exists, else big. The
  single place the \"no small ⇒ always big\" rule lives."
  [state slot]
  (let [big @(:big-model state) small @(:small-model state)]
    (if (and (= :small slot) small) small big)))

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
        slots (slot-models project)
        state (chat/make-state
                {:project project
                 :chat-id (projects/active-project-chat-id)
                 ;; BIG/SMALL SLOTS (per session). A session STARTS on the big
                 ;; slot; it lasts until the user /small's away or the session
                 ;; ends. `:model` is the effective id a turn runs; the other
                 ;; two atoms let a client show both and switch instantly.
                 :model (:big slots)
                 :model-slot :big
                 :big-model (:big slots)
                 :small-model (:small slots)})
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
            (.put ^LinkedBlockingQueue queue {:text (str s)}))})
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
                                       ;; --log-level: see config/eca-log-level.
                                       ;; Defaults to warn so ECA stops relaying
                                       ;; the MCP children's reflection warnings
                                       ;; into the grog log.
                                       :args ["--config-file" cfg
                                              "--log-level" (config/eca-log-level)]
                                       :env (chat/provider-env)
                                       :log-fn (fn [line] (dbg! "eca:" line))
                                       :trace-fn trace-fn)]
                (dbg! "ECA started ok, init model=" (get-in init [:ok :model])))
              (reset! connected true)
              (reset! cfg-stamp (ecacfg/config-stamp))
              ;; Learn image-input capability OFF the connect path, so the client
              ;; can warn before sending an image to a text-only model (ECA drops
              ;; it silently otherwise). A slow/failed call must not block.
              (future
                (try
                  (let [resp (eca/send-request! id "providers/list" {})]
                    (when-let [provs (:providers (:ok resp))]
                      (models/register-provider-vision! {:providers provs})
                      (chat/publish! state {:type :vision
                                            :value (models/vision-for @(:model state))})))
                  (catch Throwable e
                    (dbg! "providers/list (vision) failed:" (.getMessage e)))))
              (catch Throwable e
                (line! state (str "[grog] ECA connect failed: " (.getMessage e)))
                (reset! running? false)))))
        send-fn
        (fn [history text contexts]
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
                                                   :trust @(:trust state)
                                                   :contexts contexts})
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
        (fn [nm source & [opts]]
          (let [slot (if (= :small (:slot opts)) :small :big)
                global? (boolean (:global opts))
                mid (models/qualify-eca-model nm
                                              source
                                              (try (config/llm-url) (catch Exception _ nil)))]
            (when mid
              ;; WHERE the pick is written is the point of the models dialog:
              ;;   global? -> grog.edn `:eca :model` / `:eca :small-model`, the
              ;;              default every project inherits
              ;;   else    -> THIS project's manifest `:model` / `:small-model`
              ;; A plain pick stays per project, so a choice in one project
              ;; never rewrites another's (or every future session's). grog.edn
              ;; remains the fallback for a project that has not chosen yet.
              (try
                (if global?
                  (do (if (= :small slot)
                        (models/save-eca-small-model! mid)
                        (models/save-eca-model! mid))
                      ;; grog.config CACHES; re-read so a new session in THIS
                      ;; process inherits the new global default.
                      (config/reload!))
                  (if (= :small slot)
                    (projects/set-project-small-model! project mid)
                    (projects/set-project-model! project mid)))
                (catch Throwable e (dbg! "save model failed:" (.getMessage e)))))
            (when mid
              ;; remember it in the slot the user edited ...
              (reset! (if (= :small slot) (:small-model state) (:big-model state)) mid)
              ;; build the transcript note up front so the parens stay shallow
              (let [effective (slot-model state @(:model-slot state))
                    note (str (if global? "global " "project ") (name slot)
                              " model set: " mid
                              (when (not= effective mid)
                                (str " (active slot is " (name @(:model-slot state))
                                     " — running " effective ")")))]
                ;; only a change to the ACTIVE slot changes what we actually run
                (reset! (:model state) effective)
                (when (and @connected effective)
                  (eca/selected-model! id effective {:chatId @(:chat-id state)}))
                ;; the view reacts to this event to update the footer, status
                ;; line and both slot readouts
                (chat/publish! state {:type :model
                                      :value effective
                                      :slot @(:model-slot state)
                                      :big @(:big-model state)
                                      :small @(:small-model state)
                                      :text (str "model: " effective)})
                (line! state note)))))
        ;; slash-command seam: /eca-model has no transport, so it qualifies with
        ;; source=nil (the url/config heuristics decide). It edits the BIG slot —
        ;; /small edits the small one, /big and /small switch the active slot.
        set-model-1 (fn [nm] (set-model-fn nm nil))
        set-slot-fn
        (fn [slot]
          (let [slot (if (= :small slot) :small :big)
                small @(:small-model state)
                effective (slot-model state slot)]
            (reset! (:model-slot state) slot)
            (when (and @connected effective)
              (eca/selected-model! id effective {:chatId @(:chat-id state)}))
            (reset! (:model state) effective)
            (chat/publish! state {:type :model
                                  :value effective
                                  :slot slot
                                  :big @(:big-model state)
                                  :small small
                                  :text (str "model: " effective)})
            (line! state (if (and (= :small slot) (not small))
                           "model slot: small — no small model configured, running big"
                           (str "model slot: " (name slot)
                                (when effective (str " (" effective ")")))))))
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
          ;; Remember the project the user just CLOSED as last-used — UNLESS the
          ;; whole process is exiting (see !shutting-down). A normal
          ;; (non-onboarding) launch then reopens it. Written here, NOT via
          ;; `set-project!`, so a multi-tab close moves only the "reopen me"
          ;; marker and never steals the ACTIVE project.
          (when-not @!shutting-down
            (try (projects/write-last-used! project) (catch Throwable _ nil)))
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
         :set-model-slot! set-slot-fn
         :set-trust! set-trust-fn}]
    ;; start worker — the headless core owns the queue loop and slash-command
    ;; routing; :console/:quit! are the view-provided seams
    (.start (chat/chat-worker! state queue
                               {:send send-fn
                                :set-model! set-model-1
                                :set-yolo! set-trust-fn
                                :set-model-slot! set-slot-fn
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
    (.put ^LinkedBlockingQueue (:queue (sess id)) {:text (str text)}))
  (prompt! [_ id text contexts]
    (.put ^LinkedBlockingQueue (:queue (sess id))
          {:text (str text) :contexts (vec contexts)}))
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
  (set-model! [_ id model source opts] ((:set-model! (sess id)) model source opts))
  (set-model-slot! [_ id slot] ((:set-model-slot! (sess id)) (keyword slot)))
  (set-trust! [_ id on?] ((:set-trust! (sess id)) on?))
  (subscribe! [_ id f] (chat/subscribe! (:state (sess id)) f))
  (unsubscribe! [_ id f] (chat/unsubscribe! (:state (sess id)) f)))

(defn install!
  "Install the local adapter as the active `grog.client` implementation.
  Called lazily by `grog.client` on first use; call it explicitly to force a
  reset back to local (e.g. after experimenting with a remote adapter)."
  []
  (client/set-impl! (->LocalClient)))