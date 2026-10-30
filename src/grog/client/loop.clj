(ns grog.client.loop
  "`grog.client` adapter backed by grog's OWN agent loop (`grog.agent.loop`).

  This is the adapter that replaces `grog.client.local`'s ECA dependency: same
  `Client` protocol, same `grog.chat` event stream, same client wire protocol —
  but the engine is `grog.agent.*` instead of an `eca server` child process.

  What the loop supplies that ECA used to: the turn loop, the streaming
  transport, the MCP client, the coding toolbelt, the approval gate and the
  question channel. What this namespace supplies: the SESSION — per-session
  state, the prompt queue + worker, provider-shaped history, cancel, steer,
  model/trust, and the two blocking bridges (approve, ask) that turn an
  engine callback into a wire event and wait for the human.

  Blocking is the point, not a wart: the loop runs on the session's WORKER
  thread, so a `deref` on a question or an approval parks that turn without
  touching the UI thread. The timeout is the wedge guard — an unanswered
  question rejects rather than hanging forever."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.agent.context :as ctx]
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

(defn- sdbg!
  "`dbg!` for a line that belongs to ONE session, so it carries the session tag
  the log pane filters on. Without it, `loop turn:` from four tabs is four
  identical-looking lines in one file — which is how a hang took an hour to
  attribute. Keeps the `[grog-debug]` marker, so existing greps still work."
  [state & xs]
  (chat/tagged! state "[grog-debug]" (apply str (interpose " " (map str xs)))))

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

  With no `:llm :url` and no explicit source, the id's own segment is the only
  hint available, and an unresolvable one is REFUSED rather than sent somewhere
  merely plausible — a wrong endpoint is worse than a missing one."
  [model-id & [source _slot]]
  (let [mid (some-> model-id str str/trim not-empty)
        src (when source (-> source str str/lower-case str/trim not-empty))
        entry (when src (try (providers/by-id src) (catch Throwable _ nil)))
        src-url (when entry (:url entry))
        cfg-url (try (config/llm-url) (catch Throwable _ nil))
        cfg-key (try (config/llm-api-key) (catch Throwable _ nil))
        cfg-prov (when cfg-url (try (models/provider-prefix-for-url cfg-url)
                                    (catch Throwable _ nil)))
        i (when mid (str/index-of mid "/"))
        seg (when i (subs mid 0 i))
        rest-of (when i (subs mid (inc i)))]
    (cond
      ;; Explicit source (from the picker): use that provider's own URL. A
      ;; local model (ollama) must hit localhost:11434, not the configured
      ;; remote endpoint (OpenRouter, etc.).
      src-url
      (let [prefixed? (= seg src)]
        {:url src-url
         :model (or (when prefixed? rest-of) mid)
         :key (provider-key src)})

      ;; No explicit source: the configured endpoint is the only hint.
      cfg-prov
      (let [prefixed? (= seg cfg-prov)]
        {:url cfg-url
         :model (or (when prefixed? rest-of) mid)
         :key cfg-key})

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

;; --- the model --------------------------------------------------------------
;;
;; ONE model (2026-10-26). This used to resolve a big/small SLOT pair — the
;; project manifest's `:small-model`, the global `:small-model`, the `/big`
;; `/small` switch and the `/relay` cascade — and the two extra resolvers below
;; are all that is left of it. The model is `projects/project-model` (per-project
;; override) else `config/eca-model` (the global default).

(defn- session-model
  "The model id a session runs: the project's own, else the global default."
  [project]
  (some-> (or (projects/project-model project) (config/eca-model))
          str str/trim not-empty))

(defn- model-status
  "The `:model` event payload — the model id AND where it runs.

  The status line needs the provider beside the model: `qwen3.8-…` on its own
  does not say it is a local Strata server, and `aion-2.0` does not say
  openrouter. Both come from the resolved transport, so they are correct even
  when the model id carries no provider prefix of its own.

  `:choice` is the NAME the user gave this url+model pair, when they gave one.
  It is reported separately from `:label` rather than folded into it: the label is
  also what logs and old clients read, and the name is only worth showing to a
  client that knows what a choice is."
  [model-id source]
  (let [r (try (resolve-llm model-id source nil) (catch Throwable _ nil))
        url (:url r)
        prov (or (some-> source str str/trim not-empty)
                 (try (models/provider-for-endpoint url) (catch Throwable _ nil)))
        label (try (config/model-label {:provider prov :url url :model (or (:model r) model-id)})
                   (catch Throwable _ (str model-id)))]
    {:value (or (:model r) model-id)
     :model (or (:model r) model-id)
     :url url
     :provider prov
     :label label
     :choice (try (models/choice-for url (:model r)) (catch Throwable _ nil))
     :text (str "model: " label)}))

(defn- session-llm
  "The transport a turn must ACTUALLY call, taken from the SESSION's own state —
  the same url/provider/model the status line shows.

  This is the fix for 'grog routes to a model other than the status line'.
  A turn used to re-resolve its endpoint from the live config on every call
  (`resolve-llm @:model nil`), while the status line was computed — once — from
  the transport the PICK resolved to. The two are the same only by luck, and they
  disagree the moment they can: a pick whose `source` named a provider other than
  `:llm :url` (the status line says `strata · …` and the turn POSTs to
  `:llm :url`), or a project pick that rewrote the GLOBAL `:llm :url` under a
  session that is already running (the status line keeps the old endpoint, the
  turn silently follows the new one). Either way the user is told one endpoint
  and billed against another — the reported symptom.

  Pinning is the honest rule: whatever the session advertises is what it runs.
  `:url` is set once from the resolved pick and only changes when THIS session's
  model is set again (see `make-session!` and `set-model-fn`), so a config write
  from another project can no longer move a running session out from under its
  own status line. The KEY is deliberately re-read here rather than pinned, so a
  key added with `/secret` takes effect on the next turn; the URL is what must
  not drift."
  [state]
  (let [url (some-> @(:url state) str str/trim not-empty)
        model (some-> @(:model state) str str/trim not-empty)
        prov (some-> @(:provider state) str str/trim not-empty)]
    (if (nil? url)
      ;; No transport was ever resolved for this session (an unconfigured model,
      ;; or an id that named no known provider). Fall back to the config — the
      ;; same resolution `model-status` would have recorded — rather than
      ;; POSTing to a blank endpoint.
      (resolve-llm model nil nil)
      {:url url
       :model model
       :key (provider-key prov)})))


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
        model (session-model project)
        mstatus (model-status model nil)
        state (chat/make-state {:project project
                                ;; the same id the server stamps on the wire —
                                ;; see `chat/session-tag` for why the log needs it
                                :session-id id
                                :chat-id (projects/active-project-chat-id)
                                :model (:model mstatus)
                                :model-label (:label mstatus)
                                :provider (:provider mstatus)
                                :url (:url mstatus)})
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

        ;; THE STALL KNOBS, resolved once per session: `:llm :socket-timeout-sec`
        ;; (how long a silent provider is tolerated per attempt) and `:llm
        ;; :stream-retries` (how many times an attempt that received NOTHING is
        ;; re-sent). Both were hard-coded in the transport (300s / no retry), so a
        ;; transient stall killed the turn with a bare `Read timed out` and there
        ;; was nothing to tune. Every run-turn! in this session — main and
        ;; subagent — merges these, so a subagent cannot silently keep the old
        ;; behaviour. `:on-retry` is supplied per call site: only the turn the user
        ;; is watching should announce its retries.
        stall-opts
        (fn [& [overrides]]
          (merge {:socket-timeout-ms (try (config/llm-socket-timeout-ms)
                                          (catch Throwable _ 300000))
                  :stream-retries (try (config/llm-stream-retries)
                                       (catch Throwable _ 2))}
                 overrides))

        spawn-agent!
        (fn [{:keys [prompt sub-cwd]}]
          ;; a nested loop: isolated message list, NO event fan-out (the parent
          ;; sees only the answer), the SAME approval gate — a subagent must not
          ;; be a way to run a shell command the user would have refused.
          ;;
          ;; SUBS RUN THE SESSION'S MODEL. This used to pick the SMALL slot (and
          ;; before that, resolve the ACTIVE id against the small transport,
          ;; which 404'd). There is one model now, so a subagent runs the same
          ;; one the session does.
          (let [res (loop/run-turn! {:llm (merge (session-llm state)
                                                 (stall-opts {:on-retry nil}))
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
                  (sdbg! state "loop: MCP up," (count (mcp/list-tools! conn)) "tools"))
                (catch Throwable e
                  (sdbg! state "loop: MCP unavailable:" (.getMessage e))
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
                  ;; SAY SO when a retry happens — a five-minute silence followed by
                  ;; an error is the thing that made the original failure
                  ;; unreadable. Only THIS call site announces: it is the turn the
                  ;; user is watching. A subagent retries quietly and reports once.
                  turn-stall-opts
                  (stall-opts
                   {:on-retry (fn [{:keys [attempt retries error wait-ms]}]
                                (line! state (str "[grog] LLM read stalled ("
                                                  (.getMessage ^Throwable error)
                                                  ") — retrying in " (long (/ (long wait-ms) 1000))
                                                  "s (attempt " (inc (long attempt)) " of "
                                                  (inc (long retries)) ")")))})
                  res (loop/run-turn! {:llm (merge (session-llm state) turn-stall-opts)
                                       :message (str text)
                                       :cwd cwd
                                       :mcp @!mcp
                                       :history (let [h @!messages]
                                                  ;; ONE system message, always. The loop
                                                  ;; prepends `:system` itself, so replaying
                                                  ;; the previous turn's list verbatim added a
                                                  ;; SECOND copy of the ~1.6k-token prompt
                                                  ;; every turn — and `compact-messages`
                                                  ;; refuses to elide a leading system
                                                  ;; message, so they accumulated until a long
                                                  ;; session hit the context wall for no
                                                  ;; visible reason. `run-turn!` now returns
                                                  ;; `:messages` with its system message at
                                                  ;; the head; the session owns the
                                                  ;; CONVERSATION, the loop owns the SYSTEM.
                                                  ;; (Roles seen on the wire 2026-10-27:
                                                  ;; turn 2 sent ["system" "system" "user"
                                                  ;; "user" "assistant".])
                                                  (if (= "system" (:role (first h)))
                                                    (vec (next h))
                                                    h))
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
                                       ;; THE CONTEXT BUDGET IS DERIVED FROM THIS
                                       ;; SESSION'S OWN MODEL. Not a global constant:
                                       ;; the user switches between a 32k local server
                                       ;; and a 1M remote one, and one number cannot
                                       ;; be right for both (too big = a hard 400
                                       ;; with no answer; too small = a long
                                       ;; conversation needlessly truncated).
                                       ;; `context-budget` probes the endpoint that
                                       ;; the STATUS LINE shows — the same url and
                                       ;; model this turn POSTs to — and returns nil
                                       ;; when it cannot learn the window. nil is
                                       ;; deliberate: no proactive trim, send the
                                       ;; conversation intact, and let the loop's
                                       ;; overflow recovery learn the real limit from
                                       ;; the first refusal. There is NO hardcoded
                                       ;; fallback here any more: a constant arriving
                                       ;; as a default IS the bug.
                                       :max-context-tokens (try (models/context-budget-for
                                                                 (:url (session-llm state))
                                                                 (:model (session-llm state)))
                                                                (catch Throwable _ nil))
                                       ;; a REAL overflow teaches grog the window.
                                       ;; The loop hands every refusal here so it can
                                       ;; be persisted per (url, model) — the
                                       ;; provider's own ceiling beats any probe, and
                                       ;; the next turn then starts knowing.
                                       :on-overflow (fn [{:keys [limit attempt]}]
                                                      (when limit
                                                        (try
                                                          (models/remember-context-window!
                                                           (:url (session-llm state))
                                                           (:model (session-llm state))
                                                           limit)
                                                          (catch Throwable _ nil)))
                                                      (line! state
                                                             (str "[grog] context overflow — "
                                                                  (if limit
                                                                    (str "the model's window is " limit
                                                                         " tokens (learned from the provider)")
                                                                    "the model's window is unknown")
                                                                  "; distilling the conversation and retrying"
                                                                  (when (> (long (or attempt 1)) 1)
                                                                    (str " (attempt " attempt ")")))))
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
                    fmt (fn [d]
                          (let [a (Math/abs (double d))]
                            (cond
                              (>= a 0.01)   (format "%.2f" d)
                              (>= a 0.001)  (format "%.5f" d)
                              (>= a 1e-4)   (format "%.7f" d)
                              :else          (format "%.9f" d))))] ; $0.000000001+
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
              (sdbg! state "loop turn:" (:status res) "steps=" (:steps res)
                    "ms=" (- (System/currentTimeMillis) t0)))
            (catch Throwable e
              ;; a one-line message is not a diagnosis — the crash that killed a
              ;; real bb-dev turn ("String cannot be cast to Associative") named
              ;; no frame at all. Print the stack into the per-instance log.
              (.printStackTrace e)
              (sdbg! state "loop turn failed:" (.getMessage e))
              (let [d (ex-data e)]
                (cond
                  ;; A CONTEXT OVERFLOW THAT SURVIVED THE LOOP'S OWN RECOVERY.
                  ;; The loop already distilled twice before giving up, so this is
                  ;; a model that cannot take the conversation at all. The one
                  ;; thing that must NOT happen is leaving `!messages` as it was:
                  ;; the thread survives (nothing is deleted without the user
                  ;; asking) but the SAME oversized prompt is not re-sent on the
                  ;; next turn to fail identically. Distil what is stored, SAY so,
                  ;; and let the user decide whether to continue, switch model or
                  ;; /clear.
                  (:overflow? d)
                  (let [saved (count @!messages)
                        _ (reset! !messages (ctx/distill @!messages {}))]
                    (line! state (str "[grog] context overflow: " (.getMessage e)))
                    (line! state (str "[grog] the conversation is kept — " saved " message(s) were "
                                      "distilled in place so the next turn can proceed. "
                                      "Pick a larger-context model with /model, or /clear to start over.")))

                  (:stalled? d)
                  ;; A STALL IS ITS OWN KIND OF FAILURE and it gets its own line.
                  ;; `[grog] loop error: Read timed out` (2026-10-22) named no
                  ;; endpoint, no model, no duration and no next step — the user's
                  ;; bug report was the exception text, because that was all there
                  ;; was. The transport now supplies a full sentence; keep the
                  ;; partial answer if the stall happened mid-stream, since the
                  ;; user watched it arrive and it should not be thrown away.
                  (do
                    (line! state (str "[grog] " (.getMessage e)))
                    (when-let [p (not-empty (str/trim (str (:partial-text d))))]
                      (chat/publish! state {:type :content
                                            :content {:type "text"
                                                      :text (str p "\n\n*[cut off by the stall above — "
                                                                   "this is the partial answer]*")}})
                      (try (project-dialog/append-turn! :assistant p) (catch Throwable _ nil))))

                  :else
                  (line! state (str "[grog] loop error: " (.getMessage e))))))
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

        clear-fn!
        (fn []
          ;; `/clear` from the engine's point of view. `grog.chat` drops its
          ;; `:history` and publishes the transcript wipe; THIS is the part only
          ;; the adapter can do, because the context a turn replays lives here:
          ;;
          ;;   !messages      the conversation the next `run-turn!` prepends to —
          ;;                  left alone, a "cleared" session still sent every
          ;;                  prior turn (and every tool result) to the model,
          ;;                  which is exactly the reported "context isn't reset"
          ;;   !loaded-tools  tools `tool_search` attached FOR that conversation;
          ;;                  a fresh one re-searches rather than inheriting them
          ;;   !tasks         the todo list — a plan for a conversation that is
          ;;                  gone would otherwise keep being "resumed"
          ;;   !steer/!cancel leftovers from a stopped turn
          ;;
          ;; NOT reset: `!mcp` (a transport, not context — and restarting it per
          ;; `/clear` would be a subprocess churn the user never asked for) and
          ;; the `:usage` session totals (money already spent is not unspent;
          ;; wiping that number would understate the cost of the session).
          (reset! !messages [])
          (reset! !loaded-tools [])
          (reset! !tasks [])
          (reset! !steer [])
          (reset! !cancel false)
          (swap! (:usage state) assoc :turn-tokens 0 :turn-cost 0.0)
          ;; SAY what went, so "cleared" is a checkable claim rather than a hope:
          ;; the failure mode this fixes is a wipe that looked like it worked.
          ;;
          ;; `line!` (a PUBLISHED event), not `println`. `println` only reaches the
          ;; turn's console writer — which the CLI has and a HEADLESS/remote client
          ;; does not, so the claim was invisible exactly where the state is most
          ;; hidden. It is a statement about STATE, so it belongs on the wire.
          (line! state (str "context cleared — 0 prior messages; loaded tools and the todo list "
                            "reset (MCP connection and cost totals kept)")))

        set-model-fn
        (fn [nm source & [opts]]
          (let [global? (boolean (:global opts))
                url (some-> (:url opts) str str/trim not-empty)
                mid (some-> nm str str/trim not-empty)]
            ;; A pick made against a transport persists that transport too, so the
            ;; model keeps its own endpoint (`[:llm :url]`) instead of silently
            ;; inheriting a stale one. Applied on the next start (config is
            ;; file-snapshot).
            (when url
              (try (models/save-model-url! url)
                   (catch Throwable e (sdbg! state "save model url failed:" (.getMessage e)))))
            (when mid
              (try
                (if global?
                  (models/save-eca-model! mid)
                  (projects/set-project-model! project mid))
                (catch Throwable e (sdbg! state "save model failed:" (.getMessage e))))
              ;; RECENTS: every pick lands at the front of the LRU shortlist, so
              ;; the models you actually switch between are one click away next
              ;; time. `source` is the picker's PLACE, which is not a provider —
              ;; record the resolved provider instead so the entry can be
              ;; LABELLED without re-deriving it.
              (try
                (let [r (resolve-llm mid source nil)]
                  (models/push-recent! (:url r) (:model r)
                                       (models/provider-prefix-for-url (:url r))))
                (catch Throwable e (sdbg! state "recents push failed:" (.getMessage e)))))
            (when mid
              (let [ms (model-status mid source)]
                (reset! (:model state) (:model ms))
                (reset! (:model-label state) (:label ms))
                (reset! (:provider state) (:provider ms))
                (reset! (:url state) (:url ms))
                (chat/publish! state (assoc ms :type :model))
                (let [r (resolve-llm mid source nil)]
                  (line! state (str (if global? "global " "project ")
                                    "model set: " (:model ms)
                                    " → " (:url r) " · " (:model r)
                                    (when-not (:key r) "  [NO KEY — set one with /secret]"))))))))
        set-model-1 (fn [nm] (set-model-fn nm nil))

        set-trust-fn
        (fn [on?]
          (let [next (if (nil? on?) (not @(:trust state)) on?)]
            (reset! (:trust state) next)
            (chat/publish! state {:type :trust :value next})
            (line! state (str "trust (yolo) mode: "
                              (if next "ON — tool calls auto-approved" "off")))))

        ;; `/model` inside a session: resolve what the user typed against the
        ;; numbered recents list / a NAMED model / a raw id, then switch for real
        ;; (the same path a picker click takes, recents push included).
        model-command-fn
        (fn [line]
          (let [m (re-matches #"(?i)^/model(?:\s+(.*))?$" (str/trim (str line)))
                tail (str/trim (str (or (second m) "")))
                toks (when-not (str/blank? tail) (str/split tail #"\s+"))
                head (some-> (first toks) str str/lower-case)]
            (cond
              (= "reset" (str/lower-case tail))
              (line! state "No session override to clear — grog runs the configured model. Use /model <n> or /model <name>.")

              ;; the NAMED list is editable from the composer too, so the console
              ;; and the Models dialog cannot drift: `/model new <endpoint>` builds
              ;; a choice from a baked-in template, `/model rm <name>` drops one.
              (and (= "new" head) (second toks))
              (try
                (let [{:keys [choice]} (models/seed-choice! (second toks) (nth toks 2 nil))]
                  (line! state (str "added model choice " (:name choice) "  "
                                    (config/model-label choice)))
                  (line! state (str "  pick it with /model " (:name choice)
                                    (when (nil? (:model choice))
                                      "  (no model id yet — start the server and pick one)"))))
                (catch Throwable e (line! state (.getMessage e))))

              (= "rm" head)
              (if-let [name (second toks)]
                (try
                  (models/remove-choice! name)
                  (line! state (str "removed model choice " (models/choice-name name)))
                  (catch Throwable e (line! state (.getMessage e))))
                (line! state "usage: /model rm <name>"))

              :else
              (let [c (try (config/resolve-model-choice tail) (catch Throwable _ {:kind :list}))]
                (case (:kind c)
                  :list
                  (do (line! state (str "current: " (or @(:model-label state) @(:model state) "unset")))
                      (let [choices (try (config/model-choices) (catch Throwable _ []))]
                        (if (seq choices)
                          (line! state (str "  " (str/join "   "
                                                           (map (fn [{:keys [n label current?]}]
                                                                  (str n ". " label (when current? " (current)")))
                                                                choices))))
                          (line! state "no recents yet — pick one in the Models dialog, or /model <name>")))
                      (if-let [named (seq (config/named-models))]
                        (line! state (str "named: "
                                          (str/join "   "
                                                    (map (fn [{:keys [name model]}]
                                                           (str name (when (nil? model) " (NO MODEL ID)")))
                                                         named)))
                          (line! state "no named models — the Models dialog names them; /model new <endpoint> also works")))

                  :unknown
                  (line! state (str "no model numbered " (:n c) " — the list has " (:max c)
                                    " entries; run /model to see them"))

                  :incomplete
                  (line! state (:msg c))

                  :choice
                  ;; set-model-fn persists the pick, updates the session state and
                  ;; pushes the recents entry. `source` is nil: the id carries its
                  ;; own provider, or keeps the configured transport.
                  (set-model-fn (:model c) nil
                                (cond-> {}
                                  (:url c) (assoc :url (:url c))
                                  ;; a numbered recent or a NAMED model was, by
                                  ;; definition, a deliberate choice — write it
                                  ;; globally so it survives a project switch.
                                  (or (:from-recent? c) (:named? c)) (assoc :global true)))))))))

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
         :set-model! set-model-fn :set-trust! set-trust-fn}]
    (.start (chat/chat-worker! state queue
                               {:send send-fn
                                :set-model! set-model-1
                                :set-yolo! set-trust-fn
                                :model-command! model-command-fn
                                :console console
                                :clear! clear-fn!
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
  (set-trust! [_ id on?] ((:set-trust! (sess id)) on?))
  (subscribe! [_ id f] (chat/subscribe! (:state (sess id)) f))
  (unsubscribe! [_ id f] (chat/unsubscribe! (:state (sess id)) f)))

(defn install!
  "Install this adapter as the active `grog.client` implementation."
  []
  (client/set-impl! (->LoopClient)))
