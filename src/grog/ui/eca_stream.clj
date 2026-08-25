(ns grog.ui.eca-stream
  "Renders ECA `chat/contentReceived` content events onto the rich transcript.

  Unlike the old ANSI printer, this feeds the *structured* transcript API
  (grog.ui.transcript): assistant `text` streams into a live assistant message,
  thinking streams into a live thinking section, and tool events are drawn as
  tool cards. Interleaving (thinking → text → tool → text) is handled by closing
  the current live message whenever the kind of content changes, so the markdown
  renderer gets clean per-message blocks."
  (:require [grog.ui.transcript :as transcript]))

(defn- content-id [content]
  (or (:id content) (str (:name content))))

(defn make-streamer
  "A stateful renderer `(fn [content])`. `pane` is the transcript view from
  `grog.ui.transcript/chat-pane`. Runs on the ECA reader thread; the
  transcript API marshals to the EDT."
  [pane]
  (let [live (atom :none)]
    (fn [content]
      (when (map? content)
        (let [close-live! (fn []
                            (transcript/finish-assistant! pane)
                            (transcript/finish-thinking! pane)
                            (reset! live :none))]
          (case (:type content)
            "text"
            (do
              (when (not= :assistant @live)
                (when (= :thinking @live)
                  (transcript/finish-thinking! pane))
                (reset! live :assistant))
              (when-let [t (:text content)]
                (transcript/append-assistant! pane (str t))))

            "reasonText"
            (do
              (when (not= :thinking @live)
                (when (= :assistant @live)
                  (transcript/finish-assistant! pane))
                (transcript/start-thinking! pane)
                (reset! live :thinking))
              (when-let [t (:text content)]
                (transcript/append-thinking! pane (str t))))

            "reasonStarted"
            (do (close-live!)
                (transcript/start-thinking! pane)
                (reset! live :thinking))

            "reasonFinished"
            (do (transcript/finish-thinking! pane)
                (reset! live :none))

            "toolCallPrepare"
            (do (close-live!)
                (transcript/tool! pane
                                  {:key (content-id content)
                                   :name (:name content)
                                   :server (:server content)
                                   :args (or (:arguments content)
                                            (:argumentsText content)
                                            {})
                                   :status :preparing}))

            "toolCallRun"
            (do (close-live!)
                (transcript/tool! pane
                                  {:key (content-id content)
                                   :name (:name content)
                                   :server (:server content)
                                   :args (or (:arguments content)
                                            (:argumentsText content)
                                            {})
                                   :summary (:summary content)
                                   :status :running}))

            "toolCallRunning"
            (transcript/set-tool-status! pane (content-id content) :running nil)

            "toolCalled"
            (transcript/set-tool-status! pane
                                        (content-id content)
                                        (if (:error content) :error :done)
                                        (:totalTimeMs content))

            "toolCallRejected"
            (transcript/set-tool-status! pane (content-id content) :rejected nil)

            "metadata"
            (do (close-live!)
                (when-let [t (:title content)]
                  (transcript/append-status! pane (str "[" t "]"))))

            "flag"
            (do (close-live!)
                (when-let [t (:text content)]
                  (transcript/append-status! pane (str "[" t "]"))))

            "usage"
            nil

            "progress"
            (when (= "finished" (:state content))
              (close-live!))

            nil))))))