(ns grog.workspace-paths
  "Workspace containment checks **without** resolving symlinks in the path string.
  That way a symlink inside the workspace may point outside the real directory tree and
  tools still accept the path; `File` / `Files` I/O follows the link as usual.

  `..` segments are still normalized and must not escape the workspace base."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [grog.config :as cfg])
  (:import [java.io File]
           [java.nio.file Path]))

(defn workspace-base-path
  "Absolute normalized `:workspace :default-root` (symlinks in the root path itself are not followed)."
  ^Path []
  (-> (io/file (cfg/workspace-root)) .toPath .toAbsolutePath .normalize))

(defn resolve-under-workspace!
  "Absolute `File` for `path` (relative to workspace or absolute under it). Throws if the
  normalized path escapes the workspace base. Symlinks in `path` are **not** expanded here."
  ^File [^String path]
  (when (string/blank? path)
    (throw (ex-info "path is empty" {:path path})))
  (let [^Path base (workspace-base-path)
        ^File f (io/file path)
        ^Path user (if (.isAbsolute f)
                     (-> f .toPath .toAbsolutePath .normalize)
                     (-> base (.resolve (str path)) .normalize))]
    (when-not (.startsWith user base)
      (throw (ex-info "path escapes workspace :default-root"
                      {:path path :resolved (str user) :workspace (cfg/workspace-root)})))
    (.toFile user)))

(defn path-under-base?
  "True iff `file`'s absolute normalized path is `base` or a descendant (logical paths;
  symlinks not followed for the check)."
  [^File base ^File file]
  (let [^Path bp (-> base .toPath .toAbsolutePath .normalize)
        ^Path fp (-> file .toPath .toAbsolutePath .normalize)]
    (.startsWith fp bp)))
