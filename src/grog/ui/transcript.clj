(ns grog.ui.transcript
  "Rich, message-based chat log for the grog GUI.

  Replaces the old ANSI line-run transcript with a *structured* message model:
    :user      — right-aligned bubble
    :assistant — full-width block rendered as Markdown (headings, paragraphs,
                 lists, code blocks with a copy button, GFM tables, quotes)
    :thinking  — collapsible disclosure section (open while streaming, then
                 collapsed to a header)
    :tool      — card with status (preparing / running / done / error /
                 rejected), duration, expandable args and summary
    :status / :banner — dim info/error lines

  The backing widget is a custom-painted, virtualized JComponent: only rows
  intersecting the visible viewport are drawn, and per-message heights are
  cached (keyed by message id + pane width), so long transcripts and streaming
  stay cheap.

  All mutating functions marshal to the EDT (safe from the ECA worker thread);
  read accessors are intended for the EDT."
  (:require [clojure.string :as str]
            [grog.appearance :as appearance]
            [grog.md-render :as md-render])
  (:import (java.awt Color Cursor Dimension Font FontMetrics Graphics Graphics2D
                     Point Rectangle RenderingHints Toolkit)
           (java.awt.datatransfer StringSelection)
           (java.awt.image BufferedImage)
           (java.awt.event AdjustmentListener ComponentAdapter MouseAdapter MouseEvent)
           (java.io Writer)
           (javax.swing JComponent JScrollPane SwingUtilities)
           (org.commonmark.node BlockQuote BulletList Code Document Emphasis FencedCodeBlock
                                HardLineBreak Heading HtmlBlock HtmlInline IndentedCodeBlock
                                Link ListItem Node OrderedList Paragraph SoftLineBreak
                                StrongEmphasis Text ThematicBreak)
           (org.commonmark.ext.gfm.tables TableBlock)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Geometry / palette / fonts
;; ---------------------------------------------------------------------------

(def ^:private outer-pad 16)
(def ^:private msg-gap 12)
(def ^:private block-gap 8)
(def ^:private corner 14)
(def ^:private max-user-frac 0.72)

(defn- acolor ^Color [rgb]
  (let [[r g b] rgb] (Color. (int r) (int g) (int b))))

(defn- palette []
  (let [bg (appearance/rgb [:chat :background])
        text (appearance/rgb [:chat :text])]
    {:bg      (acolor bg)
     :text    (acolor text)
     :user    (acolor (appearance/rgb [:chat :user]))
     :thinking (acolor (appearance/rgb [:chat :thinking]))
     :tool    (acolor (appearance/rgb [:chat :tool-call]))
     :status  (acolor (appearance/rgb [:chat :snark]))
     :code-bg (acolor (appearance/rgb [:chat :code-bg]))
     :bubble  (acolor (appearance/rgb [:chat :bubble-bg]))
     :card    (acolor (appearance/rgb [:chat :card-bg]))
     :border  (acolor (appearance/rgb [:chat :border]))}))

(defn- fonts-map
  []
  (let [size (max 10 (long (appearance/chat-font-size)))
        fam (str (or (appearance/chat-font-family) "Monospaced"))]
    {:base (Font. fam Font/PLAIN size)
     :bold (Font. fam Font/BOLD size)
     :mono (Font. "Monospaced" Font/PLAIN (max 11 size))
     :h1   (Font. fam Font/BOLD (int (* size 1.35)))
     :h2   (Font. fam Font/BOLD (int (* size 1.18)))
     :h3   (Font. fam Font/BOLD (int (* size 1.06)))
     :btn  (Font. fam Font/PLAIN (max 10 (- size 3)))}))

(defn- make-ctx
  "Shared render context: a Graphics2D (real or offscreen), palette, fonts."
  [^Graphics2D g]
  {:g2 g :pal (palette) :fonts (fonts-map)})

;; ---------------------------------------------------------------------------
;; Styled-run helpers (measure / wrap / draw)
;; ---------------------------------------------------------------------------

(defn- runs-text ^String [runs]
  (apply str (map :text runs)))

(defn- run-width ^double [^Graphics2D g {:keys [text font]}]
  (+ 0.0 (.stringWidth (.getFontMetrics g font) text)))

(defn- runs-width ^double [^Graphics2D g runs]
  (reduce + 0.0 (map #(run-width g %) runs)))

(defn- line-height ^long [^Graphics2D g runs]
  (apply max 1 (map #(.getHeight (.getFontMetrics g (:font %))) runs)))

(defn- lines-height ^long [^Graphics2D g lines]
  (reduce (fn [acc line] (+ acc (line-height g line))) 0 lines))

(defn- slice-runs
  "Sub-runs of `runs` covering characters [start end)."
  [runs start end]
  (persistent!
   (loop [rs runs, off 0, acc (transient [])]
     (if-let [r (first rs)]
       (let [len (count (:text r))
             r-end (+ off len)]
         (if (or (<= r-end start) (>= off end))
           (recur (rest rs) r-end acc)
           (let [s (max start off)
                 e (min end r-end)
                 t (subs (:text r) (- s off) (- e off))]
             (recur (rest rs) r-end (conj! acc (assoc r :text t))))))
       acc))))

(defn- wrap-text-plain
  "Greedy word-wrap of plain `text` to `max-w` px using `fm` metrics. Returns a
   vector of lines whose concatenation equals the original text (whitespace
   preserved), so char offsets can be recovered for styled slicing."
  [^FontMetrics fm ^String text ^double max-w]
  (let [tokens (str/split text #"(?<=\s)")
        n (count tokens)]
    (loop [i 0, line "", lines (transient [])]
      (if (< i n)
        (let [tok (nth tokens i)
              cand (str line tok)
              fits (<= (.stringWidth fm cand) max-w)]
          (cond
            (and (seq line) (not fits))
            (recur i (str tok) (conj! lines line))
            :else
            (recur (inc i) cand lines)))
        (if (seq line) (conj! lines line) lines)))))

(defn- wrap-runs
  "Wrap `runs` so each line fits `max-w` px. Returns a vector of line vectors of
   styled runs (a greedy word-wrap on the run text, then per-line slicing)."
  [^Graphics2D g runs ^double max-w]
  (let [text (runs-text runs)
        n (count text)]
    (cond
      (zero? n) []
      (<= max-w 8) [runs]
      :else
      (let [^Font f (or (some-> (first runs) :font)
                        (Font. "Monospaced" Font/PLAIN 13))
            fm (.getFontMetrics g f)
            lines (persistent! (wrap-text-plain fm text max-w))]
        (loop [lines lines, off 0, acc (transient [])]
          (if-let [ln (first lines)]
            (let [len (count ln)]
              (if (zero? len)
                (recur (rest lines) off acc)
                (recur (rest lines) (+ off len)
                       (conj! acc (slice-runs runs off (+ off len))))))
            (persistent! acc)))))))

(defn- wrap-text
  "Single-style text → wrapped lines (one run each)."
  [^Graphics2D g {:keys [font color]} ^String text ^double max-w]
  (wrap-runs g [{:text text :font font :color color}] max-w))

(defn- draw-lines!
  "Draw wrapped lines at (x, y). Returns the y of the line after the last."
  ^double [^Graphics2D g lines ^double x ^double y]
  (reduce
   (fn [yy line]
     (let [hdr (first line)
           ascent (if hdr (.getAscent (.getFontMetrics g (:font hdr))) 0)
           baseline (+ (double yy) (double ascent))]
       (loop [rs line, xs 0.0]
         (when-let [r (first rs)]
           (let [t (:text r)
                 f (:font r)
                 fm (.getFontMetrics g f)
                 w (.stringWidth fm t)]
             (.setFont g f)
             (.setColor g (or (:color r) Color/WHITE))
             (.drawString g t (float (+ x xs)) (float baseline))
             (when (:underline? r)
               (.setColor g (or (:color r) Color/WHITE))
               (.drawLine g (int (+ x xs)) (int (+ baseline 2))
                          (int (+ x xs w)) (int (+ baseline 2))))
             (recur (rest rs) (+ xs w)))))
       (+ (double yy) (double (line-height g line)))))
   (double y) lines))

;; ---------------------------------------------------------------------------
;; Markdown: inline runs, then block measure/paint
;; ---------------------------------------------------------------------------

(declare node-height)

(defn- inline-runs
  "Flatten a CommonMark inline subtree into styled runs."
  [ctx ^Node n ^Font base ^Color color bold? italic? underline?]
  (let [fonts (:fonts ctx)]
    (condp instance? n
      Text
      (if-let [s (.getLiteral ^Text n)]
        [{:text s
          :font (let [style (bit-or (if bold? Font/BOLD 0) (if italic? Font/ITALIC 0))]
                  (if (zero? style) base (.deriveFont base style)))
          :color color
          :underline? (boolean underline?)}]
        [])

      Code
      [{:text (str " " (.getLiteral ^Code n) " ")
        :font (:mono fonts)
        :color (:text (:pal ctx))}]

      Emphasis
      (mapcat #(inline-runs ctx % base color bold? true underline?)
              (md-render/node-children n))

      StrongEmphasis
      (mapcat #(inline-runs ctx % base color true italic? underline?)
              (md-render/node-children n))

      Link
      (mapcat #(inline-runs ctx % base color bold? italic? true)
              (md-render/node-children n))

      SoftLineBreak
      [{:text " " :font base :color color}]

      HardLineBreak
      [{:text "\n" :font base :color color}]

      HtmlInline
      [{:text (.getLiteral ^HtmlInline n) :font (:mono fonts) :color (:status (:pal ctx))}]

      (mapcat #(inline-runs ctx % base color bold? italic? underline?)
              (md-render/node-children n)))))

(defn- para-runs [ctx ^Node p]
  (inline-runs ctx p (:base (:fonts ctx)) (:text (:pal ctx)) false false false))

(defn- para-height [ctx ^Node p maxw]
  (lines-height (:g2 ctx) (wrap-runs (:g2 ctx) (para-runs ctx p) maxw)))

(defn- heading-font [ctx lvl]
  (case (long lvl)
    1 (:h1 (:fonts ctx))
    2 (:h2 (:fonts ctx))
    3 (:h3 (:fonts ctx))
    (:base (:fonts ctx))))

(defn- code-text ^String [^Node n]
  (or (condp instance? n
        FencedCodeBlock   (.getLiteral ^FencedCodeBlock n)
        IndentedCodeBlock (.getLiteral ^IndentedCodeBlock n)
        HtmlBlock         (.getLiteral ^HtmlBlock n)
        nil)
      ""))

(defn- code-lines [^String s]
  (let [parts (str/split s #"\n" -1)]
    (if (and (= 1 (count parts)) (seq (str/trim (first parts))))
      parts
      (if (= (str s) "")
        [""]
        parts))))

(defn- code-block-height [ctx ^Node n maxw]
  (let [^Graphics2D g (:g2 ctx)
        f (:mono (:fonts ctx))
        fm (.getFontMetrics g f)
        ln (.getHeight fm)
        lines (code-lines (code-text n))]
    (+ (* (max 1 (count lines)) ln) 14)))

(defn- status-runs [ctx ^Color color ^String text]
  [{:text text :font (:base (:fonts ctx)) :color color}])

;; --- tables ---------------------------------------------------------------

(defn- table-rows-of [^Node n]
  (let [rows (md-render/table-rows n)]
    (mapv (fn [r]
            {:header? (:header? r)
             :cells   (mapv (fn [c] (get-in c [:text])) (:cells r))})
          rows)))

(defn- table-col-widths [ctx rows maxw]
  (let [^Graphics2D g (:g2 ctx)
        fm (.getFontMetrics g (:base (:fonts ctx)))
        ncols (apply max 1 (map (comp count :cells) rows))
        widths (vec (for [i (range ncols)]
                      (apply max 30
                             (map (fn [r]
                                    (when-let [c (nth (:cells r) i nil)]
                                      (+ (.stringWidth fm c) 18)))
                                  rows))))
        total (apply + widths)]
    (if (<= total maxw)
      widths
      (let [share (max 1 (long (/ (- total maxw) ncols)))]
        (mapv (fn [w] (max 30 (- w share))) widths)))))

(defn- table-height [ctx rows maxw]
  (let [^Graphics2D g (:g2 ctx)
        fm (.getFontMetrics g (:base (:fonts ctx)))]
    (+ 8 (* (max 1 (count rows)) (.getHeight fm)))))

;; --- block measurement ----------------------------------------------------

(defn- node-height
  ^long [ctx ^Node n ^double maxw]
  (let [^Graphics2D g (:g2 ctx)
        pal (:pal ctx)]
    (condp instance? n
      Document
      (apply + 0 (map #(node-height ctx % maxw) (md-render/node-children n)))

      Paragraph
      (+ (para-height ctx n maxw) block-gap)

      Heading
      (let [lvl (.getLevel ^Heading n)
            f (heading-font ctx lvl)
            runs (inline-runs ctx n f (:text pal) true false false)]
        (+ (lines-height g (wrap-runs g runs maxw)) block-gap 2))

      FencedCodeBlock (code-block-height ctx n maxw)
      IndentedCodeBlock (code-block-height ctx n maxw)
      HtmlBlock (code-block-height ctx n maxw)

      BulletList
      (apply + 0 (map #(node-height ctx % (- maxw 20)) (md-render/node-children n)))
      OrderedList
      (apply + 0 (map #(node-height ctx % (- maxw 20)) (md-render/node-children n)))
      ListItem
      (apply + 0 (map #(node-height ctx % maxw) (md-render/node-children n)))

      BlockQuote
      (+ (apply + 0 (map #(node-height ctx % (- maxw 16)) (md-render/node-children n))) 6)

      ThematicBreak
      (+ (.getHeight (.getFontMetrics g (:base (:fonts ctx)))) 6)

      TableBlock
      (table-height ctx (table-rows-of n) maxw)

      (apply + 0 (map #(node-height ctx % maxw) (md-render/node-children n))))))

(defn- measure-doc
  ^long [ctx ^Document doc ^double maxw]
  (apply + 0 (map #(node-height ctx % maxw) (md-render/node-children doc))))

;; --- block painting -------------------------------------------------------

(declare paint-node!)

(defn- paint-doc!
  [ctx m ^Document doc x y maxw actions]
  (reduce
   (fn [yy ^Node c] (paint-node! ctx m c x (double yy) maxw actions))
   y (md-render/node-children doc)))

(defn- paint-code-block!
  [ctx m ^Node n x y maxw ^clojure.lang.Atom actions]
  (let [^Graphics2D g (:g2 ctx)
        pal (:pal ctx)
        f (:mono (:fonts ctx))
        fm (.getFontMetrics g f)
        ln (.getHeight fm)
        text (code-text n)
        lines (code-lines text)
        h (+ (* (max 1 (count lines)) ln) 14)
        pad 10
        bw (- maxw (* 2 pad))
        bx (+ x pad)
        text-w (- bw 16)]
    (.setColor g (:code-bg pal))
    (.fillRoundRect g (int bx) (int y) (int bw) (int h) 8 8)
    (loop [i 0, yy (+ y 7)]
      (when (< i (count lines))
        (let [runs (wrap-runs g [{:text (nth lines i) :font f :color (:text pal)}] text-w)]
          (doseq [line runs]
            (draw-lines! g [line] (+ bx 6) (double yy))))
        (recur (inc i) (+ yy ln))))
    (let [pw 46
          px (int (+ bx bw (- pw 8)))
          py (int (+ y 5))]
      (.setColor g (:border pal))
      (.drawRoundRect g px py pw 20 10 10)
      (.setFont g (:btn (:fonts ctx)))
      (.setColor g (:tool pal))
      (.drawString g "copy" (int (+ px 7)) (int (+ py 14)))
      (when actions
        (swap! actions conj {:rect (Rectangle. px py pw 20)
                             :kind :copy-code :payload text})))
    (+ y h)))

(defn- paint-list!
  [ctx m ^Node n x y maxw ordered? ^clojure.lang.Atom actions]
  (let [^Graphics2D g (:g2 ctx)
        pal (:pal ctx)
        fm (.getFontMetrics g (:base (:fonts ctx)))
        items (md-render/node-children n)]
    (loop [idx 0, yy (double y), items items]
      (if-let [^ListItem li (first items)]
        (let [marker (if ordered? (str (inc idx) ".") "•")]
          (.setColor g (:thinking pal))
          (.setFont g (:base (:fonts ctx)))
          (.drawString g marker (float (+ x 2))
                       (float (+ yy (+ (.getAscent fm) 2))))
          (let [yy2 (double (paint-doc! ctx m li (+ x 20) yy (- maxw 20) actions))]
            (recur (inc idx) yy2 (rest items))))
        yy))))

(defn- paint-quote!
  [ctx m ^Node n x y maxw ^clojure.lang.Atom actions]
  (let [^Graphics2D g (:g2 ctx)
        pal (:pal ctx)
        inner-x (+ x 12)
        inner-w (- maxw 16)
        y0 (paint-doc! ctx m n inner-x (+ y 2) inner-w actions)]
    (.setColor g (:thinking pal))
    (.fillRect g (int (+ x 2)) (int (+ y 2)) 3 (int (max 4 (- (double y0) (double y) 4))))
    (+ y0 4)))

(defn- paint-table!
  [ctx m ^Node n x y maxw ^clojure.lang.Atom actions]
  (let [^Graphics2D g (:g2 ctx)
        pal (:pal ctx)
        f (:base (:fonts ctx))
        fm (.getFontMetrics g f)
        lh (.getHeight fm)
        rows (table-rows-of n)
        widths (table-col-widths ctx rows maxw)
        ncols (count widths)
        col-x (fn [i]
                (double (+ x (apply + 0 (map long (take i widths))))))
        yy (atom (+ y 4))]
    (doseq [row rows]
      (let [cells (:cells row)
            header? (:header? row)
            cf (if header? (:bold (:fonts ctx)) f)
            runs-vec (mapv (fn [i]
                             (wrap-text g {:font cf :color (:text pal)}
                                        (or (nth cells i "") "")
                                        (nth widths i)))
                           (range ncols))
            row-h (max lh (apply max (map #(lines-height g %) runs-vec)))]
        (doseq [i (range ncols)]
          (draw-lines! g (nth runs-vec i) (col-x i) (double @yy)))
        (swap! yy + row-h)))
    (.setColor g (:border pal))
    (.drawLine g (int x) (int @yy) (int (+ x (apply + widths))) (int @yy))
    (+ @yy 6)))

(defn- paint-node!
  [ctx m ^Node n x y maxw ^clojure.lang.Atom actions]
  (let [^Graphics2D g (:g2 ctx)
        pal (:pal ctx)]
    (condp instance? n
      Document (paint-doc! ctx m n x y maxw actions)

      Paragraph
      (let [lines (wrap-runs g (para-runs ctx n) maxw)]
        (draw-lines! g lines x (+ y 1))
        (+ y (lines-height g lines) block-gap))

      Heading
      (let [lvl (.getLevel ^Heading n)
            f (heading-font ctx lvl)
            runs (inline-runs ctx n f (:text pal) true false false)
            lines (wrap-runs g runs maxw)]
        (draw-lines! g lines x y)
        (+ y (+ (lines-height g lines) block-gap 2)))

      FencedCodeBlock (paint-code-block! ctx m n x y maxw actions)
      IndentedCodeBlock (paint-code-block! ctx m n x y maxw actions)
      HtmlBlock (paint-code-block! ctx m n x y maxw actions)

      BulletList (paint-list! ctx m n x y maxw false actions)
      OrderedList (paint-list! ctx m n x y maxw true actions)
      ListItem (paint-doc! ctx m n x y maxw actions)

      BlockQuote (paint-quote! ctx m n x y maxw actions)

      ThematicBreak
      (let [mid (+ y 8)]
        (.setColor g (:border pal))
        (.drawLine g (int x) (int mid) (int (+ x maxw)) (int mid))
        (+ y 16))

      TableBlock (paint-table! ctx m n x y maxw actions)

      (paint-doc! ctx m n x y maxw actions))))

;; ---------------------------------------------------------------------------
;; Message measurement / painting
;; ---------------------------------------------------------------------------

(defn- user-layout
  [ctx {:keys [text]} maxw]
  (let [^Graphics2D g (:g2 ctx)
        fonts (:fonts ctx)
        pal (:pal ctx)
        cap (max 80 (long (- (* maxw max-user-frac) (* 2 outer-pad))))
        lines (wrap-runs g [{:text text :font (:base fonts) :color (:user pal)}] cap)
        content-w (apply max 40 (map #(runs-width g %) lines))
        bubble-w (min cap (+ content-w 24))]
    {:lines lines :content-w content-w :w bubble-w :h (+ (lines-height g lines) 20)}))

(defn- thinking-card-h [ctx {:keys [text open?]} maxw]
  (let [^Graphics2D g (:g2 ctx)
        hdr (+ (.getHeight (.getFontMetrics g (:base (:fonts ctx)))) 8)]
    (if open?
      (+ hdr 8
         (lines-height g (wrap-runs g (status-runs ctx (:thinking (:pal ctx)) text) maxw)))
      (+ hdr 2))))

(defn- tool-card-h [ctx {:keys [args summary expanded?] :as m} maxw]
  (let [^Graphics2D g (:g2 ctx)
        hdr (+ (.getHeight (.getFontMetrics g (:base (:fonts ctx)))) 8)
        base (+ hdr 10)]
    (if expanded?
      (let [args-lines (wrap-runs g [{:text (pr-str args) :font (:mono (:fonts ctx))
                                     :color (:status (:pal ctx))}]
                                 (max 40 (- maxw 20)))
            sum-lines (when (seq summary)
                        (wrap-runs g (status-runs ctx (:status (:pal ctx)) summary)
                                   (max 40 (- maxw 20))))]
        (+ base 8 (lines-height g args-lines)
           (if sum-lines (+ 6 (lines-height g sum-lines)) 0)))
      base)))

(defn- message-height
  ^long [ctx {:keys [type] :as m} maxw]
  (let [^Graphics2D g (:g2 ctx)
        pal (:pal ctx)]
    (case type
      :user      (long (:h (user-layout ctx m (+ maxw (* 2 outer-pad)))))
      :assistant (+ (measure-doc ctx (md-render/parse! (:text m)) maxw) 8)
      :thinking  (thinking-card-h ctx m maxw)
      :tool      (tool-card-h ctx m maxw)
      :status    (+ (lines-height g (wrap-runs g (status-runs ctx (:status pal) (:text m)) maxw)) 4)
      :banner    (+ (lines-height g (wrap-runs g (status-runs ctx (:status pal) (:text m)) maxw)) 4)
      0)))

(defn- paint-user!
  [ctx {:keys [text] :as m} y maxw]
  (let [ul (user-layout ctx m (+ maxw (* 2 outer-pad)))
        ^Graphics2D g (:g2 ctx)
        pal (:pal ctx)
        h (:h ul)
        bw (:w ul)
        bx (- maxw (+ bw outer-pad))]
    (.setColor g (:bubble pal))
    (.fillRoundRect g (int (Math/round (double bx))) (int y) (int bw) (int h) corner corner)
    (draw-lines! g (:lines ul) (+ bx 12) (+ y 10))
    (+ y h)))

(defn- paint-thinking!
  [ctx {:keys [id text open?] :as m} y maxw ^clojure.lang.Atom actions]
  (let [^Graphics2D g (:g2 ctx)
        pal (:pal ctx)
        fm (.getFontMetrics g (:base (:fonts ctx)))
        hdr-h (+ (.getHeight fm) 8)
        h (thinking-card-h ctx m maxw)]
    (.setColor g (:card pal))
    (.fillRoundRect g (int outer-pad) (int y) (int maxw) (int h) corner corner)
    (.setColor g (:border pal))
    (.drawRoundRect g (int outer-pad) (int y) (int maxw) (int h) corner corner)
    (.setFont g (:base (:fonts ctx)))
    (.setColor g (:thinking pal))
    (.drawString g (if open? "▾ thinking" "▸ thinking")
                  (float (+ outer-pad 10)) (float (+ y (.getAscent fm) 6)))
    (when actions
      (swap! actions conj {:rect (Rectangle. (int outer-pad) (int y) (int maxw) (int hdr-h))
                           :kind :toggle :msg-id id}))
    (when open?
      (let [y0 (+ y (.getHeight fm) 12)
            lines (wrap-runs g (status-runs ctx (:thinking pal) text)
                             (max 40 (- maxw 20)))]
        (draw-lines! g lines (+ outer-pad 10) (double y0))))
    (+ y h)))

(defn- paint-tool!
  [ctx {:keys [id name args summary server status ms expanded?] :as m}
   y maxw ^clojure.lang.Atom actions]
  (let [^Graphics2D g (:g2 ctx)
        pal (:pal ctx)
        fm (.getFontMetrics g (:base (:fonts ctx)))
        hdr-h (+ (.getHeight fm) 8)
        h (tool-card-h ctx m maxw)
        status-color (case status
                       :done (:thinking pal)
                       :error (Color. 235 94 94)
                       :rejected (:status pal)
                       (:tool pal))
        icon (case status :done "✓" :error "✗" :rejected "⊘" "…")
        header (str "  " icon " " name
                    (when-let [ms ms] (str "  " ms "ms"))
                    (when (seq server) (str "  [" server "]")))
        y0 (+ y (.getHeight fm) 12)]
    (.setColor g (:card pal))
    (.fillRoundRect g (int outer-pad) (int y) (int maxw) (int h) corner corner)
    (.setColor g (:border pal))
    (.drawRoundRect g (int outer-pad) (int y) (int maxw) (int h) corner corner)
    (.setFont g (:base (:fonts ctx)))
    (.setColor g status-color)
    (.drawString g header (float (+ outer-pad 10)) (float (+ y (.getAscent fm) 6)))
    (when actions
      (swap! actions conj {:rect (Rectangle. (int outer-pad) (int y) (int maxw) (int hdr-h))
                           :kind :toggle :msg-id id}))
    (when expanded?
      (let [args-lines (wrap-runs g [{:text (pr-str args) :font (:mono (:fonts ctx))
                                     :color (:status pal)}]
                                 (max 40 (- maxw 20)))
            sum-lines (when (seq summary)
                        (wrap-runs g (status-runs ctx (:status pal) summary)
                                   (max 40 (- maxw 20))))]
        (when (seq (pr-str args))
          (draw-lines! g args-lines (+ outer-pad 10) (double y0)))
        (when (seq summary)
          (draw-lines! g sum-lines (+ outer-pad 10)
                       (double (+ y0 (lines-height g args-lines) 6))))))
    (+ y h)))

(defn- paint-status!
  [ctx {:keys [text]} y maxw]
  (let [^Graphics2D g (:g2 ctx)
        pal (:pal ctx)
        lines (wrap-runs g (status-runs ctx (:status pal) text) maxw)]
    (draw-lines! g lines (+ outer-pad 2) (+ y 2))
    (+ y (lines-height g lines) 4)))

(defn- paint-message!
  [ctx m maxw hover? actions]
  (let [inner (- maxw (* 2 outer-pad))]
    (case (:type m)
      :user (paint-user! ctx m (or (:y-offset m) 0.0) maxw)

      :assistant
      (let [y0 (+ 4.0 (double (or (:y-offset m) 0.0)))]
        (when hover?
          (let [^Graphics2D g (:g2 ctx)
                pw 50
                px (int (- maxw (+ outer-pad pw 4)))
                py (int (+ 4.0 (double (or (:y-offset m) 0.0))))]
            (.setColor g (:border (:pal ctx)))
            (.drawRoundRect g px py pw 20 10 10)
            (.setFont g (:btn (:fonts ctx)))
            (.setColor g (:tool (:pal ctx)))
            (.drawString g "copy" (int (+ px 7)) (int (+ py 14)))
            (when actions
              (swap! actions conj {:rect (Rectangle. px py pw 20)
                                   :kind :copy-message :payload (:text m)}))))
        (+ (paint-doc! ctx m (md-render/parse! (:text m))
                       outer-pad y0 inner actions)
           4))

      :thinking (paint-thinking! ctx m (double (or (:y-offset m) 0.0)) inner actions)
      :tool (paint-tool! ctx m (double (or (:y-offset m) 0.0)) inner actions)
      (:status :banner) (paint-status! ctx m (double (or (:y-offset m) 0.0)) inner)
      0)))

;; ---------------------------------------------------------------------------
;; Component state / layout / painting
;; ---------------------------------------------------------------------------

(def ^:private state-key ::state)
(def ^:private next-id (let [a (atom 0)] (fn [] (swap! a inc))))

(defn- create-metrics-g ^Graphics2D []
  (.createGraphics (BufferedImage. 1 1 BufferedImage/TYPE_INT_ARGB)))

(defn- base-state []
  {:messages []
   ;; splash? = conversation hasn't started yet. While true the transcriptview
   ;; stays transparent so the window's logo background (grog.ui/background-panel)
   ;; shows through as the splash screen; the first real message flips it false
   ;; and the view starts painting its own opaque background.
   :splash? true
   :follow? true
   :width 640
   :total 0
   :ys []
   :heights {}
   :hover-msg nil
   :actions []
   :g (create-metrics-g)})

(defn- st-of ^clojure.lang.Atom [^JComponent c]
  (.getClientProperty c state-key))

(defn- scroll-to-bottom! [^clojure.lang.Atom st]
  (SwingUtilities/invokeLater
   (fn []
     (when-let [^JScrollPane sp (:scrollpane @st)]
       (.validate sp)
       (let [vp (.getViewport sp)
             view (.getView vp)
             vh (.getHeight view)
             vph (.getHeight vp)]
         (.setViewPosition vp (Point. 0 (max 0 (- (long vh) (long vph))))))))))

(defn- update-and-validate! [^clojure.lang.Atom st]
  (let [s @st
        msgs (:messages s)
        width (max 160 (:width s))
        inner (double (- width (* 2 outer-pad)))
        g (:g s)
        [hm ys total]
        (loop [rs msgs, hm (:heights s), ys [], y 0]
          (if-let [m (first rs)]
            (let [prev (get hm (:id m))
                  h (if (and prev (identical? (:msg prev) m))
                      (:h prev)
                      (message-height (make-ctx g) m inner))
                  y0 y]
              (recur (rest rs)
                     (assoc hm (:id m) {:msg m :h (long h)})
                     (conj ys y0)
                     (+ y0 (+ (long h) msg-gap))))
            [hm ys y]))
        total' (if (seq ys) (- total msg-gap) 0)]
    (swap! st assoc :heights hm :ys ys :total total'))
  (when-let [^JComponent c (:component @st)]
    (.revalidate c)
    (.repaint c))
  (when (:follow? @st)
    (scroll-to-bottom! st))
  nil)

(defn- hit-action
  [^clojure.lang.Atom st ^Point p]
  (some (fn [{:keys [rect] :as a}]
          (when (and rect (.contains ^Rectangle rect p)) a))
        (:actions @st)))

(defn- copy-text! [^String s]
  (when (seq s)
    (.setContents (.getSystemClipboard (Toolkit/getDefaultToolkit))
                  (StringSelection. s)
                  nil)))

(defn- paint-view!
  [^JComponent view ^Graphics g ^clojure.lang.Atom st]
  (let [s @st
        g2 (doto ^Graphics2D (.create g)
             (.setRenderingHint RenderingHints/KEY_TEXT_ANTIALIASING
                                RenderingHints/VALUE_TEXT_ANTIALIAS_ON))
        w (.getWidth view)
        msgs (:messages s)
        ys (:ys s)
        heights (:heights s)]
    ;; While in splash (conversation not started) the view stays fully
    ;; transparent so the background logo shows  through; once content exists
    ;; it paints its own opaque background.
    (when-not (:splash? s)
      (.setColor g2 (:bg (palette)))
      (.fillRect g2 0 0 w (.getHeight view)))
    (let [^JScrollPane sp (:scrollpane s)
          vp (when sp (.getViewport sp))
          vr (if vp (.getViewRect vp) (Rectangle. 0 0 (int w) 800))
          top (.getY ^Rectangle vr)
          bot (+ top (.getHeight ^Rectangle vr))
          actions (atom [])]
      (dotimes [i (count msgs)]
        (let [m (nth msgs i)
              y (double (nth ys i))
              h (double (get-in heights [(:id m) :h] 1))]
          (when (and (< y (+ bot 400.0))
                     (> (+ y h) (- top 400.0)))
            (let [hover? (= (:hover-msg s) (:id m))
                  m (assoc m :y-offset y)]
              (paint-message! (make-ctx g2) m w hover? actions)))))
      (swap! st assoc :actions (deref actions)))
    (.dispose g2)))

(defn- make-view-st
  [^clojure.lang.Atom st]
  (let [view (proxy [JComponent] []
               (getPreferredSize []
                 (Dimension. (max 100 (:width @st)) (max 1 (:total @st))))
               (paintComponent [g]
                 (paint-view! this g st)))]
    (swap! st assoc :component view)
    (.setOpaque view false)
    (.putClientProperty view state-key st)
    view))

;; ---------------------------------------------------------------------------
;; Public construction
;; ---------------------------------------------------------------------------

(defn chat-pane
  "Return the inner message-list JComponent for a pane built by make-chat-pane."
  ^JComponent [^JScrollPane sp]
  (when sp (.getView (.getViewport sp))))

(defn make-chat-pane
  "A scrollable, rich, virtualized chat log. See chat-pane for the inner view."
  ^JScrollPane []
  (let [st (atom (base-state))
        view (make-view-st st)
        sp (JScrollPane. view)]
    (swap! st assoc :scrollpane sp)
    ;; manual scroll-away disables follow mode
    (.addAdjustmentListener (.getVerticalScrollBar sp)
      (reify AdjustmentListener
        (adjustmentValueChanged [_ _]
          (when-let [vp (.getViewport sp)]
            (let [vr (.getViewRect vp)
                  vh (.getHeight (.getView vp))
                  near? (<= (- vh (+ (.getY ^Rectangle vr) (.getHeight ^Rectangle vr))) 60)]
              (when-not near?
                (swap! st assoc :follow? false)))))))
    ;; click: toggle disclosures / copy. hover: switching + cursor.
    (let [mouse (proxy [MouseAdapter] []
                  (mouseClicked [^MouseEvent e]
                    (when-let [a (hit-action st (.getPoint e))]
                      (case (:kind a)
                        :toggle
                        (do
                          (swap! st update :messages
                                 (fn [msgs]
                                   (mapv (fn [m]
                                           (if (= (:id a) (:id m))
                                             (case (:type m)
                                               :thinking (update m :open? not)
                                               :tool (update m :expanded? not)
                                               m)
                                             m))
                                         msgs)))
                          (update-and-validate! st))
                        :copy-code (copy-text! (:payload a))
                        :copy-message (copy-text! (:payload a)))))
                  (mouseMoved [^MouseEvent e]
                    (let [a (hit-action st (.getPoint e))
                          hover-msg (when a (:msg-id a))]
                      (when (not= hover-msg (:hover-msg @st))
                        (swap! st assoc :hover-msg hover-msg)
                        (.repaint view))
                      (.setCursor view
                                  (if a
                                    (Cursor/getPredefinedCursor Cursor/HAND_CURSOR)
                                    (Cursor/getDefaultCursor))))))]
      (.addMouseListener view mouse)
      (.addMouseMotionListener view mouse))
    (.addComponentListener view
      (proxy [ComponentAdapter] []
        (componentResized [e]
          (let [w (.getWidth view)]
            (when (pos? w)
              (swap! st assoc :width w :heights {})
              (update-and-validate! st))))))
    (SwingUtilities/invokeLater
     (fn []
       (let [w (.getWidth view)]
         (when (pos? w)
           (swap! st assoc :width w)
           (update-and-validate! st)))))
    sp))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn- with-state
  "Run `f` on the state atom on the EDT, then re-layout/repaint/scroll. Any
   exception is reported to stderr (never leaves the EDT unhandled)."
  [^JComponent c f]
  (SwingUtilities/invokeLater
   (fn []
     (when-let [st (st-of c)]
       (try
         (f st)
         (catch Throwable e
           (.println System/err (str "[grog.ui.transcript] " (.getMessage e)))
           (.printStackTrace ^Throwable e)))
       (update-and-validate! st)))))

(defn messages
  "Current message vector (newest last)."
  [c]
  (:messages (deref (st-of c))))

(defn text
  "Full transcript as plain text (newlines between messages)."
  ^String [c]
  (->> (messages c)
       (map (fn [m]
              (case (:type m)
                :user      (str "> " (:text m))
                :assistant (:text m)
                :thinking  (str "[thinking] " (:text m))
                :tool      (str "[tool " (:name m) "] " (pr-str (:args m)))
                (:text m))))
       (str/join "\n\n")))

(defn clear!
  "Wipe the transcript (keeps the offscreen metrics)."
  [c]
  (with-state c
    (fn [st]
      (swap! st assoc :messages [] :heights {} :ys [] :total 0 :actions [] :hover-msg nil
             :splash? true))))

(defn follow!
  "Enable/disable follow-mode (viewport pinned to the bottom)."
  [c on?]
  (with-state c (fn [st] (swap! st assoc :follow? (boolean on?)))))

(defn append-user!
  "Append a user bubble."
  [c s]
  (with-state c
    (fn [st]
      (let [s (str s)]
        (when (seq s)
          (swap! st assoc :splash? false)
          (swap! st update :messages conj {:id (next-id) :type :user :text s}))))))

(defn append-status!
  "Append a dim status/info line."
  [c s]
  (with-state c
    (fn [st]
      (let [s (str s)]
        (when (seq s)
          (swap! st update :messages conj {:id (next-id) :type :status :text s}))))))

(defn append-banner!
  "Append a banner (e.g. startup snark)."
  [c s]
  (with-state c
    (fn [st]
      (let [s (str s)]
        (when (seq s)
          (swap! st update :messages conj {:id (next-id) :type :banner :text s}))))))

(defn append-thinking!
  "Append streaming thinking text (extends the live thinking message)."
  [c s]
  (with-state c
    (fn [st]
      (let [s (str s)]
        (when (seq s)
          (swap! st
                 (fn [state]
                   (let [msgs (:messages state)
                         last (peek msgs)]
                     (if (and last (= :thinking (:type last)) (:live? last))
                       (assoc state :messages (conj (pop msgs) (update last :text str s)))
                       (assoc state :messages (conj msgs {:id (next-id) :type :thinking
                                                          :text s :open? true :live? true})))))))))))

(defn start-thinking!
  "Begin a thinking section."
  [c]
  (with-state c
    (fn [st]
      (swap! st update :messages conj {:id (next-id) :type :thinking :text "" :open? true :live? true}))))

(defn finish-thinking!
  "Mark the live thinking section done (collapses to a header)."
  [c]
  (with-state c
    (fn [st]
      (swap! st update :messages
             (fn [msgs]
               (if-let [last (peek msgs)]
                 (if (and (= :thinking (:type last)) (:live? last))
                   (conj (pop msgs) (assoc last :live? false :done? true :open? false))
                   msgs)
                 msgs))))))

(defn append-assistant!
  "Append streaming assistant text (extends the live assistant message)."
  [c s]
  (with-state c
    (fn [st]
      (let [s (str s)]
        (when (seq s)
          (swap! st
                 (fn [state]
                   (let [msgs (:messages state)
                         last (peek msgs)]
                     (if (and last (= :assistant (:type last)) (:live? last))
                       (assoc state :splash? false :messages (conj (pop msgs) (update last :text str s)))
                       (assoc state :splash? false :messages (conj msgs {:id (next-id) :type :assistant :text s :live? true})))))))))))

(defn finish-assistant!
  "Stop extending the current assistant message (next append starts fresh)."
  [c]
  (with-state c
    (fn [st]
      (swap! st update :messages
             (fn [msgs]
               (if-let [last (peek msgs)]
                 (if (and (= :assistant (:type last)) (:live? last))
                   (conj (pop msgs) (assoc last :live? false))
                   msgs)
                 msgs))))))

(defn tool!
  "Create or update a tool-call card. `m` may carry :key :name :args :summary
   :server :status :ms. A :key on an existing card updates it in place."
  [c m]
  (with-state c
    (fn [st]
      (let [k (or (:key m) (:id m))
            entry {:id (next-id) :type :tool :key k
                   :name (or (:name m) "(tool)")
                   :args (or (:args m) {})
                   :summary (or (:summary m) "")
                   :server (or (:server m) "")
                   :status (or (:status m) :preparing)
                   :expanded? false}]
        (swap! st update :messages
               (fn [msgs]
                 (let [ix (first (keep-indexed (fn [i x] (when (= k (:key x)) i)) msgs))]
                   (if ix
                     (assoc msgs ix (merge (nth msgs ix) (dissoc m :key)))
                     (conj msgs entry)))))))))

(defn set-tool-status!
  "Update a tool card's status/duration by :key."
  [c k status ms]
  (with-state c
    (fn [st]
      (swap! st update :messages
             (fn [msgs]
               (mapv (fn [m]
                       (if (= k (:key m))
                         (-> m (assoc :status status) (assoc :ms ms))
                         m))
                     msgs))))))

(def ^:private char-array-type (class (char-array 0)))

(defn- coerce-write-str
  "Normalize a `write` argument (String, char[], int code) to a String."
  [x]
  (cond
    (string? x)                x
    (instance? char-array-type x) (String. ^chars x)
    (char? x)                  (str x)
    (integer? x)               (str (char (int x)))
    :else                      (str x)))

(defn console-writer
  "A plain-text Writer that appends its buffered output as a status message on
   flush — used for slash-command output that still prints to *out*/*err*."
  ^Writer [c]
  (let [sb (StringBuilder.)
        emit! (fn []
                (let [s (str sb)]
                  (.setLength sb 0)
                  (when (seq (str/trim s))
                    (append-status! c s))))]
    (proxy [Writer] []
      (write
        ([x]
         (.append sb (coerce-write-str x)))
        ([x off len]
         (.append sb (if (string? x)
                       (subs ^String x (int off) (int (+ (long off) (long len))))
                       (String. ^chars x (int off) (int len))))))
      (flush [] (emit!))
      (close [] (emit!)))))

(defn line-height-px
  "Current chat line height in px (for scroll unit increments)."
  ^long [c]
  (let [g (or (:g (deref (st-of c)))
              (create-metrics-g))]
    (long (.getHeight (.getFontMetrics g (Font. (or (appearance/chat-font-family) "Monospaced")
                                                Font/PLAIN (max 10 (appearance/chat-font-size))))))))