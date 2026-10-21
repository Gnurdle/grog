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
  stub that records calls is enough to observe what was sent."
  []
  (set! (.-window js/globalThis)
        #js {:grogAPI #js {:call (fn [method payload]
                                   (swap! sent conj [method (js->clj payload :keywordize-keys true)])
                                   (js/Promise.resolve nil))}
             :localStorage #js {:getItem (fn [_] nil) :setItem (fn [_ _] nil)}}))

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
                       [:user :answer] (mapv :kind (transcript)))))

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

      (.then (fn [_]
               (println)
               (if (seq @failures)
                 (do (println (str "renderer-test: FAILED — " (count @failures) " check(s):"))
                     (doseq [f @failures] (println "  -" f))
                     (.exit js/process 1))
                 (do (println "renderer-test: all checks passed") (.exit js/process 0)))))))
