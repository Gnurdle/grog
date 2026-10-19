(ns grog.agent.loop
  "The turn loop — the piece ECA owned that grog now owns itself.

  Shape: build messages → stream a completion → if it asked for tools, run them
  and append their results → repeat, up to a step cap. Events are emitted with the
  SAME segment kinds grog's client already renders (:thinking :answer :tool
  :usage), so the client wire protocol is a translation, not a redesign.

  Decisions worth the ink:

  1. The assistant message that carries tool calls is echoed back to the provider
     with `arguments` RE-SERIALISED as a JSON string. That is what the wire format
     wants, and getting it wrong is a silent 400 on the next turn.
  2. A tool call whose arguments did not parse is NOT executed and NOT dropped:
     it becomes an error result carrying the raw text, so the model — and the log —
     see that the arguments arrived mangled. That failure mode has a name in this
     project (the DSML recovery patch in the ECA fork) and the honest thing is to
     surface it, never to guess at the missing arguments.
  3. A tool that MUTATES the world is gated by `:approve`. The gate is supplied by
     the caller (the desktop client bridges it to the approval dialog); with no
     gate supplied the loop runs everything, which is why the CLI passes
     `:approval :none` explicitly rather than relying on a default.
  4. `:steer` is drained at STEP boundaries, not mid-stream. ECA could inject into
     a running stream; a blocking single-threaded transport cannot without a
     second connection. Steering between steps is real, useful and honest — and it
     is stated here rather than implied.
  5. The context budget is enforced by COMPACTION, not by hoping. Old tool results
     are elided (they are the bulk and the least needed) with a visible marker, so
     a long session degrades in a way the model can see and react to.
  6. The toolbelt is LAZY. With more than `default-tool-threshold` tools available
     (grog attaches ~65 MCP schemas; a measured turn spent 8,611 of 8,611 prompt
     tokens on schema + conversation), only `tool_search` + the tools the session
     has LOADED go on the wire, and a tool attaches to the conversation the moment
     the model searches for it. See `grog.agent.tool-search` for the ranking and
     the reasoning; the short version is that paying for 65 schemas to call 2 is
     the single largest waste this loop had."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [grog.agent.mcp :as mcp]
            [grog.agent.tool-search :as tool-search]
            [grog.agent.tools :as tools]
            [grog.agent.transport :as transport]))

(def ^:private default-max-steps
  "UNCAP, BY DEFAULT. `nil` means the loop runs until the model returns text —
  exactly what `config/chat-tool-loop-limit`'s docstring and README.md:87 promise
  ('unlimited by default … uncapped unless you set :cli :chat-tool-loop-limit').

  It used to be 12, and the client coerced an unset knob into it
  (`(or (config/chat-tool-loop-limit) 12)`) — so a documented 'no limit' was
  silently a hard 12-step ceiling, and long tasks died mid-flight. A cap is still
  available and still respected: pass `:max-steps n`, or set
  `:cli :chat-tool-loop-limit` in grog.edn.

  Uncapped is only safe WITH the repetition guard below (`step-signature`): the
  thing a step cap actually protected against is a model looping on the same call
  forever, and that is now named and refused at the third identical call instead
  of being inferred from a step count 12 too late (improvements #15)."
  nil)
(def ^:private mcp-prefix "grog-mcp__")

(def ^:private default-tool-threshold
  "Above this many tools, the belt is loaded lazily. Below it, sending every
  schema is cheaper than a search round-trip — and a small toolbelt is the case
  where laziness buys nothing."
  20)

(def ^:private default-max-loaded
  "THE CEILING, made explicit. Lazy loading removes the *initial* cost of 65
  schemas, but the loaded set grows MONOTONICALLY — one search attaches up to 12,
  so after five searches an unbounded set is 71 schemas, worse than the 65 the
  feature exists to avoid. So a step's wire carries at most
  (local + 1 meta + max-loaded) schemas, and the OLDEST loaded tool is dropped
  when the cap is passed. Dropping is safe because re-attaching is one cheap
  search; the model searched for the tool once and can search again."
  40)

(def default-system-prompt
  (str "You are grog, an agent running on the user's own machine. You have tools: "
       "the unprefixed ones are read/write/list/grep/shell, and any grog-mcp__* ones come "
       "from grog's MCP servers (memory, project search, office, imaging, odoo, …).\n\n"
       "Work by acting: read the file before editing it, run the command rather than "
       "describing it, and never claim an effect you did not verify. When a tool "
       "reports an error, believe it and adjust — do not restate the error as success.\n\n"
       "To find or search files, use `find_files` and give it an explicit `root`. It walks "
       "ONLY the one filesystem that root lives on, so it cannot fall into another mount, "
       "/proc, /sys or a network/FUSE share. Start from the project directory — that needs "
       "no permission; to search elsewhere, ask the user first, then re-call with "
       "allow_outside: true (`grep` and `directory_tree` obey the same rule). Do NOT reach for "
       "`find /`, `grep -r /` or any search from a "
       "filesystem root: there is no safe \"search the whole machine\" here, and on this "
       "machine it would wander into large unrelated volumes. Use `shell` find/grep only "
       "for narrow work that is already pointed at a specific directory.\n\n"
       "Answer the user in prose once the work is done, and say what you actually did."))

;; --- approval policy ---------------------------------------------------------

(def ^:private mutating-tools
  "Tools that can change the world. These are the ones `:approval :mutating`
  gates. MCP tools are NOT in this set on purpose: grog's own tool servers carry
  their own write switches (`grog-odoo`'s `:allow-write`, default false), and
  gating all 76 of them would train the user to click Approve."
  #{"write_file" "edit_file" "move_file" "shell" "git"})

(def ^:private argument-gates
  "Tools that are normally FREE but whose ARGUMENTS can point somewhere they
  should not. The path-scoped read tools are the set: reading inside the project
  needs no permission, but a call asking to leave it (`allow_outside`) is the
  human's call — and each tool's own handler still refuses an unapproved outside
  root, so a gate-less loop cannot silently wander."
  {"find_files"     tools/root-approval-required?
   "grep"           tools/root-approval-required?
   "directory_tree" tools/root-approval-required?})

(defn- summarize
  "One line for the approval dialog — the model's own words are not enough."
  [name args]
  (let [a (or args {})]
    (case name
      "shell" (str "run: " (str (:command a)))
      "write_file" (str "write " (:path a) " (" (count (str (:content a))) " chars)")
      "edit_file" (str "edit " (:path a))
      "move_file" (str "move " (:source a) " -> " (:destination a))
      "git" (str "git " (str/join " " (:args a)))
      "find_files" (str "search files under " (:root a)
                        (when (:query a) (str " for " (pr-str (:query a)))))
      "grep" (str "grep " (pr-str (:pattern a)) " under " (:path a))
      "directory_tree" (str "list " (:path a))
      (str name " " (pr-str a)))))

(defn- needs-approval?
  [approval name {:keys [args] :as ctx}]
  (case (or approval :mutating)
    :none     false
    :all      true
    :mutating (or (contains? mutating-tools name)
                  ;; ARGUMENT-aware: a normally-free tool whose CALL points
                  ;; somewhere it should not. find_files is the first — the project
                  ;; directory is free, `allow_outside` is not.
                  (boolean (when-let [g (get argument-gates name)] (g ctx))))
    false))

(def ^:private repetition-warn 2)
(def ^:private repetition-refuse 3)

(defn- canonical
  "Arguments → a STRING that depends only on the data, not on key order.

  Cheshire has no `:sort-keys` (the first version of this assumed it did, and the
  test caught it: a 3rd identical call with the keys in a different order scored a
  fresh signature and ran). So the keys are sorted HERE, recursively, and the
  signature is stable for equal maps however they were spelled on the wire."
  [x]
  (cond
    (map? x)  (str "{" (str/join "," (map (fn [[k v]] (str (canonical k) ":" (canonical v)))
                                          (sort-by (comp str key) x))) "}")
    (coll? x) (str "[" (str/join "," (map canonical x)) "]")
    :else     (json/generate-string x)))

(defn- step-signature
  "A stable string for one tool call — name plus canonicalised arguments. Equality
  of signature = equality of call, and a model that repeats one is not making
  progress."
  [name args]
  (try (str name " " (canonical (or args {})))
       (catch Throwable _ (str name " " (pr-str (or args {}))))))

;; --- context budget ----------------------------------------------------------

(def ^:private default-max-context-tokens 120000)

(defn- est-tokens
  "A deliberately rough estimate (4 chars ≈ 1 token). It does not need to be
  exact — it needs to fire BEFORE the provider 400s."
  [msgs]
  (quot (reduce + 0 (map #(count (str (:content %))) msgs)) 4))

(defn- compact-messages
  "Elide the middle of the conversation when it exceeds `budget`.

  Keeps: the system message, the FIRST user message (the task), and the last 12
  messages (the live working set). What goes is the middle — overwhelmingly old
  tool results — and it goes with a marker saying so, because a model that thinks
  it has seen the whole history is worse than one that knows it has not."
  [msgs budget]
  (if (<= (est-tokens msgs) budget)
    msgs
    (let [sys? (= "system" (:role (first msgs)))
          sys (when sys? (first msgs))
          rest-msgs (vec (if sys? (rest msgs) msgs))
          head (first rest-msgs)
          tail (vec (take-last 12 rest-msgs))
          elided (- (count rest-msgs) (count tail) (if head 1 0))]
      (vec (concat (when sys [sys])
                   (when head [head])
                   (when (pos? elided)
                     [{:role "user"
                       :content (str "[" elided " earlier message(s) were elided to fit the context "
                                     "budget. Re-read a file or re-run a command if you need that "
                                     "detail again.]")}])
                   tail)))))

;; --- dispatch ----------------------------------------------------------------

(defn- dispatch!
  "Route one tool call: the loop's own `tool_search`, MCP (prefixed), or the
  in-process table. A blank result is turned into a visible marker — an empty
  string reads as success to a model, and in this session's own history a tool
  that returned nothing quietly became 'the file was written'."
  [{:keys [local mcp-conn !deferred !loaded attach!]} name args]
  (let [r (cond
            ;; the meta-tool: it MUTATES the session (attaches schemas), which is
            ;; why it is dispatched here rather than being a static handler.
            (= name tool-search/search-tool-name)
            (let [query (str (:query args))
                  hits (tool-search/search @!deferred query)
                  dropped (when attach! (attach! hits))
                  remaining (remove (comp (set (map tool-search/tool-name hits)) tool-search/tool-name)
                                    @!deferred)]
              {:text (str (tool-search/render-result query hits (map tool-search/tool-name remaining))
                          (when (seq dropped)
                            (str "\n(dropped " (count dropped) " older loaded tool(s) to stay under the cap: "
                                 (str/join ", " dropped) " — search again if you need one)")))
               :error? false
               :attached (mapv tool-search/tool-name hits)
               :dropped (vec dropped)})

            (and mcp-conn (str/starts-with? name mcp-prefix))
            (let [server-tool (subs name (count mcp-prefix))]
              (try
                (let [{:keys [text error? images]} (mcp/call-tool! mcp-conn server-tool args)]
                  {:text (str text (when (pos? (long (or images 0)))
                                     (str "\n[MCP returned " images " image block(s)]")))
                   :error? error?})
                (catch Throwable t
                  {:text (str "MCP call " server-tool " failed: " (.getMessage t)) :error? true})))

            :else (tools/call local name args))]
    ;; A HANDLER MUST NEVER BE ABLE TO CRASH THE LOOP BY RETURNING THE WRONG SHAPE.
    ;; This was `(assoc r :text …)` guarded by `(str/blank? (:text r))` — and a
    ;; handler returning a bare String made `(:text r)` nil, `(str nil)` "", blank?
    ;; true, and `assoc` on a String threw ClassCastException, killing the turn the
    ;; moment the agent called `ask_user` (2026-10-19). Accept a map, or wrap
    ;; anything else as text, and say so rather than pretending it was shaped right.
    (let [r (cond
              (map? r) r
              (nil? r) {:text "(the tool returned nil — do not read that as success)" :error? true}
              :else {:text (str r) :error? false :unwrapped true})]
      (if (and (not (:error? r)) (str/blank? (str (:text r))))
        (assoc r :text "(the tool returned no output — do not read that as success)")
        r))))

(defn run-turn!
  "Run one turn to completion.

  {:llm {:url :key :model} :message \"…\" :cwd \"…\" :mcp <conn|nil>
   :on-event (fn [segment]) :max-steps n :history [...] :system \"…\"
   :cancel <atom> :steer <atom of [string]> :approve <fn> :approval :none|:mutating|:all
   :approved-tools #{name} :max-context-tokens n
   :extra-tools [schema] :local-handlers {name fn}
   :tool-threshold n :loaded-tools [name]}

  Returns {:status :done|:max-steps|:cancelled :messages [...] :steps n :usage …
           :text :reasoning :tool-log [...] :loaded-tools [name]}.

  `:approve` is `(fn [{:keys [name arguments summary]}] -> :approve|:approve-tool
  |:reject|:yolo)`; it BLOCKS until a human answers. Returning `:approve-tool`
  whitelists that tool for the rest of the turn; `:yolo` whitelists everything.

  `:max-steps` is OPTIONAL. Omitted or nil → NO cap: the loop runs until the model
  answers. The repetition guard still applies (an identical call is warned on the
  2nd and REFUSED on the 3rd), so 'uncapped' cannot mean 'spinning forever'. Pass a
  positive integer to cap it.

  Tool loading: when the toolbelt is bigger than `:tool-threshold` (default 20),
  the MCP tools are DEFERRED — the model sees `tool_search` plus the local/session
  tools plus whatever a previous turn loaded, and `tool_search` attaches the rest
  on demand. Set `:tool-threshold` to a large number to send every schema (the
  pre-lazy behaviour, and the A/B in `scripts/tool-search-test.clj`)."
  [{:keys [llm message cwd mcp on-event max-steps history system cancel steer
           approve approval approved-tools max-context-tokens tool-threshold
           extra-tools local-handlers loaded-tools max-loaded-tools]}]
  (let [;; `:or` does NOT cover a key that is present-but-nil (a caller reading an
        ;; unset config knob passes nil), and `(> step nil)` is a NullPointer.
        ;; Normalise here, once.
        max-steps (or max-steps default-max-steps)
        max-context-tokens (or max-context-tokens default-max-context-tokens)
        tool-threshold (or tool-threshold default-tool-threshold)
        !cancel (or cancel (atom false))
        emit (fn [seg] (when on-event (on-event seg)))
        local (tools/handlers cwd (or local-handlers {}))
        local-tools (vec (tools/provider-tools cwd (or extra-tools [])))
        mcp-tools (when mcp (vec (mcp/->provider-tools (mcp/list-tools! mcp) mcp-prefix)))
        mcp-tool? (set (map tool-search/tool-name mcp-tools))
        ;; LAZY or FULL. Lazy hides the MCP long tail behind `tool_search`; the
        ;; tools a previous turn already loaded (`:loaded-tools`) stay attached so
        ;; a conversation does not re-search for the same tool every turn.
        lazy? (> (+ (count local-tools) (count mcp-tools)) (long tool-threshold))
        loaded-in (if lazy? (set (or loaded-tools [])) (set mcp-tool?))
        !deferred (atom (if lazy? (vec (remove (comp loaded-in tool-search/tool-name) mcp-tools)) []))
        !loaded (atom (set (filter mcp-tool? loaded-in)))
        ;; THE CEILING IN PRACTICE. `!last-use` orders the loaded set by when each
        ;; tool was last attached, so when the cap is passed the OLDEST goes. An
        ;; ordered vector, not a set: a set of strings has no recency to drop by.
        !last-use (atom (vec (filter mcp-tool? loaded-in)))
        max-loaded (long (or max-loaded-tools default-max-loaded))
        ;; Attach `hits`, and enforce the cap. Returns how many were dropped so the
        ;; caller can SAY so — a tool silently vanishing is how a model ends up
        ;; calling something that is no longer on the wire.
        attach! (fn [hits]
                  (let [names (mapv tool-search/tool-name hits)]
                    (swap! !loaded into names)
                    (swap! !last-use (fn [v] (vec (concat (remove (set names) v) names))))
                    (let [keep (set (take-last max-loaded @!last-use))
                          dropped (vec (remove keep @!last-use))]
                      (when (seq dropped)
                        (swap! !last-use (fn [v] (vec (filter keep v))))
                        (swap! !loaded #(apply disj % dropped)))
                      dropped)))
        prompt-tools (fn []
                       (vec (concat local-tools
                                    (when lazy? [(tool-search/search-tool (count @!deferred))])
                                    (filter (comp @!loaded tool-search/tool-name) mcp-tools))))
        tool-provider-tools (fn [] (vec (concat local-tools (or mcp-tools []))))
        !approved (atom (set (or approved-tools #{})))
        ;; HOW MANY TIMES each identical call has been made this turn. The loop
        ;; detector; see `repetition-*` and the guard at the call site.
        !reps (atom {})
        !msgs (atom (vec (concat (when system [{:role "system" :content system}])
                                 history
                                 [{:role "user" :content message}])))
        !log (atom [])
        !usage (atom nil)
        gate! (fn [name args]
                (cond
                  (contains? @!approved name) :approve
                  (not (needs-approval? approval name {:args args :cwd cwd})) :approve
                  (nil? approve) :approve          ; no gate supplied = caller opted out
                  :else (let [d (approve {:name name :arguments args :summary (summarize name args)})]
                          (when (= :approve-tool d) (swap! !approved conj name))
                          (when (= :yolo d) (reset! !approved (into @!approved (map tool-search/tool-name (tool-provider-tools)))))
                          (or d :reject))))]
    (loop [step 1]
      (cond
        @!cancel {:status :cancelled :messages @!msgs :steps (dec step) :tool-log @!log :usage @!usage
                  :loaded-tools (vec @!loaded)}
        (and max-steps (> step (long max-steps)))
        ;; A step cap is a ceiling, not a stop condition. The log showed 11 turns
        ;; ending here, one after 2,549,158 ms (42 minutes) — with no word on WHAT
        ;; the model was doing at the ceiling. Scorers already recover the last
        ;; assistant text for exactly this reason; the live loop did not.
        (let [last-text (or (->> (reverse @!msgs)
                                 (filter #(and (= "assistant" (:role %))
                                               (not (str/blank? (str (:content %))))))
                                 first :content str)
                            "")
              names (->> (take-last 4 @!log) (map :name) (remove nil?) (str/join " → "))]
          (emit {:kind :tool
                 :text (str "[max-steps] hit the " max-steps "-step cap"
                            (when (seq names) (str " after: " names))
                            " — the task was not finished; raise :max-steps or split it")})
          {:status :max-steps :messages @!msgs :steps max-steps :tool-log @!log :usage @!usage
           :text last-text :loaded-tools (vec @!loaded)})
        :else
        (let [_ (when steer
                  ;; steering is drained at a STEP boundary — see the ns docstring
                  (let [pending (let [v @steer] (reset! steer []) v)]
                    (when (seq pending)
                      (swap! !msgs into (map (fn [s] {:role "user" :content (str s)}) pending))
                      (emit {:kind :tool :text (str "[steer] " (str/join " | " pending))}))))
              _ (reset! !msgs (compact-messages @!msgs max-context-tokens))
              {:keys [text reasoning tool-calls malformed usage]}
              (transport/stream-chat! (assoc llm
                                             :messages @!msgs
                                             ;; RE-COMPUTED EACH STEP (prompt-tools is
                                             ;; a fn, not a value): a `tool_search` this
                                             ;; step attaches a schema for the NEXT one.
                                             :tools (prompt-tools)
                                             :on-delta (fn [c] (emit {:kind :answer :text c}))
                                             :on-reasoning (fn [c] (emit {:kind :thinking :text c}))))]
          (when usage (reset! !usage usage))
          (doseq [m malformed]
            (emit {:kind :tool :text (str "[bad arguments] " (:why m) " raw=" (:raw m))}))
          (if (empty? tool-calls)
            (do (emit {:kind :usage :text (pr-str (or usage {}))})
                {:status :done :messages @!msgs :steps step :usage @!usage
                 :text text :reasoning reasoning :tool-log @!log
                 :loaded-tools (vec @!loaded)})
            (do
              (swap! !msgs conj
                     {:role "assistant"
                      :content (when (seq text) text)
                      :tool_calls (mapv (fn [c] {:id (:id c) :type "function"
                                                 :function {:name (:name c)
                                                            :arguments (json/generate-string (or (:arguments c) {}))}})
                                        tool-calls)})
              (doseq [c tool-calls]
                (let [;; THE REPETITION GUARD (improvements #15). A step cap is a
                      ;; crude proxy for "the model is stuck"; this is the thing
                      ;; itself. Count identical calls (same name, same args) this
                      ;; turn: the 2nd warns, the 3rd is refused rather than run.
                      ;; Without it, uncapping the loop would mean a stuck model
                      ;; runs until the user notices — which is what the cap was
                      ;; really protecting against.
                      sig (step-signature (:name c) (:arguments c))
                      seen (get (swap! !reps update sig (fnil inc 0)) sig)
                      repeat-decision (cond (= seen repetition-refuse) :refuse
                                            (= seen repetition-warn)   :warn
                                            :else nil)
                      decision (or repeat-decision
                                   (if (:invalid-json c) :reject (gate! (:name c) (:arguments c))))
                      {rtext :text rerr :error? rimg :image rattached :attached rdropped :dropped}
                      (cond
                        (= :refuse decision)
                        {:text (str "tool call REFUSED — this is identical call number " seen " to "
                                    (:name c) " this turn (same arguments). Repeating it will "
                                    "not change the result. Stop, say what you have, and either change "
                                    "the approach or ask the user.") :error? true}

                        (:invalid-json c)
                        {:text (str "tool call REJECTED: arguments were not valid JSON. raw="
                                    (pr-str (:raw (first (filter #(= (:index %) (:index c)) malformed)))))
                         :error? true}

                        (= :reject decision)
                        {:text (str "tool call REJECTED by the user — " (:name c)
                                    " was NOT run. Ask before retrying, or choose another approach.")
                         :error? true}

                        :else (dispatch! {:local local :mcp-conn mcp :attach! attach!
                                          :!deferred !deferred :!loaded !loaded}
                                         (:name c) (:arguments c)))]
                  (swap! !log conj {:name (:name c) :args (:arguments c)
                                    :decision decision
                                    :error? (boolean rerr) :text (str rtext)
                                    :invalid-json (boolean (:invalid-json c))})
                  (emit {:kind :tool :text (str (:name c) " " (pr-str (or (:arguments c) {}))
                                                (when (= :reject decision) "  → REJECTED")
                                                (when rerr "  → ERROR"))})
                  ;; the warning half of the guard: the call still RUNS, but the
                  ;; repeat is said out loud so the next step is the model's choice
                  ;; rather than a surprise at step 12.
                  (when (= :warn repeat-decision)
                    (emit {:kind :tool :text (str "  ⚠ repeated call (" seen "× this turn): "
                                                  (:name c) " with identical arguments — if the result "
                                                  "is the same, change the approach.")}))
                  ;; a search that attached schemas is worth ONE visible line: the
                  ;; toolbelt changed, and a silent change is how a model ends up
                  ;; unsure what it can call.
                  (when (seq rattached)
                    (emit {:kind :tool :text (str "  loaded " (count rattached) " tool(s): "
                                                  (str/join ", " rattached))}))
                  (when (seq rdropped)
                    (emit {:kind :tool :text (str "  dropped (cap): " (str/join ", " rdropped))}))
                  (emit {:kind :tool :text (str "  " (first (str/split-lines (str rtext))))})
                  (swap! !msgs conj {:role "tool" :tool_call_id (:id c)
                                     :content (str (when rerr "ERROR: ") (tools/clip rtext))})
                  ;; an image a tool produced is attached as a USER message — the
                  ;; only place an OpenAI-compatible provider will look at pixels.
                  (when rimg
                    (swap! !msgs conj
                           {:role "user"
                            :content [{:type "text" :text (str "Image from " (:name c) ":")}
                                      {:type "image_url"
                                       :image_url {:url (str "data:" (:media_type rimg)
                                                             ";base64," (:base64 rimg))}}]}))))
              (recur (inc step)))))))))
