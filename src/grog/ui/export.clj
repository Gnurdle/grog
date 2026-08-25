(ns grog.ui.export
  "Export the chat transcript (the rich message model) to a standalone,
   readable HTML file: user messages as bubbles, assistant replies rendered as
   Markdown (headings, code, GFM tables) via CommonMark's HtmlRenderer, and
   thinking / tool / status entries as tagged sections."
  (:require [clojure.string :as str]
            [grog.ui.transcript :as transcript])
  (:import (java.io File)
           (java.util Arrays)
           (javax.swing JFileChooser)
           (javax.swing.filechooser FileNameExtensionFilter)
           (org.commonmark.ext.gfm.tables TablesExtension)
           (org.commonmark.parser Parser)
           (org.commonmark.renderer.html HtmlRenderer)))

(defn- escape-html
  "Escape text for safe inclusion inside an HTML element body."
  ^String [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- extensions []
  (Arrays/asList (into-array [(TablesExtension/create)])))

(defn- md->html
  "Render a Markdown string to GFM-aware HTML; falls back to escaped text."
  ^String [^String md]
  (try
    (let [exts (extensions)
          parser (.build (.extensions (Parser/builder) exts))
          doc (.parse parser (str md))
          renderer (.build (.extensions (HtmlRenderer/builder) exts))]
      (.render renderer doc))
    (catch Throwable _
      (escape-html md))))

(defn- message-html
  "One HTML fragment per message."
  ^String [m]
  (case (:type m)
    :user      (str "<div class='msg user'><div class='bubble'>"
                    (escape-html (:text m)) "</div></div>")
    :assistant (str "<div class='msg assistant'>" (md->html (:text m)) "</div>")
    :thinking  (str "<div class='msg thinking'><span class='tag'>thinking</span>"
                    (md->html (:text m)) "</div>")
    :tool      (str "<div class='msg tool'><span class='tag'>tool · "
                    (escape-html (str (:name m))) "</span> "
                    (escape-html (pr-str (:args m))) "</div>")
    :status    (str "<div class='msg status'>" (escape-html (:text m)) "</div>")
    :banner    (str "<div class='msg banner'>" (escape-html (:text m)) "</div>")
    ""))

(defn ->standalone-html
  "Wrap a seq of messages in a complete, self-contained HTML document."
  ^String [msgs]
  (str "<!DOCTYPE html>\n<html>\n<head>\n"
       "<meta charset=\"utf-8\">\n"
       "<title>grog conversation</title>\n"
       "<style>"
       "body{background:#0d0e11;color:#d5dfe8;font-family:ui-sans-serif,system-ui,'Segoe UI',Roboto,sans-serif;padding:24px;max-width:860px;margin:0 auto;line-height:1.55}"
       ".msg{margin:14px 0}"
       ".msg.user{display:flex;justify-content:flex-end}"
       ".bubble{max-width:70%;background:#2b2c36;color:#d8b96e;border-radius:14px;padding:10px 14px}"
       ".msg.assistant h1{font-size:1.5em}.msg.assistant h2{font-size:1.3em}.msg.assistant h3{font-size:1.15em}"
       ".msg.assistant pre{background:#161e24;border:1px solid #35383f;border-radius:8px;padding:10px;overflow:auto;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:13px}"
       ".msg.assistant code{background:#161e24;color:#9fd4f5;padding:1px 4px;border-radius:4px;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:13px}"
       ".msg.assistant pre code{background:none;padding:0}"
       ".msg.assistant blockquote{border-left:3px solid #69b478;margin:8px 0;padding-left:10px;color:#9aa4ad}"
       ".msg.assistant table{border-collapse:collapse;margin:10px 0}"
       ".msg.assistant th,.msg.assistant td{border:1px solid #35383f;padding:6px 10px}"
       ".msg.assistant th{background:#1a2028}"
       ".msg.thinking,.msg.status,.msg.banner{color:#828692;font-size:13px}"
       ".msg.thinking{color:#69b478}"
       ".tag{display:inline-block;font-size:11px;letter-spacing:.05em;background:#23272f;padding:1px 8px;border-radius:10px;margin-right:6px}"
       "</style>\n</head>\n<body>\n"
       (apply str (map message-html msgs))
       "\n</body>\n</html>\n"))

(defn save-transcript!
  "Open a save dialog (modal) and write the transcript to an HTML file.
   Returns the chosen File, or nil on cancel. Safe on the EDT."
  [^java.awt.Window owner ^java.awt.Component pane]
  (let [chooser (doto (JFileChooser.)
                  (.setDialogTitle "Export conversation as HTML")
                  (.setFileFilter (FileNameExtensionFilter.
                                   "HTML files (*.html)" (into-array ["html"])))
                  (.setSelectedFile (File. "grog-conversation.html")))
        ret (.showSaveDialog chooser owner)]
    (when (= ret JFileChooser/APPROVE_OPTION)
      (let [f (.getSelectedFile chooser)
            f (if (str/ends-with? (str/lower-case (.getName f)) ".html")
                f
                (File. (str (.getAbsolutePath f) ".html")))]
        (spit f (->standalone-html (transcript/messages pane)))
        f))))