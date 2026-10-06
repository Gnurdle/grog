#!/usr/bin/env bb
;; providers-test.clj — guard the shipped provider catalogue and the example.
;;
;; Run: bb scripts/providers-test.clj   (from the repo root)
;;
;; Two things must not drift: every catalogue entry must be well-formed, and
;; resources/config.examples/grog.edn.example must carry each catalogue URL as an
;; `:llm :profiles` entry with the SAME base. A copy-pasted URL that quietly
;; differs from the catalogue is exactly the 404 this file exists to catch.

(require '[clojure.edn :as edn])

(def fails (atom 0))

(defn check [name ok?]
  (if ok?
    (println "ok  " name)
    (do (swap! fails inc) (println "FAIL" name))))

(def cat (edn/read-string (slurp "resources/providers.edn")))
(def ex (edn/read-string (slurp "resources/config.examples/grog.edn.example")))
(def ps (:providers cat))
(def profiles (get-in ex [:llm :profiles]))

(check "catalogue has providers" (boolean (seq ps)))
(check "ids are unique" (= (count (map :id ps)) (count (distinct (map :id ps)))))
(check "urls are unique" (= (count (map :url ps)) (count (distinct (map :url ps)))))
(check "every entry has id/label/url/kind"
       (every? #(and (string? (:id %)) (string? (:label %))
                     (string? (:url %)) (contains? #{:remote :local} (:kind %)))
               ps))
(check "urls are absolute http(s)"
       (every? #(re-find #"^https?://" (:url %)) ps))

(check "example default :llm :url is a known provider"
       (contains? (set (map :url ps)) (get-in ex [:llm :url])))

(doseq [{:keys [id url]} ps]
  (let [p (get profiles (keyword id))]
    (check (str "example has profile :" id) (some? p))
    (check (str "example profile :" id " url matches catalogue") (= url (:url p)))))

(check "example has no profile the catalogue does not know"
       (every? #(contains? (set (map (comp keyword :id) ps)) (key %)) profiles))

(println)
(if (zero? @fails)
  (do (println (str "providers-test: all " "checks passed"))
      (System/exit 0))
  (do (println (str "providers-test: " @fails " FAILED"))
      (System/exit 1)))
