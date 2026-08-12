(ns grog.assoc-memory
  "LLM tools implementing **associative memory** backed by a SQLite database file in the repo.

  Think of it as a persistent key -> blob map the model can write to and read back across turns/sessions.
  A single table `memory(key TEXT PRIMARY KEY, value BLOB)` is used; blobs are stored as UTF-8 bytes so
  plain text and JSON strings round-trip cleanly.

  The DB file lives at `<repo>/grog-assoc-memory/assoc.sqlite` by default, but every tool accepts an
  optional `:db_path` (any relative path, absolute or repo-root-relative) to select a different
  SQLite file, so an ad-hoc database can be created anywhere.

  Exposed tools:
    - assoc_store   store a blob value for a key (create/overwrite)
    - assoc_get     retrieve the blob stored under a key
    - assoc_keys    enumerate all keys
    - assoc_search  search keys by regular expression"
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.config :as cfg])
  (:import [java.sql Connection DriverManager ResultSet]
           [java.util.regex Pattern]))

(def ^:private default-store-name "assoc")
(def ^:private default-db-rel (str default-store-name ".db"))

(def ^:private create-sql
  "CREATE TABLE IF NOT EXISTS memory (key TEXT PRIMARY KEY, value BLOB NOT NULL)")

(defn- parse-args [arguments]
  (cond (map? arguments) arguments
        (string? arguments) (try (json/parse-string arguments true)
                                 (catch Exception _ {}))
        :else {}))

(defn- pick [m & ks]
  (reduce (fn [_ k]
            (when-let [v (or (get m k) (get m (name k)))]
              (reduced v)))
          nil ks))

(defn- safe-store-name?
  "A store name must be a single safe filename segment (letters/digits/._-), so that
  `<name>.db` stays a flat file in the store root — e.g. `notes` -> `notes.db`."
  [s]
  (and (seq s)
       (re-matches #"[A-Za-z0-9._-]+" s)
       (not (str/starts-with? s "."))
       (not (str/ends-with? s "."))
       (not (str/includes? s ".."))))

(defn- store-db-rel
  "Resolve a store spec to a relative `<name>.db` path.
  Precedence: `:name` (simple store name -> `<name>.db`, multiple stores supported),
  then `:db_path` (explicit path), then the default store `assoc.db`."
  ^String [m]
  (if-let [n (pick m :name :store :Namespace)]
    (do (when-not (safe-store-name? n)
          (throw (ex-info (str "invalid store name \"" n "\" — use only letters, digits, '_', '-', '.'; a store maps to <name>.db")
                          {:name n})))
        (str n ".db"))
    (or (pick m :db_path :dbPath :db)
        default-db-rel)))

(defn- db-file
  "Absolute `File` for the store addressed by this call (relative paths resolve
  against the repo root)."
  ^java.io.File [m]
  (let [rel (store-db-rel m)
        f (io/file rel)]
    (if (.isAbsolute f)
      f
      (.getCanonicalFile (io/file (cfg/repo-root) rel)))))

(defn- required-key [m]
  (let [k (pick m :key :Key)]
    (when-not k
      (throw (ex-info "key is required" {})))
    (str k)))

(defn- required-regex [m]
  (let [rx (pick m :regex :pattern)]
    (when-not rx
      (throw (ex-info "regex is required" {})))
    (str rx)))

(defn- opt-limit [m]
  (let [x (pick m :limit :Limit)]
    (cond (number? x) (max 1 (long x))
          :else nil)))

(defn- open-connection
  "Open (creating parent dirs and the DB if needed) and ensure the schema exists."
  ^Connection [^java.io.File f]
  (let [^java.io.File parent (.getParentFile f)]
    (when parent (.mkdirs parent)))
  (try
    (Class/forName "org.sqlite.JDBC")
    (catch ClassNotFoundException _))
  (let [^Connection conn (DriverManager/getConnection (str "jdbc:sqlite:" (.getAbsolutePath f)))]
    (try
      (with-open [st (.createStatement conn)]
        (.execute st create-sql))
      conn
      (catch Throwable e
        (try (.close conn) (catch Throwable _))
        (throw e)))))

(defn- resultset-keys-lazy
  "Lazy seq of `key` strings from `rs` (consume inside the `with-open` scope)."
  ^clojure.lang.ISeq [^ResultSet rs]
  (lazy-seq
   (when (.next rs)
     (cons (.getString rs 1) (resultset-keys-lazy rs)))))

;; ---------------------------------------------------------------------------
;; Tool specs
;; ---------------------------------------------------------------------------

(defn tool-specs
  "LLM function tools for associative memory (SQLite).
  A store is addressed by a simple `:name` -> the file `<name>.db` in the store root
  (default name \"assoc\" -> \"assoc.db\"). Multiple named stores are supported, e.g. `notes`,
  `contacts`, `facts` — each lives in its own <name>.db."
  []
  [{:type "function"
    :function
    {:name "assoc_store"
     :description (str "Store a blob value under a `key` in an associative-memory store (a SQLite file "
                       "<name>.db, default " default-db-rel "). Creates or overwrites the key. "
                       "`value` is any text or JSON string; stored as bytes and returned unchanged by assoc_get. "
                       "Pick a store with :name (each name is its own <name>.db; e.g. notes, facts) or an explicit :db_path.")
     :parameters {:type "object"
                  :required ["key" "value"]
                  :properties {:name {:type "string"
                                      :description (str "Store name -> <name>.db (default \"" default-store-name "\"). Use different names for separate stores.")}
                               :key {:type "string"
                                     :description "Key to store the value under (create or overwrite)."}
                               :value {:type "string"
                                       :description "Blob value to store (plain text or JSON string)."}
                               :db_path {:type "string"
                                         :description "Optional explicit SQLite path instead of :name."}}}}}
   {:type "function"
    :function
    {:name "assoc_get"
     :description (str "Retrieve the blob stored under a `key` from an associative-memory store (a SQLite file "
                       "<name>.db, default " default-db-rel "). Returns JSON with :found true and :value, "
                       "or :found false if absent. Pick a store with :name or :db_path.")
     :parameters {:type "object"
                  :required ["key"]
                  :properties {:name {:type "string"
                                      :description (str "Store name -> <name>.db (default \"" default-store-name "\").")}
                               :key {:type "string"}
                               :db_path {:type "string"
                                         :description "Optional explicit SQLite path instead of :name."}}}}}
   {:type "function"
    :function
    {:name "assoc_keys"
     :description (str "Enumerate all keys in an associative-memory store (a SQLite file <name>.db, "
                       "default " default-db-rel "), sorted alphabetically. Optional positive :limit caps the count. "
                       "Pick a store with :name or :db_path.")
     :parameters {:type "object"
                  :properties {:name {:type "string"
                                      :description (str "Store name -> <name>.db (default \"" default-store-name "\").")}
                               :limit {:type "integer"
                                       :description "Optional max number of keys to return."}
                               :db_path {:type "string"
                                         :description "Optional explicit SQLite path instead of :name."}}}}}
   {:type "function"
    :function
    {:name "assoc_search"
     :description (str "Search the keys of an associative-memory store (a SQLite file <name>.db, "
                       "default " default-db-rel ") by a Java regular expression `regex`; returns all matching keys, "
                       "sorted alphabetically. Optional positive :limit caps the count. Pick a store with :name or :db_path.")
     :parameters {:type "object"
                  :required ["regex"]
                  :properties {:name {:type "string"
                                      :description (str "Store name -> <name>.db (default \"" default-store-name "\").")}
                               :regex {:type "string"
                                       :description "Java regular expression to match against key names."}
                               :limit {:type "integer"
                                       :description "Optional max number of keys to return."}
                               :db_path {:type "string"
                                         :description "Optional explicit SQLite path instead of :name."}}}}}])

(defn tool-log-summary
  "Safe read-only summary of a call's args for the magenta `grog: tool …` invocation log."
  [tool-name arguments]
  (let [m (parse-args arguments)]
    (case tool-name
      "assoc_store" (select-keys m [:name :key :db_path :dbPath :db])
      "assoc_get" (select-keys m [:name :key :db_path :dbPath :db])
      "assoc_keys" (select-keys m [:name :limit :db_path :dbPath :db])
      "assoc_search" (select-keys m [:name :regex :limit :db_path :dbPath :db])
      (select-keys m [:name :key :regex :limit :db_path]))))

;; ---------------------------------------------------------------------------
;; Tool implementations
;; ---------------------------------------------------------------------------

(defn run-assoc-store!
  [arguments]
  (try
    (let [m (parse-args arguments)
          k (required-key m)
          raw (or (pick m :value :Value) "")
          v (if (string? raw) raw (json/generate-string raw))
          bytes (.getBytes v "UTF-8")
          db (db-file m)]
      (with-open [conn (open-connection db)]
        (with-open [ps (.prepareStatement conn
                        (str "INSERT INTO memory(key,value) VALUES(?,?) "
                             "ON CONFLICT(key) DO UPDATE SET value=excluded.value"))]
          (.setString ps 1 k)
          (.setBytes ps 2 bytes)
          (.executeUpdate ps)))
      (json/generate-string {:ok true :key k :bytes (count bytes) :db (.getPath db)}))
    (catch Exception e
      (json/generate-string {:error (.getMessage e)}))))

(defn run-assoc-get!
  [arguments]
  (try
    (let [m (parse-args arguments)
          k (required-key m)
          db (db-file m)]
      (with-open [conn (open-connection db)]
        (with-open [ps (.prepareStatement conn "SELECT value FROM memory WHERE key=?")]
          (.setString ps 1 k)
          (with-open [rs (.executeQuery ps)]
            (if (.next rs)
              (let [^bytes bytes (.getBytes rs 1)]
                (json/generate-string {:found true
                                       :key k
                                       :bytes (count bytes)
                                       :value (String. bytes "UTF-8")}))
              (json/generate-string {:found false :key k :db (.getPath db)}))))))
    (catch Exception e
      (json/generate-string {:error (.getMessage e)}))))

(defn run-assoc-keys!
  [arguments]
  (try
    (let [m (parse-args arguments)
          lim (opt-limit m)
          db (db-file m)]
      (with-open [conn (open-connection db)]
        (with-open [ps (.prepareStatement conn "SELECT key FROM memory ORDER BY key")]
          (with-open [rs (.executeQuery ps)]
            (let [keys (vec (resultset-keys-lazy rs))
                  out (vec (if lim (take lim keys) keys))]
              (json/generate-string {:db (.getPath db)
                                     :total (count keys)
                                     :limit lim
                                     :count (count out)
                                     :keys out}))))))
    (catch Exception e
      (json/generate-string {:error (.getMessage e)}))))

(defn run-assoc-search!
  [arguments]
  (try
    (let [m (parse-args arguments)
          rx (required-regex m)
          pat (Pattern/compile rx)
          lim (opt-limit m)
          db (db-file m)]
      (with-open [conn (open-connection db)]
        (with-open [ps (.prepareStatement conn "SELECT key FROM memory ORDER BY key")]
          (with-open [rs (.executeQuery ps)]
            (let [all (vec (resultset-keys-lazy rs))
                  matches (filter #(re-find pat ^String %) all)
                  out (vec (if lim (take lim matches) matches))]
              (json/generate-string {:db (.getPath db)
                                     :regex rx
                                     :total_matches (count (filter #(re-find pat ^String %) all))
                                     :limit lim
                                     :count (count out)
                                     :keys out}))))))
    (catch Exception e
      (json/generate-string {:error (.getMessage e)}))))
