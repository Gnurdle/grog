(ns grog-odoo.main
  "grog-odoo — a **strictly read-only** MCP server (over stdio) exposing Odoo
  ERP query tools.

  Talks to Odoo through its native XML-RPC API (`grog-odoo.xmlrpc`). The model
  can search/read records and run read-only SQL, but has **no way to modify the
  database** (create/write/unlink/call-method tools are removed, and SQL is
  confined to read-only statements).

  Multiple Odoo *instances* can be configured. The model can only ever select
  one of the pre-configured instance *names* (never a URL / endpoint), e.g.::

    GROG_ODOO_CONFIG=/path/to/instances.edn

  where the file is EDN (legacy JSON also accepted):

    {:instances [
        {:name \"stage\", :url \"https://exclave.cmsaero.com\",
         :db \"odoo18_stage\", :user \"admin\", :password \"...\",
         :sql {:type \"postgres\", :host \"127.0.0.1\", :port 5432,
               :db \"odoo18_stage\", :user \"odoo\", :password \"...\"}},
        {:name \"prod\", :url \"https://prod.example.com\",
         :db \"odoo18\", :user \"admin\", :password \"...\"}]}

  Backwards compatible single-instance env fallback:
    GROG_ODOO_URL / GROG_ODOO_DB / GROG_ODOO_USER / GROG_ODOO_PASSWORD

  Raw SQL runs through ``odoo_execute_sql`` and is *always* scoped to the
  currently selected instance's ``:sql`` backend (either a ``postgres`` JDBC
  connection or an ``odoo-method`` that executes SQL inside Odoo). There is no
  tool that accepts a database URL/host — the model cannot break out of the
  configured instance selection, and only read-only SQL statements are
  accepted."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [grog-odoo.xmlrpc :as xrpc])
  (:import [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
           [io.modelcontextprotocol.server McpServer]
           [io.modelcontextprotocol.server McpServerFeatures$AsyncToolSpecification]
           [io.modelcontextprotocol.spec
            McpSchema$ServerCapabilities McpSchema$Tool McpSchema$CallToolResult
            McpSchema$TextContent]
           [reactor.core.publisher Mono]
           [com.fasterxml.jackson.databind ObjectMapper]
           [java.sql DriverManager ResultSet]))

(set! *warn-on-reflection* true)

;; --- configuration ----------------------------------------------------------

(def ^{:private true} config*
  "Atom holding {:instances [..] :by-name {name inst}}; populated at startup."
  (atom nil))

(def ^{:private true} current*
  "Atom holding the name of the currently selected instance (nil = first)."
  (atom nil))

(def ^{:private true} auth*
  "Atom map instance-name -> {:url :db :uid :password} (auth cache)."
  (atom {}))

(defn- env! [k]
  (or (not-empty (str/trim (or (System/getenv k) "")))
      (throw (ex-info (str "Missing env " k " — set GROG_ODOO_* before calling Odoo tools") {}))))

(defn- normalize-url [url]
  (str/replace (str url) #"/+$" ""))

(defn- read-config-file!
  "Load instances from the config file at `path`. Accepts EDN (the current grog
  writer format, `*` `.edn`) or legacy JSON. Returns a vector of instance maps.
  `${ENV}` / `${ENV:-default}` references in string fields are interpolated from
  the process environment (so credentials can be injected per-process)."
  [path]
  (let [raw (slurp (java.io.File. path))
        data (try
               (edn/read-string {:eof nil} raw)
               (catch Exception _
                 (json/read-str raw :key-fn keyword)))
        raw-insts (get-in data [:instances])
        insts (if (sequential? raw-insts) (vec raw-insts) [])]
    (when-not (seq insts)
      (throw (ex-info "GROG_ODOO_CONFIG contains no instances" {:path path})))
    (letfn [(interp [v]
              (if (string? v)
                (str/replace v #"\$\{([^}]+)\}"
                             (fn [[_ spec]]
                               (let [[var-name default-val] (str/split spec #":-" 2)]
                                 (or (System/getenv var-name) default-val ""))))
                v))]
      (mapv (fn [i]
              (let [name (str (or (:name i) "default"))
                    url  (normalize-url (interp (or (:url i) (throw (ex-info (str "instance '" name "' missing :url") {})))))
                    db   (str (interp (or (:db i) (throw (ex-info (str "instance '" name "' missing :db") {})))))]
                {:name name
                 :url url
                 :db db
                 :user (str (interp (or (:user i) (throw (ex-info (str "instance '" name "' missing :user") {})))))
                 :password (str (or (interp (:password i)) ""))
                 :sql (when-let [s (:sql i)]
                        (into {} (map (fn [[k v]] [k (interp v)])) s))}))
            insts))))

(defn- load-config!
  "Populate `config*` from GROG_ODOO_CONFIG (EDN or legacy JSON) else legacy
  single-instance env vars. Returns the config map."
  []
  (let [cfg-file (not-empty (str/trim (or (System/getenv "GROG_ODOO_CONFIG") "")))
        instances (if cfg-file
                    (read-config-file! cfg-file)
                    [{:name "default"
                      :url  (normalize-url (env! "GROG_ODOO_URL"))
                      :db   (env! "GROG_ODOO_DB")
                      :user (env! "GROG_ODOO_USER")
                      :password (env! "GROG_ODOO_PASSWORD")
                      :sql  nil}])
        by-name (into {} (map (fn [i] [(:name i) i])) instances)]
    (when-not (seq instances)
      (throw (ex-info "No Odoo instances configured. Set GROG_ODOO_CONFIG or GROG_ODOO_* env vars." {})))
    (reset! config* {:instances instances :by-name by-name})
    (reset! current* nil)
    @config*))

(defn- active-instance
  "Return the currently selected instance map. Only names present in the
  pre-configured allowlist can ever become current; anything else throws — the
  model cannot specify an endpoint directly."
  []
  (let [{:keys [instances by-name]} @config*
        name (or @current* (:name (first instances)))]
    (or (get by-name name)
        (throw (ex-info "No Odoo instance selected — call odoo_use_instance first" {})))))

(defn- instance-auth!
  "Authenticate `inst` lazily (cached) and return {:url :db :uid :password}."
  [inst]
  (let [name (:name inst)]
    (if-let [c (get @auth* name)]
      c
      (let [uid (xrpc/xmlrpc-call! (:url inst) "common" "authenticate"
                                   [(:db inst) (:user inst) (:password inst) {}])]
        (when-not (pos? (long uid))
          (throw (ex-info (str "Odoo authentication failed for " name "/" (:user inst)) {})))
        (let [c {:url (:url inst) :db (:db inst) :uid (long uid) :password (:password inst) :name name}]
          (swap! auth* assoc name c)
          c)))))

(defn- execute-kw
  "Call `method` on Odoo `model` with `args`/`kwargs` on the active instance."
  [model method args kwargs]
  (let [{:keys [url db uid password]} (instance-auth! (active-instance))]
    (xrpc/xmlrpc-call! url "object" "execute_kw" [db uid password model method args kwargs])))

(defn- execute-kw-inst
  "Call `method` on Odoo `model` on an explicit instance map (used for the
  odoo-method SQL backend)."
  [inst model method args kwargs]
  (let [{:keys [url db uid password]} (instance-auth! inst)]
    (xrpc/xmlrpc-call! url "object" "execute_kw" [db uid password model method args kwargs])))

;; --- helpers ---------------------------------------------------------------

(defn- text-content [^String s] (McpSchema$TextContent. s))
(defn- text-result [^String s] (McpSchema$CallToolResult. [(text-content s)] false))
(defn- error-result [^String s] (McpSchema$CallToolResult. [(text-content s)] true))
(defn- ok [data] (json/write-str data))

(defn- kargs
  "Normalize MCP tool-argument keys to keywords (arguments arrive string-keyed)."
  [m]
  (into {} (map (fn [[k v]] [(keyword (name k)) v])) m))

(defn- tool
  "Build an async MCP tool spec (same helper as grog-imaging)."
  [{:keys [name description schema fn]}]
  (McpServerFeatures$AsyncToolSpecification.
    (McpSchema$Tool. name description schema)
    (reify java.util.function.BiFunction
      (apply [_ _exchange arguments]
        (Mono/create
          (reify java.util.function.Consumer
            (accept [_ sink]
              (try (.success sink (text-result (fn arguments)))
                   (catch Throwable t
                     (.success sink (error-result
                                     (str "Error executing tool " name ": "
                                          (or (:message (ex-data t)) (.getMessage t))))))))))))))

;; --- raw SQL ---------------------------------------------------------------

(def ^:private read-only-sql-pattern
  #"(?is)^\s*(?:\(?\s*)?(select|with|show|explain|describe|desc|values|table)\b")

(defn- read-only-sql? [sql]
  (boolean (re-find read-only-sql-pattern (str sql))))

(defn- rows->data [^ResultSet rs]
  (let [meta (.getMetaData rs)
        n (.getColumnCount meta)
        cols (mapv (fn [i] (.getColumnLabel meta i)) (range 1 (inc n)))]
    (loop [rows []]
      (if (.next rs)
        (recur (conj rows (into {}
                                (for [i (range 1 (inc n))]
                                  [(nth cols (dec i)) (let [v (.getObject rs i)]
                                                        (if (instance? java.sql.Timestamp v)
                                                          (str v)
                                                          v))]))))
        {:columns cols :rows rows :row-count (count rows)}))))

(defn- postgres-sql!
  "Run a read-only SQL query on the Postgres instance described by `sql-conf`.
  The connection is always the one from the selected instance's config — never
  caller-supplied. Non-read statements are refused before any connection is made."
  [sql-conf sql]
  (when-not (read-only-sql? sql)
    (throw (ex-info "Refusing non-read-only SQL — grog-odoo is strictly read-only." {})))
  (let [host (str (or (:host sql-conf) "127.0.0.1"))
        port (long (or (:port sql-conf) 5432))
        db   (or (:db sql-conf) (throw (ex-info "sql: postgres backend missing :db" {})))
        user (or (:user sql-conf) (throw (ex-info "sql: postgres backend missing :user" {})))
        password (str (or (:password sql-conf) ""))
        jdbc-url (str "jdbc:postgresql://" host ":" port "/" db)]
    (Class/forName "org.postgresql.Driver")
    (with-open [conn (DriverManager/getConnection jdbc-url user password)]
      (with-open [rs (.executeQuery (.createStatement conn) sql)]
        (merge {:read-only true} (rows->data rs))))))

(defn- odoo-method-sql!
  "Run raw SQL by calling a method on the selected Odoo instance (for instances
  that expose a SQL runner, e.g. a custom model method that executes on env.cr)."
  [inst sql]
  (let [sql-conf (:sql inst)
        model (or (:model sql-conf)
                  (throw (ex-info "sql: odoo-method backend missing :model" {})))
        method (or (:method sql-conf)
                   (throw (ex-info "sql: odoo-method backend missing :method" {})))]
    (execute-kw-inst inst model method [sql] {})))

(defn- run-sql!
  "Execute a read-only SQL query against the active instance's configured `:sql`
  backend. Statements that could change data are refused unconditionally — the
  model has no way to opt into writes."
  [sql]
  (let [inst (active-instance)
        sql-conf (:sql inst)]
    (when-not sql-conf
      (throw (ex-info (str "Instance '" (:name inst) "' has no :sql backend configured "
                           "(add a `sql` block to its entry in the instances config)") {})))
    (when-not (read-only-sql? sql)
      (throw (ex-info (str "Refusing non-read-only SQL (" (str/trim sql) ") — grog-odoo is strictly read-only.") {})))
    (let [backend (or (:type sql-conf) "postgres")]
      (case backend
        "postgres" (merge {:backend "postgres"} (postgres-sql! sql-conf sql))
        "odoo-method" (merge {:backend "odoo-method" :sql sql}
                             (odoo-method-sql! inst sql))
        (throw (ex-info (str "Unsupported sql backend '" backend "'") {}))))))

;; --- tools ------------------------------------------------------------------

(defn- build-tools
  "Build the full tool list. Reads the current config once so the instance enum
  reflects exactly the pre-configured instances."
  []
  (let [{:keys [instances by-name]} @config*
        instance-names (mapv :name instances)
        instance-summaries (mapv (fn [i] {:name (:name i) :url (:url i) :db (:db i)}) instances)]
    [{:name "odoo_list_instances"
      :description "List the pre-configured Odoo instances the model may use. Returns name/url/db only (never credentials)."
      :schema (json/write-str {:type :object :properties {} :required []})
      :fn (fn [_] (ok {:instances instance-summaries}))}

     {:name "odoo_use_instance"
      :description (str "Select which pre-configured Odoo instance to use for all subsequent calls. "
                        "You can ONLY pick one of these names; arbitrary endpoints are not allowed. "
                        "Available: " (str/join ", " instance-names))
      :schema (json/write-str {:type :object
                               :properties {:instance {:type :string
                                                       :enum instance-names}}
                               :required [:instance]})
      :fn (fn [a]
            (let [a (kargs a)
                  name (str (or (:instance a) ""))]
              (if-let [inst (get by-name name)]
                (do (reset! current* name)
                    (let [auth (instance-auth! inst)]
                      (ok {:instance name :uid (:uid auth) :db (:db inst) :authenticated true})))
                (throw (ex-info (str "Unknown Odoo instance '" name "'. Available: "
                                     (str/join ", " instance-names)) {})))))}

     {:name "odoo_authenticate"
      :description "Authenticate the currently selected Odoo instance (or the first configured one) and return the uid."
      :schema (json/write-str {:type :object :properties {} :required []})
      :fn (fn [_]
            (let [inst (active-instance)
                  auth (instance-auth! inst)]
              (ok {:instance (:name inst) :db (:db inst) :uid (:uid auth) :authenticated true})))}

     {:name "odoo_search_read"
      :description "Search Odoo records of `model` (e.g. res.partner, sale.order, account.move) matching `domain` (list of (field, operator, value) tuples). Operates on the currently selected instance. Returns matching records as JSON."
      :schema (json/write-str {:type :object
                               :properties {:model {:type :string}
                                            :domain {:type :array :items {:type :array}}
                                            :fields {:type :array :items {:type :string}}
                                            :limit {:type :integer}
                                            :offset {:type :integer}
                                            :order {:type :string}}
                               :required [:model :domain]})
      :fn (fn [a]
            (let [a (kargs a)
                  kwargs (cond-> {}
                           (:fields a) (assoc :fields (vec (:fields a)))
                           (:limit a)  (assoc :limit (long (:limit a)))
                           (:offset a) (assoc :offset (long (:offset a)))
                           (:order a)  (assoc :order (:order a)))]
              (ok (execute-kw (:model a) "search_read" [(:domain a)] kwargs))))}

     {:name "odoo_get_fields"
      :description "Return field metadata for `model` (e.g. res.partner) on the currently selected instance."
      :schema (json/write-str {:type :object
                               :properties {:model {:type :string} :attributes {:type :array :items {:type :string}}}
                               :required [:model]})
      :fn (fn [a] (let [a (kargs a)]
                    (ok (execute-kw (:model a) "fields_get" []
                                    {:attributes (or (:attributes a) ["string" "type" "required" "help"])}))))}

     {:name "odoo_execute_sql"
      :description (str "Run a read-only SQL query against the currently selected Odoo instance's configured SQL backend. "
                        "The connection is determined solely by the selected instance's `sql` config — you cannot choose a database/endpoint. "
                        "Only read-only statements are allowed: SELECT / WITH / SHOW / EXPLAIN / DESCRIBE / VALUES / TABLE. "
                        "This MCP is strictly read-only: any statement that could change data (INSERT/UPDATE/DELETE/DDL/…) is refused."
                        " Returns columns+rows.")
      :schema (json/write-str {:type :object
                               :properties {:sql {:type :string}}
                               :required [:sql]})
      :fn (fn [a]
            (let [a (kargs a)
                  sql (str (or (:sql a) ""))]
              (when (str/blank? sql)
                (throw (ex-info "Missing required :sql" {})))
              (ok (merge {:instance (:name (active-instance)) :sql sql}
                         (run-sql! sql)))))}
     ]))

;; --- server ----------------------------------------------------------------

(defn mcp-server []
  (load-config!)
  (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "grog-odoo" "0.3.0")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder) (.tools true) (.build)))
                   (.build))]
    (doseq [t (build-tools)]
      (-> (.addTool server (tool t)) (.subscribe)))
    server))

(defn -main [& _args]
  (mcp-server)
  (loop [] (Thread/sleep 1000) (recur)))