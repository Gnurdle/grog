(ns grog.pager
  "Final assistant replies: markdown render (when enabled) and print to stdout."
  (:require [clojure.string :as str]
            [grog.config :as config]
            [grog.image-png :as image-png]
            [grog.md-render :as md-render]))

(defn emit-final-reply!
  "Render `raw-content` (markdown vs plain per config), then print.
  `ansi-answer` / `ansi-reset` must match `grog.core` styling for plain mode."
  [{:keys [^String answer-prefix ^String raw-content ^String ansi-answer ^String ansi-reset]}]
  (when-not (str/blank? raw-content)
    (let [expanded (image-png/process-tags! raw-content)
          body (if (config/format-markdown?)
                 (md-render/render-to-ansi expanded)
                 (str ansi-answer expanded))
          full (str (or answer-prefix "\n") body "\n" ansi-reset)]
      (print full)
      (flush))))
