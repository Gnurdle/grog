#!/usr/bin/env bb
;; Stop conditions — the step cap and the repetition guard. No model, no network.
;;
;;   clojure -M scripts/stop-condition-test.clj
;;
;; Why this test exists: the loop shipped a hard-coded `:max-steps 12` that the
;; client COERCED an unset config into (`(or (config/chat-tool-loop-limit) 12)`),
;; so the documented "unlimited by default" (README.md:87, the docstring of
;; `config/chat-tool-loop-limit`) was silently a 12-step ceiling and long tasks
;; died mid-flight. Fixing that means "uncapped" must be TRUE, and the thing a cap
;; actually protects against — a model repeating one call forever — must be caught
;; directly instead.
;;
;; What it proves, in order:
;;   1. UNCAPPED is real: a turn that keeps calling tools runs well PAST 12 steps
;;      when `:max-steps` is nil, and stops on its own when the model answers.
;;   2. A CAP is still honoured: `:max-steps 3` ends a looping turn at step 3 with
;;      :max-steps, so the escape hatch was not removed, only de-defaulted.
;;   3. THE GUARD: an identical call is warned on the 2nd occurrence and REFUSED
;;      (never executed) on the 3rd — including through key reordering, which must
;;      NOT look like a different call.
;;
;; The fixture transport is the seam from `grog.agent.transport/stream-chat!`
;; (`:post`), the same one `scripts/tool-search-test.clj` uses.
(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[grog.agent.loop :as loop]
         '[grog.agent.mcp :as mcp])

(def failures (atom 0))
(defn- check!
  ([id ok?] (check! id ok? nil))
  ([id ok? detail]
   (println (str (if ok? "  PASS  " "  FAIL  ") id (when detail (str "  — " detail))))
   (when-not ok? (swap! failures inc))))

(defn- sse [frames]
  (java.io.ByteArrayInputStream.
   (.getBytes (str (str/join "\n" (map #(str "data: " (json/generate-string %)) frames))
                   "\ndata: [DONE]\n")
              "UTF-8")))
(defn- delta [d] {:choices [{:delta d}]})
(defn- tool-chunk [idx id name args]
  {:choices [{:delta {:tool_calls [{:index idx :id id :function {:name name :arguments args}}]}}]})

(defn- scripted-post
  "A `:post` that walks a vector of THUNKS, one per step, each returning a fresh
  SSE stream (an InputStream is consumed by the first read, so a step that is
  retried must not hand back the same exhausted stream), recording the number of
  model calls made."
  [steps !calls]
  (fn [_url _opt]
    (let [i @!calls
          make (nth steps i (last steps))]
      (swap! !calls inc)
      {:status 200 :body (make)})))

(def ^:private tmp (System/getProperty "java.io.tmpdir"))

;; --- 1. UNCAPPED is real ------------------------------------------------------
(println "=== 1. uncapped: a long turn runs PAST 12 steps ===")

;; 20 steps of a real tool call, each with DIFFERENT arguments (so the repetition
;; guard is silent by construction — this section tests the cap, not the guard),
;; then an answer.
(def long-run
  (vec (concat (for [i (range 20)]
                 (fn [] (sse [(delta {:content (str "step " i)})
                              (tool-chunk 0 (str "c" i) "list_dir"
                                          (json/generate-string {:path (str tmp "/" i)}))])))
               [(fn [] (sse [(delta {:content "all done"})]))])))

(let [!calls (atom 0)
      res (loop/run-turn! {:llm {:url "http://fixture/v1" :model "fixture"
                                 :post (scripted-post long-run !calls)}
                           :message "keep going" :cwd tmp :system "test"
                           ;; NO :max-steps — the default must be no cap
                           :local-handlers {"list_dir" (fn [_] {:text "a b c" :error? false})}})]
  (check! "an uncapped turn runs past the old 12-step ceiling"
          (> (long (:steps res)) 12) (str "steps=" (:steps res)))
  (check! "and it ends by itself when the model answers"
          (= :done (:status res)) (pr-str (:status res)))
  (check! "it took the full 21 model calls (20 tools + answer)"
          (= 21 (long @!calls)) (str @!calls " post(s)")))

(println)

;; --- 2. a cap is still honoured ----------------------------------------------
(println "=== 2. capped: :max-steps still stops a turn ===")

(let [;; the model NEVER answers — forever tools. Only the cap can end this.
      forever [(fn [] (sse [(delta {:content ""})
                            (tool-chunk 0 "cx" "list_dir" (json/generate-string {:path tmp}))]))]
      !calls (atom 0)
      segs (atom [])
      res (loop/run-turn! {:llm {:url "http://fixture/v1" :model "fixture"
                                 :post (scripted-post forever !calls)}
                           :message "go" :cwd tmp :system "test"
                           :max-steps 3
                           :on-event (fn [s] (swap! segs conj s))
                           :local-handlers {"list_dir" (fn [_] {:text "x" :error? false})}})]
  (check! ":max-steps 3 ends the turn with :max-steps"
          (= :max-steps (:status res)) (pr-str (:status res)))
  (check! "at exactly 3 steps" (= 3 (long (:steps res))) (str "steps=" (:steps res)))
  (check! "the cap is VISIBLE in the event stream, saying it was hit"
          (some #(and (= :tool (:kind %)) (str/includes? (str (:text %)) "max-steps"))
                @segs)))

(println)

;; --- 3. the repetition guard --------------------------------------------------
(println "=== 3. the guard: identical call warns twice, refuses the third ===")

;; The model makes the SAME call three times. The 2nd must warn (and run); the 3rd
;; must be REFUSED (and must NOT reach the tool).
(let [calls (atom [])                                   ; how many times the TOOL ran
      !calls (atom 0)
      same (json/generate-string {:path tmp :limit 5})
      streams [(fn [] (sse [(delta {:content ""}) (tool-chunk 0 "a" "list_dir" same)]))
               (fn [] (sse [(delta {:content ""}) (tool-chunk 0 "b" "list_dir" same)]))
               ;; 3rd: SAME call, but keys REORDERED — must still be recognised
               (fn [] (sse [(delta {:content ""}) (tool-chunk 0 "c" "list_dir"
                                                             (json/generate-string {:limit 5 :path tmp}))]))
               (fn [] (sse [(delta {:content "stopping"})]))]
      segs (atom [])
      res (loop/run-turn! {:llm {:url "http://fixture/v1" :model "fixture"
                                 :post (scripted-post streams !calls)}
                           :message "loop" :cwd tmp :system "test"
                           :on-event (fn [s] (swap! segs conj s))
                           :local-handlers {"list_dir" (fn [_]
                                                         (swap! calls conj 1)
                                                         {:text "entry" :error? false})}})]
  (check! "the tool ran for calls 1 and 2, NOT for the 3rd"
          (= 2 (count @calls)) (str (count @calls) " run(s)"))
  (check! "the 2nd identical call WARNED in the event stream"
          (some #(and (= :tool (:kind %)) (str/includes? (str (:text %)) "repeated call")) @segs))
  (check! "the refusal text reached the model as a tool result"
          (some (fn [m] (and (= "tool" (:role m))
                             (str/includes? (str (:content m)) "REFUSED")))
                (:messages res))
          (pr-str (map #(str (:role %) ": " (subs (str (:content %)) 0 (min 60 (count (str (:content %))))))
                       (filter #(= "tool" (:role %)) (:messages res)))))
  (check! "the turn finished normally after the refusal"
          (= :done (:status res)) (pr-str (:status res))))

(println)
(println (if (zero? @failures)
           "=== ALL PASS"
           (str "=== " @failures " FAILURE(S)")))
