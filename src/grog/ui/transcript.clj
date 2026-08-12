(ns grog.ui.transcript
  "Swing output sink for the grog GUI — a **virtualized chat log**.

  The chat prints everything (thinking modes, assistant replies, tool calls,
  status lines) as an ANSI-coloured stream to `*out*`/`*err*`. This namespace
  turns that stream into colored *line runs* in a `JList` (not a `JTextPane`).
  A JTextPane re-lays out its entire document on every insert, so CPU burns
  quadratically as the transcript grows; a `JList` only lays out/paints the
  visible rows, so arbitrarily long transcripts stay cheap.

  Each row in the backing `DefaultListModel` is a vector of coloured runs:
    [{:text \"...\" :color Color|} :italic bool} ...].
  A custom `ListCellRenderer` paints only the visible rows' runs with the
  list's current (monospaced) font."
  (:require [clojure.string :as str])
  (:import (java.awt Color Dimension Font Point RenderingHints Graphics2D Toolkit)
           (java.awt.datatransfer StringSelection)
           (java.awt.event AdjustmentListener KeyEvent MouseAdapter)
           (java.io Writer)
           (javax.swing AbstractAction JComponent JList JScrollPane SwingUtilities
                        DefaultListModel KeyStroke ListSelectionModel)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; ANSI SGR -> style state
;; ---------------------------------------------------------------------------

(def ^:private csi-re #"\u001B\[([0-9;]*)m")

(defn- sgr->params [code-str]
  (->> (str/split (or code-str "") #";")
       (map #(when (seq %) (Long/parseLong %)))
       (remove nil?)
       vec))

(defn- rgb-from-params
  "If `params` is [38 2 r g b], return (Color. r g b); else nil."
  [params]
  (when (and (>= (count params) 5) (= 38 (nth params 0)) (= 2 (nth params 1)))
    (let [[_ _ r g b] params]
      (Color. (int r) (int g) (int b)))))

(defn apply-sgr
  "Apply an SGR parameter string (the digits after `ESC[`, before `m`) to a
  style-state map {:color Color-or-nil :italic boolean}, returning the new state."
  [state code-str]
  (let [params (sgr->params code-str)]
    (if-let [c (rgb-from-params params)]
      (assoc state :color c)
      (reduce (fn [st p]
                (case (int p)
                  0 (assoc st :color nil :italic false) ; reset
                  3 (assoc st :italic true)             ; italic (snark)
                  st))
              state
              params))))

;; ---------------------------------------------------------------------------
;; Chat log model (line runs)
;; ---------------------------------------------------------------------------

(def ^:private log-key (keyword "transcript" "log"))

(defn- row-chars
  "Character count of a line (its runs' text lengths)."
  ^long [runs]
  (reduce (fn [acc {:keys [^String text]}] (+ acc (count text))) 0 runs))

(defn- make-log
  "Backing state for the transcript. The `model` is a `DefaultListModel` where
  each element is a vector of coloured runs; the last row may be the
  still-growing, in-progress line (tracked via `partial?`). Also maintains the
  per-row character-prefix table (for selection hit-testing) and the text
  selection state."
  []
  (let [model (DefaultListModel.)
        starts* (atom [])    ; char offset (before) the start of each row
        end* (atom 0)        ; total char count so far
        sel* (atom nil)      ; {:anchor long :caret long} in global char offsets
        follow?* (atom false); follow-mode: keep viewport pinned to the bottom
        cur (atom [])        ; runs of the line currently being built
        partial? (atom false); true <=> last model row is exactly `cur`
        blank? (atom false)] ; last emitted line was a blank separator
    (letfn [(row-len [runs] (long (row-chars runs)))
            (add-row! [runs]
              ;; Append a completed row: record its start, then advance end.
              (swap! starts* conj @end*)
              (swap! end* + (row-len runs)))
            (update-last-start-total! [runs]
              ;; The last row's text changed in place; its start stays the same,
              ;; only the running end needs recomputing.
              (reset! end* (+ (or (peek @starts*) 0) (row-len runs))))
            (touch-partial! []
              ;; Make the in-progress line the last row (add or update in place)
              (if @partial?
                (do (.setElementAt model @cur (dec (.size model)))
                    (update-last-start-total! @cur))
                (do (.addElement model @cur)
                    (add-row! @cur)
                    (reset! partial? true))))
            (complete-line! []
              ;; Finalize a line at a newline boundary. Consecutive empty
              ;; boundaries (e.g. a lone `\n\n` chunk) collapse to a single
              ;; blank separator line instead of stacking blank rows.
              (cond
                @partial?
                (do (.setElementAt model @cur (dec (.size model)))
                    (update-last-start-total! @cur)
                    (reset! partial? false)
                    (reset! cur [])
                    (reset! blank? false))

                (seq @cur)
                (do (.addElement model @cur)
                    (add-row! @cur)
                    (reset! cur [])
                    (reset! blank? false))

                :else
                (when-not @blank?
                  (.addElement model [])
                  (add-row! [])
                  (reset! blank? true))))]
      {:model model
       :sel sel*
       :follow? follow?*
       :row-start (fn [i] (if (and (>= (long i) 0) (< (long i) (count @starts*)))
                            (long (nth @starts* (long i)))
                            0))
       :total-chars (fn [] (long @end*))
       :set-selection! (fn [anchor caret]
                         (reset! sel* {:anchor (long anchor) :caret (long caret)}))
       :clear-selection! (fn [] (reset! sel* nil))
       :append! (fn [runs]
                  (doseq [{:keys [^String text color italic]} runs]
                    (let [parts (str/split text #"\n" -1)
                          n (count parts)]
                      (dotimes [i n]
                        (let [part (nth parts i)
                              boundary? (< i (dec n))]
                          (when (seq part)
                            (swap! cur conj {:text part :color color :italic italic}))
                          (when boundary?
                            (complete-line!))))))
                  ;; final partial line (if any) should be visible
                  (when (seq @cur)
                    (touch-partial!)))
       :clear! (fn []
                 (.clear model)
                 (reset! starts* [])
                 (reset! end* 0)
                 (reset! cur [])
                 (reset! partial? false)
                 (reset! blank? false)
                 (reset! sel* nil))})))

(defn- log-model-of [^JComponent c]
  (or (.getClientProperty c log-key)
      (do (.putClientProperty c log-key (make-log))
          (.getClientProperty c log-key))))

;; ---------------------------------------------------------------------------
;; Scrolling
;; ---------------------------------------------------------------------------

(defn- scroll-viewport-bottom! [^JScrollPane sp]
  ;; Force a synchronous layout BEFORE measuring: appends grow the DefaultListModel,
  ;; but the JList's laid-out height / scrollbar range only update on the next
  ;; paint. Without this we measure the *old* bottom and the follow-scroll lands a
  ;; chunk short — which then reads as "user scrolled away" and kills follow mode.
  (.validate sp)
  (let [vp (.getViewport sp)
        ^java.awt.Component view (.getView vp)
        h (.getHeight view)
        vh (.getHeight vp)]
    (.setViewPosition vp (Point. 0 (max 0 (- (long h) (long vh)))))))

(defn- viewport-near-bottom?
  "True when the viewport's visible rectangle is within `threshold-px` of the
  view bottom. True when the component isn't in a scroll pane (safety)."
  [^JComponent c ^long threshold-px]
  (if-let [sp (SwingUtilities/getAncestorOfClass JScrollPane c)]
    (let [vp (.getViewport ^JScrollPane sp)
          view-rect (.getViewRect vp)
          view-height (.getHeight (.getView vp))
          visible-bottom (+ (.getY view-rect) (.getHeight view-rect))]
      (<= (- view-height visible-bottom) threshold-px))
    true))

(defn scroll-to-bottom!
  "Force the transcript scroll pane to the bottom, ready to receive a streaming
  response. Safe to call from any thread (marshals to the EDT)."
  [^JComponent c]
  (SwingUtilities/invokeLater
    (fn []
      (when-let [sp (SwingUtilities/getAncestorOfClass JScrollPane c)]
        (scroll-viewport-bottom! sp)))))

(defn- maybe-scroll-to-bottom! [^JComponent c]
  (when-let [sp (SwingUtilities/getAncestorOfClass JScrollPane c)]
    (let [log (log-model-of c)
          following? (boolean @(:follow? log))]
      (cond
        ;; pinned: always snap to the true bottom
        following?
        (scroll-viewport-bottom! sp)

        ;; reading near the bottom: promote to follow-mode and stay glued, so a
        ;; streamed answer never leaves the user stranded mid-scroll.
        (viewport-near-bottom? c 60)
        (do (reset! (:follow? log) true)
            (scroll-viewport-bottom! sp))

        ;; scrolled away deliberately: leave the viewport alone
        :else nil))))

(defn follow!
  "Turn follow-mode on/off. When on, the viewport stays pinned to the bottom as
  new content streams in — until the user scrolls away from the bottom (which
  turns it off again). Safe from any thread; marshals to the EDT."
  [^JComponent c on?]
  (when-let [log (log-model-of c)]
    (let [on? (boolean on?)]
      (reset! (:follow? log) on?)
      (when on?
        (scroll-to-bottom! c)))))

;; ---------------------------------------------------------------------------
;; Line rendering (virtualized — only visible rows are painted)
;; ---------------------------------------------------------------------------

(defn- font-metrics
  ^java.awt.FontMetrics [^java.awt.Component c ^Font f]
  (.getFontMetrics c f))

(defn- draw-run!
  "Draw one colour/italic run of a row, highlighting any part of it that falls
  inside the selection [sel-lo sel-hi) (global char offsets). Returns the x
  advance contributed by this run."
  [^Graphics2D g2 ^Font font ^java.awt.FontMetrics fm ^String text color
   x sel-lo sel-hi global-start]
  (let [x (long x)
        ;; sel bounds are nil when there's no active selection; `overlap?` below
        ;; short-circuits on them, so keep the nils instead of coercing.
        sel-lo (some-> sel-lo long)
        sel-hi (some-> sel-hi long)
        global-start (long global-start)
        len (count text)
        global-end (+ global-start len)
        baseline (int (- (.getHeight fm) (.getDescent fm)))
        normal-color (or color Color/BLACK)
        overlap? (and sel-lo sel-hi
                      (< global-start sel-hi)
                      (> global-end sel-lo))]
    (.setFont g2 font)
    (if-not overlap?
      (do (.setColor g2 normal-color)
          (.drawString g2 text (int x) (int baseline))
          (long (.stringWidth fm text)))
      (let [sel-color (Color. 35 75 140)
            pre-len (max 0 (- sel-lo global-start))
            clipped (min len (- sel-hi global-start))
            mid-len (max 0 (- clipped pre-len))
            pre (subs text 0 (int pre-len))
            mid (subs text (int pre-len) (int (+ pre-len mid-len)))
            post (subs text (int (+ pre-len mid-len)) (int len))
            pre-w (if (pos? (count pre)) (long (.stringWidth fm pre)) 0)
            mid-w (if (pos? (count mid)) (long (.stringWidth fm mid)) 0)
            post-w (if (pos? (count post)) (long (.stringWidth fm post)) 0)
            mid-x (+ x pre-w)]
        (when (pos? (count pre))
          (.setColor g2 normal-color)
          (.drawString g2 pre (int x) (int baseline)))
        (when (pos? (count mid))
          (.setColor g2 sel-color)
          (.fillRect g2 (int mid-x) (int (- baseline (.getAscent fm)))
                     (int mid-w) (int (.getHeight fm)))
          (.setColor g2 Color/WHITE)
          (.drawString g2 mid (int mid-x) (int baseline)))
        (when (pos? (count post))
          (.setColor g2 normal-color)
          (.drawString g2 post (int (+ mid-x mid-w)) (int baseline)))
        (+ pre-w mid-w post-w)))))

(defn- make-line-renderer
  "Returns a ListCellRenderer whose cells paint the row's coloured runs with the
  list's font, highlighting any text selected via the transcript's character
  selection. Cells are cheap (no per-document layout); JList only asks for
  visible rows."
  [log]
  (let [runs* (atom [])
        font* (atom nil)
        index* (atom 0)
        pref* (atom (Dimension. 0 16))
        cell (proxy [JComponent] []
               (getPreferredSize [] ^Dimension @pref*)
               (paintComponent [g]
                 (let [^Graphics2D g2 g
                       ^Font font @font*
                       runs @runs*
                       index (long @index*)
                       sel @(:sel log)
                       row-start ((:row-start log) index)
                       sel-lo (when sel (long (min (:anchor sel) (:caret sel))))
                       sel-hi (when sel (long (max (:anchor sel) (:caret sel))))]
                   (when font
                     (.setRenderingHint g2 RenderingHints/KEY_TEXT_ANTIALIASING
                                        RenderingHints/VALUE_TEXT_ANTIALIAS_ON)
                     (loop [rs runs
                            local-start 0
                            x 0]
                       (if-let [{:keys [^String text color italic]} (first rs)]
                         (let [effective-font (if italic
                                                (.deriveFont font (bit-or Font/PLAIN Font/ITALIC))
                                                font)
                               fm (font-metrics this effective-font)
                               advance (draw-run! g2 effective-font fm text color
                                                  (long x) sel-lo sel-hi
                                                  (+ (long row-start) (long local-start)))]
                           (recur (next rs)
                                  (+ (long local-start) (long (count text)))
                                  (+ (long x) (long advance))))
                         nil))))))]
    (reify javax.swing.ListCellRenderer
      (getListCellRendererComponent [_ list value index _ _]
        (let [^javax.swing.JList list list
              runs (if (sequential? value) (vec value) [])
              ^Font base-font (.getFont list)
              fm (font-metrics cell base-font)
              width (reduce (fn [acc {:keys [^String text italic]}]
                              (let [f (if italic
                                        (.deriveFont base-font (bit-or Font/PLAIN Font/ITALIC))
                                        base-font)
                                    fm2 (if italic (font-metrics cell f) fm)]
                                (+ acc (.stringWidth fm2 text))))
                            0 runs)]
          (reset! runs* runs)
          (reset! font* base-font)
          (reset! index* (max 0 (int index)))
          (reset! pref* (Dimension. (int (max 1 width)) (int (max 1 (.getHeight fm)))))
          cell)))))

;; ---------------------------------------------------------------------------
;; EDT append
;; ---------------------------------------------------------------------------

(defn- emit-buffered!
  "Parse the buffered text into runs (ANSI-aware) and append them to `log` on
  the EDT, auto-scrolling only when the viewport was already near the bottom."
  [log c style ^StringBuilder sb]
  (let [raw (str sb)]
    (.setLength sb 0)
    (when (seq raw)
      (let [runs (atom [])]
        (loop [rest raw]
          (if-let [m (re-matcher csi-re rest)]
            (if (.find m)
              (let [start (.start m)]
                (when (> start 0)
                  (swap! runs conj {:text (subs rest 0 start)
                                    :color (:color @style)
                                    :italic (:italic @style)}))
                (swap! style #(apply-sgr % (.group m 1)))
                (recur (subs rest (.end m))))
              (swap! runs conj {:text rest :color (:color @style) :italic (:italic @style)}))
            nil))
        (let [saved-runs @runs]
          (SwingUtilities/invokeLater
            (fn []
              ((:append! log) saved-runs)
              (maybe-scroll-to-bottom! c))))))))

(def ^:private char-array-class (class (char-array 0)))

(defn- coerce-write-str
  "Normalize a `write` argument (String, char[], Integer/Character code) to a
  String for buffering."
  [x]
  (cond
    (string? x)    x
    (instance? char-array-class x) (String. ^chars x)
    (char? x)      (str x)
    (integer? x)   (str (char (int x)))
    :else          (str x)))

(defn styled-writer
  "Returns a `java.io.Writer` that streams text into the chat log as coloured
  line runs. Text is buffered until `flush`/`close`, then parsed for the known
  ANSI colour escapes and appended on the EDT (safe to call from a worker
  thread). Same Writer#write arities as the old JTextPane sink."
  ^Writer [^JComponent c]
  (let [log (log-model-of c)
        sb (StringBuilder.)
        style (atom {:color nil :italic false})]
    (proxy [Writer] []
      (write
        ([x]
         (.append sb (coerce-write-str x)))
        ([x off len]
         (.append sb
                  (cond
                    (string? x)         (subs x (int off) (int (+ (long off) (long len))))
                    (instance? char-array-class x) (String. ^chars x (int off) (int len))
                    :else               (coerce-write-str x)))))
      (flush
        []
        (emit-buffered! log c style sb))
      (close
        []
        (emit-buffered! log c style sb)))))

;; ---------------------------------------------------------------------------
;; Accessors
;; ---------------------------------------------------------------------------

(defn lines
  "All transcript lines as vectors of colour runs, newest last. The final entry
  is the in-progress line while a thought/answer is streaming."
  ^java.util.List [^JComponent c]
  (let [log (.getClientProperty c log-key)]
    (when log
      (let [^DefaultListModel model (:model log)]
        (mapv #(.getElementAt model (int %)) (range (.size model)))))))

(defn text
  "The full transcript as plain text (newline separated)."
  ^String [^JComponent c]
  (->> (lines c)
       (map (fn [runs] (apply str (map :text runs))))
       (str/join "\n")))

(defn clear!
  "Clear the transcript log. Safe to call from any thread (marshals to the EDT)."
  [^JComponent c]
  (let [log (.getClientProperty c log-key)]
    (when log
      (SwingUtilities/invokeLater #((:clear! log))))))

;; ---------------------------------------------------------------------------
;; Pane construction
;; ---------------------------------------------------------------------------

(defn- char-width
  "Monospaced advance width for the font, as a plain long."
  ^long [^java.awt.Component c ^Font f]
  (let [fm (font-metrics c f)]
    (max 1 (long (.stringWidth fm "M")))))

(defn- offset-at-x
  "Global char offset for a click at `p` in the list's coordinate space."
  ^long [log ^JList list ^java.awt.Point p]
  (let [^DefaultListModel model (:model log)
        n (.size model)]
    (if (zero? n)
      0
      (let [row (.locationToIndex list p)
            row (max 0 (min (long row) (dec (long n))))
            loc (.indexToLocation list (int row))
            x (- (.x p) (if loc (.x loc) 0))
            runs (.getElementAt model (int row))
            len (row-chars runs)
            cw (char-width list (.getFont list))
            char-idx (max 0 (min (long len) (long (Math/floor (/ (double (max 0 (long x)))
                                                                 (double cw))))))]
        (+ ((:row-start log) row) char-idx)))))

(defn- selection-runs
  "Seq of {:start .. :end ..} vertical slice info for the current selection
  (used by copy): each entry is [row-index seg-start-in-text seg-end-in-text]."
  [log]
  (let [sel @(:sel log)
        ^DefaultListModel model (:model log)
        n (.size model)]
    (when sel
      (let [lo (min (:anchor sel) (:caret sel))
            hi (max (:anchor sel) (:caret sel))]
        (when (< lo hi)
          (keep (fn [i]
                  (let [row-start ((:row-start log) i)
                        row-end (+ row-start (row-chars (.getElementAt model (int i))))
                        s (max lo row-start)
                        e (min hi row-end)]
                    (when (< s e)
                      [i (- s row-start) (- e row-start)])))
                (range n)))))))

(defn selected-text
  "The currently selected text, or nil when there's no (non-empty) selection."
  ^String [^JComponent c]
  (when-let [log (.getClientProperty c log-key)]
    (when-let [parts (not-empty (selection-runs log))]
      (let [^DefaultListModel model (:model log)]
        (str (str/join "\n"
                       (map (fn [[i s e]]
                              (let [runs (.getElementAt model (int i))]
                                (subs (apply str (map :text runs))
                                      (int s) (int e))))
                            parts))
             "\n")))))

(defn- copy-clipboard! [^String s]
  (when (seq s)
    (.setContents (.getSystemClipboard (Toolkit/getDefaultToolkit))
                  (StringSelection. s)
                  nil)))

(defn copy-selection!
  "Copy the current transcript selection to the clipboard, then drop the
  highlight. Returns the copied text (or nil). Safe to call from the EDT."
  ^String [^JComponent c]
  (let [log (.getClientProperty c log-key)
        sel (when log (selected-text c))]
    (when (seq sel)
      (copy-clipboard! sel)
      (when log
        ((:clear-selection! log))
        (.repaint c)))
    sel))

(defn make-chat-pane
  "A non-editable, scrollable, *virtualized* JList chat log with character-level
  text selection (drag + shift-click, Ctrl+A / Ctrl+C / Enter-to-copy /
  Escape). Returns a JScrollPane; the inner list is available via `chat-pane`."
  ^JScrollPane []
  (let [log (make-log)
        ^DefaultListModel model (:model log)
        list (doto (JList. model)
               (.setSelectionMode ListSelectionModel/SINGLE_SELECTION)
               (.setCellRenderer (make-line-renderer log))
               (.setOpaque false)
               (.setFocusable true))]
    (.putClientProperty list log-key log)
    (let [repaint! (fn [] (.repaint list))
          sel-at! (fn [p]
                    (long (offset-at-x log list p)))
          update-sel! (fn [anchor ^long caret]
                        ((:set-selection! log) anchor caret)
                        (repaint!))]
      (let [mouse-pressed! (fn [^java.awt.event.MouseEvent e shift?]
                             (.requestFocusInWindow list)
                             (if shift?
                               (when-let [sel @(:sel log)]
                                 (update-sel! (:anchor sel) (sel-at! (.getPoint e))))
                               (let [off (long (sel-at! (.getPoint e)))]
                                 (update-sel! off off))))]
        (.addMouseListener list
          (proxy [MouseAdapter] []
            (mousePressed [^java.awt.event.MouseEvent e]
              (when (= 1 (.getButton e))
                (mouse-pressed! e (.isShiftDown e))))))
        (.addMouseMotionListener list
          (proxy [MouseAdapter] []
            (mouseDragged [^java.awt.event.MouseEvent e]
              (when-let [sel @(:sel log)]
                (update-sel! (:anchor sel) (sel-at! (.getPoint e))))))))
      (let [im (.getInputMap list JComponent/WHEN_FOCUSED)
            am (.getActionMap list)
            ctrl (java.awt.event.InputEvent/CTRL_DOWN_MASK)]
        (.put im (KeyStroke/getKeyStroke (int \a) ctrl) "grog-sel-all")
        (.put im (KeyStroke/getKeyStroke (int \c) ctrl) "grog-copy")
        ;; drag → Enter: copy the selection to the clipboard in one step (no
        ;; right-click menu). Clears the selection afterwards so the collapse
        ;; gives instant feedback that the copy happened.
        (.put im (KeyStroke/getKeyStroke KeyEvent/VK_ENTER 0) "grog-enter-copy")
        (.put im (KeyStroke/getKeyStroke KeyEvent/VK_ESCAPE 0) "grog-sel-none")
        (doto am
          (.put "grog-sel-all"
                (proxy [AbstractAction] []
                  (actionPerformed [_ _]
                    (update-sel! 0 ((:total-chars log))))))
          (.put "grog-copy"
                (proxy [AbstractAction] []
                  (actionPerformed [_ _]
                    (copy-selection! list))))
          (.put "grog-enter-copy"
                (proxy [AbstractAction] []
                  (actionPerformed [_ _]
                    (copy-selection! list))))
          (.put "grog-sel-none"
                (proxy [AbstractAction] []
                  (actionPerformed [_ _]
                    ((:clear-selection! log))
                    (repaint!))))))
      (let [sp (JScrollPane. list)]
        ;; follow-mode: any manual scroll away from the bottom turns it off
        (.addAdjustmentListener (.getVerticalScrollBar sp)
          (reify AdjustmentListener
            (adjustmentValueChanged [_ _]
              (when-not (viewport-near-bottom? list 60)
                (reset! (:follow? log) false)))))
        sp))))

(defn chat-pane
  "Return the virtualized JList given the scroll pane produced by
  `make-chat-pane`."
  ^JList [^JScrollPane sp]
  (when sp
    (let [v (.getView (.getViewport sp))]
      (when (instance? JList v) v))))