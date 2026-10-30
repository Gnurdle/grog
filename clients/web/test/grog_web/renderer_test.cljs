(ns grog-web.renderer-test
  "Offline regression tests for the renderer's EVENT logic — no browser, no
  Electron, no spine.

  These exist because of a real bug that every other test in this repo was blind
  to. The report: *\"during initial onboarding, I try to set my secret, and
  nothing scrolls. It's in HTML dump, but the actual output screen is inert.\"*

  The getting-started landing REPLACES the transcript (`shell` renders
  `bootstrap-panel` in its place) and has no output surface of its own — while
  `/secret set …`, the command the landing itself tells you to type, publishes its
  confirmation AND its errors as `:line` segments INTO the transcript. So the
  command genuinely ran and genuinely replied; the reply was simply written
  somewhere no visible component read. Success and failure looked identical, and
  nothing scrolled, because nothing new was on screen.

  That is why this file asserts on the EVENTS and the SUBSCRIPTIONS the real
  components read, rather than on rendered output: the defect was never in the
  rendering of the segments, it was in which surface had the screen.

  It drives the real re-frame handlers registered by `grog-web.core` — nothing is
  re-implemented here, so a change to `:send` or to the landing's visibility is
  measured rather than described. The only addition is a seed event, so production
  code carries no test-only hooks.

  Run: `npm run test:renderer` (CI: the \"Offline JS tests\" step)."
  (:require [re-frame.core :as rf]
            [grog-web.core]))

;; --- node has no DOM: stub exactly what the send path touches ----------------

(defonce ^:private sent (atom []))

(defn- stub!
  "The renderer only ever reaches the main process through `window.grogAPI`, so a
  stub that records calls is enough to observe what was sent.

  `answers` optionally gives per-method REPLIES — the model dialog's operations
  are request/response (a pick asks the spine to resolve a name; a save can be
  refused), so a test has to be able to hand back what the spine would, including
  an error."
  ([] (stub! {}))
  ([answers]
   (set! (.-window js/globalThis)
         #js {:grogAPI #js {:call (fn [method payload]
                                    ;; `method` is already a JS string, and CLJS
                                    ;; strings ARE JS strings — no conversion.
                                    (let [_ (swap! sent conj [method (js->clj payload :keywordize-keys true)])]
                                      (if-let [a (get answers method)]
                                        ;; an Error reply REJECTS, like a failed RPC
                                        (if (instance? js/Error a)
                                          (js/Promise.reject a)
                                          (js/Promise.resolve (clj->js a)))
                                        (js/Promise.resolve nil))))}
              :localStorage #js {:getItem (fn [_] nil) :setItem (fn [_ _] nil)}})))

;; --- seeding ----------------------------------------------------------------
;;
;; The seed takes every flag EXPLICITLY, so a test cannot accidentally assert
;; against a state production would never be in.

(rf/reg-event-db
 :renderer-test/seed
 (fn [_ [_ m]]
   (merge {:opened? true :order ["s#1"] :first-run? true
           :sessions {"s#1" {:id "s#1" :input "" :attachments []}}}
          m)))

(rf/reg-event-db
 :renderer-test/add-session
 (fn [db [_ {:keys [id project]}]]
   (-> db
       (assoc-in [:sessions id] {:id id :project project :input "" :attachments []})
       (update :order conj id))))

;; --- assertions -------------------------------------------------------------

(def ^:private failures (atom []))

(defn- check! [label expected actual]
  (let [ok (= expected actual)]
    (when-not ok
      (swap! failures conj (str label " — expected " (pr-str expected)
                               ", got " (pr-str actual))))
    (println (str (if ok "  ok   " "  FAIL ") label
                  (when-not ok (str "   expected " (pr-str expected)
                                    ", got " (pr-str actual)))))))

(defn- sub        [] @(rf/subscribe [:db]))
(defn- needed?    [] @(rf/subscribe [:bootstrap-needed?]))
(defn- leavable?  [] @(rf/subscribe [:bootstrap-leavable?]))
(defn- transcript [] (get-in (sub) [:sessions "s#1" :transcript]))

;; re-frame's `:dispatch` (used by `:send` for `:help`/`:set-model-slot`, and by
;; `:show-bootstrap` for its status line) is ASYNCHRONOUS — `dispatch-sync` on a
;; later event does NOT drain it. So wait a macrotask after anything that can
;; dispatch, rather than assuming the queue is empty.
(defn- tick [] (js/Promise. (fn [res] (js/setTimeout res 0))))

;; A promise-returning handler that dispatches further events (set-provider
;; resolves, THEN dispatches providers-load, which re-frame processes on its own
;; tick) needs more than one macrotask to settle — ONE tick is flaky. Chain a few.
(defn- settle []
  (-> (tick) (.then (fn [_] (tick))) (.then (fn [_] (tick)))))

(defn- seed-onboarding!
  "A db shaped like a real onboarding launch: ouroboros open, landing showing."
  []
  (rf/dispatch-sync [:renderer-test/seed
                     {:home-project "ouroboros" :active "s#1"
                      :sessions {"s#1" {:id "s#1" :project "ouroboros"
                                        :input "" :attachments []}}
                      :bootstrap-needed? true :bootstrap-dismissed? false}]))

(defn- send!
  "Type `text` into the composer and send it — the exact path the composer's
  Ctrl+Enter takes."
  [text]
  (rf/dispatch-sync [:input text])
  (rf/dispatch-sync [:send])
  (tick))

(defn main [& _]
  (stub!)
  (println "\n== renderer: does sending reveal command output? ==\n")

  (-> (js/Promise.resolve)
      (.then (fn [_]
               (println "before anything is typed:")
               (seed-onboarding!)
               (check! "the landing is showing" true (needed?))
               (check! "the toolbar offers no way back (nothing to go back to)"
                       false (leavable?))
               (check! "nothing has been sent" [] @sent)))

      ;; THE BUG. `/secret` is a real slash command: it goes to the spine, whose
      ;; reply arrives as `:line` segments in the transcript. Those are only
      ;; visible if the transcript has the screen — which is what the fix arranges.
      (.then (fn [_]
               (println "\nsending `/secret set LLM_API_KEY …`:")
               (send! "/secret set LLM_API_KEY sk-not-a-real-key")))
      (.then (fn [_]
               (check! "the landing steps aside (transcript now visible)" false (needed?))
               (check! "the command still reached the spine"
                       [["prompt" {:id "s#1" :text "/secret set LLM_API_KEY sk-not-a-real-key"}]]
                       @sent)
               (check! "the toolbar now offers the way back" true (leavable?))))

      ;; `/help` is answered entirely client-side and never reaches a model — same
      ;; invisibility, same fix.
      (.then (fn [_]
               (println "\nsending `/help` (answered locally, never reaches a model):")
               (reset! sent [])
               (seed-onboarding!)
               (send! "/help")))
      (.then (fn [_]
               (check! "the landing steps aside" false (needed?))
               (check! "and /help was answered locally (nothing sent to the spine)" [] @sent)
               (check! "the command list landed in the transcript"
                       [:user :answer] (mapv :kind (transcript)))
               ;; The `/help` reply is built from `help-rows`, and a command that
               ;; is missing from that table is a command the GUI user cannot
               ;; discover — the reported "there doesn't seem to be any help for
               ;; your last change (set the port)". So assert the COMMANDS, not
               ;; just that some table rendered: `/provider` and its port form
               ;; must appear, and the port row must say what a port is FOR.
               (let [text (->> (transcript) (filter #(= :answer (:kind %)))
                               (map :text) (apply str))]
                 (check! "the help mentions /provider" true
                         (boolean (re-find #"/provider" text)))
                 (check! "…and how to repoint a LOCAL server" true
                         (boolean (re-find #"/provider <id>|/provider strata 8081" text)))
                 ;; the GUI row must name the dialog the model is actually picked
                 ;; in — pointing at a port field that no longer exists is the
                 ;; same dead end as no help at all.
                 (check! "…and points at the Models dialog, not a field that is gone"
                         true (boolean (re-find #"Models dialog" text)))
                 (check! "…never mentioning a port FIELD (the port lives in the URL now)"
                         false (boolean (re-find #"port field" text))))))

      ;; A blank send must NOT dismiss: nothing was asked, so there is no output
      ;; to reveal, and taking the page away would be pure loss.
      (.then (fn [_]
               (println "\nsending only whitespace:")
               (reset! sent [])
               (seed-onboarding!)
               (send! "   ")))
      (.then (fn [_]
               (check! "the landing stays" true (needed?))
               (check! "nothing was sent" [] @sent)))

      ;; The prose has to be recoverable, or the fix just swaps one dead end for
      ;; another.
      (.then (fn [_]
               (println "\nbringing the landing back from the toolbar:")
               (seed-onboarding!)
               (send! "/secret list")))
      (.then (fn [_]
               (rf/dispatch-sync [:show-bootstrap])
               (tick)))
      (.then (fn [_]
               (check! "the landing is showing again" true (needed?))
               (check! "the dismiss latch is cleared, so a later snapshot cannot undo it"
                       false (:bootstrap-dismissed? (sub)))
               (check! "so the button hides again" false (leavable?))))

      ;; REGRESSION: on a NORMAL launch there is no landing, so a send must not
      ;; set the latch — otherwise the toolbar offers onboarding over unrelated
      ;; work. (Caught this while writing the fix: the first version dismissed
      ;; unconditionally.)
      (.then (fn [_]
               (println "\na normal launch (no onboarding), then a send:")
               (reset! sent [])
               (rf/dispatch-sync
                [:renderer-test/seed
                 {:home-project "grog" :active "s#1"
                  :sessions {"s#1" {:id "s#1" :project "grog"
                                    :input "" :attachments []}}
                  :bootstrap-needed? false :bootstrap-dismissed? false}])
               (send! "hello")))
      (.then (fn [_]
               (check! "the landing is still not showing" false (needed?))
               (check! "no latch was set" false (:bootstrap-dismissed? (sub)))
               (check! "so no way-back button is offered" false (leavable?))
               (check! "the message went through"
                       [["prompt" {:id "s#1" :text "hello"}]] @sent)))

      ;; Someone who typed on ouroboros and then moved to their own project must
      ;; not be offered ouroboros prose from THAT project's toolbar.
      (.then (fn [_]
               (println "\nafter moving to a DIFFERENT project:")
               (seed-onboarding!)
               (send! "/secret list")))
      (.then (fn [_]
               (rf/dispatch-sync [:renderer-test/add-session
                                  {:id "mine#2" :project "my-real-work"}])
               (rf/dispatch-sync [:select-tab "mine#2"])
               (check! "the way-back button is not offered for unrelated work"
                       false (leavable?))))

      ;; The toolbar button must be honest when there is no landing to show —
      ;; silently flipping a flag that renders nothing is the bug class this whole
      ;; file exists to catch.
      (.then (fn [_]
               (println "\nasking for the landing with no startup answer:")
               (rf/dispatch-sync
                [:renderer-test/seed
                 {:home-project nil :active "s#1"
                  :sessions {"s#1" {:id "s#1" :project "ouroboros"
                                    :input "" :attachments []}}
                  :bootstrap-needed? false :bootstrap-dismissed? true}])
               (rf/dispatch-sync [:show-bootstrap])
               (tick)))
      (.then (fn [_]
               (check! "it does not claim to show a page that cannot render" false (needed?))
               (check! "and it says so instead of silently doing nothing"
                       true (boolean (some #(re-find #"no getting-started page"
                                                     (str (:text %)))
                                           (transcript))))))


      ;; ---------------------------------------------------------------------
      ;; THE MODELS DIALOG. Rewritten after: "I am extremely disappointed on how
      ;; the model selection is implemented. It is a damn mess, and virtually
      ;; impossible to use." The old panel spread ONE decision over a provider
      ;; combo, a port box, a model-id box and a catalogue list, and needed three
      ;; RPCs to land it — every reported bug (the port that would not stick, the
      ;; bogus model, the recent that switched the id but not the endpoint) came
      ;; from that shape. What it is now: a model is a NAMED record {name url
      ;; model}, the baked-in endpoints are TEMPLATES for a new one, and every
      ;; operation is one call. These check the CONTRACT of each operation,
      ;; because that is what the old panel kept breaking.
      ;; ---------------------------------------------------------------------
      (.then (fn [_]
               (println "\nthe dialog opens on the LIST, with its own data:")
               (reset! sent [])
               (rf/dispatch-sync
                [:renderer-test/seed
                 {:home-project "grog" :active "s#1"
                  :sessions {"s#1" {:id "s#1" :project "grog" :input "" :attachments []}}
                  ;; stale crud from an earlier visit must not survive
                  :dialog nil :choice-edit "strata" :choice-name "strata"
                  :choice-url "http://nowhere" :choice-model "stale"
                  :choice-catalogue {"http://nowhere" ["stale"]} :recents []}])
               (rf/dispatch-sync [:dialog-open :models ""])
               (settle)))
      (.then (fn [_]
               (check! "it loads the named list and the recents itself"
                       ["choices" "recents"] (mapv first @sent))
               (check! "the editor starts CLOSED — picking is the front door, not editing"
                       nil (:choice-edit (sub)))
               (check! "and no leftover form is left on screen"
                       "" (:choice-model (sub)))
               (check! "the previous endpoint's model list is dropped with the dialog"
                       {} (:choice-catalogue (sub)))
               (check! "Settings is NOT a model dialog any more (it loads the list, nothing picks)"
                       ["choices"] (mapv first (filter #(= "choices" (first %)) @sent)))))

      ;; A NAMED pick is the whole point: the NAME is what the user chose, so the
      ;; spine resolves it and the pair it stands for is written — the transport
      ;; AND the id, together, through the same set-model every other pick uses.
      ;; (The old bug: a recent/repoint wrote the id and left the endpoint alone.)
      (.then (fn [_]
               (println "\npicking a NAMED model resolves the name and writes the pair:")
               (reset! sent [])
               (stub! {"pick-choice" #js {:name "workstation"
                                          :url "http://localhost:8081/v1"
                                          :model "qwen3-32b"}})
               (rf/dispatch-sync
                [:renderer-test/seed
                 {:home-project "grog" :active "s#1"
                  :sessions {"s#1" {:id "s#1" :project "grog" :input "" :attachments []}}
                  :dialog :models
                  :choices [{:name "workstation" :url "http://localhost:8081/v1"
                             :model "qwen3-32b" :provider "strata"}]}])
               (rf/dispatch-sync [:choice-pick "s#1" "workstation"])
               (settle)))
      (.then (fn [_]
               (check! "first the name is resolved by the spine, never in the client"
                       "pick-choice" (first (first @sent)))
               (check! "then the RESOLVED pair is written — url and model together"
                       ["set-model" {:id "s#1" :model "qwen3-32b" :global true
                                     :url "http://localhost:8081/v1"}]
                       (first (filter #(= "set-model" (first %)) @sent)))
               (check! "a dialog pick is machine-level, so it writes the default (global)"
                       true (get-in @sent [1 1 :global]))))

      ;; A name that cannot be picked — a row saved with no model id yet — must
      ;; explain itself IN THE FORM, since that is where the user is looking.
      (.then (fn [_]
               (println "\na name with no model id says why, in the dialog:")
               (reset! sent [])
               (stub! {"pick-choice" (js/Error. "grog knows \"strata\" but it has no model id — edit it, or start the server and press check")})
               (rf/dispatch-sync
                [:renderer-test/seed
                 {:home-project "grog" :active "s#1"
                  :sessions {"s#1" {:id "s#1" :project "grog" :input "" :attachments []}}
                  :dialog :models :choice-edit "strata"}])
               (rf/dispatch-sync [:choice-pick "s#1" "strata"])
               (settle)))
      (.then (fn [_]
               (check! "nothing was written" [] (filter #(= "set-model" (first %)) @sent))
               (check! "the reason is the spine's own sentence, in the form"
                       true (boolean (re-find #"no model id" (:choice-msg (sub)))))))

      ;; A recent is the escape hatch for a model nobody named, and it carries its
      ;; OWN url — picking it therefore switches the transport too.
      (.then (fn [_]
               (println "\na RECENT carries its model and its own endpoint:")
               (reset! sent [])
               (stub!)
               (rf/dispatch-sync
                [:renderer-test/seed
                 {:home-project "grog" :active "s#1"
                  :sessions {"s#1" {:id "s#1" :project "grog" :input "" :attachments []}}
                  :dialog :models}])
               (rf/dispatch-sync
                [:pick-url-model "s#1" "a-local-model" "http://localhost:8080/v1"])
               (settle)))
      (.then (fn [_]
               (check! "one set-model, carrying BOTH halves"
                       ["set-model" {:id "s#1" :model "a-local-model" :global true
                                     :url "http://localhost:8080/v1"}]
                       (first @sent))))

      ;; An empty pick used to do nothing at all, which read as a dead button.
      (.then (fn [_]
               (println "\nan empty pick is refused in words, not silence:")
               (reset! sent [])
               (rf/dispatch-sync
                [:renderer-test/seed
                 {:home-project "grog" :active "s#1"
                  :sessions {"s#1" {:id "s#1" :project "grog" :input "" :attachments []}}
                  :dialog :models}])
               (rf/dispatch-sync [:pick-url-model "s#1" "   " "http://localhost:8080/v1"])
               (settle)))
      (.then (fn [_]
               (check! "nothing was sent" [] @sent)
               (check! "and the form says what to do instead"
                       true (boolean (re-find #"no model picked" (:choice-msg (sub)))))))

      ;; SAVE: three fields, ONE write. The reported failure mode was a button
      ;; wired to only part of the form — so this asserts the whole payload.
      (.then (fn [_]
               (println "\nsaving a model writes name + url + model in ONE call:")
               (reset! sent [])
               (rf/dispatch-sync
                [:renderer-test/seed
                 {:home-project "grog" :active "s#1"
                  :sessions {"s#1" {:id "s#1" :project "grog" :input "" :attachments []}}
                  :dialog :models :choice-edit "new" :choice-name "Workstation"
                  :choice-url "http://localhost:8081/v1" :choice-model "qwen3-32b"}])
               (rf/dispatch-sync [:choice-save])
               (settle)))
      (.then (fn [_]
               (check! "exactly one write to the spine"
                       ["save-choice"] (mapv first (filter #(= "save-choice" (first %)) @sent)))
               (check! "with all three fields, port inside the url"
                       {:name "Workstation" :url "http://localhost:8081/v1"
                        :model "qwen3-32b"}
                       (second (first (filter #(= "save-choice" (first %)) @sent))))
               (check! "no half-write rides along (no separate provider/port call)"
                       [] (filter #(= "set-provider" (first %)) @sent))
               (check! "the form closed and the list re-read, so the file is what shows"
                       ["choices"] (mapv first (filter #(= "choices" (first %)) @sent)))
               (check! "the status line says how to USE it, not just that it saved"
                       true (boolean (re-find #"/model workstation"
                                              (:text (last (transcript))))))))

      ;; A name is the identity — without it there is nothing to pick by. Refused
      ;; client-side (no round trip), and the message sits in the form.
      (.then (fn [_]
               (println "\n saving with no name is refused, in the form:")
               (reset! sent [])
               (rf/dispatch-sync
                [:renderer-test/seed
                 {:home-project "grog" :active "s#1"
                  :sessions {"s#1" {:id "s#1" :project "grog" :input "" :attachments []}}
                  :dialog :models :choice-edit "new" :choice-name "  "
                  :choice-url "http://localhost:8081/v1" :choice-model "qwen3-32b"}])
               (rf/dispatch-sync [:choice-save])
               (settle)))
      (.then (fn [_]
               (check! "nothing was written" [] @sent)
               (check! "the reason names the field that is missing"
                       true (boolean (re-find #"NAME" (:choice-msg (sub)))))))

      ;; A validation failure from the spine (a bad URL, an id with a space in it)
      ;; must appear BESIDE THE FIELDS, and must not blow away the half-typed form
      ;; the user still has to fix.
      (.then (fn [_]
               (println "\na rejected save keeps the form and shows the spine's reason:")
               (reset! sent [])
               (stub! {"save-choice" (js/Error. "grog.edn: url must be an http(s) endpoint — got \"localhost:8081\"")})
               (rf/dispatch-sync
                [:renderer-test/seed
                 {:home-project "grog" :active "s#1"
                  :sessions {"s#1" {:id "s#1" :project "grog" :input "" :attachments []}}
                  :dialog :models :choice-edit "new" :choice-name "strata2"
                  :choice-url "localhost:8081" :choice-model "qwen3-32b"}])
               (rf/dispatch-sync [:choice-save])
               (settle)))
      (.then (fn [_]
               (check! "the spine's sentence is in the form"
                       true (boolean (re-find #"must be an http" (:choice-msg (sub)))))
               (check! "the form is still open" "new" (:choice-edit (sub)))
               (check! "and the half-typed url is untouched, ready to fix"
                       "localhost:8081" (:choice-url (sub)))))

      ;; EDIT: opening a row points the model list at THAT ROW'S endpoint. Seeing
      ;; some other server's ids under a row is how an id nobody serves gets saved.
      (.then (fn [_]
               (println "\nediting a row browses that row's OWN endpoint:")
               (reset! sent [])
               (rf/dispatch-sync
                [:renderer-test/seed
                 {:home-project "grog" :active "s#1"
                  :sessions {"s#1" {:id "s#1" :project "grog" :input "" :attachments []}}
                  :dialog :models
                  ;; the live transport is somewhere else entirely
                  :model-info {:url "http://other:9999/v1" :model "other-model"}
                  :choices [{:name "workstation" :url "http://localhost:8081/v1"
                             :model "qwen3-32b"}]
                  :choice-browse "http://other:9999/v1"}])
               (rf/dispatch-sync [:choice-edit "workstation"])
               (settle)))
      (.then (fn [_]
               (check! "the form holds the row's three fields"
                       ["workstation" "http://localhost:8081/v1" "qwen3-32b"]
                       ((juxt :choice-name :choice-url :choice-model) (sub)))
               (check! "the browse box is pointed at the ROW's url"
                       "http://localhost:8081/v1" (:choice-browse (sub)))
               (check! "and that endpoint was asked (not the one in force)"
                       [["models" {:url "http://localhost:8081/v1"}]] (filter #(= "models" (first %)) @sent))))

      ;; ADD from a baked-in endpoint: the template pre-fills, and everything it
      ;; fills is editable — a template, not a cage.
      (.then (fn [_]
               (println "\nstarting from a baked-in endpoint pre-fills an EDITABLE form:")
               (reset! sent [])
               (rf/dispatch-sync
                [:renderer-test/seed
                 {:home-project "grog" :active "s#1"
                  :sessions {"s#1" {:id "s#1" :project "grog" :input "" :attachments []}}
                  :dialog :models :templates [{:id "strata" :url "http://localhost:8080/v1"
                                               :sample "qwen3-32b"}]}])
               (rf/dispatch-sync [:choice-new {:template {:id "strata"
                                                          :url "http://localhost:8080/v1"
                                                          :sample "qwen3-32b"}}])
               (settle)))
      (.then (fn [_]
               (check! "it is a NEW row, so nothing existing is edited behind the user"
                       "new" (:choice-edit (sub)))
               (check! "the name, url and a sample id are pre-filled"
                       ["strata" "http://localhost:8080/v1" "qwen3-32b"]
                       ((juxt :choice-name :choice-url :choice-model) (sub)))
               (check! "the sample is a field, so clearing it is allowed"
                       "" (do (rf/dispatch-sync [:choice-field :model ""])
                              (:choice-model (sub))))
               (check! "nothing was WRITTEN — a template is not a pick"
                       [] (filter #(#{"save-choice" "set-model" "set-provider"} (first %)) @sent))
               (check! "but the endpoint WAS asked, so the id list is ready to click"
                       [["models" {:url "http://localhost:8080/v1"}]]
                       (filter #(= "models" (first %)) @sent))))

      ;; A per-endpoint list arrives on its own broadcast (the spine fetches a slow
      ;; endpoint in the background). It may only fill the box of the endpoint it
      ;; belongs to.
      (.then (fn [_]
               (println "\na models broadcast fills only its OWN endpoint's box:")
               (rf/dispatch-sync
                [:renderer-test/seed
                 {:home-project "grog" :active "s#1"
                  :sessions {"s#1" {:id "s#1" :project "grog" :input "" :attachments []}}
                  :dialog :models :choice-browse "http://localhost:8081/v1"
                  :choice-catalogue {"http://localhost:8081/v1" ["mine"]}}])
               (rf/dispatch-sync [:choice-catalogue-loaded
                                  #js {:url "http://elsewhere:1/v1" :models #js ["theirs"]}])
               (tick)))
      (.then (fn [_]
               (check! "this row's list is untouched"
                       ["mine"] (get-in (sub) [:choice-catalogue "http://localhost:8081/v1"]))
               (check! "and the other endpoint is kept under ITS OWN url"
                       ["theirs"] (get-in (sub) [:choice-catalogue "http://elsewhere:1/v1"]))))

      ;; Escape steps out ONE level: a form closes, the list stays. A user backing
      ;; out of a form must not lose the list they came from.
      (.then (fn [_]
               (println "\nEscape in the editor closes the editor, not the dialog:")
               (rf/dispatch-sync
                [:renderer-test/seed
                 {:home-project "grog" :active "s#1"
                  :sessions {"s#1" {:id "s#1" :project "grog" :input "" :attachments []}}
                  :dialog :models :choice-edit "workstation"}])
               (rf/dispatch-sync [:key-escape])
               (tick)))
      (.then (fn [_]
               (check! "the form is gone" nil (:choice-edit (sub)))
               (check! "the dialog is still open" :models (:dialog (sub)))
               (rf/dispatch-sync [:key-escape])
               (tick)))
      (.then (fn [_]
               (check! "a second Escape closes the dialog" nil (:dialog (sub)))
               (check! "and no stale error is left for the next visit"
                       nil (:choice-msg (sub)))))

      ;; The status bar carries the NAME, not just the id — that is what makes a
      ;; named model feel like the thing you are running.
      ;; THE PRIMARY PATH, and the one the request was actually about: a model the
      ;; shipped catalogue has never heard of (a gateway, a relay, a server on a
      ;; port nobody knows) has to be TYPEABLE, not picked from a list. A blank
      ;; form, NAME FIRST, and nothing fetched — there is no endpoint to ask yet.
      (.then (fn [_]
               (println "\nadding YOUR OWN model opens a blank form, not a catalogue entry:")
               (reset! sent [])
               (rf/dispatch-sync
                [:renderer-test/seed
                 {:home-project "grog" :active "s#1"
                  :sessions {"s#1" {:id "s#1" :project "grog" :input "" :attachments []}}
                  :dialog :models :templates [{:id "strata" :url "http://localhost:8080/v1"}]}])
               (rf/dispatch-sync [:choice-new {}])
               (settle)))
      (.then (fn [_]
               (check! "a NEW row, blank in all three fields"
                       ["new" "" "" ""]
                       ((juxt :choice-edit :choice-name :choice-url :choice-model) (sub)))
               (check! "nothing was fetched or written — there is no endpoint to ask yet"
                       [] @sent)
               (check! "and typing a URL then asking it is the next step, and it browses THAT url"
                       "http://gateway.example/v1"
                       (do (rf/dispatch-sync [:choice-field :url "http://gateway.example/v1"])
                           (rf/dispatch-sync [:choice-catalogue-fetch "http://gateway.example/v1"])
                           (:choice-browse (sub))))
               (check! "…asked once, for the typed url"
                       [["models" {:url "http://gateway.example/v1"}]]
                       (filter #(= "models" (first %)) @sent))))

      (.then (fn [_]
               (println "\nthe status bar shows the NAME the session runs:")
               (rf/dispatch-sync
                [:renderer-test/seed
                 {:home-project "grog" :active "s#1"
                  :sessions {"s#1" {:id "s#1" :project "grog" :input "" :attachments []
                                    :model "qwen3-32b" :url "http://localhost:8081/v1"
                                    :model-choice "workstation"}}}])
               (tick)))
      (.then (fn [_]
               (check! "the session carries the choice it came from"
                       "workstation" (get-in (sub) [:sessions "s#1" :model-choice]))))
      (.then (fn [_]
               (println)
               (if (seq @failures)
                 (do (println (str "renderer-test: FAILED — " (count @failures) " check(s):"))
                     (doseq [f @failures] (println "  -" f))
                     (.exit js/process 1))
                 (do (println "renderer-test: all checks passed") (.exit js/process 0)))))))
