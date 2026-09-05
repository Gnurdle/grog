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
            [grog.projects :as projects]
            [grog.secrets :as secrets]
            [grog.project-dialog :as project-dialog]
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
  (:import (java.awt Color Component Font Graphics Graphics2D Image Toolkit BorderLayout FlowLayout Point)
           (javax.imageio ImageIO)
           (javax.swing AbstractAction Box BoxLayout JComponent JDialog JFrame JLabel JList
                        JMenuItem JOptionPane JPanel JPopupMenu JScrollPane JTextArea
                        JTextField JToolBar KeyStroke ListSelectionModel SwingUtilities)
           (java.util.concurrent LinkedBlockingQueue)
           (java.awt.datatransfer StringSelection)
           (java.awt.event KeyEvent MouseAdapter)))

;; Startup snark fallback when the soul pool is empty.
(def ^:private chat-startup-snark-fallback
  "No snark pool — someone edited the wrong file. Pity.")

;; Provider API keys are read from the OS keyring (/secret) and injected into
;; the ECA child process env at launch — ECA config references them only as
;; `${env:...}` (no literal keys, no persistent Windows env vars).
(def ^:private provider-env-accounts
  ["OPENROUTER_API_KEY" "MOONSHOT_API_KEY" "XAI_API_KEY"])

(defn- provider-env
  "Per-process env vars for the ECA server, pulled from the OS keyring."
  []
  (into {} (keep (fn [acct]
                   (when-let [v (secrets/get-secret acct)]
                     [acct v])))
        provider-env-accounts))

;; Debug tracer: writes to the real stderr (so it lands in grog-ui's rotated
;; log — grog-ui.log or $GROG_LOG) regardless of the pane *out*/*err* rebindings.
(defn- dbg! [& xs]
  (.println System/err (str "[grog-debug] " (apply str (interpose " " (map str xs))))))

;; --- ECA<->grog protocol tracing -------------------------------------------
;; Every JSON-RPC frame that crosses the stdio pipe to the `eca server` child is
;; summarized here (via eca.clj's :trace-fn). The launcher (./grog-ui /
;; grog-ui.bat) tees stderr/stdout into a single rotated log file (default
;; ~/grog-ui.log on Linux, %USERPROFILE%\grog-ui.log on Windows, or $GROG_LOG),
;; so these traces land there regardless of any *out*/*err* rebinding to the
;; transcript pane.

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
  "Returns {:panel <JPanel painted with the chat background colour and the logo>
  :subdue! <fn [bool]>}.

  The panel paints the chat background colour (matching the transcript while it
  is non-opaque), then, while the transcript is still in its splash state
  (`:splash? true`, i.e. no conversation yet), the viewport stays transparent so
  the centred logo shows through here. `subdue!` darkens the logo under a heavy
  scrim once the first real message arrives (the transcript then paints its own
  opaque background, so this primarily affects the moment of the transition).
  Painted with the current chat background colour; the logo is aspect-fit scaled."
  []
  (let [img @logo-image
        subdued? (atom false)
        panel-ref (atom nil)]
    {:panel (reset! panel-ref
              (proxy [JPanel] []
                (paintComponent [g]
                  (let [^Graphics2D g2 (.create ^Graphics g)
                        [r gr b] (appearance/rgb [:chat :background])]
                    ;; 1) solid chat-background base — fills any letterbox bands
                    ;;    around the aspect-fit logo so there are no mismatched margins
                    (.setColor g2 (Color. (int r) (int gr) (int b)))
                    (.fillRect g2 0 0 (.getWidth this) (.getHeight this))
                    ;; 2) centred logo scaled-to-fit (keeps aspect ratio)
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
  "Make `c` non-opaque so the background color shows through (no-op unless it's
  a JComponent)."
  [^Component c]
  (when (instance? JComponent c)
    (.setOpaque ^JComponent c false))
  c)

(defn- chat-bg-color
  "The chat background as a `Color` (from `:appearance :chat :background`)."
  ^Color []
  (let [[r g b] (appearance/rgb [:chat :background])]
    (Color. (int r) (int g) (int b))))

(defn- darken-tree!
  "Force `comp` and every descendant JComponent to OPAQUE with `color`. Used on
  Windows where FlatLaf transparent components repaint as white: by making every
  pixel of a region owned by an opaque component, the repaint pipeline can never
  show the default light background through."
  [^java.awt.Component comp ^Color color]
  (when (instance? JComponent comp)
    (doto ^JComponent comp
      (.setOpaque true)
      (.setBackground color)))
  (when (instance? java.awt.Container comp)
    (doseq [child (.getComponents ^java.awt.Container comp)]
      (darken-tree! child color)))
  comp)

(defn- darken-toolbar!
  "On Windows, force the JToolBar and everything inside it (including the
  LAF's internal content panel that actually holds the buttons) to opaque with
  the chat background. The JToolBar's internal panel is not guaranteed to follow
  the toolbar's background, and a transparent/light panel there shows up as a
  light band across the bottom of the chat window."
  [^JToolBar tb]
  (darken-tree! tb (chat-bg-color))
  tb)

(defn- component-tree-lines
  "One line per JComponent in `comp`'s subtree, describing class, opacity,
  background RGB, visibility and size — used to audit exactly which component
  could possibly render as white on Windows."
  [^java.awt.Component comp]
  (let [lines (atom [])]
    (letfn [(walk! [^java.awt.Component c depth]
              (when (instance? JComponent c)
                (let [^JComponent jc c
                      bg (.getBackground jc)
                      bg-s (if bg
                             (str (.getRed bg) "," (.getGreen bg) "," (.getBlue bg))
                             "null")
                      name (try (.getName (class jc)) (catch Throwable _ "?"))]
                  (swap! lines conj
                         (str (apply str (repeat (* 2 depth) " "))
                              name
                              " opaque=" (.isOpaque jc)
                              " bg=" bg-s
                              " vis=" (.isShowing jc)
                              " " (.getWidth jc) "x" (.getHeight jc)))))
              (when (instance? java.awt.Container c)
                (doseq [ch (.getComponents ^java.awt.Container c)]
                  (walk! ch (inc depth)))))]
      (walk! comp 0))
    @lines))

(defn- snapshot-frame!
  "Render the frame's component tree offscreen into a PNG in the OS temp dir.
  Returns the absolute path of the saved file (or nil on failure)."
  [^JFrame f]
  (try
    (let [w (.getWidth f)
          h (.getHeight f)
          img (java.awt.image.BufferedImage. w h java.awt.image.BufferedImage/TYPE_INT_RGB)
          g (.createGraphics img)]
      (try
        (.printAll ^java.awt.Component f ^Graphics2D g)
        (catch Throwable _
          (.print ^java.awt.Component f ^Graphics2D g))
        (finally (.dispose g)))
      (let [file (java.io.File. (System/getProperty "java.io.tmpdir") "grog-ui-frame.png")]
        (javax.imageio.ImageIO/write img "png" file)
        (.getAbsolutePath file)))
    (catch Throwable _ nil)))

(defn- darken-viewport!
  "Make a scroll-pane + viewport OPAQUE chat-background. The chat scroll area is
  never transparent: the transcript view paints its own background (and, while
  in splash state, the centred logo) so there is no reliance on parent repainting
  — which is what produces the white boxes under FlatLaf on Windows."
  [^JScrollPane sp]
  (let [c (chat-bg-color)]
    (doto sp
      (.setOpaque true)
      (.setBackground c))
    (when-let [vp (.getViewport sp)]
      (doto vp
        (.setOpaque true)
        (.setBackground c)))
    sp))

(defn- with-copy-menu!
  "Add a right-click popup menu to `c`. Each item is either `[label text-fn]`
  (copies `(text-fn)` to the clipboard — e.g. the transcript's \"Copy selection\" /
  \"Copy all\" and the prompt's \"Copy\") or `{:label label :action (fn [])}`
  which runs an action instead (e.g. \"Open transcript as HTML…\")."
  [^java.awt.Component c items]
  (let [menu (JPopupMenu.)]
    (doseq [it items]
      (let [item (if (map? it)
                   (let [item (JMenuItem. (:label it))]
                     (.addActionListener item
                       (reify java.awt.event.ActionListener
                         (actionPerformed [_ _]
                           (try ((:action it)) (catch Throwable _ nil)))))
                     item)
                   (let [[label text-fn] it
                         item (JMenuItem. label)]
                     (.addActionListener item
                       (reify java.awt.event.ActionListener
                         (actionPerformed [_ _]
                           (let [sel (try (text-fn) (catch Throwable _ nil))]
                             (when (seq sel)
                               (.setContents (.getSystemClipboard (Toolkit/getDefaultToolkit))
                                             (StringSelection. sel)
                                             nil))))))
                     item))]
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

;; (file-uri removed — ECA workspace URIs are built robustly in grog.projects/workspace-folders)

(defn- make-event-handler
  "Build the ECA event handler for the transcript: renders `chat/contentReceived`
  (streaming inline), flips `running?` when a prompt finishes, and prompts the
  user to approve/reject manual-approval tool calls. Runs on the ECA reader thread.
  Rendering is driven by `grog.ui.eca-stream`, which emits structured messages
  (assistant/thinking/tool cards) onto the rich transcript.

  `yolo-ref` is the trust (YOLO) atom: when it's truthy, manual-approval tool
  calls are auto-approved and the dialog is skipped — \"check it and everything
  goes\". `last-sent` holds the most recently sent user message, so the ECA echo
  of that message (a `text` content event mirroring the prompt) can be suppressed
  instead of duplicating the local user echo. `pending-steer*` tracks a steer
  that has been sent but not yet confirmed consumed by ECA; `resend-steer!` is
  called with the steer text to re-issue it as a normal prompt if the run ends
  before ECA consumes it (the protocol's documented fallback)."
  [^JComponent pane running? chat-id yolo-ref last-sent pending-steer* resend-steer!]
  (let [streamer (ecastream/make-streamer pane)
        ;; Accumulate assistant text across `text` content events so the
        ;; completed reply can be logged to the project dialog on finish.
        assistant-acc (atom "")
        ;; a steer is consumed when ECA echoes it back; resend undelivered steers
        ;; on any idle/finished transition (protocol fallback).
        finish! (fn []
                  (when-let [s @pending-steer*]
                    (reset! pending-steer* nil)
                    (resend-steer! s))
                  ;; the assistant reply is done — persist it to the project
                  ;; dialog (best effort; ignore if no active project).
                  (let [reply (str/trim (str @assistant-acc))]
                    (reset! assistant-acc nil)
                    (when (seq reply)
                      (try
                        (project-dialog/append-turn! :assistant reply)
                        (catch Throwable e
                          (dbg! "dialog append assistant error:" (.getMessage e))))))
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
            ;; accumulate the assistant reply text for the project dialog
            (when (and (= "text" (:type content))
                       (seq (str (:text content))))
              (swap! assistant-acc str (str (:text content))))
            (streamer content)
            (when (= "finished" (:state content))
              (finish!))
            ;; manual-approval tool call -> in YOLO mode auto-approve (everything
            ;; goes, no permission dialog); otherwise ask the user in a dialog
            ;; with a readable font and a click of YOLO to switch into trust mode.
            (when (and (= "toolCallRun" (:type content)) (true? (:manualApproval content)))
              (if @yolo-ref
                (eca/approve! @chat-id (:id content))
                (let [id (:id content)
                      name (str (:name content))
                      summary (:summary content)
                      ;; Tool name/summary in a modest, regular-weight monospace
                      ;; (NOT the 1.5x dialog scale) so long summaries stay
                      ;; compact instead of towering over the dialog.
                      body (doto (JTextArea.
                                  (str "Approve tool call?\n\n  " name "\n"
                                       (when-let [args (:arguments content)]
                                         (when (seq args)
                                           (str "\n  args: " (pr-str args) "\n")))
                                       (when (seq summary) (str "\n" summary "\n"))
                                       "\n• Approve — just this once\n"
                                       "• Approve tool — permanently allow this tool\n"
                                       "• Reject — don't run it\n"
                                       "• YOLO — this call and everything after, no more dialogs"))
                             (.setEditable false)
                             (.setLineWrap true)
                             (.setWrapStyleWord true)
                             (.setFont (widgets/dialog-mono-font))
                             (.setOpaque true)
                             (.setBackground (Color. 22 24 30))
                             (.setForeground (Color. 224 226 232))
                             (.setCaretColor (Color. 224 226 232))
                             (.setCaretPosition 0)
                             (.setBorder (javax.swing.BorderFactory/createEmptyBorder
                                          12 14 12 14)))
                      ;; Scrollable center keeps the body bounded; the buttons
                      ;; live in a separate SOUTH panel so they are ALWAYS
                      ;; visible and reachable, no matter how long the summary.
                      scroll (doto (JScrollPane. body)
                               (.setBorder (javax.swing.BorderFactory/createLineBorder
                                             (Color. 60 63 72))))
                      _ (widgets/boost-horizontal-wheel! scroll)
                      approve (widgets/styled-button "Approve")
                      approve-tool (widgets/styled-button "Approve tool")
                      reject  (widgets/styled-button "Reject")
                      yolo    (widgets/styled-button "YOLO")
                      ;; GridLayout (not FlowLayout) so all four buttons stay
                      ;; visible at any dialog/font size — FlowLayout clips the
                      ;; trailing button (YOLO) when they overflow the width.
                      buttons (doto (JPanel. (java.awt.GridLayout. 1 4 8 8))
                                (.setBorder (javax.swing.BorderFactory/createEmptyBorder
                                             10 12 12 12))
                                (.add approve)
                                (.add approve-tool)
                                (.add reject)
                                (.add yolo))
                      dialog (doto (JDialog. (JOptionPane/getFrameForComponent pane)
                                             "grog — tool approval" true)
                               (.setLayout (BorderLayout.))
                               (.add scroll BorderLayout/CENTER)
                               (.add buttons BorderLayout/SOUTH)
                               (.setSize (java.awt.Dimension. 620 340))
                               (.setMinimumSize (java.awt.Dimension. 380 200))
                               ;; Resizable, so a tall summary on a short screen
                               ;; can always be shrunk/scrolled to reach the
                               ;; buttons on the SOUTH panel.
                               (.setResizable true)
                               (.setLocationRelativeTo pane)
                               ;; X / Esc / focus loss without a click:
                               ;; windowClosed fires the reject path below.
                               (.setDefaultCloseOperation JDialog/DISPOSE_ON_CLOSE))
                      ;; guard so windowClosed (fired by dispose in the button
                      ;; handlers too) doesn't double-answer with a reject.
                      decided? (volatile! false)]
                  ;; Enter approves (the natural default action); Esc closes.
                  (when-let [^javax.swing.JRootPane rp (.getRootPane dialog)]
                    (.setDefaultButton rp approve))
                  (.put (.getInputMap (.getRootPane dialog) JComponent/WHEN_IN_FOCUSED_WINDOW)
                        (KeyStroke/getKeyStroke KeyEvent/VK_ESCAPE 0)
                        "grog-approval-cancel")
                  (.put (.getActionMap (.getRootPane dialog)) "grog-approval-cancel"
                        (proxy [AbstractAction] []
                          (actionPerformed [_] (.dispose dialog))))
                  (.addActionListener approve
                    (proxy [java.awt.event.ActionListener] []
                      (actionPerformed [_]
                        (vreset! decided? true)
                        (.dispose dialog)
                        (eca/approve! @chat-id id))))
                  (.addActionListener reject
                    (proxy [java.awt.event.ActionListener] []
                      (actionPerformed [_]
                        (vreset! decided? true)
                        (.dispose dialog)
                        (eca/reject! @chat-id id))))
                  ;; "Approve tool" — permanently allow this tool in the
                  ;; approved-tools allowlist, then approve the current call.
                  (.addActionListener approve-tool
                    (proxy [java.awt.event.ActionListener] []
                      (actionPerformed [_]
                        (vreset! decided? true)
                        (.dispose dialog)
                        (ecacfg/approve-tool! name)
                        (eca/approve! @chat-id id))))
                  (.addActionListener yolo
                    (proxy [java.awt.event.ActionListener] []
                      (actionPerformed [_]
                        (vreset! decided? true)
                        (.dispose dialog)
                        ;; "YOLO" — approve this call and switch into trust mode
                        ;; so all future tool calls auto-approve.
                        (reset! yolo-ref true)
                        (uifooter/set-trust-indicator! true)
                        (eca/set-trust! @chat-id true)
                        (eca/approve! @chat-id id))))
                  ;; Dialog dismissed (X / Esc / lost focus) without a choice →
                  ;; treat as a safe reject rather than running the tool.
                  (.addWindowListener dialog
                    (proxy [java.awt.event.WindowAdapter] []
                      (windowClosed [_]
                        (when-not @decided?
                          (eca/reject! @chat-id id)))))
                  ;; Show the MODAL approval dialog on the EDT. This handler runs
                  ;; on the ECA reader thread; showing a modal dialog directly off
                  ;; the EDT spins a nested event pump that can wedge the EDT and
                  ;; leave the whole app unresponsive to close. Hoisting the show
                  ;; onto the EDT keeps the frame responsive and lets the reader
                  ;; thread keep dispatching.
                  (SwingUtilities/invokeLater
                    (fn []
                      (try
                        (.setVisible dialog true)
                        (catch Throwable e
                          (dbg! "approval dialog error:" (.getMessage e)))))))))))

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
        (.add center (doto (JScrollPane. list)
                       (widgets/boost-horizontal-wheel!))))
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
    ;; Robust Enter handling. A WHEN_FOCUSED InputMap binding put here is
    ;; discarded when the LAF UI installs during `setVisible` (installUI
    ;; replaces the component's WHEN_FOCUSED map), so plain Enter on the custom
    ;; field would fall through to the text field's default `notify-field-accept`
    ;; — which has no listeners — doing nothing. A KeyListener fires on the
    ;; focused component regardless of UI install timing; consuming the event
    ;; stops the default action from also running. Enter submits from either the
    ;; custom field or the option list.
    (let [enter-listener
          (proxy [java.awt.event.KeyAdapter] []
            (keyPressed [e]
              (when (= KeyEvent/VK_ENTER (.getKeyCode e))
                (.consume e)
                (choose!))))]
      (.addKeyListener custom enter-listener)
      (when list
        (.addKeyListener list enter-listener)))
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
        (transcript/append-status!
         pane
         (str "[LLM question] " prompt))
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
  (cancel/clear!)
  ;; echo the user's input (prompt or command) as a bubble
  (when (seq (str/trim text))
    (transcript/append-user! pane text))
  (binding [*out* (transcript/console-writer pane)
            *err* (transcript/console-writer pane)]
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
          ;; wipe the transcript, and drop any trust (yolo) auto-approve so a
          ;; fresh transcript starts from a clean slate
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
          (dbg! "worker take: " (pr-str (str text)))
          (try
            (reset! history-ref (handle-turn! pane @history-ref text send-fn set-model-fn set-yolo-fn))
            (catch Throwable e
              ;; Never let a hidden exception in the turn pipeline kill the
              ;; worker thread — that would silently swallow every message
              ;; queued after it. Log it loudly so grog-ui's log shows what
              ;; actually failed instead of a dead, mute queue.
              (dbg! "worker turn error: " (.getMessage e))
              (dbg! (str (with-out-str (.printStackTrace e))))
              (transcript/append-status! pane (str "[grog] internal error handling that message: " (.getMessage e)))))
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
    ;; compute line height from the chat appearance (matches the renderer)
    (try
      (reset! line-h (max 12 (transcript/line-height-px pane)))
      (catch Throwable _ (reset! line-h 20)))
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

(defn- show-project-manager!
  "A modal dialog to manage projects: **create** (the primary action, with an
  optional description), switch, or delete one. A dedicated 'New project' form
  sits at the top (name + optional description + Create button, focused on
  open) with the existing project list below for Open/Delete.
  `frame` is the owner; `on-switch` is `(fn [name])` — the chat frame wires
  switching there so the ECA workspace/context/title follow. Reads/writes the
  projects home directly."
  [^JFrame frame on-switch]
  (let [create-btn (widgets/styled-button "Create project")
        list-model (javax.swing.DefaultListModel.)
        proj-list (doto (JList. list-model)
                    (.setSelectionMode ListSelectionModel/SINGLE_SELECTION)
                    (.setFont (widgets/ui-font)))
        cell-renderer (proxy [javax.swing.DefaultListCellRenderer] []
                        (getListCellRendererComponent [list value idx sel? foc?]
                          (let [nm (str value)
                                cur (projects/project-name)
                                active? (= nm cur)]
                            (doto (proxy-super getListCellRendererComponent
                                    list value idx sel? foc?)
                              (.setFont (widgets/ui-font))
                              (.setForeground (if active?
                                                (Color. 235 200 90)
                                                (Color. 225 228 232)))
                              (.setText (if active? (str nm "  ← active") nm))))))
        refresh! (fn []
                   (.removeAllElements list-model)
                   (doseq [n (projects/list-project-names)]
                     (.addElement list-model n))
                   (when-let [cur (projects/project-name)]
                     (.setSelectedValue proj-list cur true)))
        open-btn (widgets/styled-button "Open")
        del-btn  (widgets/styled-button "Delete")
        close-btn (widgets/styled-button "Close")
        name-field (JTextField. 30)
        desc-field (JTextField. 30)
        error-label (doto (JLabel. " ")
                      (.setFont (widgets/ui-font))
                      (.setForeground (Color. 255 110 110)))
        dialog-ref (atom nil)
        dismiss! (fn []
                   (when-let [d @dialog-ref]
                     (.dispose d)))
        set-error! (fn [msg]
                     (doto error-label
                       (.setText (or msg " "))
                       (.setVisible (boolean msg))))
        name-error
        (fn [nm]
          (cond
            (str/blank? nm) "enter a project name."
            (or (str/includes? nm "/") (str/includes? nm "\\"))
            "the name can't contain a path separator (/ or \\)."
            (or (= nm ".") (= nm "..")) "that's not a usable project name."
            :else nil))
        open-selected! (fn []
                         (when-let [nm (.getSelectedValue proj-list)]
                           (when (and (seq nm) (not= nm (projects/project-name)))
                             (on-switch (str nm))
                             (dismiss!))
                           (refresh!)))
        create! (fn []
                  (let [nm (str/trim (str (.getText name-field)))
                        desc (str/trim (str (.getText desc-field)))
                        problem (name-error nm)]
                    (if problem
                      (set-error! (str "Can't create: " problem))
                      (do
                        (set-error! nil)
                        (projects/create-project! nm desc)
                        (on-switch nm)
                        (dismiss!)))))
        delete! (fn []
                  (let [nm (str/trim (str (.getSelectedValue proj-list)))]
                    (cond
                      (str/blank? nm) nil
                      (= nm (projects/project-name))
                      (JOptionPane/showMessageDialog
                       nil (str "Can't delete the active project (" nm ").\nSwitch to another first.")
                       "Delete project" JOptionPane/WARNING_MESSAGE)
                      :else
                      (let [res (JOptionPane/showConfirmDialog
                                 nil (str "Delete project \"" nm "\"?") "Delete project" JOptionPane/YES_NO_OPTION)]
                        (when (= res JOptionPane/YES_OPTION)
                          (projects/delete-project! nm)
                          (refresh!))))))
        dialog (doto (JDialog. frame "Projects — grog" true)
                 (.setLayout (BorderLayout.))
                 (.setSize 480 560)
                 (.setMinimumSize (java.awt.Dimension. 420 480))
                 (.setLocationRelativeTo frame))]
    (reset! dialog-ref dialog)
    (let [name-label (doto (JLabel. "Name")
                       (.setFont (widgets/ui-font)))
          desc-label (doto (JLabel. "Description (optional)")
                       (.setFont (widgets/ui-font)))
          form-inner (let [p (JPanel.)]
                       (doto p
                         (.setLayout (BoxLayout. p BoxLayout/Y_AXIS))
                         (.setBorder (javax.swing.BorderFactory/createEmptyBorder 8 8 4 8))))
          form (doto (JPanel. (BorderLayout.))
                 (.setBorder (javax.swing.BorderFactory/createTitledBorder "New project"))
                 (.add form-inner BorderLayout/CENTER))
          list-label (doto (JLabel. "Open an existing project:")
                       (.setFont (widgets/ui-font)))
          proj-scroll (doto (JScrollPane. proj-list)
                        (widgets/boost-horizontal-wheel!))
          btn-row (doto (JPanel. (java.awt.FlowLayout. java.awt.FlowLayout/LEFT 8 8))
                     (.add open-btn)
                     (.add del-btn)
                     (.add close-btn))
          south (doto (JPanel. (java.awt.GridLayout. 0 1 4 4))
                 (.add list-label)
                 (.add btn-row))]
      ;; ---- the create-form ----
      (doto form-inner
        (.add name-label)
        (.add (doto name-field
                (.setFont (widgets/ui-font))
                (.setToolTipText "Project name; becomes its directory under the projects home.")
                (.addActionListener
                 (proxy [java.awt.event.ActionListener] []
                   (actionPerformed [_] (create!))))))
        (.add (Box/createVerticalStrut 6))
        (.add desc-label)
        (.add (doto desc-field
                (.setFont (widgets/ui-font))
                (.setToolTipText "One-line description shown as context (optional).")
                (.addActionListener
                 (proxy [java.awt.event.ActionListener] []
                   (actionPerformed [_] (create!))))))
        (.add (Box/createVerticalStrut 10))
        (.add (doto (JPanel. (java.awt.FlowLayout. java.awt.FlowLayout/LEFT 0 0))
                (.add create-btn)))
        (.add (Box/createVerticalStrut 6))
        (.add error-label))
      (doto create-btn
        (.setToolTipText "Create the project's directory + notes/ context, then open it.")
        (.addActionListener
         (proxy [java.awt.event.ActionListener] []
           (actionPerformed [_] (create!)))))
      ;; ---- existing project list: switch / delete ----
      (.setCellRenderer proj-list cell-renderer)
      (refresh!)
      (doto proj-list
        (.setBorder (javax.swing.BorderFactory/createEmptyBorder 8 8 8 8)))
      (.addActionListener open-btn
        (proxy [java.awt.event.ActionListener] []
          (actionPerformed [_] (open-selected!))))
      (.addActionListener del-btn
        (proxy [java.awt.event.ActionListener] []
          (actionPerformed [_] (delete!))))
      (.addActionListener close-btn
        (proxy [java.awt.event.ActionListener] []
          (actionPerformed [_] (dismiss!))))
      ;; Enter in the list opens the selected project; double-click too
      (.addKeyListener proj-list
        (proxy [java.awt.event.KeyAdapter] []
          (keyPressed [e]
            (when (= java.awt.event.KeyEvent/VK_ENTER (.getKeyCode e))
              (open-selected!)))))
      (.addMouseListener proj-list
        (proxy [java.awt.event.MouseAdapter] []
          (mouseClicked [e]
            (when (and (= (.getClickCount e) 2)
                       (= java.awt.event.MouseEvent/BUTTON1 (.getButton e))
                       (seq (.getSelectedValue proj-list)))
              (open-selected!)))))
      (doto dialog
        (.add form BorderLayout/NORTH)
        (.add proj-scroll BorderLayout/CENTER)
        (.add south BorderLayout/SOUTH)
        (.setDefaultCloseOperation JDialog/DISPOSE_ON_CLOSE))
      ;; focus the name field so creating a project is one keystroke away
      (SwingUtilities/invokeLater
        (fn []
          (try
            (.requestFocusInWindow name-field)
            (.selectAll name-field)
            (catch Throwable _ nil))))
      (.setVisible dialog true))))

(defn- build-chat-frame
  "Build the chat frame (not yet shown). Returns the JFrame."
  ^JFrame []
  (let [transcript-scroll (transcript/make-chat-pane @logo-image)
        pane (transcript/chat-pane transcript-scroll)
        prompt (JTextArea. 4 84)
        prompt-scroll (JScrollPane. prompt)
        send (widgets/toolbar-button :send "Send (Ctrl+Enter)")
        stop (widgets/toolbar-button :stop "Stop")
        term (widgets/toolbar-button :terminal "Terminal")
        settings (widgets/toolbar-button :settings "Settings")
        export (widgets/toolbar-button :export "Export transcript")
        view-html (widgets/toolbar-button :html "Open transcript as HTML")
        clear (widgets/toolbar-button :clear "Clear")
        frame (JFrame. (str "grog — " (or (projects/project-name) "default")))
        queue (LinkedBlockingQueue.)
        running? (atom false)
        history-ref (atom [])
        chat-id (atom (projects/active-project-chat-id))
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
                                            (transcript/append-status! pane (str "[grog] resending as a prompt: " s))
                                            (reset! last-sent (str s))
                                            (.put ^LinkedBlockingQueue queue (str s))))
        connect-eca! (fn []
                       (when-not @connected
                         (try
                           (let [cfg (ecacfg/generate-config!)
                                 ws (projects/workspace-folders)
                                 _ (dbg! "ECA starting: config=" cfg
                                         " model=" (or (uifooter/current-model) "(none)")
                                         " chatId=" @chat-id
                                         " workspace=" (pr-str ws))
                                 init (eca/connect! ws
                                                    :event-handler event-handler
                                                    :request-handler (make-request-handler pane)
                                                    :eca-binary (config/eca-binary)
                                                    :args ["--config-file" cfg]
                                                    :env (provider-env)
                                                    :log-fn (fn [line] (dbg! "eca:" line))
                                                    :trace-fn (make-eca-tracer))]
                             (dbg! "ECA started ok, init model=" (get-in init [:ok :model])))
                           (reset! connected true)
                           (catch Throwable e
                             (transcript/append-status! pane (str "[grog] ECA connect failed: " (.getMessage e)))
                             (reset! running? false)))))
        send-fn (fn [history text]
                  (connect-eca!)
                  (if-not @connected
                    (do (reset! running? false)
                        ;; ECA is down after a send attempt — don't silently
                        ;; swallow the user's message. Make it obvious both on
                        ;; screen and in grog-ui.log (the log is the dif for
                        ;; reproducing what happened next).
                        (let [msg (str "[grog] not connected to ECA — message not sent: " text)]
                          (dbg! msg)
                          (transcript/append-status! pane msg))
                        history)
                    (do
                      (reset! running? true)
                      (cancel/clear!)
                      (reset! last-sent (str text))
                      ;; persist the user's message to the project dialog
                      ;; (best effort; no-op without an active project)
                      (try
                        (project-dialog/append-turn! :user (str text))
                        (catch Throwable e
                          (dbg! "dialog append user error:" (.getMessage e))))
                      (dbg! "send-fn: connected, about to prompt -> " (pr-str (str text)) " model-next=" (pr-str (uifooter/current-model)))
                      (let [url (try (config/llm-url) (catch Exception _ nil))
                            ;; ECA needs an explicit model or it fails with
                            ;; "No available model found"; fall back to grog's
                            ;; configured model when the footer model is unset.
                            model (or (some-> (uifooter/current-model)
                                              (models/qualify-eca-model nil url))
                                      (models/qualify-eca-model (config/eca-model) nil url))]
                        (try
                          (let [resp (eca/prompt! text {:chatId @chat-id
                                                        :model model
                                                        :trust @yolo-ref})
                                ;; ECA reports model/backend failures IN-BAND as
                                ;; {:ok {:model "error" :status "error"}} (no
                                ;; JSON-RPC :error key) — surface those instead
                                ;; of silently swallowing them.
                                e (:error resp)
                                o (:ok resp)]
                            (cond
                              e
                              (transcript/append-status! pane (str "[grog] " (or (:message e) (pr-str e))))

                              (= "error" (some-> o :status str))
                              (transcript/append-status!
                               pane (str "[grog] ECA error: "
                                         (or (some-> o :message str (not-empty))
                                             (some-> o :model str (not-empty))
                                             (pr-str o))))

                              :else
                              (dbg! "eca prompt ok: model=" (:model o) " status=" (:status o))))
                          (catch Throwable e
                            (dbg! "eca prompt error:" (.getMessage e))
                            (reset! running? false)
                            (transcript/append-status! pane (str "[grog] " (.getMessage e)))))
                        (conj history {:user text})))))
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
                         (transcript/append-status! pane (str "model: " id))))
        set-yolo-fn (fn [on?]
                      (let [next (if (nil? on?) (not @yolo-ref) on?)]
                        (reset! yolo-ref next)
                        (uifooter/set-trust-indicator! next)
                        (when @connected
                          (eca/set-trust! @chat-id next))
                        (transcript/append-status!
                         pane
                         (str "trust (yolo) mode: "
                              (if next "ON — tool calls auto-approved" "off")))))
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
                            (transcript/append-user! pane t)
                            (when @connected
                              (eca/steer! @chat-id t)))
                        ;; idle: queue a normal prompt as before
                        (do
                          (dbg! "submit -> queue: " (pr-str t) " running?=" @running?)
                          (.put ^LinkedBlockingQueue queue t))))))]
    ;; larger fonts
    (doto pane
      (.setFont (ui-monospace-font))
      ;; the transcript view is custom-painted and OPAQUE (it paints its own
      ;; chat background + splash logo in paint-view!), so keep it opaque with
      ;; the chat background — a transparent pane is what repaints as white
      ;; under FlatLaf on Windows.
      (.setOpaque true)
      (.setBackground (chat-bg-color)))
    (doto prompt (.setFont (ui-monospace-font)))
    ;; all footer buttons are widgets/styled-button and already get the compact
    ;; system-derived button font via style!; no per-button setFont needed here.
    (uifonts/register-chat! pane :transcript)
    (uifonts/register-chat! prompt :prompt)
    ;; drag-and-drop on the prompt only (the transcript is a virtualized JList)
    (dnd/install! prompt)
    ;; right-click Copy on the transcript and prompt
    (with-copy-menu! pane
      [["Copy all" #(transcript/text pane)]
       {:label "Copy selected"
        :action #(transcript/copy-selection! pane)}
       {:label "Open transcript as HTML…"
        :action #(uiexport/show-transcript-html! frame pane)}])
    (with-copy-menu! prompt [["Copy" #(.getSelectedText prompt)]])
    ;; single-line scroll keys on the transcript
    (install-transcript-scroll-keys! pane transcript-scroll frame)
    ;; Shift+MouseWheel horizontal scroll: the JVM default is a single unit per
    ;; notch (≈1 char for text views) — too sluggish. Boost it on the chat input
    ;; and transcript so one wheel step moves a meaningful chunk.
    (widgets/boost-horizontal-wheel! prompt-scroll)
    (widgets/boost-horizontal-wheel! transcript-scroll)
    ;; Send button submits
    (.addActionListener send (reify java.awt.event.ActionListener
                               (actionPerformed [_ _] (submit!))))
    ;; Enter inserts a newline; Ctrl+Enter submits
    (.put (.getInputMap prompt javax.swing.JComponent/WHEN_FOCUSED)
          (KeyStroke/getKeyStroke KeyEvent/VK_ENTER 0)
          "insert-break")
    (.put (.getInputMap prompt javax.swing.JComponent/WHEN_FOCUSED)
          (KeyStroke/getKeyStroke KeyEvent/VK_ENTER (java.awt.event.InputEvent/CTRL_DOWN_MASK))
          "grog-submit")
    (.put (.getInputMap prompt javax.swing.JComponent/WHEN_FOCUSED)
          (KeyStroke/getKeyStroke KeyEvent/VK_ENTER (java.awt.event.InputEvent/SHIFT_DOWN_MASK))
          "insert-break")
    (.put (.getInputMap prompt javax.swing.JComponent/WHEN_FOCUSED)
          (KeyStroke/getKeyStroke KeyEvent/VK_ENTER (java.awt.event.InputEvent/ALT_DOWN_MASK))
          "insert-break")
    (.put (.getActionMap prompt) "grog-submit"
          (proxy [AbstractAction] []
            (actionPerformed [e] (submit!))))
    ;; Robust cross-platform Enter handling. A KeyListener fires identically on
    ;; every platform; consuming the event stops the default action from also
    ;; running. Plain Enter (and Shift/Alt+Enter) insert a literal newline;
    ;; Ctrl+Enter submits.
    (.addKeyListener prompt
      (proxy [java.awt.event.KeyAdapter] []
        (keyPressed [e]
          (when (= KeyEvent/VK_ENTER (.getKeyCode e))
            (let [ctrl? (pos? (bit-and (.getModifiersEx e)
                                       java.awt.event.InputEvent/CTRL_DOWN_MASK))]
              (if ctrl?
                (do (.consume e)
                    (submit!))
                (do (.consume e)
                    (.replaceSelection prompt "\n"))))))))
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
    (.addActionListener view-html (reify java.awt.event.ActionListener
                                    (actionPerformed [_ _]
                                      (uiexport/show-transcript-html! frame pane))))
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
          toolbar (let [tb (doto (JToolBar.)
                             (.setFloatable false)
                             (.setRollover true)
                             (.setBorder nil))]
                    ;; Windows: FlatLaf transparent components paint as white —
                    ;; make the toolbar an opaque chat-background strip instead.
                    ;; darken-toolbar! also forces the toolbar's internal content
                    ;; panel + every child (buttons, fillers, labels) opaque so
                    ;; no transparent pixel can repaint as white.
                    (if (appearance/windows?)
                      (darken-toolbar! tb)
                      (doto tb (.setOpaque false)))
                    tb)]
      ;; left: operation buttons (icons + hover tooltips set by `toolbar-button`)
      (doseq [b [send stop term settings export view-html clear]]
        (.add toolbar b)
        (.add toolbar (Box/createHorizontalStrut 4)))
      ;; right: model / status / trust indicators
      (.add toolbar (Box/createHorizontalGlue))
      (.add toolbar (Box/createHorizontalStrut 14))
      ;; project — a single toolbar button showing the current project name;
      ;; clicking it opens the project manager.  (No "project:" prefix label.)
      (let [proj-btn (widgets/styled-button (or (projects/project-name) "default"))
            update-btn! (fn []
                          (.setText proj-btn
                                    (str (or (projects/project-name) "default") "  ")))
            switch-to! (fn [nm]
                         (when (and (seq nm) (not= nm (projects/project-name)))
                           (when @connected
                             (eca/disconnect!)
                             (reset! connected false))
                           (projects/set-project! nm)
                           ;; new project -> new stable chat identity
                           (reset! chat-id (projects/active-project-chat-id))
                           (transcript/clear! pane)
                           (transcript/append-status! pane (str "Project: " nm))
                           (.setTitle frame (str "grog — " nm))
                           (connect-eca!))
                         (update-btn!))
            open-project-manager! (fn []
                                    (show-project-manager! frame switch-to!))]
          (update-btn!)
          (.setToolTipText proj-btn "Projects")
          (.addActionListener proj-btn
            (proxy [java.awt.event.ActionListener] []
              (actionPerformed [_] (open-project-manager!))))
          (.add toolbar proj-btn)
          (.add toolbar (Box/createHorizontalStrut 14)))
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
      ;; Windows: re-assert opaque+dark on the toolbar and every child now that
      ;; all buttons/labels have been added — the LAF can lazily create its
      ;; internal content panel on first layout, so a second walk catches it.
      (when (appearance/windows?)
        (darken-toolbar! toolbar))
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
      ;; transcript: OPAQUE chat-background on every OS. The splash logo is painted
      ;; by the view itself (see transcript/paint-view!), so no transparency is
      ;; needed — and transparent viewports repaint as white under FlatLaf on
      ;; Windows.
      (darken-viewport! transcript-scroll)
      (doto pane
        (.setOpaque true)
        (.setBackground (chat-bg-color)))
      ;; toolbar opacity is set at creation (opaque chat-background on Windows);
      ;; only make it transparent where the parent repaint reliably shows through.
      (when-not (appearance/windows?)
        (transparent! toolbar))
      ;; Windows: kill LAF default component borders — they interpret the dark
      ;; theme differently and can appear as light seams. The transcript gets a
      ;; seamless empty border; the prompt box keeps a hairline dark divider.
      (when (appearance/windows?)
        (.setBorder transcript-scroll
                    (javax.swing.BorderFactory/createEmptyBorder))
        (.setBorder prompt-scroll
                    (javax.swing.BorderFactory/createLineBorder
                      (Color. 50 53 63))))
      (.setLayout root (BorderLayout.))
      (.add root transcript-scroll BorderLayout/CENTER)
      (let [south (JPanel. (BorderLayout.))
            opaque-south? (appearance/windows?)]
        (if opaque-south?
          (doto south
            (.setOpaque true)
            (.setBackground (chat-bg-color)))
          (transparent! south))
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
      (transcript/append-banner! pane snark))
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
          (catch Throwable _ nil))
        ;; Auto-connect ECA on startup (instead of lazily on the first send) so
        ;; the initialize/initialized handshake lands in grog-ui.log right
        ;; away — a failed connect is visible in the log before you type a
        ;; single message.
        (connect-eca!)))
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
        ;; load persisted appearance (fonts/colours/theme) from grog.edn, then
        ;; install the matching FlatLaf Look & Feel
        (appearance/load!)
        (case (appearance/theme)
          "flat-intelliJ" (com.formdev.flatlaf.FlatIntelliJLaf/setup)
          "flat-darcula"  (com.formdev.flatlaf.FlatDarculaLaf/setup)
          "flat-light"    (com.formdev.flatlaf.FlatLightLaf/setup)
          (com.formdev.flatlaf.FlatDarkLaf/setup))
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
        ;; Windows: FlatLaf scrollbars/toolbars/viewports can fall back to a
        ;; light track/background; pin every such key to the chat background (or
        ;; a dark thumb) so nothing scroll-related can repaint white. Harmless
        ;; on other platforms (never applied).
        (when (appearance/windows?)
          (let [cb (chat-bg-color)
                thumb (Color. 90 94 110)
                thumb-light (Color. 105 110 128)]
            (doseq [[k v] {"ScrollBar.background" cb
                           "ScrollBar.track" cb
                           "ScrollBar.trackHighlight" cb
                           "ScrollBar.thumb" thumb
                           "ScrollBar.thumbHighlight" thumb-light
                           "ScrollBar.thumbDarkShadow" thumb
                           "ScrollBar.foreground" thumb
                           "ScrollPane.background" cb
                           "Viewport.background" cb
                           "ToolBar.background" cb
                           "ToolBar.border" (javax.swing.BorderFactory/createEmptyBorder)}]
              (javax.swing.UIManager/put k v))))
        ;; enlarge the L&F's base UI fonts from the desktop's system font so
        ;; dialogs (settings, model picker, approvals) and labels read larger
        (widgets/scale-ui-fonts!)
        (projects/resolve-active-project)
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
                      " chat-bg-color=" (chat-bg-color)
                      " d3d=" (System/getProperty "sun.java2d.d3d")
                      " noddraw=" (System/getProperty "sun.java2d.noddraw"))
                (catch Throwable e (dbg! "diag err:" (.getMessage e))))
              ;; Windows-only audit: dump the real component tree (opacity +
              ;; background per component) and an offscreen render, so a
              ;; stubborn white pixel can be pinned to the exact component.
              (when (appearance/windows?)
                (try
                  (dbg! "--- chat frame component tree (Windows audit) ---")
                  (doseq [line (component-tree-lines f)]
                    (dbg! line))
                  (when-let [shot (snapshot-frame! f)]
                    (dbg! "offscreen screenshot:" shot))
                  (catch Throwable e (dbg! "frame audit err:" (.getMessage e)))))))
          f)
        (catch Throwable t
          (println "grog.ui failed:" t)))))
  nil)
