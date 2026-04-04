(ns grog.soul
  "SOUL.md — persistent instructions merged into every chat as the model `system` message.

  Relative `:soul :path` resolves under `:workspace :default-root`. Absolute paths must still
  lie under that directory (same rule as before the simple-chat reset)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.config :as cfg])
  (:import (java.io File)
           (java.nio.file Path)))

(defn- ^Path workspace-root-path []
  (-> ^File (.getCanonicalFile (io/file (cfg/workspace-root)))
      .toPath
      .normalize
      .toAbsolutePath))

(defn- assert-under-workspace! [^String p]
  (let [soul (-> ^File (.getCanonicalFile (io/file p))
                 .toPath
                 .normalize
                 .toAbsolutePath)
        root (workspace-root-path)]
    (when-not (.startsWith soul root)
      (throw (ex-info "SOUL file must be under :workspace :default-root"
                      {:path p :workspace (cfg/workspace-root)})))))

(defn resolved-path
  "Absolute path to the soul file."
  ^String []
  (let [rel (or (some-> (get-in (cfg/grog) [:soul :path]) str str/trim not-empty)
                "SOUL.md")
        f (io/file rel)
        abs (if (.isAbsolute f)
              (.getCanonicalFile f)
              (.getCanonicalFile (io/file (cfg/workspace-root) rel)))]
    (.getPath abs)))

(defn read-text
  "Contents of the soul file (trimmed), or empty string if missing."
  []
  (let [p (resolved-path)]
    (assert-under-workspace! p)
    (let [f (io/file p)]
      (if (.exists f)
        (str/trim (slurp f :encoding "UTF-8"))
        ""))))

(defn append-text!
  "Append a markdown block (creates parent dirs and file if needed)."
  [text]
  (let [text (str/trim (str text))]
    (when (str/blank? text)
      (throw (ex-info "Refusing to append empty soul text" {})))
    (let [p (resolved-path)]
      (assert-under-workspace! p)
      (let [f (io/file p)
            parent (.getParentFile f)]
        (when parent (.mkdirs parent))
        (let [prev (if (.exists f) (slurp f :encoding "UTF-8") "")
              gap (if (or (str/blank? prev) (str/ends-with? prev "\n")) "" "\n")]
          (spit f (str prev gap "\n---\n\n" text "\n") :encoding "UTF-8"))
        (.getPath f)))))

(defn startup-status-line
  "One line for the chat banner (path, size, active vs missing; or error text)."
  []
  (try
    (let [p (resolved-path)]
      (assert-under-workspace! p)
      (let [f (io/file p)]
        (if-not (.exists f)
          (str "SOUL — " p " · file missing (no system prompt)")
          (let [t (read-text)]
            (if (str/blank? t)
              (str "SOUL — " p " · empty (no system prompt)")
              (str "SOUL — " p " · " (count t) " chars · prepended as `system` every turn"))))))
    (catch Exception e
      (str "SOUL — error: " (.getMessage e)))))
