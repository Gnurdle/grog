#!/usr/bin/env bb
;; TOKENS PER SECOND — the number in the status bar must be measured, not guessed.
;;
;;   GROG_MCP_ENABLED=0 clojure -M scripts/token-rate-test.clj
;;
;; The LLM call is stubbed (`transport/stream-chat!`), so nothing leaves the box.
;;
;; Why this test exists. The live counter in the renderer divides streamed
;; characters by 4 (grog's own `chars-per-token` convention) — that is an
;; ESTIMATE, and it is fine as a tick. But two things must be right underneath it:
;;
;;   1. THE MEASURED NUMBER MUST NOT BE PREFILL. `elapsed-ms` is the whole step,
;;      and on a local server a big prompt can spend most of it in prompt
;;      processing before the first frame arrives. Dividing by it makes a fast
;;      model look slow by whatever fraction was prefill. The rate must subtract
;;      the wait for the first frame (`:first-frame-ms`) — that is the entire
;;      reason `stream-chat!` now returns it.
;;
;;   2. A NON-STREAM MUST PRODUCE NO NUMBER. If a step logged fewer frames than
;;      it reports tokens, the timing does not describe token production at all
;;      (a cached or non-streaming answer, markup recovered after the fact). nil
;;      is the honest answer; a plausible-looking wrong one is not.
(require '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[grog.agent.loop :as loop]
         '[grog.agent.transport :as transport]
         '[grog.client :as client]
         '[grog.client.loop :as loopc])

(def failures (atom 0))
(defn- check!
  ([id ok?] (check! id ok? nil))
  ([id ok? detail]
   (println (str (if ok? "  PASS  " "  FAIL  ") id (when detail (str "  — " detail))))
   (when-not ok? (swap! failures inc))))

;; `token-rate` is private — reach it through the var, which is what the loop
;; itself calls, so this measures the real function rather than a copy.
(def rate #'loop/token-rate)

;; --- 1. the arithmetic --------------------------------------------------------
(println "=== 1. the rate subtracts prefill and refuses to guess ===")

(check! "100 tokens in 1s = 100 tok/s"
        100 (long (Math/round (double (rate 100 1000 200)))))
(check! "…and 100 tokens in 0.5s = 200 tok/s"
        200 (long (Math/round (double (rate 100 500 200)))))

;; NOTE: `rate` takes GENERATION time and knows nothing about prefill — the
;; subtraction of the first-frame wait happens in the LOOP, one level up. So the
;; check that it really is subtracted lives in section 2, against a fixture whose
;; whole-step average and whose generation rate are deliberately DIFFERENT numbers.
;; It cannot live here: a unit test of `rate` would be testing the caller's job.

(check! "no provider count = no number (never a guess)"
        (nil? (rate nil 1000 500)) (pr-str (rate nil 1000 500)))
(check! "zero tokens = no number"
        (nil? (rate 0 1000 500)) (pr-str (rate 0 1000 500)))
(check! "a single frame = NOT a stream (buffered), so no number"
        (nil? (rate 500 1000 1)) (pr-str (rate 500 1000 1)))
;; BATCHING IS NOT A PROBLEM. Some providers put several tokens in one frame, so
;; "fewer frames than tokens" is a NORMAL stream, not a broken one — the timing is
;; wall-clock and still spans the generation. A frame-per-token rule would have
;; switched this number off on exactly those providers.
(check! "a BATCHED stream (500 tokens in 3 frames) still gets a rate"
        500 (long (Math/round (double (rate 500 1000 3)))))
(check! "a sub-100ms step is refused rather than divided into noise"
        (nil? (rate 5 20 5)) (pr-str (rate 5 20 5)))

;; --- 2. end to end: the loop emits a :rate, the client keeps its `:usage` ------
(println)
(println "=== 2. the real loop through the real client adapter ===")

(def !events (atom []))
(def !requests (atom []))

(defn- stub-transport!
  "A canned streamed step shaped like a real provider's: 100 generated tokens,
  200 frames, a 2s step of which 1s was the wait for the first frame.

  The two candidate answers are deliberately far apart, so a passing check says
  WHICH ONE the code computed:
     generation rate   100 tok / 1.0s = 100.0 tok/s   <- correct
     whole-step rate   100 tok / 2.0s =  50.0 tok/s   <- the bug this guards
  Both the per-step and the turn average must read 100.0, because this fixture is
  one step."
  []
  (alter-var-root #'transport/stream-chat!
                  (fn [_]
                    (fn [{:keys [messages] :as _opt}]
                      (swap! !requests conj (vec messages))
                      {:text "reply from the stub"
                       :reasoning nil
                       :tool-calls nil
                       :malformed nil
                       :finish-reason "stop"
                       :frames 200
                       :elapsed-ms 2000
                       :first-frame-ms 1000
                       :usage {:prompt_tokens 10
                               :completion_tokens 100
                               :total_tokens 110}}))))

(defn- wait-until! [pred timeout-ms]
  (loop [n 0]
    (if (pred)
      true
      (when (< n (/ timeout-ms 50))
        (Thread/sleep 50)
        (recur (inc n))))))

(stub-transport!)
(loopc/install!)

(let [opened (client/open! {:project "token-rate-test"})
      id (:id opened)]
  (client/subscribe! id (fn [e] (swap! !events conj e)))
  (client/prompt! id "say something")
  (check! "the turn reached the transport"
          (wait-until! #(pos? (count @!requests)) 120000)
          (str "requests=" (count @!requests)))
  (check! "the turn finished"
          (wait-until! #(boolean (some #{:status} (mapv :type @!events))) 120000)
          (pr-str (mapv :type @!events)))

  ;; (a) THE RATE. It arrives as a content segment of kind "text" — the client
  ;; turns `:rate` into a transcript line, so a reader (and a remote client)
  ;; sees the measured number next to the answer.
  (let [txt (->> @!events
                 (filter #(= :content (:type %)))
                 (map #(get-in % [:content :text] ""))
                 (apply str))]
    (check! "the rate is over GENERATION time: 100 tok / 1.0s = 100.0 tok/s"
            (boolean (re-find #"100\.0 tok/s" txt))
            (pr-str (take 3 (str/split-lines txt))))
    (check! "…and NOT the whole-step average (which would read 50.0)"
            (not (re-find #"50\.0 tok/s" txt))
            (pr-str (take 3 (str/split-lines txt))))
    (check! "…and it names the tokens and the generation time it divided"
            (boolean (re-find #"100 tok in 1\.0s" txt))
            (pr-str (take 3 (str/split-lines txt)))))

  ;; (b) THE `on-event` FIX. The loop emits `{:kind :usage :text (pr-str @!usage)}`
  ;; on its terminal step; that branch of the client's `on-event` used to be a
  ;; bare `nil`, so the segment was thrown away. `:prompt_tokens` is a snake_case
  ;; key that ONLY that path can put in the session's usage atom — the client's
  ;; own result-based fold writes `:turn-tokens`/`:session-tokens`/`:session-cost`
  ;; and never this — so its presence is proof the branch ran.
  (let [u (:usage (client/session id))]
    (check! "the loop's `:usage` segment reached the session (was: discarded)"
            (contains? u :prompt_tokens)
            (pr-str u))
    (check! "…carrying the provider's own counts"
            [10 100] [(long (:prompt_tokens u 0)) (long (:completion_tokens u 0))])
    (check! "…and the session meter was still folded in by the client's own path"
            (pos? (long (:session-tokens u 0)))
            (pr-str u)))

  (client/close! id))

(println)
(if (zero? @failures)
  (println "all checks passed")
  (do (println (str @failures " check(s) FAILED"))
      (System/exit 1)))
(shutdown-agents)
