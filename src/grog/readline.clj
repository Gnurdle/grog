(ns grog.readline
  "JLine3 for chat prompts (history, editing). Falls back to `read-line` if no TTY.

  JNI/FFM/JNA are turned off so JLine does not call `System::load` (JDK 24+ warns or
  will block that). The `exec` provider is used on POSIX instead; see deps alias
  `:jline-native` if you want JNI and accept `--enable-native-access=ALL-UNNAMED`."
  (:import (org.jline.reader EndOfFileException LineReader LineReaderBuilder LineReader$Option)
           (org.jline.terminal TerminalBuilder)))

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
      (.build (doto (LineReaderBuilder/builder)
                (.terminal terminal)
                (.option LineReader$Option/BRACKETED_PASTE true))))
    (catch Throwable _
      (let [terminal (.build (doto (TerminalBuilder/builder)
                               (.system true)
                               (.dumb true)
                               (.color true)))]
        (.build (doto (LineReaderBuilder/builder)
                  (.terminal terminal)
                  (.option LineReader$Option/BRACKETED_PASTE true)))))))

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

(defn jline-terminal
  "JLine `Terminal` when using a real `LineReader`, else `nil` (stdin fallback)."
  []
  (let [lr (line-reader-or-fallback!)]
    (when-not (= lr ::stdin)
      (try (.getTerminal ^LineReader lr)
           (catch Throwable _ nil)))))

(defn read-prompt!
  "Read one line with prompt (trailing space recommended). Returns trimmed string or
  nil on EOF (Ctrl-D). Blank input is returned as the empty string.

  With bracketed-paste enabled, pasted multi-line text is inserted into the buffer as
  a single block; the user still presses Enter to submit the whole block. If that fails,
  use /paste in chat for an explicit multi-line input mode."
  ^String [^String prompt]
  (let [lr (line-reader-or-fallback!)]
    (if (= lr ::stdin)
      (do (print prompt)
          (flush)
          (read-line))
      (try (.readLine ^LineReader lr prompt)
           (catch EndOfFileException _ nil)))))
