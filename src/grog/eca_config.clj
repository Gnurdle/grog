(ns grog.eca-config
  "Generate an ECA `config.json` for grog.

  ECA is normally configured by `~/.config/eca/config.json`. Rather than rebuild
  the provider/auth setup from scratch, `generate-config!` starts from that file
  (which already has working providers + keys), then:
    * merges in the grog MCP servers (imaging / memory / office / search / big /
      babashka / fetch / rss / project-search / imap / odoo / gitlab), and
    * sets `defaultModel` to grog's `:eca :model` (so prompts resolve).

  The result is written to a separate generated file (never overwriting the
  user's default config) and passed to `eca server --config-file <that>`."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.mcp-http :as mcp-http]
            [grog.models :as models]
            [grog.projects :as projects]
            [grog.secrets :as secrets]
            [grog.soul :as soul]))

(declare generate-config!)

(defn- abs-path
  "Absolute, normalized filesystem path string."
  ^String [p]
  (str (.toAbsolutePath (.normalize (.toPath (java.io.File. (str p)))))))

(defn- env-interp
  "Interpolate `${ENV}` / `${ENV:-default}` references in a config string.
  Unset vars with no default resolve to empty string (so the value is omitted)."
  ^String [s]
  (when s
    (str/replace s #"\$\{([^}]+)\}"
                 (fn [[_ var-spec]]
                   (let [[var-name default-val] (str/split var-spec #":-" 2)]
                     (or (System/getenv var-name) default-val ""))))))

(defn odoo-instances-path
  "Path to the **user-maintained** grog-odoo instances config (EDN), in the grog
  config home. This file is the source of truth for Odoo connections — edit it
  directly (never put Odoo credentials in grog.edn)."
  ^String []
  (let [d (config/ensure-config-dir!)]
    (str d "/odoo-instances.edn")))

(defn- interp-inst
  "Interpolate `${ENV}` / `${ENV:-default}` in a scalar, then trim; nil if blank."
  ^String [v]
  (some-> v str env-interp str/trim not-empty))

(defn- clean-inst
  "Interpolate + drop blank scalar fields of an instance map."
  [m]
  (into {}
        (keep (fn [[k v]] (when-let [v (interp-inst v)] [(keyword (name k)) v])))
        m))

(defn- clean-sql
  "Interpolate env refs in the (optional) `:sql` block of an instance map,
  preserving non-string values (e.g. `:port`)."
  [sql]
  (when sql
    (into {}
          (keep (fn [[k v]]
                  (when (some? v)
                    [k (if (string? v) (interp-inst v) v)])))
          sql)))

(defn- odoo-instances-data
  "Legacy instances list from grog.edn's `:odoo` (used only as a one-time
  migration fallback — Odoo config now lives in `odoo-instances.edn`).

  New shape: `:instances [{:name ... :url ... :db ... :user ... :password ...
                           :sql {...}} ...]`.
  Legacy shape (`:url`/`:db`/`:user`/`:password` at the top level) is treated
  as a single instance named \"default\".
  Returns nil when nothing usable is configured."
  []
  (let [o (get-in (config/grog) [:odoo] {})]
    (if (seq (:instances o))
      (mapv (fn [i]
              (cond-> (clean-inst (select-keys i [:name :url :db :user :password]))
                (:sql i) (assoc :sql (clean-sql (:sql i)))))
            (:instances o))
      (let [inst (clean-inst (select-keys o [:name :url :db :user :password]))]
        (when (and (:url inst) (:db inst) (:user inst))
          [(merge {:name "default"} inst)])))))

(defn- read-odoo-instances-file
  "Read the user-maintained `odoo-instances.edn` if present. Returns the
  `:instances` vector, or nil if the file is absent/unreadable."
  []
  (let [f (io/file (odoo-instances-path))]
    (if (.exists f)
      (try (some-> (edn/read-string {:eof nil} (slurp f :encoding "UTF-8"))
                   :instances)
           (catch Exception _ nil))
      nil)))

(defn odoo-configured?
  "True when Odoo is configured anywhere: the user-maintained
  `odoo-instances.edn`, a legacy `grog.edn :odoo` block, or legacy
  `GROG_ODOO_*` env vars."
  []
  (boolean
   (or (seq (read-odoo-instances-file))
       (seq (odoo-instances-data))
       (some-> (System/getenv "GROG_ODOO_URL") str str/trim not-empty))))

(defn- odoo-env
  "Env map for the grog-odoo MCP server.

  Source of truth: the **user-maintained** `~/.config/grog/odoo-instances.edn`.
  Edit that file directly (credentials stay in config home, never in grog.edn).
  If it's absent and grog.edn still carries a legacy `:odoo` block, grog writes
  the file once (migration) so the model still sees the same instances.

  Final fallback is legacy single-instance env vars (`GROG_ODOO_URL` /
  `GROG_ODOO_DB` / `GROG_ODOO_USER` / `GROG_ODOO_PASSWORD`)."
  []
  (let [path (odoo-instances-path)
        f (io/file path)]
    (cond
      (.exists f)
      (cond-> {"GROG_ODOO_CONFIG" path}
        ;; Password comes from the OS keyring (/secret set ODOO_PASSWORD <value>),
        ;; injected as a per-process env var — never a literal in the config file.
        (some? (secrets/get-secret "ODOO_PASSWORD"))
        (assoc "GROG_ODOO_PASSWORD" (secrets/get-secret "ODOO_PASSWORD")))

      (seq (odoo-instances-data))
      (do (spit f (with-out-str (pprint/pprint {:instances (odoo-instances-data)})))
          {"GROG_ODOO_CONFIG" path})

      :else
      (let [o (get-in (config/grog) [:odoo] {})
            one (fn [k]
                  (some-> (get o k)
                          str
                          env-interp
                          str/trim
                          not-empty))
            m (into {}
                    (keep (fn [[env-k cfg-k]]
                            (when-let [v (one cfg-k)] [env-k v])))
                    [["GROG_ODOO_URL" :url]
                     ["GROG_ODOO_DB" :db]
                     ["GROG_ODOO_USER" :user]
                     ["GROG_ODOO_PASSWORD" :password]])]
        (when (seq m) m)))))

(defn gitlab-config-path
  "Path to the **user-maintained** grog-gitlab config (EDN), in the grog config
  home. This file is the source of truth for the GitLab instance — edit it
  directly (never put a GitLab token here; use `/secret set GITLAB_TOKEN`)."
  ^String []
  (let [d (config/ensure-config-dir!)]
    (str d "/gitlab.edn")))

(defn- read-gitlab-config
  "Read `gitlab.edn` if present; nil when absent/unreadable."
  []
  (let [f (io/file (gitlab-config-path))]
    (when (.exists f)
      (try (edn/read-string {:eof nil} (slurp f :encoding "UTF-8"))
           (catch Exception _ nil)))))

(defn gitlab-configured?
  "True when GitLab is configured: `~/.config/grog/gitlab.edn` carries a
  single-instance config (`:url` / `:token` / `:token-file`) or a `:config`
  instances file exists."
  []
  (let [home (or (System/getenv "HOME") (System/getProperty "user.home"))
        cfg (read-gitlab-config)
        cfg-file (some-> cfg :config str str/trim)
        cfg-file (when cfg-file (str/replace-first cfg-file #"^~(?=/|$)" home))
        inst-file (io/file (or cfg-file
                               (str home "/.config/grog/gitlab-instances.edn")))]
    (boolean
     (or (and cfg (or (:url cfg) (:token cfg) (:token-file cfg)))
         (.exists inst-file)))))

(defn- gitlab-env
  "Env map for the grog-gitlab MCP server.

  The token comes from the OS keyring (`/secret set GITLAB_TOKEN <value>`),
  injected as a per-process env var — never a literal in the config file. The
  server interpolates `${GROG_GITLAB_TOKEN}` in its `:token` field."
  []
  (let [tok (some-> (secrets/get-secret "GITLAB_TOKEN") str str/trim not-empty)]
    (when tok {"GROG_GITLAB_TOKEN" tok})))

(defn- grog-root
  "The grog project root (where deps.edn and the grog-* sibling dirs live)."
  ^String []
  (abs-path (System/getProperty "user.dir" ".")))

(defn memory-db-path
  "The grog-memory SQLite store for `project`:
  `~/grog-projects/<proj>/state/mem.db`. grog is always in a project,
  so there is no repo-root fallback."
  ^String [project]
  (when-not (and project (not (str/blank? (str project))))
    (throw (ex-info "no active project for memory store" {})))
  (projects/memory-db-path project))

(defn memory-config-path
  "Per-project grog-memory server config: `<project>/state/memory.edn`. Never a
  global file — two sessions on different projects can't clobber each other."
  ^String [project]
  (str (.getPath (projects/state-dir project)) "/memory.edn"))

(defn- write-memory-config!
  "Write `project`'s grog-memory config (`{:db … :max-open …}`) and return its
  path (handed to the server via `GROG_MEMORY_CONFIG`). Preserves a user-set
  `:max-open`."
  ^String [project]
  (let [p   (memory-config-path project)
        cur (try (edn/read-string (slurp (io/file p))) (catch Exception _ nil))
        m   (merge {:max-open 8} (when (map? cur) cur)
                   {:db (memory-db-path project)})]
    (spit (io/file p) (with-out-str (pprint/pprint m)))
    p))

(defn- memory-env
  "Env for the grog-memory server: point it at THIS project's config file."
  [project]
  {"GROG_MEMORY_CONFIG" (write-memory-config! project)})

(defn project-search-config-path
  "Per-project grog-project-search config: `<project>/state/project-search.edn`."
  ^String [project]
  (str (.getPath (projects/state-dir project)) "/project-search.edn"))

(defn- write-project-search-config!
  "Write `project`'s grog-project-search config (projects home + project name)
  and return its path (handed to the server via `GROG_PROJECT_SEARCH_CONFIG`)."
  ^String [project]
  (let [p (project-search-config-path project)
        m {:projects-dir (.getPath (config/projects-dir))
           :project project}]
    (spit (io/file p) (with-out-str (pprint/pprint m)))
    p))

(defn- project-search-env
  "Env for the grog-project-search server: point it at THIS project's config file."
  [project]
  {"GROG_PROJECT_SEARCH_CONFIG" (write-project-search-config! project)})

(defn default-eca-config-path
  "The standard ECA config file this generator starts from."
  ^String []
  (str (System/getProperty "user.home") "/.config/eca/config.json"))

(defn generated-config-path
  "Where grog writes its merged ECA config."
  ^String []
  (str (config/ensure-config-dir!) "/eca-config.generated.json"))

(defn session-config-path
  "Per-session generated ECA config path — one file per project/tab — so
  concurrent sessions never overwrite each other's config."
  ^String [project]
  (let [d (io/file (config/ensure-config-dir!) "sessions")
        safe (-> (str (or (some-> project str str/trim not-empty) "default"))
                 (str/replace #"[^A-Za-z0-9._-]" "_"))]
    (.mkdirs d)
    (str (.getPath d) "/" safe ".json")))

(defn approved-tools-path
  "Persistent store of tool names grog has been asked to always allow."
  ^String []
  (str (config/ensure-config-dir!) "/approved-tools.edn"))

(defn- shell-wrapped
  "An MCP stdio server spec that first `cd`s into `dir`, so `clojure -M:...`
  resolves that project's deps.edn regardless of ECA's working directory."
  [dir cmdline env]
  (cond-> {"command" "bash"
           "args" ["-lc" (str "cd '" dir "' && " cmdline)]}
    env (assoc "env" env)))

(defn- clean-imap-account
  "Interpolate env refs in string fields; preserve numbers/booleans as-is."
  [m]
  (into {}
        (keep (fn [[k v]]
                (when (some? v)
                  [k (if (string? v) (interp-inst v) v)])))
        m))

(defn imap-instances-path
  "Where grog writes the grog-imap MCP account metadata config (EDN)."
  ^String []
  (let [d (config/ensure-config-dir!)]
    (str d "/imap-accounts.edn")))

(defn imap-project-config-file
  "Path to the email project's IMAP account metadata (project data, outside the
  source tree). Sourced from the active project home under the `email` project."
  ^String []
  (let [d (io/file (str (System/getProperty "user.home") "/grog-projects/email/state"))]
    (.mkdirs d)
    (str d "/imap-accounts.edn")))

(defn imap-configured?
  "True when IMAP account metadata is available — from the email project file, or
  (backward compat) grog.edn's `:imap :accounts`."
  []
  (boolean
   (or (.exists (io/file (imap-project-config-file)))
       (.exists (io/file (str/replace (imap-project-config-file) #"\.edn$" ".json")))
       (seq (get-in (config/grog) [:imap :accounts])))))

(defn- read-imap-accts
  "Read the email project's account metadata file as EDN, falling back to legacy
  JSON (the old `imap-accounts.json`). Returns a seq (possibly nil)."
  []
  (let [edn-file (java.io.File. (imap-project-config-file))
        json-file (java.io.File. (str/replace (imap-project-config-file) #"\.edn$" ".json"))]
    (cond
      (.exists edn-file)
      (try (some-> (edn/read-string {:eof nil} (slurp edn-file)) :accounts)
           (catch Exception _ nil))
      (.exists json-file)
      (try (some-> (json/parse-string (slurp json-file) true) :accounts)
           (catch Exception _ nil))
      :else nil)))

(defn- imap-accounts-data
  "Account *metadata* (never secrets). Source of truth is the email project file
  at `~/grog-projects/email/state/imap-accounts.edn` (legacy `.json` accepted);
  falls back to grog.edn's `:imap :accounts` for backward compatibility."
  []
  (let [accts (or (read-imap-accts)
                  (get-in (config/grog) [:imap :accounts]))]
    (when (seq accts)
      {:accounts (mapv #(clean-imap-account
                         (select-keys % [:name :host :port :tls :user :sasl
                                         :oauth :read-only]))
                       accts)})))

(defn- imap-env
  "Env map for the grog-imap MCP server: write account *metadata* to the grog
  config home (`imap-accounts.edn`, EDN) and hand it via GROG_IMAP_CONFIG. The
  server resolves the secret itself from its per-account file — never here."
  []
  (let [data (imap-accounts-data)
        path (imap-instances-path)]
    (spit (io/file path) (with-out-str (pprint/pprint data)))
    {"GROG_IMAP_CONFIG" path}))

(defn grog-mcp-servers
  "The grog MCP server specs for `project`, keyed by server id.

  Every entry spawns the SAME command — the grog-mcp bundle JVM restricted to one
  server (`--server <id>`) from the single `grog_mcp` project — rather than
  `cd <repo>/grog-<x> && clojure -M:mcp`. Two reasons: the old form needed 13
  separate project trees on disk, and each of those servers built its MCP tool
  descriptor with the SDK's String constructor, which ships
  `function.parameters` as a JSON *string* — strict providers reject that whole
  request (400) and the model cannot see parameter names, so tools get called
  with empty arguments. The bundle's wrapper builds a real JsonSchema object
  (grog_mcp/main.clj). One entry per server id keeps ECA tool names
  (`<id>__<tool>`) and therefore existing allowlists intact, and `spec` below is
  the single place the command is built — the uberjar (`java -jar …`) swap lands
  there and needs no other change.

  The Streamable-HTTP branch (`grog.mcp-http`) is inert with the server line
  parked: no daemon runs, so `urls` is nil and the stdio specs are returned."
  [project]
  (if-let [us (mcp-http/urls)]
    (into {} (map (fn [[k u]] [k {:url u}])) us)
    (let [root (grog-root)
          bundle (str root "/grog_mcp")
          ;; Prefer the built uberjar (no Clojure CLI needed, one artifact), but
          ;; fall back to the source tree when it hasn't been built — dev boxes
          ;; and a fresh checkout keep working either way. `build.clj` writes
          ;; target/grog-mcp-<version>.jar; pick the newest if several exist.
          jar (->> (seq (.listFiles (java.io.File. (str bundle "/target"))))
                   (filter (fn [^java.io.File f]
                             (re-matches #"grog-mcp-.*\.jar" (.getName f))))
                   (sort-by (fn [^java.io.File f] (.lastModified f)))
                   last)
          spec (fn [id env]
                 (if jar
                   (shell-wrapped bundle
                                  (str "java --add-opens=java.base/java.lang=ALL-UNNAMED"
                                       " --enable-native-access=ALL-UNNAMED"
                                       " -cp '" (.getAbsolutePath ^java.io.File jar) "'"
                                       " clojure.main -m grog_mcp.main --server " id)
                                  env)
                   (shell-wrapped bundle (str "clojure -M:mcp --server " id) env)))]
      (cond-> {"grog-imaging" (spec "grog-imaging" nil)

               ;; JVM memory (Clojure/SQLite), served by the grog-mcp bundle
               ;; restricted to its memory tools — no Python/venv. Reads the same
               ;; `memory.edn` with a byte-compatible schema, so existing mem.db
               ;; files work as-is. The KEY stays "grog-memory" so ECA tool names
               ;; (grog-memory__assoc_*) don't change and allowlists keep working.
               "grog-memory"  (spec "grog-memory" (memory-env project))

               "grog-office"         (spec "grog-office" nil)
               "grog-search"         (spec "grog-search" nil)
               "grog-big"            (spec "grog-big" nil)
               "grog-babashka"       (spec "grog-babashka" nil)
               "grog-fetch"          (spec "grog-fetch" nil)
               "grog-rss"            (spec "grog-rss" nil)
               "grog-project-search" (spec "grog-project-search"
                                           (project-search-env project))
               "grog-alpaca"         (spec "grog-alpaca" nil)}
        (imap-configured?)   (assoc "grog-imap"   (spec "grog-imap" (imap-env)))
        (odoo-configured?)   (assoc "grog-odoo"   (spec "grog-odoo" (odoo-env)))
        (gitlab-configured?) (assoc "grog-gitlab" (spec "grog-gitlab" (gitlab-env)))))))

(defn debug-dump-config!
  "Log that the ECA config was (re)written to the grog debug log, **without**
  dumping the config contents (the full JSON includes API keys and should never
  be written to a log).

  Prints to `System/err` explicitly (NOT the bound `*err*`) so the line always
  lands in the real debug log (`grog-ui.<pid>.log`, via grog.log's in-process
  tee), even when called from a worker thread whose `*err*` is bound to the
  transcript pane.
  Called whenever the config is (re)written or ECA is (re)started."
  [^String path merged]
  (.println System/err (str "==== grog: ECA config (re)written -> " path))
  (.println System/err (str "==== model: " (or (:defaultModel merged) "(none)")
                            ;; summary only — never dump the map itself
                            " | mcpServers=" (count (:mcpServers merged {}))
                            " | rules=" (count (:rules merged []))
                            " | top-level keys=" (count merged)))
  path)

;; --- tool approval allowlist (permanent approval) --------------------------

(defn- normalize-allow-entry
  "Coerce an allowlist entry (string, keyword, or {name {...}}) to a tool-name string."
  ^String [e]
  (cond
    (string? e)  e
    (keyword? e) (name e)
    (map? e)     (some-> (first (keys e)) name)
    :else        nil))

(defn read-approved-tools
  "Set of tool names permanently allowed: from the approved-tools EDN file plus
  any `:eca :approval :allow` in grog.edn. Filters blank entries."
  []
  (let [from-file (try (->> (edn/read-string (slurp (io/file (approved-tools-path))))
                            (map str))
                       (catch Exception _ []))
        from-cfg  (keep normalize-allow-entry (get-in (config/grog) [:eca :approval :allow]))]
    (into #{} (keep not-empty (concat from-file from-cfg)))))

(defn approve-tool!
  "Permanently allow `tool-name`: persist it in the approved-tools store, then
  regenerate + dump the ECA config so the allowlist is applied. Returns
  `tool-name`. (ECA picks up the new `allow` entry on its next start.)"
  ^String [tool-name]
  (let [path (approved-tools-path)
        cur  (read-approved-tools)
        next (conj cur (str tool-name))]
    (spit (io/file path) (pr-str (sort next)))
    (generate-config!)
    (str tool-name)))

(defn- approval-section
  "The `toolCall.approval` map to merge into the config, or nil if no tools are
  permanently allowed."
  []
  (let [allow (read-approved-tools)]
    (when (seq allow)
      {:byDefault "ask"
       :allow (into {} (map (fn [t] [t {}])) (sort allow))
       :ask {}
       :deny {}})))

(defn- add-approval!
  "Merge the grog tool-approval allowlist into `cfg`.
  Uses a deep merge so any existing `:toolCall :approval` settings are kept and
  the grog `allow` entries are added."
  [cfg]
  (if-let [a (approval-section)]
    (config/deep-merge cfg {:toolCall {:approval a}})
    cfg))

;; --- Per-project rules (global SOUL + project SOUL overlay + context) ------

(defn project-rules-file
  "The absolute path of the generated ECA rules markdown for `project`.
  The file is the composed per-project standing context:
    * global SOUL.md (base personality),
    * the project's own SOUL.md (if present) — overrides global on conflicts,
    * the project's loaded context (banner + notes + dialog snapshot).

  Written under the project's `state/` dir so it stays out of the source tree.
  Returns nil if `project` is nil."
  ^String [project]
  (when-let [proj project]
    (let [^java.io.File dir (projects/state-dir proj)
          f (io/file dir "eca-rules.md")
          global (soul/read-text)
          project-soul (soul/read-project-text proj)
          ctx (projects/load-context proj)
          parts (cond-> []
                  (seq global)
                  (conj "## Persistent instructions (global SOUL)\n\n" global)

                  (seq project-soul)
                  (conj (str "\n\n## Project instructions (" proj " — overrides global on conflicts)\n\n"
                             project-soul))

                  (seq ctx)
                  (conj (str "\n\n## Active project context\n\n" ctx)))]
      (when (seq parts)
        (spit f (str/join "\n\n" (cons (str "# " proj " — standing context") parts))
              :encoding "UTF-8"))
      (.getPath f))))

(defn- add-rules!
  "Add a `rules` entry pointing at `project`'s generated rules file (if any).
  ECA loads rule files/dirs as standing context on every prompt."
  [cfg project]
  (if-let [rules-file (project-rules-file project)]
    (update cfg :rules conj {:path rules-file})
    cfg))

(defn- eca-config-debug! [& xs]
  "One-line ECA-config trace written to the **real** stderr so it lands in the
  grog debug log (`grog-ui.<pid>.log` / `$GROG_LOG`) regardless of `*out*`/`*err*`
  rebinding."
  (.println System/err (str "[grog-eca-config] " (apply str (interpose " " (map str xs))))))

(defn generate-config!
  "Produce the merged ECA config map and write it to `out-path`
  (default `(generated-config-path)`), dumping it to the debug log. The
  project-scoped bits (memory, project-search, rules) are resolved for
  `project` — NOT the process-global active project — so concurrent sessions
  in one grog process can't stamp each other's stores. Returns the written path."
  ([] (generate-config! (default-eca-config-path)))
  ([base-path] (generate-config! base-path (generated-config-path)))
  ([base-path out-path] (generate-config! base-path out-path (projects/resolve-active-project)))
  ([base-path out-path project]
   (let [base-file (io/file base-path)
         base-exists? (.exists base-file)
         base-parsed (when base-exists?
                       (try (json/parse-string (slurp base-file) true)
                            (catch Exception _ nil)))
         base (if base-parsed base-parsed {})
         _ (if-not base-exists?
             (eca-config-debug! "base ECA config MISSING: " base-path
                                " (empty base - NO providers/auth merged; expected at"
                                " ~/.config/eca/config.json on POSIX,"
                                " %APPDATA%/eca/config.json or ~/.config/eca/config.json on Windows)")
             (if (nil? base-parsed)
               (eca-config-debug! "base ECA config UNPARSEABLE (JSON error): " base-path
                                  " - empty base used")
               (eca-config-debug! "base ECA config loaded: " base-path
                                  " top-level keys=" (count base)
                                  " providers=" (count (:providers base {}))
                                  " defaultModel=" (pr-str (:defaultModel base)))))
         raw-model (or (config/eca-model)
                       (when-let [m (:defaultModel base)] m))
         ;; ECA resolves models as `provider/name`, so the default model must be
         ;; provider-qualified (`ollama/…`, `openrouter/…`). A bare local id such
         ;; as `qwen3.5:4b-tweaked` would make ECA fail with
         ;; "API url not found … provider 'qwen3.5:4b-tweaked'".
         model (models/qualify-eca-model raw-model
                                         nil
                                         (try (config/llm-url) (catch Exception _ nil)))
         _ (eca-config-debug! "raw model=" (pr-str raw-model)
                              " qualified=" (pr-str model)
                              " source=" (if (config/eca-model) "grog.edn :eca :model" "base config defaultModel"))
         merged (-> base
                    (assoc :mcpServers (grog-mcp-servers project))
                    (cond-> model (assoc :defaultModel model))
                    (add-approval!)
                    (add-rules! project))
         out (or out-path (generated-config-path))]
     (spit (io/file out) (json/generate-string merged {:pretty true}))
     (debug-dump-config! out merged)
     out)))
