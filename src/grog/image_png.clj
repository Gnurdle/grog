(ns grog.image-png
  "Detect `<image-png>…</image-png>` or `<image-png>…<image-png/>` (case-insensitive) in assistant text.
  Inner content is a workspace-relative or absolute-under-workspace path to a PNG file. Opens each image in
  a new Swing window; returns text with tags replaced by short notes for the terminal."
  (:require [clojure.string :as str]
            [grog.fs :as fs])
  (:import [java.awt GraphicsEnvironment Image]
           [java.util.regex Pattern]
           [javax.swing ImageIcon JLabel JFrame JScrollPane SwingUtilities WindowConstants]))

(def ^:private ^Pattern open-re (Pattern/compile "(?i)<image-png>"))
(def ^:private ^Pattern close-std-re (Pattern/compile "(?i)</image-png>"))
(def ^:private ^Pattern close-alt-re (Pattern/compile "(?i)<image-png/>"))

(defn- next-open-idx [^String s ^long from]
  (let [sub (.substring s (int from))
        m (.matcher open-re sub)]
    (when (.find m)
      (+ from (.start m)))))

(defn- open-tag-len [^String s ^long idx]
  (let [sub (.substring s (int idx))
        m (.matcher open-re sub)]
    (if (and (.find m) (zero? (.start m)))
      (- (.end m) (.start m))
      11)))

(defn- next-close-idx [^String s ^long from]
  (let [sub (.substring s (int from))
        m1 (.matcher close-std-re sub)
        a (when (.find m1) (.start m1))
        m2 (.matcher close-alt-re sub)
        b (when (.find m2) (.start m2))]
    (when (or a b)
      (+ from (long (if (and a b) (min a b) (or a b)))))))

(defn- close-tag-len [^String s ^long idx]
  (let [sub (.substring s (int idx))
        m1 (.matcher close-std-re sub)]
    (if (and (.find m1) (zero? (.start m1)))
      (- (.end m1) (.start m1))
      (let [m2 (.matcher close-alt-re sub)]
        (if (and (.find m2) (zero? (.start m2)))
          (- (.end m2) (.start m2))
          12)))))

(defn- headless? []
  (GraphicsEnvironment/isHeadless))

(defn- open-png-frame! [^java.io.File file ^String label-path]
  (when-not (headless?)
    (SwingUtilities/invokeLater
     (fn []
       (try
         (let [path (.getCanonicalPath file)
               ^ImageIcon icon (ImageIcon. path)
               w (.getIconWidth icon)
               h (.getIconHeight icon)
               max-w 1280
               max-h 900
               icon2 (if (and (pos? w) (pos? h)
                              (or (> w max-w) (> h max-h)))
                       (let [sw (/ max-w (double w))
                             sh (/ max-h (double h))
                             sc (min 1.0 (min sw sh))
                             nw (max 1 (int (* w sc)))
                             nh (max 1 (int (* h sc)))]
                         (ImageIcon. (.getScaledInstance (.getImage icon) nw nh Image/SCALE_SMOOTH)))
                       icon)
               ^JLabel label (JLabel. icon2)
               ^JScrollPane sp (JScrollPane. label)
               ^JFrame fr (JFrame. (str "Grog — " label-path))]
           (.setDefaultCloseOperation fr WindowConstants/DISPOSE_ON_CLOSE)
           (.add (.getContentPane fr) sp)
           (.setSize fr (min 1280 (+ 48 (.getIconWidth icon2))) (min 900 (+ 80 (.getIconHeight icon2))))
           (.setLocationRelativeTo fr nil)
           (.setVisible fr true))
         (catch Exception e
           (binding [*out* *err*]
             (println "grog: image-png viewer:" (.getMessage e)))))))))

(defn- handle-inner! [^StringBuilder sb ^String inner]
  (let [path-raw (str/trim inner)]
    (if (str/blank? path-raw)
      (.append sb "\n\n[image-png: empty path]\n\n")
      (try
        (let [^java.io.File f (fs/resolve-workspace-path! path-raw)
              name (.getName f)
              png? (.endsWith (.toLowerCase (str name)) ".png")]
          (cond
            (not (.isFile f))
            (.append sb (str "\n\n[image-png: not a file: " path-raw "]\n\n"))

            (not png?)
            (.append sb (str "\n\n[image-png: must be a .png file: " path-raw "]\n\n"))

            :else
            (do
              (open-png-frame! f path-raw)
              (if (headless?)
                (.append sb (str "\n\n[image-png: headless JVM — cannot open window; file: " path-raw "]\n\n"))
                (.append sb (str "\n\n[Opened PNG in viewer: " path-raw "]\n\n"))))))
        (catch Exception e
          (.append sb (str "\n\n[image-png: " (.getMessage e) "]\n\n")))))))

(defn process-tags!
  "Side effect: open Swing window(s) for each tag. Returns `s` with tags replaced by short notes."
  ^String [^String s]
  (if (or (str/blank? s) (nil? (next-open-idx s 0)))
    s
    (let [n (long (count s))
          ^StringBuilder sb (StringBuilder.)]
      (loop [i 0]
        (if (>= i n)
          (str sb)
          (if-let [open-idx (next-open-idx s i)]
            (let [olen (long (open-tag-len s open-idx))
                  cstart (+ open-idx olen)]
              (.append sb (.substring s (int i) (int open-idx)))
              (if-let [close-idx (next-close-idx s cstart)]
                (let [inner (.substring s (int cstart) (int close-idx))
                      clen (long (close-tag-len s close-idx))
                      j (+ close-idx clen)]
                  (handle-inner! sb (str/trim inner))
                  (recur j))
                (do (.append sb (.substring s (int open-idx)))
                    (str sb))))
            (do (.append sb (.substring s (int i)))
                (str sb))))))))
