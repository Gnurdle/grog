(ns grog-web.md
  "Minimal CommonMark-ish renderer for the web client: Markdown string → hiccup.

  Deliberately small — it exists so ECA answers render with REAL tables (GFM pipe
  tables become <table>, not a wall of `|`) and monospace <pre>/<code> instead
  of a plain `white-space: pre-wrap` span (see
  doc/clients/web-client-plan.md §3.2). It is NOT a full spec parser.

  Block level: fenced + indented code, ATX headings, GFM pipe tables,
  bullet/ordered lists, blockquotes, horizontal rules, paragraphs.
  Inline: code spans, bold, italic, strikethrough, links, bare-URL autolinks,
  backslash escapes.

  Portable (.cljc, no reader conditionals) so the same code is unit-tested on the
  JVM with babashka and shipped to the browser build — one implementation, one
  behaviour."
  (:require [clojure.string :as str]))

;; --- wrappers ---------------------------------------------------------------

(defn- strip-wrappers
  "Drop the MIME-style <text/markdown>…</text/markdown> regions (and the legacy
  <text-markdown> forms) so the content parses as plain Markdown."
  [s]
  (str/replace (or s "") #"(?is)</?text[-/]markdown/?>" ""))

;; --- bare URLs ---------------------------------------------------------------
;; A URL that arrives as TEXT stays text — unclickable in the client and in the
;; exported HTML. This makes links out of them. It is a POST-PASS over rendered
;; hiccup rather than a rule inside the inline parser, and that is the point:
;; text inside [:a] (a link's own label), [:code] and [:pre] is skipped
;; structurally, so a URL in a code span stays code and a link label can never
;; end up as a link inside a link. `linkify` exposes the same pass for plain,
;; non-Markdown text (tool output, a user message), which never reaches the
;; parser at all.
;;
;; ONLY http(s) is matched, deliberately: `javascript:`, `data:` and `file:`
;; never turn into a clickable href out of a model's or a tool's output.

(def ^:private url-junk
  "Trailing characters that end a SENTENCE rather than a URL."
  #{\., \, \; \: \! \? \" \' \* \_ \~})

(def ^:private url-closers {\) \( \] \[ \} \{})

(defn- ws? [c] (contains? #{\space \tab \newline \return \formfeed} c))

(defn- url-stop? [c] (or (ws? c) (contains? #{\< \> \" \'} c)))

(defn- trim-url
  "Trim trailing punctuation off `(subs s i j)`, returning the new end index — or
  nil when nothing URL-shaped is left. A closing bracket survives only while it
  balances an opener INSIDE the URL: `https://x/a(b)` keeps its `)`, while
  `(https://x)` does not."
  [^String s ^long i ^long j]
  (let [floor (+ i 8)]                        ; never eat into `https://` itself
    (loop [j j]
      (if (<= j floor)
        nil
        (let [c (nth s (dec j))
              opener (get url-closers c)]
          (if (or (contains? url-junk c)
                  (and opener (let [seg (subs s i j)]
                                (< (count (filter #(= opener %) seg))
                                   (count (filter #(= c %) seg))))))
            (recur (dec j))
            j))))))

(defn- url-at
  "If a bare http(s) URL starts at `i`, return `[href end]`; else nil. The URL
  runs to the first whitespace, angle bracket or quote — which is what makes
  `<https://x>` link x without its brackets — and trailing sentence punctuation
  is trimmed off the end."
  [^String s ^long i]
  (let [c (when (< i (count s)) (nth s i))]
    (when (or (= c \h) (= c \H))
      (let [n (count s)
            head (str/lower-case (subs s i (min n (+ i 8))))]
        (when (or (str/starts-with? head "http://") (str/starts-with? head "https://"))
          (let [end (loop [k i] (if (and (< k n) (not (url-stop? (nth s k)))) (recur (inc k)) k))]
            (when-let [e (trim-url s i end)]
              [(subs s i e) e])))))))

(defn- autolink-str
  "Split a plain string into a seq of nodes: the text as it was, with each bare
  URL as an [:a]. The link's visible text is the URL itself and is NOT re-parsed
  — a URL carrying `_` or `__` must not come back as emphasis."
  [s]
  (let [s (str s) n (count s)]
    (loop [i 0 start 0 out []]
      (if (>= i n)
        (seq (if (< start n) (conj out (subs s start)) out))
        (if-let [[href ni] (url-at s i)]
          (let [out (if (< start i) (conj out (subs s start i)) out)]
            (recur ni ni (conj out [:a {:href href} href])))
          (recur (inc i) start out))))))

(def ^:private no-link-tags
  "Inside these, text is verbatim: another <a> would be a nested link, and code
  has to stay code."
  #{:a :code :pre})

(declare autolink-node)

(defn- autolink-children
  "Walk children, splicing a TEXT child into its linkified nodes (so the result
  stays flat) and walking every other node as one."
  [kids]
  (reduce (fn [acc kid]
            (if (string? kid)
              (into acc (autolink-str kid))
              (conj acc (autolink-node kid))))
          [] kids))

(defn- autolink-node [node]
  (cond
    (string? node) (autolink-str node)
    (and (vector? node) (keyword? (first node)))
    (let [[tag attrs kids] (if (map? (second node))
                             [(first node) (second node) (drop 2 node)]
                             [(first node) nil (rest node)])]
      (if (contains? no-link-tags tag)
        node
        (if attrs
          (into [tag attrs] (autolink-children kids))
          (into [tag] (autolink-children kids)))))
    (sequential? node) (mapv autolink-node node)
    :else node))

(defn linkify
  "Bare URLs in PLAIN text (a tool result, a user message) → clickable [:a]
  nodes. Nothing else is interpreted — no emphasis, no code spans — because tool
  output is not Markdown and restyling it would be a bug. Returns a seq of
  strings and nodes."
  [s]
  (autolink-str (or s "")))

;; --- inline -----------------------------------------------------------------

(defn- alnum? [c]
  (boolean (and c (re-matches #"[A-Za-z0-9]" (str c)))))

(defn- code-span
  "Build a [:code …] from the raw span body: newlines become spaces, and one
  leading+trailing space is trimmed when both are present (CommonMark rule)."
  [t]
  (let [t (str/replace t "\n" " ")
        t (if (and (>= (count t) 2) (= \space (first t)) (= \space (last t)))
            (subs t 1 (dec (count t)))
            t)]
    [:code t]))

(declare inline)

(defn- inline-token
  "If `s` at index `i` starts an inline construct, return [node next-index];
  else nil. Node is hiccup or a raw string (for escapes)."
  [^String s ^long i]
  (let [n (count s)
        two (when (<= (+ i 2) n) (subs s i (+ i 2)))
        one (when (< i n) (subs s i (inc i)))
        space? (fn [k] (and (< k n) (= \space (nth s k))))]
    (cond
      ;; backslash escape
      (and (= one "\\") (< (inc i) n))
      [(subs s (inc i) (+ i 2)) (+ i 2)]

      (= two "**")
      (when-let [j (str/index-of s "**" (+ i 2))]
        (when (and (> j (+ i 2)) (not (space? (inc i))) (not (space? (dec j))))
          [(into [:strong] (inline (subs s (+ i 2) j))) (+ j 2)]))

      ;; `__` strong obeys the SAME intraword rule as `_` below — a `__` between
      ;; two alphanumerics is not emphasis. Without this, `server__tool` MCP names
      ;; (eca__read_file, grog-mcp__assoc_store) parse their `__` as <strong> and
      ;; the names are mangled — in the live client and in the HTML export.
      (= two "__")
      (when (and (< (+ i 2) n)
                 (or (zero? i) (not (alnum? (nth s (dec i)))))
                 (not (space? (+ i 2))))
        (when-let [j (str/index-of s "__" (+ i 2))]
          (when (and (> j (+ i 2))
                     (not (space? (dec j)))
                     (or (>= (+ j 2) n) (not (alnum? (nth s (+ j 2))))))
            [(into [:strong] (inline (subs s (+ i 2) j))) (+ j 2)])))

      (= two "~~")
      (when-let [j (str/index-of s "~~" (+ i 2))]
        (when (> j (+ i 2))
          [(into [:del] (inline (subs s (+ i 2) j))) (+ j 2)]))

      (= one "`")
      (let [run  (loop [k i] (if (and (< k n) (= \` (nth s k))) (recur (inc k)) (- k i)))
            tick (apply str (repeat run "`"))]
        (when-let [j (str/index-of s tick (+ i run))]
          [(code-span (subs s (+ i run) j)) (+ j run)]))

      (= one "*")
      (when-let [j (str/index-of s "*" (inc i))]
        (when (and (> j (inc i)) (not (space? (inc i))) (not (space? (dec j))))
          [(into [:em] (inline (subs s (inc i) j))) (inc j)]))

      ;; underscore emphasis must not fire inside a word (snake_case stays put):
      ;; open only at a word boundary, close only before a non-word char
      (= one "_")
      (when (and (or (zero? i) (not (alnum? (nth s (dec i)))))
                 (not (space? (inc i))))
        (when-let [j (str/index-of s "_" (inc i))]
          (when (and (> j (inc i))
                     (not (space? (dec j)))
                     (or (>= (inc j) n) (not (alnum? (nth s (inc j))))))
            [(into [:em] (inline (subs s (inc i) j))) (inc j)])))

      ;; image ![alt](src) — MUST precede the link branch (the '[' is preceded by
      ;; '!'). Rendered as a bare <img>: NOT wrapped in an <a>, so clicking it
      ;; can't navigate the window away (which looked like a crash).
      (and (= one "!") (< (inc i) n) (= "[" (subs s (inc i) (+ i 2))))
      (when-let [j (str/index-of s "](" (+ i 2))]
        (when-let [k (str/index-of s ")" (+ j 2))]
          [[:img {:src (str/trim (subs s (+ j 2) k))
                  :alt (str/trim (subs s (+ i 2) j))}]
           (inc k)]))

      (= one "[")
      (when-let [j (str/index-of s "](" (inc i))]
        (when-let [k (str/index-of s ")" (+ j 2))]
          [(into [:a {:href (str/trim (subs s (+ j 2) k))}]
                 (inline (subs s (inc i) j)))
           (inc k)]))

      :else nil)))

(defn- inline
  "Flatten a string of inline Markdown into a seq of hiccup nodes / strings."
  [s]
  (let [s (str s) n (count s)]
    (loop [i 0 start 0 out []]
      (if (>= i n)
        (if (< start n) (conj out (subs s start)) out)
        (if-let [[node ni] (inline-token s i)]
          (let [out (if (< start i) (conj out (subs s start i)) out)]
            (recur ni ni (conj out node)))
          (recur (inc i) start out))))))

;; --- block helpers ----------------------------------------------------------

(def ^:private fence-re   #"^\s{0,3}(`{3,}|~{3,})\s*([^`]*)$")
(def ^:private heading-re #"^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$")
(def ^:private hr-re      #"^\s{0,3}(?:(?:-\s*){3,}|(?:\*\s*){3,}|(?:_\s*){3,})$")
(def ^:private bullet-re  #"^\s{0,3}[-*+]\s+(.*)$")
(def ^:private ol-re      #"^\s{0,3}\d{1,9}[.)]\s+(.*)$")
(def ^:private quote-re   #"^\s{0,3}>\s?(.*)$")

(defn- fence-open [line]
  (when-let [m (re-matches fence-re line)]
    {:marker (nth m 1) :info (str/trim (nth m 2))}))

(defn- fence-close-re [{:keys [marker]}]
  (let [c (subs marker 0 1)]
    (re-pattern (str "^\\s{0,3}" c "{3,}\\s*$"))))

(defn- pipe-row? [line]
  (and (not (str/blank? line)) (str/includes? line "|")))

(defn- cell-text [s]
  (str/replace (str/trim s) "\\|" "|"))

(defn- split-cells
  "Split a table row into trimmed cells, dropping the outer pipes. Splits on
  unescaped `|`, but NOT on a pipe inside a `code span` (models routinely write
  `` `a|b` `` in a cell without escaping — GFM says you must, we're lenient);
  honours `\\|` escapes. `line` may be nil (callers pass the *next* line, which
  doesn't exist on the last row)."
  [line]
  (let [l (str/trim (str line))
        l (if (str/starts-with? l "|") (subs l 1) l)
        l (if (str/ends-with? l "|") (subs l 0 (dec (count l))) l)
        n (count l)]
    (loop [i 0 start 0 in-code? false out []]
      (if (>= i n)
        (conj out (cell-text (subs l start)))
        (let [c (nth l i)]
          (cond
            (= c \`)    (recur (inc i) start (not in-code?) out)
            (= c \\)    (recur (+ i 2) start in-code? out)   ; skip escaped char
            (and (= c \|) (not in-code?))
            (recur (inc i) (inc i) in-code? (conj out (cell-text (subs l start i))))
            :else       (recur (inc i) start in-code? out)))))))

(defn- delimiter-row? [line]
  (let [cells (split-cells line)]
    (and (seq cells)
         (every? (fn [c] (boolean (re-matches #":?-{1,}:?" (str/replace c " " "")))) cells))))

(defn- align-of [c]
  (let [c (str/replace c " " "")]
    (cond (and (str/starts-with? c ":") (str/ends-with? c ":")) :center
          (str/starts-with? c ":") :left
          (str/ends-with? c ":") :right
          :else nil)))

;; --- block parser -----------------------------------------------------------

(declare parse-blocks)

(defn- collect
  "Split `ls` into (take-while pred ls) and the remainder."
  [pred ls]
  (loop [taken [] ls ls]
    (if (and (seq ls) (pred (first ls)))
      (recur (conj taken (first ls)) (rest ls))
      [taken ls])))

(defn- parse-blocks [text]
  (let [lines (str/split (str text) #"\n" -1)]
    (loop [ls lines out []]
      (if-not (seq ls)
        out
        (let [line (first ls)
              more (rest ls)
              nxt  (first more)]
          (cond
            (str/blank? line)
            (recur more out)

            ;; fenced code
            (fence-open line)
            (let [fo (fence-open line)
                  close? (fence-close-re fo)
                  [body rest-ls] (collect #(not (re-matches close? %)) more)
                  rest-ls (if (seq rest-ls) (rest rest-ls) rest-ls)]
              (recur rest-ls (conj out [:pre [:code (str/join "\n" body)]])))

            ;; ATX heading
            (re-matches heading-re line)
            (let [m (re-matches heading-re line)
                  lvl (count (nth m 1))
                  txt (nth m 2)]
              (recur more (conj out (into [(keyword (str "h" lvl))] (inline txt)))))

            ;; GFM pipe table (header line + delimiter row)
            (and (pipe-row? line) (delimiter-row? nxt))
            (let [head (split-cells line)
                  aligns (mapv align-of (split-cells nxt))
                  [rows rest-ls] (collect pipe-row? more)
                  body (rest rows)        ; drop the delimiter row
                  cell (fn [tag c a]
                         (if a
                           (into [tag {:style {:text-align (name a)}}] (inline c))
                           (into [tag] (inline c))))]
              (recur rest-ls
                     (conj out
                           [:table
                            [:thead
                             (into [:tr] (mapv (fn [i c] (cell :th c (nth aligns i nil)))
                                               (range) head))]
                            (into [:tbody]
                                  (mapv (fn [r]
                                          (into [:tr]
                                                (mapv (fn [i c] (cell :td c (nth aligns i nil)))
                                                      (range) (split-cells r))))
                                        body))])))

            ;; horizontal rule (after lists so `- - -` is hr, `- item` is a list)
            (re-matches hr-re line)
            (recur more (conj out [:hr]))

            ;; unordered list
            (re-matches bullet-re line)
            (let [[items rest-ls] (collect #(re-matches bullet-re %) (cons line more))]
              (recur rest-ls
                     (conj out (into [:ul]
                                     (mapv (fn [l] (into [:li] (inline (nth (re-matches bullet-re l) 1))))
                                           items)))))

            ;; ordered list
            (re-matches ol-re line)
            (let [[items rest-ls] (collect #(re-matches ol-re %) (cons line more))]
              (recur rest-ls
                     (conj out (into [:ol]
                                     (mapv (fn [l] (into [:li] (inline (nth (re-matches ol-re l) 1))))
                                           items)))))

            ;; blockquote
            (re-matches quote-re line)
            (let [[items rest-ls] (collect #(re-matches quote-re %) (cons line more))
                  inner (str/join "\n" (map #(nth (re-matches quote-re %) 1) items))]
              (recur rest-ls (conj out (into [:blockquote] (parse-blocks inner)))))

            ;; paragraph — swallow lines until a blank line or another block start
            :else
            (let [starts? (fn [l n]
                            (or (str/blank? l) (fence-open l) (re-matches heading-re l)
                                (re-matches hr-re l) (re-matches bullet-re l)
                                (re-matches ol-re l) (re-matches quote-re l)
                                (and (pipe-row? l) (delimiter-row? n))))
                  [para rest-ls]
                  (loop [cur (cons line more) acc []]
                    (if-not (seq cur)
                      [acc []]
                      (let [l (first cur)]
                        (if (starts? l (second cur))
                          [acc cur]
                          (recur (rest cur) (conj acc l))))))]
              (recur rest-ls (conj out (into [:p] (inline (str/join " " para))))))))))))

;; --- entry ------------------------------------------------------------------

(defn ->hiccup
  "Render `md` (a Markdown string) to a vector of hiccup blocks, with bare URLs
  in text turned into links. `nil` is treated as empty (a transcript segment can
  carry no text at all)."
  [md]
  (mapv autolink-node (parse-blocks (strip-wrappers (or md "")))))
