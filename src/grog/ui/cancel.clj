(ns grog.ui.cancel
  "Cancellation registry wiring a Stop button (or any caller) to an in-flight
  generation in `grog.core/post-chat-stream!`.

  The GUI passes `(grog.ui.cancel/cancel-state)` as the `:cancel-state` opt to
  `grog.core/chat-with-tools!`. Inside the stream loop:
    * the live SSE response stream is stored in `(:stream state)`, and
    * `(:flag state)` is polled each iteration.
  `cancel!` sets the flag AND closes the live stream, so the blocking read
  returns/throws promptly and the round finishes.")
(defonce ^:private state
  (atom {:flag (atom false)
         :stream (atom nil)}))

(defn cancel-state
  "The stable `{:flag <atom> :stream <atom>}` map to hand to
  `grog.core/chat-with-tools!` as `:cancel-state`."
  []
  @state)

(defn running?
  "True if a round is either requested (flag set) or has a live stream."
  []
  (or @(:flag @state)
      (some? @(:stream @state))))

(defn clear!
  "Clear the cancel flag before starting a new user turn."
  []
  (reset! (:flag @state) false))

(defn cancel!
  "Interrupt any in-flight generation: set the cancel flag and close the live
  SSE response stream so the loop exits promptly. Safe to call at any time."
  []
  (reset! (:flag @state) true)
  (when-let [s @(:stream @state)]
    (try
      (.close ^java.io.Closeable s)
      (catch Throwable _))))
