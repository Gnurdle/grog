(ns grog.ui.modelpicker
  "A two-level, searchable model chooser: first pick a *transport* (OpenRouter,
  local Ollama, …), then pick from that transport's models — filterable by a
  partial-match search string. Returns {:model id :url transport-url} or nil."
  (:require [clojure.string :as str]
            [grog.models :as models]
            [grog.ui.widgets :as widgets])
  (:import (java.awt BorderLayout Dimension FlowLayout)
           (javax.swing DefaultComboBoxModel DefaultListCellRenderer DefaultListModel JComboBox
                        JDialog JLabel JList JPanel JScrollPane JTextField SwingUtilities)
           (javax.swing.event DocumentListener)))

(def transports
  "Available transports (label + OpenAI-compatible endpoint URL + fetcher)."
  [{:id :openrouter :label "OpenRouter"
    :url "https://openrouter.ai/api/v1" :fetch models/fetch-openrouter-models}
   {:id :ollama :label "Local Ollama"
    :url "http://localhost:11434/v1" :fetch models/fetch-ollama-models}])

(defn- label-renderer
  "For JComboBox/JList whose elements are {:label ...} maps."
  []
  (proxy [DefaultListCellRenderer] []
    (getListCellRendererComponent [l v i s f]
      (proxy-super getListCellRendererComponent l (if (map? v) (:label v) v) i s f))))

(defn- model-renderer
  "For the model JList whose elements are {:model ... :source ...} maps."
  []
  (proxy [DefaultListCellRenderer] []
    (getListCellRendererComponent [l v i s f]
      (proxy-super getListCellRendererComponent l
        (cond
          (map? v) (str (:model v))
          (some? v) (str v)
          :else "")
        i s f))))

(defn show-picker!
  "Modal two-level model chooser. Returns {:model String :url String} or nil."
  [^java.awt.Window owner current]
  (let [dlg (JDialog. owner "Choose a model" true)
        transport-combo (JComboBox. (DefaultComboBoxModel. (into-array Object transports)))
        _ (.setRenderer transport-combo (label-renderer))
        current-models (atom [])
        search (JTextField. "")
        list (JList. (DefaultListModel.))
        _ (.setCellRenderer list (model-renderer))
        status (JLabel. "")
        ok (widgets/styled-button "Use model")
        cancel (widgets/styled-button "Cancel")
        refresh (widgets/styled-button "Refresh")
        result (atom nil)]
    (letfn [(repopulate! []
              (let [term (.getText search)
                    filtered (if (str/blank? term)
                               @current-models
                               (filterv #(str/includes? (str/lower-case (:model %))
                                                        (str/lower-case term))
                                        @current-models))
                    lm (DefaultListModel.)]
                (doseq [e filtered] (.addElement lm e))
                (.setModel list lm)))
            (do-load! [t]
              (when t
                (.setText status (str "Loading " (:label t) "…"))
                (.start (Thread.
                          (fn []
                            (let [raw (try ((:fetch t)) (catch Throwable _ []))
                                  src (some-> t :id name)
                                  ms  (mapv (fn [x] (if (map? x)
                                                       (assoc x :source src)
                                                       {:model (str x) :source src}))
                                            raw)]
                              (SwingUtilities/invokeLater
                                (fn []
                                  (reset! current-models ms)
                                  (repopulate!)
                                  (.setText status
                                            (if (seq ms)
                                              (str (count ms) " models from " (:label t))
                                              (str "No models from " (:label t) " — is it reachable?")))))))))))]
      ;; transport selection -> load that transport's models
      (.addActionListener transport-combo
        (proxy [java.awt.event.ActionListener] []
          (actionPerformed [_] (do-load! (.getSelectedItem transport-combo)))))
      (.addActionListener refresh
        (proxy [java.awt.event.ActionListener] []
          (actionPerformed [_] (do-load! (.getSelectedItem transport-combo)))))
      ;; default transport = OpenRouter
      (.setSelectedItem transport-combo (first transports))
      (do-load! (.getSelectedItem transport-combo))
      ;; live partial-match filter
      (.addDocumentListener (.getDocument search)
        (proxy [DocumentListener] []
          (insertUpdate [_] (repopulate!))
          (removeUpdate [_] (repopulate!))
          (changedUpdate [_] (repopulate!))))
      ;; choose / cancel
      (.addActionListener ok
        (proxy [java.awt.event.ActionListener] []
          (actionPerformed [_]
            (let [t (.getSelectedItem transport-combo)
                  sel (.getSelectedValue list)
                  m (if (map? sel) (:model sel)
                        (let [t2 (str/trim (.getText search))]
                          (when (seq t2) t2)))]
              (when (seq (str m))
                (reset! result {:model (str m)
                                :url (:url t)
                                :source (when (map? sel) (:source sel))}))
              (.dispose dlg)))))
      (.addActionListener cancel
        (proxy [java.awt.event.ActionListener] []
          (actionPerformed [_] (.dispose dlg))))
      ;; layout
      (.setLayout dlg (BorderLayout.))
      (let [searchbar (JPanel. (FlowLayout. FlowLayout/LEFT))]
        (.add searchbar (JLabel. "Search:"))
        (.add searchbar search)
        (.add searchbar refresh)
        (.setPreferredSize search (Dimension. 320 28))
        (.add dlg searchbar BorderLayout/NORTH))
      (let [center (JPanel. (BorderLayout.))
            top (JPanel. (BorderLayout.))
            bar (JPanel. (FlowLayout. FlowLayout/LEFT))]
        (.add bar (JLabel. "Transport:"))
        (.add bar transport-combo)
        (.add top bar BorderLayout/NORTH)
        (.add top status BorderLayout/SOUTH)
        (.add center top BorderLayout/NORTH)
        (.add center (JScrollPane. list) BorderLayout/CENTER)
        (.add dlg center BorderLayout/CENTER))
      (let [south (JPanel. (FlowLayout. FlowLayout/RIGHT))]
        (.add south cancel)
        (.add south ok)
        (.add dlg south BorderLayout/SOUTH))
      (.setSize dlg 560 460)
      (.setLocationRelativeTo dlg owner)
      (.setVisible dlg true)
      @result)))
