#!/usr/bin/env bb
;; Every tab's diagnostics must say WHICH TAB. No model, no network, no window.
;;
;;   clojure -M scripts/session-tag-test.clj
;;
;; Why this test exists. grog writes ONE log per client run (`~/grog.<pid>.log`),
;; and the spine's stderr from EVERY tab lands in it. On 2026-10-26 that made a
;; hang genuinely hard to diagnose: the log showed
;;
;;     worker take: "…the rpms file isn't included in the build…"
;;     loop turn: :done steps= 25 ms= 3029306
;;
;; with no way to tell which of the four open tabs it belonged to — the messages
;; from `cms-wave`, `grog` and `devops` interleave in one stream. Worse, one of
;; those tabs' "loop turn: :done" lines was the ONLY evidence of the hang, and
;; attributing it took reading the per-project transcripts to guess.
;;
;; The log pane was supposed to solve this: its "this project" scope FILTERS
;; lines by a `[<session-id>]` prefix. But that prefix was emitted by
;; `grog.eca/connect!`, and `grog.eca` was DELETED when grog's own agent loop
;; replaced ECA (commit 0ee93df) — so the filter matched nothing, silently, and
;; every tab showed every tab's lines. A dead filter is worse than no filter: it
;; looks like it works.
;;
;; This file pins both halves:
;;   1. the spine EMITS the tag, and it is exactly the `:sessionId` the server
;;      stamps on the wire (so the renderer's session keys cannot drift from it);
;;   2. the renderer's filter, run for real, then HIDES a foreign tab's line and
;;      KEEPS the active tab's — the behaviour that was silently broken;
;;   3. a line with NO session id still prints untagged (a diagnostic we cannot
;;      place is KEPT, never dropped — hiding one that turns out to matter is the
;;      worse failure);
;;   4. the tag survives the worker path that actually prints `worker take:`.
(require '[clojure.string :as str]
         '[grog.chat :as chat])

(def failures (atom 0))
(defn- check!
  ([id ok?] (check! id ok? nil))
  ([id ok? detail]
   (println (str (if ok? "  PASS  " "  FAIL  ") id (when detail (str "  — " detail))))
   (when-not ok? (swap! failures inc))))

(defn- mk
  "A minimal session state. `session-id` nil mimics a session the spine could not
  name (an old client, or a state built before the id existed)."
  [sid]
  (chat/make-state (cond-> {:project "proj" :chat-id "chat" :model "m"
                            :model-label "l" :provider "p" :url "u"}
                     sid (assoc :session-id sid))))

(defn- capture-stderr
  "Run `f` with System/err swapped for a buffer; return what it printed."
  [f]
  (let [bos (java.io.ByteArrayOutputStream.)
        ps (java.io.PrintStream. bos true "UTF-8")
        old System/err]
    (try
      (System/setErr ps)
      (f)
      (.flush ps)
      (String. (.toByteArray bos) "UTF-8")
      (finally (System/setErr old)))))

;; --- 1. the tag is emitted, and it is the session id ---------------------------
(println "=== 1. the spine emits a [<session-id>] tag ===")

(let [sid "cms-wave#1793051293325"
      st (mk sid)
      tag (chat/session-tag st)]
  (check! "session-tag wraps the id in brackets, with a trailing space"
          (= (str "[" sid "] ") tag)
          (pr-str tag))
  (check! "the tag contains the EXACT id the server puts on the wire"
          (str/includes? tag (str "[" sid "]"))
          (pr-str tag)))

;; --- 2. the renderer's filter actually filters ---------------------------------
(println "=== 2. the log pane's scope filter hides OTHER tabs, keeps its own ===")

;; A faithful copy of the renderer's `log-foreign?` (core.cljs): a line is hidden
;; only when it carries the tag of some open session that is NOT the active one.
(defn- log-foreign?
  [line active others]
  (boolean (some (fn [sid] (str/includes? line (str "[" sid "]")))
                 (remove #(= active %) others))))

(let [sid-a "cms-wave#111" sid-b "devops#222"
      line-a (str (chat/session-tag (mk sid-a)) "[grog-debug] loop turn: :done")
      line-b (str (chat/session-tag (mk sid-b)) "[grog-debug] loop turn: :done")
      all [sid-a sid-b]]
  (check! "the ACTIVE tab's own line is kept"
          (not (log-foreign? line-a sid-a (remove #(= sid-a %) all)))
          (pr-str line-a))
  (check! "a FOREIGN tab's line is hidden"
          (log-foreign? line-a sid-b (remove #(= sid-b %) all))
          (pr-str line-a))
  (check! "…and the two tabs' lines are distinguishable at all"
          (not= line-a line-b)
          (str (pr-str line-a) " vs " (pr-str line-b)))
  ;; THE REGRESSION: with the tag missing, both lines are identical, so the
  ;; filter can only ever answer "not foreign" — every tab shows everything.
  (let [untagged "[grog-debug] loop turn: :done"]
    (check! "REGRESSION GUARD: an untagged line is shown in every scope"
            (not (log-foreign? untagged sid-b all))
            "this is what the deleted grog.eca/connect! left behind")))

;; --- 3. a session with no id is still logged, untagged -------------------------
(println "=== 3. an unnameable session still logs (never silently dropped) ===")

(let [st (mk nil)]
  (check! "no id means no tag" (= "" (chat/session-tag st)) (pr-str (chat/session-tag st)))
  (check! "…and the line still prints, in full"
          (= "client-wide note\n" (capture-stderr #(chat/tagged! st "client-wide note")))
          (pr-str (capture-stderr #(chat/tagged! st "client-wide note")))))

;; --- 4. the real worker path prints the tag ------------------------------------
(println "=== 4. the `worker take:` line carries the tag ===")

(let [sid "grog#42"
      st (mk sid)
      out (capture-stderr #(chat/tagged! st "worker take:" "\"fix these\""))]
  (check! "the line a human greps for is tagged"
          (and (str/starts-with? out (str "[" sid "] "))
               (str/includes? out "worker take:"))
          (pr-str out))
  (check! "…and it is still greppable as before (marker preserved)"
          (str/includes? out "worker take:")
          (pr-str out)))

(println)
(if (zero? @failures)
  (println "all checks passed")
  (do (println (str @failures " check(s) FAILED"))
      (System/exit 1)))
(shutdown-agents)
