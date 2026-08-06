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
            [grog.chat-context :as chat-ctx]
            [grog.config :as config]
            [grog.core :as core]
            [grog.appearance :as appearance]
            [grog.pager :as pager]
            [grog.ui.cancel :as cancel]
            [grog.ui.dnd :as dnd]
            [grog.ui.export :as uiexport]
            [grog.ui.fonts :as uifonts]
            [grog.ui.footer :as uifooter]
            [grog.ui.settings :as uisettings]
            [grog.ui.shell :as uishell]
            [grog.ui.transcript :as transcript]
            [grog.ui.widgets :as widgets]
            [grog.soul :as soul])
  (:import (java.awt Color Component Dimension Font Graphics Image Toolkit BorderLayout FlowLayout Point)
           (javax.imageio ImageIO)
           (javax.swing AbstractAction JButton JComponent JFrame JLabel JMenuItem JPanel
                        JPopupMenu JScrollPane JTextArea JTextPane KeyStroke SwingUtilities)
           (java.util.concurrent LinkedBlockingQueue)
           (java.awt.datatransfer StringSelection)
           (java.awt.event KeyEvent MouseAdapter)))

;; ANSI styling mirrors grog.core for plain mode (parsed by transcript writer).
(def ^:private ansi-answer "\u001B[38;2;100;220;255m")
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

;; Larger fonts (user preference).
(def ^:private ui-monospace-font (Font. "Monospaced" Font/PLAIN 18))
(def ^:private ui-ui-font (Font. "SansSerif" Font/PLAIN 17))

;; ---------------------------------------------------------------------------
;; Background logo
;; ---------------------------------------------------------------------------

(defonce ^:private logo-image
  (delay (try (ImageIO/read (io/file "logo.jpg"))
              (catch Throwable _ nil))))

(def ^:private jar-path (io/file "icon.png"))

;; Window / menu icon: prefers `icon.png` (repo root), falls back to the logo
;; so the window decoration never shows the default Java icon.
(defonce ^:private frame-icon-image
  (delay (or (try (ImageIO/read jar-path) (catch Throwable _ nil))
             @logo-image)))

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

(defn- with-copy-popup!
  "Add a right-click 'Copy' menu to a text component (copies the selection)."
  [^javax.swing.text.JTextComponent c]
  (let [item (JMenuItem. "Copy")]
    (.addActionListener item
      (reify java.awt.event.ActionListener
        (actionPerformed [_ _]
          (let [sel (.getSelectedText c)]
            (when (seq sel)
              (.setContents (.getSystemClipboard (Toolkit/getDefaultToolkit))
                            (StringSelection. sel)
                            nil))))))
    (let [menu (JPopupMenu.)]
      (.add menu item)
      (.addMouseListener c
        (proxy [MouseAdapter] []
          (mousePressed [e]
            (when (.isPopupTrigger e) (.show menu c (.getX e) (.getY e))))
          (mouseReleased [e]
            (when (.isPopupTrigger e) (.show menu c (.getX e) (.getY e))))))))
  c)

;; ---------------------------------------------------------------------------
;; Chat
;; ---------------------------------------------------------------------------

(defn- do-llm-turn!
  "Run one user turn through the LLM+tool loop, streaming into `pane`, and
  return the updated history. Runs on the worker thread."
  [^JTextPane pane history text]
  (dbg! "handle-turn start text=" text)
  (dbg! "llm url=" (config/llm-url) " model=" (config/model)
        " has-key=" (boolean (config/llm-api-key)))
  (try
    (let [recent (chat-ctx/recent-history-for-cap history (config/chat-history-turns))
          msgs (conj (chat-ctx/history->messages (chat-ctx/system-messages) recent)
                     {:role "user" :content text})]
      (dbg! "messages built, count=" (count msgs))
      (dbg! "calling chat round...")
        (let [result (core/run-tool-loop-on-messages msgs
                       :answer-prefix "\n"
                       :cancel-state (cancel/cancel-state))]
          (dbg! "chat round returned ok=" (:ok result)
                " err=" (str/trim (str (:error result)))
                " thinking?=" (boolean (str/trim (str (:thinking result))))
                " content=" (when-let [c (:content result)] (subs (str c) 0 (min 80 (count (str c))))))
          (if (:ok result)
            (let [content (str (:content result) "")
                  thinking (str (:thinking result) "")]
              (when (and (config/chat-show-thinking?) (seq thinking)
                         (not (:live-thinking-printed? result)))
                (println (str "── thinking ──\n" thinking "\n")))
              (when-not (:live-content-printed? result)
                (when (seq (str/trim content))
                  (pager/emit-final-reply!
                    {:answer-prefix "\n"
                     :raw-content content
                     :ansi-answer (appearance/ansi-answer)
                     :ansi-reset ansi-reset})))
              (conj history {:user text :assistant content}))
            (do (binding [*out* *err*]
                  (println (str "[grog] " (:error result))))
                history))))
      (catch Exception e
        (dbg! "exception in handle-turn:" (.getMessage e))
        (binding [*out* *err*]
          (println (str "[grog] error: " (.getMessage e))))
        history)))

(defn- handle-turn!
  "Route slash commands, or run an LLM turn. Returns the updated history."
  [^JTextPane pane history text]
  (binding [*out* (transcript/styled-writer pane)
            *err* (transcript/styled-writer pane)]
    (cancel/clear!)
    ;; echo the user's input (prompt or command) into the transcript in dark yellow
    (when (seq (str/trim text))
      (println (str "\n" (appearance/ansi-user) text ansi-reset)))
    (case (core/route-slash-command! text)
      :grog.core/quit
      (do (System/exit 0) history)
      :grog.core/clear
      (do
        ;; empty the visible transcript as well as the chat history
        (let [^javax.swing.text.StyledDocument doc (.getStyledDocument pane)
              len (.getLength doc)]
          (when (pos? len) (.remove doc 0 len)))
        (println "History cleared.")
        [])
      :grog.core/handled
      history
      :grog.core/llm
      (do-llm-turn! pane history text))))

(defn- chat-worker!
  "Process the input queue on a background thread."
  [^JTextPane pane ^LinkedBlockingQueue queue running? history-ref]
  (Thread.
    (fn []
      (loop []
        (when-let [text (.take queue)]
          (reset! running? true)
          (reset! history-ref (handle-turn! pane @history-ref text))
          (reset! running? false)
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
  [^JTextPane pane ^JScrollPane scroll-pane frame]
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
        scroll-to-bottom! (fn []
                            (let [vp (.getViewport scroll-pane)
                                  h (.getHeight (.getView vp))
                                  vh (.getHeight vp)]
                              (.setViewPosition vp (Point. 0 (max 0 (- h vh))))))
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
              (actionPerformed [_] (scroll-to-bottom!))))
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
              (actionPerformed [_] (scroll-to-bottom!))))
      (.put am "grog-cscroll-top"
            (proxy [AbstractAction] []
              (actionPerformed [_] (scroll-to-top!)))))))

(defn- build-chat-frame
  "Build the chat frame (not yet shown). Returns the JFrame."
  ^JFrame []
  (let [pane (doto (JTextPane.) (.setEditable false))
        transcript-scroll (JScrollPane. pane)
        prompt (JTextArea. 4 84)
        prompt-scroll (JScrollPane. prompt)
        send (widgets/styled-button "Send")
        stop (widgets/styled-button "Stop")
        term (widgets/styled-button "Terminal")
        settings (widgets/styled-button "Settings")
        export (widgets/styled-button "Export")
        frame (JFrame. "grog")
        queue (LinkedBlockingQueue.)
        running? (atom false)
        history-ref (atom [])
        bg (background-panel)
        sent? (atom false)   ; first real message flips the logo to subdued
        submit! (fn []
                  (when-not @running?
                    (let [t (.getText ^JTextArea prompt)]
                      (when (seq (str/trim t))
                        (.setText ^JTextArea prompt "")
                        (when (compare-and-set! sent? false true)
                          ((:subdue! bg) true))
                        (.put ^LinkedBlockingQueue queue t)))))]
    ;; larger fonts
    (doto pane
      (.setFont ui-monospace-font)
      ;; visible selection (over the dark/logo background) + white selected text
      (.setSelectionColor (Color. 55 90 150))
      (.setSelectedTextColor Color/WHITE)
      (.setCaretColor (Color. 210 220 210)))
    (doto prompt (.setFont ui-monospace-font))
    (doseq [b [send stop term]] (.setFont b ui-ui-font))    ;; register chat panes for shared Ctrl+Shift+Plus/Minus zoom
    (uifonts/register-chat! pane :transcript)
    (uifonts/register-chat! prompt :prompt)
    ;; drag-and-drop on every text pane
    (dnd/install! pane)
    (dnd/install! prompt)
    ;; right-click Copy on the transcript and prompt
    (with-copy-popup! pane)
    (with-copy-popup! prompt)
    ;; single-line scroll keys on the transcript
    (install-transcript-scroll-keys! pane transcript-scroll frame)
    ;; Send: button + Ctrl+Enter
    (.addActionListener send (reify java.awt.event.ActionListener
                               (actionPerformed [_ _] (submit!))))
    ;; Enter inserts a literal newline (JTextArea default, so the prompt is
    ;; freely editable/multiline); Shift+Enter submits the prompt.
    (.put (.getInputMap prompt javax.swing.JComponent/WHEN_FOCUSED)
          (KeyStroke/getKeyStroke KeyEvent/VK_ENTER (java.awt.event.InputEvent/SHIFT_DOWN_MASK))
          "grog-submit")
    (.put (.getActionMap prompt) "grog-submit"
          (proxy [AbstractAction] []
            (actionPerformed [e] (submit!))))
    ;; Stop -> cancellation registry; Terminal -> open the shell window
    (.addActionListener stop (reify java.awt.event.ActionListener
                               (actionPerformed [_ _] (cancel/cancel!))))
    (.addActionListener term (reify java.awt.event.ActionListener
                               (actionPerformed [_ _] (show-shell!))))
    (.addActionListener settings (reify java.awt.event.ActionListener
                                   (actionPerformed [_ _]
                                     (uisettings/show-settings! frame))))
    (.addActionListener export (reify java.awt.event.ActionListener
                                 (actionPerformed [_ _]
                                   (uiexport/save-transcript! frame pane))))
    ;; start worker
    (.start (chat-worker! pane queue running? history-ref))
    ;; layout over the logo background
    (let [root (:panel bg)
          button-row (JPanel. (FlowLayout. FlowLayout/LEFT))]
      (.add button-row send)
      (.add button-row stop)
      (.add button-row term)
      (.add button-row settings)
      (.add button-row export)
      ;; active-model indicator in the lower corner
      (.add button-row (uifooter/register-label!
                          (JLabel. (str "model: " (config/model)))))
      ;; readable prompt box: dark + light text; surrounding panes transparent
      (doto prompt
        (.setOpaque true)
        (.setBackground (Color. 18 18 18))
        (.setForeground (Color. 230 230 230))
        (.setCaretColor (Color. 230 230 230)))
      (doto prompt-scroll
        (make-viewport-transparent!))
      ;; transcript transparent so the logo shows behind the conversation
      (make-viewport-transparent! transcript-scroll)
      (transparent! pane)
      (transparent! button-row)
      (.setLayout root (BorderLayout.))
      (.add root transcript-scroll BorderLayout/CENTER)
      (let [south (JPanel. (BorderLayout.))]
        (transparent! south)
        (.add south prompt-scroll BorderLayout/CENTER)
        (.add south button-row BorderLayout/SOUTH)
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
    (.setSize frame 900 680)
    (.setLocationRelativeTo frame nil)
    (set-frame-icon! frame)))

(defn -main
  "Entry point: `clojure -M -m grog.ui`, `./grog-ui`, or `grog-ui.bat`."
  [& _]
  (SwingUtilities/invokeLater
    (fn []
      (try
        ;; apply the modern dark Look & Feel across both windows
        (com.formdev.flatlaf.FlatDarkLaf/setup)
        ;; load persisted appearance (fonts/colours) from grog.edn
        (appearance/load!)
        (.setVisible (build-chat-frame) true)
        (catch Throwable t
          (println "grog.ui failed:" t)))))
  nil)
