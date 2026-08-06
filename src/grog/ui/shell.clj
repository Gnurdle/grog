(ns grog.ui.shell
  "A real terminal window for the grog GUI, using the JediTerm terminal emulator
  over a PTY (pty4j). The shell runs under a genuine pseudo-terminal and is
  rendered by a proper ANSI terminal emulator, so you get a real interactive
  shell: prompt, colors, line editing, history, and full-screen TUI apps."

  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:require [grog.appearance :as appearance]
            [grog.ui.fonts :as uifonts])
  (:import (java.awt BorderLayout Font)
           (java.awt.event WindowAdapter)
           (java.nio.charset Charset)
           (java.util HashMap)
           (javax.swing JFrame)
           (com.jediterm.core Color)
           (com.jediterm.terminal ProcessTtyConnector)
           (com.jediterm.terminal.emulator ColorPalette)
           (com.jediterm.terminal.ui JediTermWidget)
           (com.jediterm.terminal.ui.settings DefaultSettingsProvider)
           (com.pty4j PtyProcess PtyProcessBuilder)))

(defn- shell-command
  "Resolve the [binary & args] to run in the PTY: $SHELL (fallback bash),
  launched with a clean rc so JediTerm renders a clean, single-echo shell."
  ^"[Ljava.lang.String;" []
  (let [sh (or (not-empty (System/getenv "SHELL")) "bash")]
    (into-array String [sh])))

(defn- empty-zdotdir!
  "Create/return an empty dir to use as ZDOTDIR so zsh loads no user rc (and
  won't run its first-run new-user install wizard or a fancy cursor-heavy
  theme that causes redraw/double-echo artifacts)."
  []
  (let [d (io/file (System/getProperty "java.io.tmpdir") "grog-zdot")]
    (when-not (.exists d) (.mkdirs d))
    (let [f (io/file d ".zshrc")]
      (when-not (.exists f)
        (spit f "\nPROMPT='%m:%~ %# '\nRPROMPT=''\n")))
    (str d)))

(defn- start-pty
  "Start the shell under a PTY with a clean environment. Returns the PtyProcess
  or nil on error."
  []
  (try
    (let [env (HashMap. (System/getenv))
          _   (.put env "TERM" "xterm-256color")
          _   (.put env "ZDOTDIR" (empty-zdotdir!))
          b   (doto (PtyProcessBuilder.)
                (.setCommand (shell-command))
                (.setEnvironment env)
                (.setConsole false))]
      (.start b))
    (catch Throwable _ nil)))

(defn- rgb->jcolor [[r g b]] (com.jediterm.core.Color. (int r) (int g) (int b)))

(defn- terminal-settings
  "A SettingsProvider that themes the JediTerm terminal from global appearance."
  []
  (let [fg #(rgb->jcolor (appearance/terminal-fg))
        bg #(rgb->jcolor (appearance/terminal-bg))
        size (uifonts/terminal-font-size)
        fam (appearance/terminal-font-family)
        palette
        (proxy [ColorPalette] []
          (getForegroundByColorIndex [_] (fg))
          (getBackgroundByColorIndex [_] (bg))
          (getForeground [tc] (fg))
          (getBackground [tc] (bg)))]
    (proxy [DefaultSettingsProvider] []
      (getTerminalColorPalette [] palette)
      (getTerminalFont [] (Font. fam Font/PLAIN size))
      (getTerminalFontSize [] size))))

(defn make-shell-frame
  "Build and return the real terminal `JFrame` (JediTerm widget over a PTY).
  Closing the frame closes the connector and destroys the process. Call on the
  EDT."
  ^JFrame []
  (let [sh-name (or (not-empty (System/getenv "SHELL")) "bash")
        ^PtyProcess proc (start-pty)]
    (if (nil? proc)
      (doto (JFrame. (str "grog terminal — failed to start PTY (" sh-name ")"))
        (.setSize 820 540))
      (let [connector
            (proxy [ProcessTtyConnector] [proc (Charset/forName "UTF-8")]
              (getName [] (str "grog-" sh-name)))
            widget (JediTermWidget. 100 30 (terminal-settings))
            frame (JFrame. (str "grog terminal — " sh-name))]
        (.setTtyConnector widget connector)
        (.start widget)
        (let [panel (.getTerminalPanel widget)]
          ;; paint the terminal surface from the appearance config
          (let [[r g b] (appearance/terminal-bg)]
            (.setBackground panel (java.awt.Color. (int r) (int g) (int b))))
          (let [[r g b] (appearance/terminal-fg)]
            (.setForeground panel (java.awt.Color. (int r) (int g) (int b))))
          (.setOpaque panel true)
          ;; register for shared zoom / colour refresh
          (uifonts/register-term! panel))
        (uifonts/install-zoom-bindings! (.getRootPane frame))
        (.setLayout frame (BorderLayout.))
        (.add frame (.getTerminalPanel widget) BorderLayout/CENTER)
        (.addWindowListener
          frame
          (proxy [WindowAdapter] []
            (windowClosing [e]
              (try (.close connector) (catch Throwable _))
              (try (.destroy proc) (catch Throwable _)))))
        (.setSize frame 860 560)
        (.setLocationRelativeTo frame nil)
        frame))))
