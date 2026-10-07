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
  * `:password-secret` is REQUIRED and is the ONLY credential source. There is
    no `:password` (literal or `${ENV}`) fallback, no env var and no credential
    file: one instance, one named account in the store. Because several Odoo
    instances can be configured without bound, the account name has to be
    carried in the config rather than derived from it.
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
  ${ENV} / ${ENV:-default} interpolation is honored for the non-secret fields
  (`:url` / `:db` / `:user`) only — never for a credential.

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
  "Interpolate `${ENV}` / `${ENV:-default}` references in the non-secret string
  fields of an instance (`:url` / `:db` / `:user`) from the process
  environment. Credentials are NEVER resolved this way: they are named in the
  config (`:password-secret`) and read from grog's secret store by
  `instance-password`."
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

(defn- config-home-dir
  "grog's config home: $GROG_CONFIG_HOME, else $XDG_CONFIG_HOME/grog, else
  ~/.config/grog. Mirrors grog.platform/config-home-dir, so the fallback file is
  found even on a relocated config home."
  ^String []
  (or (some-> (System/getenv "GROG_CONFIG_HOME") str str/trim not-empty)
      (if-let [xdg (some-> (System/getenv "XDG_CONFIG_HOME") str str/trim not-empty)]
        (str xdg "/grog")
        (str (or (some-> (System/getenv "HOME") str str/trim not-empty)
                 (System/getProperty "user.home"))
             "/.config/grog"))))

(defn- secrets-file
  "grog's file fallback: `<config-home>/secrets.edn`, an EDN map of
  {account password}. Mirrors grog.secrets/secrets-file so a headless Linux box
  — where the keyring backend is unreachable — still works."
  ^java.io.File []
  (io/file (config-home-dir) "secrets.edn"))

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

(defn- reject-inline-password!
  "Refuse an instance that still carries an inline `:password`.

  `:password` was the store-free escape hatch. Silently ignoring it would surface
  as a rejected login (the server would have no credential at all), which reads
  like a wrong password rather than a config that needs migrating — so say so."
  [^String path name i]
  (when (contains? i :password)
    (let [acct (str "ODOO_" (str/upper-case name) "_PASSWORD")]
      (throw (ex-info (str "Instance '" name "' in " path " sets `:password`, which is NO "
                           "longer supported — a credential must live in grog's secret store and be "
                           "named here, never written in the config file (or via `${ENV}`). Fix:  "
                           "/secret set " acct " <value>   then use  :password-secret \"" acct "\".")
                      {:path path :instance name :account acct})))))

(defn- read-config-file!
  "Load instances from the config file at `path`. Accepts EDN (the current grog
  writer format, `*` `.edn`) or legacy JSON. Returns a vector of instance maps.

  `${ENV}` / `${ENV:-default}` interpolation applies to the non-secret fields
  (`:url` / `:db` / `:user`). A credential is only ever NAMED (`:password-secret`);
  an inline `:password` is REFUSED."
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
                  _    (reject-inline-password! path name i)
                  url  (normalize-url (interp (or (:url i) (throw (ex-info (str "instance '" name "' missing :url") {})))))
                  db   (str (interp (or (:db i) (throw (ex-info (str "instance '" name "' missing :db") {})))))]
              {:name name
               :url url
               :db db
               :user (str (interp (or (:user i) (throw (ex-info (str "instance '" name "' missing :user") {})))))
               ;; the secret-store ACCOUNT NAME, not the secret itself
               :password-secret (some-> (:password-secret i) str str/trim not-empty)
               :allow-write (allowed-to-write? i)}))
          insts)))

(defn- home-dir
  "The user's home directory, preferring the JVM property over HOME. Under
  Windows Git-Bash/MSYS, HOME is an MSYS path like `/c/Users/…`, which Java
  cannot open; `user.home` is the canonical `C:\\Users\\…`."
  []
  (or (some-> (System/getProperty "user.home") str str/trim not-empty)
      (some-> (System/getenv "HOME") str str/trim not-empty)
      "~"))

(defn- odoo-config-file
  "The main odoo config, `~/.config/grog/odoo.edn` (home resolved via `home-dir`
  so it works on Windows)."
  []
  (io/file (home-dir) ".config/grog/odoo.edn"))

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
      {:url ... :db ... :user ... :password-secret ...} → single \"default\" instance
    If odoo.edn is missing or carries no `:config`, the instances file is taken
    from `GROG_ODOO_CONFIG` when set (the client sets it), else the default
    ~/.config/grog/odoo-instances.edn is used.
    ${ENV} interpolation inside the instances file is still honored — for the
    non-secret fields only. There is no credential field here either:
    `:password-secret` NAMES the store account (see `instance-password`)."
  []
  (let [cfg (load-config-file!)
        home (home-dir)
        instances
        (cond
          (and (not (:config cfg))
               (or (:url cfg) (:db cfg) (:user cfg) (:password-secret cfg) (:password cfg)))
          (let [_ (reject-inline-password! (.getPath ^java.io.File (odoo-config-file)) "default" cfg)
                missing (remove (fn [k] (not (str/blank? (str (get cfg k)))))
                                [:url :db :user])]
            (when (seq missing)
              (throw (ex-info (str "odoo.edn single-instance config is missing: "
                                   (str/join ", " (map name missing))) {})))
            [{:name "default"
              :url  (normalize-url (interp (:url cfg)))
              :db   (str (interp (:db cfg)))
              :user (str (interp (:user cfg)))
              :password-secret (some-> (:password-secret cfg) str str/trim not-empty)
              :allow-write (allowed-to-write? cfg)}])

          :else
          (let [cfg-file (not-empty (str/trim (str (or (:config cfg)
                                                       (System/getenv "GROG_ODOO_CONFIG")
                                                       "~/.config/grog/odoo-instances.edn"))))
                cfg-file (str/replace-first cfg-file #"^~(?=/|$)" home)]
            (read-config-file! cfg-file)))
        by-name (into {} (map (fn [i] [(:name i) i])) instances)]
    (when-not (seq instances)
      (throw (ex-info "No Odoo instances configured. Add :config (or :url/:db/:user/:password-secret) to ~/.config/grog/odoo.edn." {})))
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
  "The password to authenticate `inst` with: the store entry NAMED by the
  instance's `:password-secret`. That is the ONLY source — there is no literal /
  `${ENV}` `:password`, no env var and no credential file. Several instances can
  be configured without bound, so the account name is carried in the config
  rather than derived from it.

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
    (let [suggested (str "ODOO_" (str/upper-case (str (:name inst))) "_PASSWORD")]
      (throw (ex-info (str "Instance '" (:name inst) "' has no `:password-secret`, so it has no "
                           "credential. A credential is only ever NAMED in the config, never "
                           "written into it. Give the instance the name of an account in grog's "
                           "secret store:  /secret set " suggested " <value>   then add  "
                           ":password-secret " (pr-str suggested) "  to the instance.")
                      {:instance (:name inst)})))))

(defn- instance-auth!
  "Authenticate `inst` lazily (cached) and return {:url :db :uid :password}."
  [inst]
  (let [name (:name inst)]
    (if-let [c (get @auth* name)]
      c
      (let [pw  (instance-password inst)
            raw (xrpc/xmlrpc-call! (:url inst) "common" "authenticate"
                                   [(:db inst) (:user inst) pw {}])
            ;; XML-RPC authenticate returns an integer uid on success, or the
            ;; boolean `false` when the credential is rejected. Cast ONLY when it
            ;; is a number — `(long false)` used to throw a ClassCastException
            ;; here, which reached the model as an unrelated Java type error and
            ;; hid the real "login rejected" diagnosis.
            uid (when (number? raw) (long raw))]
        (when-not (and uid (pos? uid))
          (throw (ex-info
                  (str "Odoo authentication failed for instance '" name
                       "' as user '" (:user inst) "' on db '" (:db inst) "' ("
                       (:url inst) "). authenticate returned " (pr-str raw)
                       (if (false? raw)
                         (str " — the credential was REJECTED. Check that "
                              ":password-secret names the account holding the "
                              "CURRENT password, and that :user / :db / :url are right.")
                         (str " — expected an integer uid; is the URL a real Odoo "
                              "/xmlrpc/2/common and is the db name correct?")))
                  {:instance name :db (:db inst) :url (:url inst) :user (:user inst)})))
        (let [c {:url (:url inst) :db (:db inst) :uid uid :password pw :name name}]
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

(defn- error-detail
  "The most useful detail from `t`: an XML-RPC faultString, an ex-info message,
  else the exception message — and NEVER an empty string (a blank value is
  truthy in `or`, which used to yield 'Error executing tool odoo_x: ' with
  nothing after the colon). Falls back to the exception class."
  ^String [t]
  (or (not-empty (str (:message (ex-data t))))
      (not-empty (str (:faultString (ex-data t))))
      (not-empty (str (.getMessage t)))
      (str (class t))))

(defn- error-result-for [name t]
  (error-result (str "Error executing tool " name ": " (error-detail t))))

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
                     (.success sink (error-result-for name t)))))))))))

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
