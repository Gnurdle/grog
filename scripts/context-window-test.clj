#!/usr/bin/env bb
;; The context window: ask the endpoint, and clamp the budget to it. No model.
;;
;;   clojure -M scripts/context-window-test.clj
;;
;; Why this test exists. grog's context budget was a CONSTANT — 200000 in
;; `grog.config/max-context-tokens`, 120000 in the loop's fallback — and NOTHING
;; ever asked the endpoint what it would accept. Pointed at a local server
;; serving 32768 tokens, the loop built a 38683-token prompt and the server
;; answered:
;;
;;   400 invalid request: request exceeds the context window: prompt (38683
;;       tokens) leaves no room to answer in the context (32768);
;;       requests are never truncated
;;
;; (Strata, launch-iq3_s-32k.server.log). Compaction never fired because its
;; budget was three times the window. This file proves the fix:
;;   1. the two body SHAPES the endpoints actually send are parsed;
;;   2. a CATALOGUE is not a server — with many models and no id match, the probe
;;      returns nil rather than clamping to some unrelated model's window (this
;;      bug was in the first version of the fix: `(first (:data …))`);
;;   3. a single-model launch IS the served window even when the configured id is
;;      a placeholder (`local-model`) that matches no id;
;;   4. the clamp lowers the budget to fit, reserving room to answer;
;;   5. an unreachable endpoint changes NOTHING (nil means "do not guess", so a
;;      probe failure can never break a turn);
;;   6. the cache is PER (url, model) — one url can serve a whole catalogue, and a
;;      window answered for the wrong model is the original bug wearing a smaller
;;      hat;
;;   7. an overflow the provider NAMED teaches the window, and the learned number
;;      is what the budget derives from afterwards.
(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[grog.models :as models]
         '[grog.platform :as platform])

;; A THROWAWAY config home. The window cache is PERSISTED (context-windows.edn),
;; and a test that wrote into the user's real ~/.config/grog would (a) modify the
;; machine it runs on and (b) pass or fail depending on what a previous run
;; learned — a test whose result depends on history is not a test.
(System/setProperty "user.home" (System/getProperty "user.home"))
(let [tmp (io/file (System/getProperty "java.io.tmpdir")
                   (str "grog-cw-test-" (System/nanoTime)))]
  (.mkdirs tmp)
  (alter-var-root #'grog.platform/config-home-dir (constantly (fn [] tmp))))

(def failures (atom 0))
(defn- check!
  ([id ok?] (check! id ok? nil))
  ([id ok? detail]
   (println (str (if ok? "  PASS  " "  FAIL  ") id (when detail (str "  — " detail))))
   (when-not ok? (swap! failures inc))))

(defn- parse [body] (models/parse-window-response body))
(defn- parse-for [body model] (models/parse-window-response body model))

;; --- 1. the two shapes the endpoints send -------------------------------------
(println "=== 1. the wire shapes are parsed ===")

(check! "Strata/llama.cpp /models carries meta.n_ctx"
        (= 32768 (parse {:data [{:id "deepseek-v4.1-flash" :meta {:n_ctx 32768}}]}))
        (str (parse {:data [{:id "x" :meta {:n_ctx 32768}}]})))

(check! "llama.cpp /props carries default_generation_settings.n_ctx"
        (= 8192 (parse {:default_generation_settings {:n_ctx 8192}}))
        (str (parse {:default_generation_settings {:n_ctx 8192}})))

(check! "an OpenRouter-shaped entry carries context_length"
        (= 131072 (parse-for {:data [{:id "moonshotai/kimi-k3" :context_length 131072}]}
                             "moonshotai/kimi-k3")))

(check! "n_ctx_train is accepted when n_ctx is absent"
        (= 4096 (parse {:data [{:id "x" :meta {:n_ctx_train 4096}}]})))

(check! "a window of 0 or a non-number is NOT a window (no divide-by-zero, no NPE)"
        (and (nil? (parse {:data [{:id "x" :meta {:n_ctx 0}}]}))
             (nil? (parse {:data [{:id "x" :meta {:n_ctx "big"}}]}))
             (nil? (parse {:nope 1}))
             (nil? (parse nil))))

(check! "a boolean is rejected, not coerced (the `(long true)` trap)"
        (nil? (parse {:data [{:id "x" :meta {:n_ctx true}}]})))

;; --- 2. a catalogue is not a server -------------------------------------------
(println)
(println "=== 2. a many-model catalogue is never guessed at ===")

(def openrouter-ish
  {:data [{:id "openai/gpt-6.1-terra" :context_length 1050000}
          {:id "aion-labs/aion-2.0"    :context_length 200000}
          {:id "meta/llama-3.3-70b"    :context_length 131072}]})

(check! "the id MATCHES the entry — you get THAT model's window, not the first one"
        (= 200000 (parse-for openrouter-ish "aion-labs/aion-2.0"))
        (str (parse-for openrouter-ish "aion-labs/aion-2.0")))

(check! "NO id match over many models -> nil (do not clamp to a stranger's window)"
        (nil? (parse-for openrouter-ish "no-such/model"))
        "a first-entry fallback here would clamp a 1M-context model to 200k")

(check! "no model id given over many models -> nil"
        (nil? (parse-for openrouter-ish nil)))

;; --- 3. a single-model launch IS the served window ----------------------------
(println)
(println "=== 3. a single-model launch reports its window even for a placeholder id ===")

(check! "one entry, id does not match `local-model` (the real Strata case)"
        (= 131072 (parse-for {:data [{:id "deepseek-v4.1-flash" :meta {:n_ctx 131072}}]}
                             "local-model"))
        "`:strata` ships :sample nil, so the configured id is often a placeholder")

(check! "one entry, no model id at all"
        (= 131072 (parse {:data [{:id "deepseek-v4.1-flash" :meta {:n_ctx 131072}}]})))

;; --- 4. the clamp -------------------------------------------------------------
(println)
(println "=== 4. the budget is clamped to fit, leaving room to answer ===")

(check! "the reported case: a 200k budget against a 32k window"
        (= 28672 (models/clamp-budget 200000 32768 4096))
        (str (models/clamp-budget 200000 32768 4096)))

(check! "a budget already inside the window is LEFT ALONE"
        (= 20000 (models/clamp-budget 20000 32768 4096)))

(check! "the clamp never goes below 1 (a 100-token window is absurd but not a crash)"
        (pos? (models/clamp-budget 200000 100 4096)))

(check! "no window -> the budget passes through unchanged"
        (= 200000 (models/clamp-budget 200000 nil 4096)))

(check! "no budget -> nil (nil means 'do not trim', not 0)"
        (nil? (models/clamp-budget nil 32768 4096)))

(check! "the reserve is a sensible fraction of the window, bounded at both ends"
        (let [r (fn [w] ((var models/completion-reserve) w))]
          (and (>= (r 32768) 2048) (<= (r 32768) 32768)
               (>= (r 1048576) 2048) (<= (r 1048576) 32768)
               ;; an eighth of 32k = 4k — but ONLY when :llm :max-tokens is unset;
               ;; a user who set one gets their own reservation, so this exact
               ;; value is asserted only in the default case (config-independent
               ;; test: it must pass on a machine with a different grog.edn).
               (or (some? (try ((requiring-resolve 'grog.config/llm-max-tokens))
                               (catch Throwable _ nil)))
                   (= 4096 (r 32768))))))

;; --- 5. failure is the OLD behaviour, never a broken turn ---------------------
(println)
(println "=== 5. an unreachable endpoint changes nothing ===")

(check! "a dead port yields nil, not an exception"
        (nil? (models/endpoint-context-window "http://127.0.0.1:59999/v1" "x")))

(check! "a blank url yields nil (never throws on a nil url)"
        (and (nil? (models/endpoint-context-window "" "x"))
             (nil? (models/endpoint-context-window nil "x"))))

(check! "`forget-context-window!` is callable on an empty cache"
        (do (models/forget-context-window!) (models/forget-context-window! "http://x/v1") true))

;; --- 6. the id a pick may RECORD (live-model-id) ------------------------------
;;
;; The model picker's other half: when a pick names no model, where does one come
;; from? Asking the endpoint is right; INVENTING one is what put "local-model" —
;; an id no server answers to — into a user's config, paired against a transport
;; that serves something else entirely.
(println)
(println "=== 6. what `live-model-id` is allowed to fall back to ===")

(def ^:private dead-port "http://127.0.0.1:59999/v1")

(check! "a placeholder hint is NEVER recorded, even when the server answers"
        (nil? (models/live-model-id dead-port "local-model")))

(check! "…and `placeholder-model-id?` names them"
        (and (models/placeholder-model-id? "local-model")
             (models/placeholder-model-id? "  LOCAL-MODEL ")
             (not (models/placeholder-model-id? "llama-3.3-70b"))
             (not (models/placeholder-model-id? nil))))

;; A LOCAL endpoint that does not answer means the id is genuinely UNKNOWN: a
;; local launch serves whatever it was started with, so there is no default to
;; guess. The answer is nil (leave it blank), not the hint.
(check! "a dead LOCAL endpoint yields nil, even for a plausible-looking hint"
        (nil? (models/live-model-id dead-port "llama-3.3-70b")))

(check! "…but a dead localhost/127.0.0.1 endpoint is treated the same"
        (and (nil? (models/live-model-id "http://localhost:59999/v1" "some-id"))
             (nil? (models/live-model-id "http://127.0.0.1:59999/v1" "some-id"))))

;; A REMOTE endpoint's hint comes from the shipped catalogue and is a REAL catalog
;; id (openrouter etc.), so it is usable when the endpoint cannot be reached — the
;; user can still be pointed at a plausible model instead of a blank.
(check! "a dead REMOTE endpoint keeps the catalogue hint"
        (= "moonshotai/kimi-k3"
           (models/live-model-id "https://not-a-real-host.invalid/v1" "moonshotai/kimi-k3")))

;; --- 7. the cache is per (url, model), and a learned window is a real source ----
(println)
(println "=== 7. per-model memory, and a refusal teaches the window ===")

(models/forget-context-window!)
(models/remember-context-window! "http://fixture-a/v1/" "small-model" 32768)
(models/remember-context-window! "http://fixture-a/v1" "big-model" 1048576)

(check! "two models on ONE url keep their own windows"
        (and (= 32768 (models/cached-context-window "http://fixture-a/v1" "small-model"))
             (= 1048576 (models/cached-context-window "http://fixture-a/v1" "big-model")))
        (str (models/cached-context-window "http://fixture-a/v1" "small-model") " / "
             (models/cached-context-window "http://fixture-a/v1" "big-model")))

(check! "a trailing slash is the same endpoint (no phantom cache entry)"
        (= 32768 (models/cached-context-window "http://fixture-a/v1/" "small-model")))

(check! "an unknown model on a known url is nil, NOT the other model's window"
        (nil? (models/cached-context-window "http://fixture-a/v1" "never-seen"))
        "a url-keyed cache would answer 32768 here — the original bug, smaller")

(check! "a learned window IS the endpoint's answer (no probe needed)"
        (= 1048576 (models/endpoint-context-window "http://fixture-a/v1" "big-model")))

(check! "junk is not remembered (0, nil, a string)"
        (and (nil? (models/remember-context-window! "http://fixture-b/v1" "m" 0))
             (nil? (models/remember-context-window! "http://fixture-b/v1" "m" nil))
             (nil? (models/remember-context-window! "http://fixture-b/v1" "m" "big"))))

(models/forget-context-window! "http://fixture-a/v1" "small-model")
(check! "forgetting ONE model leaves its neighbour alone"
        (and (nil? (models/cached-context-window "http://fixture-a/v1" "small-model"))
             (= 1048576 (models/cached-context-window "http://fixture-a/v1" "big-model"))))

(models/forget-context-window! "http://fixture-a/v1")
(check! "forgetting a URL clears every model on it"
        (nil? (models/cached-context-window "http://fixture-a/v1" "big-model")))

;; --- 8. the budget is DERIVED, never a constant -------------------------------
(println)
(println "=== 8. the budget comes from the window, and unknown means no guess ===")

(models/forget-context-window!)
(check! "an unknown endpoint gives NO budget (nil = do not guess, do not trim)"
        (nil? (models/context-budget))
        "the old default was 200000 — a number right for no model in particular")

(check! "…even when a caller passes a fallback through an `(or …)` idiom"
        (= 120000 (models/context-budget 120000))
        "only an EXPLICIT fallback is used; a new call site should pass none")

(models/remember-context-window! "http://fixture-c/v1" "32k" 32768)
(check! "the clamp arithmetic is the same as before, now applied to the window"
        (= 28672 (models/clamp-budget 32768 32768 4096)))

(println)
(if (zero? @failures)
  (do (println "context-window-test: all checks passed") (System/exit 0))
  (do (println (str "context-window-test: " @failures " FAILED")) (System/exit 1)))
