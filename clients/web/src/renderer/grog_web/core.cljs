(ns grog-web.core
  "grog web client renderer: tabs, status bar, trust/model, tool-approval
  and LLM-question dialogs. Transport lives in Electron main; this ns only sees
  window.grogAPI (see doc/clients/web-client-plan.md).

  The renderer is THIN: no ECA/MCP/native deps. ECA's raw `:content` is
  normalized into the role vocabulary here."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [reagent.dom :as rdom]
            [re-frame.core :as rf]
            [grog-web.voice :as voice]
            [grog-web.export :as export]
            [grog-web.md :as md]))

;; --- helpers ---------------------------------------------------------------

;; Forward declaration: `!follow?` is defined with the transcript further down,
;; but the `:send` handler above uses it (sending re-pins to the bottom).
(declare !follow?)

(defn- usage-text
  "Compact one-line summary of an ECA `usage` content."
  [c]
  (str (:sessionTokens c) " tok"
       (when (:lastMessageCost c) (str " · msg $" (:lastMessageCost c)))
       (when (:sessionCost c) (str " · $" (:sessionCost c)))))

;; --- tok/s: the LIVE counter -------------------------------------------------
;;
;; WHERE THE LIVE NUMBER COMES FROM, and why it cannot come from the spine. A
;; provider reports `usage` — and therefore its own `completion_tokens` — in the
;; FINAL frame of a stream, so a number derived from it can only ever be an
;; AFTER-THE-FACT average. The renderer is the only party that watches the answer
;; ARRIVE, delta by delta, so it is the only party that can show a rate WHILE the
;; model is still talking. That is the whole reason this counter exists here and
;; not in the status line's token meter.
;;
;; IT IS AN ESTIMATE, AND IT SAYS SO. Deltas are characters, and grog's own
;; convention (`grog.agent.context/chars-per-token`) is ~4 chars per token, so the
;; count is approximate — deliberately the SAME constant the spine uses for its
;; budget arithmetic, so the two numbers at least agree about what a token is. The
;; measured value arrives with the turn (see the loop's `:rate` segment, which
;; the client draws as a transcript line); this is what you watch in the meantime.
;;
;; ON TIMERS. The status bar's "no JS, no timers" comment below is about the TOKEN
;; METER, and it is right there: a timer per update would be pure waste, because
;; that number only changes when a report arrives. This counter is the opposite
;; case — it must keep ticking while NOTHING arrives, since a rate that freezes
;; whenever the model pauses is how a stall goes unnoticed. So there is exactly
;; ONE timer per streaming session (4 Hz, not 60), it starts on the first delta,
;; it stops itself the moment the session is no longer running, and it exists only
;; while a turn streams. Reagent re-renders on the atom it writes.

(def ^:private chars-per-token
  "≈4 chars/token, the SAME convention the spine uses for its budget arithmetic
  (`grog.agent.context/chars-per-token`). It is a rough factor either way — the
  point of using the same one is that the status bar and the context meter at
  least agree about what a token is."
  4.0)

(def ^:private rate-window-ms
  "How far back the rate looks. A whole-turn average is dominated by the opening
  burst and a per-delta rate is unreadable noise, so this is a short trailing
  window — long enough to be steady, short enough that a model which has slowed
  down stops looking fast."
  2000)

(def ^:private rate-render-ms
  "How often the counter is recomputed and repainted. 4 Hz reads as live without
  re-rendering the status bar 60 times a second."
  250)

(def ^:private generation-pause-ms
  "An inter-delta gap longer than this is a PAUSE, not generation: the wait for
  the first token, a tool call between steps, or the model thinking before it
  speaks. Those gaps are excluded from the rate's denominator. This is the live
  counter's equivalent of the loop's `:first-frame-ms` subtraction — without it a
  local model that spends 15s prefilling reads as 2 tok/s when it is producing
  60."
  1000)

(def ^:private rate-stale-ms
  "Stop the ticking timer once nothing has arrived for this long. The turn may
  still be alive (a tool call, a long prefill) — the counter just stops claiming
  to measure something, and restarts on the next delta. This is how the timer
  ends without the tick having to ask re-frame whether the session is running,
  which it cannot do from outside a reactive context anyway."
  2500)

;; One counter per session — two tabs can stream at once and each has its own
;; rate. `r/atom`, NOT a plain atom: the status bar derefs this while rendering,
;; and reagent only re-renders a component that derefs a REACTIVE atom.
;;
;; `id -> {:samples [{:at ms :chars n :gen-gap ms}]  ; trailing window
;;         :est-tok n                                ; this turn, chars/4
;;         :last-at ms :timer id :tick n}`
(defonce ^:private !rates (r/atom {}))

(defn- now-ms [] (.now js/Date))

(def ^:private max-samples
  "Hard cap on retained deltas, independent of the time window: a model emitting
  one character per chunk would otherwise put thousands of samples in the vector
  between two prunes."
  400)

(defn- prune-samples!
  "Drop samples older than the window from `samples` (oldest first)."
  [samples cutoff]
  (let [kept (vec (drop-while #(< (:at %) cutoff) samples))]
    (if (> (count kept) max-samples) (vec (take-last max-samples kept)) kept)))

(defn- rate-of
  "The live rate for `st` as `[tok-per-sec est-tokens]`, or nil when it cannot be
  computed without dividing by nearly nothing.

  Numerator: the characters in the trailing window, over the chars-per-token
  factor. Denominator: the GENERATION time in that same window — the sum of the
  per-sample gaps that were not pauses. Under ~250ms of generation time the
  divisor is too small to mean anything (the first delta or two would read as
  thousands of tok/s), so nil is returned and the bar stays quiet."
  [{:keys [samples est-tok]}]
  (let [cutoff (- (now-ms) rate-window-ms)
        win (prune-samples! samples cutoff)]
    (when (seq win)
      (let [chars (reduce + 0 (map :chars win))
            gen   (reduce + 0 (map :gen-gap win))]
        (when (>= (long gen) 250)
          [(/ (/ (double chars) chars-per-token) (/ (double gen) 1000.0))
           (long (or est-tok 0))])))))

(defn live-rate
  "The live generation rate for `sid` as `[tok-per-sec est-tokens]`, or nil when
  there is not yet enough measured generation time to say.

  PUBLIC on purpose: the status bar and the offline renderer tests must read the
  SAME function. A test that re-implemented the window arithmetic would keep
  passing after the real one broke."
  [sid]
  (rate-of (get @!rates sid)))

(defn- stop-rate!
  "Stop the ticking timer for `sid` and forget its samples."
  [sid]
  (when-let [id (:timer (get @!rates sid))]
    (js/clearInterval id))
  (swap! !rates dissoc sid)
  nil)

(defn- tick-rate!
  "Record one delta of `chars` characters for `sid`, now.

  Generation time is accumulated HERE, per delta, from the gap since the previous
  one — a gap longer than `generation-pause-ms` contributes ZERO, so prefill and
  tool pauses never enter the denominator. The first delta also contributes zero:
  everything before it was the wait for the first token."
  [sid chars]
  (let [t (now-ms)
        {:keys [timer] :as st} (get @!rates sid)
        last-at (:last-at st)
        gap (when last-at (- t (long last-at)))
        gen-gap (if (and gap (<= (long gap) (long generation-pause-ms))) (long gap) 0)
        est (+ (long (or (:est-tok st) 0))
               (long (Math/round (/ (double chars) chars-per-token))))]
    (swap! !rates assoc sid
           {:samples (conj (vec (:samples st)) {:at t :chars (long chars) :gen-gap gen-gap})
            :est-tok est
            :last-at t
            :timer timer})))

(defn- start-rate!
  "Start the repaint timer for `sid` if it is not already running. The tick only
  re-renders (and stops itself once the stream has gone quiet for
  `rate-stale-ms`) — `rate-of` reads the window, so nothing is recomputed here."
  [sid]
  (when-not (:timer (get @!rates sid))
    (let [id (js/setInterval
              (fn []
                (let [{:keys [last-at] :as st} (get @!rates sid)
                      idle (when last-at (- (now-ms) (long last-at)))]
                  (if (and idle (> (long idle) (long rate-stale-ms)))
                    (stop-rate! sid)
                    ;; writing the atom is what repaints the status bar
                    (when st (swap! !rates update sid assoc :tick (inc (long (or (:tick st) 0))))))))
              rate-render-ms)]
      (swap! !rates update sid assoc :timer id))))

(defn- line-of
  "Map one raw content to a transcript segment, or nil to skip it.

  The agent loop streams many content types in a turn: reasoning (`reasonText` wrapped in
  `reasonStarted`/`reasonFinished`), tool lifecycle (`toolCallPrepare` →
  `toolCallRunning` → `toolCalled`/`toolCallRun`), plus `progress`/`metadata`
  chatter. Only the ones with user-meaningful payload are painted; the rest are
  dropped so they can't pollute the transcript (or fragment the answer stream)."
  [content]
  (let [{:keys [type text name summary]} content]
    (case type
      "text"            {:kind :answer   :text (str (or text ""))}
      "thinking"        {:kind :thinking :text (str (or text ""))}
      "reasonText"      {:kind :thinking :text (str (or text ""))}
      "toolCalled"      {:kind :tool     :text (str "⚙ " (or summary name "tool"))}
      "toolCallRun"     {:kind :tool     :text (str "⚙ " (or summary name "tool"))}
      ;; "usage" is deliberately NOT a segment here. It arrives mid-stream, and a
      ;; segment landing between two :answer/:thinking chunks BREAKS the
      ;; consecutive-run coalescing in `append-segment` — the answer gets split in
      ;; half every time usage updates. It belongs in the status bar; see `:ev`.
      ;; an assistant image (from an MCP tool's image content block or image
      ;; generation) — ECA sends it inline as base64; render it, don't text it.
      "image"           {:kind :image    :media_type (str (or (:mediaType content) "image/png"))
                         :base64 (str (:base64 content))}
      ;; lifecycle / noise — deliberately not painted
      ("reasonStarted" "reasonFinished" "progress" "metadata"
       "toolCallPrepare" "toolCallRunning") nil
      ;; UNKNOWN — now PAINTED, not merely logged. This branch used to return nil,
      ;; which is how the agent loop's `tool-call-dropped` notice ("the model
      ;; emitted a DSML tool call that could not be parsed … the call was
      ;; dropped") became invisible in the UI: a real diagnostic, discarded for
      ;; not appearing in the list above. Any unknown type carrying text is shown
      ;; as a diagnostic line, so the next notice the loop invents is visible
      ;; without anyone remembering to add it here — a hard-coded list is a thing
      ;; that goes stale.
      (let [msg (str (or text summary name ""))]
        (if (str/blank? msg)
          (do (.log js/console "[grog-web] unhandled content type (no text):" (pr-str type))
              nil)
          (do (.log js/console "[grog-web] unhandled content type:" (pr-str type) msg)
              {:kind :line :text (str "⚠ " (or name type) ": " msg)}))))))

;; The project the client opens is decided by the SPINE (`startup`), never
;; hard-coded here. It used to be a literal "grog" — the developer's own
;; project, which exists on nobody else's machine. On a fresh install the spine
;; seeds the getting-started project (`ouroboros`); see the :startup-ok event.

(defn- opt-label
  "Render one option from a question, tolerating ECA sending strings or maps."
  [o]
  (cond
    (string? o) o
    (map? o)    (str (or (:label o) (:value o) (:text o) (pr-str o)))
    :else       (str o)))

;; --- the command reference (ONE source of truth) ---------------------------
;;
;; Both the onboarding panel AND `/help` render from this, so they can never
;; drift. `[command description trigger]`; `trigger` is the UI/keyboard
;; equivalent where one exists ("—" = command line only). The renderer answers
;; `/help` LOCALLY from this, instead of round-tripping the spine's text dump.
(def ^:private help-rows
  [["/help"    "this list — everything grog can be told to do"           "—"]
   ["/project" "list · switch · create (/project new <name>) · delete"   "Ctrl+T new tab · Ctrl+W close · Ctrl+Tab cycle · Ctrl+1‑9 jump"]
   ["/model"   "show the current model + recents, or switch: /model <n> / <name>" "the Models dialog (Ctrl+M) — and the status bar's model line"]
   ["/provider"
    "list the transport catalogue, or set it: /provider <id> · add a port for a LOCAL server (/provider strata 8081 — a second launch)"
    "the Models dialog (Ctrl+M) — a model's URL holds its host and port"]
   ["/secret"  "store or remove a key — values are never printed"        "—"]
   ["/clear"   "start a fresh conversation"                              "—"]
   ["/doctor"  "check what is installed and whether it holds together"   "—"]
   ["/reset"   "factory reset, if things get too wonky"                  "—"]])

;; Pure UI — no slash command, just a control or a key.
(def ^:private ui-shortcuts
  [["Ctrl+Enter" "send (Enter is a newline)"]
   ["Ctrl+E" "copy the whole transcript"]
   ["Ctrl+Shift+E" "export the session to a standalone HTML file"]
   ["Ctrl+= / Ctrl+- / Ctrl+0" "transcript font size"]
   ["Ctrl+Shift+Space" "voice input (local speech-to-text)"]
   ["📎 / paste / drop" "attach a file or an image"]])

(defn- help-markdown
  "The `/help` reply — built from `help-rows`+`ui-shortcuts` so it matches the
  onboarding panel exactly. Markdown (the transcript renders it)."
  []
  (str "**Commands** — anything starting with `/` is a command, not a message to "
       "the model. Most also have a UI trigger.\n\n"
       "| Command | Does | Also via |\n|---|---|---|\n"
       (apply str (for [[c d t] help-rows] (str "| `" c "` | " d " | " t " |\n")))
       "\n**Keys / controls with no command:**\n\n"
       (apply str (for [[k d] ui-shortcuts] (str "- `" k "` — " d "\n")))))

;; --- db / events -----------------------------------------------------------

(rf/reg-event-db :init
  (fn [_ _]
    {:sessions {} :order [] :active nil :focused? true
     :connected? false :socket-path "" :opened? false :font-size 16
     ;; per-kind transcript overrides (font/size/colour); {} = all defaults
     :seg-styles {}
     ;; the log pane (off unless the user turns it on; persisted like font-size).
     ;; `:log-lines` is a bounded TAIL — the file is the record, this is a viewer.
     ;; `:log-scope` :project keeps this session's lines plus everything
     ;; unattributed (client/global) — see `log-foreign?`.
     :log-open? false :log-lines [] :log-path nil :log-scope :project
     :dialog nil :dialog-input "" :q-input "" :projects []
     ;; startup (from the spine's `startup` call): which project to open, and
     ;; whether an LLM is even reachable — if not, the client shows the "Job 1"
     ;; onboarding page instead of a transcript.
     :home-project nil :bootstrap-needed? false :bootstrap-configured? false
     :bootstrap-dismissed? false :bootstrap-mcps nil :docs-dir nil :first-run? false
     ;; THE MODELS DIALOG's whole state (it is a self-contained dialog now — see
     ;; `models-dialog`). A model is a NAMED CHOICE: {name url model}, picked by
     ;; name. `:choices` is the list from the spine (`choices` RPC), `:templates`
     ;; the baked-in endpoints a new row starts from, `:model-info` the live
     ;; transport (what a session runs right now), `:recents` the LRU shortlist
     ;; for anything that is NOT a named choice.
     :choices [] :templates [] :model-info nil :model-sources [["local" "Local"]]
     :recents []
     ;; the editor form. `:choice-edit` is the name being edited, or "new".
     ;; nil = the dialog is showing only the list. The draft fields are the
     ;; user's text and are NEVER overwritten by a refresh while editing.
     :choice-edit nil :choice-name "" :choice-url "" :choice-model ""
     ;; a per-choice browse list: the endpoint of the row being edited, keyed by
     ;; url in `:choice-catalogue` (the spine caches per url, see the `models` RPC)
     :choice-browse nil :choice-catalogue {} :choice-query ""
     ;; the "start from a baked-in endpoint" list's search box
     :template-query ""
     ;; what the last save/probe/remove said, shown IN the form rather than only
     ;; in the status line — a validation failure must appear beside the field
     ;; that caused it.
     :choice-msg nil :choice-busy? false
     ;; the composer draft and its attachments are PER SESSION
     ;; ([:sessions sid :input] / [:sessions sid :attachments]) so swapping
     ;; projects cannot leak one project's text or image into another — they are
     ;; not top-level keys. See the :input / :attach-* events and their subs.
     :voice nil :recording? false :transcribing? false}))

(rf/reg-sub :db (fn [db _] db))
(defn- sess-of [db id] (get-in db [:sessions id]))

(defn- append-segment
  "Append a transcript segment. `nil` seg = nothing to paint (skipped). Consecutive
  :answer/:thinking runs coalesce into one growing blob; consecutive identical
  :tool/:line segments (lifecycle duplicates) are deduped."
  [db sid seg]
  (if (nil? seg)
    db
    (update-in db [:sessions sid :transcript]
               (fn [t]
                 (let [t (or t []) last-seg (peek t)]
                   (cond
                     (and last-seg
                          (= (:kind last-seg) (:kind seg))
                          (contains? #{:answer :thinking} (:kind seg)))
                     (conj (pop t) (update last-seg :text str (:text seg)))

                     (and last-seg
                          (= (:text last-seg) (:text seg))
                          (contains? #{:tool :line} (:kind seg)))
                     t

                     :else (conj t seg)))))))

(rf/reg-event-fx :startup-ok
  "The spine told us which project to open, and whether grog can talk at all.
  Store it, then open that project — on a first run the spine has already seeded
  the getting-started project, so this is the onboarding landing."
  (fn [{:keys [db]} [_ s]]
    {:db (-> db
             (assoc :home-project (:project s)
                    :first-run? (boolean (:first-run? s))
                    :bootstrap-needed? (and (not (:bootstrap-dismissed? db))
                                            (boolean (get-in s [:bootstrap :needed?])))
                    :bootstrap-configured? (boolean (get-in s [:bootstrap :configured?]))
                    :bootstrap-mcps (vec (or (get-in s [:bootstrap :mcps]) []))
                    :docs-dir (get-in s [:bootstrap :docs-dir])))
     :dispatch [:open (:project s)]}))

(rf/reg-event-db :open-ok
  (fn [db [_ snap]]
    (let [id (:id snap)
          ;; the picker's sources ride along on every open (config-derived), so
          ;; keep the last good list if this snapshot somehow lacks one; same for
          ;; the bootstrap/doc-dir hints.
          db (-> db
                 (assoc :model-sources (or (seq (:model-sources snap))
                                           (:model-sources db)))
                 (assoc :model-info (or (:model-info snap) (:model-info db)))
                 (assoc :recents (or (seq (:recents snap)) (:recents db)))
                 (assoc :bootstrap-needed? (if (:bootstrap-dismissed? db)
                                             false
                                             (boolean (get-in snap [:bootstrap :needed?])))
                        :bootstrap-configured? (boolean (get-in snap [:bootstrap :configured?]))
                        :bootstrap-mcps (or (seq (get-in snap [:bootstrap :mcps]))
                                            (:bootstrap-mcps db))
                        :docs-dir (or (get-in snap [:bootstrap :docs-dir])
                                      (:docs-dir db))))]
      (if (contains? (:sessions db) id)
        ;; already-open project: the server returns the existing session id.
        ;; Focus it; do NOT clobber its transcript.
        (assoc db :active id :opened? true)
        (-> db
            (assoc-in [:sessions id]
                      {:id id :project (:project snap)
                       :model (:model snap) :status (or (:status snap) "idle")
                       :version (:version snap)
                       ;; WHERE the model runs — the status line shows the
                       ;; provider beside the id.
                       :model-label (:model-label snap)
                       :provider (:provider snap)
                       :url (:url snap)
                       :trust (boolean (:trust snap))
                       :running? (boolean (:running? snap))
                       :transcript (if (:banner snap)
                                     [{:kind :snark :text (str (:banner snap))}]
                                     [])})
            (update :order (fnil conj []) id)
            (assoc :active id :opened? true))))))

(rf/reg-event-db :dismiss-bootstrap
  "Leave the getting-started landing for the transcript. Sticky for the session,
  and it also stops `:open-ok` (another tab's snapshot) from bringing the page
  back."
  (fn [db _] (assoc db :bootstrap-needed? false :bootstrap-dismissed? true)))

(rf/reg-event-fx :show-bootstrap
  "Bring the getting-started landing back — the counterpart to `:dismiss-bootstrap`,
  asked for from the toolbar so the onboarding prose is not lost once you have
  typed anything.

  It sets BOTH keys, and that is the whole subtlety: `:bootstrap-dismissed?` is
  the latch that stops `:startup-ok`/`:open-ok` (another tab's snapshot) from
  re-showing the page, so raising `:bootstrap-needed?` alone would be undone by
  the next snapshot and read as a dead button."
  (fn [{:keys [db]} _]
    (if (:home-project db)
      {:db (assoc db :bootstrap-needed? true :bootstrap-dismissed? false)}
      ;; No startup answer yet ⇒ there is no landing to show (the page is
      ;; rendered per-project, from what `startup`/`open` reported). Say so
      ;; rather than flip a flag that draws nothing.
      {:dispatch [:status-line nil "[grog] no getting-started page this launch"]})))

(rf/reg-event-db :select-tab (fn [db [_ id]] (assoc db :active id)))
(rf/reg-event-db :focus     (fn [db [_ f]] (assoc db :focused? f)))
(rf/reg-event-db :input     (fn [db [_ s]] (assoc-in db [:sessions (:active db) :input] s)))

;; --- attachments (images pasted, files picked/dropped) ---------------------
;;
;; Attachments ride to ECA as `contexts` on the prompt: a pasted image becomes
;; an ImageContext (inline base64 — no path needed), a picked/dropped file a
;; FileContext (a path the ECA server reads; a .png path counts as an image).
;; Nothing is uploaded anywhere — the bytes go only to the local ECA.

(defn- bytes->base64
  "Uint8Array → base64 in 32 KiB chunks. `String.fromCharCode.apply` over a
  whole multi-MB image would overflow the call stack."
  [^js u8]
  (let [n (.-length u8) chunk 0x8000]
    (loop [i 0 parts (js/Array.)]
      (if (< i n)
        (do (.push parts (js/String.fromCharCode.apply nil (.subarray u8 i (min n (+ i chunk)))))
            (recur (+ i chunk) parts))
        (js/btoa (.join parts ""))))))

(defn- attach-image-bytes!
  "Read a Blob's bytes and dispatch them as an image attachment (async)."
  [blob]
  (-> (.arrayBuffer blob)
      (.then (fn [buf]
               (rf/dispatch [:attach-image (str (.-type blob))
                             (bytes->base64 (js/Uint8Array. buf))])))
      (.catch (fn [_] nil))))

;; Attachments are PER SESSION: swapping projects must not carry a pasted image
;; or a picked file into the other project's composer.
(rf/reg-event-db :attach-image
  (fn [db [_ media-type base64]]
    (update-in db [:sessions (:active db) :attachments] (fnil conj [])
               {:kind :image :media_type (or media-type "image/png") :base64 base64})))

(rf/reg-event-db :attach-files
  (fn [db [_ paths]]
    (update-in db [:sessions (:active db) :attachments] (fnil into [])
               (map (fn [p] {:kind :file :path (str p)})) paths)))

(rf/reg-event-db :attach-remove
  (fn [db [_ i]]
    (update-in db [:sessions (:active db) :attachments]
               (fn [xs] (vec (keep-indexed (fn [j x] (when (not= j i) x)) (or xs [])))))))

(rf/reg-event-db :attach-clear
  (fn [db _] (assoc-in db [:sessions (:active db) :attachments] [])))

(rf/reg-event-fx :attach-pick
  (fn [_ _]
    (-> (.pickFiles js/window.grogAPI)
        (.then (fn [paths] (when (seq paths) (rf/dispatch [:attach-files (js->clj paths)]))))
        (.catch (fn [_] nil)))
    {}))

;; An assistant image: open it FULL SIZE externally (OS viewer / browser — the
;; in-app <img> is capped so it can't swallow the transcript) or save it to the
;; Downloads folder. Both take the image's own bytes from the event.
(rf/reg-event-fx :open-image
  (fn [_ [_ media-type base64]]
    (-> (.openImage js/window.grogAPI (or media-type "image/png") base64)
        (.catch (fn [e] (rf/dispatch [:status-line nil (str "[grog] open image failed: " (.-message e))]))))
    {}))

(rf/reg-event-fx :save-image
  (fn [_ [_ media-type base64]]
    (-> (.saveImage js/window.grogAPI (or media-type "image/png") base64)
        (.then (fn [p] (rf/dispatch [:status-line nil (str "[grog] saved image to " p)])))
        (.catch (fn [e] (rf/dispatch [:status-line nil (str "[grog] save image failed: " (.-message e))]))))
    {}))

;; --- assistant images: persisted INTO THE PROJECT ---------------------------
;;
;; An image arrives as base64 with no path. We write it into the SESSION'S
;; PROJECT (images/, via the spine's `save-image` RPC) so it becomes a durable
;; artifact that travels with the project — then show a SMALL inline thumbnail
;; and let a click open the full-size project file in the OS viewer.
;;
;; Persisting is idempotent, keyed by the image bytes, so re-rendering the
;; transcript never writes a second copy.

(defn- img-key
  "Stable key for one assistant image (media type + byte count + content hash)."
  [media-type base64]
  (str (or media-type "image/png") ":" (count (str base64)) ":" (hash (str base64))))

(rf/reg-event-db :image-saved
  (fn [db [_ sid k info]]
    (assoc-in db [:sessions sid :image-paths k] info)))

(rf/reg-event-fx :persist-image
  "Write the image into the project's images/ dir once. No-op when it is already
  there (idempotent by byte key)."
  (fn [{:keys [db]} [_ sid media-type base64]]
    (let [k (img-key media-type base64)]
      (if (or (nil? sid) (nil? base64) (get-in db [:sessions sid :image-paths k]))
        {}
        (do
          (-> (.call js/window.grogAPI "save-image"
                     (clj->js {:id sid :media-type (or media-type "image/png") :base64 base64}))
              (.then (fn [r] (when r (rf/dispatch [:image-saved sid k (js->clj r :keywordize-keys true)]))))
              (.catch (fn [_] nil)))
          {})))))

(rf/reg-event-fx :open-image-file
  "Open an assistant image FULL SIZE: the copy written into the project if it is
  there, otherwise persist it first and then open."
  (fn [{:keys [db]} [_ sid media-type base64]]
    (let [k (img-key media-type base64)
          saved (get-in db [:sessions sid :image-paths k])]
      (if-let [p (:path saved)]
        (-> (.openPath js/window.grogAPI p)
            (.catch (fn [e] (rf/dispatch [:status-line sid (str "[grog] open failed: " (.-message e))]))))
        (-> (.call js/window.grogAPI "save-image"
                   (clj->js {:id sid :media-type (or media-type "image/png") :base64 base64}))
            (.then (fn [r] (when r
                             (rf/dispatch [:image-saved sid k (js->clj r :keywordize-keys true)])
                             (rf/dispatch [:open-image-file sid media-type base64]))))
            (.catch (fn [e] (rf/dispatch [:status-line sid (str "[grog] image failed: " (.-message e))])))))
      {})))
(rf/reg-event-db :q-input   (fn [db [_ s]] (assoc db :q-input s)))
(rf/reg-event-db :connected (fn [db [_ on? path]]
                              (assoc db :connected? on? :socket-path (or path (:socket-path db)))))
(rf/reg-event-db :retry-status (fn [db [_ r]] (assoc db :retry r)))

;; --- voice (client-side STT: capture here, transcribe locally) --------------

(defonce ^:private voice-timer (atom nil))

(defn- focus-composer! []
  ;; let re-frame paint the new :input before focusing the textarea
  (js/setTimeout (fn [] (when-let [el (.getElementById js/document "grog-composer")]
                          (.focus el)))
                 0))

(defn- clear-voice-timer! []
  (when-let [t @voice-timer] (js/clearTimeout t))
  (reset! voice-timer nil))

(rf/reg-event-db :voice-status  (fn [db [_ s]] (assoc db :voice s)))
(rf/reg-event-db :recording     (fn [db [_ on?]] (assoc db :recording? (boolean on?))))
(rf/reg-event-db :transcribing  (fn [db [_ on?]] (assoc db :transcribing? (boolean on?))))

(rf/reg-event-db :insert-transcript
  (fn [db [_ text]]
    (let [cur (str (get-in db [:sessions (:active db) :input]))
          sep (if (and (seq cur) (not (str/ends-with? cur " "))) " " "")]
      (assoc-in db [:sessions (:active db) :input] (str cur sep text)))))

;; auto-stop: a forgotten recording shouldn't run forever
(rf/reg-event-fx :voice-auto-stop
  (fn [{:keys [db]} _]
    (if (:recording? db) {:dispatch [:toggle-voice]} {})))

(rf/reg-event-fx :toggle-voice
  (fn [{:keys [db]} _]
    (let [st (:voice db) enabled? (and st (:enabled st))]
      (cond
        ;; ignore clicks while a transcription is already in flight
        (:transcribing? db) {}

        ;; click #2 → stop, transcribe locally, insert into the prompt
        (:recording? db)
        (do
          (clear-voice-timer!)
          (rf/dispatch [:recording false])
          (rf/dispatch [:transcribing true])
          (-> (voice/stop!)
              (.then (fn [res]
                       (-> (.voiceTranscribe js/window.grogAPI (:wav res))
                           (.then (fn [r]
                                    (rf/dispatch [:transcribing false])
                                    (let [txt (str (:text (js->clj r :keywordize-keys true)))]
                                      (if (seq (str/trim txt))
                                        (do (rf/dispatch [:insert-transcript txt])
                                            (focus-composer!)
                                            (rf/dispatch [:status-line nil "[grog] voice: inserted transcript"]))
                                        (rf/dispatch [:status-line nil "[grog] voice: no transcript"])))))
                           (.catch (fn [e]
                                     (rf/dispatch [:transcribing false])
                                     (rf/dispatch [:status-line nil (str "[grog] voice failed: " (.-message e))]))))))
              (.catch (fn [e]
                        (rf/dispatch [:transcribing false])
                        (rf/dispatch [:status-line nil (str "[grog] voice capture failed: " (.-message e))]))))
          {})

        (not enabled?)
        {:dispatch [:status-line nil (str "[grog] voice off — "
                                          (or (:reason st) "set GROG_VOICE_COMMAND"))]}

        (not (voice/supported?))
        {:dispatch [:status-line nil "[grog] microphone unavailable in this window"]}

        ;; click #1 → start recording
        :else
        (do
          (-> (voice/start!)
              (.then (fn [_]
                       (rf/dispatch [:recording true])
                       (rf/dispatch [:status-line nil "[grog] recording… click the mic again to stop"])
                       (clear-voice-timer!)
                       (reset! voice-timer
                               (js/setTimeout #(rf/dispatch [:voice-auto-stop])
                                              (* 1000 (or (:maxSeconds st) 60))))))
              (.catch (fn [e]
                        (rf/dispatch [:status-line nil (str "[grog] mic error: " (.-message e))]))))
          {})))))

;; --- font size (font-zoom) -------------------------------------------------

(def ^:private min-font 11)
(def ^:private max-font 30)
(def ^:private default-font 16)

(defn- clamp-font [n] (-> (int n) (max min-font) (min max-font)))

(defn- apply-font! [n]
  (try (set! (.. js/document -documentElement -style -fontSize) (str n "px"))
       (catch :default _ nil))
  (try (.setItem (.-localStorage js/window) "grog-web.font-size" (str n))
       (catch :default _ nil)))

(rf/reg-event-fx :set-font-size
  (fn [{:keys [db]} [_ n]]
    (let [n (clamp-font n)]
      (apply-font! n)
      {:db (assoc db :font-size n)})))

(rf/reg-event-fx :bump-font
  (fn [{:keys [db]} [_ d]]
    {:dispatch [:set-font-size (+ (or (:font-size db) default-font) d)]}))

;; --- per-kind transcript styling --------------------------------------------
;; Every segment kind (user prompt, answer, thinking, tool output, status line,
;; startup snark) can carry its OWN font family, size and colour. Stored like the
;; font size: renderer-local (`localStorage`), because it is presentation, not
;; configuration — and applied as an inline override on top of the CSS classes,
;; so an unset field falls back to the stylesheet exactly as before.

(def ^:private seg-kinds [:user :answer :thinking :tool :line :snark])

(def ^:private seg-kind-labels
  {:user "prompt (your message)" :answer "answer" :thinking "thinking"
   :tool "tool output" :line "status line" :snark "startup snark"})

(defn- seg-style-css
  "The inline style map for `kind`, from the user's overrides. Absent fields are
  simply not emitted, so the CSS class keeps winning."
  [styles kind]
  (let [s (get styles kind)]
    (when (seq s)
      (cond-> {}
        (:font s)  (assoc :font-family (case (:font s)
                                         :mono "var(--grog-mono)"
                                         :serif "Georgia, 'Times New Roman', serif"
                                         nil))
        (:size s)  (assoc :font-size (str (:size s) "px"))
        (:color s) (assoc :color (:color s))))))

(defn- save-seg-styles! [styles]
  (try (.setItem (.-localStorage js/window) "grog-web.seg-styles"
                 (js/JSON.stringify (clj->js styles)))
       (catch :default _ nil)))

(rf/reg-event-fx :set-seg-style
  (fn [{:keys [db]} [_ kind field value]]
    (let [kind (keyword kind)
          field (keyword field)
          styles (if (or (nil? value) (= "" value))
                   (update-in (:seg-styles db) [kind] dissoc field)
                   (assoc-in (:seg-styles db) [kind field] value))
          ;; drop kinds that no longer override anything, so the stored map stays
          ;; a description of the DIFFERENCE from the defaults
          styles (into {} (remove (fn [[_ v]] (empty? v)) styles))]
      (save-seg-styles! styles)
      {:db (assoc db :seg-styles styles)})))

(rf/reg-event-fx :load-seg-styles
  (fn [{:keys [db]} [_ styles]]
    {:db (assoc db :seg-styles (or styles {}))}))

(rf/reg-event-fx :reset-seg-styles
  (fn [{:keys [db]} _]
    (save-seg-styles! {})
    {:db (assoc db :seg-styles {})}))

(rf/reg-event-fx :reset-seg-style
  (fn [{:keys [db]} [_ kind]]
    (let [styles (dissoc (:seg-styles db) (keyword kind))]
      (save-seg-styles! styles)
      {:db (assoc db :seg-styles styles)})))

(rf/reg-sub :seg-styles (fn [db _] (:seg-styles db)))

(rf/reg-event-fx :dialog-open
  (fn [{:keys [db]} [_ kind initial]]
    (cond-> {:db (assoc db :dialog kind :dialog-input (or initial ""))}
      ;; the project picker needs the live list from the server
      (= kind :projects) (assoc :dispatch [:fetch-projects])
      ;; Settings is appearance + voice ONLY. Model selection is NOT in here —
      ;; it is a dialog of its own (see `:models`), because cramming a transport
      ;; combo, a port box, a catalogue list and a raw model id into the same
      ;; panel as the font size is what made it unusable. Settings keeps a
      ;; read-only "current model" line and the button that opens the real one.
      (= kind :settings) (assoc :dispatch [:choices-load])
      ;; THE MODELS DIALOG. Self-contained: it loads its own list (named choices
      ;; + baked-in templates) and the recents shortlist, and it starts with the
      ;; editor CLOSED — the list is the front door, because picking a model you
      ;; already set up is the common case. The per-endpoint browse cache is
      ;; dropped with the last dialog: a list fetched for another endpoint would
      ;; be shown as if it were this row's.
      (= kind :models)
      (assoc :db (assoc db :dialog :models
                           :choice-edit nil :choice-name "" :choice-url ""
                           :choice-model "" :choice-browse nil :choice-query ""
                           :choice-msg nil :choice-busy? false
                           :choice-catalogue {})
             :dispatch-n [[:choices-load] [:recents-fetch]]))))

;; --- project list (server `projects` method) -------------------------------

(rf/reg-event-db :projects-loaded
  (fn [db [_ xs]] (assoc db :projects (vec xs))))

(rf/reg-event-fx :fetch-projects
  (fn [_ _]
    (-> (.call js/window.grogAPI "projects" (clj->js {}))
        (.then (fn [xs] (rf/dispatch [:projects-loaded (js->clj xs :keywordize-keys true)])))
        (.catch (fn [e] (rf/dispatch [:status-line nil (str "[grog] projects failed: " (.-message e))]))))
    {}))
(rf/reg-event-db :dialog-input (fn [db [_ s]] (assoc db :dialog-input s)))
(rf/reg-event-db :dialog-close (fn [db _] (assoc db :dialog nil)))
;; =============================================================================
;; THE MODELS DIALOG — one self-contained selection layer
;; =============================================================================
;;
;; A model, as the user knows it, is a NAME: `workstation` means "that qwen on
;; port 8081". Everything here exists to make NAME the unit of every operation —
;; pick one, add one, edit one, remove one, each one click from the list — and to
;; keep the three fields a name stands for (`name`, `url`, `model`) in ONE form
;; that is written in ONE call.
;;
;; What this replaces, and why: the panel used to spread one decision across a
;; provider combo, a separate port box, a raw model-id box and a catalogue list —
;; four controls that only make sense together, with three separate RPCs to land
;; one change. That shape produced the reported bugs one after another (the port
;; that would not stick, the bogus model, the recents row that switched the id but
;; not the endpoint), and each fix added another field to keep in sync. There is
;; no separate port field here: the URL is a text field the user owns, port
;; included, so there is nothing left to desync.
;;
;; The spine owns the state (`choices` / `save-choice` / `remove-choice`); this
;; side holds only what is on screen. A validation failure comes back as an error
;; whose MESSAGE is the sentence to show, and it is shown IN THE FORM
;; (`:choice-msg`), not only in the status line — a user who mistyped a URL needs
;; the reason beside the field they mistyped.

(defn- choices-load-failure [e what]
  (rf/dispatch [:status-line nil (str "[grog] " what " failed: " (.-message e))]))

(rf/reg-event-fx :choices-load
  "Read the named models + the baked-in templates. Cheap and network-free on the
  spine (it answers from the config), so this runs every time the dialog opens
  and after every write — the list the user sees is always the file."
  (fn [_ _]
    (-> (.call js/window.grogAPI "choices" (clj->js {}))
        (.then (fn [m] (rf/dispatch [:choices-loaded m])))
        (.catch (fn [e] (choices-load-failure e "choices"))))
    {}))

(rf/reg-event-db :choices-loaded
  (fn [db [_ m]]
    (let [m (js->clj m :keywordize-keys true)]
      (-> db
          (assoc :choices (vec (:choices m)))
          ;; the baked-in endpoints, kept for the "start from one" picker
          (assoc :templates (vec (:templates m)))
          ;; the LIVE transport (what a session actually runs) rides along, so
          ;; the dialog can mark the row that is in force without a second call
          (assoc :model-info (or (:current m) (:model-info db)))
          (assoc :model-sources (or (seq (get-in m [:current :sources]))
                                    (:model-sources db)))))))

;; --- the editor form --------------------------------------------------------
;;
;; One form, three fields, opened from a row (edit) or from a baked-in template
;; (add). The drafts are the user's text: nothing a refresh returns may overwrite
;; them while the form is open.

(defn- choice-row
  "The stored choice with this name, or nil (case-insensitive — the spine folds
  every name to lower case)."
  [db name]
  (let [n (some-> name str str/trim str/lower-case not-empty)]
    (when n (first (filter #(= n (:name %)) (:choices db))))))

(rf/reg-event-fx :choice-edit
  "Open the form on an existing row: its name (shown, and used as the key), its
  url and its model id, and start browsing ITS endpoint — not the configured
  transport's. Opening a row and seeing some other server's model list is how a
  user ends up saving a model id that endpoint has never heard of."
  (fn [{:keys [db]} [_ name]]
    (let [c (choice-row db name)]
      {:db (assoc db :choice-edit (:name c)
                     :choice-name (str (:name c))
                     :choice-url (str (or (:url c) ""))
                     :choice-model (str (or (:model c) ""))
                     :choice-query "" :choice-msg nil :choice-busy? false
                     :choice-browse (some-> (:url c) str str/trim (str/replace #"/+$" "") not-empty))
       :dispatch (when-let [u (:url c)] [:choice-catalogue-fetch u nil])})))

(rf/reg-event-fx :choice-new
  "Open a blank form, optionally PRE-FILLED from a baked-in endpoint
  (`{:template {:id \"strata\" :url \"…\" :sample \"…\"}}`). The template is a
  starting point the user then edits — the baked-in list is a template, not a
  cage."
  (fn [{:keys [db]} [_ {:keys [template]}]]
    (let [t (or template {})
          url (str (or (:url t) ""))]
      {:db (assoc db :choice-edit "new"
                     :choice-name (str (or (:id t) ""))
                     ;; the sample is a SUGGESTION, so it lands in a field the
                     ;; user can clear — not in a locked value
                     :choice-url url
                     :choice-model (str (or (:sample t) ""))
                     :choice-query "" :choice-msg nil :choice-busy? false
                     :choice-browse (some-> url str/trim (str/replace #"/+$" "") not-empty))
       :dispatch (when (seq url) [:choice-catalogue-fetch url nil])})))

(rf/reg-event-db :choice-cancel
  (fn [db _]
    (-> db (assoc :choice-edit nil :choice-msg nil :choice-busy? false
                  :choice-query "" :choice-browse nil))))

(rf/reg-event-db :choice-field
  "One field of the form. `k` is :name / :url / :model."
  (fn [db [_ k v]]
    (assoc db (case k :name :choice-name :url :choice-url :model :choice-model)
              (str (or v "")))))

(rf/reg-event-db :choice-msg
  (fn [db [_ m]] (assoc db :choice-msg (some-> m str not-empty))))

(rf/reg-event-fx :choice-save
  "Save the form. ONE call, THREE fields — name, URL (port included, because it
  is part of the URL the user typed) and model id.

  On a validation failure the spine's message goes into `:choice-msg`, i.e. into
  the form itself; the list is NOT re-read, because the file did not change and
  re-reading would blow away the half-typed fields the user still needs to fix."
  (fn [{:keys [db]} _]
    (let [editing (:choice-edit db)
          new? (= "new" editing)
          nm (if new? (:choice-name db) (or (:choice-name db) editing))]
      (if (and new? (str/blank? (str nm)))
        ;; the message is shown in the form, not the status line: the field the
        ;; user has to fix is right there on screen
        {:db (assoc db :choice-msg "give it a NAME — that is what you pick a model with (/model <name>)")}
        (do
          (-> (.call js/window.grogAPI "save-choice"
                     (clj->js {:name nm :url (:choice-url db) :model (:choice-model db)}))
              (.then (fn [_]
                       ;; landed: the form closes, the list re-reads, and the
                       ;; status line says what happened
                       (rf/dispatch [:choice-cancel])
                       (rf/dispatch [:choices-load])
                       (rf/dispatch [:status-line nil
                                     (str "[grog] saved model " (str/lower-case (str nm))
                                          " — pick it with /model " (str/lower-case (str nm)))])))
              (.catch (fn [e] (rf/dispatch [:choice-msg (.-message e)]))))
          {:db (assoc db :choice-busy? true)})))))

(rf/reg-event-fx :choice-remove
  "Delete a named model. It is removed from the FILE (the spine also drops the
  legacy :profiles key it came from), so it cannot reappear on the next read —
  and the row that is currently IN FORCE stays in force: removing a name does not
  stop a session that is running it."
  (fn [{:keys [db]} [_ name]]
    (-> (.call js/window.grogAPI "remove-choice" (clj->js {:name name}))
        (.then (fn [_]
                 (rf/dispatch [:choices-load])
                 (rf/dispatch [:status-line nil (str "[grog] removed model " name)])))
        (.catch (fn [e] (choices-load-failure e (str "remove-choice " name)))))
    {:db (cond-> db
           (= name (:choice-edit db)) (assoc :choice-edit nil :choice-msg nil))}))

;; --- picking ---------------------------------------------------------------

(rf/reg-event-fx :choice-pick
  "PICK a named model — the button on the row, and the whole point of the dialog.

  The spine resolves the NAME to the `[url model]` pair it stands for
  (`pick-choice`), and the pair is then written through the SAME `set-model` call
  every other pick uses: one write path for the transport, the model, the recents
  row and the session's pinned endpoint, so the dialog, `/model <name>` and the
  project manifest cannot drift apart.

  A named model is a machine-level thing, so a pick is GLOBAL (it writes
  grog.edn), and it takes effect on the NEXT turn — the running session re-reads
  its pinned transport when the spine pushes the `model` event back."
  (fn [{:keys [db]} [_ sid name]]
    ;; the spine resolves the name, THEN the client writes the pair it stands for
    ;; through set-model. One write path, so the dial, /model and the manifest do
    ;; the same thing.
    (-> (.call js/window.grogAPI "pick-choice" (clj->js {:name name}))
        (.then (fn [c]
                 (let [c (js->clj c :keywordize-keys true)]
                   (.call js/window.grogAPI "set-model"
                          (clj->js {:id sid :model (:model c) :global true
                                    :url (:url c)})))))
        (.then (fn [_]
                 (rf/dispatch [:choices-load])
                 (rf/dispatch [:recents-fetch])))
        ;; a name that cannot be picked (no model id yet) says why, in the dialog
        (.catch (fn [e]
                  (rf/dispatch [:choice-msg (.-message e)])
                  (rf/dispatch [:choices-load]))))
    {:db db}))

;; --- what an endpoint actually serves --------------------------------------

(rf/reg-event-fx :choice-catalogue-fetch
  "The model list for ONE endpoint url — the row being edited, which need not be
  the transport grog currently runs. Keyed by url on the spine (it caches per
  endpoint), answered from cache instantly, and a slow endpoint is fetched on a
  background thread and arrives as a `models` broadcast carrying that url."
  (fn [{:keys [db]} [_ url force]]
    (let [u (some-> url str str/trim (str/replace #"/+$" "") not-empty)]
      (if (nil? u)
        {}
        (do
          (-> (.call js/window.grogAPI "models" (clj->js (cond-> {:url u} force (assoc :force true))))
              (.then (fn [cat] (rf/dispatch [:choice-catalogue-loaded cat])))
              (.catch (fn [_] nil)))
          ;; the browse box follows the endpoint the form is pointed at, even
          ;; before the fetch lands — so it can say "loading…" for the RIGHT url
          {:db (assoc db :choice-browse u)
           :dispatch-n [(when (= u (:choice-browse db)) [:choice-catalogue-loaded
                                                        {:url u :models (get-in db [:choice-catalogue u])}])]})))))

(rf/reg-event-db :choice-catalogue-loaded
  "Fill the browse box of ONE endpoint. TWO shapes arrive here: the RPC response
  (a raw JS object) and the spine's `models` broadcast (already CLJS, converted in
  `:notify`). Accepting only one is how a list that WAS fetched never shows up —
  the box says 'ask this URL what it serves' forever, which reads as a dead
  button."
  (fn [db [_ m]]
    (let [m (cond (nil? m) {}
                  (map? m) m
                  :else (js->clj m :keywordize-keys true))
          u (some-> (:url m) str str/trim (str/replace #"/+$" "") not-empty)]
      (if (nil? u)
        db
        (assoc-in db [:choice-catalogue u]
                  ;; nil models = never fetched (keep whatever was there);
                  ;; a list = what that endpoint says it serves
                  (if-let [ms (:models m)] (vec ms) (get-in db [:choice-catalogue u])))))))

(rf/reg-event-db :choice-query
  (fn [db [_ q]] (assoc db :choice-query (or q ""))))

(rf/reg-event-db :template-query
  (fn [db [_ q]] (assoc db :template-query (or q ""))))

(rf/reg-event-fx :choice-probe
  "\"What is actually there\" for one row: does the endpoint answer, what does it
  serve, what window does it accept. Network, so it is a button the user presses,
  never part of a plain list read."
  (fn [{:keys [db]} [_ name]]
    (-> (.call js/window.grogAPI "choice-probe" (clj->js {:name name}))
        (.then (fn [r]
                 (rf/dispatch [:choice-probed (js->clj r :keywordize-keys true)])))
        (.catch (fn [e]
                  (rf/dispatch [:choice-msg (str "could not reach " name ": " (.-message e))]))))
    {:db db}))

(rf/reg-event-db :choice-probed
  "Merge one probed row into the list — the answer arrives from the spine both as
  this call's result (a JS object) and (for other clients) as a `choice`
  broadcast (already CLJS). Both shapes are accepted, for the reason given on
  `:choice-catalogue-loaded`."
  (fn [db [_ r]]
    (let [r (cond (nil? r) {}
                  (map? r) r
                  :else (js->clj r :keywordize-keys true))
          n (:name r)]
      (if (nil? n)
        db
        (update db :choices
                (fn [xs]
                  (mapv (fn [c] (if (= (:name c) n)
                                  (merge c (select-keys r [:reachable? :models :context-window :port :provider]))
                                  c))
                        xs)))))))

;; --- recents (the LRU shortlist) -------------------------------------------
;;
;; Recents is the shortlist for anything that is NOT a named choice — a model id
;; tried once, an endpoint nothing was saved for. Named choices are the durable
;; list; this is the scratch pad, and it is kept short on purpose.

(rf/reg-event-fx :recents-fetch
  (fn [_ _]
    (-> (.call js/window.grogAPI "recents" (clj->js {}))
        (.then (fn [xs] (rf/dispatch [:recents-loaded xs])))
        (.catch (fn [_] nil)))
    {}))

(rf/reg-event-db :recents-loaded
  (fn [db [_ xs]]
    (assoc db :recents (vec (js->clj (or xs []) :keywordize-keys true)))))

(rf/reg-event-fx :recents-forget
  (fn [_ [_ url model]]
    (-> (.call js/window.grogAPI "forget-recent" (clj->js {:url url :model model}))
        (.then (fn [_] (rf/dispatch [:recents-fetch])))
        (.catch (fn [e] (choices-load-failure e "forget-recent"))))
    {}))

;; --- picking a raw model id / a recent --------------------------------------
;;
;; A named choice is the front door, but a user must still be able to run a model
;; they never saved: a row from recents (which carries its OWN url, so it switches
;; the transport too) or an id typed into the dialog. Both go through `set-model`
;; — the same single write path — with `:global true`, because a model picked from
;; the dialog is a machine-level choice, not a one-session experiment (that is
;; what `/model <id>` in the composer is for).

(rf/reg-event-fx :pick-url-model
  (fn [{:keys [db]} [_ sid model url]]
    (let [m (some-> model str str/trim not-empty)]
      (if (nil? m)
        ;; an empty pick says so, in the dialog, rather than doing nothing
        {:db (assoc db :choice-msg "no model picked — click one from the list, or type an id")}
        (do
          (-> (.call js/window.grogAPI "set-model"
                     (clj->js (cond-> {:id sid :model m :global true}
                                (seq (str (or url ""))) (assoc :url (str url)))))
              (.then (fn [_]
                       (rf/dispatch [:choices-load])
                       (rf/dispatch [:recents-fetch])))
              (.catch (fn [e] (choices-load-failure e "set-model"))))
          {:db db})))))

(rf/reg-event-db :status-line
  (fn [db [_ sid text]]
    (let [sid (or sid (:active db))]
      (cond-> db
        sid (update-in [:sessions sid :transcript]
                       (fnil conj []) {:kind :line :text (str text)})))))

(def ^:private model-output-types
  "ECA content types that mean the MODEL has started producing output — the
  moment a turn stops being 'waiting on the model' and becomes 'streaming'.
  `usage`/`progress`/`metadata`/lifecycle chatter do not count."
  #{"text" "thinking" "reasonText" "toolCalled" "toolCallRun" "image"})

(defn- set-running
  "Set a session's busy flag, keeping `:waiting?` consistent with it: a NEW turn
  (false -> true) begins in the waiting state, and any end of turn clears it."
  [db sid on?]
  (let [on? (boolean on?)
        was? (boolean (get-in db [:sessions sid :running?]))]
    (cond-> (assoc-in db [:sessions sid :running?] on?)
      (and on? (not was?)) (assoc-in [:sessions sid :waiting?] true)
      (not on?)            (assoc-in [:sessions sid :waiting?] false))))

(rf/reg-event-db :ev
  (fn [db [_ wire]]
    (let [sid  (:sessionId wire)
          ev   (cond-> wire (string? (:type wire)) (update :type keyword))
          kind (:type ev)]
      (cond
        (= :content kind)
        (let [c (:content ev)
              usage? (= "usage" (:type c))
              ;; THE LIVE tok/s COUNTER. A reasoning chunk is counted the same as
              ;; an answer chunk: both are the model producing tokens, and on a
              ;; thinking model most of the wall-clock is spent there — a counter
              ;; that ignored it would read "0 tok/s" through the longest part of
              ;; the turn. Tool lifecycle and `usage` are not generation.
              gen-chars (when (contains? #{"text" "thinking" "reasonText"} (str (:type c)))
                          (count (str (:text c))))
              db2 (cond-> db
                    ;; NOT a transcript segment (see `line-of`): appending it would
                    ;; split a running answer or thinking block in two.
                    (not usage?) (append-segment sid (line-of c))
                    ;; output is flowing now, so it is no longer "waiting on the model"
                    (contains? model-output-types (str (:type c)))
                    (assoc-in [:sessions sid :waiting?] false))]
          ;; side effect, not db state: the counter lives in its own atom (it is
          ;; written 4× a second by its timer, and putting that in the re-frame db
          ;; would push a whole-db event through every subscriber at that rate).
          (when (and sid gen-chars (pos? (long gen-chars)))
            (tick-rate! sid gen-chars)
            (start-rate! sid))
          (if usage?
            (-> db2
                (assoc-in [:sessions sid :usage] c)
                ;; a tick per update, so the status bar can flash (its span is
                ;; keyed on this — a changed key remounts it and replays the CSS)
                (update-in [:sessions sid :usage-tick] (fnil inc 0)))
            db2))
        (= :user kind)     (append-segment db sid {:kind :user :text (str (:text ev))})
        (= :line kind)     (append-segment db sid {:kind :line :text (str (:text ev))})
        (= :status kind)   (let [v (str (:value ev))]
                             ;; ECA's own "idle" is the turn-finished signal
                             (cond-> (assoc-in db [:sessions sid :status] v)
                               (= "idle" v) (set-running sid false)))
        (= :running kind)  (do (when-not (:value ev) (stop-rate! sid))
                               (set-running db sid (boolean (:value ev))))
        (= :trust kind)    (assoc-in db [:sessions sid :trust] (boolean (:value ev)))
        (= :model kind)    (cond-> (assoc-in db [:sessions sid :model] (:value ev))
                             ;; the `model` event also carries WHERE it runs — the
                             ;; provider and label the status line shows, and the
                             ;; NAME the user gave that url+model pair when they
                             ;; gave one (the spine matches it, so the label can
                             ;; say `workstation` instead of a url nobody chose).
                             (contains? ev :label)
                             (assoc-in [:sessions sid :model-label] (:label ev))
                             (contains? ev :provider)
                             (assoc-in [:sessions sid :provider] (:provider ev))
                             (contains? ev :url)
                             (assoc-in [:sessions sid :url] (:url ev))
                             (contains? ev :choice)
                             (assoc-in [:sessions sid :model-choice] (:choice ev)))
        (= :vision kind)   (assoc-in db [:sessions sid :vision?] (:value ev))
        (= :clear kind)    (do (stop-rate! sid)
                               (assoc-in db [:sessions sid :transcript] []))
        (= :approval kind) (assoc-in db [:sessions sid :pending-approval] (dissoc ev :answer))
        (= :question kind)
        (let [q (dissoc ev :answer)
              opts (:options q)]
          ;; Echo the question INTO the transcript so it lands in the chat log
          ;; (not only in a transient popup). No focus/window change here — the
          ;; tab turns brown and `question-panel` offers the controls.
          (-> db
              (assoc-in [:sessions sid :pending-question] q)
              (update-in [:sessions sid :transcript] (fnil conj [])
                         {:kind :line
                          :text (str "❓ " (or (:prompt q) "the model is asking")
                                     (when (seq opts)
                                       (str "   [" (str/join " / " (map opt-label opts)) "]")))})))
        :else db))))

(rf/reg-event-db :running
  (fn [db [_ sid on?]] (set-running db sid on?)))

;; keyboard
(rf/reg-event-db :jump
  (fn [db [_ n]]
    (if-let [id (nth (:order db) (dec n) nil)] (assoc db :active id) db)))

(rf/reg-event-fx :cycle
  (fn [{:keys [db]} [_ dir]]
    (let [order (:order db) n (count order) active (:active db)]
      (if (> n 1)
        (let [i (.indexOf (to-array (or order [])) active)]
          {:db (assoc db :active (nth order (mod (+ i dir) n)))})
        {}))))

(rf/reg-event-fx :close-active
  (fn [{:keys [db]} [_ id]]
    (let [id (or id (:active db)) order (:order db)]
      (if (> (count order) 1)
        (do
          (-> (.call js/window.grogAPI "close" (clj->js {:id id}))
              (.catch (fn [_] nil)))
          (let [new-order (vec (remove #{id} order))]
            {:db (-> db
                     (assoc :order new-order)
                     (assoc :active (if (= id (:active db)) (first new-order) (:active db)))
                     (update :sessions dissoc id))}))
        (do
          (rf/dispatch [:status-line id "[grog] can't close the last tab"])
          {})))))

(rf/reg-event-fx :key-escape
  (fn [{:keys [db]} _]
    (let [sid (:active db) sess (sess-of db sid)]
      (cond
        ;; Escape in the Models dialog steps OUT one level: the editor closes
        ;; first, the dialog second. A user who hits Escape expecting to back out
        ;; of a form must not lose the list they came from.
        (and (= :models (:dialog db)) (:choice-edit db))
        {:db (-> db (assoc :choice-edit nil :choice-msg nil))}

        (:dialog db)            {:db (assoc db :dialog nil)}
        (and (:focused? db) (:pending-approval sess))
        {:dispatch [:answer-approval sid (:id (:pending-approval sess)) :reject]}
        (and (:focused? db) (:pending-question sess))
        {:dispatch [:answer-question sid {:cancelled true :answer nil}]}
        :else {}))))

;; server notification router
(rf/reg-event-fx :notify
  (fn [_ [_ msg]]
    (case (:method msg)
      "event"         {:dispatch [:ev (:params msg)]}
      "question"      {:dispatch [:ev (assoc (:params msg) :type "question")]}
      "server-status" {:dispatch-n [[:connected (:connected (:params msg)) (:path (:params msg))]
                                    [:retry-status (select-keys (:params msg) [:attempts :nextRetryAt :retrying])]
                                    [:retry-open]]}
      "server-down"   {:dispatch [:status-line nil (:text (:params msg))]}
      ;; a per-endpoint model list landed from the spine's background fetch. It
      ;; carries the URL it belongs to, so it can only fill the browse box of the
      ;; row that asked for it — a list from another endpoint showing up under
      ;; this one is how a model id nobody serves gets saved.
      "models"        {:dispatch [:choice-catalogue-loaded (:params msg)]}
      ;; a probe of one choice's endpoint landed (server `choice-probe`): merge
      ;; the answer into that row rather than re-reading the whole list
      "choice"        {:dispatch [:choice-probed (:params msg)]}
      nil)))

;; --- commands --------------------------------------------------------------

(rf/reg-event-fx :open
  (fn [_ [_ project]]
    (-> (.call js/window.grogAPI "open" (clj->js {:project project}))
        (.then (fn [snap]
                 (let [snap (js->clj snap :keywordize-keys true)]
                   (rf/dispatch [:open-ok snap])
                   (rf/dispatch [:connect (:id snap)]))))
        (.catch (fn [e] (rf/dispatch [:status-line nil (str "[grog] open failed: " (.-message e))]))))
    {}))

(rf/reg-event-fx :open-new
  (fn [{:keys [db]} _]
    (let [nm (str/trim (str (:dialog-input db)))]
      (if (seq nm)
        (do
          ;; scaffold first (idempotent), then open — so a brand-new name lands
          ;; in a real project dir with notes/state/dialog/scripts.
          (-> (.call js/window.grogAPI "create-project" (clj->js {:name nm}))
              (.then (fn [_] (rf/dispatch [:open nm])))
              (.catch (fn [_] (rf/dispatch [:open nm]))))
          {:db (assoc db :dialog nil)})
        {}))))

(rf/reg-event-fx :pick-project
  (fn [_ [_ nm]]
    {:dispatch-n [[:dialog-close] [:open nm]]}))

(rf/reg-event-fx :connect
  (fn [_ [_ id]]
    (-> (.call js/window.grogAPI "connect" (clj->js {:id id}))
        (.catch (fn [e] (rf/dispatch [:status-line id (str "[grog] connect failed: " (.-message e))]))))
    {}))

(rf/reg-event-fx :send
  "Send the composer's text (and attachments) for the active session.

  A send also dismisses the getting-started landing, which is the fix for the
  reported bug: that landing REPLACES the transcript (`shell` renders
  `bootstrap-panel` in its place) and has no output surface of its own, while
  `/secret set …` — the command the landing itself tells you to type — publishes
  its confirmation AND its errors as `:line` segments INTO the transcript. The
  page asked for a command and then swallowed the answer: success and failure
  looked identical, and nothing scrolled, because no visible component read those
  segments. `/help` and `/big`/`/small` are handled client-side and were
  invisible on that page for the same reason. The toolbar's \"getting started\"
  button (`:show-bootstrap`) brings the page back."
  (fn [{:keys [db]} _]
    (let [id (:active db)
          txt  (or (get-in db [:sessions id :input]) "")
          atts (or (get-in db [:sessions id :attachments]) [])
          ;; ECA ChatContext: {type image mediaType base64} | {type file path}
          contexts (mapv (fn [{:keys [kind media_type base64 path]}]
                           (if (= kind :file)
                             {:type "file" :path path}
                             {:type "image" :mediaType media_type :base64 base64}))
                         atts)
          sending? (and id (or (seq (str/trim txt)) (seq atts)))
          ;; Dismiss the getting-started landing on any send — but ONLY if it is
          ;; actually up. Without the `:bootstrap-needed?` guard this fires on a
          ;; NORMAL launch too (where the landing was never shown), and setting
          ;; the latch there would make `:bootstrap-leavable?` true and offer an
          ;; onboarding page over unrelated work.
          db   (if (and sending? (:bootstrap-needed? db))
                 (assoc db :bootstrap-needed? false :bootstrap-dismissed? true)
                 db)]
      (cond
        ;; `/help` is answered LOCALLY — the same table as the onboarding panel,
        ;; UI triggers and all — instead of the spine's long text dump.
        (and id (empty? atts) (= "/help" (str/lower-case (str/trim txt))))
        (do (reset! !follow? true)
            (rf/dispatch [:help id])
            {:db (assoc-in db [:sessions id :input] "")})

        ;; `/model` goes to the SPINE, which owns the recents list and the
        ;; grog.edn write. Handled here only so the input clears and the reply
        ;; (the numbered list, or the confirmation) lands in the transcript
        ;; through the normal `:line` events.
        (and id (empty? atts) (re-matches #"(?i)/model(?:\s+.*)?$" (str/trim txt)))
        (do (reset! !follow? true)
            (-> (.call js/window.grogAPI "prompt"
                       (clj->js {:id id :text txt}))
                (.catch (fn [e]
                          (rf/dispatch [:status-line id (str "[grog] prompt failed: " (.-message e))]))))
            {:db (assoc-in db [:sessions id :input] "")})

        sending?
        (do
          ;; sending is an explicit "take me to the latest" — re-pin even if the
          ;; reader had scrolled up
          (reset! !follow? true)
          (rf/dispatch [:running id true])
          (-> (.call js/window.grogAPI "prompt"
                     (clj->js (cond-> {:id id :text txt}
                                (seq contexts) (assoc :contexts contexts))))
              (.catch (fn [e]
                        (rf/dispatch [:running id false])
                        (rf/dispatch [:status-line id (str "[grog] prompt failed: " (.-message e))]))))
          {:db (-> db (assoc-in [:sessions id :input] "")
                    (assoc-in [:sessions id :attachments] []))})

        :else {}))))

(rf/reg-event-db :help
  "Render the command reference (`help-rows`) into the transcript, locally."
  (fn [db [_ sid]]
    (let [sid (or sid (:active db))]
      (-> db
          (append-segment sid {:kind :user   :text "/help"})
          (append-segment sid {:kind :answer :text (help-markdown)})))))

(rf/reg-event-fx :stop
  (fn [{:keys [db]} _]
    (when-let [id (:active db)]
      (-> (.call js/window.grogAPI "stop" (clj->js {:id id}))
          (.catch #(rf/dispatch [:status-line id "[grog] stop failed"])))
      (rf/dispatch [:running id false]))
    {}))

(rf/reg-event-fx :toggle-trust
  (fn [{:keys [db]} [_ id]]
    (let [on (not (get-in db [:sessions id :trust]))]
      (-> (.call js/window.grogAPI "set-trust" (clj->js {:id id :on on}))
          (.catch (fn [e] (rf/dispatch [:status-line id (str "[grog] set-trust failed: " (.-message e))]))))
      {:db (assoc-in db [:sessions id :trust] on)})))

;; (The model-selection events this file used to carry — `:set-model`,
;; `:models-apply`, `:settings-save`, and the port-draft pairing between them —
;; are gone. They were the old three-RPC dance: set the transport, then the port,
;; then the model, chaining dispatches so the writes would not race, and
;; reconciling the field each one had touched. The Models dialog writes a NAMED
;; model in ONE call (`:choice-pick`, `:choice-save`, `:pick-url-model` above), so
;; there is nothing left to order or reconcile. The composer's `/model` goes to
;; the spine, which owns the config; it never used these.)

(rf/reg-event-fx :answer-approval
  (fn [{:keys [db]} [_ sid approval-id decision]]
    (-> (.call js/window.grogAPI "answer"
               (clj->js {:id sid :approval-id approval-id :decision (name decision)}))
        (.catch (fn [e] (rf/dispatch [:status-line sid (str "[grog] answer failed: " (.-message e))]))))
    {:db (update-in db [:sessions sid] dissoc :pending-approval)}))

(rf/reg-event-fx :answer-question
  (fn [{:keys [db]} [_ sid result]]
    (let [qid (get-in db [:sessions sid :pending-question :questionId])]
      (-> (.call js/window.grogAPI "answer-question" (clj->js {:question-id qid :result result}))
          (.catch (fn [e] (rf/dispatch [:status-line sid (str "[grog] answer-question failed: " (.-message e))]))))
      {:db (-> db (update-in [:sessions sid] dissoc :pending-question) (assoc :q-input ""))})))

(rf/reg-event-fx :write-clipboard
  (fn [{:keys [db]} [_ text ok-msg]]
    (let [cb (.-clipboard js/navigator)]
      (if (and cb (.-writeText cb))
        (-> (.writeText cb text)
            (.then (fn [_] (rf/dispatch [:status-line nil ok-msg])))
            (.catch (fn [_] (rf/dispatch [:status-line nil "[grog] clipboard write failed"]))))
        (rf/dispatch [:status-line nil "[grog] clipboard unavailable"])))
    {}))

(rf/reg-event-fx :copy-transcript
  (fn [{:keys [db]} _]
    (let [sid (:active db)
          segs (get-in db [:sessions sid :transcript])
          txt (str/join "\n\n" (map (fn [s] (str (name (:kind s)) ": " (:text s))) segs))]
      (if (seq (str/trim (str txt)))
        {:dispatch [:write-clipboard txt "[grog] transcript copied to clipboard"]}
        {}))))

(rf/reg-event-fx :export-html
  "Dump the WHOLE session to a standalone .html and open it — something you can
  hand to somebody, including another grog.

  The HTML is built here (pure: `grog-web.export`) and WRITTEN by the main
  process, which owns the filesystem and the browser. No dialog: main picks
  ~/grog-sessions/<project>-<yyyyMMdd-HHmm>.html."
  (fn [{:keys [db]} [_ sid]]
    (let [sid (or sid (:active db))
          s (get-in db [:sessions sid])]
      (if (seq (:transcript s))
        (let [html (export/->standalone-html
                    {:project   (:project s)
                     :session-id sid
                     :model     (:model s)
                     :version   (:version s)
                     :segments  (:transcript s)
                     :usage     (:usage s)
                     :exported-at (.toLocaleString (js/Date.))})]
          (-> (.exportHtml js/window.grogAPI (clj->js {:project (:project s) :html html}))
              (.then (fn [p] (rf/dispatch [:status-line sid (str "[grog] session dumped to " p)])))
              (.catch (fn [e] (rf/dispatch [:status-line sid (str "[grog] export failed: " (.-message e))])))))
        (rf/dispatch [:status-line sid "[grog] nothing to export yet"]))
      {})))

(rf/reg-event-fx :open-doc
  "Open a shipped documentation file (docs-relative) in the OS handler — or the
  docs folder itself when `rel` is blank. Confined to the docs dir in main."
  (fn [_ [_ rel]]
    (-> (.openDoc js/window.grogAPI rel)
        (.then (fn [err]
                 (when (and err (string? err) (seq err))
                   (rf/dispatch [:status-line nil (str "[grog] could not open docs: " err)]))))
        (.catch (fn [e] (rf/dispatch [:status-line nil (str "[grog] open doc failed: " (.-message e))]))))
    {}))

(rf/reg-event-fx :request-startup
  "Ask the spine which project to open, and whether an LLM is reachable. Retried
  on every (re)attach until it lands, so it is safe before the server is up."
  (fn [_ _]
    (-> (.call js/window.grogAPI "startup" (clj->js {}))
        (.then (fn [s] (rf/dispatch [:startup-ok (js->clj s :keywordize-keys true)])))
        (.catch (fn [e] (.log js/console "[grog-web] startup call failed (will retry):" (.-message e)))))
    {}))

(rf/reg-event-fx :retry-open
  (fn [{:keys [db]} [_ project]]
    (when-not (:opened? db)
      (if-let [p (or project (:home-project db))]
        (rf/dispatch [:open p])
        ;; we never got a startup answer (server was down at launch) — ask again
        (rf/dispatch [:request-startup])))
    {}))

;; --- subs ------------------------------------------------------------------

(rf/reg-sub :order   (fn [db _] (:order db)))
(rf/reg-sub :active  (fn [db _] (:active db)))
(rf/reg-sub :focused (fn [db _] (:focused? db)))
;; composer draft + attachments live on the ACTIVE session, so swapping projects
;; does not carry one project's text/image into another.
(rf/reg-sub :input   (fn [db _] (get-in db [:sessions (:active db) :input] "")))
(rf/reg-sub :attachments (fn [db _] (get-in db [:sessions (:active db) :attachments] [])))
(rf/reg-sub :q-input (fn [db _] (:q-input db)))
(rf/reg-sub :connected? (fn [db _] (:connected? db)))
(rf/reg-sub :voice (fn [db _] (:voice db)))
(rf/reg-sub :recording? (fn [db _] (:recording? db)))
(rf/reg-sub :transcribing? (fn [db _] (:transcribing? db)))
(rf/reg-sub :font-size (fn [db _] (or (:font-size db) 16)))
(rf/reg-sub :socket-path (fn [db _] (:socket-path db)))
(rf/reg-sub :retry (fn [db _] (:retry db)))
(rf/reg-sub :bootstrap-needed? (fn [db _] (:bootstrap-needed? db)))
;; The toolbar's way BACK to the landing. Shown only when there is something to go
;; back to: the landing was left this session (`:bootstrap-dismissed?`, set by
;; "Start chatting" or by sending anything) AND we are on the project the landing
;; belongs to. Without that gate the button would sit on every project's toolbar
;; forever and offer to show ouroboros prose over unrelated work.
(rf/reg-sub :bootstrap-leavable?
            (fn [db _] (boolean (and (:bootstrap-dismissed? db)
                                     (let [p (:project (sess-of db (:active db)))]
                                       (and p (= p (:home-project db))))))))
(rf/reg-sub :opened? (fn [db _] (boolean (:opened? db))))
(rf/reg-sub :bootstrap-configured? (fn [db _] (:bootstrap-configured? db)))
(rf/reg-sub :bootstrap-mcps (fn [db _] (:bootstrap-mcps db)))
(rf/reg-sub :docs-dir (fn [db _] (:docs-dir db)))
(rf/reg-sub :sessions (fn [db _] (:sessions db)))
(rf/reg-sub :sess (fn [db [_ id]] (get-in db [:sessions id])))
(rf/reg-sub :ui-dialog (fn [db _] (:dialog db)))
(rf/reg-sub :dialog-input (fn [db _] (:dialog-input db)))
(rf/reg-sub :projects (fn [db _] (:projects db)))

;; --- the Models dialog's subscriptions --------------------------------------
;; the named list, the baked-in templates, the live transport, the shortlist
(rf/reg-sub :choices (fn [db _] (:choices db)))
(rf/reg-sub :templates (fn [db _] (:templates db)))
(rf/reg-sub :model-info (fn [db _] (:model-info db)))
(rf/reg-sub :recents (fn [db _] (:recents db)))
;; the editor form
(rf/reg-sub :choice-edit (fn [db _] (:choice-edit db)))
(rf/reg-sub :choice-name (fn [db _] (:choice-name db)))
(rf/reg-sub :choice-url (fn [db _] (:choice-url db)))
(rf/reg-sub :choice-model (fn [db _] (:choice-model db)))
(rf/reg-sub :choice-msg (fn [db _] (:choice-msg db)))
(rf/reg-sub :choice-busy? (fn [db _] (boolean (:choice-busy? db))))
(rf/reg-sub :choice-browse (fn [db _] (:choice-browse db)))
(rf/reg-sub :choice-query (fn [db _] (:choice-query db)))
(rf/reg-sub :choice-catalogue (fn [db _] (:choice-catalogue db)))
;; the baked-in template picker's search box
(rf/reg-sub :template-query (fn [db _] (:template-query db)))
(rf/reg-sub :pending-questions
  (fn [db _] (->> (:sessions db) (keep (fn [[id s]] (when (:pending-question s) id))) set)))

;; --- views -----------------------------------------------------------------

(defn- role-label [kind]
  (case kind :answer "answer" :thinking "think" :tool "tool"
        :user "user" :usage "usage" "·"))

(defn- segment-view
  "One transcript segment."
  [sid {:keys [kind text media_type base64]}]
  (let [styles @(rf/subscribe [:seg-styles])
        st (seg-style-css styles kind)]
  (cond
    (= :snark kind)
    [:div {:class "text-center seg-snark italic text-sm py-1" :style st} text]

    ;; assistant answer / reasoning render as Markdown — real <table>s and
    ;; monospace <pre>/<code>.
    ;; The role label sits in a fixed column so blocks indent under it.
    (contains? #{:answer :thinking} kind)
    [:div {:class "flex gap-2 text-sm"}
     [:span {:class (str "flex-none pt-0.5 text-[0.7rem] "
                         (if (= :thinking kind) "role-think" "role-answer"))}
      (role-label kind)]
     (into [:div {:class (str "flex-1 min-w-0 md "
                              (if (= :thinking kind) "seg-think" "seg-answer"))
                    :style st}]
           (md/->hiccup text))]

    ;; an assistant image: shown SMALL inline (a preview — it must not swallow
    ;; the transcript), captioned with the copy the SPINE writes into the
    ;; project's images/ dir. Clicking opens that full-size PROJECT file in the
    ;; OS viewer (real pan + zoom). Persisting happens on load, so the file is
    ;; there "right then".
    (= :image kind)
    (let [k (img-key media_type base64)
          saved (get-in @(rf/subscribe [:sess sid]) [:image-paths k])]
      [:div {:class "py-1 space-y-1"}
       [:img {:src (str "data:" (or media_type "image/png") ";base64," base64)
              :alt "image"
              :title "click to open full size (pan/zoom)"
              :on-load #(rf/dispatch [:persist-image sid media_type base64])
              :on-click #(rf/dispatch [:open-image-file sid media_type base64])
              :class "max-h-40 max-w-[22rem] w-auto rounded-md border border-slate-700 bg-[#121212] cursor-zoom-in"}]
       [:div {:class "flex items-center gap-3 text-[0.7rem]"}
        [:button {:class "text-sky-300 hover:underline"
                  :on-click #(rf/dispatch [:open-image-file sid media_type base64])} "↗ open full size"]
        (if-let [rel (:rel saved)]
          [:span {:class "text-slate-500 font-mono truncate max-w-[16rem]"} (str "grog: " rel)]
          [:span {:class "text-slate-500"} "saving into the project…"])
        [:button {:class "text-slate-400 hover:underline"
                  :on-click #(rf/dispatch [:save-image media_type base64])} "↓ Downloads"]]])

    :else
    ;; :user / :tool / :usage are NOT Markdown — they render verbatim, so a URL
    ;; in a tool result (a run page, a docs link) used to be dead text.
    ;; `md/linkify` only turns bare http(s) URLs into links; every other byte,
    ;; whitespace included, is left exactly as it is.
    [:div {:class (str "text-sm " (if (= :user kind) "text-right" ""))}
     [:span {:class (str (case kind :tool "role-tool" :user "role-user" "role-snark")
                         " text-[0.7rem] mr-2 align-top")}
      (role-label kind)]
     [:span {:class (case kind :user "seg-user" :tool "seg-tool" "seg-answer")
             :style (merge {:white-space "pre-wrap"} st)}
      (md/linkify text)]])))

(defonce ^:private !scroll-el (atom nil))

;; FOLLOWING? — is the view pinned to the bottom? A reagent atom (not a plain
;; one) so the "jump to latest" chip re-renders the instant the user scrolls
;; away or returns. TRUE = keep pinning to new output; FALSE = the user scrolled
;; up to read, so we must NOT yank them down when more arrives.
(defonce ^:private !follow? (r/atom true))

;; How close to the bottom still counts as "at the bottom" (px) — a little slack
;; so sub-pixel / rounding gaps never flip following off spuriously.
(def ^:private scroll-slack-px 48)

;; SMOOTH PINS — a JUMP-sized gap (a whole message, tool result or code block
;; landed, or the reader came back to the bottom) is animated; the per-token
;; deltas of a stream stay INSTANT, because a smooth scroll restarted on every
;; delta never catches up and leaves the newest line below the fold. Honours
;; prefers-reduced-motion.
(def ^:private smooth-gap-px 320)

;; Our own animation fires scroll events with `scrollTop < max`, which
;; `on-scroll!` would read as "the reader scrolled up" — flipping `!follow?` off
;; and stranding the view mid-scroll. So an animated pin is MARKED and its events
;; are ignored until it lands. A pin that outlives the deadline was interrupted
;; (the reader took over, or the tab was hidden), so at that point the position
;; is believed and following switches off.
(defonce ^:private !pin-anim? (atom false))
(defonce ^:private !pin-deadline (atom 0))
(defonce ^:private !pin-raf (atom nil))
;; Start of the in-flight pin, on the SAME clock `requestAnimationFrame` reports
;; (a DOMHighResTimeStamp, ms since page load). Captured from the first frame so
;; the two can never disagree.
(defonce ^:private !pin-t0 (atom nil))

;; PACE OF THE SMOOTH PIN. A FIXED, short duration — the same shape as the
;; browser's own `behavior:"smooth"`, which is what a smooth scroll should feel
;; like. Deliberately NOT scaled by distance: that made a jump-sized gap crawl
;; (up to 8s), which read as "slow", not "smooth". Still eased out, so it arrives
;; and STOPS.
(def ^:private smooth-ms 220)

(def ^:private reduced-motion?
  (boolean (and (exists? js/window)
                (fn? (.-matchMedia js/window))
                (.-matches (.matchMedia js/window "(prefers-reduced-motion: reduce)")))))

(defn- stop-pin!
  "Cancel an in-flight animated pin: it landed, the reader took over (a wheel,
  a touch, a click), or a newer pin replaced it. Idempotent."
  []
  (when-let [id @!pin-raf]
    (.cancelAnimationFrame js/window id)
    (reset! !pin-raf nil))
  (reset! !pin-anim? false)
  (reset! !pin-deadline 0)
  (reset! !pin-t0 nil))

(defn- animate-pin!
  "Ease `n` to the bottom over our own requestAnimationFrame loop — a FIXED
  `smooth-ms`, eased out — and mark the pin as ours so `on-scroll!` ignores the
  events it fires. Each frame interpolates from the position the pin started at
  toward the CURRENT bottom, so a transcript that keeps growing is chased and the
  finish is still exactly the bottom.

  The clock is taken FROM THE FRAME CALLBACK itself. `requestAnimationFrame`
  reports a DOMHighResTimeStamp (ms since page load), NOT the epoch `Date.now()`
  uses; mixing the two made the progress ratio hugely negative, which pinned
  `scrollTop` at 0 (a jump to the TOP) and never let the loop finish."
  [^js n]
  (stop-pin!)
  (let [start (.-scrollTop n)
        bottom (fn [] (max 0 (- (.-scrollHeight n) (.-clientHeight n))))]
    (reset! !pin-t0 nil)
    (reset! !pin-anim? true)
    (reset! !pin-deadline (+ (.now js/Date) smooth-ms 1500))
    (letfn [(step [now]
              (when-not @!pin-t0 (reset! !pin-t0 now))
              (let [p (js/Math.min 1.0 (/ (- now @!pin-t0) smooth-ms))
                    ;; easeOutCubic: quick to leave, gentle to arrive
                    eased (- 1 (js/Math.pow (- 1 p) 3))]
                (set! (.-scrollTop n) (+ start (* (- (bottom) start) eased)))
                (if (< p 1.0)
                  (reset! !pin-raf (.requestAnimationFrame js/window step))
                  (do (set! (.-scrollTop n) (bottom))
                      (stop-pin!)))))]
      (reset! !pin-raf (.requestAnimationFrame js/window step)))))

(defn- pin!
  "Scroll `n` to the bottom. `smooth?` animates it (our own tween) and marks the
  pin as ours; anything else is an instant write, which is what a streaming pin
  must be."
  [^js n smooth?]
  (if (and smooth? (fn? (.-requestAnimationFrame js/window)))
    (animate-pin! n)
    (do (stop-pin!) (set! (.-scrollTop n) (.-scrollHeight n)))))

(defn- at-bottom?
  "True when `n` is scrolled to (or within `scroll-slack-px` of) the bottom."
  [^js n]
  (boolean
   (and n
        (<= (- (.-scrollHeight n) (.-scrollTop n) (.-clientHeight n))
            scroll-slack-px))))

(defn- scroll-end! []
  ;; Pin to the bottom ONLY while following. This is the whole fix: a render
  ;; no longer drags the reader back down if they have scrolled up.
  (when (and @!follow? (some? @!scroll-el))
    (let [n @!scroll-el]
      (if @!pin-anim?
        ;; Our animation is still travelling: let it finish. Past the deadline it
        ;; was interrupted, so believe the position rather than the flag.
        (when (> (.now js/Date) @!pin-deadline)
          (reset! !pin-anim? false)
          (when-not (at-bottom? n) (reset! !follow? false)))
        (let [gap (- (.-scrollHeight n) (.-scrollTop n) (.-clientHeight n))]
          (pin! n (and (not reduced-motion?) (> gap smooth-gap-px))))))))

(defn- on-scroll! [e]
  ;; Called on every scroll, including our own programmatic ones. While a smooth
  ;; pin is in flight its events are OURS and must not be mistaken for the reader
  ;; scrolling up. Otherwise: setting the position to the bottom keeps
  ;; `following?` true, so there is no feedback fight, and any upward scroll
  ;; flips it off.
  (if (and @!pin-anim? (<= (.now js/Date) @!pin-deadline))
    (when (at-bottom? (.-currentTarget e)) (reset! !pin-anim? false))
    (do (reset! !pin-anim? false)
        (reset! !follow? (at-bottom? (.-currentTarget e))))))

(defn- has-messages?
  "True once a real turn has happened (the splash logo retires then, like
  client 1's :splash?)."
  [segs]
  (boolean (some #(contains? #{:user :answer :thinking :tool :image} (:kind %)) segs)))

;; --- matrix rain (splash background) ---------------------------------------
;;
;; The splash sits on a Matrix-style falling-glyph canvas. It lives only as long
;; as the splash does: `transcript` swaps the splash out the moment a real turn
;; exists (`has-messages?`), which unmounts the canvas and cancels the RAF loop
;; via :component-will-unmount. So the rain "comes down" when work starts.

(defonce ^:private !matrix (atom {:raf nil :resize nil}))
(defonce ^:private !splash-canvas (atom nil))

;; Milliseconds between rain steps during the intro sweep. The RAF loop runs
;; every frame (~60fps) but only paints once this much time has elapsed, so
;; the glyphs fall at a calm, readable pace instead of a frantic blur.
;; Halved from 55: the opening downpour runs at double pace (~33ms a step at
;; 60fps — the gate quantises to whole frames).
(def ^:private matrix-frame-ms 27.5)

;; After the first sweep — the leading glyphs have fallen off the bottom of
;; the canvas — the rain settles: it steps slower and only a fraction of the
;; columns keep falling, so the splash calms down behind the logo instead of
;; holding the full opening downpour. Bump the density to keep more columns.
;; No longer derived from the intro (it was `* 3`): the trickle is now half its
;; old PACE (165ms -> 330ms) while the intro went the other way, so the two
;; knobs move independently instead of fighting each other.
(def ^:private matrix-frame-ms-settled 330)
(def ^:private matrix-settled-density 0.33)

;; The rain's alphabet. Classic Matrix uses half-width katakana, but this box
;; has NO CJK font installed (katakana render blank), so we stand in with
;; Greek + Cyrillic + box-drawing/block/geometric glyphs — all present in the
;; stock monospace fonts — for that foreign, glitchy texture.
(def ^:private matrix-glyphs
  (str "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
       "АБВГДЕЖЗИКЛМНОПРСТУФХЦЧШЩЭЮЯ"
       "ΔΘΛΞΠΣΦΨΩαβγδεζηθλμπσφχψω"
       "─│┌┐└┘├┤┬┴┼╔╗╚╝╠╣╦╩╬░▒▓█◇○●□■△▲"))

(def ^:private matrix-font "16px 'Fira Code','Noto Sans Mono','DejaVu Sans Mono',monospace")

(defn- stop-matrix! []
  (let [{:keys [raf resize]} @!matrix]
    (when raf (js/cancelAnimationFrame raf))
    (when resize (.removeEventListener js/window "resize" resize))
    (reset! !matrix {:raf nil :resize nil})))

(defn- matrix-active-cols
  "Boolean array marking which columns keep raining once the splash has
  settled; guarantees at least one active column."
  [cols]
  (let [a (into-array (map (fn [_] (< (js/Math.random) matrix-settled-density))
                           (range cols)))]
    (if (some true? a)
      a
      (doto a (aset 0 true)))))

(defn- start-matrix! [canvas]
  (stop-matrix!)
  (let [ctx    (.getContext canvas "2d")
        chars  matrix-glyphs
        nchars (count chars)
        font   16
        st     (atom {:cols 1 :drops (array) :w 0 :h 0 :swept? false :active nil})
        !last  (atom 0)]
    (letfn [(fit []
              (let [w    (.-clientWidth canvas)
                    h    (.-clientHeight canvas)
                    cols (int (max 1 (js/Math.floor (/ w font))))]
                (set! (.-width canvas) w)
                (set! (.-height canvas) h)
                (swap! st (fn [s]
                            (cond-> (assoc s :cols cols :w w :h h
                                           :drops (into-array (repeat cols 1)))
                              ;; keep the settled thinning across resizes
                              (:swept? s) (assoc :active (matrix-active-cols cols)))))))
            (draw [now]
              ;; time-gated: skip frames until this phase's interval has passed
              ;; (intro sweep at full pace; the settled rain steps ~3x slower)
              (let [gate (if (:swept? @st) matrix-frame-ms-settled matrix-frame-ms)]
                (when (>= (- now (or @!last 0)) gate)
                  (reset! !last now)
                  ;; first sweep is done once the leading glyphs fall off the
                  ;; bottom — thin the rain to a subset of columns from here on
                  (let [{:keys [drops h]} @st]
                    (when (and (not (:swept? @st))
                               (some #(> (* % font) h) drops))
                      (swap! st assoc :swept? true
                                      :active (matrix-active-cols (:cols @st)))))
                  (let [{:keys [cols drops w h swept? active]} @st]
                    ;; fade the previous frame toward the page bg, then paint glyphs
                    (set! (.-fillStyle ctx) "rgba(13,14,17,0.10)")
                    (.fillRect ctx 0 0 w h)
                    ;; teal — matches the splash logo palette
                    (set! (.-fillStyle ctx) "#2ec7cd")
                    (set! (.-font ctx) matrix-font)
                    (dotimes [i cols]
                      (let [i (int i)]
                        (when (or (not swept?) (aget active i))
                          (let [ch (.charAt chars (js/Math.floor (* (js/Math.random) nchars)))
                                y  (* (aget drops i) font)]
                            (.fillText ctx ch (* i font) y)
                            (when (and (> y h) (> (js/Math.random) 0.975))
                              (aset drops i 0))
                            (aset drops i (inc (aget drops i))))))))))
              (swap! !matrix assoc :raf (js/requestAnimationFrame draw)))]
      (fit)
      (let [on-resize (fn [_] (fit))]
        (.addEventListener js/window "resize" on-resize)
        (swap! !matrix assoc :resize on-resize))
      (draw (js/performance.now)))))

(defn- splash [segs version]
  (r/create-class
   {:component-did-mount
    (fn [_]
      (when-let [c @!splash-canvas]
        (start-matrix! c)))
    :component-will-unmount (fn [_] (stop-matrix!))
    :reagent-render
    (fn [segs version]
      (let [notes (filter #(contains? #{:snark :line} (:kind %)) segs)]
        [:div {:class "absolute inset-0 flex flex-col items-center justify-center gap-3 overflow-hidden p-6"}
         [:canvas {:ref (fn [el] (reset! !splash-canvas el))
                   :class "absolute inset-0 w-full h-full"}]
         [:div {:class "relative z-10 flex flex-col items-center gap-3 w-full max-h-full min-h-0"}
          ;; the logo yields space first (min-h-0 + shrink + explicit max-height)
          ;; so the snark line and hint below it are never clipped at the bottom.
          ;; logo.PNG, not the jpg: the splash floats this at opacity-50 over the
          ;; matrix canvas, and the jpg's flattened black background painted a
          ;; dark rectangle over the rain. The png's alpha lets the rain through
          ;; and gives rounded-xl/shadow-2xl the letters' silhouette to work with.
          [:img {:src "logo.png" :alt "grog"
                 :class "w-auto max-w-[70vw] min-h-0 shrink object-contain rounded-xl shadow-2xl opacity-50"
                 :style {:max-height "50vh"}}]
          ;; which build this is — the spine stamps it into the jar; nil when an
          ;; unstamped source tree is running, in which case show nothing.
          (when version
            [:div {:class "text-xs font-mono tracking-wide seg-snark opacity-70"} version])
          (when (seq notes)
            [:div {:class "flex flex-col items-center gap-1 max-w-3xl max-h-[22vh] overflow-y-auto min-h-0"}
             (for [[i s] (map-indexed vector notes)]
               ^{:key i} [:div {:class "text-center text-sm italic seg-snark"} (:text s)])])]]))}))

(defn- transcript [sid]
  (r/create-class
   {:component-did-mount
    ;; A freshly-mounted transcript (a new tab, or a switch to another project)
    ;; starts pinned to the bottom — a session you just opened should show the
    ;; latest output, not wherever the previous tab happened to be scrolled.
    (fn [_] (reset! !follow? true))
    :reagent-render
    (fn [sid]
      (r/after-render scroll-end!)
      (let [s @(rf/subscribe [:sess sid])
            segs (:transcript s)]
        [:div {:class "relative flex-1 min-h-0"}
         [:div {:ref (fn [el] (reset! !scroll-el el))
                :on-scroll on-scroll!
                ;; the reader taking over must CANCEL our tween — the browser
                ;; used to do this for a native smooth scroll; a hand-rolled one
                ;; would otherwise fight the wheel/scrollbar for its duration.
                :on-wheel (fn [_] (stop-pin!))
                :on-mouse-down (fn [_] (stop-pin!))
                :on-touch-start (fn [_] (stop-pin!))
                :class "absolute inset-0 overflow-y-auto px-6 py-4 space-y-3"}
          (if (has-messages? segs)
            (for [[i seg] (map-indexed vector segs)]
              ^{:key i} [segment-view sid seg])
            [splash segs (:version s)])]
         ;; Only while the reader is scrolled away from the bottom: a one-click
         ;; way back, and a signal that new output has landed below.
         (when-not @!follow?
           [:button {:on-click (fn [_] (reset! !follow? true) (scroll-end!))
                     :title "Jump to the newest output"
                     :class "absolute bottom-4 right-4 z-10 px-3 py-1.5 rounded-full bg-sky-500 text-slate-950 text-xs font-medium shadow-lg"}
            "↓ Jump to latest"])]))}))

(defn- tab-strip []
  (let [order @(rf/subscribe [:order]) active @(rf/subscribe [:active])
        pending @(rf/subscribe [:pending-questions]) sessions @(rf/subscribe [:sessions])]
    [:div {:class "flex items-center gap-1 px-2 pt-2 bg-slate-900/70 border-b border-slate-700"}
     (for [id order]
       (let [pend? (contains? pending id)
             running? (get-in sessions [id :running?])
             waiting? (get-in sessions [id :waiting?])
             ;; four distinct states, so "is it working, and on what?" is
             ;; readable at a glance: idle is a static dot, nothing else is.
             state (cond pend? :question waiting? :waiting running? :streaming :else :idle)]
         ^{:key id}
         [:div {:on-click #(rf/dispatch [:select-tab id])
                ;; A question does NOT raise or focus the window — the tab just
                ;; turns BROWN, so say that, rather than implying it did.
                :title (cond pend? "Answer needed — open this tab to answer (focus was not stolen)"
                             waiting? "Waiting on the model…"
                             running? "Streaming"
                             :else "Idle")
                :class (str "group flex items-center gap-2 px-3 py-2 rounded-t-md text-sm cursor-pointer "
                            (if pend? "bg-amber-800/30 border border-amber-700/70 border-b-0 text-amber-100"
                                (if (= id active) "bg-sky-500/15 border border-sky-500/40 border-b-0 text-sky-200"
                                    "text-slate-400 hover:bg-slate-800")))}
          [:span {:class (str "w-2 h-2 rounded-full "
                              (case state
                                :question  "bg-amber-600 animate-pulse"
                                ;; violet = sent, but no output yet
                                :waiting   "bg-fuchsia-400 animate-pulse"
                                :streaming "bg-sky-400 animate-pulse"
                                "bg-emerald-500/50"))}]
          (get-in sessions [id :project])
          (when pend? [:span {:class "px-1 rounded bg-amber-700/40 text-[0.64rem] text-amber-100"} "?"])
          [:span {:class "ml-1 opacity-0 group-hover:opacity-100 text-slate-500 hover:text-rose-300"
                  :on-click (fn [e] (.stopPropagation e)
                              (rf/dispatch [:close-active id]))} "✕"]]) )
     [:div {:class "ml-auto flex items-center gap-1 text-xs pr-1"}
      [:button {:title "New tab (Ctrl+T)" :class "px-2 py-1 rounded text-slate-400 hover:bg-slate-800"
                :on-click #(rf/dispatch [:dialog-open :projects ""])} "+"] ]]))

;; --- the log pane (toolbar toggle) -------------------------------------------
;;
;; grog writes ONE log per client RUN — `~/grog.<pid>.log`, holding the client's
;; own lines, the spine's stderr and the renderer's console — so "the project's
;; log" is a FILTER over that one stream, not a different file. The spine tags
;; each session's lines `[<session-id>]` (see grog.chat/session-tag, called on
;; every per-session diagnostic), using the same id the renderer keys its
;; sessions by — the server's `:sessionId` — which is what makes the filter exact
;; rather than a guess at a project name. Anything untagged is client/global and
;; is always shown: a line we cannot place is kept, because hiding a diagnostic
;; that turns out to matter is worse than showing one that does not.
;;
;; That tag's ORIGINAL emitter was `grog.eca/connect!`; ECA was deleted when
;; grog's own agent loop replaced it, and the filter then matched nothing at all
;; (silently — a dead filter looks like a working one). scripts/session-tag-test.clj
;; pins it now.

(rf/reg-sub :log-open? (fn [db _] (:log-open? db)))
(rf/reg-sub :log-lines (fn [db _] (:log-lines db)))
(rf/reg-sub :log-path (fn [db _] (:log-path db)))
(rf/reg-sub :log-scope (fn [db _] (:log-scope db)))

(def ^:private log-max-lines 400)

(defonce ^:private !log-el (atom nil))
(defonce ^:private !log-follow? (atom true))

(defn- log-foreign?
  "True when `line` belongs to an open session that is NOT the active one — the
  only lines the project scope hides."
  [line active others]
  (boolean (some (fn [sid] (str/includes? line (str "[" sid "]")))
                 (remove #(= active %) others))))

(defn- log-on-scroll! [e]
  (let [el (.-currentTarget e)]
    ;; slack, so our own scrollTop write (rounded by the browser) cannot flip
    ;; follow off and on again on every line
    (reset! !log-follow? (>= (.-scrollTop el) (- (.-scrollHeight el) (.-clientHeight el) 24)))))

(defn- log-follow! []
  (when-let [el @!log-el]
    (when @!log-follow? (set! (.-scrollTop el) (.-scrollHeight el)))))

(defn- log-subscribe!
  "Turn the main process's log feed on/off. The invoke hands back the current
  tail, so a pane that opens never starts empty. Persisted like font-size: having
  asked for the log once, a developer expects it there next launch."
  [on?]
  (try (.setItem (.-localStorage js/window) "grog-web.log-open" (if on? "1" "0"))
       (catch :default _ nil))
  (when-let [api js/window.grogAPI]
    (-> (.logSubscribe api on?)
        (.then (fn [r]
                 (when r
                   (rf/dispatch [:log-tail (.-path r)
                                 (vec (array-seq (or (.-lines r) #js [])))])))))))

(rf/reg-event-db
 :log-tail
 (fn [db [_ path lines]]
   (assoc db :log-path path
          :log-lines (vec (take-last log-max-lines (or lines []))))))

(rf/reg-event-db
 :log-line
 (fn [db [_ line]]
   (if-not (:log-open? db)
     db  ; closed: the tail is re-fetched when it opens, so dropping is free
     (let [ls (conj (vec (:log-lines db)) line)]
       (assoc db :log-lines (if (> (count ls) log-max-lines)
                              (subvec ls (- (count ls) log-max-lines))
                              ls))))))

(rf/reg-event-db :log-clear (fn [db _] (assoc db :log-lines [])))
(rf/reg-event-db :set-log-scope (fn [db [_ scope]] (assoc db :log-scope scope)))

(rf/reg-event-fx
 :toggle-log
 (fn [{:keys [db]} _]
   (let [on? (not (:log-open? db))]
     ;; starting/stopping the feed is the main process's business — this only
     ;; flips the flag
     (log-subscribe! on?)
     {:db (assoc db :log-open? on?)})))

(defn- log-pane []
  (let [open?    @(rf/subscribe [:log-open?])
        lines    @(rf/subscribe [:log-lines])
        lpath    @(rf/subscribe [:log-path])
        scope    @(rf/subscribe [:log-scope])
        active   @(rf/subscribe [:active])
        sessions @(rf/subscribe [:sessions])
        project  (get-in sessions [active :project])
        shown    (if (= :all scope)
                   lines
                   (remove #(log-foreign? % active (keys sessions)) lines))]
    (when open?
      ;; PASS the fn, don't call it. `(r/after-render (f))` calls f immediately and
      ;; enqueues its RETURN value — nil here — which reagent asserts on
      ;; ("Enqueued function must not be nil") and which takes the whole renderer
      ;; down. The transcript's `(r/after-render scroll-end!)` next door is the
      ;; pattern; keeping the two visually identical is the point.
      (r/after-render log-follow!)
      [:div {:class "shrink-0 rounded-md border border-slate-700 bg-[#0b0c0f] overflow-hidden"}
       [:div {:class "flex items-center gap-2 px-2 py-1 border-b border-slate-800 text-[0.64rem] text-slate-500"}
        [:span {:class "font-medium text-slate-300"} "log"]
        [:button {:title (if (= :all scope)
                           "Showing every session — click for this one only"
                           "This session, plus client/global lines — click for all")
                  :class (str "px-1.5 rounded border "
                              (if (= :all scope)
                                "border-slate-700 text-slate-400"
                                "border-sky-500/50 text-sky-200"))
                  :on-click #(rf/dispatch [:set-log-scope (if (= :all scope) :project :all)])}
         (if (= :all scope) "all" (or project "this"))]
        [:span {:class "flex-1 truncate font-mono text-slate-600"} (or lpath "")]
        [:button {:title "Open the log file itself" :class "hover:text-sky-300"
                  :on-click #(when lpath (js/window.grogAPI.openPath lpath))} "↗"]
        [:button {:title "Clear this pane — the file keeps everything" :class "hover:text-sky-300"
                  :on-click #(rf/dispatch [:log-clear])} "⌫"]
        [:button {:title "Hide — the log keeps being written" :class "hover:text-rose-300"
                  :on-click #(rf/dispatch [:toggle-log])} "✕"]]
       [:div {:ref (fn [el] (reset! !log-el el))
              :on-scroll log-on-scroll!
              :class "h-40 overflow-y-auto px-2 py-1 font-mono text-[0.64rem] leading-snug text-slate-400 whitespace-pre-wrap break-all"}
        (if (seq shown)
          (for [[i l] (map-indexed vector shown)]
            ^{:key i} [:div l])
          [:div {:class "text-slate-600"} "(nothing yet — the pane follows the log as it lands)"])]])))

(defn- toolbar []
  (let [active @(rf/subscribe [:active])
        sess (get-in @(rf/subscribe [:sessions]) [active])
        running? (:running? sess) trust? (:trust sess)
        st @(rf/subscribe [:voice])
        recording? @(rf/subscribe [:recording?])
        transcribing? @(rf/subscribe [:transcribing?])
        voice? (and st (:enabled st))
        log? @(rf/subscribe [:log-open?])]
    [:div {:class "flex items-center gap-1 text-xs px-3 pt-2"}
     [:button {:disabled (not active)
               :class "px-3 py-1.5 rounded-md bg-sky-500 text-slate-950 font-medium disabled:opacity-40"
               :on-click #(rf/dispatch [:send])} "Send"]
     [:button {:disabled (not running?)
               :class "px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300 disabled:opacity-40"
               :on-click #(rf/dispatch [:stop])} "Stop"]
     [:button {:disabled (not active)
               :title (cond transcribing? "Transcribing…"
                            recording? "Recording — click to stop"
                            voice? (str "Voice input — click to record, click again to stop · " (:engine st))
                            :else (str "Voice off — " (or (:reason st) "no transcription engine")))
               :class (str "px-3 py-1.5 rounded-md border "
                           (cond recording? "bg-rose-500/20 border-rose-500/50 text-rose-200 animate-pulse"
                                 (not voice?) "bg-slate-800 border-slate-700 text-slate-500 opacity-60"
                                 :else "bg-slate-800 border-slate-700 text-slate-300")
                           " disabled:opacity-40")
               :on-click #(rf/dispatch [:toggle-voice])}
      (cond transcribing? "…" recording? "⏹" :else "🎤")]
     [:button {:disabled (not active)
               :title "Trust / YOLO — auto-approve tool calls"
               :class (str "px-3 py-1.5 rounded-md border "
                           (if trust? "bg-amber-500/20 border-amber-500/50 text-amber-200"
                               "bg-slate-800 border-slate-700 text-slate-300 disabled:opacity-40"))
               :on-click #(rf/dispatch [:toggle-trust active])}
      (if trust? "YOLO ON" "YOLO")]
     [:button {:disabled (not active)
               :title "Attach files — or paste / drop an image"
               :class "px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300 disabled:opacity-40"
               :on-click #(rf/dispatch [:attach-pick])}
      "📎"]
     ;; MODEL — the dialog, from the toolbar as well as the status bar. The whole
     ;; subject opens in one place (Ctrl+M); a button here is what makes the help
     ;; text true and gives a user with NO model set a way in that does not
     ;; require knowing a shortcut or reading a status line.
     [:button {:disabled (not active) :title "Models — pick one, or add your own (Ctrl+M)"
               :class "px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300 disabled:opacity-40"
               :on-click #(rf/dispatch [:dialog-open :models ""])}
      (str "model" (when-let [m (:model-choice sess)] (str " · " m)))]
     [:button {:disabled (not active) :title "Settings"
               :class "px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300 disabled:opacity-40"
               :on-click #(rf/dispatch [:dialog-open :settings (get-in @(rf/subscribe [:sessions]) [active :model])])}
      "⚙"]
     [:button {:disabled (not active) :title "Copy transcript (Ctrl+E)"
               :class "px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300 disabled:opacity-40"
               :on-click #(rf/dispatch [:copy-transcript])} "⧉"]
     [:button {:disabled (not active) :title "Dump the whole session to a standalone HTML file (Ctrl+Shift+E)"
               :class "px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300 disabled:opacity-40"
               :on-click #(rf/dispatch [:export-html])} "⤓"]
     ;; the log is a property of the CLIENT, not of any one session — no :disabled
     [:button {:title "Show the grog log — client, spine and renderer console (off by default)"
               :class (str "px-3 py-1.5 rounded-md border "
                           (if log?
                             "bg-sky-500/20 border-sky-500/50 text-sky-200"
                             "bg-slate-800 border-slate-700 text-slate-300"))
               :on-click #(rf/dispatch [:toggle-log])}
      "log"]
     ;; The getting-started landing is a VIEW, not a one-shot. Sending anything
     ;; dismisses it so you can SEE the command output you asked for (see `:send`)
     ;; — this is the way back to the prose, without a restart. Rendered only when
     ;; it would actually do something (see `:bootstrap-leavable?`).
     (when @(rf/subscribe [:bootstrap-leavable?])
       [:button {:title "Show the getting-started page again"
                 :class "px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300"
                 :on-click #(rf/dispatch [:show-bootstrap])}
        "getting started"])
     ;; (the temporary smooth-scroll pace slider that lived here was removed — the
     ;; pin is now a fixed `smooth-ms`, matching the browser's own smooth scroll.)
     ]))

(defn- composer []
  (let [txt @(rf/subscribe [:input]) active @(rf/subscribe [:active])
        atts (or @(rf/subscribe [:attachments]) [])]
    [:div {:class "border-t border-slate-700 bg-slate-900/50 p-3 space-y-2"
           ;; drop files/images anywhere on the composer: a file → its absolute
           ;; path (ECA reads it), an image with no path → inline bytes
           :on-drag-over (fn [e] (.preventDefault e))
           :on-drop
           (fn [e]
             (.preventDefault e)
             (doseq [f (array-seq (.. e -dataTransfer -files))]
               (let [p (js/window.grogAPI.pathForFile f)]
                 (if (seq (str p))
                   (rf/dispatch [:attach-files [p]])
                   (when (str/starts-with? (str (.-type f)) "image/")
                     (attach-image-bytes! f))))))}
     [toolbar]
     [log-pane]
     (when (seq atts)
       (into [:div {:class "flex flex-wrap gap-2"}]
             (for [[i a] (map-indexed vector atts)]
               ^{:key i}
               [:div {:class "flex items-center gap-1 rounded-md border border-slate-700 bg-slate-800 px-2 py-1 text-[0.7rem] text-slate-300"}
                (if (= :image (:kind a))
                  [:img {:src (str "data:" (:media_type a) ";base64," (:base64 a))
                         :alt "attachment" :class "h-8 w-8 object-cover rounded"}]
                  [:span {:class "font-mono max-w-[16rem] truncate"}
                   (str "📄 " (last (str/split (str (:path a)) #"[/\\]")) )])
                [:button {:title "remove" :class "ml-1 text-slate-400 hover:text-rose-300"
                          :on-click #(rf/dispatch [:attach-remove i])} "✕"]])))
     ;; warn when an image is attached to a model ECA will silently drop it on
     (when (some #(= :image (:kind %)) atts)
       (let [v (get-in @(rf/subscribe [:sessions]) [active :vision?])]
         (cond
           (false? v)
           [:div {:class "text-[0.7rem] text-rose-300"}
            "⚠ this model has no image input — the image will be ignored (pick a vision model)"]
           (true? v) nil
           :else
           [:div {:class "text-[0.7rem] text-slate-500"}
            "images only reach models with image input (vision)"])))
     [:textarea {:id "grog-composer" :rows 3 :value txt
                 :placeholder "Ctrl+Enter sends · Enter newline · paste or drop an image"
                 :disabled (not active)
                 :on-change #(rf/dispatch [:input (.. % -target -value)])
                 :on-key-down #(when (and (= "Enter" (.-key %)) (.-ctrlKey %))
                                 (.preventDefault %) (rf/dispatch [:send]))
                 ;; paste an image straight from the clipboard
                 :on-paste
                 (fn [e]
                   (let [imgs (->> (array-seq (.. e -clipboardData -items))
                                   (filter #(str/starts-with? (str (.-type %)) "image/")))]
                     (when (seq imgs)
                       (.preventDefault e)
                       (doseq [it imgs] (attach-image-bytes! (.getAsFile it))))))
                 :class "w-full rounded-md bg-[#121212] border border-slate-700 px-3 py-2 text-sm text-slate-200 outline-none focus:border-sky-500"}]
     [:div {:class "text-[0.7rem] text-slate-500"} "Ctrl+Enter send · Ctrl+Shift+Space voice · 📎 attach · paste an image · /clear · /yolo"]]))

(defn- status-bar []
  (let [pending @(rf/subscribe [:pending-questions])
        connected? @(rf/subscribe [:connected?])
        active @(rf/subscribe [:active])
        sess (get-in @(rf/subscribe [:sessions]) [active])
        name-of (fn [id] (get-in @(rf/subscribe [:sessions]) [id :project]))]
    [:div {:class "flex items-center gap-4 px-4 py-1.5 bg-slate-900 border-t border-slate-700 text-[0.7rem] text-slate-400"}
     [:span {:class "flex items-center gap-1.5"}
      [:span {:class (str "w-2 h-2 rounded-full " (if connected? "bg-emerald-400" "bg-rose-500"))}]
      (if connected? "server attached" "server unreachable")]
     ;; MODEL — the NAME the user gave it, when they gave one; and always the
     ;; id + endpoint, because a name that only exists in the user's head still
     ;; has to be checkable. Clicking opens the Models dialog (Ctrl+M), which is
     ;; the whole of model selection; `/model` in the composer prints the same
     ;; state as a numbered/named list.
     (when sess
       [:span {:class "cursor-pointer hover:underline text-sky-300"
               :title "the model this session runs · click to choose another (Ctrl+M, or type /model)"
               :on-click #(rf/dispatch [:dialog-open :models ""])}
        (let [nm (:model-choice sess)]
          (str "model: " (if nm (str nm " · ") "") (or (:model sess) "?")))])
     ;; the raw endpoint, so a confusing pick is diagnosable without opening a
     ;; dialog. Truncated; the tooltip has the whole URL.
     (when (and sess (:url sess))
       [:span {:class "text-slate-500 truncate max-w-[16rem]"
               :title (str (:url sess))}
        (str "· " (:url sess))])
     ;; usage shows HERE and nowhere else. `:key` on the tick remounts this span on
     ;; every update, which replays the CSS flash — the number blinks, then fades
     ;; back to normal. No JS, no timers, and it can't fragment the transcript.
     (let [u (:usage sess)]
       (when (and u (:sessionTokens u))
         [:span {:key (:usage-tick sess 0)
                 :class "usage-flash"
                 :title "session tokens · cost"}
          (usage-text u)]))
     ;; THE LIVE RATE. It sits next to the token meter because it is the same
     ;; subject, and it is the only thing here that changes while the model is
     ;; still talking. Only while a turn runs: left up afterwards it would be a
     ;; stale number pretending to describe something that has stopped, and the
     ;; MEASURED figure (the loop's `:rate` line) is in the transcript by then.
     ;;
     ;; Deliberately NOT `usage-flash`: that animation means "a report arrived".
     ;; This number is redrawn 4× a second and a flash on each would be a strobe.
     (when (and sess (:running? sess))
       (let [r (live-rate active)]
         (when r
           (let [[tps est] r]
             [:span {:class "text-sky-300"
                     ;; tabular-nums INLINE, not as a Tailwind class: the number is
                     ;; rewritten 4× a second and proportional digits make it jitter.
                     ;; An inline style needs no Tailwind rebuild to exist.
                     :style {:font-variant-numeric "tabular-nums"}
                     :title (str "live generation rate — estimated from streamed characters "
                                 "at ~" chars-per-token " chars/token, over the last "
                                 (quot rate-window-ms 1000) "s of GENERATION time "
                                 "(pauses and prefill excluded). The measured figure arrives "
                                 "in the transcript when the step ends.")}
              (str "⚡" (if (>= tps 100) (js/Math.round tps) (.toFixed tps 1)) " tok/s"
                   " · ~" est " tok")]))))
     (when sess
       (let [r? (:running? sess) w? (:waiting? sess)]
         [:span {:class (cond w? "text-fuchsia-300" r? "text-sky-300" :else "text-slate-500")}
          (cond w? "◌ waiting on model…" r? "● streaming" :else "idle")]))
     (when sess [:span (if (:trust sess) "YOLO ON" "trust off")])
     (when (seq pending)
       [:span {:class "px-1.5 py-0.5 rounded bg-amber-500/20 text-amber-300 border border-amber-500/40"}
        (str "⚠ question pending · " (str/join ", " (map name-of pending)))])]))

(defn- banner []
  (when-not @(rf/subscribe [:connected?])
    (let [r @(rf/subscribe [:retry])
          att (:attempts r)
          nra (:nextRetryAt r)
          secs (when (and nra (pos? nra))
                 (max 1 (int (/ (- nra (.now js/Date)) 1000))))]
      [:div {:class "mx-4 mt-3 px-3 py-2 rounded-md bg-rose-500/15 border border-rose-500/40 text-rose-200 text-xs"}
       (str "⚠ grog-server unreachable at " @(rf/subscribe [:socket-path])
            (when (and att (pos? att))
              (str " — reconnecting (attempt " att (when secs (str ", next in ~" secs "s")) ")…"))
            " Start the daemon and it attaches automatically (retries back off to 30s).")])))

(defn- overlay [& body]
  [:div {:class "fixed inset-0 z-50 bg-black/60 flex items-center justify-center"} body])

(defn- approval-dialog []
  (let [focused @(rf/subscribe [:focused]) active @(rf/subscribe [:active])
        sess (get-in @(rf/subscribe [:sessions]) [active])]
    (when (and focused active (:pending-approval sess))
      (let [a (:pending-approval sess) sid active
            ans (fn [d] (rf/dispatch [:answer-approval sid (:id a) d]))]
        [overlay
         [:div {:class "card rounded-xl shadow-2xl p-4 space-y-3 w-[620px]"}
          [:div {:class "text-slate-200 text-sm font-medium"} "🔧 Tool approval requested"]
          [:div {:class "text-slate-300 text-sm"} (str (:name a))]
          (when (:summary a) [:div {:class "text-xs text-slate-400"} (:summary a)])
          [:pre {:class "text-xs text-slate-400 bg-black/30 rounded p-2 overflow-auto max-h-40"}
           (pr-str (:args a))]
          [:div {:class "flex gap-2 justify-end text-sm"}
           [:button {:class "px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300"
                     :on-click #(ans :reject)} "Reject"]
           [:button {:class "px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300"
                     :on-click #(ans :yolo)} "YOLO"]
           [:button {:class "px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300"
                     :on-click #(ans :approve-tool)} "Approve tool"]
           [:button {:autoFocus true
                     :class "px-3 py-1.5 rounded-md bg-sky-500 text-slate-950 font-medium"
                     :on-click #(ans :approve)} "Approve"]]]]))))

(defn- question-panel
  "The model's LLM question, shown INLINE above the composer — never as a modal.

  It deliberately does NOT steal focus: there is no `:autoFocus`, and the window
  is never raised (main.js excludes `question` from `needsAttention`). The tab
  turns brown with a `?` badge, and the `:ev` `:question` branch echoes the
  prompt into the transcript, so the question lands in the chat log too.

  The panel only renders when the window IS focused (so it never grabs focus
  from another application) AND the question's tab is active — mirroring the
  approval-dialog guard."
  []
  (let [focused @(rf/subscribe [:focused])
        active @(rf/subscribe [:active])
        sess (get-in @(rf/subscribe [:sessions]) [active])
        qin @(rf/subscribe [:q-input])]
    (when (and focused active (:pending-question sess))
      (let [q (:pending-question sess) sid active
            ans (fn [res] (rf/dispatch [:answer-question sid res]))]
        [:div {:class "mx-3 mb-2 rounded-lg border border-amber-700/60 bg-amber-900/20 p-3 space-y-2"}
         [:div {:class "text-amber-100 text-xs font-medium"} "❓ The model is asking"]
         [:div {:class "text-amber-50 text-sm"} (:prompt q)]
         (when (seq (:options q))
           [:div {:class "space-y-1"}
            (for [[i o] (map-indexed vector (:options q))]
              ^{:key i}
              [:button {:class "w-full text-left px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-200 hover:border-amber-500"
                        :on-click #(ans {:cancelled false :answer (opt-label o)})}
               (opt-label o)])])
         (when (:allowFreeform? q)
           [:input {:value qin :placeholder "or type an answer…"
                    :on-change #(rf/dispatch [:q-input (.. % -target -value)])
                    :on-key-down #(when (= "Enter" (.-key %))
                                    (ans {:cancelled false :answer qin}))
                    :class "w-full rounded-md bg-[#121212] border border-slate-700 px-3 py-2 text-sm text-slate-200 outline-none focus:border-amber-500"}])
         [:div {:class "flex gap-2 justify-end text-sm"}
          [:button {:class "px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300"
                    :on-click #(ans {:cancelled true :answer nil})} "Cancel"]
          [:button {:class "px-3 py-1.5 rounded-md bg-amber-500 text-slate-950 font-medium"
                    :on-click #(ans {:cancelled false :answer qin})} "Answer"]]]))))

(defn- project-manager []
  (let [in @(rf/subscribe [:dialog-input])
        projects @(rf/subscribe [:projects])
        order @(rf/subscribe [:order])
        sessions @(rf/subscribe [:sessions])
        q (str/lower-case (str/trim (str in)))
        names (map #(if (map? %) (:name %) (str %)) projects)
        shown (if (seq q) (filter #(str/includes? (str/lower-case %) q) names) names)
        exact? (contains? (set names) (str/trim (str in)))
        open-names (set (map #(get-in sessions [% :project]) order))
        desc-of (fn [nm] (some (fn [p] (when (= nm (if (map? p) (:name p) (str p)))
                                        (:description p)))
                               projects))]
    [overlay
     [:div {:class "card rounded-xl shadow-2xl p-4 space-y-3 w-[560px] max-h-[80vh] flex flex-col"}
      [:div {:class "text-slate-200 text-sm font-medium"} "Open project"]
      [:input {:value in :autoFocus true :placeholder "filter or type a new project name…"
               :on-change #(rf/dispatch [:dialog-input (.. % -target -value)])
               :on-key-down #(when (= "Enter" (.-key %))
                               (let [v (str/trim (str in))]
                                 (when (seq v)
                                   (if (contains? (set names) v)
                                     (rf/dispatch [:pick-project v])
                                     (rf/dispatch [:open-new])))))
               :class "w-full rounded-md bg-[#121212] border border-slate-700 px-3 py-2 text-sm text-slate-200 outline-none focus:border-sky-500"}]
      [:div {:class "flex-1 min-h-0 overflow-y-auto border border-slate-700 rounded-md divide-y divide-slate-800"}
       (if (seq shown)
         (for [nm shown]
           (let [desc (desc-of nm) open? (contains? open-names nm)]
             ^{:key nm}
             [:button {:class (str "w-full text-left px-3 py-2 flex items-center gap-2 "
                                   (if open? "bg-sky-500/15 text-sky-200 hover:bg-sky-500/25"
                                       "text-slate-200 hover:bg-slate-700/50"))
                       :on-click #(rf/dispatch [:pick-project nm])}
              [:span {:class "flex-1 truncate"} nm]
              (when desc [:span {:class "text-[0.64rem] seg-snark truncate max-w-[45%]"} desc])
              (when open? [:span {:class "text-[0.64rem] text-sky-300 shrink-0"} "open"])]))
         [:div {:class "text-xs text-slate-500 p-3"}
          (if (seq q) "no matching project" "no projects found")])]
      (when (and (seq q) (not exact?))
        [:button {:class "px-3 py-2 rounded-md bg-emerald-600/20 border border-emerald-600/50 text-emerald-200 text-sm text-left hover:bg-emerald-600/30"
                  :on-click #(rf/dispatch [:open-new])}
         (str "+ create project “" (str/trim (str in)) "”")])
      [:div {:class "flex gap-2 justify-end text-sm"}
       [:button {:class "px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300"
                 :on-click #(rf/dispatch [:dialog-close])} "Cancel"]]]]))

;; =============================================================================
;; THE MODELS DIALOG — one self-contained selection layer (see the events above)
;; =============================================================================
;;
;; The whole of model selection, in one place, in ONE vocabulary: a model has a
;; NAME, a URL and an id, and the dialog's only jobs are to list them, pick one,
;; and let a row be added or edited. It opens from the status bar's model line and
;; from the toolbar's `model` button — NOT from inside Settings, which is where
;; the old one hid a provider combo, a port box, a raw id box and a catalogue
;; list underneath a font-size control.
;;
;; Three states, in the order a user needs them:
;;   1. the LIST of named models, the one in force marked, each row one click from
;;      picked / edited / removed;
;;   2. the FORM — name, URL, model id — opened on a row or blank, with that row's
;;      own endpoint's model list below it to click an id from;
;;   3. the baked-in endpoints (`resources/providers.edn`, served by the spine) as
;;      TEMPLATES: one click pre-fills the form with a known base URL and, for a
;;      running server, the model id it actually answers to.
;;
;; A note on the URL field: it is a plain text field, port included. There is no
;; separate "port" control, because the port IS part of the URL — the old split
;; (catalogue entry + a port draft kept in a separate db key) is what produced
;; "I change the port and Apply doesn't stick": two fields, two writers, and a
;; button wired to only one of them.

(defn- named-model-row
  "One row of the named list. The NAME is the row's identity and the thing a
  session runs; the URL and id sit under it in mono so a wrong one is visible at a
  glance. The dot carries the probe result — nil (never asked) is grey, so a row
  never looks confirmed when it has not been checked."
  [sid c in-force?]
  (let [n (:name c)
        prov (:provider c)
        url (:url c)
        model (:model c)]
    ^{:key n}
    [:div {:class (str "rounded-md border px-2 py-1.5 space-y-1 "
                       (if in-force?
                         "border-sky-500/50 bg-sky-950/30"
                         "border-slate-700 bg-[#121212] hover:border-slate-600"))}
     [:div {:class "flex items-center gap-2"}
      [:span {:class (str "w-1.5 h-1.5 rounded-full shrink-0 "
                          (cond (nil? (:reachable? c)) "bg-slate-600"
                                (:reachable? c) "bg-emerald-400"
                                :else "bg-rose-500"))
              :title (cond (nil? (:reachable? c)) "not checked"
                           (:reachable? c) "the endpoint answers"
                           :else "nothing answered at that URL")}]
      [:button {:class "text-left flex-1 min-w-0 text-slate-100 text-xs font-medium truncate"
                :title (if sid
                         "pick this model — writes grog.edn, used from the next turn on"
                         "no session open — saving it is still enough; grog runs it when a session opens")
                :on-click #(when sid (rf/dispatch [:choice-pick sid n]))}
       (str n (when in-force? "  ← in use"))]
      (when-let [w (:context-window c)]
        [:span {:class "text-[0.62rem] text-slate-500 shrink-0"
                :title "the context window this endpoint reports"}
         (str (quot w 1000) "k")])
      [:button {:class "text-[0.64rem] text-slate-400 hover:text-sky-300 shrink-0"
                :title "ask that endpoint what it serves"
                :on-click #(rf/dispatch [:choice-probe n])} "check"]
      [:button {:class "text-[0.64rem] text-slate-400 hover:text-slate-100 shrink-0"
                :title "edit its URL / model id"
                :on-click #(rf/dispatch [:choice-edit n])} "edit"]
      [:button {:class "text-[0.64rem] text-slate-500 hover:text-rose-300 shrink-0"
                :title "remove it from grog.edn"
                :on-click #(rf/dispatch [:choice-remove n])} "✕"]]
     [:div {:class "text-[0.64rem] font-mono text-slate-500 truncate"
            :title (str url)}
      (str (or prov "?") " · " (or url "(no url — the transport in force)"))]
     (if (seq model)
       [:div {:class "text-[0.64rem] font-mono text-slate-400 truncate" :title model} model]
       [:div {:class "text-[0.64rem] text-amber-300"}
         "no model id — edit it, or start the server and press check"])]))

(defn- template-picker
  "The baked-in endpoints, as TEMPLATES: pick one and the form opens pre-filled
  with its base URL (and its sample id, which the user may clear). This is the
  \"baked in models are a template\" half of the request — a starting point, not a
  menu of fixed choices."
  []
  (let [ts (or @(rf/subscribe [:templates]) [])
        q  @(rf/subscribe [:template-query])
        shown (if (str/blank? q)
                ts
                (let [qq (str/lower-case q)]
                  (filterv #(or (str/includes? (str/lower-case (str (:label %))) qq)
                                (str/includes? (str/lower-case (str (:id %))) qq)
                                (str/includes? (str/lower-case (str (:url %))) qq))
                           ts)))]
    [:div {:class "space-y-1.5"}
     [:div {:class "flex items-center gap-2"}
      [:span {:class "text-[0.7rem] text-slate-400"} "start from a known endpoint"]
      [:input {:value q :placeholder "filter (strata, ollama, openrouter…)"
               :on-change #(rf/dispatch [:template-query (.. % -target -value)])
               :class "flex-1 rounded-md bg-[#121212] border border-slate-700 px-2 py-1 text-[0.7rem] text-slate-200 outline-none focus:border-sky-500"}]]
     (into [:div {:class "grid grid-cols-2 gap-1 max-h-40 overflow-y-auto"}]
           (for [t shown]
             ^{:key (:id t)}
             [:button {:class (str "text-left rounded border border-slate-700 bg-[#121212] hover:border-sky-500/60 px-2 py-1 "
                                   (when (:current? t) "border-sky-500/40"))
                       :title (str (:url t) (when-let [nn (:note t)] (str "\n" nn)))
                       :on-click #(rf/dispatch [:choice-new {:template t}])}
              [:div {:class "text-[0.7rem] text-slate-200 truncate"}
               (str (:label t) (when (:local? t) "  · local")
                    (when (:current? t) "  ← current"))]
              [:div {:class "text-[0.62rem] font-mono text-slate-500 truncate"} (:url t)]]))
     [:div {:class "text-[0.64rem] text-slate-500"}
      "pre-fills the URL and the model id a running server answers to — then you edit it"]]))

(defn- choice-form
  "The editor: THREE fields, one Save. Name, URL, model id — and the model field
  comes with whatever that URL actually serves, clickable.

  One Save writes all three in a single call, so there is no ordering to get
  wrong and no second button wired to only half the form. A click on a listed id
  fills the field; it never writes, so nothing changes behind the form.

  The message line is where a validation failure lands — the spine's own
  sentence, beside the fields that caused it."
  []
  (let [editing @(rf/subscribe [:choice-edit])
        nm      @(rf/subscribe [:choice-name])
        url     @(rf/subscribe [:choice-url])
        model   @(rf/subscribe [:choice-model])
        msg     @(rf/subscribe [:choice-msg])
        busy?   @(rf/subscribe [:choice-busy?])
        browse  @(rf/subscribe [:choice-browse])
        cat     @(rf/subscribe [:choice-catalogue])
        q       @(rf/subscribe [:choice-query])
        key     (some-> browse str (str/replace #"/+$" ""))
        ids     (or (get cat key) [])
        shown   (if (str/blank? q)
                  ids
                  (filterv #(str/includes? (str/lower-case %) (str/lower-case q)) ids))
        new?    (= "new" editing)]
    [:div {:class "rounded-lg border border-sky-500/40 bg-sky-950/20 p-3 space-y-2"}
     [:div {:class "flex items-center gap-2"}
      [:span {:class "text-[0.7rem] text-slate-200 font-medium"}
       (if new? "add a model" (str "edit: " editing))]
      [:button {:class "ml-auto text-[0.64rem] text-slate-400 hover:text-slate-100"
                :title "discard this form (Escape)"
                :on-click #(rf/dispatch [:choice-cancel])} "close"]]

     [:div {:class "flex items-center gap-2"}
      [:label {:class "text-[0.68rem] text-slate-400 w-14 shrink-0" :for "gm-name"} "name"]
      [:input {:id "gm-name" :value nm :autoFocus true
               :placeholder "workstation"
               :on-change #(rf/dispatch [:choice-field :name (.. % -target -value)])
               :class "flex-1 rounded-md bg-[#121212] border border-slate-700 px-2 py-1.5 text-xs text-slate-100 outline-none focus:border-sky-500"}]
      [:span {:class "text-[0.62rem] text-slate-500 w-44 shrink-0"}
       (str "how you pick it — /model " (or (some-> nm str/lower-case) "<name>"))]]

     [:div {:class "flex items-center gap-2"}
      [:label {:class "text-[0.68rem] text-slate-400 w-14 shrink-0" :for "gm-url"} "url"]
      [:input {:id "gm-url" :value url :placeholder "http://localhost:8081/v1"
               :on-change #(rf/dispatch [:choice-field :url (.. % -target -value)])
               :on-key-down #(when (= "Enter" (.-key %))
                               (rf/dispatch [:choice-catalogue-fetch url :force]))
               :class "flex-1 rounded-md bg-[#121212] border border-slate-700 px-2 py-1.5 text-xs font-mono text-slate-200 outline-none focus:border-sky-500"}]
      [:button {:class "text-[0.64rem] text-slate-400 hover:text-slate-100 shrink-0"
                :title "ask this URL what it serves (Enter in the field does the same)"
                :on-click #(rf/dispatch [:choice-catalogue-fetch url :force])} "list ↻"]]

     [:div {:class "flex items-start gap-2"}
      [:label {:class "text-[0.68rem] text-slate-400 w-14 shrink-0 pt-1.5" :for "gm-model"} "model"]
      [:div {:class "flex-1 min-w-0 space-y-1"}
       [:input {:id "gm-model" :value model :placeholder "an id this endpoint answers to"
                :on-change #(rf/dispatch [:choice-field :model (.. % -target -value)])
                :on-key-down #(when (= "Enter" (.-key %)) (rf/dispatch [:choice-save]))
                :class "w-full rounded-md bg-[#121212] border border-slate-700 px-2 py-1.5 text-xs font-mono text-slate-200 outline-none focus:border-sky-500"}]
       (when (seq browse)
         [:div {:class "space-y-1"}
          [:input {:value q
                   :placeholder (str "filter " (count ids) " ids from " browse "…")
                   :on-change #(rf/dispatch [:choice-query (.. % -target -value)])
                   :class "w-full rounded-md bg-[#121212] border border-slate-700 px-2 py-1 text-[0.66rem] text-slate-300 outline-none focus:border-sky-500"}]
          (into [:div {:class "max-h-40 overflow-y-auto rounded-md border border-slate-700"}]
                (cond
                  ;; asked, not answered yet
                  (nil? (get cat key))
                  [[:div {:class "p-2 text-[0.68rem] text-slate-500"}
                    (str "asking " browse "…")]]

                  (empty? shown)
                  [[:div {:class "p-2 text-[0.68rem] text-slate-500"}
                    (if (seq ids)
                      (str "no id matches “" q "”")
                      (str "nothing answered at " browse " — is that server running?"))]]

                  :else
                  (for [m shown]
                    ^{:key m}
                    [:button {:class (str "block w-full text-left px-2 py-0.5 text-[0.68rem] font-mono hover:bg-slate-800 "
                                          (if (= m model) "text-sky-300" "text-slate-300"))
                              :on-click #(rf/dispatch [:choice-field :model m])}
                     m])))])
       [:div {:class "text-[0.62rem] text-slate-500"}
        (if (seq browse)
          "click an id to fill the box — Save is what writes it"
          "type the id yourself, or press list ↻ to see what this URL serves")]]]

     [:div {:class "flex items-center gap-2"}
      [:button {:class "px-3 py-1.5 rounded-md bg-sky-500 text-slate-950 text-xs font-medium"
                :disabled (boolean busy?)
                :on-click #(rf/dispatch [:choice-save])}
       (if busy? "saving…" "Save")]
      (when-not new?
        [:span {:class "text-[0.62rem] text-slate-500"}
         "saving edits the name; what is running keeps running until you pick it"])
      (when msg
        [:span {:class "text-[0.66rem] text-rose-300"} msg])]]))

(defn- recent-row
  "One row of the recents shortlist — the escape hatch for a model that was never
  saved as a name. A recent carries its OWN url, so picking one switches the
  transport with it (which is why the pair is stored)."
  [sid r]
  ^{:key (str (:url r) "|" (:model r))}
  [:div {:class "flex items-center gap-2 text-[0.68rem] font-mono rounded px-1.5 py-0.5 hover:bg-slate-800 cursor-pointer"
         :title (str (:url r))
         :on-click #(rf/dispatch [:pick-url-model sid (:model r) (:url r)])}
   [:span {:class "text-slate-500 shrink-0 w-24 truncate"} (or (:provider r) "?")]
   [:span {:class "text-slate-200 flex-1 min-w-0 truncate"} (:model r)]
   [:button {:class "text-slate-600 hover:text-rose-300 px-1 shrink-0"
             :title "forget this one"
             :on-click (fn [e] (.stopPropagation e)
                         (rf/dispatch [:recents-forget (:url r) (:model r)]))}
    "✕"]])

(defn- models-dialog
  "The model selection dialog — SELF-CONTAINED, opened by Ctrl+M, the toolbar's
  `model` button, the status bar's model line, or Settings' `change…`. It is not a
  tab inside Settings: a model is a decision of its own, and it used to be spread
  across a provider combo, a port box, an id box and a catalogue list that needed
  three RPCs to land one change.

  The shape now: the NAMED list is the front door (it loads its own data, so this
  dialog never depends on anything else having been opened), one click picks, and
  adding is either **your own URL** (the primary path — a gateway or a server on a
  port nobody has heard of has to be typeable) or a baked-in endpoint used as a
  TEMPLATE. Every change is ONE call to the spine."
  []
  (let [active  @(rf/subscribe [:active])
        sess    (get-in @(rf/subscribe [:sessions]) [active])
        db      @(rf/subscribe [:db])
        choices @(rf/subscribe [:choices])
        recents @(rf/subscribe [:recents])
        info    (:model-info db)
        editing @(rf/subscribe [:choice-edit])
        msg     @(rf/subscribe [:choice-msg])
        in-force (fn [c] (boolean (:current? c)))
        ;; a session can be running a model its project pinned (`project.edn
        ;; :model`), which is NOT the global choice — the dialog says so rather
        ;; than marking nothing and looking broken.
        sess-model (:model sess)
        sess-url   (:url sess)]
    [overlay
     [:div {:class "card rounded-xl shadow-2xl p-4 space-y-3 w-[680px] max-h-[88vh] overflow-y-auto"}
      [:div {:class "flex items-baseline gap-3"}
       [:div {:class "text-slate-100 text-sm font-medium"} "Models"]
       [:div {:class "text-[0.68rem] text-slate-500"}
        (str "named models · pick one, or add your own (name + URL + model id)")]
       [:button {:class "ml-auto text-[0.7rem] text-slate-400 hover:text-slate-100"
                 :on-click #(rf/dispatch [:dialog-close])} "close ✕"]]

      ;; what is running NOW, in one line: the NAME when there is one, the id and
      ;; the endpoint always. This is the answer to "which of these is live?".
      [:div {:class "rounded-md border border-slate-700 bg-[#121212] px-2 py-1.5 text-[0.68rem]"}
       [:span {:class "text-slate-500"} "in use: "]
       [:span {:class "text-slate-100"}
        (or (:model-choice sess) (:choice info) "—")]
       [:span {:class "text-slate-400 font-mono"}
        (str "  " (or sess-model (:model info) "(no model)"))]
       [:span {:class "text-slate-500 font-mono"}
        (str "  @ " (or sess-url (:url info) "(no endpoint)"))]
       (when (and sess-model (:model info) (not= sess-model (:model info)))
         [:div {:class "text-[0.62rem] text-amber-300 mt-0.5"}
          "this project pins its own model (project.edn :model) — picking a name below changes the global default, not this project's"])
       (when (and (nil? sess-model) (nil? (:model info)))
         [:div {:class "text-[0.62rem] text-amber-300 mt-0.5"}
          "no model is set — pick one below, or add one from a known endpoint"])]

      ;; THE LIST
      (if (seq choices)
        (into [:div {:class "space-y-1.5"}]
              (for [c choices] ^{:key (:name c)} [named-model-row active c (in-force c)]))
        [:div {:class "rounded-md border border-dashed border-slate-700 p-3 text-[0.7rem] text-slate-500"}
         "no named models yet. Add one yourself — NAME it, type its URL (port "
         "included), pick the id it answers to. Or start from a known endpoint below: "
         "grog fills in the URL and, for a running server, the id it serves. Then pick "
         "it with /model <name>, or from here."])

      ;; the editor, when open
      (when editing [choice-form])

      ;; otherwise: how to ADD. YOUR OWN endpoint comes first, because that is the
      ;; case the shipped catalogue cannot cover — a gateway, a relay, a server on
      ;; a port nobody has heard of. The baked-in endpoints are the shortcut below
      ;; it, not the only door.
      (when-not editing
        [:div {:class "border-t border-slate-700 pt-3 space-y-2"}
         [:div {:class "flex items-center gap-2"}
          [:button {:class "px-3 py-1.5 rounded-md bg-sky-500/15 border border-sky-500/50 text-sky-200 text-[0.7rem] font-medium"
                    :title "name a model yourself: a URL and the id it answers to"
                    :on-click #(rf/dispatch [:choice-new {}])} "+ add a model (your own URL)"]
          [:span {:class "text-[0.64rem] text-slate-500"}
           "give it a NAME, type its URL (port included), pick the id it serves"]]
         (template-picker)])

      ;; recents: the shortlist of models tried but never named
      (when (seq recents)
        [:div {:class "border-t border-slate-700 pt-3 space-y-1"}
         [:div {:class "flex items-center gap-2"}
          [:span {:class "text-[0.68rem] text-slate-400"} "recently used (unnamed)"]
          [:button {:class "ml-auto text-[0.62rem] text-slate-500 hover:text-slate-300"
                    :title "re-read the list"
                    :on-click #(rf/dispatch [:recents-fetch])} "↻"]]
         (into [:div {:class "space-y-0.5"}]
               (for [r recents] ^{:key (str (:url r) "|" (:model r))} [recent-row active r]))
         [:div {:class "text-[0.62rem] text-slate-500"}
          "a pick lands here automatically; save it as a name to keep it"]])

      ;; the message line, when an action (not the form) produced one
      (when (and msg (not editing))
        [:div {:class "text-[0.68rem] text-rose-300"} msg])

      [:div {:class "text-[0.62rem] text-slate-500 border-t border-slate-700 pt-2"}
       "a named model is one record — name, URL, model id — written in one step and "
       "picked by name: here, or with " [:code "/model <name>"] " (also "
       [:code "/model new <endpoint>"] " and " [:code "/model rm <name>"] ")."]]]))

(defn- seg-style-row
  "One kind's font / size / colour controls. Empty controls mean `inherit`, so
  clearing a field restores the stylesheet default for that kind."
  [kind]
  (let [styles @(rf/subscribe [:seg-styles])
        s (get styles kind)
        put! (fn [field value] (rf/dispatch [:set-seg-style kind field value]))
        font (name (or (:font s) :sans))]
    [:div {:class "flex items-center gap-2"}
     [:span {:class "w-32 shrink-0 text-[0.7rem] text-slate-400"} (get seg-kind-labels kind (name kind))]
     [:select {:value font
               :on-change #(let [v (.. % -target -value)]
                             (put! :font (when-not (= v "sans") (keyword v))))
               :class "rounded bg-[#121212] border border-slate-700 text-[0.7rem] text-slate-200 px-1 py-1"}
      [:option {:value "sans"} "body"]
      [:option {:value "mono"} "mono"]
      [:option {:value "serif"} "serif"]]
     [:input {:type "number" :min 8 :max 40
              :value (or (:size s) "")
              :placeholder "px"
              :on-change #(let [v (.. % -target -value)
                                n (js/parseInt v)]
                            (put! :size (if (js/isNaN n) nil n)))
              :class "w-16 rounded bg-[#121212] border border-slate-700 text-[0.7rem] text-slate-200 px-1 py-1"}]
     [:input {:type "color" :value (or (:color s) "#cbd5e1") :title "colour"
              :on-change #(put! :color (.. % -target -value))
              :class "h-6 w-8 rounded border border-slate-700 bg-transparent p-0"}]
     [:button {:class "text-[0.7rem] text-slate-500 hover:text-rose-300"
               :on-click #(rf/dispatch [:reset-seg-style kind])} "reset"]]))

(defn- settings-dialog []
  "Appearance and voice. Model selection is NOT here — it is its own dialog
  (`models-dialog`), opened by the button below, the status bar's model line, or
  the toolbar's `model` button.

  Settings used to carry a provider combo, a raw model-id box and a catalogue
  list, and that is where the complaint came from: a font-size control and a
  four-part model editor sharing one panel, with no way to see which of the four
  had to be touched. Settings now shows the model that IS running, one line, and
  hands the whole subject to the dialog that owns it."
  (let [active @(rf/subscribe [:active])
        sess (get-in @(rf/subscribe [:sessions]) [active])]
    [overlay
     [:div {:class "card rounded-xl shadow-2xl p-4 space-y-3 w-[520px] max-h-[85vh] overflow-y-auto"}
      [:div {:class "flex items-baseline gap-3"}
       [:div {:class "text-slate-200 text-sm font-medium"} "Settings"]
       [:div {:class "text-[0.68rem] text-slate-500"} (str "project: " (:project sess))]]
      ;; the model, READ ONLY, with the way to change it. One line the user can
      ;; verify, and one button — no half-editors in here.
      [:div {:class "flex items-center gap-2 rounded-md border border-slate-700 bg-[#121212] px-2 py-1.5"}
       [:span {:class "text-[0.68rem] text-slate-500 shrink-0"} "model"]
       [:span {:class "text-[0.7rem] text-slate-200 flex-1 min-w-0 truncate"
               :title (str (:url sess))}
        (or (:model-choice sess) (:model-label sess) (:model sess) "(none set)")]
       [:button {:class "px-2 py-1 rounded-md bg-sky-500 text-slate-950 text-[0.7rem] font-medium shrink-0"
                 :on-click #(rf/dispatch [:dialog-open :models ""])} "change…"]]
      [:div {:class "border-t border-slate-700 pt-3 space-y-1"}
       [:label {:class "text-xs text-slate-400"} "font size"]
       [:div {:class "flex items-center gap-2"}
        [:button {:class "px-3 py-1 rounded bg-slate-800 border border-slate-700 text-slate-200"
                  :on-click #(rf/dispatch [:bump-font -1])} "A−"]
        [:span {:class "w-12 text-center text-slate-200 text-sm"} (str @(rf/subscribe [:font-size]) "px")]
        [:button {:class "px-3 py-1 rounded bg-slate-800 border border-slate-700 text-slate-200"
                  :on-click #(rf/dispatch [:bump-font 1])} "A+"]
        [:button {:class "px-3 py-1 rounded bg-slate-800 border border-slate-700 text-slate-400"
                  :on-click #(rf/dispatch [:set-font-size 16])} "reset"]]
       [:div {:class "text-[0.7rem] text-slate-500"} "Ctrl+= / Ctrl+- / Ctrl+0 · saved locally"]]
      [:div {:class "border-t border-slate-700 pt-3 space-y-1"}
       [:label {:class "text-xs text-slate-400"} "text per message type"]
       (for [k seg-kinds] ^{:key k} [seg-style-row k])
       [:div {:class "flex justify-end"}
        [:button {:class "text-[0.7rem] text-slate-500 hover:text-rose-300"
                  :on-click #(rf/dispatch [:reset-seg-styles])} "reset all"]]
       [:div {:class "text-[0.7rem] text-slate-500"}
        "font, size and colour for each message type · saved locally"]]
      [:div {:class "border-t border-slate-700 pt-3 space-y-1"}
       [:label {:class "text-xs text-slate-400"} "voice input"]
       [:div {:class "text-xs text-slate-300"}
        (let [st @(rf/subscribe [:voice])]
          (if (and st (:enabled st))
            (str "on · " (:engine st) " · max " (:maxSeconds st) "s")
            (str "off — " (or (:reason st) "set GROG_VOICE_COMMAND"))))]
       [:div {:class "text-[0.7rem] text-slate-500"}
        "mic + transcription stay on this machine; nothing is sent to the server"]]
      ;; every section in here saves itself the moment it is touched, so the
      ;; dialog has no Save — a button implying pending changes that do not exist
      ;; is its own kind of confusion.
      [:div {:class "flex gap-2 justify-end text-sm border-t border-slate-700 pt-3"}
       [:button {:class "px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300"
                 :on-click #(rf/dispatch [:dialog-close])} "close ✕"]]]]))

(defn- mcp-row
  "One shipped MCP server: label, what it gives you, and either a readiness word
  or the thing it wants first. `:configured?` is true/false when the spine could
  check cheaply, nil when it would need a secret-store probe (show the hint
  instead of a status)."
  [m]
  (let [c (:configured? m)
        n (:needs m)]
    ^{:key (:id m)}
    [:div {:class "flex items-baseline gap-3 text-sm"}
     [:span {:class "font-mono text-slate-300 shrink-0 w-32 truncate"} (:label m)]
     [:span {:class "text-slate-400 flex-1 min-w-0"} (:what m)]
     (cond
       (true? c)  [:span {:class "text-teal-300 text-[0.7rem] shrink-0"} "ready"]
       (false? c) [:span {:class "text-amber-300 text-[0.7rem] shrink-0 max-w-[14rem] text-right"}
                   (or n "needs setup")]
       n          [:span {:class "text-slate-500 text-[0.7rem] shrink-0 max-w-[14rem] text-right"} n]
       :else      [:span {:class "text-slate-500 text-[0.7rem] shrink-0"} "ready"])]))

(defn- bootstrap-panel
  "The getting-started landing: shown instead of the transcript when THIS launch
  wants onboarding (a fresh install, or a config home that was just seeded or
  reset). Renders fully OFFLINE — no model, no network. The composer stays live
  underneath, because `/secret` is handled locally and needs no brain.

  `configured?` reflects whether GROG ITSELF has a brain (its own key or a local
  endpoint). When it already does — a key survived the reset — the page says so
  and offers a way through to the transcript, rather than claiming there is no
  brain."
  []
  (let [docs @(rf/subscribe [:docs-dir])
        configured? @(rf/subscribe [:bootstrap-configured?])
        mcps @(rf/subscribe [:bootstrap-mcps])]
    [:div {:class "flex-1 min-h-0 overflow-y-auto px-6 py-10 flex justify-center"}
     [:div {:class "max-w-2xl w-full space-y-5"}
      ;; the ouroboros emblem — the snake eating its own tail, the loop that says
      ;; "the app bootstraps itself". Art is <repo>/snake.png, kept current in
      ;; resources/public by scripts/sync-assets.js so it actually SHIPS with the
      ;; renderer. The on-error guard keeps the page intact if the art is ever
      ;; missing, rather than showing a broken-image glyph.
      [:div {:class "flex items-center gap-4"}
       [:img {:src "snake.png" :alt "ouroboros"
              :class "h-16 sm:h-20 w-auto max-w-[10rem] shrink-0 object-contain"
              :style {:filter "drop-shadow(0 0 18px rgba(46,199,205,0.35))"}
              :on-error (fn [e] (set! (.. e -target -style -display) "none"))}]
       [:div {:class "space-y-1"}
        [:div {:class "text-[0.7rem] uppercase tracking-widest seg-snark"} "getting started"]
        [:div {:class "text-2xl font-semibold text-slate-100"} "Bootstrap grog"]]]

      ;; what this actually IS — the wider point, not a vague "walk you through it"
      [:div {:class "text-sm text-slate-300 space-y-2"}
       [:p
        "This project is " [:span {:class "font-medium text-slate-100"} "ouroboros"]
        " — the snake eating its own tail. What is happening here is grog "
        [:span {:class "font-medium text-slate-100"}
         "bootstrapping an LLM to work inside this project"]
        ": me talking to myself, reading grog's own documentation, until there is a brain living here."]
       ;; THE point, stated plainly instead of buried in prose — the whole app in
       ;; one sentence. "Project by project" is load-bearing: each project is its
       ;; own loop, its own memory, its own chance to get smarter.
       [:p {:class "rounded-lg border border-teal-800/50 bg-teal-950/30 px-4 py-3 text-slate-100"}
        "Concept of GROG: a " [:span {:class "font-medium"} "vicious, deliberate cycle"]
        " that has the LLM eat its own tail and get smarter — "
        [:span {:class "font-medium"} "project by project"] "."]
       [:p
        "A " [:span {:class "font-medium text-slate-100"} "project"]
        " is where grog keeps its memory of one thing you work on — its notes, the conversation, "
        "its workspace, and the tools it is allowed to touch. Everything grog remembers belongs to a "
        "project, so two projects never bleed into each other. Keep as many as you like and move "
        "between them with " [:code "/project"] "."]
       [:p
        "ouroboros exists to get you running — it is not where your work lives. Once the brain is "
        "online, start the project you actually care about with "
        [:code "/project new <name>"] " and get on with it. This one stays behind as your reference "
        "copy of the documentation; come back whenever you want to read it."]]

      ;; the concrete job — or the all-clear
      (if configured?
        [:div {:class "rounded-xl border border-teal-700/60 bg-teal-950/40 p-4 text-sm text-teal-200"}
         "The brain is already online — grog found a working key (or a local endpoint). Nothing to fix: "
         "ask me anything, or point me at the docs."]
        [:div {:class "card rounded-xl p-4 space-y-3"}
         [:div {:class "text-slate-200 text-sm font-medium"} "Job 1 — get the brain online"]
         [:div {:class "text-sm text-slate-400"}
          "Two things, both yours to set. Nothing here guesses on your behalf."]
         [:ol {:class "list-decimal ml-5 space-y-2 text-sm text-slate-300"}
          [:li "Open " [:span {:class "text-slate-100"} "⚙ Settings"] " and set the provider and model "
           "to match what you actually have — grog thinks with whatever model you point it at, so this "
           "is the one setting that has to be right."]
          [:li "Hand grog the key with "
           [:code "/secret set LLM_API_KEY <value>"]
           " — type it straight into the composer below. It goes to your OS keychain and stays there; "
           "grog never echoes it back."]
          [:li "Send it (" [:span {:class "text-slate-100"} "Ctrl+Enter"]
           ") and this page steps aside for the transcript, so you can read the result — "
           "\"Stored … \" or the reason it failed. Bring the page back any time with "
           [:code "getting started"] " on the toolbar."]
          [:li "Restart grog. This page retires the moment the brain is reachable."]]])

      ;; slash commands + how to get help
      [:div {:class "card rounded-xl p-4 space-y-3"}
       [:div {:class "text-slate-200 text-sm font-medium"} "Commands and help"]
       [:div {:class "text-sm text-slate-400"}
        "Anything you type that starts with " [:code "/"] " is a command, not a message to the model. "
        "Most of them ALSO have a UI trigger — a tab, a dialog, a key. Type " [:code "/help"] " any time "
        "and this exact list prints into the conversation — the two never drift."]
       ;; rows come from `help-rows` — the SAME data `/help` renders (see help-markdown)
       (into [:div {:class "grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm"}]
             (mapcat (fn [[c d t]]
                       [(with-meta [:code {:class "text-slate-300"} c] {:key (str c ":cmd")})
                        (with-meta [:span {:class "text-slate-400"}
                                    d (when-not (= "—" t)
                                        [:span {:class "text-slate-500"} (str "  ·  " t)])]
                          {:key (str c ":desc")})])
                     help-rows))
       [:div {:class "text-[0.7rem] text-slate-500"}
        "Bare UI, no command: "
        (str/join " · " (map (fn [[k d]] (str k " — " d)) ui-shortcuts))]]

      ;; what else you can switch on — the MCP servers grog ships
      (when (seq mcps)
        [:div {:class "card rounded-xl p-4 space-y-3"}
         [:div {:class "text-slate-200 text-sm font-medium"} "Tools you can switch on"]
         [:div {:class "text-sm text-slate-400"}
          "These are grog's own MCP servers — capabilities I can use on this machine. They are all "
          "shipped and available; the ones with a note just want something first. Nothing here is "
          "required to chat."]
         ;; `into` (not a bare vector child) — a vector ON its own line would be read as a nested
         ;; element, not a list of children. Same rule that bit the markdown renderer.
         (into [:div {:class "space-y-2"}] (map mcp-row mcps))])

      ;; the short version, stated plainly
      [:div {:class "text-sm text-slate-300"}
       "The core of it: " [:span {:class "font-medium text-slate-100"} "⚙ Settings set to match your model"]
       ", and the " [:span {:class "font-medium text-slate-100"} "/secret"] " key. Everything else is optional."]

      [:div {:class "flex flex-wrap gap-2"}
       (when configured?
         [:button {:class "px-3 py-1.5 rounded-md bg-sky-500 text-slate-950 font-medium text-sm"
                   :on-click #(rf/dispatch [:dismiss-bootstrap])} "Start chatting"])
       [:button {:class "px-3 py-1.5 rounded-md bg-sky-500 text-slate-950 font-medium text-sm"
                 :on-click #(rf/dispatch [:open-doc "README.md"])} "Open README"]
       [:button {:class "px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300 text-sm"
                 :on-click #(rf/dispatch [:open-doc "USERS-GUIDE.md"])} "Users guide"]
       [:button {:class "px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300 text-sm"
                 :on-click #(rf/dispatch [:open-doc ""])} "Docs folder"]]
      (when docs
        [:div {:class "text-[0.7rem] text-slate-500 font-mono"} (str "docs: " docs)])]]))

(defn- drop-boot-card!
  "Remove the STATIC boot card that index.html paints before any JS runs. That
  card exists so the window is never an empty rectangle while the renderer
  bundle is parsed; once the real boot screen is on screen, it has done its job."
  []
  (when-let [el (.getElementById js/document "boot")]
    (set! (.. el -style -opacity) "0")
    (js/setTimeout (fn [] (when-let [e (.getElementById js/document "boot")]
                            (.remove e)))
                   240)))

(defn- boot-screen
  "The Indian-head test card, shown from the moment the window paints until a
  session is open (or the spine has told us onboarding is due) — i.e. while the
  spine is starting up. Unmounting this is what hands the screen to the matrix
  splash, so the test card is never on screen at the same time as the rain.

  The CARD IS THE WHOLE SCREEN: no caption, no status line. There is nothing
  here to say that the outline does not already say, and the text was the one
  part of it that changed with engine state."
  []
  (r/create-class
   {:component-did-mount (fn [_] (drop-boot-card!))
    :reagent-render
    (fn []
      [:div {:class "fixed inset-0 z-40 bg-black flex items-center justify-center overflow-hidden"}
       ;; VECTOR (the 3774 paths are vector; only the portrait is an embedded
       ;; raster): white knocked out as fill-opacity, ink #20A9C4 dimmed to
       ;; #0a333b at 30% opacity (the file says so itself). Scales to any window
       ;; size without resampling.
       [:img {:src "indian-head-cyan.svg"
              :alt "RCA Indian-head test pattern"
              :class "max-h-full max-w-full object-contain"}]])}))

(defn- shell []
  (let [active @(rf/subscribe [:active]) dialog @(rf/subscribe [:ui-dialog])
        boot? @(rf/subscribe [:bootstrap-needed?])
        opened? @(rf/subscribe [:opened?])]
    (if-not (or opened? boot?)
      ;; boot phase: the spine is still coming up
      [boot-screen]
      ;; boot phase is over: make sure the static card cannot outlive it (a hot
      ;; reload into an already-open session would otherwise leave it covering
      ;; the app, because `boot-screen` — which normally removes it — never mounts)
      (do
        (r/after-render (fn [] (drop-boot-card!)))
        [:div {:class "app h-screen w-screen flex flex-col overflow-hidden"}
     [banner]
     [:div {:class "flex-1 min-h-0 flex flex-col overflow-hidden"}
      [tab-strip]
      (cond
        boot?  [bootstrap-panel]
        ;; keyed by session so switching tabs REMOUNTS the transcript — a tab
        ;; you switch to opens at the bottom, not at the previous tab's scroll.
        active ^{:key active} [transcript active]
        :else  [:div {:class "flex-1 grid place-items-center text-slate-500"} "connecting…"])
      ;; the model's question sits INLINE above the composer — non-modal, no
      ;; focus steal. (approval-dialog stays modal: a blocked turn needs a yes.)
      [question-panel]
      [composer]
      [status-bar]]
     [approval-dialog]
     (when (= :projects dialog) [project-manager])
     (when (= :settings dialog) [settings-dialog])
     ;; The Models dialog is NOT gated on a session being open, unlike Settings:
     ;; a launch with no model at all is exactly the case where the user needs it,
     ;; and the getting-started page points here. The actions inside it that need
     ;; a session (picking) read the active id and are harmless when there is
     ;; none — the list, the editor and the templates all work regardless.
     (when (= :models dialog) [models-dialog])]))))

;; --- init ------------------------------------------------------------------

(defn ^:export init []
  (rf/dispatch-sync [:init])
  ;; restore the saved font size (font-zoom), then apply it
  (rf/dispatch-sync
   [:set-font-size
    (let [s (try (.getItem (.-localStorage js/window) "grog-web.font-size")
                 (catch :default _ nil))
          n (js/parseInt (or s "16"))]
      (if (js/isNaN n) 16 n))])
  ;; restore the per-kind transcript styles (font/size/colour overrides)
  (rf/dispatch-sync
   [:load-seg-styles
    (let [s (try (.getItem (.-localStorage js/window) "grog-web.seg-styles")
                 (catch :default _ nil))]
      (if s
        (try (js->clj (js/JSON.parse s) :keywordize-keys true)
             (catch :default _ {}))
        {}))])
  (.onNotify js/window.grogAPI
             (fn [msg] (rf/dispatch [:notify (js->clj msg :keywordize-keys true)])))
  ;; the log pane: every line the main process writes, and — if the pane was on
  ;; last launch — turn the feed back on so it is live from the start
  (.onLogLine js/window.grogAPI (fn [line] (rf/dispatch [:log-line line])))
  (when (= "1" (try (.getItem (.-localStorage js/window) "grog-web.log-open")
                    (catch :default _ nil)))
    (rf/dispatch [:toggle-log]))
  (.onFocus js/window.grogAPI (fn [f] (rf/dispatch [:focus (boolean f)])))
  ;; global shortcuts (§3.1)
  (.addEventListener
   js/document "keydown"
   (fn [e]
     (let [ctrl (.-ctrlKey e) shift (.-shiftKey e) k (.-key e)]
       (cond
         (and ctrl (= k "t")) (do (.preventDefault e) (rf/dispatch [:dialog-open :projects ""]))
         (and ctrl (= k "w")) (do (.preventDefault e) (rf/dispatch [:close-active]))
         (and ctrl (= k "Tab")) (do (.preventDefault e) (rf/dispatch [:cycle (if shift -1 1)]))
         (and ctrl (re-matches #"[1-9]" k)) (do (.preventDefault e) (rf/dispatch [:jump (js/parseInt k)]))
         (and ctrl shift (= (str/lower-case k) "e")) (do (.preventDefault e) (rf/dispatch [:export-html]))
         (and ctrl (= k "e")) (do (.preventDefault e) (rf/dispatch [:copy-transcript]))
         ;; the Models dialog, on a key: model selection is a first-class
         ;; activity now, not something buried behind ⚙. Ctrl+M works from
         ;; anywhere, including the boot screen and the getting-started page —
         ;; which is where a user who has no model yet needs it most.
         (and (not shift) ctrl (= (str/lower-case k) "m"))
         (do (.preventDefault e) (rf/dispatch [:dialog-open :models ""]))
         (and ctrl (or (= k "=") (= k "+"))) (do (.preventDefault e) (rf/dispatch [:bump-font 1]))
         (and ctrl (= k "-")) (do (.preventDefault e) (rf/dispatch [:bump-font -1]))
         (and ctrl (= k "0")) (do (.preventDefault e) (rf/dispatch [:set-font-size 16]))
         (and ctrl shift (= k " ")) (do (.preventDefault e) (rf/dispatch [:toggle-voice]))
         (= k "Escape") (rf/dispatch [:key-escape])
         :else nil))))
  ;; seed the expected socket path for the "unreachable at …" banner
  (-> (.socketPath js/window.grogAPI)
      (.then (fn [p] (rf/dispatch [:connected false p]))))
  ;; voice: ask the LOCAL engine (never the server) whether input is available
  (-> (.voiceStatus js/window.grogAPI)
      (.then (fn [s] (rf/dispatch [:voice-status (js->clj s :keywordize-keys true)])))
      (.catch (fn [_] nil)))
  ;; ASK THE SPINE which project to open (it decides — and on a fresh install it
  ;; seeds the getting-started project first), then open it. `:retry-open` asks
  ;; again on (re)attach if the server was not up yet.
  (rf/dispatch [:request-startup])
  (rdom/render [shell] (.getElementById js/document "app")))
