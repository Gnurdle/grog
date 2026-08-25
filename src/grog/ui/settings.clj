(ns grog.ui.settings
  "Modal 'settings' dialog for grog: a tabbed pane. The Appearance tab edits
  fonts/colours via the shared `grog.appearance` store and persists to grog.edn."
  (:require [clojure.string :as str]
            [grog.appearance :as appearance]
            [grog.config :as config]
            [grog.eca :as eca]
            [grog.models :as models]
            [grog.secrets :as secrets]
            [grog.ui.fonts :as uifonts]
            [grog.ui.footer :as uifooter]
            [grog.ui.modelpicker :as modelpicker]
            [grog.ui.widgets :as widgets])
  (:import (java.awt BorderLayout Color Dimension FlowLayout GraphicsEnvironment)
           (javax.swing Box BoxLayout DefaultListModel JButton JColorChooser JComboBox
                        JDialog JFrame JLabel JList JOptionPane JPanel JScrollPane JSpinner
                        JTabbedPane JTextField SpinnerNumberModel SwingUtilities)))

(defn- rgb->awt [[r g b]] (Color. (int r) (int g) (int b)))

(defn- stub-tab
  "A placeholder tab: a centered label."
  ^JPanel [text]
  (let [p (JPanel. (BorderLayout.))]
    (.setBorder p (javax.swing.BorderFactory/createEmptyBorder 30 20 30 20))
    (.add p (javax.swing.JLabel. text) BorderLayout/CENTER)
    p))

;; --- Appearance tab --------------------------------------------------------

(def ^:private label-w 170)

(defn- row-align!
  "Pad a row's leading label to a fixed width so all rows' controls line up."
  [^JPanel row ^JLabel l]
  (.setPreferredSize l (Dimension. label-w 24))
  row)

(defn- color-row
  "Label + a colour swatch + a 'Change…' button (styled like the main page)."
  ^JPanel [label ks]
  (let [row (JPanel. (FlowLayout. FlowLayout/LEFT))
        l (JLabel. label)
        sw (doto (JLabel.)
             (.setOpaque true)
             (.setBackground (rgb->awt (appearance/rgb ks)))
             (.setPreferredSize (Dimension. 30 22)))
        btn (widgets/styled-button "Change…")
        _ (row-align! row l)]
    (.addActionListener btn
      (reify java.awt.event.ActionListener
        (actionPerformed [_ _]
          (let [c (JColorChooser/showDialog btn (str label " color")
                                             (rgb->awt (appearance/rgb ks)))]
            (when c
              (appearance/set-rgb! ks [(.getRed c) (.getGreen c) (.getBlue c)])
              (.setBackground sw c)
              (uifonts/apply-all!))))))
    (.add row l)
    (.add row sw)
    (.add row btn)
    row))

(defn- font-size-row
  "A labeled spinner for an appearance font-size path."
  ^JPanel [label ks]
  (let [row (JPanel. (FlowLayout. FlowLayout/LEFT))
        l (JLabel. label)
        sp (JSpinner. (SpinnerNumberModel. (int (or (appearance/get-of ks nil) 18)) 8 48 1))
        _ (row-align! row l)]
    (.add row l)
    (.add row sp)
    (.addChangeListener sp
      (proxy [javax.swing.event.ChangeListener] []
        (stateChanged [_]
          (appearance/set-of! ks (int (.getValue sp)))
          (uifonts/apply-all!))))
    row))

(defn- font-family-row
  "A labeled combo box of system font families for an appearance font-family path."
  ^JPanel [label ks families]
  (let [row (JPanel. (FlowLayout. FlowLayout/LEFT))
        l (JLabel. label)
        cur (or (appearance/get-of ks nil) "Monospaced")
        cb (JComboBox. (into-array String families))
        _ (.setSelectedItem cb cur)
        _ (doto cb (.setPrototypeDisplayValue "XXXXXXXXXXXXXXXXXXXX"))
        _ (row-align! row l)]
    (.add row l)
    (.add row cb)
    (.addActionListener cb
      (reify java.awt.event.ActionListener
        (actionPerformed [_ _]
          (appearance/set-of! ks (str (.getSelectedItem cb)))
          (uifonts/apply-all!))))
    row))

(declare populate-appearance!)

(defn- defaults-button
  "A button that resets appearance — if `section` is given, only that subtree
  (e.g. :terminal) — to the built-in defaults, then rebuilds the tab.
  `repopulate` is a zero-arg fn that refreshes the panel's controls."
  ^JButton [section repopulate]
  (let [b (widgets/styled-button "Defaults")]
    (.addActionListener b
      (reify java.awt.event.ActionListener
        (actionPerformed [_ _]
          (if section
            (appearance/set-terminal-defaults!)
            (appearance/set-values! appearance/defaults))
          (uifonts/apply-all!)
          (repopulate))))
    b))

(def ^:private themes
  [{:id "flat-dark"      :label "Dark"      :laf com.formdev.flatlaf.FlatDarkLaf}
   {:id "flat-intelliJ"  :label "IntelliJ"  :laf com.formdev.flatlaf.FlatIntelliJLaf}
   {:id "flat-darcula"   :label "Darcula"   :laf com.formdev.flatlaf.FlatDarculaLaf}
   {:id "flat-light"     :label "Light"     :laf com.formdev.flatlaf.FlatLightLaf}])

(defn- apply-laf!
  "Install a FlatLaf theme, keep the prompt text-area dark (the chat renderer is
   always dark-paletted), re-scale UI fonts, refresh appearance and repaint every
   open window."
  [^Class laf]
  (javax.swing.UIManager/setLookAndFeel laf)
  (doseq [[k v] {"TextArea.background" (Color. 18 18 18)
                 "TextArea.foreground" (Color. 230 230 230)
                 "TextArea.caretForeground" (Color. 230 230 230)
                 "TextArea.inactiveBackground" (Color. 18 18 18)
                 "TextArea.inactiveForeground" (Color. 230 230 230)
                 "TextPane.background" (Color. 18 18 18)
                 "TextPane.foreground" (Color. 230 230 230)}]
    (javax.swing.UIManager/put k v))
  (widgets/scale-ui-fonts!)
  (uifonts/apply-all!)
  (doseq [^java.awt.Window w (java.awt.Window/getWindows)]
    (SwingUtilities/updateComponentTreeUI ^java.awt.Component w)))

(defn- theme-row
  "A labeled combo box of FlatLaf themes."
  ^JPanel []
  (let [row (JPanel. (FlowLayout. FlowLayout/LEFT))
        l (JLabel. "Theme")
        cur (appearance/theme)
        cb (JComboBox. (into-array String (map :label themes)))
        _ (when-let [t (some #(when (= cur (:id %)) %) themes)]
            (.setSelectedItem cb (:label t)))
        _ (doto cb (.setPrototypeDisplayValue "XXXXXXXXXXXX"))
        _ (row-align! row l)]
    (.add row l)
    (.add row cb)
    (.addActionListener cb
      (reify java.awt.event.ActionListener
        (actionPerformed [_ _]
          (when-let [t (some #(when (= (str (.getSelectedItem cb)) (:label %)) %) themes)]
            (appearance/set-theme! (:id t))
            (apply-laf! (:laf t))))))
    row))

(defn- populate-appearance!
  "Fill the appearance tab panel (a vertical box) with the theme + chat controls."
  [^JPanel panel families]
  (.removeAll panel)
  (doseq [r [(theme-row)
             (font-size-row     "Chat font size"   [:chat :font-size])
             (font-family-row   "Chat font"        [:chat :font-family] families)
             (color-row "User text"          [:chat :user])
             (color-row "Thinking"           [:chat :thinking])
             (color-row "Answer"             [:chat :answer])
             (color-row "Tool call"          [:chat :tool-call])
             (color-row "Chat background"    [:chat :background])]]
    (.add panel r)
    (.add panel (Box/createVerticalStrut 6)))
  (.add panel (defaults-button nil #(populate-appearance! panel families)))
  (.revalidate panel)
  (.repaint panel))

(defn- build-appearance-tab
  "Chat fonts + colours, in a scrollable vertical panel."
  ^JPanel []
  (let [families (vec (.getAvailableFontFamilyNames
                       (GraphicsEnvironment/getLocalGraphicsEnvironment)))
        vbox (JPanel.)
        _ (.setLayout vbox (BoxLayout. vbox BoxLayout/Y_AXIS))
        _ (.setBorder vbox (javax.swing.BorderFactory/createEmptyBorder 12 12 12 12))
        _ (populate-appearance! vbox families)
        outer (doto (JPanel. (BorderLayout.))
                (.setOpaque false)
                (.add (JScrollPane. vbox) BorderLayout/CENTER))]
    outer))

;; --- Terminal tab ----------------------------------------------------------

(defn- populate-terminal!
  "Fill the terminal tab panel (a vertical box) with font/colour controls."
  [^JPanel panel families]
  (.removeAll panel)
  (doseq [r [(font-family-row   "Terminal font"    [:terminal :font-family] families)
             (font-size-row     "Font size"        [:terminal :font-size])
             (color-row "Terminal text"    [:terminal :foreground])
             (color-row "Background"       [:terminal :background])]]
    (.add panel r)
    (.add panel (Box/createVerticalStrut 6)))
  (.add panel (defaults-button :terminal #(populate-terminal! panel families)))
  (.revalidate panel)
  (.repaint panel))

(defn- build-terminal-tab
  "Terminal font, text colour and background, in a scrollable vertical panel."
  ^JPanel []
  (let [families (vec (.getAvailableFontFamilyNames
                       (GraphicsEnvironment/getLocalGraphicsEnvironment)))
        vbox (JPanel.)
        _ (.setLayout vbox (BoxLayout. vbox BoxLayout/Y_AXIS))
        _ (.setBorder vbox (javax.swing.BorderFactory/createEmptyBorder 12 12 12 12))
        _ (populate-terminal! vbox families)
        outer (doto (JPanel. (BorderLayout.))
                (.setOpaque false)
                (.add (JScrollPane. vbox) BorderLayout/CENTER))]
    outer))

;; --- Models tab ------------------------------------------------------------

(defn- apply-eca-model!
  "Apply a newly chosen model to the whole GUI: update the session model (so
  future `chat/prompt` calls use it), persist it as `:eca :model`, refresh the
  config and footer, and notify the running ECA child to switch — so the change
  shows up as `chat/selectedModelChanged` traffic in the debug log."
  [m]
  (let [id (models/qualify-eca-model m
                                     nil
                                     (try (config/llm-url) (catch Exception _ nil)))]
    (when id
      (uifooter/set-model-ref! id)
      (models/save-eca-model! id)
      (config/reload!)
      (uifooter/set-model! id)
      (when (eca/connected?)
        (eca/selected-model! id)))
    id))

(defn- to-eca-id
  "Translate a picked model (from the picker: {:model … :source …}) into the
  provider-qualified id ECA understands (e.g. `openrouter/…`, `ollama/…`). The
  picker returns raw transport ids (no prefix), and ECA resolves models by
  `provider/name` — so we map the source onto the prefix here."
  [{:keys [model source]}]
  (models/qualify-eca-model model
                            source
                            (try (config/llm-url) (catch Exception _ nil))))

(defn- field-row
  "A labeled text field row."
  ^JPanel [label ^JTextField tf]
  (let [row (JPanel. (FlowLayout. FlowLayout/LEFT))
        l (JLabel. label)]
    (.setPreferredSize l (Dimension. (int label-w) 24))
    (.setPreferredSize tf (Dimension. 320 28))
    (.add row l)
    (.add row tf)
    row))

(defn- spinner-row
  "A labeled spinner row."
  ^JPanel [label ^JSpinner sp]
  (let [row (JPanel. (FlowLayout. FlowLayout/LEFT))
        l (JLabel. label)]
    (.setPreferredSize l (Dimension. (int label-w) 24))
    (.add row l)
    (.add row sp)
    row))

(defn- build-models-tab
  "Default provider + a fully-editable list of named models (profiles)."
  ^JPanel []
  (let [llm (models/llm-config)
        model-tf (JTextField. (str (or (:model llm) "")))
        url-tf   (JTextField. (str (or (:url llm) "")))
        max-sp   (JSpinner. (SpinnerNumberModel. (int (or (:max-tokens llm) 0)) 0 131072 256))
        temp-sp  (JSpinner. (SpinnerNumberModel. (double (or (:temperature llm) 0.7)) 0.0 2.0 0.1))
        key-btn  (widgets/styled-button "Set default API key…")
        key-lbl  (JLabel. (if (secrets/get-secret "LLM_API_KEY")
                            "default key: in OS keyring"
                            "default key: not set"))
        apply-btn (widgets/styled-button "Apply defaults")
        pick-btn  (widgets/styled-button "Pick model…")
        ;; profiles
        list-model (DefaultListModel.)
        _ (doseq [n (models/profile-names)] (.addElement list-model n))
        profile-list (JList. list-model)
        p-name-lbl (JLabel. "(select a profile to edit)")
        p-model (JTextField.)
        p-url   (JTextField.)
        p-key   (JTextField.)
        save-prof (widgets/styled-button "Save profile")
        add-prof  (widgets/styled-button "Add profile…")
        rm-prof   (widgets/styled-button "Remove profile")
        vbox (JPanel.)
        _ (.setLayout vbox (BoxLayout. vbox BoxLayout/Y_AXIS))
        _ (.setBorder vbox (javax.swing.BorderFactory/createEmptyBorder 12 12 12 12))]

    ;; default provider
    (doseq [row [(field-row "Default model" model-tf)
                 (field-row "Provider URL" url-tf)
                 (spinner-row "Max tokens" max-sp)
                 (spinner-row "Temperature" temp-sp)]]
      (.add vbox row)
      (.add vbox (Box/createVerticalStrut 6)))
    (let [kr (JPanel. (FlowLayout. FlowLayout/LEFT))
          l (JLabel. "API key")]
      (.setPreferredSize l (Dimension. (int label-w) 24))
      (.add kr l) (.add kr key-btn) (.add kr key-lbl)
      (.add vbox kr))
    (.add vbox (Box/createVerticalStrut 6))
    (.add vbox (doto (JPanel. (FlowLayout. FlowLayout/LEFT))
                 (.add apply-btn)
                 (.add pick-btn)))
    (.add vbox (Box/createVerticalStrut 12))

    (.add vbox (Box/createVerticalStrut 12))

    ;; profiles
    (.add vbox (doto (JLabel. "Models (named profiles)")
                 (.setPreferredSize (Dimension. 240 24))))
    (.add vbox (JScrollPane. profile-list))
    (.add vbox (doto p-name-lbl (.setPreferredSize (Dimension. 300 22))))
    (doseq [row [(field-row "Profile model" p-model)
                 (field-row "Profile URL" p-url)
                 (field-row "Profile API key" p-key)]]
      (.add vbox row)
      (.add vbox (Box/createVerticalStrut 4)))
    (let [btnrow (JPanel. (FlowLayout. FlowLayout/LEFT))]
      (.add btnrow save-prof)
      (.add btnrow add-prof)
      (.add btnrow rm-prof)
      (.add vbox btnrow))

    ;; selecting a profile loads its values into the edit fields
    (let [sel (proxy [javax.swing.event.ListSelectionListener] []
                (valueChanged [e]
                  (let [n (.getSelectedValue profile-list)
                        prof (when n (get-in (models/llm-config) [:profiles (keyword (str n))]))]
                    (.setText p-name-lbl (str (or n "nothing selected")))
                    (.setText p-model (str (or (:model prof) "")))
                    (.setText p-url (str (or (:url prof) "")))
                    (.setText p-key (str (or (:api-key prof) ""))))))]
      (.addListSelectionListener profile-list sel))

    ;; actions
    (.addActionListener key-btn
      (reify java.awt.event.ActionListener
        (actionPerformed [_ _]
          (let [in (JOptionPane/showInputDialog key-btn "Paste the default API key:"
                                                "Set API key" JOptionPane/QUESTION_MESSAGE)]
            (when (and in (seq (str/trim in)))
              (secrets/set-secret! "LLM_API_KEY" (str/trim in))
              (.setText key-lbl "default key: in OS keyring"))))))
    (.addActionListener apply-btn
      (reify java.awt.event.ActionListener
        (actionPerformed [_ _]
          ;; "Apply defaults" edits the CLI :llm default provider only — the
          ;; value may be a bare local id that ECA cannot resolve, so it must
          ;; NOT be pushed into the ECA model.
          (models/save-fields! {:model (.getText model-tf)
                                :url (.getText url-tf)
                                :max-tokens (int (.getValue max-sp))
                                :temperature (double (.getValue temp-sp))})
          (config/clear-llm-override!)
          (config/reload!))))
    (.addActionListener pick-btn
      (reify java.awt.event.ActionListener
        (actionPerformed [_ _]
          (let [owner (SwingUtilities/getWindowAncestor pick-btn)
                r (modelpicker/show-picker! owner (.getText model-tf))]
            (when r
              (let [eca-id (to-eca-id r)]
                (.setText model-tf (:model r))
                (.setText url-tf (:url r))
                (models/save-fields! (merge {:model (:model r)} (when (:url r) {:url (:url r)})))
                (config/clear-llm-override!)
                (apply-eca-model! eca-id)))))))
    (.addActionListener save-prof
      (reify java.awt.event.ActionListener
        (actionPerformed [_ _]
          (let [n (.getSelectedValue profile-list)]
            (when n
              (models/save-profile! (str n) {:model (.getText p-model)
                                             :url (.getText p-url)
                                             :api-key (.getText p-key)}))))))
    (.addActionListener add-prof
      (reify java.awt.event.ActionListener
        (actionPerformed [_ _]
          (let [nm (JOptionPane/showInputDialog add-prof "Profile name:"
                                                "Add model" JOptionPane/QUESTION_MESSAGE)]
            (when (and nm (seq (str/trim nm)))
              (models/save-profile! (str/trim nm) {:model (.getText p-model)
                                                   :url (.getText p-url)
                                                   :api-key (.getText p-key)})
              (.addElement list-model (str/trim nm)))))))
    (.addActionListener rm-prof
      (reify java.awt.event.ActionListener
        (actionPerformed [_ _]
          (let [sel (.getSelectedValue profile-list)]
            (when sel
              (models/remove-profile! (str sel))
              (.removeElement list-model sel))))))

    (doto (JPanel. (BorderLayout.))
      (.setOpaque false)
      (.add (JScrollPane. vbox) BorderLayout/CENTER))))

;; --- dialog ----------------------------------------------------------------

(defn show-settings!
  "Open a modal tabbed settings dialog owned by `owner` (a JFrame)."
  [^JFrame owner]
  (let [dlg (JDialog. ^JFrame owner "grog settings" true)
        tabs (JTabbedPane.)
        close-btn (widgets/styled-button "Close")
        bottom (JPanel. (FlowLayout. FlowLayout/RIGHT))]
    (.addTab tabs "General" (stub-tab "General settings — coming soon."))
    (.addTab tabs "Appearance" (build-appearance-tab))
    (.addTab tabs "Models" (build-models-tab))
    (.addTab tabs "Terminal" (build-terminal-tab))
    (.addTab tabs "About" (stub-tab "grog — an AI chat plus terminal."))
    (.addActionListener close-btn
      (reify java.awt.event.ActionListener
        (actionPerformed [_ _] (.dispose dlg))))
    (.add bottom close-btn)
    (.setLayout dlg (BorderLayout.))
    (.add dlg tabs BorderLayout/CENTER)
    (.add dlg bottom BorderLayout/SOUTH)
    (.setSize dlg 620 520)
    (.setLocationRelativeTo dlg owner)
    (.setVisible dlg true)
    dlg))
