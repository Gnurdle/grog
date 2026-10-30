#!/usr/bin/env bb
;; url-normalization-test.clj — the endpoint a saved model stores, exactly.
;;
;; Why this exists. Driving the Models dialog's own `save-choice` call against a
;; real spine turned up a URL that was quietly WRONG:
;;
;;   save-choice {:url "http://127.0.0.1:9/v1"}  ->  stored "http://http://127.0.0.1:9/v1"
;;
;; The cause is a Clojure classic: `re-matches` used with a pattern that
;; describes a PREFIX (`^[a-z][a-z0-9+.-]*://`). `re-matches` demands a
;; WHOLE-string match, so a url that already carried a scheme failed that test and
;; got `http://` glued on in front of it. Every model saved from the dialog — the
;; one place a user types a URL — would have been stored wrong, and the failure
;; would surface much later as "connection refused" against a host that never
;; existed. Nothing tested the URL field, because nothing tested the URL field.
;;
;; This file pins the boundaries of that one function: the edits a user actually
;; makes in the URL field, and the cases that must be REFUSED rather than silently
;; "fixed", because a repaired guess saves a row whose endpoint cannot exist.
;;
;; ISOLATION: the second half WRITES grog.edn (it exercises `save-choice!`, the
;; dialog's real write path), so it REFUSES to run without an isolated
;; GROG_CONFIG_HOME — same guard as model-picker-test.clj.
;;
;; Run: scripts/url-normalization-test.sh   (from the repo root)

(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[grog.models :as m])

(def fails (atom 0))
(defn- check!
  ([id ok?] (check! id ok? nil))
  ([id ok? detail]
   (println (str (if ok? "  PASS  " "  FAIL  ") id (when detail (str "  — " detail))))
   (when-not ok? (swap! fails inc))))

(defn- eq! [id expected actual]
  (check! id (= expected actual) (str "expected " (pr-str expected) ", got " (pr-str actual))))

;; `normalize-choice` is the reader's gate too — a hand-edited grog.edn row goes
;; through it — so it is exercised without touching the config file.
(defn- url-of [u] (:url (m/normalize-choice {:name "t" :url u :model "x"})))

(println "== the URL field: what a typed endpoint becomes ==")

;; THE BUG. A scheme that is already there must be left alone.
(eq! "a full http url is stored EXACTLY (no second scheme glued on)"
     "http://127.0.0.1:9/v1" (url-of "http://127.0.0.1:9/v1"))
(eq! "…same for https"
     "https://openrouter.ai/api/v1" (url-of "https://openrouter.ai/api/v1"))
(eq! "…and a non-default port survives (two local launches differ by nothing else)"
     "http://localhost:8081/v1" (url-of "http://localhost:8081/v1"))
(eq! "…and an odd PATH is the user's business — never rewritten to /v1"
     "https://api.groq.com/openai/v1" (url-of "https://api.groq.com/openai/v1"))
(eq! "…a path ending in something other than v1 too"
     "https://api.anthropic.com/v1" (url-of "https://api.anthropic.com/v1"))

;; The convenience edit: a bare host+port is what people actually type.
(eq! "a bare host gets the scheme it means"
     "http://localhost:8081/v1" (url-of "localhost:8081/v1"))
(eq! "trailing slashes are trimmed"
     "http://localhost:8081/v1" (url-of "http://localhost:8081/v1///"))
(eq! "surrounding spaces are trimmed"
     "http://localhost:8080/v1" (url-of "  http://localhost:8080/v1  "))

;; Refused rather than repaired: each would otherwise save a row whose endpoint
;; cannot exist, and the failure would show up much later as a connection error
;; nobody can attribute.
(eq! "a DOUBLED scheme is refused, not saved"
     nil (url-of "http://http://localhost:9/v1"))
(eq! "…same for https pasted twice"
     nil (url-of "https://https://openrouter.ai/api/v1"))
(eq! "a non-http scheme yields NO url"  nil (url-of "ftp://x.example/y"))
(eq! "a sentence yields NO url"          nil (url-of "not a url"))
(eq! "a host-less url yields NO url"     nil (url-of "http:///v1"))

;; A BLANK url is legal and means "the transport already configured" — which is
;; exactly why a present-but-junk url must be an error rather than nil: silently
;; turning "my server on 8081" into "whatever is configured" is the worse failure.
(eq! "a blank url stays blank (means the configured transport)"
     nil (url-of ""))

;; ---------------------------------------------------------------------------
;; The DIALOG's write path. `save-choice!` is what the Save button calls: an
;; unusable value raises with a sentence the FORM prints beside the field, and a
;; usable one lands in the file with the url unchanged.
;; ---------------------------------------------------------------------------
(let [home (System/getenv "GROG_CONFIG_HOME")]
  (when (str/blank? home)
    (println "\nREFUSING TO RUN: GROG_CONFIG_HOME is unset.")
    (println "  save-choice! writes grog.edn; with no GROG_CONFIG_HOME it would write")
    (println "  the REAL one. Use: scripts/url-normalization-test.sh")
    (System/exit 2))
  (when (= (str home) (str (io/file (System/getProperty "user.home") ".config" "grog")))
    (println "REFUSING TO RUN: GROG_CONFIG_HOME points at the real config home:" home)
    (System/exit 2))

  (defn- save-error [url]
    (try (m/save-choice! {:name "probe-row" :url url :model "x"})
         nil
         (catch Throwable e (.getMessage e))))

  (println)
  (println "== save-choice! (the Save button): refused in words, saved exactly ==")
  (check! "a junk url RAISES instead of storing a dead row" (some? (save-error "not a url")))
  (check! "…the message names the field"
          (boolean (re-find #"(?i)url" (str (save-error "not a url")))))
  (check! "…and shows the shape that IS wanted"
          (boolean (re-find #"http://" (str (save-error "not a url")))))
  (check! "a doubled scheme is refused too, not stored"
          (some? (save-error "http://http://localhost:9/v1")))
  (let [r (m/save-choice! {:name "probe-row" :url "http://127.0.0.1:9/v1" :model "x"})]
    (eq! "a good url is saved with its url UNCHANGED"
         "http://127.0.0.1:9/v1" (get-in r [:choice :url]))
    (eq! "…and the name is folded to what /model accepts"
         "probe-row" (:name (:choice r))))
  (m/remove-choice! "probe-row"))

(println)
(if (pos? @fails)
  (do (println (str "url-normalization-test: FAILED — " @fails " check(s)")) (System/exit 1))
  (do (println "url-normalization-test: all checks passed") (System/exit 0)))
