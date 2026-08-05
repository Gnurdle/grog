(ns grog.ui.widgets
  "Shared rounded dark buttons used across the GUI (main page and settings), so
  text is always readable on the dark theme."
  (:import (java.awt BasicStroke Color Cursor Font Graphics RenderingHints)
           (javax.swing JButton SwingConstants)
           (javax.swing.border Border)))

(def ui-ui-font (Font. "SansSerif" Font/PLAIN 17))

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
    (.setFont ui-ui-font)
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

(defn swatch-button
  "A dark styled-button with a small colour square on the left. `get-color` is a
  no-arg fn returning the current java.awt.Color (evaluated each repaint, so it
  updates live after the user picks a new colour)."
  ^JButton [text get-color]
  (let [sw 20]
    (let [b (proxy [JButton] []
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
          (doto (.setBorder (javax.swing.BorderFactory/createEmptyBorder 8 44 8 18)))))))
