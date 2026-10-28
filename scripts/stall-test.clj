#!/usr/bin/env bb
;; Stalled LLM streams — retry before data, diagnose after it. No model, no network.
;;
;;   clojure -M scripts/stall-test.clj
;;
;; Why this test exists: on 2026-10-22 a turn died and the ONLY thing the user
;; could report was the seven words the client rendered —
;;     [grog] loop error: Read timed out
;; — no endpoint, no model, no elapsed time, no way forward. The cause was a
;; `java.net.SocketTimeoutException` escaping `grog.agent.transport/stream-chat!`
;; uncaught and unretried, from a read timeout that was hard-coded to 300s.
;;
;; What it proves, in order:
;;   1. RETRY-BEFORE-DATA: an attempt that receives NOTHING is re-sent, and the
;;      turn SUCCEEDS on the later attempt — a transient stall is now survivable.
;;   2. NO RETRY AFTER DATA: an attempt that stalls MID-ANSWER is not re-sent
;;      (that would replay text the user already watched stream in); the turn
;;      fails ONCE, and the partial answer is carried out on the exception.
;;   3. THE MESSAGE: the failure names the endpoint, the model, the number of
;;      attempts and the knob to raise — instead of "Read timed out".
;;   4. RETRIES ARE BOUNDED: with `:stream-retries 0` a stall fails immediately;
;;      with 2 it tries exactly three times. A dead provider must not become an
;;      infinite loop, and must not silently multiply the 300s wait forever.
;;   5. NOT EVERYTHING IS RETRIED: an HTTP 400 is thrown on the FIRST attempt —
;;      a re-sent 400 is the same 400, only later.
(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[grog.agent.loop :as loop])

(def failures (atom 0))
(defn- check!
  ([id ok?] (check! id ok? nil))
  ([id ok? detail]
   (println (str (if ok? "  PASS  " "  FAIL  ") id (when detail (str "  — " detail))))
   (when-not ok? (swap! failures inc))))

(def ^:private tmp (System/getProperty "java.io.tmpdir"))

(defn- sse [frames]
  (java.io.ByteArrayInputStream.
   (.getBytes (str (str/join "\n" (map #(str "data: " (json/generate-string %)) frames))
                   "\ndata: [DONE]\n")
              "UTF-8")))
(defn- delta [d] {:choices [{:delta d}]})

(defn- timed-out [] (java.net.SocketTimeoutException. "Read timed out"))

(defn- posting
  "A `:post` that walks a vector of THUNKS, one per ATTEMPT. Each thunk either
  throws (the stall) or returns a fresh SSE stream. `!attempts` counts them, so a
  test can assert HOW MANY times the request was actually re-sent."
  [steps !attempts]
  (fn [_url _opt]
    (let [i @!attempts
          make (nth steps i (last steps))]
      (swap! !attempts inc)
      {:status 200 :body (make)})))

(defn- stalling
  "A `:post` that throws a read timeout for the first `n` attempts, then answers
  with `text`."
  [n text !attempts]
  (posting (vec (concat (repeat n (fn [] (throw (timed-out))))
                        [(fn [] (sse [(delta {:content text})]))]))
           !attempts))

;; --- 1. retry BEFORE any data -------------------------------------------------
(println "=== 1. a stall that received NOTHING is retried, and the turn survives ===")

(let [!attempts (atom 0)
      retries (atom [])
      res (loop/run-turn! {:llm {:url "http://fixture/v1" :model "fixture"
                                 :post (stalling 2 "recovered on the third try" !attempts)
                                 :stream-retries 2
                                 :on-retry (fn [info] (swap! retries conj info))}
                           :message "hello" :cwd tmp :system "test"})]
  (check! "the turn SUCCEEDED after two stalls" (= :done (:status res)) (pr-str (:status res)))
  (check! "the answer from the surviving attempt is the turn's text"
          (= "recovered on the third try" (:text res)) (pr-str (:text res)))
  (check! "the request was re-sent exactly 3 times total (1 + 2 retries)"
          (= 3 (long @!attempts)) (str @!attempts " attempt(s)"))
  (check! "each retry was ANNOUNCED (so the pause is explained, not silent)"
          (= 2 (count @retries))
          (pr-str (map #(select-keys % [:attempt :wait-ms]) @retries))))

(println)

(defn- stall-after
  "An InputStream that yields `s`'s bytes and then throws a read timeout — i.e. a
  stream that stalls MID-ANSWER. Backed by a real byte buffer so the SSE parser
  sees genuine data before the wire gives up."
  [^String s]
  (let [bs (.getBytes s "UTF-8")
        !pos (atom 0)]
    (proxy [java.io.InputStream] []
      (read
        ([] (if (< (long @!pos) (alength bs))
              (let [b (aget bs (int @!pos))]
                (swap! !pos inc)
                (bit-and 0xff b))
              (throw (timed-out))))
        ([^bytes b ^long off ^long len]
         (if (< (long @!pos) (alength bs))
           (let [n (int (min len (- (alength bs) (long @!pos))))]
             (System/arraycopy bs (int @!pos) b (int off) n)
             (swap! !pos + n)
             n)
           (throw (timed-out))))))))

;; --- 2. NO retry once data has arrived ---------------------------------------
(println "=== 2. a stall MID-ANSWER is not retried, and the partial text is kept ===")

(let [;; attempt 1: stream two deltas then stall. attempt 2 (must never happen):
      ;; a full answer, which WOULD have been replayed at the user if we retried.
      steps [(fn [] (stall-after (str "data: {\"choices\":[{\"delta\":{\"content\":\"the answer so far\"}}]}\n"
                                      "data: {\"choices\":[{\"delta\":{\"content\":\" — and then it stopped\"}}]}\n")))
             (fn [] (sse [(delta {:content "A REPLAYED ANSWER — must not appear"})]))]
      !attempts (atom 0)
      segs (atom [])
      res (try
            (loop/run-turn! {:llm {:url "http://fixture/v1" :model "fixture"
                                   :post (posting steps !attempts)
                                   :stream-retries 2}
                             :message "go" :cwd tmp :system "test"
                             :on-event (fn [s] (swap! segs conj s))})
            (catch Throwable e {:threw e :data (ex-data e)}))]
  (check! "the turn FAILED (a mid-stream stall is not silently swallowed)"
          (instance? Throwable (:threw res)) (pr-str (:status res)))
  (check! "it was NOT retried — exactly 1 attempt"
          (= 1 (long @!attempts)) (str @!attempts " attempt(s)"))
  (check! "the error is tagged :stalled?"
          (true? (:stalled? (:data res))) (pr-str (keys (:data res))))
  (check! "the PARTIAL ANSWER is carried out, not thrown away"
          (str/includes? (str (:partial-text (:data res))) "the answer so far")
          (pr-str (:partial-text (:data res))))
  (check! "the partial text was streamed to the client as it arrived"
          (let [all (apply str (map #(when (= :answer (:kind %)) (:text %)) @segs))]
            (str/includes? all "the answer so far"))
          (pr-str (map #(select-keys % [:kind :text]) @segs))))

(println)

;; --- 3. the message names the cause ------------------------------------------
(println "=== 3. the failure is a diagnosis, not seven words ===")

(let [!attempts (atom 0)
      res (try
            (loop/run-turn! {:llm {:url "https://openrouter.ai/api/v1" :model "vendor/some-model"
                                   :post (stalling 5 "never reached" !attempts)
                                   :stream-retries 2}
                             :message "hi" :cwd tmp :system "test"})
            (catch Throwable e {:msg (.getMessage e) :data (ex-data e)}))]
  (println (str "  message: " (:msg res)))
  (check! "it names the ENDPOINT" (str/includes? (str (:msg res)) "openrouter.ai") nil)
  (check! "it names the MODEL" (str/includes? (str (:msg res)) "vendor/some-model") nil)
  (check! "it says HOW MANY attempts were made" (str/includes? (str (:msg res)) "Tried 3 time") nil)
  (check! "it names the KNOB to raise" (str/includes? (str (:msg res)) ":socket-timeout-sec") nil)
  (check! "it is NOT the bare exception text"
          (not= "Read timed out" (str/trim (str (:msg res)))) nil)
  (check! "the cause is preserved for the log"
          (str/includes? (str (:cause-message (:data res))) "Read timed out")
          (pr-str (:cause-message (:data res)))))

(println)

;; --- 4. retries are BOUNDED ---------------------------------------------------
(println "=== 4. a dead provider fails in a bounded number of attempts ===")

(doseq [[retries expected] [[0 1] [1 2] [2 3]]]
  (let [!attempts (atom 0)]
    (try
      (loop/run-turn! {:llm {:url "http://fixture/v1" :model "fixture"
                             :post (stalling 9 "never" !attempts)
                             :stream-retries retries}
                       :message "hi" :cwd tmp :system "test"})
      (catch Throwable _ nil))
    (check! (str ":stream-retries " retries " → exactly " expected " attempt(s)")
            (= (long expected) (long @!attempts)) (str @!attempts " attempt(s)"))))

(println)

;; --- 5. an HTTP error is NOT retried ------------------------------------------
(println "=== 5. an HTTP 400 is thrown on the first attempt (a retry cannot fix it) ===")

(let [!attempts (atom 0)
      res (try
            (loop/run-turn! {:llm {:url "http://fixture/v1" :model "fixture"
                                   :post (fn [_ _]
                                           (swap! !attempts inc)
                                           {:status 400
                                            :body (java.io.ByteArrayInputStream.
                                                   (.getBytes "{\"error\":{\"message\":\"bad tool schema\"}}"
                                                              "UTF-8"))})
                                   :stream-retries 3}
                             :message "hi" :cwd tmp :system "test"})
            (catch Throwable e {:msg (.getMessage e) :data (ex-data e)}))]
  (check! "it was attempted exactly ONCE" (= 1 (long @!attempts)) (str @!attempts " attempt(s)"))
  (check! "the provider's own words are in the message"
          (str/includes? (str (:msg res)) "bad tool schema") (pr-str (:msg res)))
  (check! "it is NOT tagged :stalled?" (not (:stalled? (:data res))) (pr-str (:data res))))

(println)
(println (if (zero? @failures)
           "=== ALL PASS"
           (str "=== " @failures " FAILURE(S)")))
(when (pos? @failures) (System/exit 1))
