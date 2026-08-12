(ns grog.ui.export
  "Export the chat transcript (a virtualized JList line model) to a standalone,
  colour-preserving HTML file.

  The transcript pane holds the *entire* conversation as coloured line runs —
  user echoes, thinking, tool calls, answers. This namespace converts those runs
  (`{:text ... :color ... :italic ...}` vectors from `grog.ui.transcript/lines`)
  into inline-CSS spans, so the exported file keeps all the intermediate text
  and its original colouring, and opens in any browser."
  (:require [clojure.string :as str]
            [grog.ui.transcript :as transcript])
  (:import (java.awt Color)
           (java.io File)
           (javax.swing JFileChooser)
           (javax.swing.filechooser FileNameExtensionFilter)))

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

(defn- run->html
  "One coloured run as an (escaped) inline-styled span."
  ^String [{:keys [^String text color italic]}]
  (let [esc  (escape-html text)
        style (str (when (and color (not= color Color/BLACK))
                     (str "color:" (hex-color color) ";"))
                   (when italic "font-style:italic;"))]
    (if (seq style)
      (str "<span style=\"" style "\">" esc "</span>")
      esc)))

(defn lines->html
  "Convert a seq of run-lines into a <pre> HTML fragment preserving colours."
  ^String [lines]
  (->> lines
       (map (fn [runs]
              (str (apply str (map run->html runs)) "\n")))
       (apply str)))

(defn ->standalone-html
  "Wrap run-lines in a complete, self-contained HTML document with a dark
  monospace theme so colours read as in the chat window."
  ^String [lines]
  (str "<!DOCTYPE html>\n<html>\n<head>\n"
       "<meta charset=\"utf-8\">\n"
       "<title>grog conversation</title>\n"
       "<style>"
       "body{background:#000;color:#d5d5d5;font-family:Monaco,Menlo,Consolas,monospace;padding:16px;}"
       "pre{white-space:pre-wrap;word-wrap:break-word;margin:0;}"
       "</style>\n</head>\n<body>\n<pre>"
       (lines->html lines)
       "</pre>\n</body>\n</html>\n"))

(defn save-transcript!
  "Open a save dialog (modal) and write the chat transcript to an HTML file,
  preserving all styling. Returns the chosen File, or nil if the user cancelled.
  Safe to call on the EDT (the blocking dialog pumps events normally)."
  [^java.awt.Window owner ^java.awt.Component pane]
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
        (spit f (->standalone-html (transcript/lines pane)))
        f))))