(ns grog.ui.dnd
  "Shared drag-and-drop for grog GUI text panes.

  Install on any JTextComponent (chat prompt, chat output, shell input, shell
  output). Selected text can be dragged OUT of any pane and dropped INTO any
  editable pane; drops insert at the drop-point caret. Works across both
  windows because the flavor is shared.

  Note: `JTextComponent/setDragEnabled` throws on non-editable components, so
  non-editable panes (output) get a manual mouse-drag gesture."
  (:import (java.awt Point)
           (java.awt.datatransfer DataFlavor StringSelection)
           (javax.swing JComponent TransferHandler)
           (javax.swing.event MouseInputAdapter)
           (javax.swing.text JTextComponent)))

(def ^:private string-flavor DataFlavor/stringFlavor)

(defn- can-import-transferable?
  "True if `transferable` carries a string flavor."
  [^java.awt.datatransfer.Transferable transferable]
  (boolean (some-> transferable (.isDataFlavorSupported string-flavor))))

(defn- drop-insert!
  "Insert dragged text into an editable JTextComponent at the drop point (or
  caret). `support` is a TransferSupport (may be nil for the legacy 2-arg path)."
  [^JTextComponent tc transferable support]
  (if (and (instance? JTextComponent tc)
           (.isEditable tc)
           (can-import-transferable? transferable))
    (try
      (let [text (str (.getTransferData transferable string-flavor))
            pt (when support (try (.getDropPoint (.getDropLocation support))
                                  (catch Throwable _ nil)))
            offset (if pt (.viewToModel2D tc pt) (.getCaretPosition tc))]
        (.setCaretPosition tc (int offset))
        (.replaceSelection tc text)
        true)
      (catch Exception _ false))
    false))

(defn- make-transfer-handler
  "COPY export of the selected text + drop import (string flavor) into editable
  components at the drop position."
  []
  (proxy [TransferHandler] []
    (getSourceActions [^JComponent c] TransferHandler/COPY)
    (createTransferable [^JComponent c]
      (StringSelection. (str (.getSelectedText ^JTextComponent c))))
    (canImport [support]
      (and (can-import-transferable? (.getTransferable support))
           (instance? JTextComponent (.getComponent support))
           (.isEditable ^JTextComponent (.getComponent support))))
    (importData
      ;; modern single-arg API: TransferSupport
      ([support]
       (drop-insert! (.getComponent support) (.getTransferable support) support))
      ;; legacy two-arg API: (JComponent, Transferable)
      ([comp transferable]
       (drop-insert! ^JTextComponent comp transferable nil)))))

(defn- install-manual-drag!
  "Start an export-drag from a non-editable component when the mouse moves more
  than a small threshold with a button pressed (standard Swing custom DnD)."
  [^JTextComponent c]
  (let [drag-source (atom nil)]
    (.addMouseListener
      c
      (proxy [MouseInputAdapter] []
        (mousePressed [e]
          ;; Only start a potential drag-out if the press lands inside an
          ;; existing selection; otherwise let the drag do normal text
          ;; selection/copy.
          (.requestFocusInWindow c)
          (let [pt (.getPoint e)
                off (try (long (.viewToModel2D ^javax.swing.text.JTextComponent c pt))
                         (catch Throwable _ -1))
                ss (.getSelectionStart c)
                se (.getSelectionEnd c)]
            (reset! drag-source
              (when (and (>= off 0) (>= off (long ss)) (<= off (long se))
                         (pos? (- (long se) (long ss))))
                {:comp c :pt pt}))))
        (mouseReleased [e]
          (reset! drag-source nil))))
    (.addMouseMotionListener
      c
      (proxy [MouseInputAdapter] []
        (mouseDragged [e]
          (when-let [{:keys [comp pt]} @drag-source]
            (let [p (.getPoint e)]
              (when (or (> (Math/abs (long (- (.x p) (.x ^Point pt)))) 4)
                        (> (Math/abs (long (- (.y p) (.y ^Point pt)))) 4))
                (reset! drag-source nil)
                (.exportAsDrag (.getTransferHandler comp) comp e TransferHandler/COPY)))))))))

(defn install!
  "Enable dragging selected text out of `c` and dropping text onto it."
  [^JTextComponent c]
  (let [th (make-transfer-handler)]
    (.setTransferHandler c th)
    (if (.isEditable c)
      (.setDragEnabled c true)
      (install-manual-drag! c)))
  c)
