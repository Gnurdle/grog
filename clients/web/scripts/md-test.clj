#!/usr/bin/env bb
;; Offline test for grog-web.md — the JVM route for this .cljc (no browser, no
;; shadow-cljs build needed).
;;
;;   bb clients/web/scripts/md-test.clj
;;
;; CI runs it in the "Offline JS tests" step beside the node scripts. It guards
;; the bare-URL autolinker, whose edge cases — sentence punctuation, balanced
;; brackets, code spans, a link inside a link label — are exactly the kind that
;; rot silently and only show up as a mangled transcript.
(require '[clojure.java.io :as io])

;; Resolve md.cljc relative to THIS file, so the working directory doesn't matter.
(def ^:private md-file
  (-> *file* io/file .getCanonicalFile .getParentFile .getParentFile
      (io/file "src/renderer/grog_web/md.cljc")))

(load-file (str md-file))
(alias 'md 'grog-web.md)

(def ^:private failures (atom 0))

(defn- hrefs
  "Every <a href> in a rendered form, in document order."
  [form]
  (->> (tree-seq coll? seq form)
       (filter #(and (vector? %) (= :a (first %))))
       (mapv (comp :href second))))

(defn- text-nodes
  "Text nodes of a rendered form, WALKING ONLY CHILDREN — attribute maps are not
  text, and a href read as text would double-count a link."
  [form]
  (cond
    (string? form) [form]
    (and (vector? form) (keyword? (first form)))
    (mapcat text-nodes (if (map? (second form)) (drop 2 form) (rest form)))
    (coll? form) (mapcat text-nodes form)
    :else []))

(defn- text
  "All text nodes of a rendered form, concatenated (what the reader sees, minus
  markup)."
  [form]
  (apply str (text-nodes form)))

(defn- check [label expected actual]
  (let [ok? (= expected actual)]
    (when-not ok? (swap! failures inc))
    (println (if ok? "  ok   " "  FAIL ") label)
    (when-not ok?
      (println "         expected:" (pr-str expected))
      (println "         actual:  " (pr-str actual)))))

(println "grog-web.md — bare-URL autolinking")

;; --- the basic move ----------------------------------------------------------
(check "a bare URL in a paragraph becomes a link"
       ["https://example.com/a"]
       (hrefs (md/->hiccup "see https://example.com/a now")))

(check "two URLs on one line"
       ["http://a.example" "https://b.example"]
       (hrefs (md/->hiccup "http://a.example and https://b.example")))

(check "URL in a table cell links"
       ["https://x.example"]
       (hrefs (md/->hiccup "| a | b |\n| --- | --- |\n| https://x.example | y |")))

;; --- sentence punctuation ----------------------------------------------------
(check "a trailing period is not part of the URL"
       ["https://example.com/a"]
       (hrefs (md/->hiccup "see https://example.com/a.")))

(check "…and the period is still shown"
       "see https://example.com/a."
       (text (md/->hiccup "see https://example.com/a.")))

(check "an enclosing paren is not part of the URL"
       ["http://x.example/b"]
       (hrefs (md/->hiccup "(http://x.example/b)")))

(check "a BALANCED paren inside the URL is kept"
       ["https://en.wikipedia.org/wiki/X_(Y)"]
       (hrefs (md/->hiccup "https://en.wikipedia.org/wiki/X_(Y)")))

(check "a trailing comma and semicolon are dropped"
       ["https://example.com/a"]
       (hrefs (md/->hiccup "https://example.com/a, then;")))

(check "the angle-bracket form links the URL without its brackets"
       ["https://github.com/Gnurdle/grog/actions/runs/1"]
       (hrefs (md/->hiccup "run <https://github.com/Gnurdle/grog/actions/runs/1> ok")))

;; --- what must NOT be touched ------------------------------------------------
(check "a URL in a code span stays code"
       []
       (hrefs (md/->hiccup "`https://x.example`")))

(check "…verbatim"
       "https://x.example"
       (text (md/->hiccup "`https://x.example`")))

(check "a URL in a fenced block stays code"
       []
       (hrefs (md/->hiccup "```\nhttps://x.example\n```")))

(check "a link's LABEL does not become a link inside a link"
       1
       (count (hrefs (md/->hiccup "[https://a.example](https://b.example)"))))

(check "…and the outer destination wins"
       ["https://b.example"]
       (hrefs (md/->hiccup "[https://a.example](https://b.example)")))

(check "javascript:/data:/file: are never turned into links"
       []
       (hrefs (md/->hiccup "javascript:alert(1) data:text/html,x file:///etc/passwd")))

;; --- neighbours that must keep working ---------------------------------------
(check "bold text containing a URL is still bold AND linked"
       ["https://x.example"]
       (hrefs (md/->hiccup "**https://x.example**")))

(check "MCP tool names are still plain (no emphasis, no link)"
       "grog-mcp__assoc_store and eca__read_file"
       (text (md/->hiccup "grog-mcp__assoc_store and eca__read_file")))

(check "a URL with __ in it is not emphasised"
       ["https://x.example/a_b__c"]
       (hrefs (md/->hiccup "https://x.example/a_b__c")))

;; --- linkify: plain, non-Markdown text (tool output, user messages) ----------
(check "linkify keeps every other byte, whitespace included"
       "Saved to /tmp/a.png\nhttps://x.example/y\n"
       (text (md/linkify "Saved to /tmp/a.png\nhttps://x.example/y\n")))

(check "linkify links exactly the URL"
       ["https://x.example/y"]
       (hrefs (md/linkify "Saved to /tmp/a.png\nhttps://x.example/y\n")))

(check "linkify does NOT interpret Markdown"
       "**not bold** `not code`"
       (text (md/linkify "**not bold** `not code`")))

(check "linkify of nil/empty yields nothing"
       nil
       (md/linkify nil))

(println)
(if (zero? @failures)
  (println "md-test: all checks passed")
  (println "md-test:" @failures "FAILED"))
(System/exit (if (zero? @failures) 0 1))
