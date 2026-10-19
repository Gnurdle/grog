(ns grog.agent.tool-search
  "DEFERRED TOOL LOADING — the `ToolSearch` pattern, and the first improvement
  taken from the leaked Claude Code harness (see `improvements/README.md` #1 and
  the sources filed there).

  The problem is measured, not theoretical. On 2026-10-14 this loop ran a real
  turn with 59 MCP tools + 6 local tools attached and the provider reported
  8,611 prompt tokens of which 8,384 were cached — i.e. essentially the WHOLE
  prompt was tool schemas. Every step of every turn pays that again, and on a
  typical turn the model calls two tools. Paying for 65 schemas to call 2 is the
  largest single waste in this loop.

  The fix, as Claude Code does it and as three independent write-ups recommend:
  mark the long tail of tools as DEFERRED (the model does not see them), give the
  model ONE meta-tool that searches them, and attach a matching tool's schema to
  the conversation only once the model has asked for it.

  What is searched is names AND descriptions, in plain words, because that is
  what a model has to hand: it knows it wants to \"store a memory\", not that the
  tool is called `assoc_store`. Ranking is deliberately simple and deterministic
  (exact/whole-query name hit > description hit > per-word hit) — a fuzzy
  embedding would be a second thing to be wrong, and this is a tool the model
  must be able to predict.

  This namespace holds no state and calls nothing: it scores, ranks and renders.
  The loop owns which tools are deferred and which are currently attached."
  (:require [clojure.string :as str]))

(def search-tool-name
  "The model-facing name of the meta-tool. Not `grog-mcp__`-prefixed: it is the
  loop's own tool, like `read_file`."
  "tool_search")

(defn tool-name [provider-tool]
  (get-in provider-tool [:function :name]))

(defn tool-description [provider-tool]
  (str (get-in provider-tool [:function :description] "")))

;; --- scoring -----------------------------------------------------------------

(defn- norm [s] (str/lower-case (str s)))

(defn- words [s]
  (->> (str/split (norm s) #"[^a-z0-9_]+")
       (remove str/blank?)
       vec))

(defn score
  "How well `provider-tool` answers `query`, as a non-negative integer. 0 = no
  match at all (and therefore never attached). The bands are ordered so a name
  hit always beats a description hit, and a whole-query hit beats a word hit —
  \"read an xlsx\" should find `read_office_document` before anything merely
  mentioning the word \"read\"."
  [provider-tool query]
  (let [q (norm query)
        qw (words query)
        n (norm (tool-name provider-tool))
        d (norm (tool-description provider-tool))]
    (if (str/blank? q)
      0
      (cond
        (str/includes? n q) 100
        (str/includes? d q) 40
        :else (+ (reduce + 0 (for [w qw]
                               (cond (str/includes? n w) 10
                                     (str/includes? d w) 3
                                     :else 0)))
                 ;; all words present somewhere: better than a partial word hit
                 (if (and (seq qw) (every? #(str/includes? (str n " " d) %) qw)) 5 0))))))

(defn search
  "Rank `tools` (a seq of provider tool maps) against `query`. Returns the best
  `:limit`, strongest first. A tie breaks on name so the result is stable across
  runs — a model that asks the same question twice should get the same answer."
  ([tools query] (search tools query {}))
  ([tools query {:keys [limit] :or {limit 12}}]
   (->> tools
        (keep (fn [t] (let [s (score t query)] (when (pos? (long s)) [s t]))))
        (sort-by (fn [[s t]] [(- (long s)) (str (tool-name t))]))
        (take (long limit))
        (mapv second))))

;; --- the meta-tool -----------------------------------------------------------

(defn search-tool
  "The provider-shaped schema for the meta-tool. `deferred-count` is in the
  description on purpose: the model should know there IS more toolbelt than it
  can see, otherwise `tool_search` reads as a generic search and gets ignored."
  [deferred-count]
  {:type "function"
   :function
   {:name search-tool-name
    :description
    (str "Search the " deferred-count " tool(s) that are NOT currently loaded — grog's MCP "
         "servers (memory, project search, office/xlsx, imaging/OCR, odoo, gitlab, "
         "alpaca/market data, imap, rss, web fetch, babashka…) plus anything else this "
         "session connected. Their schemas are attached only AFTER you ask for them, so the "
         "tools you can see are not the whole toolbelt. Call this with a plain-words "
         "description of what you want to do (\"store a memory\", \"read a spreadsheet\", "
         "\"search the gitlab project\") whenever the tool you need is not in your list; the "
         "matching tools become callable immediately, on the next step.")
    :parameters
    {:type "object"
     :properties
     {:query {:type "string"
              :description "what you want to do, in plain words — names and descriptions are both searched"}}
     :required ["query"]}}})

(defn- one-line [s n]
  (let [s (str/replace (str s) #"\s+" " ")]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn render-result
  "The tool RESULT the model reads. Three jobs: say what was attached, say it is
  now callable, and — when nothing matched — say what IS available, because a
  dead end with no map is how a model gives up or invents a tool name."
  [query hits remaining-names]
  (if (seq hits)
    (str (count hits) " matching tool(s) — their schemas are ATTACHED now; call them directly on your next step:\n"
         (str/join "\n" (map (fn [t] (str "- " (tool-name t) " — " (one-line (tool-description t) 110)))
                             hits)))
    (str "no deferred tool matched " (pr-str (str query)) ". "
         (count remaining-names) " tool(s) still unloaded"
         (when (seq remaining-names)
           (str ", e.g.: " (str/join ", " (take 30 remaining-names)) ", …"))
         "\nTry different words (what you want to DO), or proceed without it.")))