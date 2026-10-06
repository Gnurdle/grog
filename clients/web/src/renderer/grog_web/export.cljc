(ns grog-web.export
  "Standalone HTML dump of a whole session.

  The point: hand a conversation to somebody — including ANOTHER grog — as ONE
  self-contained `.html` file. No JavaScript, no external assets, no fonts to
  fetch: it opens anywhere, offline, forever.

  Shape: COMPRESSED BY DEFAULT. The readable layer is the user prompts and the
  assistant's answers (Markdown, tables preserved); everything else — reasoning,
  tool calls, status/log noise — is folded into native `<details>` so the page
  reads as a conversation with the depth one click away.

  Pure: takes a plain session map, returns a string. That makes it testable on
  the JVM with babashka exactly like `grog-web.md`, with no browser involved."
  (:require [clojure.string :as str]
            [grog-web.md :as md]))

;; --- HTML primitives --------------------------------------------------------

(defn- esc
  "HTML-escape a value for text or attribute context."
  [x]
  (-> (str x)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(def ^:private void-tags
  "Elements with no closing tag."
  #{"img" "br" "hr" "input" "meta" "link"})

(defn- style->css
  "A hiccup `:style` map as a css declaration list."
  [m]
  (str/join ";" (for [[k v] m] (str (name k) ":" v))))

(defn hiccup->html
  "Serialize the subset of hiccup `grog-web.md` produces to an HTML string.

  NOT a general hiccup renderer — it covers what the Markdown pipeline emits
  (element vectors with a leading attribute map, `:style` maps, void elements,
  and a top-level SEQUENCE of elements) and always escapes text.

  The sequence/element distinction matters: `grog-web.md/->hiccup` returns a
  vector OF elements, so a vector whose first item is not a keyword is a list of
  children, not an element — treating it as an element would crash on `name`."
  [h]
  (cond
    (nil? h) ""
    (or (string? h) (number? h) (boolean? h)) (esc h)
    (and (vector? h) (keyword? (first h)))
    (let [[tag & body] h
          [attrs body] (if (map? (first body)) [(first body) (rest body)] [{} body])
          t (name tag)
          attr-str (apply str
                          (for [[k v] attrs
                                :when (and v (not (false? v)) (not= "" v))]
                            (str " " (name k) "=\""
                                 (esc (if (= :style k) (style->css v) v)) "\"")))]
      (if (contains? void-tags t)
        (str "<" t attr-str "/>")
        (str "<" t attr-str ">" (apply str (map hiccup->html body)) "</" t ">")))
    (sequential? h) (apply str (map hiccup->html h))
    :else (esc h)))

(defn- md-html
  "Markdown source -> HTML. A dump must NEVER fail because of one odd turn, so
  a parser failure falls back to an escaped <pre>."
  [text]
  (try
    (hiccup->html (md/->hiccup (str text)))
    (catch #?(:clj Throwable :cljs :default) _ (str "<pre>" (esc text) "</pre>"))))

(defn- fold
  "A native <details> block — no JS, works offline."
  [summary inner]
  (str "<details><summary>" (esc summary) "</summary>\n" inner "\n</details>\n"))

;; --- segment blocks ---------------------------------------------------------

(defn- think-block [run]
  (let [txt (str/join "\n" (map :text run))
        n (count (str/split-lines txt))]
    (fold (str "▸ thinking — " n " line" (when (not= 1 n) "s"))
          (str "<div class=\"think\">" (md-html txt) "</div>"))))

(defn- tool-block [run]
  (fold (str "▸ tool calls (" (count run) ")")
        (str "<div class=\"tools\">"
             (apply str (for [s run] (str "<div class=\"tool\">" (esc (:text s)) "</div>")))
             "</div>")))

(defn- log-block [run]
  (fold (str "▸ session log (" (count run) ")")
        (str "<div class=\"log\">"
             (apply str (for [s run] (str "<div>" (esc (:text s)) "</div>")))
             "</div>")))

(defn- image-block [{:keys [media_type base64 text]}]
  (if (seq (str base64))
    (str "<figure><img alt=\"" (esc (or text "image")) "\" src=\"data:"
         (esc (or media_type "image/png")) ";base64," (esc base64) "\"/></figure>")
    (str "<p class=\"tool\">" (esc text) "</p>")))

(def ^:private foldable
  "Kinds whose CONSECUTIVE runs fold into one <details>."
  #{:thinking :tool :line :usage})

(defn- run-key
  "Partition key: foldable kinds group by kind; everything else is its own
  group (so two adjacent answers with different text never merge)."
  [s]
  (let [k (:kind s)]
    (if (contains? foldable k) [k] [::solo k (:text s)])))

(defn- run-html [run]
  (let [k (:kind (first run))]
    (case k
      :thinking (think-block run)
      :tool     (tool-block run)
      (:line :usage) (log-block run)
      :image    (image-block (first run))
      :snark    (str "<div class=\"snark\">" (esc (str/join "\n" (map :text run))) "</div>")
      :user     (str "<div class=\"turn user\"><span class=\"who\">user</span>"
                     "<div class=\"body md\">" (md-html (str/join "\n\n" (map :text run))) "</div></div>")
      :answer   (str "<div class=\"turn answer\"><span class=\"who\">grog</span>"
                     "<div class=\"body md\">" (md-html (str/join "\n\n" (map :text run))) "</div></div>")
      (str "<div class=\"tool\">" (esc (str/join "\n" (map :text run))) "</div>"))))

;; --- the document -----------------------------------------------------------

(def ^:private css
  (str ":root{color-scheme:dark;--bg:#0e0e0e;--fg:#e2e8f0;--dim:#94a3b8;"
       "--line:#1e293b;--card:#141414;--user:#7dd3fc;--answer:#e2e8f0;"
       "--think:#a78bfa;--tool:#facc15;--snark:#2ec7cd;--mono:ui-monospace,"
       "SFMono-Regular,Menlo,Consolas,monospace}"
       "*{box-sizing:border-box}"
       "body{margin:0;background:var(--bg);color:var(--fg);"
       "font:15px/1.55 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}"
       "header{padding:1.2rem 1.5rem;border-bottom:1px solid var(--line)}"
       "header h1{margin:0 0 .35rem;font-size:1.05rem}"
       "header .meta{color:var(--dim);font-size:.78rem;"
       "font-family:var(--mono);display:flex;flex-wrap:wrap;gap:.25rem 1rem}"
       "main{max-width:60rem;margin:0 auto;padding:1.5rem;"
       "display:flex;flex-direction:column;gap:.9rem}"
       ".turn{display:flex;gap:.75rem}"
       ".turn .who{flex:0 0 3.5rem;font-size:.7rem;text-transform:uppercase;"
       "letter-spacing:.06em;padding-top:.2rem;color:var(--dim)}"
       ".turn .body{flex:1;min-width:0}"
       ".turn.user .who{color:var(--user)}"
       ".turn.user .body{color:var(--user)}"
       ".turn.answer .who{color:var(--answer)}"
       ".snark{text-align:center;color:var(--snark);font-style:italic;font-size:.85rem;opacity:.8}"
       "details{margin:.1rem 0 .1rem 4.25rem;border-left:2px solid var(--line);padding-left:.75rem}"
       "summary{cursor:pointer;color:var(--dim);font-size:.78rem;font-family:var(--mono)}"
       "summary:hover{color:var(--fg)}"
       ".think{color:var(--think);opacity:.85;font-size:.86rem;padding:.4rem 0}"
       ".tools,.log{font-family:var(--mono);font-size:.78rem;color:var(--tool);"
       "max-height:22rem;overflow:auto;padding:.4rem 0;white-space:pre-wrap}"
       ".log{color:var(--dim)}"
       "figure{margin:.2rem 0}"
       "figure img{max-width:100%;height:auto;border:1px solid var(--line);border-radius:.4rem}"
       ".md h1,.md h2,.md h3{font-size:1.05rem;margin:.6rem 0 .3rem}"
       ".md p{margin:.35rem 0}"
       ".md code{font-family:var(--mono);font-size:.85em;background:#1b1b1b;"
       "padding:.08em .3em;border-radius:.25rem}"
       ".md pre{background:var(--card);border:1px solid var(--line);border-radius:.4rem;"
       "padding:.6rem .75rem;overflow-x:auto}"
       ".md pre code{background:none;padding:0}"
       ".md table{border-collapse:collapse;margin:.5rem 0;display:block;overflow-x:auto}"
       ".md th,.md td{border:1px solid var(--line);padding:.3rem .55rem}"
       ".md th{background:#161616;text-align:left}"
       ".md blockquote{margin:.4rem 0;padding-left:.75rem;border-left:3px solid var(--line);color:var(--dim)}"
       ".md ul,.md ol{margin:.35rem 0;padding-left:1.3rem}"
       ".md hr{border:0;border-top:1px solid var(--line)}"
       ".md a{color:var(--user)}"
       "footer{color:var(--dim);font-size:.72rem;text-align:center;padding:1.5rem;"
       "font-family:var(--mono)}"))

(defn- meta-item [k v]
  (when (and v (not (str/blank? (str v))))
    (str "<span>" (esc k) ": " (esc v) "</span>")))

(defn- usage-line
  "A compact `k=v · …` line from the session's usage map (defensive: shapes vary)."
  [usage]
  (when (map? usage)
    (let [pairs (->> usage
                     (remove (fn [[k v]] (or (= k :type) (nil? v)
                                             (not (or (string? v) (number? v))))))
                     (sort-by (comp str key))
                     (map (fn [[k v]] (str (name k) "=" v))))]
      (when (seq pairs) (str/join " · " pairs)))))

(defn ->standalone-html
  "The whole dump, as one self-contained HTML string.

  `session` keys: :project :session-id :model :version :segments :usage
  :exported-at (a display string; the FILE name is the caller's business, so
  this stays free of clocks and filesystems)."
  [{:keys [project session-id model version segments usage exported-at]}]
  (let [segs (vec (or segments []))
        ats (keep :at segs)
        started (first ats)
        ended (last ats)
        n-user (count (filter #(= :user (:kind %)) segs))
        n-ans (count (filter #(= :answer (:kind %)) segs))
        body (apply str (for [run (partition-by run-key segs)] (run-html run)))]
    (str
     "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"/>"
     "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>"
     "<title>" (esc (str (or project "session") " — grog session")) "</title>"
     "<style>" css "</style></head><body>"
     "<header><h1>" (esc (or project "session")) " — grog session</h1>"
     "<div class=\"meta\">"
     (meta-item "session" session-id)
     (meta-item "model" model)
     (meta-item "grog" version)
     (meta-item "turns" (str n-user " prompt" (when (not= 1 n-user) "s")
                             ", " n-ans " answer" (when (not= 1 n-ans) "s")))
     (meta-item "started" (when started (str started)))
     (meta-item "ended" (when ended (str ended)))
     (meta-item "exported" exported-at)
     "</div>"
     (when-let [u (usage-line usage)]
       (str "<div class=\"meta\">" (meta-item "usage" u) "</div>"))
     "</header>"
     "<main>" body "</main>"
     "<footer>dumped from grog — self-contained; folding needs no JavaScript</footer>"
     "</body></html>\n")))
