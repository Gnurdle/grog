(ns grog.ui.eca-stream
  "Renders ECA `chat/contentReceived` content events onto the grog transcript pane.

  The caller binds `*out*` to `grog.ui.transcript/styled-writer` (which parses
  ANSI SGR into styled runs on the EDT), then feeds each `:content` object to a
  stateful renderer returned by `make-streamer`. Runs on the ECA reader thread;
  the styled-writer marshals to the EDT.

  Streamed thinking (`reasonText`) prints inline for a continuous feel; answer
  `text` is rendered as Markdown blocks (GFM tables → box tables) via
  `grog.md-stream`. Block boundaries (thinking start/finish, tool calls,
  metadata) close any in-progress line."
  (:require [clojure.string :as str]
            [grog.appearance :as appearance]
            [grog.md-stream :as md-stream]))

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

(defn make-streamer
  "A stateful renderer `(fn [content])`. Thinking (`reasonText`) and other
  blocks stream inline as before, but answer `text` is fed through the shared
  Markdown→ANSI streamer (`grog.md-stream`), so GFM pipe tables render as box
  tables and complete blocks emit as they close (matching the CLI's
  `:chat-stream-live-markdown` behavior)."
  []
  (let [open? (atom false)                      ; an inline streamed block is being printed
        md    (atom (md-stream/empty-state))]   ; answer Markdown buffer
    (fn [content]
      (when (map? content)
        (let [close-line! (fn []
                            (when @open?
                              (print ansi-reset)
                              (println)
                              (flush)
                              (reset! open? false)))
              open-line! (fn [^String color]
                           (when @open? (println) (flush))
                           (reset! open? true)
                           (print color)
                           (flush))
              emit-md! (fn [blocks]
                         (doseq [b blocks]
                           (close-line!)
                           (print b)
                           (flush)))]
          (case (:type content)
            "text"
            (let [txt (str (:text content))]
              (when (seq txt)
                (let [[emitted new-state] (md-stream/feed @md txt)]
                  (reset! md new-state)
                  (emit-md! emitted))))

            "reasonText"
            (let [txt (str (:text content))]
              (when (seq txt)
                (when-not @open? (open-line! (appearance/ansi-thinking)))
                (print txt)
                (flush)))

            "reasonStarted"
            (do (close-line!)
                (say! (appearance/ansi-thinking) "── thinking ──" ansi-reset))

            "reasonFinished"
            (close-line!)

            "toolCallPrepare" nil

            "toolCallRun"
            (do (close-line!)
                (say! (appearance/ansi-tool-call) (tool-banner content)
                      (when (true? (:manualApproval content)) "  ⚠ approval required")
                      ansi-reset))

            "toolCallRunning"
            (do (close-line!)
                (say! (appearance/ansi-tool-call) (str "   running " (:name content) "…")
                      ansi-reset))

            "toolCalled"
            (do (close-line!)
                (let [err (:error content)]
                  (say! (appearance/ansi-tool-call)
                        (str "   " (if err "✗" "✓") " " (:name content)
                             (when-let [ms (:totalTimeMs content)] (str " (" ms "ms)")))
                        ansi-reset)))

            "toolCallRejected"
            (do (close-line!)
                (say! (appearance/ansi-tool-call) (str "   ✗ " (:name content) " rejected")
                      ansi-reset))

            "metadata"
            (do (close-line!)
                (let [t (:title content)]
                  (when (seq t) (say! (str "[" t "]")))))

            "flag"
            (do (close-line!)
                (let [t (:text content)]
                  (when (seq t) (say! (str "[" t "]")))))

            "usage" nil

            "progress"
            (when (= "finished" (:state content))
              (close-line!)
              ;; flush any answer Markdown still buffered at end of stream
              (let [[emitted new-state] (md-stream/finish @md)]
                (reset! md new-state)
                (emit-md! emitted)))

            nil))))))
