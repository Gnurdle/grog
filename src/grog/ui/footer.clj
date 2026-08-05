(ns grog.ui.footer
  "Shared mutable reference to the main panel's footer model label, so the model
  picker (in the settings dialog) can update the live display in the chat window."
  (:import (javax.swing JLabel)))

(defonce label-ref (atom nil))

(defn register-label!
  "Record the chat window's footer model JLabel."
  [^JLabel l]
  (reset! label-ref l)
  l)

(defn set-model!
  "Update the footer model label text (no-op if not yet registered)."
  [s]
  (when-let [l @label-ref]
    (.setText l (str s))))
