(ns grog.md-render
  "CommonMark → ANSI for terminal display (matches grog.core answer / thinking palette).
  Markdown may be wrapped in <text/markdown>…</text/markdown> or <text/markdown>…<text/markdown/>
  (MIME-style, RFC 7763). Legacy <text-markdown>…</text-markdown> is normalized to the same.
  GFM pipe tables render as Unicode box tables."
  (:require [clojure.string :as str])
  (:import [java.util Arrays]
           [org.jline.utils WCWidth]
           [org.commonmark.ext.gfm.tables TableBlock TableBody TableCell TableCell$Alignment
            TableHead TableRow TablesExtension]
           [org.commonmark.node BlockQuote BulletList Code Document Emphasis
            FencedCodeBlock HardLineBreak Heading HtmlBlock HtmlInline IndentedCodeBlock
            Link ListBlock ListItem Node OrderedList Paragraph SoftLineBreak StrongEmphasis Text
            ThematicBreak]
           [org.commonmark.parser Parser]))

(def ^:private reset "\u001B[0m")
(def ^:private bold "\u001B[1m")
(def ^:private dim "\u001B[2m")
(def ^:private italic "\u001B[3m")
(def ^:private body "\u001B[38;2;100;220;255m")
(def ^:private head "\u001B[1m\u001B[38;2;150;235;255m")
(def ^:private code-style "\u001B[38;2;110;205;245m\u001B[48;2;24;42;52m")
(def ^:private quote-style "\u001B[38;2;80;185;215m")
(def ^:private link-url "\u001B[2m\u001B[38;2;70;175;205m")

(def ^:private ^String open-marker "<text/markdown>")
(def ^:private ^String close-std "</text/markdown>")
(def ^:private ^String close-alt "<text/markdown/>")

(defn- normalize-legacy-markdown-tags
  "Map old <text-markdown> delimiters to <text/markdown> (order matters: self-close before open tag)."
  ^String [^String s]
  (-> s
      (str/replace "<text-markdown/>" close-alt)
      (str/replace "</text-markdown>" close-std)
      (str/replace "<text-markdown>" open-marker)))

(defn- make-parser []
  (let [exts (Arrays/asList (into-array [(TablesExtension/create)]))]
    (.build (.extensions (Parser/builder) exts))))

(defn- next-close-index [^String s ^long from]
  (let [a (str/index-of s close-std from)
        b (str/index-of s close-alt from)]
    (cond (and a b) (min a b)
          a a
          b b
          :else nil)))

(defn- close-tag-len [^String s ^long idx]
  (cond (str/starts-with? (subs s idx) close-std) (count close-std)
        (str/starts-with? (subs s idx) close-alt) (count close-alt)
        :else (count close-std)))

(defn- split-text-markdown-segments
  "Returns a vector of plain or markdown segments in document order."
  [^String s]
  (let [n (count s)]
    (loop [i 0, acc []]
      (if (>= i n)
        acc
        (let [open-idx (str/index-of s open-marker i)]
          (if (nil? open-idx)
            (conj acc [:plain (subs s i)])
            (let [acc1 (if (> open-idx i) (conj acc [:plain (subs s i open-idx)]) acc)
                  content-start (+ open-idx (count open-marker))
                  close-idx (next-close-index s content-start)]
              (if (nil? close-idx)
                (conj acc1 [:md (str/trim (subs s content-start))])
                (let [inner (str/trim (subs s content-start close-idx))
                      j (+ close-idx (long (close-tag-len s close-idx)))]
                  (recur j (conj acc1 [:md inner])))))))))))

(defn- iter-children [^Node parent walk-fn]
  (loop [^Node c (.getFirstChild parent)]
    (when c
      (let [^Node nxt (.getNext c)]
        (walk-fn c)
        (recur nxt)))))

(defn node-children
  "Children of a CommonMark node as a seq (empty when it's a leaf)."
  [^Node parent]
  (persistent!
    (loop [^Node c (.getFirstChild parent) v (transient [])]
      (if c
        (let [^Node nxt (.getNext c)]
          (recur nxt (conj! v c)))
        v))))

(defn- strip-markdown-tags
  "Remove `<text/markdown>` / `</text/markdown>` / `<text/markdown/>` wrappers
  (and the legacy `<text-markdown>` forms). Returns the bare content."
  ^String [^String s]
  (str/replace s
               #"(?i)</?text[-/]markdown/?>|</text[-/]markdown>"
               ""))

(declare rewrite-single-backtick-code-with-newlines table-block-rows normalize-row-widths)

(defn table-rows
  "Public: a normalized grid for a GFM table node. Returns a vector of
   `{:header? bool :cells [..]}`, one entry per row, each cell a plain string
   (markup flattened). Column count is padded to be consistent."
  [^TableBlock block]
  (let [raw (vec (table-block-rows block))
        rows (if (empty? raw) [] (normalize-row-widths raw))]
    (mapv (fn [r]
            {:header? (:header? r)
             :cells   (mapv (fn [p] {:text (:text p) :align (:align p)}) (:parts r))})
          rows)))

(defn parse!
  "Public entrypoint: parse `markdown` as CommonMark (with GFM tables), stripping
  any `<text/markdown>` MIME-style wrappers before parsing so the caller gets a
  clean AST. Returns the root `org.commonmark.node.Document`. On parse failure
  returns an empty Document (never throws)."
  ^org.commonmark.node.Document [^String markdown]
  (try
    (let [s (strip-markdown-tags (or markdown ""))]
      (.parse (make-parser) (rewrite-single-backtick-code-with-newlines s)))
    (catch Exception _
      (.parse (make-parser) ""))))

(defn- align-kw [^TableCell cell]
  (let [^TableCell$Alignment a (.getAlignment cell)]
    (cond (identical? a TableCell$Alignment/LEFT) :left
          (identical? a TableCell$Alignment/CENTER) :center
          (identical? a TableCell$Alignment/RIGHT) :right
          :else :left)))

(defn- collect-inline-text [^Node n ^StringBuilder sb]
  (condp instance? n
    Text (.append sb (.getLiteral ^Text n))
    Code (.append sb (.getLiteral ^Code n))
    SoftLineBreak (.append sb " ")
    HardLineBreak (.append sb " ")
    (iter-children n #(collect-inline-text % sb))))

(defn- cell-plain [^TableCell cell]
  (let [sb (StringBuilder.)]
    (iter-children cell #(collect-inline-text % sb))
    (str/trim (str/replace (str sb) #"[\r\n\t ]+" " "))))

(defn- row-parts [^TableRow row]
  (vec (loop [^Node c (.getFirstChild row) out []]
         (if c
           (if (instance? TableCell c)
             (recur (.getNext c)
                    (conj out {:text (cell-plain c)
                               :align (align-kw c)
                               :header? (.isHeader ^TableCell c)}))
             (recur (.getNext c) out))
           out))))

(defn- table-block-rows [^TableBlock block]
  (let [rows (volatile! [])]
    (iter-children
     block
     (fn [^Node ch]
       (cond (instance? TableHead ch)
             (iter-children ch
                            (fn [^Node n]
                              (when (instance? TableRow n)
                                (vswap! rows conj {:header? true :parts (row-parts n)}))))
             (instance? TableBody ch)
             (iter-children ch
                            (fn [^Node n]
                              (when (instance? TableRow n)
                                (vswap! rows conj {:header? false :parts (row-parts n)}))))
             (instance? TableRow ch)
             (vswap! rows conj {:header? false :parts (row-parts ch)})
             :else nil)))
    @rows))

(defn- display-width
  "Terminal display width (emoji / CJK / etc.), not Java `String.length`."
  ^long [^String s]
  (loop [i 0 w 0]
    (if (>= i (.length s))
      w
      (let [cp (.codePointAt s i)
            step (long (Character/charCount cp))
            cw (long (WCWidth/wcwidth cp))]
        (recur (+ i step) (+ w (max 0 cw)))))))

(defn- truncate-to-display-width
  ^String [^String s ^long max-dw]
  (if (<= (display-width s) max-dw)
    s
    (let [sb (StringBuilder.)]
      (loop [i 0 dw 0]
        (if (>= i (.length s))
          (str sb)
          (let [cp (.codePointAt s i)
                step (long (Character/charCount cp))
                cw (long (max 0 (WCWidth/wcwidth cp)))
                ndw (+ dw cw)]
            (if (> ndw max-dw)
              (str sb)
              (do (.appendCodePoint sb (int cp))
                  (recur (+ i step) ndw)))))))))

(defn- pad-cell [text width align]
  (let [^String t0 (str text)
        w (long width)]
    (if (> (display-width t0) w)
      (truncate-to-display-width t0 w)
      (let [sp (- w (display-width t0))]
        (case align
          :left (str t0 (str/join (repeat sp " ")))
          :right (str (str/join (repeat sp " ")) t0)
          :center (let [l (quot sp 2) r (- sp l)]
                    (str (str/join (repeat l " ")) t0 (str/join (repeat r " ")))))))))

(defn- column-widths [normalized-rows]
  (let [ncols (apply max 0 (map (comp count :parts) normalized-rows))]
    (vec (for [i (range ncols)]
           (apply max 0
                  (map (fn [r]
                         (display-width
                          (or (get-in r [:parts i :text]) "")))
                       normalized-rows))))))

(defn- normalize-row-widths [rows]
  (let [ncols (apply max 1 (map (comp count :parts) rows))]
    (mapv (fn [r]
            (update r :parts
                    (fn [parts]
                      (vec (take ncols
                                 (concat parts
                                         (repeat {:text "" :align :left :header? false})))))))
          rows)))

(defn- box-hline [widths ^String left ^String sep-mid ^String right]
  (str left
       (str/join sep-mid (map (fn [w] (apply str (repeat (+ 2 w) "─"))) widths))
       right))

(defn- table->ansi-string [^TableBlock block]
  (let [raw-rows (vec (table-block-rows block))
        rows (if (empty? raw-rows) [] (normalize-row-widths raw-rows))
        widths (column-widths rows)
        [hdr-rows data-rows] (split-with :header? rows)
        hdr-rows (vec hdr-rows)
        data-rows (vec data-rows)
        dim-seg (fn [s] (str dim s reset body))
        htop (dim-seg (box-hline widths "┌" "┬" "┐"))
        hmid (dim-seg (box-hline widths "├" "┼" "┤"))
        hbot (dim-seg (box-hline widths "└" "┴" "┘"))
        fmt-row (fn [r]
                  (let [parts (:parts r)
                        header-row? (:header? r)]
                    (str (dim-seg "│")
                         (str/join ""
                                   (map (fn [w p]
                                          (str " "
                                               (if header-row?
                                                 (str head bold
                                                      (pad-cell (:text p) w (:align p))
                                                      reset body)
                                                 (str body
                                                      (pad-cell (:text p) w (:align p))))
                                               " "
                                               (dim-seg "│")))
                                        widths parts))
                         "\n")))]
    (str htop "\n"
         (str/join "" (map fmt-row hdr-rows))
         (when (seq hdr-rows) (str hmid "\n"))
         (str/join "" (map fmt-row data-rows))
         hbot "\n\n")))

(defn- walk [^Node n ^StringBuilder sb]
  (condp instance? n
    Document
    (iter-children n #(walk % sb))

    Heading
    (do (.append sb body)
        (.append sb head)
        (.append sb bold)
        (iter-children n #(walk % sb))
        (.append sb reset)
        (.append sb body)
        (.append sb "\n\n"))

    Paragraph
    (let [parent (.getParent n)
          tight-list? (when (instance? ListItem parent)
                        (.isTight ^ListBlock (.getParent ^ListItem parent)))]
      (.append sb body)
      (iter-children n #(walk % sb))
      (.append sb (if tight-list? "\n" "\n\n")))

    Text
    (.append sb (.getLiteral ^Text n))

    StrongEmphasis
    (do (.append sb bold)
        (iter-children n #(walk % sb))
        (.append sb reset)
        (.append sb body))

    Emphasis
    (do (.append sb italic)
        (iter-children n #(walk % sb))
        (.append sb reset)
        (.append sb body))

    Code
    (do (.append sb code-style)
        (.append sb " ")
        (.append sb (.getLiteral ^Code n))
        (.append sb " ")
        (.append sb reset)
        (.append sb body))

    FencedCodeBlock
    (do (.append sb dim)
        (.append sb (.getLiteral ^FencedCodeBlock n))
        (.append sb reset)
        (.append sb body)
        (.append sb "\n\n"))

    IndentedCodeBlock
    (do (.append sb dim)
        (.append sb (.getLiteral ^IndentedCodeBlock n))
        (.append sb reset)
        (.append sb body)
        (.append sb "\n\n"))

    BulletList
    (do (iter-children n #(walk % sb))
        ;; Tight lists need a trailing blank line; loose items already end with one.
        (when (.isTight ^ListBlock n)
          (.append sb "\n")))

    OrderedList
    (do (iter-children n #(walk % sb))
        (when (.isTight ^ListBlock n)
          (.append sb "\n")))

    ListItem
    (do (.append sb body)
        (.append sb "  • ")
        ;; Paragraph children supply their own trailing newline(s) based on the
        ;; parent list's tightness, so ListItem adds no suffix of its own.
        (iter-children n #(walk % sb)))

    Link
    (do (iter-children n #(walk % sb))
        (.append sb link-url)
        (.append sb " (")
        (.append sb (.getDestination ^Link n))
        (.append sb ")")
        (.append sb reset)
        (.append sb body))

    BlockQuote
    (let [start (.length sb)]
      (.append sb quote-style)
      (.append sb "│ ")
      (iter-children n #(walk % sb))
      ;; If the children already ended with a blank line (e.g. Paragraph),
      ;; don't add another one.
      (let [children-end-nn? (str/ends-with? (subs (str sb) start) "\n\n")]
        (.append sb reset)
        (.append sb body)
        (when-not children-end-nn?
          (.append sb "\n\n"))))

    ThematicBreak
    (do (.append sb dim)
        (.append sb "────────────────────────────────────────\n")
        (.append sb reset)
        (.append sb body)
        (.append sb "\n"))

    SoftLineBreak
    (.append sb " ")

    HardLineBreak
    (.append sb "\n")

    HtmlBlock
    (do (.append sb dim)
        (.append sb (.getLiteral ^HtmlBlock n))
        (.append sb reset)
        (.append sb body))

    HtmlInline
    (do (.append sb dim)
        (.append sb (.getLiteral ^HtmlInline n))
        (.append sb reset)
        (.append sb body))

    TableBlock
    (.append sb (table->ansi-string ^TableBlock n))

    ;; Fallback: recurse into unknown block nodes
    (if (.getFirstChild n)
      (iter-children n #(walk % sb))
      nil)))

(defn- rewrite-single-backtick-code-with-newlines
  "CommonMark treats line endings inside inline code spans as spaces. Some models misuse
   single backticks for multi-line code, collapsing it. Detect single-backtick spans that
   contain a newline, are at the start of a line, and rewrite them as fenced code blocks
   before parsing. Mid-paragraph inline code is left as-is so paragraphs stay intact."
  [^String s]
  (let [n (count s)]
    (loop [i 0, acc (StringBuilder.)]
      (if (>= i n)
        (str acc)
        (let [c (.charAt s i)]
          (if (and (= \` c)
                   (or (zero? i) (not= \` (.charAt s (dec i))))
                   (< (inc i) n) (not= \` (.charAt s (inc i))))
            ;; Potential single-backtick inline code opening
            (let [close-idx (loop [j (inc i)]
                              (cond
                                (>= j n) nil
                                (= \` (.charAt s j))
                                (if (or (= j (dec n)) (not= \` (.charAt s (inc j))))
                                  j
                                  (recur (inc j)))
                                :else (recur (inc j))))
                  at-line-start?
                  (let [prev (when (pos? i) (.charAt s (dec i)))]
                    (or (zero? i)
                        (= \newline prev)
                        (and (Character/isWhitespace prev)
                             (loop [k (dec i)]
                               (cond
                                 (< k 0) true
                                 (= \newline (.charAt s k)) true
                                 (Character/isWhitespace (.charAt s k)) (recur (dec k))
                                 :else false)))))]
              (if close-idx
                (let [content (subs s (inc i) close-idx)]
                  (if (and (str/includes? content "\n") at-line-start?)
                    (let [trimmed (str/trim content)
                          max-run (loop [k 0 max-run 0 cur 0]
                                    (if (>= k (count trimmed))
                                      max-run
                                      (if (= \` (.charAt trimmed k))
                                        (recur (inc k) max-run (inc cur))
                                        (recur (inc k) (max max-run cur) 0))))
                          fence-len (max 4 (inc max-run))
                          fence (str/join (repeat fence-len \`))]
                      (.append acc fence)
                      (.append acc "\n")
                      (.append acc trimmed)
                      (.append acc "\n")
                      (.append acc fence)
                      (.append acc "\n")
                      (recur (inc close-idx) acc))
                    (do (.append acc \`)
                        (.append acc content)
                        (.append acc \`)
                        (recur (inc close-idx) acc))))
                (do (.append acc c)
                    (recur (inc i) acc))))
            (do (.append acc c)
                (recur (inc i) acc))))))))

(defn- render-md-chunk
  ^String [^String markdown]
  (let [parser (make-parser)
        ^Document doc (.parse parser (rewrite-single-backtick-code-with-newlines markdown))
        sb (StringBuilder.)]
    (.append sb body)
    (walk doc sb)
    (str sb)))

(defn- render-segment [[kind payload]]
  (case kind
    :plain (str body payload)
    :md (render-md-chunk payload)))

(defn render-to-ansi
  "Parse `markdown` as CommonMark (with GFM tables). If the string contains `<text/markdown>`,
  only the regions up to `</text/markdown>` or `<text/markdown/>` are parsed as Markdown;
  outside text is shown in the default body color. Legacy `<text-markdown>` tags are accepted.
  On failure, returns the original text in the default body color."
  ^String [^String markdown]
  (if (str/blank? markdown)
    ""
    (try
      (let [s (normalize-legacy-markdown-tags markdown)]
        (if (str/includes? s open-marker)
          (str/join "" (map render-segment (split-text-markdown-segments s)))
          (render-md-chunk s)))
      (catch Exception _
        (str body markdown)))))
