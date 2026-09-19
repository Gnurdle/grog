(ns grog.ui.widgets
  "Shared rounded dark buttons used across the GUI (main page and settings), so
  text is always readable on the dark theme. Dialog/button/status fonts are
  derived from the active Look & Feel's system font (see `scale-ui-fonts!`)."
  (:require [grog.appearance :as appearance])
  (:import (java.awt BasicStroke Color Cursor Font Graphics RenderingHints)
           (java.awt.event MouseWheelEvent MouseWheelListener)
           (java.awt.geom Path2D$Double)
           (java.awt.image BufferedImage)
           (javax.swing ImageIcon JButton JScrollPane SwingConstants UIManager)))

(def ^:private ui-font-scale 1.5)
(def ^:private min-ui-size 19)
(def ^:private min-mono-size 18)
(def ^:private button-scale 1.3)
(def ^:private min-button-size 16)

;; The *true* system UI font size, captured before grog inflates the L&F fonts in
;; `scale-ui-fonts!`. `lf-base-font` reads the UIManager, which after
;; `scale-ui-fonts!` holds an already-inflated font — deriving scales from that
;; would double-scale (e.g. buttons 1.3x of a 1.5x base = huge). So we snapshot
;; the desktop font once and always scale from it.
(defonce ^:private system-base-size
  (let [f (or (try (Font/getFont "Label.font" (Font. "SansSerif" Font/PLAIN 13))
                   (catch Throwable _ nil))
              (Font. "SansSerif" Font/PLAIN 13))]
    (int (Math/round (float (.getSize f))))))

(defn- lf-base-font
  "The active Look & Feel's base UI font (FlatLaf mirrors the desktop's system
  font + DPI settings). Falls back to a sensible default so font sizing always
  has a family and size to scale from."
  ^Font []
  (or (try (UIManager/getFont "Label.font") (catch Throwable _ nil))
      (try (UIManager/getFont "defaultFont") (catch Throwable _ nil))
      (Font. "SansSerif" Font/PLAIN 13)))

(defn- stable-base-size
  "The true desktop font size (pre-inflation), as an int >= 1."
  []
  (max 1 system-base-size))

(defn ui-font-size
  "Font size for dialogs/buttons/status, from the system L&F font scaled up."
  []
  (max min-ui-size (int (Math/round (* (stable-base-size) ui-font-scale)))))

(defn mono-font-size
  "Monospace dialog font size, from the system L&F font scaled up."
  []
  (max min-mono-size (int (Math/round (* (stable-base-size) ui-font-scale)))))

(defn- button-size
  "Button font size: follows the system L&F font but stays compact (a footer of
  buttons full of 20pt text is too shouty; dialogs/status get the big scale)."
  []
  (max min-button-size (int (Math/round (* (stable-base-size) button-scale)))))

(defn button-font
  "Compact sans font for footer/action buttons, following the system family."
  ^Font []
  (Font. (.getFamily (lf-base-font)) Font/PLAIN (button-size)))

(defn ui-font
  "Readable sans font for dialogs, buttons, and the footer status line.
  Family follows the system UI font; size is scaled up."
  ^Font []
  (Font. (.getFamily (lf-base-font)) Font/PLAIN (ui-font-size)))

(defn mono-font
  "Readable monospace font for dialog body text, sized from the system UI font."
  ^Font []
  (Font. "Monospaced" (if (appearance/windows?) Font/BOLD Font/PLAIN) (mono-font-size)))

(defn dialog-mono-font
  "Monospace font for dialog/approval body text. Follows the configured chat
  font family (Fira Code, …) at a readable size clamped to roughly the chat
  font size — so approval call text reads the same size as the transcript it
  accompanies, without ever towering beyond the chat font itself."
  ^Font []
  (let [size (long (appearance/chat-font-size))]
    (Font. (or (some-> (appearance/chat-font-family) str not-empty) "Monospaced")
           Font/PLAIN
           (int (max 14 (min 21 size))))))

(defn- wheel-step-px
  "How far one notched wheel step should scroll horizontally when Shift is held.
  Derived from the UI scale so it feels proportional on any DPI/font size."
  []
  (let [base (max 14 (ui-font-size))]
    (max 120 (int (* base 7)))))

(defn boost-horizontal-wheel!
  "Boost Shift+MouseWheel horizontal scrolling on `sp`. The JVM default scrolls a
  single unit per notch (for a text view that's ~1 character), which feels
  horribly sluggish; this handler scrolls the horizontal scrollbar by a generous
  fixed step and consumes the event so the default handler doesn't also run.
  Vertical wheel scrolling is left untouched."
  ^JScrollPane [^JScrollPane sp]
  (let [step (atom (wheel-step-px))]
    (.addMouseWheelListener sp
      (reify MouseWheelListener
        (mouseWheelMoved [_ e]
          (when (.isShiftDown ^MouseWheelEvent e)
            (.consume ^MouseWheelEvent e)
            (let [sb (.getHorizontalScrollBar sp)]
              (when (and sb (pos? (- (.getMaximum sb) (.getMinimum sb))))
                (.setValue sb
                           (+ (.getValue sb)
                              (* (long (.getWheelRotation ^MouseWheelEvent e))
                                 (long @step)))))))))))
  sp)

(defn scale-ui-fonts!
  "After the Look & Feel is installed, bump its base UI font keys so every
  component (settings/model-picker labels and lists, tooltips, option panes)
  inherits the larger system-derived font. Call once after FlatLaf setup and
  before building frames."
  []
  (let [f (ui-font)
        bf (button-font)
        keys ["defaultFont" "Label.font" "TextField.font"
              "ComboBox.font" "List.font" "Spinner.font" "TabbedPane.font"
              "ToolTip.font" "CheckBox.font" "RadioButton.font"
              "OptionPane.messageFont"
              "TitledBorder.font" "Menu.font" "MenuItem.font"]
        button-keys ["Button.font" "OptionPane.buttonFont"]]
    (doseq [k keys]
      (try (UIManager/put k f) (catch Throwable _)))
    ;; buttons use the compact font so dialogs (approve/reject/yolo, question OK/
    ;; cancel, settings) don't render oversized; the big scale is for labels/lists
    (doseq [k button-keys]
      (try (UIManager/put k bf) (catch Throwable _)))
    f))

(def btn-normal (Color. 42 44 56))
(def btn-hover  (Color. 66 70 90))
(def btn-armed  (Color. 28 30 38))
(def btn-border (Color. 150 156 178))
(def btn-arc 18)

(defn- style!
  "Common dark-theme styling for a JButton."
  [^JButton b]
  (doto b
    (.setContentAreaFilled false)
    (.setFocusPainted false)
    (.setBorderPainted false)
    (.setOpaque false)
    (.setForeground (Color. 235 238 245))
    (.setFont (button-font))
    (.setCursor (Cursor/getPredefinedCursor Cursor/HAND_CURSOR))
    (.setHorizontalAlignment SwingConstants/LEFT))
  b)

(defn- paint-rounded!
  "Draw the dark rounded button base + border."
  [^Graphics g this]
  (let [w (.getWidth this) h (.getHeight this)
        m (.getModel this)
        bg (cond (.isArmed m) btn-armed
                 (.isRollover m) btn-hover
                 :else btn-normal)]
    (let [g2 (doto (.create ^Graphics g)
               (.setRenderingHint RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
               (.setColor bg)
               (.fillRoundRect 0 0 w h btn-arc btn-arc))]
      (.dispose g2))
    (let [g2 (doto (.create ^Graphics g)
               (.setColor btn-border)
               (.setStroke (BasicStroke. 1.0))
               (.drawRoundRect 1 1 (max 0 (- w 3)) (max 0 (- h 3)) btn-arc btn-arc))]
      (.dispose g2))))

(defn action-icon
  "A small monochrome `ImageIcon` for toolbar operation buttons.
  `kind` is one of :send :stop :terminal :settings :export :html :clear :mic.
  Glyphs are designed on an 18×18 grid and scaled to `size` px, drawn in
  `color` (default button text color). Solid shapes for send/stop/mic read
  better than hairlines at toolbar sizes."
  ^ImageIcon [kind & {:keys [color size]
                      :or {color (Color. 235 238 245) size 18}}]
  (let [size (int (max 12 (long size)))
        s    (/ (double size) 18.0)
        img  (BufferedImage. size size BufferedImage/TYPE_INT_ARGB)
        g2   (doto (.createGraphics img)
               (.setRenderingHint RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
               (.setRenderingHint RenderingHints/KEY_STROKE_CONTROL RenderingHints/VALUE_STROKE_PURE)
               (.setColor color)
               (.setStroke (BasicStroke. (float (* 1.7 s))
                                         BasicStroke/CAP_ROUND
                                         BasicStroke/JOIN_ROUND)))
        X    (fn [v] (* s (double v)))
        line! (fn [x1 y1 x2 y2]
                (.draw g2 (java.awt.geom.Line2D$Double. (X x1) (X y1) (X x2) (X y2))))
        fill-oval! (fn [cx cy r]
                     (.fill g2 (java.awt.geom.Ellipse2D$Double.
                                (- (X cx) (X r)) (- (X cy) (X r))
                                (X (* 2 r)) (X (* 2 r)))))
        fill-round! (fn [x y w h arc]
                      (.fill g2 (java.awt.geom.RoundRectangle2D$Double.
                                 (X x) (X y) (X w) (X h) (X arc) (X arc))))]
    (case kind
      ;; paper plane, solid
      :send
      (let [p (Path2D$Double.)]
        (.moveTo p (X 2) (X 3))
        (.lineTo p (X 16.5) (X 9))
        (.lineTo p (X 2) (X 15))
        (.lineTo p (X 6.5) (X 9))
        (.closePath p)
        (.fill g2 p))

      ;; solid rounded square — the universal stop
      :stop
      (fill-round! 5 5 8 8 2)

      ;; ">_" shell prompt
      :terminal
      (do (line! 4 5 9 9)
          (line! 9 9 4 13)
          (line! 11.5 13.5 15 13.5))

      ;; sliders (modern "settings/tune"): three tracks with knobs
      :settings
      (do (line! 3 5 15 5)
          (line! 3 9 15 9)
          (line! 3 13 15 13)
          (fill-oval! 7 5 2)
          (fill-oval! 12 9 2)
          (fill-oval! 6 13 2))

      ;; download into a tray
      :export
      (do (line! 9 3 9 11)
          (line! 5 7 9 11)
          (line! 13 7 9 11)
          (line! 4 12 4 15)
          (line! 4 15 14 15)
          (line! 14 15 14 12))

      ;; "</>" code glyph
      :html
      (do (line! 7 6 4 9)
          (line! 4 9 7 12)
          (line! 11 6 14 9)
          (line! 14 9 11 12)
          (line! 10 5 8 13))

      ;; clear — bold X
      :clear
      (do (line! 5 5 13 13)
          (line! 13 5 5 13))

      ;; microphone (push-to-talk)
      :mic
      (do (fill-round! 7 2.5 4 8 2)
          (let [p (Path2D$Double.)]
            (.moveTo p (X 4.5) (X 8.5))
            (.curveTo p (X 4.5) (X 13) (X 13.5) (X 13) (X 13.5) (X 8.5))
            (.draw g2 p))
          (line! 9 12.5 9 15.5)
          (line! 6.5 15.5 11.5 15.5))

      ;; fallback: a dash (never throws on an unknown kind)
      (line! 4 9 14 9))
    (.dispose g2)
    (ImageIcon. img)))

(defn styled-button
  "A rounded-corner dark JButton with readable light text."
  ^JButton [text]
  (let [b (proxy [JButton] []
            (paintComponent [g]
              (paint-rounded! g this)
              (proxy-super paintComponent g)))
        _ (.setText b text)]
    (-> b
        (style!)
        (doto (.setBorder (javax.swing.BorderFactory/createEmptyBorder 8 18 8 18))))))

(defn toolbar-button
  "An icon-only rounded dark button for toolbars, with hover tooltip help.
  `icon-kind` is one of `action-icon`'s kinds (e.g. :send :stop :terminal
  :settings :export :clear)."
  ^JButton [icon-kind tooltip]
  (let [b (proxy [JButton] []
            (paintComponent [g]
              (paint-rounded! g this)
              (proxy-super paintComponent g)))]
    (-> b
        (style!)
        (doto (.setIcon (action-icon icon-kind))
              (.setToolTipText tooltip)
              (.setHorizontalAlignment SwingConstants/CENTER)
              (.setFocusable false)
              (.setBorder (javax.swing.BorderFactory/createEmptyBorder 6 10 6 10))))))

(defn swatch-button
  "A dark styled-button with a small colour square on the left. `get-color` is a
  no-arg fn returning the current java.awt.Color (evaluated each repaint, so it
  updates live after the user picks a new colour)."
  ^JButton [text get-color]
  (let [sw 20
        b (proxy [JButton] []
              (paintComponent [g]
                (paint-rounded! g this)
                (let [h (.getHeight this)
                      g2 (doto (.create ^Graphics g)
                           (.setRenderingHint RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
                           (.setColor (get-color))
                           (.fillRoundRect 12 (int (/ (- h sw) 2)) sw sw 4 4))]
                  (.dispose g2))
                (proxy-super paintComponent g)))
        _ (.setText b text)]
    (-> b
        (style!)
        (doto (.setBorder (javax.swing.BorderFactory/createEmptyBorder 8 44 8 18))))))
