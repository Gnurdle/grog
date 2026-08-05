(ns grog.appearance
  "Runtime appearance state (fonts + colors for chat and terminal text) edited
  via the settings GUI and persisted into `grog.edn` under the :appearance key.
  Load/save preserves every other top-level key in the file; writes are atomic
  (write temp, then replace)."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]))

(def defaults
  {:chat {:font-family "Monospaced"
          :font-size 18
          :background {:rgb [0 0 0]}
          :user      {:rgb [165 138 25]}
          :thinking  {:rgb [55 165 95]}
          :answer    {:rgb [100 220 255]}
          :tool-call {:rgb [255 0 255]}
          :snark     {:rgb [128 128 140]}}
   :terminal {:font-family "Monospaced"
              :font-size 18
              :foreground {:rgb [40 230 90]}
              :background {:rgb [0 0 0]}}})

(defn- deep-merge
  "Merge maps recursively (later wins)."
  [& ms]
  (letfn [(mrg [a b]
            (if (and (map? a) (map? b)) (merge-with mrg a b) b))]
    (reduce mrg {} ms)))

(defn- deep-merge-at
  "Build {k1 {k2 ... v}} from a keyword path and a value."
  [ks v]
  (reduce #(hash-map %2 %1) v (reverse ks)))

(def ^:private state (atom defaults))

(defn current
  "The current appearance map (defaults merged in)."
  []
  (deep-merge defaults @state))

;; --- file I/O --------------------------------------------------------------

(defn- grogedn-file ^java.io.File []
  (io/file "grog.edn"))

(defn- read-grogedn-map
  "Read the whole grog.edn map (best effort); nil if unreadable/missing."
  []
  (try
    (let [f (grogedn-file)]
      (when (.exists f)
        (edn/read-string {:readers *data-readers*} (slurp f))))
    (catch Throwable _ nil)))

(defn load!
  "Load :appearance from grog.edn into the live state (merged over defaults)."
  []
  (let [app (:appearance (read-grogedn-map))]
    (reset! state (deep-merge defaults app))
    (current)))

(defn save!
  "Persist current appearance into grog.edn atomically, preserving all other
  top-level keys and values in the file."
  []
  (let [existing (or (read-grogedn-map) {})
        updated (assoc existing :appearance (current))
        f (grogedn-file)
        tmp (io/file (str f ".tmp"))]
    (spit tmp (with-out-str (pp/pprint updated)))
    (io/copy tmp f)
    (when (.exists tmp) (.delete tmp))
    (current)))

;; --- accessors -------------------------------------------------------------

(defn get-of [ks not-found] (get-in (current) ks not-found))
(defn set-of!
  "Set a nested appearance value (e.g. [:chat :answer :rgb] [...]), persist,
  and return the new current map."
  [ks v]
  (reset! state (deep-merge @state (deep-merge-at ks v)))
  (save!)
  (current))

(defn set-values!
  "Replace the whole appearance map, persist, and return it."
  [m]
  (reset! state (deep-merge defaults m))
  (save!)
  (current))

(defn reset-to-defaults!
  "Reset appearance to the built-in defaults, persist, and return them."
  []
  (reset! state (deep-merge defaults {}))
  (save!)
  (current))

(defn rgb [ks] (or (get-of (conj ks :rgb) nil) [255 255 255]))

(defn set-rgb!
  "Set a colour (appearance rgb path minus :rgb, e.g. [:chat :answer]) to a vector
  [r g b], persist, and return the new current map."
  [ks [r g b]]
  (set-of! (conj ks :rgb) [(int r) (int g) (int b)]))

(defn ansi-fg [ks]
  (let [[r g b] (rgb ks)]
    (str "\u001B[38;2;" r ";" g ";" b "m")))

(defn ansi-bg [ks]
  (let [[r g b] (rgb ks)]
    (str "\u001B[48;2;" r ";" g ";" b "m")))

;; --- convenience -----------------------------------------------------------

(defn chat-font-size       [] (get-of [:chat :font-size] 18))
(defn chat-font-family     [] (get-of [:chat :font-family] "Monospaced"))
(defn chat-bg              [] (rgb [:chat :background]))
(defn terminal-font-size   [] (get-of [:terminal :font-size] 18))
(defn terminal-fg          [] (rgb [:terminal :foreground]))
(defn terminal-bg          [] (rgb [:terminal :background]))

(defn ansi-user     [] (ansi-fg [:chat :user]))
(defn ansi-thinking [] (ansi-fg [:chat :thinking]))
(defn ansi-answer   [] (ansi-fg [:chat :answer]))
(defn ansi-tool-call [] (ansi-fg [:chat :tool-call]))
