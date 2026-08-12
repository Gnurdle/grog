(ns grog.ui
  "Compact Swing GUI around the grog chat engine + an optional bash shell window
  (opened via a third 'Terminal' button).

  Layout:
    * chat frame: `logo.jpg` painted as the background, colored streaming
      transcript (JTextPane) + editable prompt (JTextArea, full cursor control)
      + Send / Stop / Terminal buttons.
    * shell frame: persistent bash subprocess runner (see grog.ui.shell),
      opened on demand.
  All panes are drag-and-drop enabled (see grog.ui.dnd), so you can select text
  in the chat prompt / transcript and drag it into the shell input and vice
  versa.

  The chat runs on a worker thread with `*out*`/`*err*` rebound to a styled
  `grog.ui.transcript` writer, so the existing ANSI-colored streams (thinking,
  answer, tool calls) land in the pane unchanged. Stop uses `grog.ui.cancel`."

  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.core :as core]
            [grog.eca :as eca]
            [grog.eca-config :as ecacfg]
            [grog.models :as models]
            [grog.appearance :as appearance]
            [grog.ui.cancel :as cancel]
            [grog.ui.dnd :as dnd]
            [grog.ui.eca-stream :as ecastream]
            [grog.ui.export :as uiexport]
            [grog.ui.fonts :as uifonts]
            [grog.ui.footer :as uifooter]
            [grog.ui.settings :as uisettings]
            [grog.ui.shell :as uishell]
            [grog.ui.transcript :as transcript]
            [grog.ui.widgets :as widgets]
            [grog.soul :as soul])
  (:import (java.awt Color Component Font Graphics Image Toolkit BorderLayout FlowLayout Point)
           (javax.imageio ImageIO)
           (javax.swing AbstractAction Box BoxLayout JComponent JDialog JFrame JLabel JList
                        JMenuItem JOptionPane JPanel JPopupMenu JScrollPane JTextArea
                        JTextField JToolBar KeyStroke ListSelectionModel SwingUtilities)
           (java.util.concurrent LinkedBlockingQueue)
           (java.awt.datatransfer StringSelection)
           (java.awt.event KeyEvent MouseAdapter)))

;; ANSI styling mirrors grog.core for plain mode (parsed by transcript writer).
(def ^:private ansi-reset  "\u001B[0m")

;; echoed user prompt colour (dark yellow)
(def ^:private ansi-user "\u001B[38;2;165;138;25m")

;; orange, italic startup snark (visible on the dark pane)
(def ^:private ansi-snark "\u001B[38;2;255;150;40m\u001B[3m")
(def ^:private chat-startup-snark-fallback
  "No snark pool — someone edited the wrong file. Pity.")

;; Debug tracer: writes to the real stderr (so it lands in ~/.grog-ui.log
;; via grog-ui's tee) regardless of the pane *out*/*err* rebindings.
(defn- dbg! [& xs]
  (.println System/err (str "[grog-debug] " (apply str (interpose " " (map str xs))))))

;; --- ECA<->grog protocol tracing into the debug log ------------------------
;; Every JSON-RPC frame that crosses the stdio pipe to the `eca server` child is
;; summarized here (via eca.clj's :trace-fn) so ~/.grog-ui.log shows what is
;; actually flowing between grog and ECA.

(defn- trunc
  "Clip a string to `n` chars with an ellipsis; used to keep streamed text lines
  bounded in the trace."
  [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- summarize-content
  "A compact one-line summary of a `chat/contentReceived` content object."
  [c]
  (let [t (:type c)]
    (case t
      "text"            (str "type=" t " text=" (pr-str (trunc (:text c) 120)))
      "reasonText"      (str "type=" t " text=" (pr-str (trunc (:text c) 120)))
      "reasonStarted"   (str "type=" t " (thinking start)")
      "reasonFinished"  (str "type=" t " (thinking end)")
      "toolCallPrepare" (str "type=" t " tool=" (:name c) (when-let [s (:summary c)] (str " — " (trunc s 80))))
      "toolCallRun"     (str "type=" t " tool=" (:name c)
                             (when (true? (:manualApproval c)) " [manual approval]")
                             (when-let [s (:summary c)] (str " — " (trunc s 80))))
      "toolCallRunning" (str "type=" t " tool=" (:name c))
      "toolCalled"      (str "type=" t " tool=" (:name c)
                             (if (:error c) " ERR" "")
                             (when-let [ms (:totalTimeMs c)] (str " " ms "ms"))
                             (when-let [s (:summary c)] (str " — " (trunc s 80))))
      "toolCallRejected" (str "type=" t " tool=" (:name c))
      "metadata"        (str "type=" t (when-let [ti (:title c)] (str " title=" (pr-str ti))))
      "flag"            (str "type=" t (when-let [tx (:text c)] (str " text=" (pr-str tx))))
      "usage"           (str "type=" t " usage=" (pr-str (dissoc c :type)))
      "progress"        (str "type=" t " state=" (:state c))
      (str "type=" t " content=" (pr-str (trunc c 200))))))

(defn- summarize-frame
  "A one-line, direction-tagged summary of a raw ECA JSON-RPC frame."
  [dir frame]
  (let [{:keys [id method result error]} frame
        params (:params frame)
        arrow (if (= dir :out) "→" "←")]
    (str arrow
         (cond
           ;; a response to one of our requests
           (and (some? id) (not method))
           (str "  resp id=" id " -> "
                (if error (str "ERR " (pr-str (:message error))) (pr-str result)))

           ;; a server->client request
           (and (some? id) method)
           (str "  req  id=" id " " method " " (pr-str params))

           ;; a notification
           method
           (str "  notify " method " "
                (if (= method "chat/contentReceived")
                  (str "{content " (summarize-content (:content params)) "}")
                  (pr-str params)))

           :else
           (str "  " (pr-str frame))))))

(defn make-eca-tracer
  "Build an ECA `:trace-fn` that logs every JSON-RPC frame to the grog debug log,
  except `chat/contentReceived` notifications — those arrive in a high-frequency
  stream of tiny content chunks and would swamp the log, so they're elided."
  []
  (fn [dir frame]
    ;; skip the noisy token/event stream; log everything else (handshake,
    ;; prompt requests/responses, statusChanged, tool events, errors, etc.)
    (when-not (and (nil? (:id frame))
                   (= "chat/contentReceived" (:method frame)))
      (dbg! (summarize-frame dir frame)))))

;; Dialog / status fonts follow the active Look & Feel's system font at a larger
;; size (see grog.ui.widgets/ui-font + mono-font). These are functions, not
;; constants, because the L&F font is only known after FlatLaf/setup runs.
(defn- ui-monospace-font ^Font [] (widgets/mono-font))

;; ---------------------------------------------------------------------------
;; Background logo
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Asset (icon/logo) resolution. grog-ui exports GROG_HOME (the repo dir) so
;; these are found regardless of the process's current working directory — a
;; relative "icon.png"/"logo.jpg" would silently fail when grog is started from
;; anywhere but the repo (e.g. a desktop launcher whose CWD isn't the repo).

(defn- asset-file
  "Resolve a repo-relative asset name (icon.png, logo.jpg) to a File, trying in
  order: the `grog.home` system property, the `GROG_HOME` env var (set by
  grog-ui), then the current working directory."
  [name]
  (or (some-> (System/getProperty "grog.home") (io/file name))
      (some-> (System/getenv "GROG_HOME") (io/file name))
      (io/file name)))

(defonce ^:private logo-image
  (delay (try (ImageIO/read (asset-file "logo.jpg"))
              (catch Throwable _ nil))))

;; Window / menu icon: prefers `icon.png` (repo root), falls back to the logo
;; so the window decoration never shows the default Java icon.
(defonce ^:private frame-icon-image
  (delay
    (let [icon (try (ImageIO/read (asset-file "icon.png")) (catch Throwable _ nil))]
      (if icon
        icon
        (do (dbg! "icon.png not found; falling back to logo.jpg for window icon")
            @logo-image)))))

(defn- set-frame-icon!
  "Apply the app icon to a frame's window decoration (instead of the Java
  default). Safe to call with a missing icon."
  [^javax.swing.JFrame f]
  (when-let [img @frame-icon-image]
    (.setIconImage f ^java.awt.Image img))
  f)

(defn- background-panel
  "Returns {:panel <JPanel that paints logo.jpg scaled-to-fill> :subdue! <fn [bool]>}.
  `subdue!` darkens the logo under a heavy black scrim (used once chat starts so
  the transcript stays readable)."
  []
  (let [img @logo-image
        subdued? (atom false)
        panel-ref (atom nil)]
    {:panel (reset! panel-ref
              (proxy [JPanel] []
                (paintComponent [g]
                  ;; 1) solid black base — fills any letterbox bands around the
                  ;;    aspect-fit logo so there are no white margins
                  (let [g2 (.create ^Graphics g)]
                    (.setColor g2 (Color. 0 0 0))
                    (.fillRect g2 0 0 (.getWidth this) (.getHeight this))
                    ;; 2) centered logo scaled-to-fit (keeps aspect ratio)
                    (when img
                      (let [iw (.getWidth ^Image img)
                            ih (.getHeight ^Image img)
                            w (.getWidth this)
                            h (.getHeight this)]
                        (when (and (pos? iw) (pos? ih) (pos? w) (pos? h))
                          (let [scale (min (double (/ w iw)) (double (/ h ih)))
                                dw (int (* iw scale))
                                dh (int (* ih scale))
                                dx (int (/ (- w dw) 2))
                                dy (int (/ (- h dh) 2))]
                            (.drawImage g2 img dx dy dw dh this)))))
                    ;; 3) subdued scrim once chat starts
                    (when @subdued?
                      (.setColor g2 (Color. 0 0 0 185))
                      (.fillRect g2 0 0 (.getWidth this) (.getHeight this)))
                    (.dispose g2)))))
     :subdue! (fn [b]
                (reset! subdued? b)
                (when-let [p @panel-ref] (.repaint p)))}))

(defn- transparent!
  "Make `c` non-opaque so the background logo shows through (no-op unless it's
  a JComponent)."
  [^Component c]
  (when (instance? JComponent c)
    (.setOpaque ^JComponent c false))
  c)

(defn- make-viewport-transparent! [^JScrollPane sp]
  (transparent! sp)
  (transparent! (.getViewport sp))
  sp)

(defn- with-copy-menu!
  "Add a right-click copy menu to `c`. `items` is a seq of [label text-fn];
  selecting an item copies `(text-fn)` to the clipboard (e.g. the transcript's
  \"Copy selection\" / \"Copy all\" and the prompt's \"Copy\")."
  [^java.awt.Component c items]
  (let [menu (JPopupMenu.)]
    (doseq [[label text-fn] items]
      (let [item (JMenuItem. label)]
        (.addActionListener item
          (reify java.awt.event.ActionListener
            (actionPerformed [_ _]
              (let [sel (try (text-fn) (catch Throwable _ nil))]
                (when (seq sel)
                  (.setContents (.getSystemClipboard (Toolkit/getDefaultToolkit))
                                (StringSelection. sel)
                                nil))))))
        (.add menu item)))
    (.addMouseListener c
      (proxy [MouseAdapter] []
        (mousePressed [e]
          (when (.isPopupTrigger e) (.show menu c (.getX e) (.getY e))))
        (mouseReleased [e]
          (when (.isPopupTrigger e) (.show menu c (.getX e) (.getY e)))))))
  c)

;; ---------------------------------------------------------------------------
;; Chat
;; ---------------------------------------------------------------------------

(defn- file-uri
  "A `file://` URI for a local path, for ECA `workspaceFolders`. Uses
  `File/toURI` so Windows paths (backslashes, drive letters) become valid URIs
  (`file:///C:/...`); a hand-built `(str \"file://\" abs)` would emit backslashes
  and break ECA's URI parser on Windows."
  [^String path]
  (str (.toURI (-> (java.io.File. path)
                   .toPath .toAbsolutePath .normalize .toFile))))

(defn- make-event-handler
  "Build the ECA event handler for the transcript: renders `chat/contentReceived`
  (streaming inline), flips `running?` when a prompt finishes, and prompts the
  user to approve/reject manual-approval tool calls. Runs on the ECA reader thread.
  NOTE: a single `styled-writer` is shared across all events so its active ANSI
  color persists between contiguous streamed chunks (a fresh writer per event
  would reset the color to black mid-stream).

  `yolo-ref` is the trust (YOLO) atom: when it's truthy, manual-approval tool
  calls are auto-approved and the dialog is skipped — \"check it and everything
  goes\". `last-sent` holds the most recently sent user message, so the ECA echo
  of that message (a `text` content event mirroring the prompt) can be suppressed
  instead of duplicating the local user echo. `pending-steer*` tracks a steer
  that has been sent but not yet confirmed consumed by ECA; `resend-steer!` is
  called with the steer text to re-issue it as a normal prompt if the run ends
  before ECA consumes it (the protocol's documented fallback)."
  [^JComponent pane running? chat-id yolo-ref last-sent pending-steer* resend-steer!]
  (let [streamer (ecastream/make-streamer)
        writer (transcript/styled-writer pane)
        ;; a steer is consumed when ECA echoes it back; resend undelivered steers
        ;; on any idle/finished transition (protocol fallback).
        finish! (fn []
                  (when-let [s @pending-steer*]
                    (reset! pending-steer* nil)
                    (resend-steer! s))
                  (reset! running? false))]
    (fn [method params]
      (case method
        "chat/contentReceived"
        (let [content (:content params)
              echo? (and (= "text" (:type content))
                         @last-sent
                         (= (str/trim (str (:text content)))
                            (str/trim (str @last-sent))))]
          ;; the steer echo (from ECA consuming the steer) confirms it was
          ;; accepted — mark it consumed so the finish-path doesn't resend it.
          (when (and echo? @pending-steer*
                     (= (str/trim (str (:text content)))
                        (str/trim (str @pending-steer*))))
            (reset! pending-steer* nil))
          (when-not echo?
            (binding [*out* writer]
              (streamer content))
            (when (= "finished" (:state content))
              (finish!))
            ;; manual-approval tool call -> in YOLO mode auto-approve (everything
            ;; goes, no permission dialog); otherwise ask the user in a dialog
            ;; with a readable font and a check-box to switch into YOLO mode.
            (when (and (= "toolCallRun" (:type content)) (true? (:manualApproval content)))
              (if @yolo-ref
                (eca/approve! @chat-id (:id content))
                (let [id (:id content)
                      name (str (:name content))
                      summary (:summary content)
                      msg (doto (JTextArea.
                                 (str "Approve tool call?\n\n  " name "\n"
                                      (when (seq summary) (str "\n" summary "\n"))
                                      "\n• Approve — just this once\n"
                                      "• Reject — don't run it\n"
                                      "• YOLO — this call and everything after, no more dialogs"))
                             (.setEditable false)
                             (.setLineWrap true)
                             (.setWrapStyleWord true)
                             (.setFont (ui-monospace-font))
                             (.setOpaque true)
                             (.setBorder (javax.swing.BorderFactory/createEmptyBorder
                                          8 8 8 8)))
                      panel (doto (JPanel. (BorderLayout.))
                              (.setBorder (javax.swing.BorderFactory/createEmptyBorder
                                           8 8 8 8))
                              (.add msg BorderLayout/CENTER))
                      opts (into-array ["Approve" "Reject" "YOLO"])
                      dismiss (JOptionPane/DEFAULT_OPTION)
                      choice (JOptionPane/showOptionDialog
                               pane panel
                               "grog — tool approval"
                               dismiss
                               JOptionPane/QUESTION_MESSAGE
                               nil opts opts)]
                  (case (int choice)
                    0 (eca/approve! @chat-id id)
                    1 (eca/reject! @chat-id id)
                    ;; "YOLO" — approve this call and switch into trust mode so
                    ;; all future tool calls auto-approve
                    (do (reset! yolo-ref true)
                        (uifooter/set-trust-indicator! true)
                        (eca/set-trust! @chat-id true)
                        (eca/approve! @chat-id id))))))))

        "chat/statusChanged"
        (let [st (str (:status params))]
          (when (= "idle" st)
            (finish!))
          (uifooter/set-status! st))

        ;; ECA re-syncs its model catalog on startup/login: remember the full set
        ;; of provider-qualified ids so model qualification is exact — this is what
        ;; disambiguates OpenRouter catalog orgs that collide with native provider
        ;; names (deepseek/…, openai/…, google/…, …).
        "config/updated"
        (when-let [ms (get-in params [:chat :models])]
          (models/register-eca-catalog! ms))

        nil))))

(defn- show-question-dialog!
  "Modal 'Question from the LLM' dialog.

  `options` (optional) is a seq of `{:label .. :description ..}` maps (or plain
  strings) from ECA's `chat/askQuestion`. Labels are shown as a selectable list
  (descriptions shown dimmed underneath); when `allow-freeform?` is true a text
  field lets the user type a custom answer instead. Returns
  `{:cancelled bool :answer str|nil}`."
  [^JComponent owner prompt options allow-freeform?]
  (let [frame (JOptionPane/getFrameForComponent owner)
        dlg (JDialog. frame "Question from the LLM" true)
        labels (vec (keep (fn [o]
                            (when-let [l (if (map? o) (:label o) o)]
                              (str/trim (str l))))
                          (or options [])))
        list (when (seq labels)
               (let [l (JList. (into-array String labels))]
                 (.setSelectionMode l ListSelectionModel/SINGLE_SELECTION)
                 (.setSelectionInterval l 0 0)
                 l))
        custom (JTextField. 32)
        custom-row (when allow-freeform?
                     (let [p (JPanel. (BorderLayout.))]
                       (.add p (JLabel. "Or type a custom answer:") BorderLayout/WEST)
                       (.add p custom BorderLayout/CENTER)
                       p))
        q (doto (JTextArea. (str (or prompt "")))
            (.setEditable false)
            (.setLineWrap true)
            (.setWrapStyleWord true)
            (.setFont (ui-monospace-font))
            (.setOpaque false))
        ok (widgets/styled-button "OK")
        cancel (widgets/styled-button "Cancel")
        result (atom {:cancelled true :answer nil})
        choose! (fn []
                  (let [custom-ans (str/trim (.getText custom))
                        selected (when list (str/trim (str (.getSelectedValue list))))
                        ans (cond
                              (seq custom-ans) custom-ans
                              (seq selected) selected
                              :else nil)]
                    (reset! result (if (seq ans)
                                     {:cancelled false :answer ans}
                                     {:cancelled true :answer nil}))
                    (.dispose dlg)))]
    ;; layout
    (let [center (JPanel.)
          _ (.setLayout center (BoxLayout. center BoxLayout/Y_AXIS))
          south (JPanel. (FlowLayout. FlowLayout/RIGHT))]
      (.add center q)
      (when list
        (.add center (JScrollPane. list)))
      (when custom-row
        (.add center custom-row))
      (.add south cancel)
      (.add south ok)
      (.add dlg south BorderLayout/SOUTH)
      (.setLayout dlg (BorderLayout.))
      (.add dlg center BorderLayout/CENTER)
      (doto dlg (.setSize 600 360)
            (.setLocationRelativeTo frame)))
    ;; actions
    (.addActionListener ok
      (reify java.awt.event.ActionListener
        (actionPerformed [_ _] (choose!))))
    (.addActionListener cancel
      (reify java.awt.event.ActionListener
        (actionPerformed [_ _] (.dispose dlg))))
    (let [listener (proxy [MouseAdapter] []
                     (mouseClicked [e]
                       (when (and (= 2 (.getClickCount e)) list)
                         (choose!))))]
      (.addMouseListener q listener)
      (when list (.addMouseListener list listener)))
    (when custom
      (.put (.getInputMap custom JComponent/WHEN_FOCUSED)
            (KeyStroke/getKeyStroke KeyEvent/VK_ENTER 0) "question-ok")
      (.put (.getActionMap custom) "question-ok"
            (proxy [AbstractAction] []
              (actionPerformed [_ _] (choose!)))))
    (.setVisible dlg true)
    @result))

(defn- make-request-handler
  "Handle ECA server→client requests. The one that matters for the user is
  `chat/askQuestion` — the LLM asking a question — which surfaces a dialog on
  the EDT and answers with the user's input (or cancels). Everything else gets
  safe defaults (empty diagnostics / empty result). Runs on the ECA reader
  thread; the dialog is marshalled to the EDT with `invokeAndWait` and the
  reader thread blocks until the user answers (which is what we want: ECA is
  waiting for the response)."
  [^JComponent pane]
  (fn [method params]
    (case method
      "chat/askQuestion"
      (let [prompt (or (some-> (:prompt params) str str/trim not-empty)
                       (some-> (:message params) str str/trim not-empty)
                       (some-> (:question params) str str/trim not-empty)
                       (pr-str params))
            options (when (sequential? (:options params)) (vec (:options params)))
            allow-freeform? (not (false? (:allowFreeform params)))
            res (atom {:cancelled true :answer nil})]
        (binding [*out* (transcript/styled-writer pane)]
          (println (str "\n[" (appearance/ansi-tool-call) "LLM question" ansi-reset "] "
                        prompt)))
        (SwingUtilities/invokeAndWait
          (fn []
            (reset! res (show-question-dialog! pane prompt options allow-freeform?))))
        {:result @res})

      "editor/getDiagnostics"
      {:result {:diagnostics []}}

      {:result {}})))

(defn- handle-turn!
  "Echo user input, route slash commands, or hand an LLM turn to `send-fn`
  (a closure `(fn [history text] -> history)` owned by the frame). Also handles
  `/eca-model <name>` via `set-model-fn` and `/yolo [on|off]` via `set-yolo-fn`.
  Returns the updated history."
  [^JComponent pane history text send-fn set-model-fn set-yolo-fn]
  (binding [*out* (transcript/styled-writer pane)
            *err* (transcript/styled-writer pane)]
    (cancel/clear!)
    ;; echo the user's input (prompt or command) into the transcript in dark yellow
    (when (seq (str/trim text))
      (println (str "\n" (appearance/ansi-user) text ansi-reset)))
    (cond
      (re-matches #"(?i)^/eca-model\s+(.+)$" (str/trim text))
      (let [m (re-matches #"(?i)^/eca-model\s+(.+)$" (str/trim text))]
        (set-model-fn (str/trim (second m)))
        history)

      (re-matches #"(?i)^/yolo(?:\s+(on|off))?$" (str/trim text))
      (let [m (re-matches #"(?i)^/yolo(?:\s+(on|off))?$" (str/trim text))
            on? (when (second m) (= "on" (str/lower-case (second m))))]
        (set-yolo-fn on?)
        history)

      :else
      (case (core/route-slash-command! text)
        :grog.core/quit
        (do (System/exit 0) history)
        :grog.core/clear
        (do
          ;; clear the virtualized transcript log, and drop any trust (yolo)
          ;; auto-approve so a fresh transcript starts from a clean slate
          (transcript/clear! pane)
          (set-yolo-fn false)
          (println "History cleared.")
          [])
        :grog.core/handled
        history
        :grog.core/llm
        (send-fn history text)))))

(defn- chat-worker!
  "Process the input queue on a background thread. `send-fn` is the ECA turn
  closure; `running?` is managed by the ECA event lifecycle."
  [^JComponent pane ^LinkedBlockingQueue queue history-ref send-fn set-model-fn set-yolo-fn]
  (Thread.
    (fn []
      (loop []
        (when-let [text (.take queue)]
          (reset! history-ref (handle-turn! pane @history-ref text send-fn set-model-fn set-yolo-fn))
          (recur))))))

(def ^:private shell-frame-ref (atom nil))

(defn- show-shell! ^JFrame []
  (let [f (or @shell-frame-ref
              (do (reset! shell-frame-ref (set-frame-icon! (uishell/make-shell-frame)))
                  @shell-frame-ref))]
    (.setVisible f true)))

;; ---------------------------------------------------------------------------
;; Rounded, theme-matched buttons
;; ---------------------------------------------------------------------------

(defn- install-transcript-scroll-keys!
  "Add single-line scroll key bindings for the transcript pane.
   - Up/Down (when pane is focused) scroll one line
   - j/k (vim-style) scroll one line when pane is focused
   - Ctrl+Up/Ctrl+Down window-wide scroll the transcript
   Also sets the scrollbar unit increment to match the pane's font line height."
  [^JComponent pane ^JScrollPane scroll-pane frame]
  (let [line-h (atom 20)  ;; updated below from font metrics
        scroll-unit! (fn []
                       (let [sb (.getVerticalScrollBar scroll-pane)]
                         (.setUnitIncrement sb (int @line-h))))
        scroll-line! (fn [delta]
                       (let [vp (.getViewport scroll-pane)
                             ^Point pos (.getViewPosition vp)]
                         (.setViewPosition vp (Point. (.x pos)
                                                      (max 0 (+ (.y pos)
                                                                (* delta (int @line-h))))))))
        scroll-to-top! (fn []
                         (.setViewPosition (.getViewport scroll-pane)
                                           (Point. 0 0)))]
    ;; compute line height from the pane's current font
    (let [fm (.getFontMetrics pane (.getFont pane))]
      (reset! line-h (max 12 (.getHeight fm))))
    (scroll-unit!)
    ;; ---- pane-focused keys (Up / Down / j / k / End / Home) ----
    (let [im (.getInputMap pane JComponent/WHEN_FOCUSED)
          am (.getActionMap pane)]
      (.put im (KeyStroke/getKeyStroke KeyEvent/VK_UP 0)   "grog-scroll-up")
      (.put im (KeyStroke/getKeyStroke KeyEvent/VK_DOWN 0) "grog-scroll-down")
      (.put im (KeyStroke/getKeyStroke (int \j) 0)         "grog-scroll-down")
      (.put im (KeyStroke/getKeyStroke (int \k) 0)         "grog-scroll-up")
      (.put im (KeyStroke/getKeyStroke KeyEvent/VK_END 0)  "grog-scroll-bottom")
      (.put im (KeyStroke/getKeyStroke KeyEvent/VK_HOME 0) "grog-scroll-top")
      (.put am "grog-scroll-up"
            (proxy [AbstractAction] []
              (actionPerformed [_] (scroll-line! -1))))
      (.put am "grog-scroll-down"
            (proxy [AbstractAction] []
              (actionPerformed [_] (scroll-line! 1))))
      (.put am "grog-scroll-bottom"
            (proxy [AbstractAction] []
              (actionPerformed [_] (transcript/follow! pane true))))
      (.put am "grog-scroll-top"
            (proxy [AbstractAction] []
              (actionPerformed [_] (scroll-to-top!)))))
    ;; ---- window-wide Ctrl+Up / Ctrl+Down / Ctrl+End / Ctrl+Home ----
    (let [im (.getInputMap (.getRootPane frame) JComponent/WHEN_IN_FOCUSED_WINDOW)
          am (.getActionMap (.getRootPane frame))
          ctrl (java.awt.event.InputEvent/CTRL_DOWN_MASK)]
      (.put im (KeyStroke/getKeyStroke KeyEvent/VK_UP ctrl)   "grog-cscroll-up")
      (.put im (KeyStroke/getKeyStroke KeyEvent/VK_DOWN ctrl) "grog-cscroll-down")
      (.put im (KeyStroke/getKeyStroke KeyEvent/VK_END ctrl)  "grog-cscroll-bottom")
      (.put im (KeyStroke/getKeyStroke KeyEvent/VK_HOME ctrl) "grog-cscroll-top")
      (.put am "grog-cscroll-up"
            (proxy [AbstractAction] []
              (actionPerformed [_] (scroll-line! -1))))
      (.put am "grog-cscroll-down"
            (proxy [AbstractAction] []
              (actionPerformed [_] (scroll-line! 1))))
      (.put am "grog-cscroll-bottom"
            (proxy [AbstractAction] []
              (actionPerformed [_] (transcript/follow! pane true))))
      (.put am "grog-cscroll-top"
            (proxy [AbstractAction] []
              (actionPerformed [_] (scroll-to-top!)))))))

(defn- build-chat-frame
  "Build the chat frame (not yet shown). Returns the JFrame."
  ^JFrame []
  (let [transcript-scroll (transcript/make-chat-pane)
        pane (transcript/chat-pane transcript-scroll)
        prompt (JTextArea. 4 84)
        prompt-scroll (JScrollPane. prompt)
        send (widgets/toolbar-button :send "Send (Ctrl+Enter)")
        stop (widgets/toolbar-button :stop "Stop")
        term (widgets/toolbar-button :terminal "Terminal")
        settings (widgets/toolbar-button :settings "Settings")
        export (widgets/toolbar-button :export "Export transcript")
        clear (widgets/toolbar-button :clear "Clear")
        frame (JFrame. "grog")
        queue (LinkedBlockingQueue.)
        running? (atom false)
        history-ref (atom [])
        chat-id (atom (str (java.util.UUID/randomUUID)))
        _ (uifooter/init-model! (models/qualify-eca-model (config/eca-model)
                                                          nil
                                                          (try (config/llm-url) (catch Exception _ nil))))
        yolo-ref (atom false)   ; trust (yolo) mode: auto-approve all tool calls
        last-sent (atom nil)    ; last user message sent, to suppress ECA's echo
        pending-steer* (atom nil) ; steer text sent but not yet confirmed consumed by ECA
        connected (atom false)
        event-handler (make-event-handler pane running? chat-id yolo-ref last-sent
                                          pending-steer*
                                          (fn [s]
                                            ;; steer was dropped (run finished before
                                            ;; ECA consumed it): re-issue as a normal prompt
                                            (binding [*out* (transcript/styled-writer pane)
                                                      *err* (transcript/styled-writer pane)]
                                              (println (str "[grog] resending as a prompt: " s)))
                                            (reset! last-sent (str s))
                                            (.put ^LinkedBlockingQueue queue (str s))))
        connect-eca! (fn []
                       (when-not @connected
                         (try
                           (let [cfg (ecacfg/generate-config!)
                                 _ (dbg! "ECA starting: config=" cfg
                                         " model=" (or (uifooter/current-model) "(none)")
                                         " chatId=" @chat-id
                                         " root=" (config/repo-root))
                                 init (eca/connect! [{:uri (file-uri (config/repo-root)) :name "grog"}]
                                                    :event-handler event-handler
                                                    :request-handler (make-request-handler pane)
                                                    :args ["--config-file" cfg]
                                                    :log-fn (fn [line] (dbg! "eca:" line))
                                                    :trace-fn (make-eca-tracer))]
                             (dbg! "ECA started ok, init model=" (get-in init [:ok :model])))
                           (reset! connected true)
                           (catch Throwable e
                             (binding [*out* (transcript/styled-writer pane)]
                               (println (str "[grog] ECA connect failed: " (.getMessage e))))
                             (reset! running? false)))))
        send-fn (fn [history text]
                  (connect-eca!)
                  (if-not @connected
                    (do (reset! running? false) history)
                    (do
                      (reset! running? true)
                      (cancel/clear!)
                      (reset! last-sent (str text))
                      (try
                        (let [resp (eca/prompt! text {:chatId @chat-id
                                                      :model (some-> (uifooter/current-model)
                                                                     (models/qualify-eca-model
                                                                      nil
                                                                      (try (config/llm-url) (catch Exception _ nil))))
                                                      :trust @yolo-ref})]
                          (when-let [e (:error resp)]
                            (binding [*out* (transcript/styled-writer pane)]
                              (println (str "[grog] " (or (:message e) (pr-str e))))))
                          (conj history {:user text}))
                        (catch Throwable e
                          (dbg! "eca prompt error:" (.getMessage e))
                          (reset! running? false)
                          (binding [*out* (transcript/styled-writer pane)]
                            (println (str "[grog] " (.getMessage e))))
                          history)))))
        stop-action! (fn []
                       (when @connected (eca/stop! @chat-id))
                       (cancel/cancel!)
                       (reset! running? false))
        set-model-fn (fn [name]
                       (let [id (models/qualify-eca-model name
                                                          nil
                                                          (try (config/llm-url) (catch Exception _ nil)))]
                         (when (and @connected id)
                           (eca/selected-model! id {:chatId @chat-id}))
                         (uifooter/set-model-ref! id)
                         (when id (models/save-eca-model! id))
                         (config/reload!)
                         (uifooter/set-model! id)
                         (binding [*out* (transcript/styled-writer pane)]
                           (println (str "model: " id)))))
        set-yolo-fn (fn [on?]
                      (let [next (if (nil? on?) (not @yolo-ref) on?)]
                        (reset! yolo-ref next)
                        (uifooter/set-trust-indicator! next)
                        (when @connected
                          (eca/set-trust! @chat-id next))
                        (binding [*out* (transcript/styled-writer pane)]
                          (println (str "trust (yolo) mode: "
                                        (if next "ON — tool calls auto-approved" "off"))))))
        bg (background-panel)
        sent? (atom false)   ; first real message flips the logo to subdued
        submit! (fn []
                  (let [t (.getText ^JTextArea prompt)
                        t (str/trim (str t))]
                    (when (seq t)
                      (transcript/follow! pane true)
                      (.setText ^JTextArea prompt "")
                      ;; first real message flips the logo to subdued
                      (when (compare-and-set! sent? false true)
                        ((:subdue! bg) true))
                      (if @running?
                        ;; model is mid-turn: echo the prompt locally and steer
                        ;; the running response (chat/promptSteer). Set last-sent
                        ;; so ECA's own echo of the steer is suppressed (no dup).
                        ;; Track it as pending: if the prompt finishes before ECA
                        ;; consumes the steer (dropped), we resend it as a regular
                        ;; prompt (see make-event-handler's finish handling).
                        (do (reset! pending-steer* t)
                            (reset! last-sent t)
                            (binding [*out* (transcript/styled-writer pane)
                                      *err* (transcript/styled-writer pane)]
                              (println (str "\n" (appearance/ansi-user) t ansi-reset)))
                            (when @connected
                              (eca/steer! @chat-id t)))
                        ;; idle: queue a normal prompt as before
                        (.put ^LinkedBlockingQueue queue t)))))]
    ;; larger fonts
    (doto pane
      (.setFont (ui-monospace-font))
      ;; keep the transcript transparent over the logo background
      (.setOpaque false))
    (doto prompt (.setFont (ui-monospace-font)))
    ;; all footer buttons are widgets/styled-button and already get the compact
    ;; system-derived button font via style!; no per-button setFont needed here.
    (uifonts/register-chat! pane :transcript)
    (uifonts/register-chat! prompt :prompt)
    ;; drag-and-drop on the prompt only (the transcript is a virtualized JList)
    (dnd/install! prompt)
    ;; right-click Copy on the transcript and prompt
    (with-copy-menu! pane [["Copy selection" #(transcript/copy-selection! pane)]
                           ["Copy all" #(transcript/text pane)]])
    (with-copy-menu! prompt [["Copy" #(.getSelectedText prompt)]])
    ;; single-line scroll keys on the transcript
    (install-transcript-scroll-keys! pane transcript-scroll frame)
    ;; Send: button + Ctrl+Enter
    (.addActionListener send (reify java.awt.event.ActionListener
                               (actionPerformed [_ _] (submit!))))
    ;; Enter submits the prompt; Shift+Enter inserts a literal newline
    ;; (reversed from before) so the prompt stays multiline but sending is one key.
    (.put (.getInputMap prompt javax.swing.JComponent/WHEN_FOCUSED)
          (KeyStroke/getKeyStroke KeyEvent/VK_ENTER 0)
          "grog-submit")
    (.put (.getInputMap prompt javax.swing.JComponent/WHEN_FOCUSED)
          (KeyStroke/getKeyStroke KeyEvent/VK_ENTER (java.awt.event.InputEvent/SHIFT_DOWN_MASK))
          "insert-break")
    (.put (.getActionMap prompt) "grog-submit"
          (proxy [AbstractAction] []
            (actionPerformed [e] (submit!))))
    ;; Robust cross-platform Enter handling. The WHEN_FOCUSED InputMap binding
    ;; above is not honoured reliably on Windows when the JTextArea has a
    ;; TransferHandler/setDragEnabled installed (see grog.ui.dnd) and default
    ;; `insert-break` wins, so plain Enter inserts a newline instead of sending.
    ;; A KeyListener on the focused component fires identically on every
    ;; platform; consuming the event stops the InputMap/default newline from
    ;; also running. Plain Enter submits (Ctrl+Enter still does too via `send`),
    ;; Shift+Enter inserts a literal newline.
    (.addKeyListener prompt
      (proxy [java.awt.event.KeyAdapter] []
        (keyPressed [e]
          (when (= KeyEvent/VK_ENTER (.getKeyCode e))
            (if (zero? (bit-and (.getModifiersEx e)
                                java.awt.event.InputEvent/SHIFT_DOWN_MASK))
              (do (.consume e)
                  (submit!))
              (do (.consume e)
                  (.replaceSelection prompt "\n")))))))
    ;; Stop -> stop the ECA prompt (and cancel registry); Terminal -> shell window
    (.addActionListener stop (reify java.awt.event.ActionListener
                               (actionPerformed [_ _] (stop-action!))))
    (.addActionListener term (reify java.awt.event.ActionListener
                               (actionPerformed [_ _] (show-shell!))))
    (.addActionListener settings (reify java.awt.event.ActionListener
                                   (actionPerformed [_ _]
                                     (uisettings/show-settings! frame))))
    (.addActionListener export (reify java.awt.event.ActionListener
                                 (actionPerformed [_ _]
                                   (uiexport/save-transcript! frame pane))))
    ;; Clear button routes through the same behavior as /clear: wipe the
    ;; transcript and drop trust (yolo) auto-approve, without echoing "/clear".
    (let [do-clear! (fn []
                      (transcript/clear! pane)
                      (set-yolo-fn false)
                      (reset! history-ref []))]
      (.addActionListener clear (reify java.awt.event.ActionListener
                                  (actionPerformed [_ _]
                                    (do-clear!)))))
    ;; start worker
    (.start (chat-worker! pane queue history-ref send-fn set-model-fn set-yolo-fn))
    ;; layout over the logo background
    (let [root (:panel bg)
          toolbar (doto (JToolBar.)
                    (.setFloatable false)
                    (.setRollover true)
                    (.setOpaque false)
                    (.setBorder nil))]
      ;; left: operation buttons (icons + hover tooltips set by `toolbar-button`)
      (doseq [b [send stop term settings export clear]]
        (.add toolbar b)
        (.add toolbar (Box/createHorizontalStrut 4)))
      ;; right: model / status / trust indicators
      (.add toolbar (Box/createHorizontalGlue))
      (.add toolbar (Box/createHorizontalStrut 14))
      ;; model — dainty, dim, small
      (let [model-label (JLabel. (str " " (or (uifooter/current-model) (config/model))))
            base (widgets/ui-font)
            dainty (Font. (.getFamily base) Font/PLAIN (max 12 (int (/ (.getSize base) 1.4))))]
        (.setFont model-label dainty)
        (.setForeground model-label (Color. 140 142 152))
        (.add toolbar (uifooter/register-label! model-label))
        (.add toolbar (Box/createHorizontalStrut 16)))
      ;; status — dot icon (running / idle / question), no text
      (let [status-label (JLabel.)]
        (.setOpaque status-label false)
        (.add toolbar (uifooter/register-status-label! status-label))
        (uifooter/set-status! "idle")
        (.add toolbar (Box/createHorizontalStrut 10)))
      ;; trust — dot icon (on / off), no text
      (let [trust-label (JLabel.)]
        (.setOpaque trust-label false)
        (.add toolbar (uifooter/register-trust-label! trust-label))
        (uifooter/set-trust-indicator! @yolo-ref))
      ;; readable prompt box: dark + light text; surrounding panes transparent
      (doto prompt
        ;; NON-OPAQUE: FlatLaf's JTextArea UI paints an opaque WHITE fill on
        ;; Windows (ignoring the component background) — a known FlatLaf text
        ;; component bug. Making the text area transparent lets the dark
        ;; JScrollPane viewport behind it show through as the background, while
        ;; only the text/caret are drawn on top. This is the reliable fix.
        (.setOpaque false)
        (.setForeground (Color. 230 230 230))
        (.setCaretColor (Color. 230 230 230)))
      ;; The prompt's scrollpane/viewport must be OPAQUE dark (not transparent):
      ;; a transparent viewport relies on the parent repainting behind it, which
      ;; is unreliable on Windows and can show the default light background as a
      ;; white box around/behind the prompt text area.
      (doto prompt-scroll
        (.setOpaque true)
        (.setBackground (Color. 18 18 18)))
      (when-let [vp (.getViewport prompt-scroll)]
        (.setOpaque vp true)
        (.setBackground vp (Color. 18 18 18)))
      ;; transcript transparent so the logo shows behind the conversation
      (make-viewport-transparent! transcript-scroll)
      (transparent! pane)
      (transparent! toolbar)
      (.setLayout root (BorderLayout.))
      (.add root transcript-scroll BorderLayout/CENTER)
      (let [south (JPanel. (BorderLayout.))]
        (transparent! south)
        (.add south prompt-scroll BorderLayout/CENTER)
        (.add south toolbar BorderLayout/SOUTH)
        (.add root south BorderLayout/SOUTH))
      ;; explicit black on frame + content so nothing light can peek through
      (.setBackground root (Color. 0 0 0))
      (.setContentPane frame root)
      (.setBackground frame (Color. 0 0 0)))
    (uifonts/install-zoom-bindings! (.getRootPane frame))
    ;; Ctrl+E exports the whole conversation as colour-preserving HTML
    (let [im (.getInputMap (.getRootPane frame) JComponent/WHEN_IN_FOCUSED_WINDOW)
          am (.getActionMap (.getRootPane frame))]
      (.put im (KeyStroke/getKeyStroke KeyEvent/VK_E (java.awt.event.InputEvent/CTRL_DOWN_MASK))
            "grog-export")
      (.put am "grog-export"
            (proxy [AbstractAction] []
              (actionPerformed [_] (uiexport/save-transcript! frame pane)))))
    ;; register the chat panes for the shared appearance/zoom system
    (uifonts/apply-all!)
    ;; greet with a snarky startup line in the transcript
    (let [snark (or (some-> (soul/startup-snark-line) str/trim not-empty)
                    chat-startup-snark-fallback)]
      (binding [*out* (transcript/styled-writer pane)]
        (println (str ansi-snark snark ansi-reset "\n"))))
    (.setDefaultCloseOperation frame JFrame/EXIT_ON_CLOSE)
    (.setSize frame 1350 1020)
    (.setLocationRelativeTo frame nil)
    (set-frame-icon! frame)
    ;; Give the prompt keyboard focus once the frame is shown & laid out, so on
    ;; Windows the caret/typing land in the input area (and Enter reaches it)
    ;; instead of falling to the transcript/scrollpane. Best-effort; runs after
    ;; the window is visible via invokeLater.
    (SwingUtilities/invokeLater
      (fn []
        (try
          (.requestFocusInWindow prompt)
          (catch Throwable _ nil))))
    frame))

(defn -main
  "Entry point: `clojure -M -m grog.ui`, `./grog-ui`, or `grog-ui.bat`."
  [& _]
  ;; cleanly shut down the ECA subprocess (if any) on JVM exit
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. (fn [] (eca/disconnect!))))
  (SwingUtilities/invokeLater
    (fn []
      (try
        ;; apply the modern dark Look & Feel across both windows
        (com.formdev.flatlaf.FlatDarkLaf/setup)
        ;; Force dark text-area defaults through the L&F's UIManager so no
        ;; Windows L&F default (white background) can override the prompt box.
        ;; Without this, FlatLaf's TextArea UI can fall back to a light/white
        ;; background on Windows while the foreground stays light — leaving a
        ;; white box with barely-readable text.
        (doseq [[k v] {"TextArea.background" (Color. 18 18 18)
                       "TextArea.foreground" (Color. 230 230 230)
                       "TextArea.caretForeground" (Color. 230 230 230)
                       "TextArea.inactiveBackground" (Color. 18 18 18)
                       "TextArea.inactiveForeground" (Color. 230 230 230)
                       "TextPane.background" (Color. 18 18 18)
                       "TextPane.foreground" (Color. 230 230 230)}]
          (javax.swing.UIManager/put k v))
        ;; enlarge the L&F's base UI fonts from the desktop's system font so
        ;; dialogs (settings, model picker, approvals) and labels read larger
        (widgets/scale-ui-fonts!)
        ;; load persisted appearance (fonts/colours) from grog.edn
        (appearance/load!)
        (let [^javax.swing.JFrame f (build-chat-frame)]
          (.setVisible f true)
          ;; DIAGNOSTIC: report what the JVM actually computed for the prompt's
          ;; colors vs. the L&F defaults, so we can see whether the box should
          ;; be dark (code) but the screen shows it light (rendering issue).
          (SwingUtilities/invokeLater
            (fn []
              (try
                (dbg! "UIManager TextArea.background=" (javax.swing.UIManager/get "TextArea.background")
                      " TextArea.foreground=" (javax.swing.UIManager/get "TextArea.foreground")
                      " LAF=" (or (some-> (javax.swing.UIManager/getLookAndFeel) .getName) "?")
                      " d3d=" (System/getProperty "sun.java2d.d3d")
                      " noddraw=" (System/getProperty "sun.java2d.noddraw"))
                (catch Throwable e (dbg! "diag err:" (.getMessage e))))))
          f)
        (catch Throwable t
          (println "grog.ui failed:" t)))))
  nil)
