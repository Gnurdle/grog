(ns grog.client.loop
  "`grog.client` adapter backed by grog's OWN agent loop (`grog.agent.loop`).

  This is the adapter that replaces `grog.client.local`'s ECA dependency: same
  `Client` protocol, same `grog.chat` event stream, same client wire protocol —
  but the engine is `grog.agent.*` instead of an `eca server` child process.

  What the loop supplies that ECA used to: the turn loop, the streaming
  transport, the MCP client, the coding toolbelt, the approval gate and the
  question channel. What this namespace supplies: the SESSION — per-session
  state, the prompt queue + worker, provider-shaped history, cancel, steer,
  model/trust slots, and the two blocking bridges (approve, ask) that turn an
  engine callback into a wire event and wait for the human.

  Blocking is the point, not a wart: the loop runs on the session's WORKER
  thread, so a `deref` on a question or an approval parks that turn without
  touching the UI thread. The timeout is the wedge guard — an unanswered
  question rejects rather than hanging forever."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.agent.loop :as loop]
            [grog.agent.mcp :as mcp]
            [grog.agent.tools :as tools]
            [grog.chat :as chat]
            [grog.client :as client]
            [grog.config :as config]
            [grog.eca-config :as ecacfg]
            [grog.models :as models]
            [grog.project-dialog :as project-dialog]
            [grog.projects :as projects]
            [grog.providers :as providers]
            [grog.secrets :as secrets]
            [grog.session :as session])
  (:import (java.io File)
           (java.util Base64)
           (java.util.concurrent LinkedBlockingQueue)))

(def ^:private answer-timeout-ms 600000)   ; 10 minutes, then reject — never wedge

(defn- dbg! [& xs]
  (.println System/err (str "[grog-debug] " (apply str (interpose " " (map str xs))))))

(def ^:private !sessions (atom {}))

(defonce ^:private !shutting-down (atom false))

(defn mark-shutting-down! []
  (reset! !shutting-down true))

(defn- sess [id]
  (or (get @!sessions id)
      (throw (ex-info "grog.client.loop: unknown session" {:id id}))))

(defn- line! [state text]
  (chat/publish! state {:type :line :text (str text)}))

;; --- provider resolution -----------------------------------------------------

(defn- provider-key
  "The API key for `prov`.

  Order matters, and it is grog's own order: a per-provider secret
  (`<PROVIDER>_API_KEY` — the very accounts `grog.chat/provider-env` injects)
  wins, then `config/llm-api-key`, which is `:llm :api-key` or the `LLM_API_KEY`
  secret. The first version asked for `secrets/get-secret \"<provider>\"`, an
  account nobody writes, so a key the user HAD entered was invisible to the loop."
  [prov]
  (or (when prov
        (try (secrets/get-secret (str (str/upper-case (str prov)) "_API_KEY"))
             (catch Throwable _ nil)))
      (try (config/llm-api-key) (catch Throwable _ nil))))

(defn- resolve-llm
  "`provider/model` (or a bare model id) -> `{:url :key :model}`.

  This is what makes `set-model!` REAL under this adapter: the id selects the
  provider (its url from grog's catalogue, its key through `provider-key`), so a
  model pick changes the endpoint a turn actually calls rather than only the
  text in the status bar.

  THE CONFIGURED ENDPOINT WINS. `:llm :url` is the url a turn will really call and
  the source of its key, so when it is set, an id whose first segment names some
  OTHER provider is a model id from the configured provider's own catalogue —
  OpenRouter's list contains `deepseek/deepseek-v4.1-flash`, a first segment that
  names the NATIVE deepseek provider — and NOT a routing instruction. Reading it
  as one sent turns to api.deepseek.com with an OpenRouter key: a 401 that blames
  the key and names the wrong provider. Seen 2026-10-19.

  `models/qualify-eca-model` is deliberately NOT used here: it resolves against
  ECA's catalogue (so it depends on that having arrived, and trusts any segment
  ECA knows), which is right for the ECA model and unpredictable for this one.

  With no `:llm :url`, the id's own segment is the only hint available, and an
  unresolvable one is REFUSED rather than sent somewhere merely plausible — a
  wrong endpoint is worse than a missing one."
  [model-id]
  (let [mid (some-> model-id str str/trim not-empty)
        cfg-url (try (config/llm-url) (catch Throwable _ nil))
        cfg-prov (when cfg-url (try (models/provider-prefix-for-url cfg-url)
                                    (catch Throwable _ nil)))
        i (when mid (str/index-of mid "/"))
        seg (when i (subs mid 0 i))
        rest-of (when i (subs mid (inc i)))]
    (cond
      cfg-prov
      (let [prefixed? (= seg cfg-prov)]
        {:url cfg-url
         :model (or (when prefixed? rest-of) mid)
         :key (provider-key cfg-prov)})

      :else
      (let [entry (when seg (try (providers/by-id seg) (catch Throwable _ nil)))]
        (when (and seg (nil? entry))
          (throw (ex-info (str "model '" (str model-id) "' names provider '" seg
                               "', which is not in grog's provider catalogue, and no :llm :url is set."
                               " Pick a provider-qualified model, or set :llm :url in grog.edn.")
                          {:model model-id :provider seg})))
        {:url (:url entry)
         :model (or rest-of mid)
         :key (provider-key seg)}))))

;; --- model slots -------------------------------------------------------------

(defn- slot-model [state slot]
  (let [big @(:big-model state) small @(:small-model state)]
    (if (and (= :small slot) small) small big)))

(defn- slot-models [project]
  (let [q (fn [m] (some-> m str str/trim not-empty))]
    {:big (q (or (projects/project-model project) (config/eca-model)))
     :small (q (or (projects/project-small-model project) (config/eca-small-model)))}))

(defn- abs-path
  "~ and relative paths resolve against `cwd` — the same rule the tools use."
  [cwd p]
  (let [p (str p)
        p (cond (= p "~") (System/getProperty "user.home")
                (str/starts-with? p "~/") (str (System/getProperty "user.home") "/" (subs p 2))
                :else p)]
    (-> (if (.isAbsolute (io/file p)) (io/file p) (io/file (or cwd ".") p))
        (.getCanonicalFile))))

;; --- session -----------------------------------------------------------------

(defn- tool-threshold
  "How big the toolbelt may be before the loop hides the MCP long tail behind
  `tool_search`. `:llm :tool-search-threshold` in grog.edn, else nil (the loop's
  own default). A LARGE value disables lazy loading, which is the A/B switch."
  []
  (try (config/tool-search-threshold) (catch Throwable _ nil)))

(defn- mcp-enabled?
  "`GROG_MCP_ENABLED=0` runs the loop with the in-process toolbelt only — the
  headless/offline case (and a much smaller tool list for a small local model)."
  []
  (not= "0" (System/getenv "GROG_MCP_ENABLED")))

(defn- make-session!
  [{:keys [project console quit! on-state]}]
  (let [project (or project "default")
        id (str project "#" (System/nanoTime))
        queue (LinkedBlockingQueue.)
        ;; tools `tool_search` attached in this session, carried turn to turn.
        ;; An atom of a VECTOR, not a bare vector: the turn's reset must be a
        ;; mutation the send fn can see, since it closes over the atom.
        !loaded-tools (atom [])
        slots (slot-models project)
        state (chat/make-state {:project project
                                :chat-id (projects/active-project-chat-id)
                                :model (:big slots)
                                :model-slot :big
                                :big-model (:big slots)
                                :small-model (:small slots)})
        {:keys [connected running?]} state
        cwd (or (some-> (projects/project-dir project) str) (System/getProperty "user.dir"))
        !messages (atom [])
        !mcp (atom nil)
        !cancel (atom false)
        !steer (atom [])
        !tasks (atom [])
        !approvals (atom {})
        !questions (atom {})
        _ (when on-state (on-state state))

        ;; --- the two blocking bridges -------------------------------------
        approve!
        (fn [{:keys [name arguments summary]}]
          (if @(:trust state)
            :approve
            (let [aid (str (random-uuid))
                  p (promise)]
              ;; keep the NAME with the promise: answering :approve-tool must
              ;; whitelist THIS tool, and :yolo has to flip session trust.
              (swap! !approvals assoc aid {:p p :name (str name)})
              (chat/publish! state {:type :approval
                                    :id aid
                                    :name (str name)
                                    :args arguments
                                    :summary (str summary)})
              (let [d (deref p answer-timeout-ms ::timeout)]
                (swap! !approvals dissoc aid)
                (case d
                  ::timeout
                  (do (line! state (str "[grog] approval for " name " timed out — rejected"))
                      :reject)

                  ;; YOLO is a MODE, not a one-off: it has to flip session trust
                  ;; (which the status bar and every later gate read) — the bug
                  ;; was delivering :yolo to the loop and stopping there.
                  :yolo
                  (do (reset! (:trust state) true)
                      (chat/publish! state {:type :trust :value true})
                      (line! state "trust (yolo) mode: ON — tool calls auto-approved")
                      :approve)

                  ;; "approve tool" persists the name so later sessions skip it
                  :approve-tool
                  (do (try (ecacfg/approve-tool! (str name)) (catch Throwable _ nil))
                      :approve)

                  (or d :reject))))))

        ask-user!
        (fn [{:keys [prompt options allow-freeform?]}]
          (let [qid (str (random-uuid))
                p (promise)]
            (swap! !questions assoc qid p)
            (chat/publish! state {:type :question
                                  :prompt (str prompt)
                                  :options (when (seq options) (vec options))
                                  :allowFreeform? (not (false? allow-freeform?))
                                  :answer (fn [res] (deliver p res))})
            (let [r (deref p answer-timeout-ms ::timeout)]
              (swap! !questions dissoc qid)
              (if (= r ::timeout)
                "[no answer — the user did not respond]"
                (if (:cancelled r)
                  "[the user cancelled the question]"
                  (str (:answer r)))))))

        load-skill!
        (fn [skill-id]
          (let [f (try (requiring-resolve 'grog.skills/render-skill-for-prompt)
                       (catch Throwable _ nil))]
            (if f
              (f (str skill-id))
              (let [d (try (requiring-resolve 'grog.skills/discover-skill-records!)
                           (catch Throwable _ nil))
                    recs (when d (try (d) (catch Throwable _ nil)))]
                (if (and (nil? skill-id) (seq recs))
                  (str "available skills: " (str/join ", " (map :id recs)))
                  (str "no skill loader available for " (pr-str skill-id)
                       " (grog.skills exposes no renderer)"))))))

        view-image!
        (fn [{:keys [path]}]
          (let [f (abs-path cwd path)]
            (when-not (.exists ^File f) (throw (ex-info (str "no such image: " f) {})))
            (let [name (str/lower-case (str (.getName ^File f)))
                  mt (cond (str/ends-with? name ".png") "image/png"
                           (or (str/ends-with? name ".jpg") (str/ends-with? name ".jpeg")) "image/jpeg"
                           (str/ends-with? name ".gif") "image/gif"
                           (str/ends-with? name ".webp") "image/webp"
                           :else (throw (ex-info (str "unsupported image type: " name) {})))
                  b64 (.encodeToString (Base64/getEncoder) (java.nio.file.Files/readAllBytes (.toPath ^File f)))]
              {:text (str "attached " f " (" mt ")")
               :error? false
               :image {:media_type mt :base64 b64}})))

        spawn-agent!
        (fn [{:keys [prompt sub-cwd]}]
          ;; a nested loop: isolated message list, NO event fan-out (the parent
          ;; sees only the answer), the SAME approval gate — a subagent must not
          ;; be a way to run a shell command the user would have refused.
          (let [res (loop/run-turn! {:llm (resolve-llm @(:model state))
                                     :message (str prompt)
                                     :cwd (or sub-cwd cwd)
                                     :mcp @!mcp
                                     ;; same rule as the parent: no cap unless the
                                     ;; user configured one. The old hard 8 was an
                                     ;; arbitrary second ceiling — a subagent asked to
                                     ;; do a real job hit it and returned nothing.
                                     :max-steps (try (config/chat-tool-loop-limit)
                                                     (catch Throwable _ nil))
                                     :system loop/default-system-prompt
                                     :approve approve!
                                     :approval :mutating})]
            (or (not-empty (str/trim (str (:text res))))
                (str "[subagent finished with status " (:status res) " and no answer]"))))

        st (tools/session-tools {:ask-user! ask-user!
                                 :load-skill! load-skill!
                                 :spawn-agent! spawn-agent!
                                 :view-image! view-image!
                                 :!tasks !tasks})
        connect-fn!
        (fn []
          (when-not @connected
            (when (mcp-enabled?)
              (try
                (let [conn (mcp/start! (mcp/server-spec project))]
                  (mcp/handshake! conn)
                  (reset! !mcp conn)
                  (dbg! "loop: MCP up," (count (mcp/list-tools! conn)) "tools"))
                (catch Throwable e
                  (dbg! "loop: MCP unavailable:" (.getMessage e))
                  (line! state (str "[grog] MCP unavailable — local tools only: " (.getMessage e))))))
            (reset! connected true)))

        on-event
        (fn [seg]
          (case (:kind seg)
            :answer   (chat/publish! state {:type :content
                                            :content {:type "text" :text (str (:text seg))}})
            :thinking (chat/publish! state {:type :content
                                            :content {:type "thinking" :text (str (:text seg))}})
            :tool     (chat/publish! state {:type :content
                                            :content {:type "toolCallRun" :summary (str (:text seg))}})
            :usage    nil
            nil))

        send-fn
        (fn [_history text contexts]
          (connect-fn!)
          (reset! running? true)
          (reset! !cancel false)
          (reset! !steer [])
          (swap! (:usage state) assoc :turn-tokens 0 :turn-cost 0.0)
          (reset! (:last-sent state) (str text))
          (try (project-dialog/append-turn! :user (str text)) (catch Throwable _ nil))
          (try
            (let [t0 (System/currentTimeMillis)
                  res (loop/run-turn! {:llm (resolve-llm @(:model state))
                                       :message (str text)
                                       :cwd cwd
                                       :mcp @!mcp
                                       :history @!messages
                                       :system loop/default-system-prompt
                                       :cancel !cancel
                                       :steer !steer
                                       :approve approve!
                                       :approval :mutating
                                       :approved-tools (try (set (ecacfg/read-approved-tools))
                                                            (catch Throwable _ #{}))
                                       ;; UNCAP BY DEFAULT. `chat-tool-loop-limit`
                                       ;; is nil when unset and its docstring says
                                       ;; nil = no limit — so pass nil, NOT a
                                       ;; coerced 12. `(or … 12)` is exactly what
                                       ;; made a documented "unlimited" a hard cap.
                                       :max-steps (try (config/chat-tool-loop-limit)
                                                       (catch Throwable _ nil))
                                       :max-context-tokens (or (try (config/max-context-tokens)
                                                                    (catch Throwable _ nil))
                                                               120000)
                                       ;; lazy tool loading: keep the session's
                                       ;; loaded set across turns (and let the
                                       ;; threshold be raised to disable laziness)
                                       :loaded-tools @!loaded-tools
                                       :tool-threshold (or (try (config/tool-search-threshold)
                                                                (catch Throwable _ nil))
                                                           nil)
                                       :extra-tools (:tools st)
                                       :local-handlers (:handlers st)
                                       :on-event on-event})]
              (reset! !messages (:messages res))
              ;; remember which tools `tool_search` attached, for the NEXT turn
              (when (seq (:loaded-tools res)) (reset! !loaded-tools (vec (:loaded-tools res))))
              ;; USAGE / the cost meter. The status bar renders an ECA-shaped
              ;; content map with CAMELCASE keys — and only when `:sessionTokens`
              ;; is present (`usage-text`/the status bar gate on it). The loop's
              ;; provider usage is snake_case (`{:prompt_tokens … :total_tokens …}`),
              ;; and the first version both read the wrong key (`:total-tokens`)
              ;; and published `pr-str` of a map the client ignores: hence the
              ;; vanished meter. Translate, accumulate, publish.
              (let [u (:usage res)
                    tok (long (or (:total_tokens u) (:totalTokens u) 0))
                    cost (some-> (or (:cost u) (:total_cost u) (:cost_usd u)) double)
                    prev @(:usage state)
                    sess-tok (+ (long (or (:session-tokens prev) 0)) tok)
                    sess-cost (when (or cost (:session-cost prev))
                                (+ (double (or (:session-cost prev) 0.0))
                                   (double (or cost 0.0))))
                    fmt (fn [d] (format "%.4f" (double d)))]
                (swap! (:usage state) assoc
                       :turn-tokens tok
                       :turn-cost (or cost 0.0)
                       :session-tokens sess-tok
                       :session-cost sess-cost)
                (when (pos? tok)
                  (chat/publish! state
                                 {:type :content
                                  :content (cond-> {:type "usage"
                                                    :sessionTokens sess-tok}
                                             cost (assoc :lastMessageCost (fmt cost))
                                             sess-cost (assoc :sessionCost (fmt sess-cost)))})))
              (when-let [reply (not-empty (str/trim (str (:text res))))]
                (try (project-dialog/append-turn! :assistant reply) (catch Throwable _ nil)))
              (dbg! "loop turn:" (:status res) "steps=" (:steps res)
                    "ms=" (- (System/currentTimeMillis) t0)))
            (catch Throwable e
              ;; a one-line message is not a diagnosis — the crash that killed a
              ;; real bb-dev turn ("String cannot be cast to Associative") named
              ;; no frame at all. Print the stack into the per-instance log.
              (.printStackTrace e)
              (dbg! "loop turn failed:" (.getMessage e))
              (line! state (str "[grog] loop error: " (.getMessage e))))
            (finally
              (reset! running? false)
              (chat/publish! state {:type :status :value "idle"})))
          @(:history state))

        stop-fn!
        (fn []
          (reset! !cancel true)
          ;; `!approvals` now maps id -> {:p promise :name …}; deliver the promise
          (doseq [[_ {:keys [p]}] @!approvals] (deliver p :reject))
          (doseq [[_ p] @!questions] (deliver p {:cancelled true :answer nil}))
          (reset! running? false))

        set-model-fn
        (fn [nm source & [opts]]
          (let [slot (if (= :small (:slot opts)) :small :big)
                global? (boolean (:global opts))
                mid (some-> nm str str/trim not-empty)]
            (when mid
              (try
                (if global?
                  (if (= :small slot)
                    (models/save-eca-small-model! mid)
                    (models/save-eca-model! mid))
                  (if (= :small slot)
                    (projects/set-project-small-model! project mid)
                    (projects/set-project-model! project mid)))
                (catch Throwable e (dbg! "save model failed:" (.getMessage e)))))
            (when mid
              (reset! (if (= :small slot) (:small-model state) (:big-model state)) mid)
              (let [effective (slot-model state @(:model-slot state))]
                (reset! (:model state) effective)
                (chat/publish! state {:type :model
                                      :value effective
                                      :slot @(:model-slot state)
                                      :big @(:big-model state)
                                      :small @(:small-model state)
                                      :text (str "model: " effective)})
                (let [r (resolve-llm effective)]
                  (line! state (str (if global? "global " "project ") (name slot)
                                    " model set: " mid
                                    " → " (:url r) " · " (:model r)
                                    (when-not (:key r) "  [NO KEY — set one with /secret]"))))))))
        set-model-1 (fn [nm] (set-model-fn nm nil))

        set-slot-fn
        (fn [slot]
          (let [slot (if (= :small slot) :small :big)
                small @(:small-model state)
                effective (slot-model state slot)]
            (reset! (:model-slot state) slot)
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
            (chat/publish! state {:type :trust :value next})
            (line! state (str "trust (yolo) mode: "
                              (if next "ON — tool calls auto-approved" "off")))))

        disconnect-fn!
        (fn []
          (when-let [c @!mcp] (try (mcp/stop! c) (catch Throwable _ nil)))
          (reset! !mcp nil)
          (reset! connected false)
          (session/release! project)
          (swap! !sessions dissoc id)
          (when-not @!shutting-down
            (try (projects/write-last-used! project) (catch Throwable _ nil)))
          nil)

        sess-map
        {:id id :project project :state state :queue queue
         :approvals !approvals :questions !questions :steer !steer :messages !messages
         :connect! connect-fn! :disconnect! disconnect-fn! :stop! stop-fn!
         :set-model! set-model-fn :set-model-slot! set-slot-fn :set-trust! set-trust-fn}]
    (.start (chat/chat-worker! state queue
                               {:send send-fn
                                :set-model! set-model-1
                                :set-yolo! set-trust-fn
                                :set-model-slot! set-slot-fn
                                :console console
                                :quit! quit!}))
    sess-map))

(defrecord LoopClient []
  client/Client
  (open [_ opts]
    (let [s (make-session! opts)]
      (swap! !sessions assoc (:id s) s)
      {:id (:id s) :project (:project s) :state (:state s)}))
  (close [_ id]
    (when-let [s (get @!sessions id)] ((:disconnect! s))))
  (sessions [_]
    (mapv (fn [s] (assoc (chat/snapshot (:state s)) :id (:id s))) (vals @!sessions)))
  (session [_ id]
    (when-let [s (get @!sessions id)]
      (assoc (chat/snapshot (:state s)) :id (:id s))))
  (connect! [_ id] ((:connect! (sess id))))
  (disconnect! [_ id]
    (if-let [s (get @!sessions id)] ((:disconnect! s)) nil))
  (prompt! [_ id text]
    (.put ^LinkedBlockingQueue (:queue (sess id)) {:text (str text)}))
  (prompt! [_ id text contexts]
    (.put ^LinkedBlockingQueue (:queue (sess id)) {:text (str text) :contexts (vec contexts)}))
  (steer! [_ id text]
    ;; REAL steering: queued into the turn's `:steer` atom and drained at the
    ;; next step boundary by `grog.agent.loop/run-turn!`.
    (let [s (sess id)]
      (swap! (:steer s) conj (str text))
      (line! (:state s) (str "[steer] " (str text)))))
  (stop! [_ id] ((:stop! (sess id))))
  (answer! [_ id {:keys [approval-id decision]}]
    (when-let [s (get @!sessions id)]
      ;; tolerant: a closed tab or a double click must never blow up a turn
      (when-let [{:keys [p]} (get @(:approvals s) approval-id)]
        (deliver p decision))))
  (set-model! [_ id model] ((:set-model! (sess id)) model nil))
  (set-model! [_ id model source] ((:set-model! (sess id)) model source))
  (set-model! [_ id model source opts] ((:set-model! (sess id)) model source opts))
  (set-model-slot! [_ id slot] ((:set-model-slot! (sess id)) (keyword slot)))
  (set-trust! [_ id on?] ((:set-trust! (sess id)) on?))
  (subscribe! [_ id f] (chat/subscribe! (:state (sess id)) f))
  (unsubscribe! [_ id f] (chat/unsubscribe! (:state (sess id)) f)))

(defn install!
  "Install this adapter as the active `grog.client` implementation."
  []
  (client/set-impl! (->LoopClient)))
