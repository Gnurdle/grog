#!/usr/bin/env bb
;; recents-test.clj — the model shortlist: LRU order, the cap, and what /model
;; resolves to.
;;
;;   clojure -M scripts/recents-test.clj
;;
;; Why this exists: `recents` replaced the big/small slots as the way to switch
;; models, and its whole value is the ORDER — a pick you use again must move to
;; the FRONT, and the entry that falls off the cap must be the one you have not
;; touched for longest. That is a property, not a vibe: an implementation that
;; appends and takes the last N would look right in a screenshot and be wrong in
;; use, and only a test that re-picks an existing entry can tell the difference.
;;
;; ISOLATION IS THE POINT. `grog.platform/config-home-dir` reads the
;; `GROG_CONFIG_HOME` ENV VAR, not a system property — so this file does NOT set
;; one (a `System/setProperty` here would be silently ignored and the test would
;; write into the developer's real ~/.config/grog/grog.edn; it did, once). Run it
;; through the wrapper that supplies the env var, which is what CI does:
;;
;;   scripts/recents-test.sh
;;
;; and this file REFUSES to run against the real config home, so a bare
;; `clojure -M scripts/recents-test.clj` fails loudly instead of clobbering it.

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def fails (atom 0))
(defn- check!
  ([id ok?] (check! id ok? nil))
  ([id ok? detail]
   (println (str (if ok? "  PASS  " "  FAIL  ") id (when detail (str "  — " detail))))
   (when-not ok? (swap! fails inc))))

(def tmp-home (System/getenv "GROG_CONFIG_HOME"))

(when (str/blank? tmp-home)
  (println "REFUSING TO RUN: GROG_CONFIG_HOME is unset.")
  (println "  This test writes grog.edn, and with no GROG_CONFIG_HOME it would")
  (println "  write the REAL one. Use: scripts/recents-test.sh")
  (System/exit 2))

(when (= (str tmp-home) (str (io/file (System/getProperty "user.home") ".config" "grog")))
  (println "REFUSING TO RUN: GROG_CONFIG_HOME points at the real config home:" tmp-home)
  (System/exit 2))

(println (str "(isolated config home: " tmp-home ")"))
(.mkdirs (io/file tmp-home))

(require '[grog.config :as config]
         '[grog.models :as models])

(defn- models-of [rows] (mapv :model rows))

(defn- reset-llm! [m]
  ;; write a bare :llm map, the way a user's grog.edn would have it
  (spit (io/file tmp-home "grog.edn") (pr-str {:llm m}))
  (config/reload!))

(defn- write-recents! [rows]
  (spit (io/file tmp-home "grog.edn")
        (pr-str {:llm {:url "http://localhost:8080/v1"
                       :model "m-current"
                       :recents rows
                       :recents-max 3}}))
  (config/reload!))

;; --- 1. LRU order, not insertion order -------------------------------------

(println "=== 1. LRU order: a re-pick moves to the FRONT ===")

(reset-llm! {:url "http://localhost:8080/v1" :model "m-current" :recents-max 3})
(check! "an empty config has no recents" (empty? (config/recents)))

(models/push-recent! "http://a/v1" "m-a")
(models/push-recent! "http://b/v1" "m-b")
(models/push-recent! "http://c/v1" "m-c")
(check! "newest first" (= ["m-c" "m-b" "m-a"] (models-of (config/recents)))
        (pr-str (models-of (config/recents))))

;; THE discriminating case: re-pick the OLDEST. An append+take-last list keeps
;; it at the end (and would drop it next); an LRU moves it to the front.
(models/push-recent! "http://a/v1" "m-a")
(check! "re-picking the OLDEST moves it to the front, without duplicating"
        (= ["m-a" "m-c" "m-b"] (models-of (config/recents)))
        (pr-str (models-of (config/recents))))

;; --- 2. the cap is an LRU bound --------------------------------------------

(println)
(println "=== 2. the cap drops the least-recently-used, not the first-seen ===")

;; cap is 3; m-b is now the LRU (it was not re-picked), so adding m-d evicts it
(models/push-recent! "http://d/v1" "m-d")
(check! "the LRU entry falls off" (= ["m-d" "m-a" "m-c"] (models-of (config/recents)))
        (pr-str (models-of (config/recents))))
(check! "the entry that was just re-picked is still there"
        (some #(= "m-a" (:model %)) (config/recents)))
(check! "the cap is honoured" (= 3 (count (config/recents))))

;; --- 3. the entry carries its transport ------------------------------------

(println)
(println "=== 3. a recents row carries the transport it was picked against ===")

(write-recents! [{:url "http://localhost:8080/v1" :model "strata-model"}
                 {:url "https://openrouter.ai/api/v1" :model "aion-labs/aion-2.0"}])
(let [rows (config/recents)]
  (check! "each row keeps its own url"
          (= ["http://localhost:8080/v1" "https://openrouter.ai/api/v1"] (mapv :url rows)))
  (check! "the provider is derived for a catalogue url"
          (= "strata" (:provider (first rows))) (pr-str (:provider (first rows))))
  (check! "and for a remote one"
          (= "openrouter" (:provider (second rows))) (pr-str (:provider (second rows)))))

;; --- 4. the label shows provider AND model ---------------------------------

(println)
(println "=== 4. the label names the provider beside the model ===")

(check! "a known provider is labelled"
        (= "strata · strata-model" (config/model-label (first (config/recents))))
        (config/model-label (first (config/recents))))
(check! "an unknown host still labels (falls back to the model id)"
        (= "weird-model" (config/model-label {:url "http://10.0.0.9:9999/v1" :model "weird-model"}))
        (config/model-label {:url "http://10.0.0.9:9999/v1" :model "weird-model"}))

;; --- 5. junk in the list does not throw ------------------------------------

(println)
(println "=== 5. a hand-edited list degrades instead of throwing ===")

(write-recents! [{:url "http://a/v1" :model "ok"}
                 {:model "no-url"}
                 "not-a-map"
                 {:url "http://a/v1" :model "ok"}      ; duplicate
                 {:url "http://b/v1" :model "ok-2"}])
(check! "only well-formed rows survive"
        (= ["ok" "ok-2"] (models-of (config/recents)))
        (pr-str (models-of (config/recents))))

;; --- 6. /model resolves the numbered list ----------------------------------

(println)
(println "=== 6. /model <n> addresses the list it prints ===")

(write-recents! [{:url "http://localhost:8080/v1" :model "strata-model"}
                 {:url "https://openrouter.ai/api/v1" :model "aion-labs/aion-2.0"}])
(let [choices (config/model-choices)]
  (check! "entry 1 is the CURRENT model (not the first recent)"
          (and (= 1 (:n (first choices))) (:current? (first choices)))
          (pr-str (first choices)))
  (check! "the recents follow, numbered from 2"
          (= [2 3] (mapv :n (rest choices))) (pr-str (mapv :n choices)))
  (check! "the numbers are contiguous (what /model <n> accepts)"
          (= (range 1 (inc (count choices))) (mapv :n choices))))

(let [c (config/resolve-model-choice "2")]
  (check! "/model 2 resolves to the second row, with its OWN url"
          (and (= :choice (:kind c))
               (= "http://localhost:8080/v1" (:url c))
               (= "strata-model" (:model c)))
          (pr-str c)))

(check! "/model with no argument asks for the LIST"
        (= :list (:kind (config/resolve-model-choice ""))))
(check! "/model 99 is out of range, not silently entry 1"
        (= :unknown (:kind (config/resolve-model-choice "99")))
        (pr-str (config/resolve-model-choice "99")))
(check! "/model <name> is a raw id on the configured transport"
        (let [c (config/resolve-model-choice "some-model")]
          (and (= :choice (:kind c)) (= "some-model" (:model c))
               (= "http://localhost:8080/v1" (:url c))))
        (pr-str (config/resolve-model-choice "some-model")))

;; --- 7. forget -------------------------------------------------------------

(println)
(println "=== 7. forget drops exactly one row ===")

(models/forget-recent! "https://openrouter.ai/api/v1" "aion-labs/aion-2.0")
(check! "the forgotten row is gone"
        (= ["strata-model"] (models-of (config/recents)))
        (pr-str (models-of (config/recents))))

;; --- 8. other config keys survive a write ----------------------------------

(println)
(println "=== 8. writing recents preserves the rest of grog.edn ===")

(spit (io/file tmp-home "grog.edn")
      (pr-str {:soul {:path "SOUL.md"}
               :llm {:url "http://localhost:8080/v1" :model "m-current" :recents-max 3}}))
(config/reload!)
(models/push-recent! "http://a/v1" "m-a")
(let [after (edn/read-string (slurp (io/file tmp-home "grog.edn")))]
  (check! "a sibling top-level key is untouched"
          (= {:path "SOUL.md"} (:soul after)) (pr-str (:soul after)))
  (check! "the model id is untouched"
          (= "m-current" (get-in after [:llm :model])) (pr-str (get-in after [:llm :model])))
  (check! "and the recents landed"
          (= ["m-a"] (models-of (config/recents)))))

(println)
(if (zero? @fails)
  (do (println "recents-test: ALL PASS")
      (System/exit 0))
  (do (println (str "recents-test: " @fails " FAILED"))
      (System/exit 1)))
