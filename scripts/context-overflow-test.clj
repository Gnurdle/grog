#!/usr/bin/env bb
;; An overflowing context must DISTILL AND RETRY, never kill the thread.
;;
;;   clojure -M scripts/context-overflow-test.clj
;;
;; Why this test exists. On 2026-10-28 the report was operational, not cosmetic:
;;   "It is operationally intolerable to have the thread get DESTROYED by
;;    overflowing context with no recovery."
;;
;; It was true, and it was three defects in a line:
;;   1. the budget was a GLOBAL CONSTANT (`:llm :max-context-tokens`, default
;;      200000) — right for no model in particular;
;;   2. the estimate counted only `:content`, so tool schemas and `tool_calls`
;;      were invisible — the check could pass on a request the server refuses;
;;   3. a real 400 raised straight out of the loop, killed the turn, and left the
;;      oversized history in place. The NEXT turn re-sent it and died the same
;;      way, so one overflow cost the conversation.
;;
;; What this file proves, with no network and no model:
;;   A. `grog.agent.context` — the estimate counts schemas; compaction keeps the
;;      task + tail and NAMES the gap; distillation gets strictly smaller down the
;;      ladder and always leaves a conversation;
;;   B. `parse-overflow` recognises the real wire messages and extracts the
;;      provider's own ceiling WITHOUT mistaking the prompt size for it, and never
;;      calls a 401 an overflow;
;;   C. the LOOP, driven by a `:post` fixture that refuses the first request(s)
;;      exactly as llama.cpp does: the turn SUCCEEDS after recovery, the second
;;      request is smaller, the window is learned, and the caller's `:on-overflow`
;;      fires — the thread survives;
;;   D. recovery is BOUNDED: a provider that refuses everything yields ONE
;;      exception that says what happened and what to do, not an infinite loop;
;;   E. a non-overflow HTTP failure is still thrown on the first attempt (a 401
;;      must not be mistaken for a size problem and retried).
(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[grog.agent.context :as ctx]
         '[grog.agent.loop :as loop])

(def failures (atom 0))
(defn- check!
  ([id ok?] (check! id ok? nil))
  ([id ok? detail]
   (println (str (if ok? "  PASS  " "  FAIL  ") id (when detail (str "  — " detail))))
   (when-not ok? (swap! failures inc))))

(def ^:private tmp (System/getProperty "java.io.tmpdir"))

;; --- A. accounting, compaction, distillation ---------------------------------
(println "=== A. the estimate, compaction and distillation (pure) ===")

(defn- big-msgs [n chars]
  (vec (cons {:role "system" :content "SYSTEM PROMPT"}
             (concat [{:role "user" :content "THE ORIGINAL TASK: fix the thing"}]
                     (map-indexed (fn [i _] {:role (if (even? i) "assistant" "tool")
                                             :content (apply str (repeat chars "x"))})
                                  (range n))))))

(let [msgs (big-msgs 30 4000)
      no-schema (ctx/est-tokens msgs)
      with-schema (ctx/est-tokens msgs [{:type "function" :function {:name "shell"}}])]
  (check! "tool schemas are COUNTED — they ride every request and cannot be elided"
          (> with-schema no-schema) (str no-schema " without, " with-schema " with"))
  (check! "tool_calls are counted too, not just :content"
          (> (ctx/est-tokens [{:role "assistant" :content "x"
                               :tool_calls [{:function {:arguments (apply str (repeat 4000 "y"))}}]}])
             (ctx/est-tokens [{:role "assistant" :content "x"}]))))

(let [msgs (big-msgs 30 4000)
      compacted (ctx/compact msgs 5000)]
  (check! "compaction gets under the budget" (< (ctx/est-tokens compacted) 5000)
          (str (ctx/est-tokens compacted)))
  (check! "…keeps the system prompt" (= "SYSTEM PROMPT" (:content (first compacted))))
  (check! "…keeps the ORIGINAL TASK (the reason the turn exists)"
          (some #(str/includes? (str (:content %)) "THE ORIGINAL TASK") compacted))
  (check! "…and NAMES what it dropped (a silent splice makes a model assert half a story)"
          (some #(str/includes? (str (:content %)) "elided to fit") compacted))
  (check! "a nil budget means NO trim (unknown window = do not guess)"
          (= msgs (ctx/compact msgs nil)))
  (check! "a conversation already inside budget is returned untouched"
          (= msgs (ctx/compact msgs 10000000))))

(let [msgs (big-msgs 40 5000)
      once (ctx/distill msgs {})
      twice (ctx/distill once {})
      fitted (ctx/distill msgs {:budget 4000})]
  (check! "distillation is STRICTLY smaller than its input"
          (< (ctx/est-tokens once) (ctx/est-tokens msgs))
          (str (ctx/est-tokens msgs) " → " (ctx/est-tokens once)))
  (check! "…and idempotent enough that a second pass does not grow it"
          (<= (ctx/est-tokens twice) (ctx/est-tokens once)))
  (check! "a target budget is met by walking the ladder"
          (< (ctx/est-tokens fitted) 4000) (str (ctx/est-tokens fitted)))
  (check! "distillation ALWAYS leaves a conversation — system + task + something"
          (and (= "SYSTEM PROMPT" (:content (first fitted)))
               (some #(str/includes? (str (:content %)) "THE ORIGINAL TASK") fitted)))
  (check! "long tool results are CLIPPED with a marker, not silently cut"
          (some #(str/includes? (str (:content %)) "truncated") fitted))
  (check! "an image block is dropped (base64 is the first thing to go)"
          (let [d (ctx/distill [{:role "user"
                                 :content [{:type "text" :text "look at this"}
                                           {:type "image_url" :image_url {:url "data:image/png;base64,AAAA"}}]}]
                                {:max-chars 100})]
            (not (str/includes? (pr-str d) "base64")))))

;; --- B. reading the provider's refusal ---------------------------------------
(println)
(println "=== B. what the provider says, and the ceiling it names ===")

(def strata-body
  "{\"error\":{\"message\":\"invalid request: request exceeds the context window: prompt (38683 tokens) leaves no room to answer in the context (32768); requests are never truncated\"}}")

(let [o (ctx/parse-overflow 400 strata-body)]
  (check! "the reported Strata body IS an overflow" (true? (:overflow? o)) (pr-str o))
  (check! "…and the ceiling it names is the WINDOW, not the prompt"
          (= 32768 (:context-limit o)) (str (:context-limit o)))
  (check! "…while the prompt size is kept separate (evidence, not the limit)"
          (= 38683 (:prompt-tokens o)) (str (:prompt-tokens o))))

(check! "OpenAI's 'maximum context length is N tokens' is read"
        (= 128000 (:context-limit (ctx/parse-overflow 400
                                                      "This model's maximum context length is 128000 tokens. However, you requested 200000 tokens."))))

(check! "Anthropic's 'prompt is too long' is an overflow"
        (true? (:overflow? (ctx/parse-overflow 400 "prompt is too long: 210000 tokens > 200000 maximum"))))

(check! "a 413 (payload too large) mentioning context is an overflow"
        (true? (:overflow? (ctx/parse-overflow 413 "context length exceeded"))))

(check! "a 401 is NEVER an overflow, whatever the body says"
        (nil? (ctx/parse-overflow 401 "invalid api key (context window docs at …)"))
        "mislabelling a credential error as 'too big' would be a new lie")

(check! "a 500 carrying the word 'context' is not ours to call an overflow"
        (nil? (ctx/parse-overflow 500 "internal error in context handler")))

(check! "an ordinary format 400 is not an overflow"
        (nil? (ctx/parse-overflow 400 "tool schema: missing required field 'name'")))

(check! "it is TOTAL: nil/garbage never throws"
        (and (nil? (ctx/parse-overflow nil nil))
             (nil? (ctx/parse-overflow 400 nil))
             (nil? (ctx/parse-overflow "400" "hello"))))

;; --- C. the LOOP recovers ----------------------------------------------------
(println)
(println "=== C. the real loop: overflow → distill → re-send → the turn lives ===")

(defn- sse [frames]
  (java.io.ByteArrayInputStream.
   (.getBytes (str (str/join "\n" (map #(str "data: " (json/generate-string %)) frames))
                   "\ndata: [DONE]\n")
              "UTF-8")))
(defn- delta [d] {:choices [{:delta d}]})
(defn- answer [text] (sse [(delta {:content text})]))

(defn- refusing
  "A `:post` that REFUSES the first `n` requests as a context overflow (the real
  Strata wording, naming `limit` tokens), then answers `text`. Records the size of
  every request it was handed, so the test can prove the retry was SMALLER."
  [n limit text !sizes !bodies]
  (fn [_url opts]
    (let [body (:body opts)
          parsed (json/parse-string body true)
          i (count @!sizes)]
      (swap! !sizes conj (count body))
      (swap! !bodies conj (:messages parsed))
      (if (< i n)
        {:status 400
         :body (java.io.ByteArrayInputStream.
                (.getBytes (json/generate-string
                            {:error {:message (str "invalid request: request exceeds the context window: "
                                                   "prompt (990000 tokens) leaves no room to answer in "
                                                   "the context (" limit "); requests are never truncated")}})
                           "UTF-8"))}
        {:status 200 :body (answer text)}))))

(let [!sizes (atom []) !bodies (atom []) !overflow (atom [])
      res (loop/run-turn!
           {:llm {:url "http://fixture/v1" :model "fixture"
                  :post (refusing 1 32768 "recovered after distilling" !sizes !bodies)}
            :message "hello" :cwd tmp :system "test"
            :on-overflow (fn [info] (swap! !overflow conj info))})]
  (check! "a turn whose first request overflows still SUCCEEDS"
          (= :done (:status res)) (pr-str (:status res)))
  (check! "…with the answer from the recovered request"
          (= "recovered after distilling" (:text res)) (pr-str (:text res))))

(let [!sizes (atom []) !bodies (atom []) !overflow (atom [])
      res (loop/run-turn!
           {:llm {:url "http://fixture/v1" :model "fixture"
                  :post (refusing 1 32768 "ok" !sizes !bodies)}
            :message "fix the thing"
            :history [{:role "assistant" :content "working"}
                      {:role "tool" :tool_call_id "1" :content (apply str (repeat 60000 "z"))}]
            :cwd tmp :system "test"
            :on-overflow (fn [info] (swap! !overflow conj info))})]
  (check! "the request was re-sent (2 attempts)" (= 2 (count @!sizes)) (str (count @!sizes)))
  (check! "the SECOND request is strictly SMALLER than the refused one"
          (< (second @!sizes) (first @!sizes))
          (str (first @!sizes) " → " (second @!sizes) " chars"))
  (check! "the caller was TOLD (so the window can be persisted)"
          (and (= 1 (count @!overflow))
               (= 32768 (:limit (first @!overflow))))
          (pr-str @!overflow))
  (check! "the turn's messages are a CONVERSATION (system kept, nothing empty)"
          (and (= "test" (:content (first (:messages res))))
               (> (count (:messages res)) 1)))
)

(let [!segs (atom [])]
  (loop/run-turn!
   {:llm {:url "http://fixture/v1" :model "fixture"
          :post (refusing 1 32768 "ok" (atom []) (atom []))}
    :message "hello" :cwd tmp :system "test"
    :on-event (fn [seg] (swap! !segs conj seg))})
  (check! "…and it really is emitted, naming the overflow"
          (some #(str/includes? (str (:text %)) "context overflow") @!segs)
          (pr-str (map :text (filter #(= :tool (:kind %)) @!segs)))))

;; --- D. bounded ---------------------------------------------------------------
(println)
(println "=== D. a model that refuses EVERYTHING is reported, not looped on ===")

(defn- err-stream
  "An SSE-shaped error body, as the transport reads it (a slurpable stream)."
  [message]
  (java.io.ByteArrayInputStream.
   (.getBytes (json/generate-string {:error {:message message}}) "UTF-8")))

(defn- always-overflow
  "A `:post` that refuses EVERY request as a context overflow naming `limit`."
  [limit !attempts]
  (fn [_url _opts]
    (swap! !attempts inc)
    {:status 400
     :body (err-stream (str "request exceeds the context window (" limit ")"))}))

(defn- always-status
  "A `:post` that always answers `status` with `message`."
  [status message !attempts]
  (fn [_url _opts]
    (swap! !attempts inc)
    {:status status :body (err-stream message)}))

(let [!attempts (atom 0)
      e (try
          (loop/run-turn! {:llm {:url "http://fixture/v1" :model "fixture"
                                 :post (always-overflow 100 !attempts)}
                           :message "hello" :cwd tmp :system "test"
                           :history [{:role "user" :content (apply str (repeat 50000 "q"))}]})
          nil
          (catch Throwable e e))]
  (check! "an exception IS thrown once recovery is exhausted" (some? e))
  (check! "…it is marked as an overflow" (true? (:overflow? (ex-data e))) (pr-str (ex-data e)))
  (check! "…and its message says what happened and what to do"
          (and (str/includes? (str (.getMessage e)) "context overflow")
               (str/includes? (str (.getMessage e)) "/clear"))
          (str (.getMessage e)))
  (check! "…after a BOUNDED number of attempts (not an infinite loop)"
          (= 3 @!attempts) (str @!attempts " attempt(s)")))

;; --- E. a non-overflow HTTP failure is not retried ----------------------------
(println)
(println "=== E. a 401 is still a 401, on the first attempt ===")

(let [!attempts (atom 0)
      e (try
          (loop/run-turn! {:llm {:url "http://fixture/v1" :model "fixture"
                                 :post (always-status 401 "invalid api key" !attempts)}
                           :message "hello" :cwd tmp :system "test"})
          nil
          (catch Throwable e e))]
  (check! "the 401 is thrown" (some? e))
  (check! "…once — a re-sent 401 is the same 401, only later"
          (= 1 @!attempts) (str @!attempts " attempt(s)"))
  (check! "…and it is NOT labelled an overflow" (nil? (:overflow? (ex-data e)))
          (pr-str (select-keys (ex-data e) [:status :overflow?]))))

(println)
(if (zero? @failures)
  (do (println "context-overflow-test: all checks passed") (System/exit 0))
  (do (println (str "context-overflow-test: " @failures " FAILED")) (System/exit 1)))
