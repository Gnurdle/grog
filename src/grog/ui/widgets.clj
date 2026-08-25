(ns grog.ui.widgets
  "Shared rounded dark buttons used across the GUI (main page and settings), so
  text is always readable on the dark theme. Dialog/button/status fonts are
  derived from the active Look & Feel's system font (see `scale-ui-fonts!`)."
  (:require [grog.appearance :as appearance])
  (:import (java.awt BasicStroke Color Cursor Font Graphics RenderingHints)
           (java.awt.geom Path2D$Double)
           (java.awt.image BufferedImage)
           (javax.swing ImageIcon JButton SwingConstants UIManager)))

(def ^:private ui-font-scale 1.5)
(def ^:private min-ui-size 19)
(def ^:private min-mono-size 18)
(def ^:private button-scale 1.3)
(def ^:private min-button-size 16)

(defn- lf-base-font
  "The active Look & Feel's base UI font (FlatLaf mirrors the desktop's system
  font + DPI settings). Falls back to a sensible default so font sizing always
  has a family and size to scale from."
  ^Font []
  (or (try (UIManager/getFont "Label.font") (catch Throwable _ nil))
      (try (UIManager/getFont "defaultFont") (catch Throwable _ nil))
      (Font. "SansSerif" Font/PLAIN 13)))

(defn- scaled-size
  "Round `(base-font-size * ui-font-scale)`, but never below `floor`."
  [floor]
  (max floor (int (Math/round (* (.getSize (lf-base-font)) ui-font-scale)))))

(defn ui-font-size
  "Font size for dialogs/buttons/status, from the system L&F font scaled up."
  []
  (scaled-size min-ui-size))

(defn mono-font-size
  "Monospace dialog font size, from the system L&F font scaled up."
  []
  (scaled-size min-mono-size))

(defn- button-size
  "Button font size: follows the system L&F font but stays compact (a footer of
  buttons full of 20pt text is too shouty; dialogs/status get the big scale)."
  []
  (max min-button-size (int (Math/round (* (.getSize (lf-base-font)) button-scale)))))

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
  "Moderate monospace font for dialog/approval body text. Unlike `mono-font`
  this stays at (roughly) the system L&F base size instead of the 1.5x dialog
  scale, and uses regular weight, so a long tool-call summary in an approval
  dialog reads as plain body text rather than towering bold monospace."
  ^Font []
  (Font. "Monospaced" Font/PLAIN (max 13 (int (.getSize (lf-base-font))))))

(defn scale-ui-fonts!
  "After the Look & Feel is installed, bump its base UI font keys so every
  component (settings/model-picker labels and lists, tooltips, option panes)
  inherits the larger system-derived font. Call once after FlatLaf setup and
  before building frames."
  []
  (let [f (ui-font)
        keys ["defaultFont" "Label.font" "Button.font" "TextField.font"
              "ComboBox.font" "List.font" "Spinner.font" "TabbedPane.font"
              "ToolTip.font" "CheckBox.font" "RadioButton.font"
              "OptionPane.messageFont" "OptionPane.buttonFont"
              "TitledBorder.font" "Menu.font" "MenuItem.font"]]
    (doseq [k keys]
      (try (UIManager/put k f) (catch Throwable _)))
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
  `kind` is one of :send :stop :terminal :settings :export :clear.
  Draws a simple stroked glyph in the given `color` (default button text color)."
  ^ImageIcon [kind & {:keys [color size]
                      :or {color (Color. 235 238 245) size 18}}]
  (let [img (BufferedImage. (int size) (int size) BufferedImage/TYPE_INT_ARGB)
        g2 (doto (.createGraphics img)
             (.setRenderingHint RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
             (.setColor color)
             (.setStroke (BasicStroke. 1.6)))]
    (letfn [(line! [x1 y1 x2 y2] (.drawLine g2 (int x1) (int y1) (int x2) (int y2)))
            (stroke! [p] (.draw g2 p))]
      (case kind
        :send
        (let [p (Path2D$Double.)]
          (.moveTo p 2 3)
          (.lineTo p 16 9)
          (.lineTo p 2 15)
          (.lineTo p 6 9)
          (.closePath p)
          (stroke! p))

        :stop
        (do (line! 5 5 13 5)
            (line! 13 5 13 13)
            (line! 13 13 5 13)
            (line! 5 13 5 5))

        :terminal
        (do (line! 3 5 9 9)
            (line! 9 9 3 13)
            (line! 11 11 15 11))

        :settings
        (let [p (Path2D$Double.)]
          ;; crude cog: four bars around a center hub
          (.moveTo p 9 2) (.lineTo p 9 7)
          (.moveTo p 9 11) (.lineTo p 9 16)
          (.moveTo p 2 9) (.lineTo p 7 9)
          (.moveTo p 11 9) (.lineTo p 16 9)
          (stroke! p)
          (.fillOval g2 6 6 6 6))

        :export
        (do (line! 9 2 9 11)
            (line! 5 8 9 11)
            (line! 13 8 9 11)
            (line! 3 15 15 15))

        :clear
        (do (line! 5 5 13 13)
            (line! 13 5 5 13))))

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
