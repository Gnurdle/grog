(ns grog.ui.export
  "Export the chat transcript (the rich message model) to a standalone,
   readable HTML file: user messages as bubbles, assistant replies rendered as
   Markdown (headings, code, GFM tables) via CommonMark's HtmlRenderer, and
   thinking / tool / status entries as tagged sections.

   Table handling is intentionally defensive: GFM tables are detected both by
   CommonMark (proper delimiter row) AND by a hand-rolled pipe-table fallback
   for the very common case where a model emits a table without the
   `---|---` separator row (CommonMark then treats it as a paragraph and the
   pipe text renders as a literal line, which looks like a broken table)."
  (:require [clojure.string :as str]
            [grog.ui.transcript :as transcript]
            [grog.ui.widgets :as widgets])
  (:import (java.io File)
           (java.awt BorderLayout Toolkit)
           (java.awt.datatransfer StringSelection)
           (java.util Arrays)
           (javax.swing JDialog JEditorPane JFileChooser JPanel JScrollPane)
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

(defn- strip-markdown-tags
  "Remove `<text/markdown>` / `<text/markdown/>` wrappers (case-insensitive) so
  wrapped tables/noise never leak into the exported HTML."
  ^String [^String s]
  (-> (or s "")
      (str/replace #"(?is)<text/markdown/?>" "")
      (str/replace #"(?is)<text/markdown>" "")
      (str/trim)))

(defn- looks-like-pipe-table?
  "Heuristic: at least two lines starting with `|` (when trimmed), i.e. a
  pipe-delimited block even if the model omitted the GFM `---|---` delimiter
  row (CommonMark then treats it as a paragraph)."
  [^String text]
  (let [lines (->> (str/split (strip-markdown-tags text) #"\n")
                   (map str/trim)
                   (remove str/blank?))]
    (and (>= (count lines) 2)
         (>= (count (filter #(str/starts-with? % "|") lines)) 2))))

(defn- pipe-cells
  "Split one text line on unescaped pipes into cell strings."
  ^java.util.List [^String line]
  (->> (str/split (str/trim line) #"(?<!\\)\|")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- pipe-table-html
  "Render a pipe table (even without a GFM delimiter row) to a `<table>`; cells
  are HTML-escaped, and the first line is treated as the header."
  ^String [^String text]
  (let [lines (->> (str/split (strip-markdown-tags text) #"\n")
                   (map str/trim)
                   (remove str/blank?))
        lines (remove #(re-matches #"(?i)\|?\s*:?-{3,}" %) lines)
        header (-> (first lines) pipe-cells)
        ncols (count header)
        body (map pipe-cells (rest lines))
        row-html (fn [cells]
                   (str "<tr>"
                        (apply str (map (fn [c]
                                          (str "<td>" (escape-html (or c "")) "</td>"))
                                        (take ncols (concat cells (repeat "")))))
                        "</tr>"))]
    (str "<table>"
         "<thead><tr>"
         (apply str (map (fn [c] (str "<th>" (escape-html (or c "")) "</th>")) header))
         "</tr></thead>"
         "<tbody>"
         (apply str (map row-html body))
         "</tbody></table>")))

(defn- md->html
  "Render a Markdown string to GFM-aware HTML. Strips `<text/markdown>` wrappers,
   and falls back to a hand-rolled pipe table when CommonMark doesn't recognize
   one (e.g. a missing `---|---` delimiter row). On any other error, escapes."
  ^String [^String md]
  (try
    (let [clean (strip-markdown-tags md)
          html (-> (str clean)
                   (as-> body
                     (let [exts (extensions)
                           parser (.build (.extensions (Parser/builder) exts))
                           doc (.parse parser body)
                           renderer (.build (.extensions (HtmlRenderer/builder) exts))]
                       (.render renderer doc))))]
      ;; If CommonMark produced no table block but the text is shaped like a pipe
      ;; table, render one ourselves so the cells don't appear as raw `|` text.
      (if (and (not (str/includes? html "<table>"))
               (looks-like-pipe-table? md))
        (pipe-table-html md)
        html))
    (catch Throwable _
      (escape-html md))))

(defn- message-html
  "One HTML fragment per message."
  ^String [m]
  (case (:type m)
    :user      (str "<div class='msg user'><div class='bubble'>"
                    (escape-html (:text m)) "</div></div>")
    :assistant (str "<div class='msg assistant'>" (md->html (:text m)) "</div>")
    :thinking  (let [body (md->html (:text m))]
                 ;; Same affordance as the live transcript: a `+`/`-` header
                 ;; that toggles the body on click (native <details>). Honors
                 ;; the on-screen collapse state via the `open` attribute.
                 (if (:open? m)
                   (str "<div class='msg thinking'><details open>"
                        "<summary><span class='disc'>-</span> thinking</summary>"
                        body "</details></div>")
                   (str "<div class='msg thinking'><details>"
                        "<summary><span class='disc'>+</span> thinking</summary>"
                        body "</details></div>")))
    :tool      (let [st (or (:status m) :preparing)
                     icon (case st :done "ok" :error "!!" :rejected "no" "...")
                     name (str (:name m))
                     header (str icon " " name
                                 (when-let [ms (:ms m)] (str "  " ms "ms"))
                                 (when (seq (:server m)) (str "  [" (:server m) "]")))
                     body (str (when (seq (:summary m))
                                 (str "<div class='sum'>" (escape-html (:summary m)) "</div>"))
                               (when (seq (pr-str (:args m)))
                                 (str "<div class='args'><pre>" (escape-html (pr-str (:args m)))
                                      "</pre></div>")))]
                 (if (:expanded? m)
                   (str "<div class='msg tool'><details open>"
                        "<summary><span class='disc'>-</span> " header "</summary>"
                        body "</details></div>")
                   (str "<div class='msg tool'><details>"
                        "<summary><span class='disc'>+</span> " header "</summary>"
                        body "</details></div>")))
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
       ".msg.thinking details,.msg.tool details{margin-top:2px}"
       ".msg.thinking summary,.msg.tool summary{cursor:pointer;font-size:13px;user-select:none;list-style:none;display:flex;align-items:baseline;gap:6px}"
       ".msg.thinking summary::-webkit-details-marker,.msg.tool summary::-webkit-details-marker{display:none}"
       ".msg.thinking summary{color:#69b478}.msg.thinking summary:hover{opacity:1}.msg.thinking summary{opacity:.9}"
       ".msg.tool summary{color:#ffa05a;opacity:.95}.msg.tool summary:hover{opacity:1}"
       ".msg.thinking details[open] summary,.msg.tool details[open] summary{margin-bottom:6px}"
       ".disc{display:inline-block;min-width:1em;font-weight:700}"
       ".msg.tool .args,.msg.tool .sum{margin:6px 0 0 22px;font-size:12px;font-family:ui-monospace,Menlo,Consolas,monospace}"
       ".msg.tool .args pre{background:#161e24;border:1px solid #35383f;border-radius:8px;padding:8px;overflow:auto;margin:0;white-space:pre-wrap;word-break:break-word}"
       ".msg.tool .sum{color:#828692}"
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

;; --- HTML viewer (Swing's built-in HTMLEditorKit) --------------------------

(defn- copy-html! [^String html]
  (when (seq html)
    (.setContents (.getSystemClipboard (Toolkit/getDefaultToolkit))
                  (StringSelection. html)
                  nil)))

(defn show-html!
  "Open a modal dialog that renders `html` with Swing's built-in HTML engine
   (HTMLEditorKit via a read-only JEditorPane). `owner` is the parent Window (or
   nil). No new external dependency — this is the JVM's own text/html renderer."
  [^java.awt.Window owner ^String html]
  (let [html (str (or html ""))
        pane (doto (JEditorPane.)
               (.setContentType "text/html")
               (.setEditable false)
               (.setText html)
               (.setCaretPosition 0))
        copy-btn (widgets/styled-button "Copy HTML")
        close-btn (widgets/styled-button "Close")
        btns (doto (JPanel. (java.awt.FlowLayout. java.awt.FlowLayout/RIGHT))
               (.add copy-btn)
               (.add close-btn))
        dlg (JDialog. owner "grog — transcript (HTML)" true)]
    (.addActionListener close-btn
      (reify java.awt.event.ActionListener
        (actionPerformed [_ _] (.dispose dlg))))
    (.addActionListener copy-btn
      (reify java.awt.event.ActionListener
        (actionPerformed [_ _] (copy-html! html))))
    (doto dlg
      (.setLayout (BorderLayout.))
      (.add (JScrollPane. pane) BorderLayout/CENTER)
      (.add btns BorderLayout/SOUTH)
      (.setSize 880 620)
      (.setLocationRelativeTo owner)
      (.setVisible true))
    dlg))

(defn show-transcript-html!
  "Open the current transcript as HTML in a modal viewer (same output as
   Save-as-HTML, but rendered live with Swing's built-in HTML engine)."
  [^java.awt.Window owner pane]
  (show-html! owner (->standalone-html (transcript/messages pane))))