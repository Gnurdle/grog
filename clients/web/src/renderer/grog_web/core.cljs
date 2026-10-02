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
            [grog-web.md :as md]))

;; --- helpers ---------------------------------------------------------------

(defn- usage-text
  "Compact one-line summary of an ECA `usage` content."
  [c]
  (str (:sessionTokens c) " tok"
       (when (:lastMessageCost c) (str " · msg $" (:lastMessageCost c)))
       (when (:sessionCost c) (str " · $" (:sessionCost c)))))

(defn- line-of
  "Map one raw ECA `content` to a transcript segment, or nil to skip it.

  ECA streams many content types in a turn: reasoning (`reasonText` wrapped in
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
      "usage"           {:kind :usage    :text (usage-text content)}
      ;; lifecycle / noise — deliberately not painted
      ("reasonStarted" "reasonFinished" "progress" "metadata"
       "toolCallPrepare" "toolCallRunning") nil
      ;; unknown: log for discovery, don't paint a raw map
      (do (.log js/console "[grog-web] unhandled content type:" (pr-str type))
          nil))))

(def ^:private project-name "grog")

(defn- opt-label
  "Render one option from a question, tolerating ECA sending strings or maps."
  [o]
  (cond
    (string? o) o
    (map? o)    (str (or (:label o) (:value o) (:text o) (pr-str o)))
    :else       (str o)))

;; --- db / events -----------------------------------------------------------

(rf/reg-event-db :init
  (fn [_ _]
    {:sessions {} :order [] :active nil :focused? true :input ""
     :connected? false :socket-path "" :opened? false :font-size 16
     :dialog nil :dialog-input "" :q-input "" :projects []
     ;; model picker (settings dialog): catalogue from the server, which source
     ;; is being browsed, and the search string
     :catalogue nil :model-source "eca" :model-query ""
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

(rf/reg-event-db :open-ok
  (fn [db [_ snap]]
    (let [id (:id snap)]
      (if (contains? (:sessions db) id)
        ;; already-open project: the server returns the existing session id.
        ;; Focus it; do NOT clobber its transcript.
        (assoc db :active id :opened? true)
        (-> db
            (assoc-in [:sessions id]
                      {:id id :project (:project snap)
                       :model (:model snap) :status (or (:status snap) "idle")
                       :trust (boolean (:trust snap))
                       :running? (boolean (:running? snap))
                       :transcript (if (:banner snap)
                                     [{:kind :snark :text (str (:banner snap))}]
                                     [])})
            (update :order (fnil conj []) id)
            (assoc :active id :opened? true))))))

(rf/reg-event-db :select-tab (fn [db [_ id]] (assoc db :active id)))
(rf/reg-event-db :focus     (fn [db [_ f]] (assoc db :focused? f)))
(rf/reg-event-db :input     (fn [db [_ s]] (assoc db :input s)))
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
    (let [cur (str (:input db))
          sep (if (and (seq cur) (not (str/ends-with? cur " "))) " " "")]
      (assoc db :input (str cur sep text)))))

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
        {:dispatch [:status-line nil "[grog] voice off — set GROG_VOICE_COMMAND"]}

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

(rf/reg-event-fx :dialog-open
  (fn [{:keys [db]} [_ kind initial]]
    (cond-> {:db (assoc db :dialog kind :dialog-input (or initial ""))}
      ;; the project picker needs the live list from the server
      (= kind :projects) (assoc :dispatch [:fetch-projects])
      ;; the settings dialog opens onto the model picker's current source
      (= kind :settings) (assoc :dispatch [:models-fetch nil]))))

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

;; --- model picker ----------------------------------------------------------
;;
;; The catalogue lives on the SERVER (it has the config, the OpenRouter/Ollama
;; fetchers and ECA's own model list). `models` answers from cache, and a slow
;; source is refreshed in the background — the refreshed list arrives as a
;; `models` broadcast, so a first click on Ollama/OpenRouter shows "loading…"
;; and then fills in, rather than freezing the dialog.

(rf/reg-event-db :models-source
  (fn [db [_ source]] (assoc db :model-source source :model-query "")))

(rf/reg-event-db :model-query
  (fn [db [_ q]] (assoc db :model-query q)))

(rf/reg-event-db :catalogue
  (fn [db [_ cat]] (assoc db :catalogue (js->clj cat :keywordize-keys true))))

(rf/reg-event-db :models-source-loaded
  (fn [db [_ {:keys [source models]}]]
    (assoc-in db [:catalogue (keyword source)] (vec models))))

(rf/reg-event-fx :models-fetch
  "Ask for `source`'s models (default: whichever source is being browsed).
  `force` re-fetches a source we already have cached."
  (fn [{:keys [db]} [_ force]]
    (-> (.call js/window.grogAPI
               "models"
               (clj->js (cond-> {:source (or (:model-source db) "eca")}
                          force (assoc :force true))))
        (.then (fn [cat] (rf/dispatch [:catalogue cat])))
        (.catch (fn [e] (rf/dispatch [:status-line nil (str "[grog] models failed: " (.-message e))]))))
    {}))

(rf/reg-event-db :status-line
  (fn [db [_ sid text]]
    (let [sid (or sid (:active db))]
      (cond-> db
        sid (update-in [:sessions sid :transcript]
                       (fnil conj []) {:kind :line :text (str text)})))))

(rf/reg-event-db :ev
  (fn [db [_ wire]]
    (let [sid  (:sessionId wire)
          ev   (cond-> wire (string? (:type wire)) (update :type keyword))
          kind (:type ev)]
      (cond
        (= :content kind)
        (let [c (:content ev)
              db2 (append-segment db sid (line-of c))]
          (if (= "usage" (:type c))
            (assoc-in db2 [:sessions sid :usage] c)
            db2))
        (= :user kind)     (append-segment db sid {:kind :user :text (str (:text ev))})
        (= :line kind)     (append-segment db sid {:kind :line :text (str (:text ev))})
        (= :status kind)   (assoc-in db [:sessions sid :status] (str (:value ev)))
        (= :trust kind)    (assoc-in db [:sessions sid :trust] (boolean (:value ev)))
        (= :model kind)    (assoc-in db [:sessions sid :model] (:value ev))
        (= :clear kind)    (assoc-in db [:sessions sid :transcript] [])
        (= :approval kind) (assoc-in db [:sessions sid :pending-approval] (dissoc ev :answer))
        (= :question kind) (-> db
                               (assoc-in [:sessions sid :pending-question] (dissoc ev :answer))
                               (update-in [:sessions sid :transcript] (fnil conj [])
                                          {:kind :line :text "[LLM question pending]"}))
        :else db))))

(rf/reg-event-db :running
  (fn [db [_ sid on?]] (assoc-in db [:sessions sid :running?] (boolean on?))))

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
      ;; a background OpenRouter/Ollama refresh landed
      "models"        {:dispatch [:models-source-loaded (:params msg)]}
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
  (fn [{:keys [db]} _]
    (let [id (:active db) txt (:input db)]
      (if (and id (seq (str/trim txt)))
        (do
          (rf/dispatch [:running id true])
          (-> (.call js/window.grogAPI "prompt" (clj->js {:id id :text txt}))
              (.catch (fn [e]
                        (rf/dispatch [:running id false])
                        (rf/dispatch [:status-line id (str "[grog] prompt failed: " (.-message e))]))))
          {:db (assoc db :input "")})
        {}))))

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

(rf/reg-event-fx :set-model
  (fn [{:keys [db]} [_ id model source]]
    (let [m (str/trim (str model))]
      (if (seq m)
        (do
          ;; `source` (from the picker) lets the server qualify the id exactly:
          ;; an OpenRouter catalog id must not be mistaken for a native
          ;; provider just because its org slug matches one.
          (-> (.call js/window.grogAPI "set-model"
                     (clj->js (cond-> {:id id :model m}
                                source (assoc :source (str source)))))
              (.catch (fn [e] (rf/dispatch [:status-line id (str "[grog] set-model failed: " (.-message e))]))))
          {:db (assoc db :dialog nil)})
        {}))))

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

(rf/reg-event-fx :retry-open
  (fn [{:keys [db]} [_ project]]
    (when-not (:opened? db) (rf/dispatch [:open (or project project-name)]))
    {}))

;; --- subs ------------------------------------------------------------------

(rf/reg-sub :order   (fn [db _] (:order db)))
(rf/reg-sub :active  (fn [db _] (:active db)))
(rf/reg-sub :focused (fn [db _] (:focused? db)))
(rf/reg-sub :input   (fn [db _] (:input db)))
(rf/reg-sub :q-input (fn [db _] (:q-input db)))
(rf/reg-sub :connected? (fn [db _] (:connected? db)))
(rf/reg-sub :voice (fn [db _] (:voice db)))
(rf/reg-sub :recording? (fn [db _] (:recording? db)))
(rf/reg-sub :transcribing? (fn [db _] (:transcribing? db)))
(rf/reg-sub :font-size (fn [db _] (or (:font-size db) 16)))
(rf/reg-sub :socket-path (fn [db _] (:socket-path db)))
(rf/reg-sub :retry (fn [db _] (:retry db)))
(rf/reg-sub :sessions (fn [db _] (:sessions db)))
(rf/reg-sub :sess (fn [db [_ id]] (get-in db [:sessions id])))
(rf/reg-sub :ui-dialog (fn [db _] (:dialog db)))
(rf/reg-sub :dialog-input (fn [db _] (:dialog-input db)))
(rf/reg-sub :projects (fn [db _] (:projects db)))
(rf/reg-sub :catalogue (fn [db _] (:catalogue db)))
(rf/reg-sub :model-source (fn [db _] (:model-source db)))
(rf/reg-sub :model-query (fn [db _] (:model-query db)))
(rf/reg-sub :pending-questions
  (fn [db _] (->> (:sessions db) (keep (fn [[id s]] (when (:pending-question s) id))) set)))

;; --- views -----------------------------------------------------------------

(defn- role-label [kind]
  (case kind :answer "answer" :thinking "think" :tool "tool"
        :user "user" :usage "usage" "·"))

(defn- segment-view [{:keys [kind text]}]
  (cond
    (= :snark kind)
    [:div {:class "text-center seg-snark italic text-sm py-1"} text]

    ;; assistant answer / reasoning render as Markdown — real <table>s and
    ;; monospace <pre>/<code>.
    ;; The role label sits in a fixed column so blocks indent under it.
    (contains? #{:answer :thinking} kind)
    [:div {:class "flex gap-2 text-sm"}
     [:span {:class (str "flex-none pt-0.5 text-[0.7rem] "
                         (if (= :thinking kind) "role-think" "role-answer"))}
      (role-label kind)]
     (into [:div {:class (str "flex-1 min-w-0 md "
                              (if (= :thinking kind) "seg-think" "seg-answer"))}]
           (md/->hiccup text))]

    :else
    [:div {:class (str "text-sm " (if (= :user kind) "text-right" ""))}
     [:span {:class (str (case kind :tool "role-tool" :user "role-user" "role-snark")
                         " text-[0.7rem] mr-2 align-top")}
      (role-label kind)]
     [:span {:class (case kind :user "seg-user" :tool "seg-tool" "seg-answer")
             :style {:white-space "pre-wrap"}}
      text]]))

(defonce ^:private !scroll-el (atom nil))

(defn- scroll-end! []
  (when-let [n @!scroll-el]
    (set! (.-scrollTop n) (.-scrollHeight n))))

(defn- has-messages?
  "True once a real turn has happened (the splash logo retires then, like
  client 1's :splash?)."
  [segs]
  (boolean (some #(contains? #{:user :answer :thinking :tool} (:kind %)) segs)))

;; --- matrix rain (splash background) ---------------------------------------
;;
;; The splash sits on a Matrix-style falling-glyph canvas. It lives only as long
;; as the splash does: `transcript` swaps the splash out the moment a real turn
;; exists (`has-messages?`), which unmounts the canvas and cancels the RAF loop
;; via :component-will-unmount. So the rain "comes down" when work starts.

(defonce ^:private !matrix (atom {:raf nil :resize nil}))
(defonce ^:private !splash-canvas (atom nil))

;; Milliseconds between rain steps. The RAF loop runs every frame (~60fps) but
;; only paints once this much time has elapsed, so the glyphs fall at a calm,
;; readable pace instead of a frantic blur. Bump this to slow it further.
(def ^:private matrix-frame-ms 55)

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

(defn- start-matrix! [canvas]
  (stop-matrix!)
  (let [ctx    (.getContext canvas "2d")
        chars  matrix-glyphs
        nchars (count chars)
        font   16
        st     (atom {:cols 1 :drops (array) :w 0 :h 0})
        !last  (atom 0)]
    (letfn [(fit []
              (let [w    (.-clientWidth canvas)
                    h    (.-clientHeight canvas)
                    cols (int (max 1 (js/Math.floor (/ w font))))]
                (set! (.-width canvas) w)
                (set! (.-height canvas) h)
                (swap! st assoc :cols cols :w w :h h
                       :drops (into-array (repeat cols 1)))))
            (draw [now]
              ;; time-gated: skip frames until matrix-frame-ms has passed
              (when (>= (- now (or @!last 0)) matrix-frame-ms)
                (reset! !last now)
                (let [{:keys [cols drops w h]} @st]
                  ;; fade the previous frame toward the page bg, then paint glyphs
                  (set! (.-fillStyle ctx) "rgba(13,14,17,0.10)")
                  (.fillRect ctx 0 0 w h)
                  ;; teal — matches the splash logo palette
                  (set! (.-fillStyle ctx) "#2ec7cd")
                  (set! (.-font ctx) matrix-font)
                  (dotimes [i cols]
                    (let [i  (int i)
                          ch (.charAt chars (js/Math.floor (* (js/Math.random) nchars)))
                          y  (* (aget drops i) font)]
                      (.fillText ctx ch (* i font) y)
                      (when (and (> y h) (> (js/Math.random) 0.975))
                        (aset drops i 0))
                      (aset drops i (inc (aget drops i)))))))
              (swap! !matrix assoc :raf (js/requestAnimationFrame draw)))]
      (fit)
      (let [on-resize (fn [_] (fit))]
        (.addEventListener js/window "resize" on-resize)
        (swap! !matrix assoc :resize on-resize))
      (draw (js/performance.now)))))

(defn- splash [segs]
  (r/create-class
   {:component-did-mount
    (fn [_]
      (when-let [c @!splash-canvas]
        (start-matrix! c)))
    :component-will-unmount (fn [_] (stop-matrix!))
    :reagent-render
    (fn [segs]
      (let [notes (filter #(contains? #{:snark :line} (:kind %)) segs)]
        [:div {:class "absolute inset-0 flex flex-col items-center justify-center gap-3 overflow-hidden p-6"}
         [:canvas {:ref (fn [el] (reset! !splash-canvas el))
                   :class "absolute inset-0 w-full h-full"}]
         [:div {:class "relative z-10 flex flex-col items-center gap-3 w-full max-h-full min-h-0"}
          ;; the logo yields space first (min-h-0 + shrink + explicit max-height)
          ;; so the snark line and hint below it are never clipped at the bottom.
          [:img {:src "logo.jpg" :alt "grog"
                 :class "w-auto max-w-[70vw] min-h-0 shrink object-contain rounded-xl shadow-2xl opacity-50"
                 :style {:max-height "50vh"}}]
          (when (seq notes)
            [:div {:class "flex flex-col items-center gap-1 max-w-3xl max-h-[22vh] overflow-y-auto min-h-0"}
             (for [[i s] (map-indexed vector notes)]
               ^{:key i} [:div {:class "text-center text-sm italic seg-snark"} (:text s)])])]]))}))

(defn- transcript [sid]
  (let [s @(rf/subscribe [:sess sid])
        segs (:transcript s)]
    (r/after-render scroll-end!)
    [:div {:ref (fn [el] (reset! !scroll-el el))
           :class "relative flex-1 min-h-0 overflow-y-auto px-6 py-4 space-y-3"}
     (if (has-messages? segs)
       (for [[i seg] (map-indexed vector segs)]
         ^{:key i} [segment-view seg])
       [splash segs])]))

(defn- tab-strip []
  (let [order @(rf/subscribe [:order]) active @(rf/subscribe [:active])
        pending @(rf/subscribe [:pending-questions]) sessions @(rf/subscribe [:sessions])]
    [:div {:class "flex items-center gap-1 px-2 pt-2 bg-slate-900/70 border-b border-slate-700"}
     (for [id order]
       (let [pend? (contains? pending id) running? (get-in sessions [id :running?])]
         ^{:key id}
         [:div {:on-click #(rf/dispatch [:select-tab id])
                :title (when pend? "Question pending — dialog appears when this tab is selected and the window is focused")
                :class (str "group flex items-center gap-2 px-3 py-2 rounded-t-md text-sm cursor-pointer "
                            (if pend? "bg-amber-500/10 border border-amber-500/40 border-b-0 text-amber-200"
                                (if (= id active) "bg-sky-500/15 border border-sky-500/40 border-b-0 text-sky-200"
                                    "text-slate-400 hover:bg-slate-800")))}
          [:span {:class (str "w-2 h-2 rounded-full "
                              (cond pend? "bg-amber-400 animate-pulse"
                                    running? "bg-sky-400 animate-pulse"
                                    :else "bg-emerald-400"))}]
          (get-in sessions [id :project])
          (when pend? [:span {:class "px-1 rounded bg-amber-500/20 text-[0.64rem] text-amber-300"} "?"])
          [:span {:class "ml-1 opacity-0 group-hover:opacity-100 text-slate-500 hover:text-rose-300"
                  :on-click (fn [e] (.stopPropagation e)
                              (rf/dispatch [:close-active id]))} "✕"]]) )
     [:div {:class "ml-auto flex items-center gap-1 text-xs pr-1"}
      [:button {:title "New tab (Ctrl+T)" :class "px-2 py-1 rounded text-slate-400 hover:bg-slate-800"
                :on-click #(rf/dispatch [:dialog-open :projects ""])} "+"] ]]))

(defn- toolbar []
  (let [active @(rf/subscribe [:active])
        sess (get-in @(rf/subscribe [:sessions]) [active])
        running? (:running? sess) trust? (:trust sess)
        st @(rf/subscribe [:voice])
        recording? @(rf/subscribe [:recording?])
        transcribing? @(rf/subscribe [:transcribing?])
        voice? (and st (:enabled st))]
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
                            :else "Voice off — set GROG_VOICE_COMMAND")
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
     [:button {:disabled (not active) :title "Settings"
               :class "px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300 disabled:opacity-40"
               :on-click #(rf/dispatch [:dialog-open :settings (get-in @(rf/subscribe [:sessions]) [active :model])])}
      "⚙"]
     [:button {:disabled (not active) :title "Copy transcript (Ctrl+E)"
               :class "px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300 disabled:opacity-40"
               :on-click #(rf/dispatch [:copy-transcript])} "⧉"]]))

(defn- composer []
  (let [txt @(rf/subscribe [:input]) active @(rf/subscribe [:active])]
    [:div {:class "border-t border-slate-700 bg-slate-900/50 p-3 space-y-2"}
     [toolbar]
     [:textarea {:id "grog-composer" :rows 3 :value txt :placeholder "Ctrl+Enter sends · Enter newline"
                 :disabled (not active)
                 :on-change #(rf/dispatch [:input (.. % -target -value)])
                 :on-key-down #(when (and (= "Enter" (.-key %)) (.-ctrlKey %))
                                 (.preventDefault %) (rf/dispatch [:send]))
                 :class "w-full rounded-md bg-[#121212] border border-slate-700 px-3 py-2 text-sm text-slate-200 outline-none focus:border-sky-500"}]
     [:div {:class "text-[0.7rem] text-slate-500"} "Ctrl+Enter send · Ctrl+Shift+Space voice · /clear · /yolo"]]))

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
     (when sess [:span (str "model: " (or (:model sess) "?"))])
     (when-let [u (:usage sess)] [:span (str (:sessionTokens u) " tok · $" (:sessionCost u))])
     (when sess [:span (if (:running? sess) "● streaming" "idle")])
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

(defn- question-dialog []
  (let [focused @(rf/subscribe [:focused]) active @(rf/subscribe [:active])
        sess (get-in @(rf/subscribe [:sessions]) [active])
        qin @(rf/subscribe [:q-input])]
    (when (and focused active (:pending-question sess))
      (let [q (:pending-question sess) sid active
            ans (fn [res] (rf/dispatch [:answer-question sid res]))]
        [overlay
         [:div {:class "card rounded-xl shadow-2xl p-4 space-y-3 w-[620px]"}
          [:div {:class "text-slate-200 text-sm font-medium"} "❓ The model is asking"]
          [:div {:class "text-slate-300 text-sm"} (:prompt q)]
          (when (seq (:options q))
            [:div {:class "space-y-1"}
             (for [[i o] (map-indexed vector (:options q))]
               ^{:key i}
               [:button {:class "w-full text-left px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-200 hover:border-sky-500"
                         :on-click #(ans {:cancelled false :answer (opt-label o)})}
                (opt-label o)])])
          (when (:allowFreeform? q)
            [:input {:value qin :placeholder "or type an answer…" :autoFocus true
                     :on-change #(rf/dispatch [:q-input (.. % -target -value)])
                     :on-key-down #(when (= "Enter" (.-key %))
                                     (ans {:cancelled false :answer qin}))
                     :class "w-full rounded-md bg-[#121212] border border-slate-700 px-3 py-2 text-sm text-slate-200 outline-none focus:border-sky-500"}])
          [:div {:class "flex gap-2 justify-end text-sm"}
           [:button {:class "px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300"
                     :on-click #(ans {:cancelled true :answer nil})} "Cancel"]
           [:button {:class "px-3 py-1.5 rounded-md bg-sky-500 text-slate-950 font-medium"
                     :on-click #(ans {:cancelled false :answer qin})} "Answer"]]]]))))

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

(def ^:private picker-sources
  "Model sources the picker offers, in order. `eca` is ECA's own catalogue
  (offline, arrives on connect); the other two are fetched."
  [["eca" "ECA"] ["openrouter" "OpenRouter"] ["ollama" "Ollama"]])

(defn- settings-dialog []
  (let [in @(rf/subscribe [:dialog-input]) active @(rf/subscribe [:active])
        sess (get-in @(rf/subscribe [:sessions]) [active])
        src @(rf/subscribe [:model-source])
        q @(rf/subscribe [:model-query])
        cat @(rf/subscribe [:catalogue])
        all (get cat (keyword src))
        all (or all [])
        shown (if (str/blank? q)
                all
                (filterv #(str/includes? (str/lower-case %) (str/lower-case q)) all))
        loading-src? (some #(= src %) (:loading cat))]
    [overlay
     [:div {:class "card rounded-xl shadow-2xl p-4 space-y-3 w-[520px] max-h-[85vh] overflow-y-auto"}
      [:div {:class "text-slate-200 text-sm font-medium"} "Settings"]
      [:div {:class "text-xs text-slate-400"} (str "project: " (:project sess))]
      [:label {:class "text-xs text-slate-400"} "model"]
      [:input {:value in :autoFocus true :placeholder (or (:model sess) "provider/model")
               :on-change #(rf/dispatch [:dialog-input (.. % -target -value)])
               :on-key-down #(when (= "Enter" (.-key %)) (rf/dispatch [:set-model active in]))
               :class "w-full rounded-md bg-[#121212] border border-slate-700 px-3 py-2 text-sm text-slate-200 outline-none focus:border-sky-500"}]
      [:div {:class "text-[0.7rem] text-slate-500"} "persists via /eca-model on the server"]

      ;; browse + search + pick. The list comes from the server; a source that
      ;; has never been fetched shows "loading…" and fills in via broadcast.
      [:div {:class "border-t border-slate-700 pt-3 space-y-2"}
       [:div {:class "flex items-center gap-1"}
        (into [:span {:class "flex items-center gap-1"}]
              (for [[id label] picker-sources]
                ^{:key id}
                [:button {:class (str "px-2 py-1 rounded text-[0.7rem] border "
                                      (if (= id src)
                                        "bg-sky-600 border-sky-500 text-slate-950 font-medium"
                                        "bg-slate-800 border-slate-700 text-slate-300"))
                          :on-click #(rf/dispatch [:models-source id])}
                 label]))
        [:button {:class "ml-auto px-2 py-1 rounded text-[0.7rem] bg-slate-800 border border-slate-700 text-slate-300"
                  :title "refresh this source"
                  :on-click #(rf/dispatch [:models-fetch :force])} "↻"]]
       [:input {:value q :placeholder "search models…"
                :on-change #(rf/dispatch [:model-query (.. % -target -value)])
                :on-key-down #(when (= "Escape" (.-key %)) (rf/dispatch [:model-query ""]))
                :class "w-full rounded-md bg-[#121212] border border-slate-700 px-3 py-1.5 text-xs text-slate-200 outline-none focus:border-sky-500"}]
       (into [:div {:class "max-h-48 overflow-y-auto rounded-md border border-slate-700"}]
             (cond
               loading-src?
               [[:div {:class "p-2 text-xs text-slate-500"} (str "loading " src "…")]]

               (empty? shown)
               [[:div {:class "p-2 text-xs text-slate-500"}
                 (str "no models — " (if (= "eca" src)
                                       "connect a session, or pick another source"
                                       "press ↻ to fetch"))]]

               :else
               (for [m shown]
                 ^{:key m}
                 [:div {:class "px-2 py-1 text-[0.7rem] font-mono text-slate-300 hover:bg-slate-800 cursor-pointer"
                        :on-click #(rf/dispatch [:dialog-input m])
                        :on-double-click #(rf/dispatch [:set-model active m src])}
                  m])))
       [:div {:class "text-[0.7rem] text-slate-500"}
        (str (count shown) " model" (when (not= 1 (count shown)) "s")
             " · click fills the box, double-click applies")]]
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
       [:label {:class "text-xs text-slate-400"} "voice input"]
       [:div {:class "text-xs text-slate-300"}
        (let [st @(rf/subscribe [:voice])]
          (if (and st (:enabled st))
            (str "on · " (:engine st) " · max " (:maxSeconds st) "s")
            "off — set GROG_VOICE_COMMAND"))]
       [:div {:class "text-[0.7rem] text-slate-500"}
        "mic + transcription stay on this machine; nothing is sent to the server"]]
      [:div {:class "flex gap-2 justify-end text-sm"}
       [:button {:class "px-3 py-1.5 rounded-md bg-slate-800 border border-slate-700 text-slate-300"
                 :on-click #(rf/dispatch [:dialog-close])} "Cancel"]
       [:button {:class "px-3 py-1.5 rounded-md bg-sky-500 text-slate-950 font-medium"
                 :on-click #(rf/dispatch [:set-model active in])} "Save"]]]]))

(defn- shell []
  (let [active @(rf/subscribe [:active]) dialog @(rf/subscribe [:ui-dialog])]
    [:div {:class "app h-screen w-screen flex flex-col overflow-hidden"}
     [banner]
     [:div {:class "flex-1 min-h-0 flex flex-col overflow-hidden"}
      [tab-strip]
      (if active [transcript active]
          [:div {:class "flex-1 grid place-items-center text-slate-500"} "connecting…"])
      [composer]
      [status-bar]]
     [approval-dialog]
     [question-dialog]
     (when (= :projects dialog) [project-manager])
     (when (= :settings dialog) [settings-dialog])]))

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
  (.onNotify js/window.grogAPI
             (fn [msg] (rf/dispatch [:notify (js->clj msg :keywordize-keys true)])))
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
         (and ctrl (= k "e")) (do (.preventDefault e) (rf/dispatch [:copy-transcript]))
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
  ;; if the daemon is already attached, open now; else :retry-open fires on attach.
  (-> (.call js/window.grogAPI "open" (clj->js {:project project-name}))
      (.then (fn [snap]
               (let [snap (js->clj snap :keywordize-keys true)]
                 (rf/dispatch [:open-ok snap])
                 (rf/dispatch [:connect (:id snap)]))))
      (.catch (fn [e] (rf/dispatch [:status-line nil (str "[grog] open failed: " (.-message e))]))))
  (rdom/render [shell] (.getElementById js/document "app")))
