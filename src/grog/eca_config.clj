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

(defn- odoo-instances-data
  "Instances list from grog.edn's `:odoo`, used as a one-time migration fallback
  — Odoo config otherwise lives in `odoo-instances.edn`.

  Shape: `:instances [{:name ... :url ... :db ... :user ... :password ...
                       :allow-write false} ...]`.
  A flat shape (`:url`/`:db`/`:user`/`:password` at the top level) is treated
  as a single instance named \"default\".

  There is deliberately no `:sql` block any more: SQL goes through the
  instance's own Odoo API (the Select-O-Matic addon), never a database
  connection, so no database host/port/credentials belong here.

  Returns nil when nothing usable is configured."
  []
  (let [o (get-in (config/grog) [:odoo] {})
        ;; :allow-write is a BOOLEAN — keep it out of clean-inst (which
        ;; stringifies), or `false` would come back as the truthy "false".
        with-write (fn [m src]
                     (cond-> m
                       (contains? src :allow-write)
                       (assoc :allow-write (boolean (:allow-write src)))))]
    (if (seq (:instances o))
      (mapv (fn [i] (with-write (clean-inst (select-keys i [:name :url :db :user :password])) i))
            (:instances o))
      (let [inst (with-write (clean-inst (select-keys o [:name :url :db :user :password])) o)]
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
  `odoo-instances.edn`, a `grog.edn :odoo` block, or
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
  If it's absent and grog.edn carries a `:odoo` block, grog writes the file
  once (migration) so the model still sees the same instances.

  Final fallback is single-instance env vars (`GROG_ODOO_URL` /
  `GROG_ODOO_DB` / `GROG_ODOO_USER`) — NO password.

  NOTE: like grog-gitlab, this server gets NO secret injection. It used to pass
  `GROG_ODOO_PASSWORD`, and everything in this env map is written VERBATIM into
  the generated config (`~/.config/grog/sessions/<project>.json`), so the Odoo
  password sat on disk in cleartext. grog-odoo now reads the OS keyring itself,
  per instance (`:password-secret` in odoo-instances.edn). Nothing secret may
  enter this env map."
  []
  (let [path (odoo-instances-path)
        f (io/file path)]
    (cond
      (.exists f)
      {"GROG_ODOO_CONFIG" path}

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
                     ["GROG_ODOO_USER" :user]])]
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

;; NOTE: there is deliberately no `gitlab-env` any more.
;;
;; It used to inject the token as `GROG_GITLAB_TOKEN` — and everything in this
;; env map is written VERBATIM into the generated config
;; (`~/.config/grog/sessions/<project>.json`), so the GitLab token sat on disk in
;; plaintext. The token is now read from the OS keyring by grog-gitlab itself
;; (`/secret set GITLAB_TOKEN <value>`), which is also how grog-search reads the
;; Brave key. Nothing secret should ever enter this env map.

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
  resolves that project's deps.edn regardless of ECA's working directory.

  When `dir` does not exist the `cd` is dropped: a packaged install has no
  source tree, and runs the server from an absolute jar path instead."
  [dir cmdline env]
  (let [prefix (if (and dir (.isDirectory (io/file dir)))
                 (str "cd '" dir "' && ")
                 "")]
    (cond-> {"command" "bash"
             "args" ["-lc" (str prefix cmdline)]}
      env (assoc "env" env))))

(defn- explicit-bundle-jar
  "The tool-bundle jar named by `GROG_MCP_JAR`, when the app ships one outside
  any source tree (a packaged install). Returns a File, or nil."
  ^java.io.File []
  (when-let [p (some-> (System/getenv "GROG_MCP_JAR") str str/trim not-empty)]
    (let [f (io/file p)]
      (when (.exists f) f))))

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

(defn startup-config-files
  "The files the ECA child — and the MCP servers IT spawns — read **at startup**.

  None of these are re-read. An MCP server bakes its configuration in when it is
  launched, so editing `odoo-instances.edn` (or any of these) cannot reach a
  running ECA or its children; the old contents keep being served, tool schemas
  included. Picking a change up therefore means STOPPING the ECA instance and
  letting it respawn — which is what `config-stamp` detects.

  `grog.edn` is deliberately NOT watched: grog rewrites it for unrelated things
  (a model pick, an appearance tweak) and restarting ECA on each of those would
  be worse than the problem."
  []
  (let [pf (fn [f] (try (some-> (f) io/file) (catch Throwable _ nil)))]
    (remove nil?
            (concat
             [(io/file (default-eca-config-path))]
             (map pf [odoo-instances-path
                      gitlab-config-path
                      memory-config-path
                      project-search-config-path
                      imap-instances-path])))))

(defn config-stamp
  "Newest last-modified time (epoch ms) across `startup-config-files`, else 0.

  Cheap enough to evaluate before every prompt. If it has moved since the
  running ECA was started, that ECA and everything under it is serving stale
  config and must be restarted."
  ^long []
  (reduce (fn [acc ^java.io.File f]
            (try (if (.exists f) (long (max acc (.lastModified f))) acc)
                 (catch Throwable _ acc)))
          0
          (startup-config-files)))

(defn imap-project-config-file
  "Path to the email project's IMAP account metadata (project data, outside the
  source tree). Sourced from the active project home under the `email` project."
  ^String []
  (let [d (io/file (str (System/getProperty "user.home") "/grog-projects/email/state"))]
    (.mkdirs d)
    (str d "/imap-accounts.edn")))

(defn imap-configured?
  "True when IMAP account metadata is available — from the email project file, or
  grog.edn's `:imap :accounts`."
  []
  (boolean
   (or (.exists (io/file (imap-project-config-file)))
       (.exists (io/file (str/replace (imap-project-config-file) #"\.edn$" ".json")))
       (seq (get-in (config/grog) [:imap :accounts])))))

(defn- read-imap-accts
  "Read the email project's account metadata file as EDN, falling back to a
  legacy JSON file (`imap-accounts.json`). Returns a seq (possibly nil)."
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
  at `~/grog-projects/email/state/imap-accounts.edn` (a `.json` file is also
  accepted); falls back to grog.edn's `:imap :accounts`."
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
  "The grog MCP server specs for `project` — ONE entry, ONE process.

  `grog_mcp.main` registers every server's tools in a single McpServer, so the
  whole toolbelt is one JVM per session instead of one per server id (13 of
  them). The entry is keyed `grog-mcp`, which is the prefix ECA puts on every
  tool (`grog-mcp__odoo_search_read`).

  That key was long kept per-server id — `grog-odoo`, `grog-rss`, … — on the
  theory that it preserved existing allowlists. It does not: the permanent
  approval store (`approved-tools.edn`) holds BARE tool names (`odoo_search_read`,
  `read_pdf_document`), so collapsing the keys changes only the display prefix.

  Every per-server config env is merged into that one child's environment. A
  server therefore contributes tools only when its config actually loads — an
  unconfigured server's `build-tools` throws, `collect-tools` swallows it, and
  its tools are simply absent.

  The bundle wrapper also carries the schema fix: it builds a real JsonSchema
  object, where the SDK's String constructor would ship `function.parameters` as
  a JSON *string* and strict providers would 400 the entire request. `spec` is
  the single place the command is built, so the jar↔source choice lives there.

  The Streamable-HTTP branch (`grog.mcp-http`) is inert unless a daemon is
  running: with no daemon, `urls` is nil and the stdio spec is returned."
  [project]
  (if-let [us (mcp-http/urls)]
    (into {} (map (fn [[k u]] [k {:url u}])) us)
    (let [root (grog-root)
          bundle (str root "/grog_mcp")
          ;; Prefer the built uberjar (no Clojure CLI needed, one artifact), but
          ;; fall back to the source tree when it hasn't been built — dev boxes
          ;; and a fresh checkout keep working either way. `build.clj` writes
          ;; target/grog-mcp-<version>.jar; pick the newest if several exist.
          jar (or (explicit-bundle-jar)
                  (->> (seq (.listFiles (java.io.File. (str bundle "/target"))))
                       (filter (fn [^java.io.File f]
                                 (re-matches #"grog-mcp-.*\.jar" (.getName f))))
                       (sort-by (fn [^java.io.File f] (.lastModified f)))
                       last))
          ;; GROG_MCP_SOURCE=1 (set by `bb dev`) runs from .clj source at launch
          ;; instead of the built jar — plain `clojure -M:mcp` in the bundle, the
          ;; same on-the-fly compile, just without the jar. The bundle's
          ;; `:local/root` deps point straight at the live sibling projects, so
          ;; there is no copy to refresh first. Also the
          ;; automatic behaviour when no jar has been built.
          source? (or (some-> (System/getenv "GROG_MCP_SOURCE") str str/trim not-empty)
                      (nil? jar))
          ;; One child, so all per-server config rides in ITS environment.
          ;; All of it is written verbatim into the generated config file, so
          ;; these values must be PATHS and switches only — never secrets. The
          ;; GitLab token is absent for exactly that reason: the server reads it
          ;; from the OS keyring (see the note above `grog-root`).
          env (merge (memory-env project)
                     (project-search-env project)
                     (when (imap-configured?) (imap-env))
                     (when (odoo-configured?) (odoo-env)))
          spec (if source?
                 (shell-wrapped bundle "clojure -M:mcp" env)
                 (shell-wrapped bundle
                                (str "java --add-opens=java.base/java.lang=ALL-UNNAMED"
                                     " --enable-native-access=ALL-UNNAMED"
                                     " -cp '" (.getAbsolutePath ^java.io.File jar) "'"
                                     " clojure.main -m grog_mcp.main")
                                env))]
      {"grog-mcp" spec})))

(defn debug-dump-config!
  "Log that the ECA config was (re)written to the grog debug log, **without**
  dumping the config contents (the full JSON includes API keys and should never
  be written to a log).

  Prints to `System/err` explicitly (NOT the bound `*err*`) so the line always
  reaches the real stderr — the desktop client redirects the backend's stderr
  into its per-instance log (`<base>.<pid>.log`; see
  clients/web/src/main/log.js) — even when called from a worker thread whose
  `*err*` is bound to the transcript pane.
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

(defn- odoo-rules-file
  "Write (and return) the grog-odoo standing-context rule file, or nil when Odoo
  is not configured.

  The instance rule is stated here, not only in the tool schemas, because
  getting it wrong is expensive: with more than one instance configured, every
  `odoo_*` call has to name one, and a bare call is REFUSED by the server — the
  model should know that before it tries."
  ^String []
  (when (odoo-configured?)
    (let [insts (or (seq (read-odoo-instances-file)) (seq (odoo-instances-data)))
          names (mapv (fn [i] (str (or (:name i) "default"))) insts)
          multi? (> (count names) 1)
          f (io/file (config/ensure-config-dir!) "odoo-rules.md")]
      (spit f
            (str/join
             "\n"
             (concat
              ["# Odoo (grog-odoo)"
               ""
               (str "- Configured instances: " (str/join ", " names))]
              (when multi?
                ["- **Every `odoo_*` call MUST pass `instance`** — the ONE exception is `odoo_list_instances`, which exists to discover them and takes no instance. More than one instance is configured, so there is NO default: any other call that does not name one is refused. Name it explicitly; never guess."
                 "- An invocation like `odoo stage …` does nothing until `instance` is stated — say which instance you mean."])
              ["- SQL goes through the Select-O-Matic addon over the Odoo API. There is no direct database access, and no database credentials are involved."
               "- Writes are per instance: an instance configured `:allow-write false` (the default) refuses any statement that could modify data."]))
            :encoding "UTF-8")
      (.getPath f))))

(defn- add-odoo-rules!
  "Add the grog-odoo standing-context rule file when Odoo is configured."
  [cfg]
  (if-let [rules-file (odoo-rules-file)]
    (update cfg :rules conj {:path rules-file})
    cfg))

(defn core-rules-file
  "Write (and return) grog's own standing operating rules — the rules that apply
  to every project, on every client, regardless of what grog.edn says.

  Written to the grog config home (not the project state dir) because they are
  grog's rules, not the project's."
  ^String []
  (let [f (io/file (config/ensure-config-dir!) "grog-rules.md")]
    (spit f
          (str/join
           "\n"
           ["# grog — operating rules"
            ""
            "These apply to every project and every client."
            ""
            "- **When an MCP tool breaks, debug the tool. Do not wire around it.**"
            "  If a tool errors, returns an empty or partial result, times out, or"
            "  behaves unexpectedly, the task stops there until that tool is"
            "  understood and fixed."
            "- Do NOT bypass the MCP tools — no shell command, `curl`, direct"
            "  database connection, or one-off script — to reach data or an effect"
            "  the tools are meant to provide, and do not present a result obtained"
            "  that way as if a tool had produced it. Using ANOTHER MCP tool that"
            "  genuinely serves the request is fine; bypassing the tools is not."
            "- Report the failure plainly (tool, arguments, exact error, evidence),"
            "  then debug it: the MCP tools ARE the product, and a workaround hides"
            "  the bug instead of fixing it."
            "- If a tool is genuinely unusable, say so explicitly and get agreement"
            "  before using any alternative — never substitute silently."
            "- The same goes for a tool that 'works' but lies: empty or partial"
            "  results are a break, not a green light."])
          :encoding "UTF-8")
    (.getPath f)))

(defn- add-core-rules!
  "Add grog's own standing operating rules to the generated ECA config."
  [cfg]
  (update cfg :rules conj {:path (core-rules-file)}))

(defn- eca-config-debug! [& xs]
  "One-line ECA-config trace written to the **real** stderr so it survives
  regardless of `*out*`/`*err*` rebinding; the desktop client redirects the
  backend's stderr into its per-instance log (`<base>.<pid>.log`)."
  (.println System/err (str "[grog-eca-config] " (apply str (interpose " " (map str xs))))))

(def ^:private eca-built-in-providers
  "Providers ECA ships a built-in default URL for. Adding our own entry for one
  of these would CLOBBER that default, so we never do."
  #{"openai" "anthropic" "github-copilot" "google" "ollama"})

(defn- model-provider
  "The provider half of a `provider/model` id, or nil."
  [model]
  (when-let [m (re-matches #"([^/]+)/.+" (str model))]
    (second m)))

(defn- ensure-provider
  "Make sure `cfg` carries a provider entry for `model`'s provider half.

  This is what makes the base case work with ONE secret and no eca/config.json:
  ECA ships defaults for openai/anthropic/google/github-copilot/ollama but NOT,
  say, openrouter — so an `openrouter/…` model has no URL to resolve, and the
  user was left hand-writing a provider block.

  Precedence: a provider already in the user's ECA config wins (their config,
  their call) -> an explicit `:eca :providers` entry -> derived from grog's own
  `:llm :url`. The derived `:key` is an env REFERENCE; grog injects the value
  into the ECA child (see `chat/provider-env`), so no secret lands in a file."
  [cfg model]
  (let [p (model-provider model)
        providers (:providers cfg)]
    (cond
      (nil? p)                            cfg
      ;; Compare by NAME, not by key identity: the base config comes from
      ;; cheshire with KEYWORD keys (`:openrouter`) while we add a String key,
      ;; and `contains?` would miss the user's own provider — then we would add
      ;; a duplicate `"openrouter"` that clobbers it in the JSON.
      (some #(= (name %) p) (keys providers)) cfg
      (contains? eca-built-in-providers p) cfg
      :else
      (let [explicit (get (config/eca-provider-overrides) p)
            derived  {:api "openai-chat"
                      :url (try (config/llm-url) (catch Exception _ nil))
                      :key "${env:GROG_LLM_API_KEY}"}]
        (assoc-in cfg [:providers p] (or explicit derived))))))

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
                    (ensure-provider model)
                    (assoc :mcpServers (grog-mcp-servers project))
                    (cond-> model (assoc :defaultModel model))
                    (add-approval!)
                    (add-core-rules!)
                    (add-rules! project)
                    (add-odoo-rules!))
         ;; `sort-by name`: provider keys are a MIX of keywords (from the
         ;; cheshire-parsed base config) and strings (ours), and a plain `sort`
         ;; throws ClassCastException comparing Keyword to String.
         _ (eca-config-debug! "providers in generated config:"
                              (pr-str (sort (map name (keys (:providers merged {}))))))
         out (or out-path (generated-config-path))]
     (spit (io/file out) (json/generate-string merged {:pretty true}))
     (debug-dump-config! out merged)
     out)))
