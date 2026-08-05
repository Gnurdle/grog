(ns grog.ui.fonts
  "Shared font/color state for the chat and terminal windows, driven by
  Ctrl+Shift+Plus / Ctrl+Shift+Minus and by the Appearance settings tab. All
  values live in `grog.appearance` so the settings GUI and the shortcuts write
  through the same source."
  (:require [grog.appearance :as appearance])
  (:import (java.awt Color Font)
           (java.awt.event InputEvent KeyEvent)
           (javax.swing AbstractAction JComponent KeyStroke)))

(defonce chat-targets (atom []))   ; each entry: [JTextComponent kind] where kind :: :transcript | :prompt
(defonce term-panel (atom nil))

(declare apply-to-term! rgb->awt)

(defn register-chat!
  "Add a JTextComponent (chat transcript or prompt) to the font/zoom set."
  [^javax.swing.text.JTextComponent c kind]
  (swap! chat-targets conj [c kind]))

(defn register-term!
  "Record the JediTerm TerminalPanel so terminal zoom/colour updates re-render."
  [p]
  (reset! term-panel p)
  (apply-to-term!))

(defn chat-font-size [] (appearance/chat-font-size))
(defn terminal-font-size []
  (float (or (appearance/terminal-font-size) 18)))

(defn chat-font ^Font []
  (Font. (appearance/chat-font-family) Font/PLAIN (appearance/chat-font-size)))

(defn- clamp [n] (max 8 (min 48 n)))

(defn apply-chat-fonts!
  "Re-apply the current chat font to all panes; chat-background colour only to the
  prompt box (the transcript stays transparent over the logo)."
  []
  (let [f (chat-font)
        bg (rgb->awt (appearance/chat-bg))]
    (doseq [[c kind] @chat-targets]
      (.setFont c f)
      (when (= kind :prompt)
        (.setOpaque c true)
        (.setBackground c bg)))))

(defn- rgb->awt [[r g b]] (Color. (int r) (int g) (int b)))

(defn apply-to-term!
  "Re-read terminal appearance (font size + foreground/background) and refresh
  the panel."
  []
  (when-let [p ^javax.swing.JComponent @term-panel]
    (try
      (let [m (.getDeclaredMethod (class p) "reinitFontAndResize"
                                  (into-array Class []))]
        (.setAccessible m true)
        (.invoke m p (object-array 0)))
      (catch Throwable _))
    (.setForeground p (rgb->awt (appearance/terminal-fg)))
    (.setBackground p (rgb->awt (appearance/terminal-bg)))
    (.repaint p)))

(defn apply-all!
  "Re-apply appearance (fonts + colours) to chat and terminal."
  []
  (apply-chat-fonts!)
  (apply-to-term!))

(defn zoom!
  "Adjust the shared font size by `delta` (both chat and terminal), persist via
  appearance, and re-apply."
  [delta]
  (appearance/set-of! [:chat :font-size] (clamp (+ (appearance/chat-font-size) delta)))
  (appearance/set-of! [:terminal :font-size] (clamp (+ (appearance/terminal-font-size) delta)))
  (apply-all!))

(defn install-zoom-bindings!
  "Bind Ctrl+Shift+Plus / Ctrl+Shift+Minus on `root` (window-wide) to zoom all
  registered text components."
  [^JComponent root]
  (let [im (.getInputMap root JComponent/WHEN_IN_FOCUSED_WINDOW)
        am (.getActionMap root)
        mask (bit-or (int InputEvent/CTRL_DOWN_MASK) (int InputEvent/SHIFT_DOWN_MASK))]
    (doseq [k [KeyEvent/VK_PLUS KeyEvent/VK_ADD KeyEvent/VK_EQUALS]]
      (.put im (KeyStroke/getKeyStroke (int k) mask) "grog-zoom-in"))
    (doseq [k [KeyEvent/VK_MINUS KeyEvent/VK_SUBTRACT]]
      (.put im (KeyStroke/getKeyStroke (int k) mask) "grog-zoom-out"))
    (.put am "grog-zoom-in"
          (proxy [AbstractAction] [] (actionPerformed [_] (zoom! 1))))
    (.put am "grog-zoom-out"
          (proxy [AbstractAction] [] (actionPerformed [_] (zoom! -1))))))
