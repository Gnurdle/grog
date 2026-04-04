(ns grog.edn-store
  "Filesystem tree of **.edn** files → nested Clojure maps (no third-party DB).

  Config: `:edn-store {:root \"…\"}` under `grog.edn` — path relative to
  `:workspace :default-root` unless absolute; must stay inside the workspace.

  **Key hierarchy (path → nested keywords):**
  - Only `*.edn` files under the root (recursive) participate.
  - Relative path (from root, `/` normalized): strip `.edn`, split on `/`.
  - Each segment becomes a keyword; nest outer → inner; leaf = `clojure.edn/read-string` of the file.
  - Example: `topic/chat/note.edn` → `{:topic {:chat {:note <value>}}}`.
  - Multiple files `deep-merge`; non-map leaves: last wins (same as before).

  Schema inside files is yours — Grog does not interpret it.

  **Write:** `write-leaf!` persists one leaf as pretty-printed EDN (creates parent dirs)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [grog.config :as cfg]
            [grog.workspace-paths :as wsp])
  (:import [java.io File]
           [java.net URLDecoder]))

(defn- resolve-root!
  (^File []
   (let [raw (some-> (get-in (cfg/grog) [:edn-store :root]) str str/trim not-empty)]
     (when raw
       (wsp/resolve-under-workspace! raw)))))

(defn configured?
  []
  (boolean (some-> (get-in (cfg/grog) [:edn-store :root]) str str/trim not-empty)))

(defn root-directory
  []
  (try (resolve-root!) (catch Exception _ nil)))

(defn- rel-path ^String [^File root ^File file]
  (-> (.relativize (.toPath root) (.toPath (.getCanonicalFile file)))
      .toString
      (str/replace \\ \/)))

(defn- prefixes-from-relative-path [^String rel]
  (when (str/ends-with? rel ".edn")
    (some-> (-> rel (subs 0 (- (count rel) 4)) (str/split #"/"))
            (->> (remove str/blank?))
            seq
            vec)))

(defn- with-nested-prefixes [prefixes value]
  (->> (map keyword prefixes)
       reverse
       (reduce #(hash-map %2 %1) value)))

(defn- read-edn-file [^File f]
  (let [s (slurp f :encoding "UTF-8")]
    (if (str/blank? s)
      {}
      (edn/read-string {:eof nil} s))))

(defn read-tree
  "Deep-merge all `*.edn` files under the configured root. Returns `{}` if off / empty / missing."
  []
  (if-let [^File root (root-directory)]
    (if (.isDirectory root)
      (let [files (->> (file-seq root)
                       (filter #(.isFile ^File %))
                       (filter #(str/ends-with? (.getName ^File %) ".edn")))]
        (if (empty? files)
          {}
          (->> files
               (keep (fn [^File f]
                       (when-let [rel (rel-path root f)]
                         (when-let [pfx (prefixes-from-relative-path rel)]
                           (try
                             (with-nested-prefixes pfx (read-edn-file f))
                             (catch Exception _ nil))))))
               (reduce cfg/deep-merge {}))))
      {})
    {}))

(defn- keypath->relative-edn-path ^String [keypath]
  (when (seq keypath)
    (str (str/join "/" (map #(if (keyword? %) (name %) (str %)) keypath)) ".edn")))

(defn leaf-file
  "Resolved `File` for `keypath` if it would live under the store root; else `nil`."
  [keypath]
  (when-let [^File root (resolve-root!)]
    (when-let [rel (keypath->relative-edn-path keypath)]
      (let [^File f (io/file root rel)]
        (when (wsp/path-under-base? root f)
          f)))))

(defn read-leaf
  "Read and parse one `.edn` leaf; returns `nil` if missing or not configured."
  [keypath]
  (when-let [^File f (leaf-file keypath)]
    (when (.isFile f)
      (read-edn-file f))))

(defn write-leaf!
  "Write `value` as EDN to `keypath` (vector of keywords/strings), e.g. `[:a :b]` → `a/b.edn`.
  Creates parent directories under the store root. Returns the `File` written."
  [keypath value]
  (let [^File root (or (resolve-root!)
                       (throw (ex-info "edn-store not configured" {})))
        rel (or (keypath->relative-edn-path keypath)
                (throw (ex-info "keypath must be non-empty" {})))
        ^File f (io/file root rel)]
    (when-not (wsp/path-under-base? root f)
      (throw (ex-info "keypath escapes edn-store root" {:keypath keypath})))
    (.mkdirs (.getParentFile f))
    (spit f (with-out-str (pprint/pprint value)) :encoding "UTF-8")
    f))

(defn delete-leaf!
  "Delete one `.edn` file for `keypath` if it exists and stays under root. Returns true if deleted."
  [keypath]
  (when-let [^File f (leaf-file keypath)]
    (when (.isFile f)
      (boolean (.delete f)))))

(defn delete-subtree!
  "Delete a directory under the store root. `rel-segments` is path strings, e.g.
  `[\"grog-memory\" \"my-ns\"]` — no `.edn` suffix. Returns true if the directory existed and was removed."
  [rel-segments]
  (if (and (seq rel-segments) (resolve-root!))
    (let [^File root (resolve-root!)
          ^File dir (reduce (fn [^File acc ^String seg] (io/file acc (str seg))) root rel-segments)]
      (if (and (.exists dir) (.isDirectory dir) (wsp/path-under-base? root dir))
        (do (doseq [^File f (reverse (file-seq dir))]
              (.delete f))
            true)
        false))
    false))

(defn list-edn-files-in-subdir
  "Direct `*.edn` files under `rel-segments` (directory path only), sorted by filename.
  Returns `[]` if missing / not configured / not a directory."
  [rel-segments]
  (if-not (and (seq rel-segments) (resolve-root!))
    []
    (let [^File root (resolve-root!)
          ^File dir (reduce (fn [^File acc ^String seg] (io/file acc (str seg))) root rel-segments)]
      (if (and (.isDirectory dir) (wsp/path-under-base? root dir))
        (->> (.listFiles dir)
             (filter some?)
             (filter #(.isFile ^File %))
             (filter #(str/ends-with? (.getName ^File %) ".edn"))
             (sort-by #(.getName ^File %))
             vec)
        []))))

(defn list-memory-project-display-names
  "Sorted display names for each immediate subdirectory of `grog-memory/Projects/` (URL-decoded dir names).
  Empty vector if edn-store is off or the tree does not exist yet."
  []
  (if-let [^File root (root-directory)]
    (let [^File p (io/file root "grog-memory" "Projects")]
      (if (and (.isDirectory p) (wsp/path-under-base? root p))
        (->> (.listFiles p)
             (filter some?)
             (filter #(.isDirectory ^File %))
             (map #(.getName ^File %))
             (map (fn [^String enc]
                    (try (URLDecoder/decode enc "UTF-8")
                         (catch Exception _ enc))))
             sort
             vec)
        []))
    []))

(defn startup-status-line []
  (try
    (if-not (configured?)
      "edn-store: off — set :edn-store {:root \"…\"} under workspace"
      (str "edn-store: " (.getPath ^File (resolve-root!))))
    (catch Exception e
      (str "edn-store: error — " (.getMessage e)))))
