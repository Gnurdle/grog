(ns grog.pager
  "Final assistant replies: optional `less -R -F -X` (ANSI, quit-if-one-screen, no termcap clear)."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.image-png :as image-png]
            [grog.md-render :as md-render]
            [grog.readline :as gread])
  (:import [java.io OutputStreamWriter]
           [java.lang ProcessBuilder ProcessBuilder$Redirect]
           [java.nio.charset StandardCharsets]))

(defn- interactive-tty?
  []
  (boolean (or (some? (gread/jline-terminal))
               (some? (System/console)))))

(def ^:private less-usable?
  (delay
    (try
      (zero? (:exit (sh/sh "less" "--version")))
      (catch Exception _ false))))

(defn- run-less! [^String full-text]
  (let [pb (doto (ProcessBuilder. ^java.util.List ["less" "-R" "-F" "-X"])
             (.redirectInput ProcessBuilder$Redirect/PIPE)
             (.redirectOutput ProcessBuilder$Redirect/INHERIT)
             (.redirectError ProcessBuilder$Redirect/INHERIT))]
    (try
      (let [proc (.start pb)]
        (with-open [stdin (.getOutputStream proc)
                    ^java.io.Writer w (OutputStreamWriter. stdin StandardCharsets/UTF_8)]
          (.write w full-text)
          (.flush w))
        (.waitFor proc))
      (catch Exception e
        (binding [*out* *err*]
          (println "grog: reply pager (less) failed:" (.getMessage e)))
        (print full-text)
        (flush)))))

(defn emit-final-reply!
  "Render `raw-content` (markdown vs plain per config), then `less` or print.
  `ansi-answer` / `ansi-reset` must match `grog.core` styling for plain mode."
  [{:keys [^String answer-prefix ^String raw-content ^String ansi-answer ^String ansi-reset]}]
  (when-not (str/blank? raw-content)
    (let [expanded (image-png/process-tags! raw-content)
          body (if (config/format-markdown?)
                 (md-render/render-to-ansi expanded)
                 (str ansi-answer expanded))
          full (str (or answer-prefix "\n") body "\n" ansi-reset)]
      (if (and (config/reply-pager?)
               (interactive-tty?)
               @less-usable?)
        (run-less! full)
        (do (print full)
            (flush))))))
