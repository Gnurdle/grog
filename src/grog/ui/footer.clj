(ns grog.ui.footer
  "Shared mutable references to the main panel's footer indicators (model name,
  ECA chat status, and trust/yolo mode), so the settings dialog and the ECA
  event handler can update the live display in the chat window without threading
  them through every call site.

  The status and trust indicators are *icons* (coloured dots) with tooltips,
  in the style of a desktop toolbar status area."
  (:require [clojure.string :as str])
  (:import (java.awt BasicStroke Color RenderingHints)
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

(defn ^ImageIcon status-dot-icon
  "The status dot as a standalone icon, using EXACTLY the footer's mode mapping
  and colours — so a per-tab dot reads identically to the shared indicator.
  `s` is an ECA status string/keyword (`\"idle\"`, `:executing`, …)."
  [s]
  (dot-icon (status-mode-color (status-mode s)) 14))

(defn ^ImageIcon close-icon
  "A drawn ✕ glyph in `size` px. Font-independent: the tab close control used to
  be a JButton labelled with the literal U+2715 MULTIPLICATION X, which renders
  as tofu in any font lacking that codepoint. Drawing it means it can never
  depend on the look-and-feel font again."
  ^ImageIcon [^Color color ^long size]
  (let [sz (int size)
        inset (int (max 2 (Math/round (* 0.28 (double sz)))))
        img (BufferedImage. sz sz BufferedImage/TYPE_INT_ARGB)
        g (.createGraphics img)]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    (.setRenderingHint g RenderingHints/KEY_STROKE_CONTROL RenderingHints/VALUE_STROKE_PURE)
    (.setColor g color)
    (.setStroke g (BasicStroke. (float (max 1.4 (/ (double sz) 7.0)))
                                BasicStroke/CAP_ROUND
                                BasicStroke/JOIN_ROUND))
    (.drawLine g inset inset (- sz inset) (- sz inset))
    (.drawLine g (- sz inset) inset inset (- sz inset))
    (.dispose g)
    (ImageIcon. img)))

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

;; --- usage (tokens + cost) indicator ----------------------------------------
;; Fed by ECA `usage` content events (see grog.ui/usage-event!). Shows the last
;; prompt's totals plus the running session totals. Cost strings come from ECA
;; already formatted to 2 decimals, so very cheap (local/free/OpenRouter-flash)
;; models legitimately render as $0.00 — the token counts are the honest signal
;; there.

(defonce usage-label-ref (atom nil))

(defn register-usage-label!
  "Record the chat window's footer usage JLabel."
  [^JLabel l]
  (reset! usage-label-ref l)
  l)

(defn- fmt-tokens
  "Compact token count: 950, 1.2k, 3.4M."
  [n]
  (let [n (long (or n 0))]
    (cond
      (>= n 1000000) (format "%.1fM" (/ n 1.0e6))
      (>= n 1000)    (format "%.1fk" (/ n 1.0e3))
      :else          (str n))))

(defn- fmt-cost
  "ECA cost string/number to `$0.00`, or `—` when unknown (no price table)."
  [f]
  (if (number? f) (format "$%.2f" (double f)) "—"))

(defn set-usage!
  "Update the footer usage label from `u`, a map of the last prompt's totals:

    {:turn-tokens n :turn-cost f|nil :session-tokens n :session-cost f|nil}

  `:turn-cost`/`:session-cost` are numbers (parsed from ECA's 2-dp strings) or
  nil. Renders on the EDT; no-op if the label isn't registered. Empty text until
  there's been a turn with tokens."
  [u]
  (when-let [l @usage-label-ref]
    (SwingUtilities/invokeLater
      (fn []
        (let [txt (if (and u (pos? (long (or (:turn-tokens u) 0))))
                    (str "last: " (fmt-tokens (:turn-tokens u)) " tok · " (fmt-cost (:turn-cost u))
                         "  |  session: " (fmt-cost (:session-cost u))
                         " · " (fmt-tokens (:session-tokens u)) " tok")
                    "")]
          (.setText l txt)
          (.setToolTipText l
            (str "Tokens/cost reported by ECA usage events. Cost is 2-dp "
                 "(cheap models read $0.00). \"last\" covers the whole turn, "
                 "including tool-loop round-trips. — = no price table for the model.")))))))
