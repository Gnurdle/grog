(ns grog-mcp.memory
  "grog-memory — associative key/value store backed by SQLite over JDBC.

  Served by the consolidated grog-mcp bundle, byte-compatible with the
  grog-memory MCP server:

    * The same `assoc_*` tools as the standalone server, plus everything added
      since (`assoc_span`, `assoc_append`, `assoc_import`, …). Deliberately no
      count: this header once claimed seven, and stayed that way for nine more
      tools, because a stale count is worse than none.
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
;; other grog servers.)
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
  ;; UTC, offset-free — `updated_at` is meant to be a SORTABLE axis, and an
  ;; OffsetDateTime string carries the machine's current offset, so the same
  ;; instant reads differently either side of a DST change and no longer sorts
  ;; chronologically. Instant.toString is always `...Z`.
  (str (java.time.Instant/now)))

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
;; `name` must be honored: a caller asking for "global" must not silently hit
;; the default (project) store, which would leave global-mem.db unreachable
;; while a project is active.

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

;; --- ${ref} resolution ------------------------------------------------------
;;
;; A value may REFERENCE another entry in the SAME store with `${key}`. An entry
;; that is mostly a pointer costs nothing to store and cannot drift: ONE entry
;; owns the fact and every entry that wants it says so. That is the difference
;; between a key/value store and a wad of files — the filesystem makes you copy,
;; and copies rot.
;;
;; Rules: depth-limited, cycle-safe, and a reference that does not resolve is
;; left LITERAL (`${whatever}` shows up as text), so a broken link degrades to
;; something visible rather than an empty value or a hang. Write `$${key}` when
;; you mean those characters literally.

(def ^:private ref-re #"\$\{([^{}]+)\}")
(def ^:private max-ref-depth 5)
(def ^:private esc-sentinel "\u0001ASSOC-ESC\u0001")

(defn- raw-get
  "The stored value for `key` using the ALREADY-OPEN `conn`, or nil when absent.
  Kept separate from `assoc-get` so reference resolution reuses ONE connection
  instead of opening another per hop."
  ^String [^Connection conn ^String key]
  (with-open [st (.prepareStatement conn "SELECT value FROM assoc WHERE key=?")]
    (.setString st 1 (str key))
    (with-open [rs (.executeQuery st)]
      (when (.next rs) (.getString rs 1)))))

(defn- resolve-refs
  "Expand `${key}` references in `v` against `conn`.

  `seen` is the ANCESTOR PATH — the keys on the branch currently being expanded —
  NOT a set of everything visited. That distinction is the whole ballgame: a key
  referenced twice in one value must expand BOTH times (a diamond), while only a
  true cycle (a key inside its own ancestry) is refused. A shared 'visited' set
  silently treats the second reference as a cycle."
  ^String [^Connection conn ^String v depth seen]
  (cond
    (nil? v) nil
    (or (zero? depth) (nil? (re-find ref-re v)))
    (str/replace v esc-sentinel "${")

    :else
    (-> (str/replace v "$${" esc-sentinel)
        (str/replace ref-re
                     (fn [[whole k]]
                       (let [k (str/trim k)
                             raw (when-not (contains? seen k) (raw-get conn k))]
                         (if (some? raw)
                           ;; thread the path DOWN; siblings keep the caller's set
                           (resolve-refs conn raw (dec depth) (conj seen k))
                           whole))))
        (str/replace esc-sentinel "${"))))

(defn assoc-get [key store]
  (let [conn (connection (effective-store store))]
    (locking lock
      (or (resolve-refs conn (raw-get conn (str key)) max-ref-depth #{(str key)})
          ""))))

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

;; --- ordered spans over time-based keys -------------------------------------
;;
;; assoc_keys and assoc_search are WHOLE-STORE operations: one hands back every
;; key, the other scans every value for a substring. Neither can answer what a
;; time-keyed log actually asks - "the entries between X and Y, newest first,
;; twenty of them". Call that a SPAN: a key range plus a window.
;;
;; It is cheap because `key` is the PRIMARY KEY, so a range predicate on it is an
;; index range scan: an unbounded store does not make a bounded query slow.

(defn- utc-stamp
  "A sortable UTC stamp, `YYYYMMDDTHHMMSSZ`. Zero-padded and fixed width ON
  PURPOSE: an un-padded hour sorts after a padded one ('9' > '1'), which silently
  reorders a log."
  ^String []
  (-> (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'")
      (.withZone (java.time.ZoneOffset/UTC))
      (.format (java.time.Instant/now))))

(defn- span-bounds
  "The `[lo hi]` key range a span describes.

  `prefix` ALONE means the range of keys that start with it — `[prefix,
  prefix+\\uffff]`, which is an index range scan and immune to LIKE wildcards
  inside the prefix. `from`/`to` override either end."
  [prefix from to]
  (let [p (some-> prefix str str/trim not-empty)
        lo (or (some-> from str str/trim not-empty) p "")
        hi (or (some-> to str str/trim not-empty)
               (when p (str p "\uffff"))
               "\uffff")]
    [lo hi]))

(defn- parse-limit
  [v default]
  (try (let [n (Long/parseLong (str (or v "")))]
         (if (pos? n) (min n 1000) default))
       (catch Throwable _ default)))

(defn- truthy-arg?
  "MCP args arrive as strings or booleans; absent means true."
  [v]
  (cond
    (nil? v) true
    (false? v) false
    :else (not= "false" (str/lower-case (str v)))))

(defn assoc-span
  "Ordered, BOUNDED scan over keys — the read side a time-keyed log needs.

  `opts`: `:prefix` (key prefix), `:from` / `:to` (explicit key bounds, both
  inclusive), `:limit` (default 50, max 1000), `:order` (\"asc\"|\"desc\",
  default asc), `:values` (default true; false for keys only).

  Returns a JSON array — objects `{key,updated_at,value}` when `:values`, else
  key strings. `updated_at` is exposed here because it is a DIFFERENT axis from a
  time token in the key: a rollup key is rewritten all day, so its key says when
  the day started and its `updated_at` says when it was last touched."
  [opts store]
  (let [[lo hi] (span-bounds (:prefix opts) (:from opts) (:to opts))
        n (parse-limit (:limit opts) 50)
        desc? (= "desc" (str/lower-case (str (or (:order opts) "asc"))))
        values? (truthy-arg? (:values opts))
        sql (str "SELECT key,updated_at" (when values? ",value")
                 " FROM assoc WHERE key>=? AND key<=? ORDER BY key "
                 (if desc? "DESC" "ASC") " LIMIT ?")
        conn (connection (effective-store store))]
    (locking lock
      (with-open [st (.prepareStatement ^Connection conn sql)]
        (.setString st 1 lo)
        (.setString st 2 hi)
        (.setInt st 3 (int n))
        (with-open [rs (.executeQuery st)]
          (let [acc (transient [])]
            (while (.next rs)
              (conj! acc (if values?
                           {"key" (.getString rs 1)
                            "updated_at" (.getString rs 2)
                            "value" (.getString rs 3)}
                           (.getString rs 1))))
            (pr-str (persistent! acc))))))))

(defn assoc-append
  "Append a timestamped entry under `namespace` and return the key written.

  The SERVER mints the stamp (`<ns>_YYYYMMDDTHHMMSSZ`) so no caller has to format
  one — padding/UTC is the one mistake that silently reorders a log. If two
  appends land in the same second the key is disambiguated (`-02`, `-03`, …)
  rather than overwritten: an append must never lose an entry."
  [namespace value store]
  (let [ns (some-> namespace str str/trim not-empty)]
    (when-not ns (throw (ex-info "assoc_append needs a `namespace`" {})))
    (let [base (str ns "_" (utc-stamp))
          conn (connection (effective-store store))]
      (locking lock
        (loop [k base n 0]
          (cond
            (nil? (raw-get conn k)) (do (assoc-store k (str value) store) k)
            (> n 99) (throw (ex-info "assoc_append: 100 appends in one second" {:base base}))
            :else (recur (str base "-" (format "%02d" (inc n))) (inc n))))))))

(defn assoc-count
  "How many entries live under `prefix` (whole store when blank)."
  [prefix store]
  (let [[lo hi] (span-bounds prefix nil nil)
        conn (connection (effective-store store))]
    (locking lock
      (with-open [st (.prepareStatement ^Connection conn
                                        "SELECT COUNT(*) FROM assoc WHERE key>=? AND key<=?")]
        (.setString st 1 lo)
        (.setString st 2 hi)
        (with-open [rs (.executeQuery st)]
          (if (.next rs) (str (.getInt rs 1)) "0"))))))

(defn assoc-prune
  "Keep the NEWEST `keep` entries under `prefix`, delete the rest — so a log can
  live forever without growing forever. `keep` 0 empties the prefix. Returns a
  one-line report."
  [prefix keep store]
  (let [[lo hi] (span-bounds prefix nil nil)
        n (max 0 (long (or (try (Long/parseLong (str keep)) (catch Throwable _ nil)) 0)))
        conn (connection (effective-store store))]
    (locking lock
      (with-open [st (.prepareStatement ^Connection conn
                                        (str "DELETE FROM assoc WHERE key>=? AND key<=? AND key NOT IN"
                                             " (SELECT key FROM assoc WHERE key>=? AND key<=? ORDER BY key DESC LIMIT ?)"))]
        (.setString st 1 lo)
        (.setString st 2 hi)
        (.setString st 3 lo)
        (.setString st 4 hi)
        (.setInt st 5 (int n))
        (let [d (.executeUpdate st)]
          (str "deleted " d (if (zero? d) " (nothing to prune)" "")))))))

(defn- coerce-seq
  "MCP args arrive as JSON text, an already-parsed Clojure collection, or — in
  practice — a java.util.List. The SDK's Jackson ObjectMapper deserializes
  nested JSON into JAVA collections, and a java.util.List is NOT `sequential?`,
  so without that branch a nested array silently coerced to [] (the
  \"assoc_write needs `entries`\" bug: the arg was present on the wire and read
  as absent here)."
  [v]
  (cond
    (nil? v) []
    (string? v) (try (json/parse-string v true) (catch Throwable _ []))
    (sequential? v) v
    (instance? java.util.Collection v) (vec v)
    :else []))

(defn- map-like?
  "Clojure map OR java.util.Map — nested args come back as the latter, and
  `map?` does not see a java.util.Map (so entry-key/value read it as absent).
  `clojure.core/get` DOES work on a java.util.Map, so only the predicate matters."
  [v]
  (or (map? v) (instance? java.util.Map v)))

(defn- entry-key [e]
  (some-> (if (map-like? e) (or (get e "key") (get e :key)) e) str str/trim not-empty))

(defn- entry-value [e]
  (if (map-like? e) (or (get e "value") (get e :value)) nil))

(defn assoc-write
  "Write several entries and/or delete several keys in ONE transaction.

  Either all of it lands or none of it does. The point is not speed: it is that an
  entry and the index that points at it cannot half-apply, which is the failure
  mode of doing it as two calls. `entries` is `[{key,value}]`, `delete` is
  `[key]`; both accept JSON text or a parsed collection."
  [entries deletes store]
  (let [pairs (mapv (fn [e]
                      (let [k (entry-key e)]
                        ;; FAIL, do not skip. An atomic write that silently drops a
                        ;; malformed entry reports success for work it did not do.
                        (when-not k (throw (ex-info "assoc_write: entry without a `key`" {:entry e})))
                        [k (str (entry-value e))]))
                    (coerce-seq entries))
        dks (mapv (fn [d]
                    (or (entry-key d)
                        (throw (ex-info "assoc_write: delete without a key" {:entry d}))))
                  (coerce-seq deletes))]
    (when (and (empty? pairs) (empty? dks))
      (throw (ex-info "assoc_write needs `entries` and/or `delete`" {})))
    (let [conn (connection (effective-store store))
          iso (now-iso)]
      (locking lock
        (let [auto (.getAutoCommit conn)]
          (try
            (.setAutoCommit conn false)
            (when (seq pairs)
              (with-open [st (.prepareStatement
                              ^Connection conn
                              (str "INSERT INTO assoc(key,value,updated_at) VALUES(?,?,?) "
                                   "ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at"))]
                (doseq [[k v] pairs]
                  (.setString st 1 ^String k)
                  (.setString st 2 ^String v)
                  (.setString st 3 ^String iso)
                  (.addBatch st))
                (.executeBatch st)))
            (let [dn (if (seq dks)
                       (with-open [st (.prepareStatement ^Connection conn "DELETE FROM assoc WHERE key=?")]
                         (doseq [k dks] (.setString st 1 ^String k) (.addBatch st))
                         ;; bind the batch result ONCE - calling executeBatch twice
                         ;; would run the whole batch a second time
                         (let [^ints res (.executeBatch st)]
                           (areduce res i acc 0 (+ acc (aget res i)))))
                       0)]
              (.commit conn)
              (str "wrote " (count pairs) ", deleted " dn (when (seq dks) (str " of " (count dks) " requested"))))
            (catch Throwable e
              (try (.rollback conn) (catch Throwable _ nil))
              (throw e))
            (finally
              (try (.setAutoCommit conn auto) (catch Throwable _ nil)))))))))

(defn assoc-rename
  "Move the value at `from` to `to`.

  With `redirect` (default TRUE) the old key is left holding `${to}` — a LIVE
  reference — so every existing `${from}` reference keeps resolving instead of
  going latent the moment the name changes. Set redirect=false for a clean move.

  Doing this as store+delete by hand is how a store accumulates dangling names."
  [from to redirect store]
  (let [f (some-> from str str/trim not-empty)
        t (some-> to str str/trim not-empty)]
    (when-not (and f t) (throw (ex-info "assoc_rename needs `from` and `to`" {})))
    (let [conn (connection (effective-store store))
          v (locking lock (raw-get conn f))]
      (when (nil? v) (throw (ex-info (str "assoc_rename: no such key: " f) {})))
      (let [redirect? (truthy-arg? redirect)]
        (assoc-write (cond-> [{"key" t "value" v}]
                       redirect? (conj {"key" f "value" (str "${" t "}")}))
                     (when-not redirect? [f])
                     store)
        (str "renamed " f " -> " t (if redirect? " (old key now redirects)" ""))))))

(defn assoc-changed
  "Entries whose `updated_at` falls in `[since, until]` (both optional, ISO-8601).

  This is the axis a time token in the KEY cannot give you: a rollup key is
  rewritten all day, so its key records when the day started and its `updated_at`
  records when it was last touched. Sorted by `updated_at` (newest first by
  default), because that is the order a changed-set is interesting in."
  [opts store]
  (let [since (some-> (:since opts) str str/trim not-empty)
        until (some-> (:until opts) str str/trim not-empty)
        [plo phi] (span-bounds (:prefix opts) nil nil)
        n (parse-limit (:limit opts) 50)
        desc? (= "desc" (str/lower-case (str (or (:order opts) "desc"))))
        values? (truthy-arg? (:values opts))
        sql (str "SELECT key,updated_at" (when values? ",value")
                 " FROM assoc WHERE key>=? AND key<=?"
                 (when since " AND updated_at>=?")
                 (when until " AND updated_at<=?")
                 " ORDER BY updated_at " (if desc? "DESC" "ASC") " LIMIT ?")
        conn (connection (effective-store store))]
    (locking lock
      (with-open [st (.prepareStatement ^Connection conn sql)]
        (let [idx (atom 0)
              put! (fn [s] (swap! idx inc) (.setString st (int @idx) (str s)))]
          (put! plo)
          (put! phi)
          (when since (put! since))
          (when until (put! until))
          (.setInt st (int (inc @idx)) (int n)))
        (with-open [rs (.executeQuery st)]
          (let [acc (transient [])]
            (while (.next rs)
              (conj! acc (if values?
                           {"key" (.getString rs 1)
                            "updated_at" (.getString rs 2)
                            "value" (.getString rs 3)}
                           (.getString rs 1))))
            (pr-str (persistent! acc))))))))

(defn assoc-namespaces
  "The distinct dotted prefixes in the store (up to `depth` segments, default 1)
  with a count each — the SHAPE of what is stored, without pulling every key.

  Scans at most 20000 keys under `prefix`; treat the counts as \"at least\", which
  for deciding how to file something is all you need."
  [depth prefix store]
  (let [d (max 1 (min 4 (parse-limit depth 1)))
        [lo hi] (span-bounds prefix nil nil)
        conn (connection (effective-store store))]
    (locking lock
      (with-open [st (.prepareStatement ^Connection conn
                                        (str "SELECT key FROM assoc WHERE key>=? AND key<=? "
                                             "ORDER BY key LIMIT 20000"))]
        (.setString st 1 lo)
        (.setString st 2 hi)
        (with-open [rs (.executeQuery st)]
          (let [acc (transient {})]
            (while (.next rs)
              (let [k (.getString rs 1)
                    p (str/join "." (take d (str/split k #"\.")))]
                (assoc! acc p (inc (get acc p 0)))))
            (pr-str (into (sorted-map) (persistent! acc)))))))))

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

(defn assoc-import
  "Store the CONTENT of the text file at `path` under `key` — so a note can be
  mirrored into the store BY PATH.

  Why a path and not a value: the caller pays for every byte it sends, and the
  whole point of mirroring a note is to keep the note's bytes OUT of a session's
  context. Handing over a 19 KB handoff as an `assoc_store` value costs 19 KB of
  the caller's context; handing over its path costs nothing, and the file is read
  here instead. Retrieval is then one `assoc_get` by key."
  [path key opts]
  (let [p (some-> path str str/trim not-empty)
        k (some-> key str str/trim not-empty)
        f (when p (io/file p))]
    (cond
      (nil? p) "assoc_import needs a `path`"
      (nil? k) "assoc_import needs a `key`"
      (not (.isFile f)) (str "no such file: " p)
      :else (let [txt (slurp f :encoding "UTF-8")]
              (assoc-store k txt opts)
              (str "imported " (count txt) " chars from " p " -> " k)))))

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
                      "Precedence: handle > name > default. Returns the key. "
                      "The value may REFERENCE other entries with `${key}` — they are expanded by `assoc_get`, so an entry "
                      "can thread several facts together instead of duplicating (and later contradicting) them.")
    :schema "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"},\"value\":{\"type\":\"string\"},\"handle\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}},\"required\":[\"key\",\"value\"]}"
    :fn (fn [a] (let [m (parse-args a)]
                  (assoc-store (str (or (:key m) "")) (str (or (:value m) "")) m)))}
   {:name "assoc_import"
    :description (str "Store the CONTENT of the text file at `path` under `key` — mirror a note or file into the store WITHOUT "
                      "sending its bytes through the tool call. That is the point: handing over a 19 KB handoff as an `assoc_store` "
                      "value costs 19 KB of your context; handing over the path costs nothing. Returns a one-line report with the "
                      "character count. Retrieve it with `assoc_get` (one call, whole text); list a directory's worth with "
                      "`assoc_span` on a key prefix. `handle` (optional) is a store from `assoc_open_store`; `name` (optional) picks "
                      "a store by label — reserved `global` = the cross-project store at <config-home>/global-mem.db, any other name "
                      "= <name>.db beside the default store. With neither, the default (active project's) store is used. "
                      "Precedence: handle > name > default.")
    :schema "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"key\":{\"type\":\"string\"},\"handle\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}},\"required\":[\"path\",\"key\"]}"
    :fn (fn [a] (let [m (parse-args a)] (assoc-import (:path m) (:key m) m)))}
   {:name "assoc_get"
    :description (str "Return the value for `key`, or '' if absent. `handle` (optional) is a store from `assoc_open_store`; "
                      "`name` (optional) picks a store by label — reserved `global` = the cross-project store at "
                      "<config-home>/global-mem.db, any other name = <name>.db beside the default store. With neither, the "
                      "default (active project's) store is used. Precedence: handle > name > default. "
                      "VALUES MAY REFERENCE OTHER ENTRIES: `${other-key}` inside a value is expanded against the SAME store "
                      "(depth 5, cycle-safe). An unresolved reference is left LITERAL so a broken link is visible; write "
                      "`$${key}` for the characters literally. `assoc_search` returns values UNexpanded.")
    :schema "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"},\"handle\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}},\"required\":[\"key\"]}"
    :fn (fn [a] (let [m (parse-args a)] (assoc-get (str (or (:key m) "")) m)))}
   {:name "assoc_keys"
    :description (str "List ALL keys as a JSON array — fine for a small store; for anything bounded or ordered (prefix, a time "
                      "window, newest-first, a limit) use `assoc_span` instead. `handle` (optional) is a store from "
                      "`assoc_open_store`; `name` (optional) picks a store by label — reserved `global` = the cross-project store at "
                      "<config-home>/global-mem.db (regardless of active project), any other name = <name>.db beside the default "
                      "store. With neither, the default (active project's) store is used. Precedence: handle > name > default.")
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
    :fn (fn [a] (let [m (parse-args a)] (assoc-search (str (or (:substring m) "")) m)))}
   {:name "assoc_span"
    :description (str "ORDERED, BOUNDED scan over keys — what a time-keyed log needs. `prefix` = keys starting with it; "
                      "`from`/`to` = explicit key bounds (both inclusive); `limit` default 50 (max 1000); `order` asc|desc; "
                      "`values` default true (false = keys only). Returns a JSON array of {\"key\",\"updated_at\",\"value\"} "
                      "(key strings when values=false). For 'the newest under this prefix' use order=desc & limit=1. "
                      "`key` is the PRIMARY KEY, so a range here is an INDEX scan — a large store does not make it slow. "
                      "Store selection as usual (handle > name > default).")
    :schema "{\"type\":\"object\",\"properties\":{\"prefix\":{\"type\":\"string\"},\"from\":{\"type\":\"string\"},\"to\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"},\"order\":{\"type\":\"string\",\"enum\":[\"asc\",\"desc\"]},\"values\":{\"type\":\"boolean\"},\"handle\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}}}"
    :fn (fn [a] (let [m (parse-args a)] (assoc-span m m)))}
   {:name "assoc_append"
    :description (str "Append a timestamped entry: stores `value` under a key the SERVER mints as `<namespace>_YYYYMMDDTHHMMSSZ`, "
                      "and returns that key. Use this for journals and logs instead of hand-formatting a timestamp — an un-padded "
                      "hour sorts after a padded one and silently reorders the log. Two appends in the same second are "
                      "disambiguated, never overwritten: an append must not lose an entry.")
    :schema "{\"type\":\"object\",\"properties\":{\"namespace\":{\"type\":\"string\"},\"value\":{\"type\":\"string\"},\"handle\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}},\"required\":[\"namespace\",\"value\"]}"
    :fn (fn [a] (let [m (parse-args a)] (assoc-append (:namespace m) (:value m) m)))}
   {:name "assoc_count"
    :description (str "How many entries are under `prefix` (the whole store when blank) — cheap sanity before pulling anything.")
    :schema "{\"type\":\"object\",\"properties\":{\"prefix\":{\"type\":\"string\"},\"handle\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}}}"
    :fn (fn [a] (let [m (parse-args a)] (assoc-count (:prefix m) m)))}
   {:name "assoc_prune"
    :description (str "Keep the NEWEST `keep` entries under `prefix` and delete the rest (`keep` 0 empties it). This is what lets a "
                      "log live forever without growing forever. Returns a one-line report of how many went.")
    :schema "{\"type\":\"object\",\"properties\":{\"prefix\":{\"type\":\"string\"},\"keep\":{\"type\":\"integer\"},\"handle\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}},\"required\":[\"prefix\",\"keep\"]}"
    :fn (fn [a] (let [m (parse-args a)] (assoc-prune (:prefix m) (:keep m) m)))}
   {:name "assoc_write"
    :description (str "Write several entries and/or delete several keys in ONE TRANSACTION — all of it lands or none of it does. "
                      "`entries` = [{\"key\":\"…\",\"value\":\"…\"}], `delete` = [\"key\"]. Use it when an entry and the index "
                      "that points at it must agree: as two separate calls they can half-apply. Returns 'wrote N, deleted M'.")
    :schema "{\"type\":\"object\",\"properties\":{\"entries\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"},\"value\":{\"type\":\"string\"}},\"required\":[\"key\",\"value\"]}},\"delete\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"handle\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}}}"
    :fn (fn [a] (let [m (parse-args a)] (assoc-write (:entries m) (:delete m) m)))}
   {:name "assoc_rename"
    :description (str "Move the value at `from` to `to`. `redirect` (default true) leaves the OLD key holding a live `${to}` "
                      "reference, so every existing reference to the old name keeps resolving instead of going latent — use "
                      "redirect=false for a clean move. Renaming by hand (store + delete) is how a store accumulates dangling names.")
    :schema "{\"type\":\"object\",\"properties\":{\"from\":{\"type\":\"string\"},\"to\":{\"type\":\"string\"},\"redirect\":{\"type\":\"boolean\"},\"handle\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}},\"required\":[\"from\",\"to\"]}"
    :fn (fn [a] (let [m (parse-args a)] (assoc-rename (:from m) (:to m) (:redirect m) m)))}
   {:name "assoc_changed"
    :description (str "Entries whose `updated_at` is within [since, until] (ISO-8601, both optional) — the axis a time token in "
                      "the KEY cannot give you, because a rollup key is rewritten all day. `prefix`, `limit` (default 50), "
                      "`order` (default desc = most recently changed first), `values` as for assoc_span. Returns the same "
                      "{\"key\",\"updated_at\",\"value\"} array.")
    :schema "{\"type\":\"object\",\"properties\":{\"since\":{\"type\":\"string\"},\"until\":{\"type\":\"string\"},\"prefix\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"},\"order\":{\"type\":\"string\",\"enum\":[\"asc\",\"desc\"]},\"values\":{\"type\":\"boolean\"},\"handle\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}}}"
    :fn (fn [a] (let [m (parse-args a)] (assoc-changed m m)))}
   {:name "assoc_namespaces"
    :description (str "The distinct dotted prefixes in the store (up to `depth` segments, default 1) with a count each — the SHAPE "
                      "of what is stored, without pulling every key. Scans at most 20000 keys under `prefix`.")
    :schema "{\"type\":\"object\",\"properties\":{\"depth\":{\"type\":\"integer\"},\"prefix\":{\"type\":\"string\"},\"handle\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}}}"
    :fn (fn [a] (let [m (parse-args a)] (assoc-namespaces (:depth m) (:prefix m) m)))}])