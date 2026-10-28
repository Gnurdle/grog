#!/usr/bin/env bb
;; edit_file's own verification — the checks that do not need a model.
;;
;;   clojure -M scripts/edit-file-verify-test.clj
;;
;; Why this test exists. `edit_file` is one of grog's two SELF-VERIFYING writers:
;; it re-reads the file and reports whether the bytes landed. Its success test has
;; now been wrong TWICE, in the same way and in opposite directions, and both times
;; the file on disk was RIGHT while the report was WRONG:
;;
;;   (a) ORIGINAL: `(and ok? (or all_occurrences (zero? old-left)))` — "no
;;       occurrence of the anchor may survive". That is false for any edit that
;;       keeps the anchor and adds text around it, which is the ordinary
;;       "append a line below this line" edit. Reported
;;       "EDIT FAILED VERIFICATION ... written=N read-back=N" for a write that had
;;       landed. Hit twice on 2026-10-19 (improvements #14).
;;
;;   (b) FIRST FIX (#14): `(<= old-left n-before)` — "the anchor count may not
;;       increase". Fixes (a), still wrong whenever `new_content` CONTAINS the
;;       anchor: replacing "a" with "aa" legitimately raises the count to 2 and was
;;       rejected although the file was exactly right. Measured 2026-10-23.
;;
;; The lesson, and the reason this file exists rather than a note: whether the
;; anchor count rises, falls or stays is a property of the REPLACEMENT TEXT, not of
;; whether the edit happened. No threshold on that count can be correct, so the
;; verdict is `(= want got)` alone and the counts are reported as evidence only.
;;
;; What it proves:
;;   1. Every legitimate shape of edit SUCCEEDS (append below, replacement that
;;      contains the anchor, in-place removal, prepend-above, all_occurrences).
;;   2. The 2026-10-14 failure class is still CAUGHT: a lying tool / an
;;      interrupted write must not be reported as success. (b)'s fix must not have
;;      been bought by making the verifier credulous — see case 6.
;;   3. A refused edit writes NOTHING (a failed anchor match is not a partial edit).
;;   4. A no-op is reported as no-change, not as "1 replacement(s)".
;;
;; Each case runs against the REAL `edit-file*` with a real temp file, so this
;; tests the shipped verifier rather than a copy of its logic.
(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[grog.agent.tools :as tools])

(def failures (atom 0))
(defn- check!
  ([id ok?] (check! id ok? nil))
  ([id ok? detail]
   (println (str (if ok? "  PASS  " "  FAIL  ") id (when detail (str "  — " detail))))
   (when-not ok? (swap! failures inc))))

(defn- run-edit
  "Write `text` to a temp file, run the real edit-file* over it, and return
  {:error? :text :before :after :expected}. `expected` is what the file MUST be
  after a successful edit; the checks compare against it rather than against the
  tool's own message."
  [text anchor replacement & [{:keys [all?]}]
   ]
  (let [f (java.io.File/createTempFile "grog-editverify" ".txt")]
    (try
      (spit f text :encoding "UTF-8")
      (let [r (tools/edit-file* {:path (.getAbsolutePath f)
                                 :original_content anchor
                                 :new_content replacement
                                 :all_occurrences (boolean all?)})]
        {:error? (:error? r)
         :text (str (:text r))
         :before text
         :after (slurp f :encoding "UTF-8")})
      (finally (.delete f)))))

(defn- edit-ok?
  "Did the edit land AND get reported as landing? Both, or the case fails: the
  whole point of this test is that the report and the disk must agree."
  [{:keys [error? after]} expected]
  (and (not error?) (= expected after)))

;; --- 1. legitimate edits must all be reported as LANDED -----------------------
(println "=== 1. every legitimate shape of edit succeeds ===")

(let [r (run-edit "draft\n" "draft" "draft\nverified")]
  (check! "append a line BELOW the anchor (the #14 case)" (edit-ok? r "draft\nverified\n")
          (str "error?=" (:error? r) " after=" (pr-str (:after r)))))

(let [r (run-edit "a\n" "a" "aa")]
  (check! "replacement CONTAINS the anchor (count grows) — the residual bug"
          (edit-ok? r "aa\n")
          (str "error?=" (:error? r) " after=" (pr-str (:after r))
               (when (:error? r) (str " | " (first (str/split-lines (:text r))))))))

(let [r (run-edit "hello world\n" "world" "there")]
  (check! "in-place replacement that REMOVES the anchor" (edit-ok? r "hello there\n")
          (str "error?=" (:error? r) " after=" (pr-str (:after r)))))

(let [r (run-edit "one\ntwo\n" "two" "ONE\nTWO")]
  (check! "replacement that re-adds the anchor text elsewhere" (edit-ok? r "one\nONE\nTWO\n")
          (str "error?=" (:error? r) " after=" (pr-str (:after r)))))

(let [r (run-edit "x x x\n" "x" "y" {:all? true})]
  (check! "all_occurrences replaces every hit" (edit-ok? r "y y y\n")
          (str "error?=" (:error? r) " after=" (pr-str (:after r)))))

(let [r (run-edit "body\n" "body" "head\nbody")]
  (check! "prepend ABOVE the anchor (count unchanged)" (edit-ok? r "head\nbody\n")
          (str "error?=" (:error? r) " after=" (pr-str (:after r)))))

;; --- 2. a refused edit must write NOTHING -------------------------------------
(println "=== 2. a refused edit writes nothing (no partial edit) ===")

(let [r (run-edit "alpha beta\n" "gamma" "delta")]
  (check! "missing anchor → refused" (true? (:error? r)) (str "error?=" (:error? r)))
  (check! "missing anchor → file untouched" (= "alpha beta\n" (:after r)) (pr-str (:after r))))

(let [r (run-edit "dup dup\n" "dup" "other")]
  (check! "ambiguous anchor without all_occurrences → refused" (true? (:error? r)))
  (check! "ambiguous anchor → file untouched" (= "dup dup\n" (:after r)) (pr-str (:after r))))

(let [r (run-edit "anything\n" "" "x")]
  (check! "empty anchor → refused" (true? (:error? r)))
  (check! "empty anchor → file untouched" (= "anything\n" (:after r)) (pr-str (:after r))))

;; --- 3. a no-op is not an edit ------------------------------------------------
(println "=== 3. a no-op is reported as no-change, not as a replacement ===")
(let [r (run-edit "same\n" "same" "same")]
  (check! "an edit that changes nothing is NOT reported as a replacement"
          (true? (:error? r))
          (str "error?=" (:error? r) " text=" (pr-str (first (str/split-lines (:text r))))))
  (check! "no-op leaves the file as it was" (= "same\n" (:after r)) (pr-str (:after r))))

;; --- 4. the verifier must still CATCH a failed write ---------------------------
(println "=== 4. the verifier still catches a write that did not happen ===")
;; The 2026-10-14 class: the tool claims a replacement and the file is unchanged.
;; Reproduced honestly by making the file UNWRITABLE after the read — the read
;; succeeds, the write does not, and the caller must NOT be told an edit happened.
;;
;; Dispatched through `tools/call` (the table the loop actually uses) rather than
;; `edit-file*` directly: the loop's contract is that a tool returns
;; {:text :error?}, so a throw is the DISPATCHER's job to convert into a reported
;; failure, and this checks the failure reaches the model as a failure either way.
;;
;; (A read-only file is the fixture; if this box runs as root the chmod does
;; nothing and the case is SKIPPED rather than faked — a silently-green check is
;; worse than a reported skip.)
(let [f (java.io.File/createTempFile "grog-editverify-ro" ".txt")
      table (tools/handlers (System/getProperty "java.io.tmpdir"))]
  (try
    (spit f "draft\n" :encoding "UTF-8")
    (let [writable? (.setWritable f false false)]
      (if-not writable?
        (println "  SKIP  cannot make the file read-only here (running as root?)")
        (let [r (tools/call table "edit_file" {:path (.getAbsolutePath f)
                                               :original_content "draft"
                                               :new_content "draft\nverified"})
              after (slurp f :encoding "UTF-8")]
          (check! "an unwritable file is NOT reported as a successful edit"
                  (true? (:error? r))
                  (str "error?=" (:error? r) " text=" (pr-str (first (str/split-lines (str (:text r)))))))
          (check! "and the file really is unchanged (so the check is not vacuous)"
                  (= "draft\n" after) (pr-str after)))))
    (finally
      (.setWritable f true true)
      (.delete f))))

;; --- 5. the report says WHAT it verified --------------------------------------
(println "=== 5. the report describes the effect it measured ===")
(let [r (run-edit "line1\nline2\n" "line1" "LINE-ONE")]
  (check! "the message says verified-by-reading-it-back"
          (str/includes? (:text r) "verified by reading it back")
          (pr-str (first (str/split-lines (:text r)))))
  (check! "and it names the number of replacements made"
          (str/includes? (:text r) "1 replacement(s)")
          (pr-str (first (str/split-lines (:text r))))))

(let [r (run-edit "x x x\n" "x" "y" {:all? true})]
  (check! "all_occurrences reports the real count (3), not 1"
          (str/includes? (:text r) "3 replacement(s)")
          (pr-str (first (str/split-lines (:text r))))))

;; NOTE on the anchor COUNTS: they appear in the FAILURE text ("anchor N -> M"),
;; where they are the evidence a human needs to see why a verdict was reached. They
;; are deliberately NOT printed on success — an anchor count is not actionable when
;; the edit landed, and the point of this fix is that it never decides the verdict.
;; Reaching that branch in a test would require the file to change between `spit`
;; and the read-back, which no legitimate fixture can produce; an unwritable file
;; throws at the write (case 4) and is reported by the dispatcher instead. So the
;; branch is covered by the `ok?` cases above — every one of them would have FAILED
;; under the old count-based test — rather than by a fabricated one here.

(println)
(println (if (zero? @failures)
           "=== ALL PASS"
           (str "=== " @failures " FAILURE(S)")))
(when (pos? @failures) (System/exit 1))
