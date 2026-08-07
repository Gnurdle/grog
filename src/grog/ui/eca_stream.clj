(ns grog.ui.eca-stream
  "Renders ECA `chat/contentReceived` content events onto the grog transcript pane.

  The caller binds `*out*` to `grog.ui.transcript/styled-writer` (which parses
  ANSI SGR into styled runs on the EDT), then calls `render-content!` for each
  `:content` object in a `chat/contentReceived` notification. Runs on the ECA
  reader thread; the styled-writer marshals to the EDT."
  (:require [clojure.string :as str]
            [grog.appearance :as appearance]))

(def ^:private ansi-reset "\u001B[0m")

(defn- say! [& xs]
  (apply println xs)
  (flush))

(defn- tool-banner
  "A compact one-line banner for a tool-call content event."
  [content]
  (let [name (:name content)
        server (:server content)
        summary (:summary content)]
    (str "── tool " name
         (when (seq server) (str " [" server "]"))
         (when (seq summary) (str " — " (str/trim summary)))
         " ──")))

(defn render-content!
  "Append one ECA `:content` object to the pane (via the bound `*out*` writer)."
  [^javax.swing.JTextPane _pane content]
  (when (map? content)
    (case (:type content)
      "text"
      (let [t (str (:text content))]
        (when (seq t)
          (say! (appearance/ansi-answer) t ansi-reset)))

      "reasonStarted"
      (say! (appearance/ansi-thinking) "── thinking ──" ansi-reset)

      "reasonText"
      (let [t (str (:text content))]
        (when (seq t)
          (say! (appearance/ansi-thinking) t ansi-reset)))

      "reasonFinished"
      (say! (appearance/ansi-thinking) ansi-reset)

      "toolCallPrepare" nil

      "toolCallRun"
      (say! (appearance/ansi-tool-call) (tool-banner content)
            (when (true? (:manualApproval content)) "  ⚠ approval required")
            ansi-reset)

      "toolCallRunning"
      (say! (appearance/ansi-tool-call) (str "   running " (:name content) "…")
            ansi-reset)

      "toolCalled"
      (let [err (:error content)]
        (say! (appearance/ansi-tool-call)
              (str "   " (if err "✗" "✓") " " (:name content)
                   (when-let [ms (:totalTimeMs content)] (str " (" ms "ms)")))
              ansi-reset))

      "toolCallRejected"
      (say! (appearance/ansi-tool-call) (str "   ✗ " (:name content) " rejected")
            ansi-reset)

      "metadata"
      (let [t (:title content)]
        (when (seq t) (say! (str "[" t "]"))))

      "flag"
      (let [t (:text content)]
        (when (seq t) (say! (str "[" t "]"))))

      "usage" nil
      "progress" nil
      nil)))
