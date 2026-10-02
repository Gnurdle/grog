(ns grog-odoo.main
  "grog-odoo — an MCP server (over stdio) exposing Odoo ERP tools.

  Talks to Odoo through its native XML-RPC API (`grog-odoo.xmlrpc`). Record
  search/read is always available. SQL goes through the **Select-O-Matic**
  addon (`select.o.matic.wizard`) — also over the API — so grog never opens a
  database connection of its own and needs no database credentials.

  INSTANCES. Several Odoo instances can be configured, and the model can only
  ever name one of the pre-configured instance *names* (never a URL/endpoint):

    ~/.config/grog/odoo.edn
      {:config \"~/.config/grog/odoo-instances.edn\"}   → load an instances file
      {:url ... :db ... :user ... :password-secret ...} → single \"default\" instance

  where the instances file is EDN (legacy JSON also accepted):

    {:instances [
        {:name \"stage\", :url \"https://exclave.cmsaero.com\",
         :db \"odoo18_stage\", :user \"admin\",
         :password-secret \"ODOO_STAGE_PASSWORD\",
         :allow-write false},
        {:name \"prod\", :url \"https://prod.example.com\",
         :db \"odoo18\", :user \"admin\",
         :password-secret \"ODOO_PROD_PASSWORD\",
         :allow-write false}]}

  * `:password-secret` names an ACCOUNT in grog's secret store — the password
    itself is NEVER in this file. Set it with `/secret set ODOO_STAGE_PASSWORD
    <value>` (OS keyring; grog's `secrets.edn` is the headless fallback). This
    server reads the store ITSELF rather than receiving the value in its
    environment: everything in an MCP server's env map is written verbatim into
    the generated ECA config, so an env-injected password would sit on disk in
    cleartext.
  * A missing secret THROWS, naming the instance + account + the exact fix. It
    never authenticates with a blank password — that fails later, silently.
  * `:password` (a literal, or `${ENV}`) still works for anyone not using the
    store. `:password-secret` wins when both are present.
  * `:allow-write` (default FALSE) is the per-instance write switch. With it
    false, any statement that could modify data is refused before Odoo is even
    called. With it true, mutating SQL is passed through to Select-O-Matic
    (which still requires the SUPERUSER account and an explicit confirmation).
  * There is no `:sql` block any more, and no JDBC connection: the direct
    database path was removed in favour of Select-O-Matic's API.
  * With MORE THAN ONE instance configured, EVERY call must name one with the
    `instance` argument — there is no \"first configured\" fallback, so a bare
    call can never silently hit the wrong database. With exactly one instance
    the name is optional (it is unambiguous).
  ${ENV} / ${ENV:-default} interpolation inside the config is honored.

  Requires the `select_o_matic` addon on the Odoo instance, and the configured
  Odoo user to be in its `group_select_o_matic` group."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog-odoo.xmlrpc :as xrpc])
  (:import [com.github.javakeyring Keyring]
           [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
           [io.modelcontextprotocol.server McpServer]
           [io.modelcontextprotocol.server McpServerFeatures$AsyncToolSpecification]
           [io.modelcontextprotocol.spec
            McpSchema$ServerCapabilities McpSchema$Tool McpSchema$CallToolResult
            McpSchema$TextContent]
           [reactor.core.publisher Mono]
           [com.fasterxml.jackson.databind ObjectMapper]))

(set! *warn-on-reflection* true)

;; --- configuration ----------------------------------------------------------

(def ^{:private true} config*
  "Atom holding {:instances [..] :by-name {name inst}}; populated at startup."
  (atom nil))

(def ^{:private true} auth*
  "Atom map instance-name -> {:url :db :uid :password} (auth cache)."
  (atom {}))

(defn- interp
  "Interpolate `${ENV}` / `${ENV:-default}` references in string fields from the
  process environment (so credentials can be injected per-process)."
  [v]
  (if (string? v)
    (str/replace v #"\$\{([^}]+)\}"
                 (fn [[_ spec]]
                   (let [[var-name default-val] (str/split spec #":-" 2)]
                     (or (System/getenv var-name) default-val ""))))
    v))

(defn- normalize-url [url]
  (str/replace (str url) #"/+$" ""))

(defn- allowed-to-write?
  "The per-instance write switch. Default FALSE: an instance is read-only until
  it is explicitly marked otherwise."
  [i]
  (boolean (:allow-write i)))

;; --- secrets ----------------------------------------------------------------
;; Credentials live in grog's secret store, not in the instances file. Read the
;; OS keyring directly — the pattern grog-gitlab and grog-search already use —
;; rather than receiving the password through the process env: everything in an
;; MCP server's env map is written VERBATIM into
;; ~/.config/grog/sessions/<project>.json, so an env-injected credential ends up
;; in cleartext on disk.

(def ^:private ^String keyring-service "grog")

(def ^:private keyring-read-timeout-ms 4000)

(defn- keyring-secret
  "Read `account` from the OS keyring (service \"grog\"). Time-bounded: without a
  working Secret Service / D-Bus (SSH, containers) `Keyring/create` can block
  forever. nil when absent, unsupported, or timed out."
  ^String [^String account]
  (when-not (str/blank? (str account))
    (let [f (future
              (try
                (with-open [^Keyring kr (Keyring/create)]
                  (some-> (.getPassword kr keyring-service (str account))
                          str str/trim not-empty))
                (catch Throwable _ nil)))
          v (deref f keyring-read-timeout-ms ::timeout)]
      (when-not (= ::timeout v) v))))

(defn- secrets-file
  "grog's file fallback: `<config-home>/secrets.edn`, an EDN map of
  {account password}. Mirrors grog.secrets/secrets-file so a headless Linux box
  — where the keyring backend is unreachable — still works."
  ^java.io.File []
  (let [home (or (some-> (System/getenv "XDG_CONFIG_HOME") str not-empty)
                 (str (or (some-> (System/getenv "HOME") str not-empty) "~")
                      "/.config"))]
    (io/file home "grog" "secrets.edn")))

(defn- file-secret ^String [^String account]
  (try
    (let [f (secrets-file)]
      (when (.exists f)
        (some-> (edn/read-string {:eof nil} (slurp f :encoding "UTF-8"))
                (get account) str str/trim not-empty)))
    (catch Throwable _ nil)))

(defn- lookup-secret
  "OS keyring first, then grog's secrets file. nil when neither has it."
  ^String [^String account]
  (or (keyring-secret account) (file-secret account)))

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
      (throw (ex-info (str "Odoo instances file contains no instances: " path) {:path path})))
    (mapv (fn [i]
            (let [name (str (or (:name i) "default"))
                  url  (normalize-url (interp (or (:url i) (throw (ex-info (str "instance '" name "' missing :url") {})))))
                  db   (str (interp (or (:db i) (throw (ex-info (str "instance '" name "' missing :db") {})))))]
              {:name name
               :url url
               :db db
               :user (str (interp (or (:user i) (throw (ex-info (str "instance '" name "' missing :user") {})))))
               :password (str (or (interp (:password i)) ""))
               ;; the secret-store ACCOUNT NAME, not the secret itself
               :password-secret (some-> (:password-secret i) str str/trim not-empty)
               :allow-write (allowed-to-write? i)}))
          insts)))

(defn- odoo-config-file []
  (io/file (or (some-> (System/getenv "HOME") str not-empty) "~")
           ".config/grog/odoo.edn"))

(defn- load-config-file!
  "Load ~/.config/grog/odoo.edn. Returns {} when missing (normal — we fall back
  to the default instances file) or when it fails to parse."
  []
  (let [f (odoo-config-file)]
    (if (.exists f)
      (try (edn/read-string (slurp f))
           (catch Exception e (binding [*out* *err*] (println "odoo config load error:" (.getMessage e))) {}))
      {})))

(defn- load-config!
  "Populate `config*` from ~/.config/grog/odoo.edn:
      {:config \"path\"}                       → load that instances file
      {:url ... :db ... :user ... :password ...} → single \"default\" instance
    If odoo.edn is missing or carries no `:config`, the default instances file
    ~/.config/grog/odoo-instances.edn is used (legacy GROG_ODOO_CONFIG behavior).
    ${ENV} interpolation inside the instances file is still honored."
  []
  (let [cfg (load-config-file!)
        home (or (System/getenv "HOME") (System/getProperty "user.home"))
        instances
        (cond
          (and (not (:config cfg))
               (or (:url cfg) (:db cfg) (:user cfg) (:password cfg)))
          (let [missing (remove (fn [k] (not (str/blank? (str (get cfg k)))))
                                [:url :db :user])]
            (when (seq missing)
              (throw (ex-info (str "odoo.edn single-instance config is missing: "
                                   (str/join ", " (map name missing))) {})))
            [{:name "default"
              :url  (normalize-url (interp (:url cfg)))
              :db   (str (interp (:db cfg)))
              :user (str (interp (:user cfg)))
              :password (str (or (interp (:password cfg)) ""))
              :allow-write (allowed-to-write? cfg)}])

          :else
          (let [cfg-file (not-empty (str/trim (str (or (:config cfg) "~/.config/grog/odoo-instances.edn"))))
                cfg-file (str/replace-first cfg-file #"^~(?=/|$)" home)]
            (read-config-file! cfg-file)))
        by-name (into {} (map (fn [i] [(:name i) i])) instances)]
    (when-not (seq instances)
      (throw (ex-info "No Odoo instances configured. Add :config (or :url/:db/:user/:password) to ~/.config/grog/odoo.edn." {})))
    (reset! config* {:instances instances :by-name by-name})
    @config*))

(defn- instance-names []
  (mapv :name (:instances @config*)))

(defn- resolve-instance
  "Pick the instance for THIS call.

  With more than one instance configured the caller MUST name it: there is no
  'first configured' fallback, because a bare call would silently hit whichever
  instance happened to be listed first. That is the rule — `odoo …` never does
  anything until an instance is stated explicitly. With exactly one instance
  configured the name is optional, since it is unambiguous."
  [a]
  (let [{:keys [instances by-name]} @config*
        want (some-> (:instance a) str str/trim not-empty)]
    (cond
      want
      (or (get by-name want)
          (throw (ex-info (str "Unknown Odoo instance '" want "'. Available: "
                               (str/join ", " (map :name instances))) {})))

      (= 1 (count instances))
      (first instances)

      :else
      (throw (ex-info (str "This grog has " (count instances)
                           " Odoo instances configured, so every call must name one explicitly "
                           "with the `instance` argument. Available: "
                           (str/join ", " (map :name instances)))
                      {:instances (map :name instances)})))))

(defn- instance-password
  "The password to authenticate `inst` with: the per-instance secret-store entry
  when `:password-secret` is configured, otherwise the literal / `${ENV}`
  `:password`.

  Throws — naming the instance and the account — rather than authenticating with
  a blank password, which fails later and silently. Resolution is LAZY (at auth
  time, not boot), so a missing or unreachable secret can never break tool
  registration."
  ^String [inst]
  (if-let [acct (:password-secret inst)]
    (or (lookup-secret acct)
        (throw (ex-info (str "Instance '" (:name inst) "': no secret for account '" acct
                             "' in the OS keyring (service '" keyring-service "') "
                             "or in " (.getPath (secrets-file)) ". Set it with:  "
                             "/secret set " acct " <value>")
                        {:instance (:name inst) :secret acct})))
    (let [p (str (:password inst))]
      (when (str/blank? p)
        (throw (ex-info (str "Instance '" (:name inst) "' has no credentials: configure "
                             ":password-secret (preferred) or :password.")
                        {:instance (:name inst)})))
      p)))

(defn- instance-auth!
  "Authenticate `inst` lazily (cached) and return {:url :db :uid :password}."
  [inst]
  (let [name (:name inst)]
    (if-let [c (get @auth* name)]
      c
      (let [pw (instance-password inst)
            uid (xrpc/xmlrpc-call! (:url inst) "common" "authenticate"
                                   [(:db inst) (:user inst) pw {}])]
        (when-not (pos? (long uid))
          (throw (ex-info (str "Odoo authentication failed for " name "/" (:user inst)) {})))
        (let [c {:url (:url inst) :db (:db inst) :uid (long uid) :password pw :name name}]
          (swap! auth* assoc name c)
          c)))))

(defn- execute-kw
  "Call `method` on Odoo `model` with `args`/`kwargs` on instance `inst`."
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
                     ;; Surface an XML-RPC fault's faultString: without it an Odoo
                     ;; permission error degrades to the useless "Odoo XML-RPC
                     ;; fault" and the caller cannot tell a bad query from a
                     ;; missing group on the target.
                     (.success sink (error-result
                                     (str "Error executing tool " name ": "
                                          (or (:message (ex-data t))
                                              (:faultString (ex-data t))
                                              (.getMessage t))))))))))))))

;; --- SQL, via the Select-O-Matic addon (no direct database access) ----------

(def ^:private read-only-sql-pattern
  #"(?is)^\s*(?:\(?\s*)?(select|with|show|explain|describe|desc|values|table)\b")

(defn- read-only-sql? [sql]
  (boolean (re-find read-only-sql-pattern (str sql))))

(defn- select-o-matic!
  "Run `sql` on `inst` through the Select-O-Matic wizard
  (`select.o.matic.wizard`), over the Odoo API.

  This is the ONLY way grog reaches SQL: no JDBC, no database host/port, no
  database credentials — just the instance's normal Odoo login, and Select-O-Matic's
  own guards (single statement, no dangerous functions, row ceiling, statement
  timeout, read-only statements rolled back in a savepoint). Mutating statements
  need the superuser account AND `confirm_write`, which we only send when the
  instance is marked `:allow-write true`."
  [inst sql row-limit confirm-write?]
  (let [{:keys [url db uid password]} (instance-auth! inst)
        model "select.o.matic.wizard"
        call (fn [method args kwargs]
               (xrpc/xmlrpc-call! url "object" "execute_kw" [db uid password model method args kwargs]))
        wid (call "create" [{:sql_text sql
                             :row_limit (int row-limit)
                             :confirm_write (boolean confirm-write?)}] {})
        _ (call "action_run" [[wid]] {})
        [rec] (call "read" [[wid] ["status" "error" "result_json" "has_result"]] {})]
    rec))

(defn- run-sql!
  "Execute SQL against `inst` via Select-O-Matic.

  A statement that could modify data is refused *before* Odoo is called unless
  the instance is marked `:allow-write true` — the write switch is per instance,
  not a global setting."
  [inst sql row-limit]
  (let [write? (not (read-only-sql? sql))
        name (:name inst)]
    (when (and write? (not (:allow-write inst)))
      (throw (ex-info (str "Instance '" name "' is configured read-only (:allow-write false), "
                           "so a statement that could modify data is refused. "
                           "Set :allow-write true on that instance to permit writes.")
                      {:instance name :allow-write false})))
    (let [rec (select-o-matic! inst sql row-limit write?)
          status (:status rec)
          error (:error rec)
          payload (try (some-> (:result_json rec) str not-empty (json/read-str :key-fn keyword))
                       (catch Exception _ nil))]
      (when (and error (not (str/blank? (str error))))
        (throw (ex-info (str "Select-O-Matic refused the statement: " error)
                        {:instance name :status status})))
      (merge {:instance name
              :allow-write (:allow-write inst)
              :status status
              :has-result (:has_result rec)
              :sql sql}
             (when payload
               {:columns (:columns payload)
                :rows (:rows payload)
                :row-count (count (:rows payload))})))))

;; --- tools ------------------------------------------------------------------

(defn- instance-prop
  "The `instance` tool argument. Listed as an enum of the configured names so
  the model cannot invent an endpoint."
  [names]
  {:type :string
   :enum names
   :description (str "Which configured Odoo instance to act on. REQUIRED whenever more than one instance "
                     "is configured — a call without it is refused. Available: " (str/join ", " names))})

(defn build-tools
  "Build the full tool list. Reads the current config once so the instance enum
  reflects exactly the pre-configured instances.

  Loads the config HERE if nothing has yet: the bundle (`grog_mcp.main`) calls
  this fn directly through `requiring-resolve` and never runs this server's
  `-main`/`mcp-server` — the only other place `load-config!` is called. Without
  this guard the tools come back with an EMPTY instance list under the bundle
  (`{\"instances\": [], \"instance-required\": false}`)."
  []
  (when (nil? @config*) (load-config!))
  (let [{:keys [instances]} @config*
        names (mapv :name instances)
        multi? (> (count instances) 1)
        instance-summaries (mapv (fn [i] {:name (:name i) :url (:url i) :db (:db i)
                                          :allow-write (:allow-write i)})
                                 instances)]
    [{:name "odoo_list_instances"
      :description (str "List the pre-configured Odoo instances. Returns name/url/db/allow-write only "
                        "(never credentials). "
                        (when multi? (str "This grog has " (count instances)
                                          " instances, so every other odoo_* call must pass `instance` explicitly.")))
      :schema (json/write-str {:type :object :properties {} :required []})
      :fn (fn [_] (ok {:instances instance-summaries
                       :instance-required multi?}))}

     {:name "odoo_authenticate"
      :description "Authenticate an Odoo instance and return the uid. Name the instance when more than one is configured."
      :schema (json/write-str {:type :object
                               :properties {:instance (instance-prop names)}
                               :required (if multi? [:instance] [])})
      :fn (fn [a]
            (let [inst (resolve-instance (kargs a))
                  auth (instance-auth! inst)]
              (ok {:instance (:name inst) :db (:db inst) :uid (:uid auth)
                   :allow-write (:allow-write inst) :authenticated true})))}

     {:name "odoo_search_read"
      :description "Search Odoo records of `model` (e.g. res.partner, sale.order, account.move) matching `domain` (list of (field, operator, value) tuples). Returns matching records as JSON. Name the instance when more than one is configured."
      :schema (json/write-str {:type :object
                               :properties {:instance (instance-prop names)
                                            :model {:type :string}
                                            :domain {:type :array :items {:type :array}}
                                            :fields {:type :array :items {:type :string}}
                                            :limit {:type :integer}
                                            :offset {:type :integer}
                                            :order {:type :string}}
                               :required (if multi? [:instance :model :domain] [:model :domain])})
      :fn (fn [a]
            (let [a (kargs a)
                  inst (resolve-instance a)
                  kwargs (cond-> {}
                           (:fields a) (assoc :fields (vec (:fields a)))
                           (:limit a)  (assoc :limit (long (:limit a)))
                           (:offset a) (assoc :offset (long (:offset a)))
                           (:order a)  (assoc :order (:order a)))]
              (ok (execute-kw inst (:model a) "search_read" [(:domain a)] kwargs))))}

     {:name "odoo_get_fields"
      :description "Return field metadata for `model` (e.g. res.partner). Name the instance when more than one is configured."
      :schema (json/write-str {:type :object
                               :properties {:instance (instance-prop names)
                                            :model {:type :string}
                                            :attributes {:type :array :items {:type :string}}}
                               :required (if multi? [:instance :model] [:model])})
      :fn (fn [a]
            (let [a (kargs a)
                  inst (resolve-instance a)]
              (ok (execute-kw inst (:model a) "fields_get" []
                              {:attributes (or (:attributes a) ["string" "type" "required" "help"])}))))}

     {:name "odoo_execute_sql"
      :description (str "Run SQL against an Odoo instance through the Select-O-Matic addon (select.o.matic.wizard) — "
                        "over the Odoo API. There is no direct database connection and no database credentials are used. "
                        "Read statements (SELECT/WITH/SHOW/EXPLAIN/…) always work. A statement that could modify data is "
                        "refused unless the instance is marked :allow-write true, and even then Select-O-Matic requires the "
                        "superuser account. Name the instance when more than one is configured.")
      :schema (json/write-str {:type :object
                               :properties {:instance (instance-prop names)
                                            :sql {:type :string}
                                            :row-limit {:type :integer}}
                               :required (if multi? [:instance :sql] [:sql])})
      :fn (fn [a]
            (let [a (kargs a)
                  inst (resolve-instance a)
                  sql (str (or (:sql a) ""))
                  limit (long (or (:row-limit a) 200))]
              (when (str/blank? sql)
                (throw (ex-info "Missing required :sql" {})))
              (ok (run-sql! inst sql limit))))}
     ]))

;; --- server ----------------------------------------------------------------

(defn mcp-server []
  (load-config!)
  (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "grog-odoo" "0.4.0")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder) (.tools true) (.build)))
                   (.build))]
    (doseq [t (build-tools)]
      (-> (.addTool server (tool t)) (.subscribe)))
    server))

(defn -main [& _args]
  (mcp-server)
  (loop [] (Thread/sleep 1000) (recur)))
