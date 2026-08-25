(ns grog.projects
  "Grog's **project home** — a directory **outside the source tree** where per-project
  context lives. Configured via `:projects {:dir …}` in grog.edn (default
  `~/grog-projects`). Each immediate subdirectory is a project; grog lists them
  for `/project`, and when a project is active it loads its notes / state / dialog
  as context for the model (see `grog.chat-context`).

  A project is a first-class entity: a directory with an optional `project.edn`
  manifest (name, description, model, MCP servers, appearance). Grog is **always
  in exactly one project** — resolved on startup from the last-used marker, else
  a configured default, else the first project; `resolve-active-project` guarantees
  this invariant.

  Convention inside a project dir (`~/grog-projects/<project>/`):
    - `project.edn` — optional manifest
    - `notes/`   — markdown/text handoff notes, decisions, logs
    - `state/`   — working data (mem store DBs, scratch)
    - `dialog/`  — per-turn chat log (thread.edn), if present
    - `scripts/` — project-specific scripts/tools

  Grog does not impose a schema on the contents; `load-context` reads text files
  under `notes/` + `dialog/` (best effort) and surfaces them to the model."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [grog.config :as cfg])
  (:import (java.io File)))

;; ---------------------------------------------------------------------------
;; Projects home
;; ---------------------------------------------------------------------------

(defn projects-home
  "The projects home `File` (may not exist yet)."
  ^File []
  (cfg/projects-dir))

(defn- list-dirs
  "Direct child directory names of `dir` (sorted), or [] if absent/not a dir."
  ^java.util.List [^File dir]
  (if (and dir (.isDirectory dir))
    (->> (.listFiles dir)
         (filter some?)
         (filter #(.isDirectory ^File %))
         (map #(.getName ^File %))
         sort
         vec)
    []))

(defn list-project-names
  "Sorted names of projects (immediate subdirs of the projects home)."
  []
  (list-dirs (projects-home)))

;; ---------------------------------------------------------------------------
;; Active-project invariant (always in a project)
;; ---------------------------------------------------------------------------

(defn project-name
  "The current session project name, or nil until `resolve-active-project` runs."
  []
  (cfg/active-project-name))

(defn- state-file
  "Marker file holding the last-used active project (outside the source tree)."
  ^File []
  (io/file (projects-home) ".active-project"))

(declare ensure-project-dir!)

(defn write-last-used!
  "Persist `name` as the last-used active project."
  [name]
  (try
    (.mkdirs (projects-home))
    (spit (state-file) (str name))
    (catch Exception _ nil)))

(defn set-project!
  "Switch the session active project to `name` (persisting it as last-used)."
  [name]
  (cfg/set-active-project! name)
  (write-last-used! name)
  name)

(defn- read-last-used
  "Last-used active project name from the marker file, or nil."
  []
  (try
    (let [^File f (state-file)]
      (when (.exists f)
        (let [s (str/trim (slurp f :encoding "UTF-8"))]
          (when (seq s) s))))
    (catch Exception _ nil)))

(defn resolve-active-project
  "Guarantee grog is always in a project: return the current active project if
  set; else the last-used marker if it still exists as a project dir; else the
  configured default (`:projects :default`); else the first project; else
  `\"default\"` (created lazily). Sets the active project as a side effect and
  returns its name."
  []
  (or (some-> (cfg/active-project-name) str str/trim not-empty)
      (let [last (read-last-used)
            default (some-> (get-in (cfg/grog) [:projects :default]) str str/trim not-empty)
            first (first (list-project-names))
            choice (or (when (and last ((set (list-project-names)) last)) last)
                       default
                       first
                       "default")]
        (ensure-project-dir! choice)
        (cfg/set-active-project! choice)
        (write-last-used! choice)
        choice)))

;; ---------------------------------------------------------------------------
;; Project directories + manifest
;; ---------------------------------------------------------------------------

(defn project-exists?
  "True if `name` is an existing project dir (or would be created as default)."
  [name]
  (and (seq name) (.isDirectory (io/file (projects-home) (str name)))))

(defn project-dir
  "The `File` for a project's directory under the projects home, or nil if the
  name is blank."
  ^File [project-name]
  (when (and (seq project-name) (not (str/blank? (str project-name))))
    (io/file (projects-home) (str project-name))))

(defn project-dir-for-active
  "The active project's directory under the projects home. Resolves the active
  project first (grog is always in a project)."
  ^File []
  (let [p (resolve-active-project)]
    (when p (project-dir p))))

(defn- manifest-file
  ^File [^File proj-dir]
  (io/file proj-dir "project.edn"))

(defn read-manifest
  "The parsed `project.edn` for a project dir (map), or {} if absent/invalid."
  ^clojure.lang.PersistentArrayMap [proj-dir]
  (let [^File f (manifest-file proj-dir)]
    (if (.exists f)
      (try (or (edn/read-string (slurp f :encoding "UTF-8")) {})
           (catch Exception _ {}))
      {})))

(defn project-root
  "The project's primary working directory. Defaults to the project dir under the
  projects home, but a `:root` field in `project.edn` (absolute, or `~`-expanded,
  or relative to the projects home) overrides it — e.g. a grog project rooted at
  the repo. This is what ECA's workspaceFolders and the shell cwd should use."
  ^File [name]
  (let [d (project-dir name)
        raw (some-> (read-manifest d) :root str str/trim not-empty)]
    (if raw
      (let [base (str/replace-first raw #"^~(?=/|$)" (str (System/getProperty "user.home")))
            f (if (.isAbsolute (io/file base))
                (io/file base)
                (io/file (projects-home) base))]
        (.getCanonicalFile f))
      d)))

(defn project-root-for-active
  "The active project's primary working directory. Resolves the active project
  first (grog is always in a project)."
  ^File []
  (let [p (resolve-active-project)]
    (when p (project-root p))))

(defn workspace-folders
  "ECA `workspaceFolders` for the active project: a one-element vector of
  {:uri <file://…> :name <project>} rooted at the active project's primary
  directory (`project-root`). This abandons the repo-root workspace — the agent
  operates purely on the active project. Falls back to the projects home if the
  project dir cannot be resolved. Always returns a non-empty seq."
  []
  (let [proj (or (resolve-active-project) (project-name) "default")
        root (or (project-root proj)
                 (ensure-project-dir! proj))]
    [{:uri (str (.toURI (.getCanonicalFile root)))
      :name proj}]))

(defn manifest-for
  "The manifest for a project by name (or {} if no project)."
  [name]
  (if-let [d (project-dir name)]
    (read-manifest d)
    {}))

(defn write-manifest!
  "Write/update a project's `project.edn`, preserving other keys. `m` is a map
  of manifest fields (:description :model :mcp :appearance ...)."
  [name m]
  (let [d (ensure-project-dir! name)
        f (manifest-file d)
        merged (merge (read-manifest d) m)]
    (spit f (with-out-str (pprint/pprint (into (sorted-map) merged)))
          :encoding "UTF-8")
    merged))

(defn ensure-project-dir!
  "Create (if needed) and return the project dir for `name`."
  ^File [name]
  (let [d (project-dir name)]
    (.mkdirs d)
    d))

(def ^:private text-extensions
  "Extensions treated as readable context text (lowercased)."
  #{".md" ".txt" ".edn" ".json" ".adoc" ".markdown"})

(defn- read-text-file
  "Best-effort slurp of a (small) text file; nil on any failure."
  ^String [^File f]
  (try
    (let [s (slurp f :encoding "UTF-8")]
      (when (seq (str/trim s)) (str/trim s)))
    (catch Exception _ nil)))

(defn list-context-files
  "Text-ish files under a project's `notes/` and `dialog/` subdirs, as `File`s
  (relative to the project dir). Used to decide what counts as \"relevant context\"."
  [^File proj-dir]
  (letfn [(walk [^File base]
            (when (.isDirectory base)
              (->> (or (.listFiles base) (make-array File 0))
                   (filter some?)
                   (sort-by #(.getName ^File %))
                                       (mapcat (fn [^File f]
                                                (cond
                                                  (.isDirectory f) (walk f)
                                                  (some #(str/ends-with? (str/lower-case (.getName f)) %)
                                                        text-extensions) [f]
                                                  :else []))))))]
    (vec (mapcat (fn [root] (walk (io/file proj-dir root)))
                 ["notes" "dialog"]))))

(defn dir-listing
  "A short human-readable listing of a project dir (top-level entries), for context."
  ^String [^File proj-dir]
  (let [names (if (.isDirectory proj-dir)
                (->> (.listFiles proj-dir)
                     (filter some?)
                     (map (fn [^File f]
                            (str (.getName f) (when (.isDirectory f) "/"))))
                     sort)
                [])]
    (if (seq names)
      (str/join ", " names)
      "(empty)")))

(defn load-context
  "Load a project's relevant context as a markdown string: a short header with the
  project path, manifest description (if any), and top-level layout, then the
  contents of its `notes/` and `dialog/` text files (best effort). Returns nil when
  no active project / no dir."
  ^String []
  (when-let [proj-dir (project-dir-for-active)]
    (let [proj (project-name)]
      (if-not (and proj (.isDirectory proj-dir))
        (str "Active project: **" proj "** (no directory yet at "
             (.getPath proj-dir) " — create it to hold project context).")
        (let [manifest (read-manifest proj-dir)
              desc (some-> (:description manifest) str str/trim not-empty)
              files (list-context-files proj-dir)
              body (mapcat (fn [^File f]
                             (if-let [txt (read-text-file f)]
                               [(str "### " (.getPath f) "\n\n" txt "\n")]
                               []))
                           files)]
          (->> (concat
                [(str "### Project directory\n\n"
                      (.getPath proj-dir)
                      (when desc (str "\n\nDescription: " desc))
                      (when-let [r (project-root proj)]
                        (str "\n\nPrimary working dir: " (.getPath r)))
                      "\n\nTop-level layout: " (dir-listing proj-dir) "\n")]
                body)
               (str/join "\n")))))))

(defn startup-status-line
  []
  (let [h (projects-home)]
    (str "projects: " (.getPath h)
         (let [n (count (list-project-names))]
           (str " — " n " project" (when (not= 1 n) "s"))))))
