(ns grog.fs.walk
  "Device-bounded filesystem traversal — the walker every search stands on.

  THE INVARIANT: a walk started at `root` NEVER crosses a filesystem boundary.
  A directory is entered only when its device (`st_dev`) equals the root's, so
  mount points (other devices), `/proc`, `/sys`, `/dev`, tmpfs, network and FUSE
  mounts are all out of bounds *by construction* — not by a flag someone has to
  remember (`find -xdev`) or a habit someone has to keep.

  Symlinks are NOT followed by default, so a link cannot smuggle the walk onto
  another filesystem either. When `:follow-links?` is set they are followed, but
  only within the root AND the same device; anything that would leave is recorded
  in `:left` rather than traversed.

  Nothing here throws for a permission error or a vanishing file: an unreadable
  directory is skipped and counted. A walk reports what it refused to cross, so
  \"not found\" can be told apart from \"not looked at\"."
  (:require [clojure.java.io :as io])
  (:import (java.nio.file Files FileVisitOption FileVisitResult LinkOption Path Paths
                          SimpleFileVisitor)
           (java.nio.file.attribute BasicFileAttributes)
           (java.util EnumSet)))

(defn device
  "The device (`st_dev`) a path lives on, as a comparable value. Unix uses the
  `unix:dev` attribute; without a unix view we fall back to the `FileStore`,
  whose equality is device-based on OpenJDK."
  [^Path p]
  (try (Files/getAttribute p "unix:dev" (make-array LinkOption 0))
       (catch Throwable _ (Files/getFileStore p))))

(defn real-path
  "Canonical (symlink-free, absolute) path. Throws if it does not exist — which
  is the honest answer for a search root that is not there."
  ^Path [p]
  (.toRealPath (Paths/get (str p) (make-array String 0)) (make-array LinkOption 0)))

(defn under?
  "True when `child` is `parent` or lies beneath it. Both should be real paths."
  [^Path parent ^Path child]
  (.startsWith child parent))

(defn bounded-walk
  "Visit every REGULAR FILE under `root` that lives on the SAME filesystem as
  `root`.

  opts:
    :visit        (fn [{:keys [path rel attrs]}])  called per regular file
    :visit-dir    (fn [{:keys [path rel]}])        called per directory ENTERED
    :skip-dir?    (fn [^Path rel]) -> true prunes the subtree
    :follow-links? follow symlinks, but only within root+device (default false)

  Returns {:root :device :dirs :files :left [rel …]} — `:left` names each
  directory the walk REFUSED to enter because it was on another filesystem."
  [root {:keys [visit visit-dir skip-dir? follow-links?]}]
  (let [^Path root (real-path root)
        root-dev (device root)
        !s (atom {:dirs 0 :files 0 :left []})
        opts (if follow-links?
               (EnumSet/of FileVisitOption FileVisitOption/FOLLOW_LINKS)
               (EnumSet/noneOf FileVisitOption))]
    (Files/walkFileTree
     root opts Integer/MAX_VALUE
     (proxy [SimpleFileVisitor] []
       (preVisitDirectory [^Path dir _attrs]
         (let [same-dev (= root-dev (device dir))
               in-tree  (or (not follow-links?) (under? root dir))
               rel      (.relativize root dir)]
           (if (and same-dev in-tree)
             (do (swap! !s update :dirs inc)
                 (if (and skip-dir? (skip-dir? rel))
                   FileVisitResult/SKIP_SUBTREE
                   (do (when visit-dir (visit-dir {:path dir :rel rel}))
                       FileVisitResult/CONTINUE)))
             ;; THE BOUNDARY: another filesystem (or a link out of the tree) is
             ;; recorded and skipped, never entered.
             (do (swap! !s update :left conj (str rel))
                 FileVisitResult/SKIP_SUBTREE))))
       (visitFile [^Path f ^BasicFileAttributes attrs]
         (when (.isRegularFile attrs)
           (swap! !s update :files inc)
           (when visit (visit {:path f :rel (.relativize root f) :attrs attrs})))
         FileVisitResult/CONTINUE)
       (visitFileFailed [_ _] FileVisitResult/CONTINUE)))   ; never abort a walk
    (assoc @!s :root (str root) :device (str root-dev))))

(defn same-device?
  "True when `a` and `b` live on the same filesystem."
  [a b]
  (= (device (real-path a)) (device (real-path b))))
