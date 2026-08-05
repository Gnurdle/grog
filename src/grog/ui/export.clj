(ns grog.ui.export
  "Export the chat transcript (a Swing StyledDocument) to a standalone,
  colour-preserving HTML file.

  The transcript pane holds the *entire* conversation as styled runs — user
  echoes, thinking, tool calls, answers — with the streaming ANSI colours and
  italics already decoded into Swing character attributes (foreground + italic)
  by `grog.ui.transcript`. This namespace walks the document run-by-run and
  re-emits those attributes as inline CSS, so the exported file keeps all the
  intermediate text and its original colouring, and opens in any browser."
  (:require [clojure.string :as str])
  (:import (java.awt Color)
           (java.io File)
           (javax.swing JFileChooser JTextPane)
           (javax.swing.filechooser FileNameExtensionFilter)
           (javax.swing.text StyleConstants StyledDocument)))

(defn- hex-color
  ^String [^Color c]
  (format "#%02X%02X%02X" (.getRed c) (.getGreen c) (.getBlue c)))

(defn- escape-html
  "Escape text for safe inclusion inside an HTML element body."
  ^String [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn doc->html
  "Convert `doc` into an HTML fragment (meant to live inside a <pre>) that
  preserves each run's foreground colour and italic flag as inline `style`.
  Runs without colour/italic are emitted as plain (escaped) text. Colour is
  emitted whenever it differs from plain black."
  [^StyledDocument doc]
  (let [len (.getLength doc)
        sb  (StringBuilder.)]
    (loop [pos 0]
      (when (< pos len)
        (let [elem   (.getCharacterElement doc pos)
              end    (min (.getEndOffset elem) len)
              color  (StyleConstants/getForeground elem)
              italic (StyleConstants/isItalic elem)
              text   (.getText doc pos (max 0 (- end pos)))]
          (let [esc   (escape-html text)
                style (str (when (and color (not= color Color/BLACK))
                             (str "color:" (hex-color color) ";"))
                           (when italic "font-style:italic;"))]
            (if (seq style)
              (.append sb (str "<span style=\"" style "\">" esc "</span>"))
              (.append sb esc)))
          (recur (max (inc pos) end)))))
    (.toString sb)))

(defn ->standalone-html
  "Wrap `doc` in a complete, self-contained HTML document with a dark
  monospace theme so colours read as in the chat window."
  ^String [^StyledDocument doc]
  (str "<!DOCTYPE html>\n<html>\n<head>\n"
       "<meta charset=\"utf-8\">\n"
       "<title>grog conversation</title>\n"
       "<style>"
       "body{background:#000;color:#d5d5d5;font-family:Monaco,Menlo,Consolas,monospace;padding:16px;}"
       "pre{white-space:pre-wrap;word-wrap:break-word;margin:0;}"
       "</style>\n</head>\n<body>\n<pre>"
       (doc->html doc)
       "</pre>\n</body>\n</html>\n"))

(defn save-transcript!
  "Open a save dialog (modal) and write `pane`'s transcript to an HTML file,
  preserving all styling. Returns the chosen File, or nil if the user cancelled.
  Safe to call on the EDT (the blocking dialog pumps events normally)."
  [^java.awt.Window owner ^JTextPane pane]
  (let [chooser (doto (JFileChooser.)
                  (.setDialogTitle "Export conversation as HTML")
                  (.setFileFilter (FileNameExtensionFilter. "HTML files (*.html)" (into-array ["html"])))
                  (.setSelectedFile (File. "grog-conversation.html")))
        ret (.showSaveDialog chooser owner)]
    (when (= ret JFileChooser/APPROVE_OPTION)
      (let [f (.getSelectedFile chooser)
            f (if (str/ends-with? (str/lower-case (.getName f)) ".html")
                f
                (File. (str (.getAbsolutePath f) ".html")))]
        (spit f (->standalone-html (.getStyledDocument pane)))
        f))))
