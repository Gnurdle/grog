(ns grog.readline
  "JLine3 for chat prompts (history, editing). Falls back to `read-line` if no TTY.

  JNI/FFM/JNA are turned off so JLine does not call `System::load` (JDK 24+ warns or
  will block that). The `exec` provider is used on POSIX instead; see deps alias
  `:jline-native` if you want JNI and accept `--enable-native-access=ALL-UNNAMED`."
  (:import (org.jline.reader EndOfFileException LineReader LineReaderBuilder)
           (org.jline.terminal TerminalBuilder)
           (org.jline.utils NonBlockingReader)))

(defonce ^:private line-reader-state (atom nil))

(defn- build-line-reader!
  "Prefer non-native terminal providers to avoid restricted `System::load`."
  []
  (try
    (let [terminal (.build (doto (TerminalBuilder/builder)
                             (.system true)
                             (.jni false)
                             (.ffm false)
                             (.jna false)
                             (.exec true)))]
      (.build (doto (LineReaderBuilder/builder) (.terminal terminal))))
    (catch Throwable _
      (let [terminal (.build (doto (TerminalBuilder/builder)
                               (.system true)
                               (.dumb true)
                               (.color true)))]
        (.build (doto (LineReaderBuilder/builder) (.terminal terminal)))))))

(defn- line-reader-or-fallback!
  "Returns a `LineReader`, or keyword `::stdin` if JLine cannot start."
  []
  (swap! line-reader-state
         (fn [cur]
           (if (some? cur)
             cur
             (try (build-line-reader!)
                  (catch Throwable _
                    ::stdin))))))

(defonce ^:private chat-cancel-flag (volatile! false))

(defn reset-chat-cancel!
  "Clear the Esc-cancel flag before starting an assistant round."
  []
  (vreset! chat-cancel-flag false))

(defn chat-cancel-requested?
  "True after the user pressed Esc during a round (see `start-escape-cancel-watcher!`)."
  []
  @chat-cancel-flag)

(defn mark-chat-cancel!
  "Set the cancel flag (used when closing the HTTP stream after Esc)."
  []
  (vreset! chat-cancel-flag true))

(defn jline-terminal
  "JLine `Terminal` when using a real `LineReader`, else `nil` (stdin fallback)."
  []
  (let [lr (line-reader-or-fallback!)]
    (when-not (= lr ::stdin)
      (try (.getTerminal ^LineReader lr)
           (catch Throwable _ nil)))))

(defn start-escape-cancel-watcher!
  "While active, a daemon thread `peek`s keyboard input via JLine. **Esc** invokes
  `on-escape` once (typically close the HTTP stream so generation stops). Call the
  returned `stop!` with no args before the next `read-prompt!`. Returns `nil` if there
  is no interactive terminal reader (Esc cancel unavailable)."
  [on-escape]
  (when-let [term (jline-terminal)]
    (try
      (let [^NonBlockingReader nr (.reader term)
            done (volatile! false)
            fired (atom false)
            th (Thread.
                ^Runnable
                (fn []
                  (try
                    (while (not @done)
                      (try
                        (let [c (.peek nr 200)]
                          (when (== c (int 27))
                            (.read nr)
                            (when (compare-and-set! fired false true)
                              (on-escape))))
                        (catch InterruptedException _
                          (.interrupt (Thread/currentThread)))
                        (catch Throwable _ nil)))
                    (catch Throwable _ nil))))]
        (.setDaemon th true)
        (.start th)
        (fn stop-escape-watcher! []
          (vreset! done true)
          (.interrupt th)))
      (catch Throwable _ nil))))

(defn read-prompt!
  "Read one line with `prompt` (trailing space recommended). Returns trimmed string or
  `nil` on EOF (Ctrl-D). Blank input is returned as `\"\"`."
  ^String [^String prompt]
  (let [lr (line-reader-or-fallback!)]
    (if (= lr ::stdin)
      (do (print prompt)
          (flush)
          (read-line))
      (try (.readLine ^LineReader lr prompt)
           (catch EndOfFileException _ nil)))))
