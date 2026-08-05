(ns grog.ui.transcript
  "Swing output sink for the grog GUI.

  The headless chat prints everything (thinking modes, assistant replies, tool
  calls, status lines) to `*out*`/`*err*` with the ANSI color escapes defined in
  `grog.core`. This namespace turns that stream into colored runs in a
  `JTextPane` with zero changes to the existing print sites: you simply bind
  `*out*`/`*err*` to a `styled-writer` over the pane."
  (:require [clojure.string :as str])
  (:import (java.awt Color Point)
           (java.io Writer)
           (javax.swing JScrollPane JTextPane SwingUtilities Timer)
           (javax.swing.text JTextComponent StyleConstants StyledDocument)))

;; ---------------------------------------------------------------------------
;; ANSI SGR -> Swing style
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

(defn- viewport-near-bottom?
  "True when the viewport's visible rectangle is within `threshold-px` of the
  document bottom. Returns true when the pane is not in a scroll pane (safety)."
  [^JTextPane pane ^long threshold-px]
  (if-let [sp (SwingUtilities/getAncestorOfClass JScrollPane pane)]
    (let [vp (.getViewport ^JScrollPane sp)
          view-rect (.getViewRect vp)
          view-height (.getHeight (.getView vp))
          visible-bottom (+ (.getY view-rect) (.getHeight view-rect))]
      (<= (- view-height visible-bottom) threshold-px))
    true))

;; ---------------------------------------------------------------------------
;; Smooth scrolling
;; ---------------------------------------------------------------------------

(def ^:private smooth-scroll-duration-ms 120)
(def ^:private smooth-scroll-interval-ms 16)  ;; ~60 fps

(defn- smooth-scroll-to-bottom!
  "Animate the viewport of `pane`'s enclosing scroll pane to the bottom over
  ~120ms with a simple ease-out curve. No-op if the pane isn't in a scroll pane."
  [^JTextPane pane]
  (if-let [sp (SwingUtilities/getAncestorOfClass JScrollPane pane)]
    (let [vp (.getViewport ^JScrollPane sp)
          start-y ^double (.getY (.getViewPosition vp))
          target-y ^double (max 0.0 (- (.getHeight (.getView vp))
                                       (.getHeight vp)))
          ;; total distance to travel
          dist (- target-y start-y)]
      ;; If already at target or very close, just jump
      (if (< (Math/abs dist) 4.0)
        (.setCaretPosition pane (.getLength (.getStyledDocument pane)))
        (let [start-ms (long (System/currentTimeMillis))
              duration (long smooth-scroll-duration-ms)
              timer (Timer. (int smooth-scroll-interval-ms)
                            (reify java.awt.event.ActionListener
                              (actionPerformed [_ e]
                                (let [elapsed (- (System/currentTimeMillis) start-ms)
                                      t (min 1.0 (/ (double elapsed) duration))
                                      ;; ease-out quad: t*(2-t)
                                      eased (* t (- 2.0 t))
                                      cur-y (+ start-y (* dist eased))]
                                  (.setViewPosition vp (Point. 0 (int cur-y)))
                                  (when (>= t 1.0)
                                    (.stop ^Timer (.getSource e)))))))]
          (.setRepeats timer true)
          (.start timer))))
    ;; Fallback: no scroll pane — just set caret
    (.setCaretPosition pane (.getLength (.getStyledDocument pane)))))

;; ---------------------------------------------------------------------------
;; EDT append
;; ---------------------------------------------------------------------------

(defn- append-styled!
  "Append `text` to the styled document of `pane` on the EDT. `color` nil =
  default black; `italic` toggles italics. Only auto-scrolls to the bottom
  when the viewport is already near the end (within 2 line-heights). Scroll
  animation is smooth (ease-out, ~120ms) instead of a hard snap."
  [^JTextPane pane color italic ^String text]
  (when (seq text)
    (SwingUtilities/invokeLater
      (fn []
        (let [^StyledDocument doc (.getStyledDocument pane)
              style (.addStyle doc (str "grog-s" (System/nanoTime)) nil)
              was-at-bottom? (viewport-near-bottom? pane 60)  ;; ~2 line heights
              moved-caret? (not= (.getCaretPosition pane) (.getLength doc))]
          (StyleConstants/setForeground style (or color Color/BLACK))
          (when italic (StyleConstants/setItalic style true))
          (.insertString doc (.getLength doc) text style)
          ;; Only snap to bottom if the user was already there (and hadn't moved
          ;; the caret somewhere else in the document). Use smooth animation.
          (when (and was-at-bottom? (not moved-caret?))
            (smooth-scroll-to-bottom! pane)))))))

(defn- emit-buffered! [^JTextPane pane style sb]
  (let [raw (str sb)]
    (.setLength sb 0)
    (when (seq raw)
      ;; Split on CSI sequences; text between them uses the current style, and
      ;; each escape updates the style for subsequent text.
      (loop [rest raw]
        (if-let [m (re-matcher csi-re rest)]
          (if (.find m)
            (let [start (.start m)]
              (when (> start 0)
                (append-styled! pane (:color @style) (:italic @style)
                                (subs rest 0 start)))
              (swap! style #(apply-sgr % (.group m 1)))
              (recur (subs rest (.end m))))
            (append-styled! pane (:color @style) (:italic @style) rest))
          nil)))))

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
  "Returns a `java.io.Writer` that streams text into `pane` as colored runs.
  Text is buffered until `flush`/`close`, then parsed for the known ANSI color
  escapes and appended on the EDT (safe to call from a worker thread). Handles
  every `Writer#write` arity the Clojure printer uses (write(int), write(String),
  write(char[]), and the 3-arg buffer forms)."
  ^Writer [^JTextPane pane]
  (let [sb (StringBuilder.)
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
        (emit-buffered! pane style sb))
      (close
        []
        (emit-buffered! pane style sb)))))

;; ---------------------------------------------------------------------------
;; Pane construction
;; ---------------------------------------------------------------------------

(defn make-chat-pane
  "A non-editable, scrollable JTextPane for the chat transcript."
  ^JScrollPane []
  (let [pane (doto (JTextPane.)
               (.setEditable false))]
    (JScrollPane. pane)))

(defn chat-pane
  "Return the underlying editable/scroll JTextPane given the scroll pane
  produced by `make-chat-pane`."
  ^JTextPane [^JScrollPane sp]
  (when sp
    (let [vp (.getViewport sp)
          view (.getView vp)]
      (when (instance? JTextComponent view)
        view))))
