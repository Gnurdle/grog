(ns grog-mcp.memory
  "grog-memory — associative key/value store backed by SQLite over JDBC.

  A faithful Clojure re-implementation of the (now-Python) grog-memory MCP server
  (src/grog_memory/server.py) so the consolidated grog-mcp bundle can serve the
  same assoc_* tools without a Python process. Deliberately byte-compatible:

    * Same 7 tools: assoc_open_store / assoc_close_store / assoc_store /
      assoc_get / assoc_keys / assoc_delete / assoc_search
    * Same SQLite schema: CREATE TABLE IF NOT EXISTS assoc (key TEXT PRIMARY KEY,
      value TEXT NOT NULL, updated_at TEXT NOT NULL);
    * Same default DB resolution (file ~/.config/grog/memory.edn {:db ...},
      default ./grog-memory.db)
    * Same handle semantics: assoc_open_store(\"<path>\") opens/creates a store
      and returns its abs path as the handle; pass handle to the other tools to
      use that DB instead of the default.
    * Values are strings (JSON text for structured data) — same as Python.

  Because the SCHEMA and file format are identical, existing state/mem.db files
  created by the Python server are read as-is — no migration."

  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cheshire.core :as json])
  (:import [java.sql Connection DriverManager]
           [java.nio.file Paths]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Config
;; ---------------------------------------------------------------------------

;; Config is file-based: ~/.config/grog/memory.edn
;;   {:db "grog-memory.db" :max-open 8}
;; (env vars are intentionally NOT read — file-config normalization, like the
;; Python grog-memory server. GROG_MEMORY_DB / GROG_MEMORY_MAX_OPEN are gone.)
(defn- config-file ^java.io.File []
  ;; grog hands the PER-PROJECT config path via GROG_MEMORY_CONFIG; fall back to
  ;; the config-home default only when run standalone.
  (or (some-> (System/getenv "GROG_MEMORY_CONFIG") str str/trim not-empty io/file)
      (io/file (or (some-> (System/getenv "HOME") str not-empty) "~")
               ".config/grog/memory.edn")))

(defn- load-config []
  (let [f (config-file)]
    (if (.exists f)
      (try (edn/read-string (slurp f))
           (catch Exception e (binding [*out* *err*] (println "memory config load error:" (.getMessage e))) {}))
      {})))

(defn- ^String expand-tilde [s]
  (str/replace-first (str s) #"^~(?=/|$)" (System/getProperty "user.home")))

(defn- cfg [] (load-config))
(defn- default-db []
  (expand-tilde (or (:db (cfg)) "grog-memory.db")))
(defn- max-open [] (long (or (:max-open (cfg)) 8)))

(defn- ^String config-home-dir
  "Mirror grog.platform/config-home-dir so both surfaces resolve `global` to the
  same file: GROG_CONFIG_HOME, else ${XDG_CONFIG_HOME:-~/.config}/grog."
  []
  (let [home (or (some-> (System/getenv "HOME") str not-empty)
                 (System/getProperty "user.home"))
        raw (or (some-> (System/getenv "GROG_CONFIG_HOME") str str/trim not-empty)
                (str (or (some-> (System/getenv "XDG_CONFIG_HOME") str str/trim not-empty)
                         (str home "/.config"))
                     "/grog"))]
    (expand-tilde raw)))

;; ---------------------------------------------------------------------------
;; Store registry: abs-path -> {:conn java.sql.Connection :u long}
;; LRU by insertion order; guarded by a single lock (same as Python's threading.Lock).
;; ---------------------------------------------------------------------------

(def ^:private stores (java.util.LinkedHashMap.))
(def ^:private lock (Object.))

(defn- now-iso []
  (str (java.time.OffsetDateTime/now)))

(defn- abs-path
  "Resolve a possibly-relative path to absolute (returns a java String).
  A leading `~/` is expanded first — `Paths/get` alone would have made
  `~/.config/grog/global-mem.db` a literal `~` directory under the process cwd."
  [p]
  (let [^java.nio.file.Path path (Paths/get (expand-tilde p) (make-array String 0))]
    (str (.toAbsolutePath path))))

;; --- store addressing -------------------------------------------------------
;; grog's assoc tools address a store by `name` (see grog.assoc-memory, the CLI
;; twin): the reserved label `global` ALWAYS means the cross-project store at
;; <config-home>/global-mem.db, any other label is a `<name>.db` beside the
;; default store, and no label means the default — the active project's
;; state/mem.db when grog exported GROG_MEMORY_CONFIG.
;;
;; Without this the MCP dropped `name` on the floor: a caller asking for
;; "global" silently hit the default (project) store, so writes landed there
;; AND reads of them succeeded there too — a false-positive round trip that
;; left global-mem.db unreachable while a project was active.

(defn- store-label
  "The store label a caller passed (`name`/`store`/`Namespace`), or nil."
  [args]
  (some-> (or (:name args) (:store args) (:Namespace args)) str str/trim not-empty))

(defn- reserved-global? [label]
  (boolean (and label (re-matches #"(?i)global" label))))

(defn- safe-label? [label]
  (and (re-matches #"[A-Za-z0-9._-]+" label)
       (not (str/starts-with? label "."))
       (not (str/ends-with? label "."))
       (not (str/includes? label ".."))))

(defn- effective-store
  "Absolute path of the store this call addresses.
  Precedence: `handle` (a store already opened) > `name` (a store label;
  reserved `global` always wins over the active project) > configured default."
  ^String [args]
  (let [h (some-> (:handle args) str str/trim not-empty)
        l (store-label args)]
    (cond
      h (abs-path h)

      (some? l)
      (if (reserved-global? l)
        (abs-path (str (config-home-dir) java.io.File/separator "global-mem.db"))
        (do (when-not (safe-label? l)
              (throw (ex-info (str "invalid store name \"" l
                                   "\" — use only letters, digits, '_', '-', '.'; a store maps to <name>.db")
                              {:name l})))
            (abs-path (str (java.io.File. (.getParentFile (java.io.File. ^String (default-db)))
                                          (str l ".db"))))))

      :else (abs-path (default-db)))))

(defn- open-conn! [^String abs]
  (doto (DriverManager/getConnection (str "jdbc:sqlite:" abs))
    (.setAutoCommit true)))

(defn- schema! [^Connection conn]
  (with-open [st (.createStatement conn)]
    (.execute st "CREATE TABLE IF NOT EXISTS assoc (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at TEXT NOT NULL)")))

(defn- touch-lru! [key]
  (when (.containsKey ^java.util.LinkedHashMap stores key)
    (let [entry (.get ^java.util.LinkedHashMap stores key)]
      (.remove ^java.util.LinkedHashMap stores key)
      (.put ^java.util.LinkedHashMap stores key entry)))
  (when (> (.size ^java.util.LinkedHashMap stores) (max-open))
    (let [it (.entrySet ^java.util.LinkedHashMap stores)
          first (.next (.iterator it))
          evict-k (.getKey first)]
      (.remove ^java.util.LinkedHashMap stores evict-k)
      (when-let [^Connection c (:conn (.get ^java.util.LinkedHashMap stores evict-k))]
        (try (.close c) (catch Exception _))))))

(defn- connection ^Connection [path]
  (let [abs (abs-path (if (and path (not (str/blank? path))) path (default-db)))
        db-file (java.io.File. abs)]
    (when-let [pf (.getParentFile db-file)]
      (.mkdirs pf))
    (locking lock
      (when-not (.containsKey ^java.util.LinkedHashMap stores abs)
        (let [conn (open-conn! abs)]
          (try (schema! conn)
               (catch Exception e
                 (try (.close conn) (catch Exception _))
                 (throw e)))
          (.put ^java.util.LinkedHashMap stores abs {:conn conn :u 0})))
      (touch-lru! abs)
      (:conn (.get ^java.util.LinkedHashMap stores abs)))))

;; ---------------------------------------------------------------------------
;; Tools (same contract as the Python server)
;; ---------------------------------------------------------------------------

(defn assoc-open-store
  "Open a store (creating parents/file as needed) and return its absolute path
  as a `handle`. Address it by explicit `path`, by a `name` label (reserved
  `global` = the cross-project store), or by neither (the configured default)."
  [args]
  (let [target (if-let [p (some-> (:path args) str str/trim not-empty)]
                 (abs-path p)
                 (effective-store args))]
    (connection target)
    target))

(defn assoc-close-store [handle]
  (locking lock
    (let [abs (abs-path handle)
          conn (:conn (.get ^java.util.LinkedHashMap stores abs))]
      (when conn
        (try (.close ^Connection conn) (catch Exception _))
        (.remove ^java.util.LinkedHashMap stores abs)
        "closed")
      (if conn "closed" "absent"))))

(defn assoc-store [key value store]
  (let [conn (connection (effective-store store))]
    (locking lock
      (with-open [st (.prepareStatement ^Connection conn
                     "INSERT INTO assoc(key,value,updated_at) VALUES(?,?,?)
                      ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at")]
        (.setString st 1 (str key))
        (.setString st 2 (str value))
        (.setString st 3 (now-iso))
        (.executeUpdate st)))
    (str key)))

(defn assoc-get [key store]
  (let [conn (connection (effective-store store))]
    (locking lock
      (with-open [st (.prepareStatement ^Connection conn "SELECT value FROM assoc WHERE key=?")]
        (.setString st 1 (str key))
        (with-open [rs (.executeQuery st)]
          (if (.next rs)
            (.getString rs 1)
            ""))))))

(defn assoc-keys [store]
  (let [conn (connection (effective-store store))]
    (locking lock
      (with-open [st (.prepareStatement ^Connection conn "SELECT key FROM assoc ORDER BY key")]
        (with-open [rs (.executeQuery st)]
          (let [acc (transient [])]
            (while (.next rs)
              (conj! acc (.getString rs 1)))
            (pr-str (persistent! acc))))))))

(defn assoc-delete [key store]
  (let [conn (connection (effective-store store))]
    (locking lock
      (with-open [st (.prepareStatement ^Connection conn "DELETE FROM assoc WHERE key=?")]
        (.setString st 1 (str key))
        (let [n (.executeUpdate st)]
          (if (pos? n) "deleted" "absent"))))))

(defn assoc-search [substring store]
  (let [conn (connection (effective-store store))
        like (str "%" substring "%")]
    (locking lock
      (with-open [st (.prepareStatement ^Connection conn "SELECT key,value FROM assoc WHERE key LIKE ? OR value LIKE ? ORDER BY key")]
        (.setString st 1 like)
        (.setString st 2 like)
        (with-open [rs (.executeQuery st)]
          (let [acc (transient {})]
            (while (.next rs)
              (assoc! acc (.getString rs 1) (.getString rs 2)))
            (pr-str (persistent! acc))))))))

;; ---------------------------------------------------------------------------
;; Args normalization (MCP passes java.util.Map with string keys; normalize to kw map)
;; ---------------------------------------------------------------------------

(defn- parse-args [arguments]
  (cond
    (map? arguments) (into {} (map (fn [[k v]] [(keyword (name (str k))) v])) arguments)
    (instance? java.util.Map arguments) (into {} (map (fn [[k v]] [(keyword (name (str k))) v])) arguments)
    (string? arguments) (try (json/parse-string arguments true) (catch Exception _ {}))
    :else {}))

;; ---------------------------------------------------------------------------
;; Tool specs (for the bundle's registry)
;; ---------------------------------------------------------------------------

(def tools
  [{:name "assoc_open_store"
    :description (str "Open an associative-memory store and return its absolute path as a `handle`. "
                      "Address it with `path` (absolute or relative, `~` expanded; parents are created as needed), "
                      "or with `name` (reserved `global` = the cross-project store at <config-home>/global-mem.db; any "
                      "other name = <name>.db beside the default store), or with neither (the default store — the active "
                      "project's state/mem.db when grog set GROG_MEMORY_CONFIG). "
                      "Pass the returned handle as `handle` to the other assoc_* tools (omit `handle` to use the default).")
    :schema "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}}}"
    :fn (fn [a] (let [m (parse-args a)] (assoc-open-store m)))}
   {:name "assoc_close_store"
    :description "Close and release the store opened by `assoc_open_store(handle)`. Returns 'closed' or 'absent'."
    :schema "{\"type\":\"object\",\"properties\":{\"handle\":{\"type\":\"string\"}},\"required\":[\"handle\"]}"
    :fn (fn [a] (let [{:keys [handle]} (parse-args a)] (assoc-close-store (str (or handle "")))))}
   {:name "assoc_store"
    :description (str "Store a key->value entry in associative memory. Value is stored as a string; use JSON text for structured data. "
                      "`handle` (optional) is a store returned by `assoc_open_store`; `name` (optional) picks a store by label — reserved "
                      "`global` = the cross-project store at <config-home>/global-mem.db (regardless of active project), any other name = "
                      "<name>.db beside the default store. With neither, the default (active project's) store is used. "
                      "Precedence: handle > name > default. Returns the key.")
    :schema "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"},\"value\":{\"type\":\"string\"},\"handle\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}},\"required\":[\"key\",\"value\"]}"
    :fn (fn [a] (let [m (parse-args a)]
                  (assoc-store (str (or (:key m) "")) (str (or (:value m) "")) m)))}
   {:name "assoc_get"
    :description (str "Return the value for `key`, or '' if absent. `handle` (optional) is a store from `assoc_open_store`; "
                      "`name` (optional) picks a store by label — reserved `global` = the cross-project store at "
                      "<config-home>/global-mem.db, any other name = <name>.db beside the default store. With neither, the "
                      "default (active project's) store is used. Precedence: handle > name > default.")
    :schema "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"},\"handle\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}},\"required\":[\"key\"]}"
    :fn (fn [a] (let [m (parse-args a)] (assoc-get (str (or (:key m) "")) m)))}
   {:name "assoc_keys"
    :description (str "List all keys as a JSON array. `handle` (optional) is a store from `assoc_open_store`; `name` (optional) picks a "
                      "store by label — reserved `global` = the cross-project store at <config-home>/global-mem.db (regardless of active "
                      "project), any other name = <name>.db beside the default store. With neither, the default (active project's) store "
                      "is used. Precedence: handle > name > default.")
    :schema "{\"type\":\"object\",\"properties\":{\"handle\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}}}"
    :fn (fn [a] (let [m (parse-args a)] (assoc-keys m)))}
   {:name "assoc_delete"
    :description (str "Delete the entry for `key`. Returns 'deleted' or 'absent'. `handle` (optional) is a store from `assoc_open_store`; "
                      "`name` (optional) picks a store by label — reserved `global` = the cross-project store at "
                      "<config-home>/global-mem.db, any other name = <name>.db beside the default store. With neither, the default "
                      "(active project's) store is used. Precedence: handle > name > default.")
    :schema "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"},\"handle\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}},\"required\":[\"key\"]}"
    :fn (fn [a] (let [m (parse-args a)] (assoc-delete (str (or (:key m) "")) m)))}
   {:name "assoc_search"
    :description (str "Return all (key, value) pairs whose key or value contains `substring`, as a JSON object. `handle` (optional) is a "
                      "store from `assoc_open_store`; `name` (optional) picks a store by label — reserved `global` = the cross-project "
                      "store at <config-home>/global-mem.db, any other name = <name>.db beside the default store. With neither, the "
                      "default (active project's) store is used. Precedence: handle > name > default.")
    :schema "{\"type\":\"object\",\"properties\":{\"substring\":{\"type\":\"string\"},\"handle\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}},\"required\":[\"substring\"]}"
    :fn (fn [a] (let [m (parse-args a)] (assoc-search (str (or (:substring m) "")) m)))}])