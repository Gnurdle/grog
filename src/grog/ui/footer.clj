(ns grog.ui.footer
  "Shared mutable references to the main panel's footer indicators (model name,
  ECA chat status, and trust/yolo mode), so the settings dialog and the ECA
  event handler can update the live display in the chat window without threading
  them through every call site.

  The status and trust indicators are *icons* (coloured dots) with tooltips,
  in the style of a desktop toolbar status area."
  (:require [clojure.string :as str])
  (:import (java.awt Color RenderingHints)
           (java.awt.image BufferedImage)
           (javax.swing ImageIcon JLabel SwingUtilities)))

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

;; --- Shared session model reference ----------------------------------------
;; Promoted here (instead of a local atom in grog.ui) so both the chat window
;; and the settings dialog can read/write the GUI/ECA model that `chat/prompt`
;; and the footer display.
(defonce model-ref (atom nil))

(defn init-model!
  "Set the session model if unset (called once at chat startup)."
  [m]
  (when (nil? @model-ref)
    (reset! model-ref m))
  m)

(defn current-model
  "The GUI/ECA model currently selected for this session, or nil."
  []
  @model-ref)

(defn set-model-ref!
  "Set the session GUI/ECA model (no UI/persistence side effects)."
  [m]
  (reset! model-ref (str m))
  m)

;; --- Status / trust icon drawing --------------------------------------------

(defn- dot-icon
  "A small filled-circle `ImageIcon` in `color`, `size` px square."
  ^ImageIcon [^Color color ^long size]
  (let [img (BufferedImage. (int size) (int size) BufferedImage/TYPE_INT_ARGB)
        g  (.createGraphics img)]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    (.setColor g color)
    (.fillOval g 1 1 (- (int size) 2) (- (int size) 2))
    (.dispose g)
    (ImageIcon. img)))

;; --- ECA chat status (chat/statusChanged) indicator -------------------------

(defonce status-label-ref (atom nil))

(defn register-status-label!
  "Record the chat window's footer status JLabel (shows a status dot icon)."
  [^JLabel l]
  (reset! status-label-ref l)
  l)

(defn- status-mode
  "Map an ECA chat status to a coarse mode: :idle :running :question :error.
  ECA sends status as a keyword (`:executing`, `:waiting-approval`, …); the UI
  receives `(str :executing)` = `\":executing\"`, so strip any leading `:` and
  lower-case before matching."
  [s]
  (let [s (-> s str (str/replace #"^:" "") str/lower-case)]
    (case s
      ("idle" "ready" "done")                          :idle
      ("prompting" "running" "executing" "thinking"
       "streaming" "working" "queued")                 :running
      ("waiting" "waiting-approval" "awaitingapproval") :question
      ("error" "failed" "stopping")                    :error
      :idle)))

(defn- status-mode-color [mode]
  (case mode
    :idle     (Color. 255 120 120)  ;; idle = red
    :running  (Color. 130 200 130)  ;; running = green
    :question (Color. 255 170 90)
    :error    (Color. 255 0 255)
    (Color. 180 180 180)))

(defn set-status!
  "Update the footer status indicator (dot icon + tooltip) on the EDT for a given
  ECA chat status string. No-op if the label isn't registered."
  [s]
  (when-let [l @status-label-ref]
    (SwingUtilities/invokeLater
      (fn []
        (let [s (str s)
              mode (status-mode s)
              ic (dot-icon (status-mode-color mode) 14)]
          (.setIcon l ic)
          (.setToolTipText l (str "status: " s)))))))

;; --- trust (YOLO) mode indicator --------------------------------------------

(defonce trust-label-ref (atom nil))

(defn register-trust-label!
  "Record the chat window's footer trust (YOLO) JLabel (shows a trust dot icon)."
  [^JLabel l]
  (reset! trust-label-ref l)
  l)

(defn set-trust-indicator!
  "Update the footer trust (YOLO) indicator (dot icon + tooltip) on the EDT.
  No-op if not registered."
  [on?]
  (when-let [l @trust-label-ref]
    (SwingUtilities/invokeLater
      (fn []
        (if on?
          (do (.setIcon l (dot-icon (Color. 255 120 80) 14))
              (.setToolTipText l "TRUST ON — tools auto-approved"))
          (do (.setIcon l (dot-icon (Color. 110 120 130) 14))
              (.setToolTipText l "trust off")))))))
