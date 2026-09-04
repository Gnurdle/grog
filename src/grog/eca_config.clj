(ns grog.eca-config
  "Generate an ECA `config.json` for grog.

  ECA is normally configured by `~/.config/eca/config.json`. Rather than rebuild
  the provider/auth setup from scratch, `generate-config!` starts from that file
  (which already has working providers + keys), then:
    * merges in the three grog MCP servers (imaging / memory / odoo), and
    * sets `defaultModel` to grog's `:eca :model` (so prompts resolve).

  The result is written to a separate generated file (never overwriting the
  user's default config) and passed to `eca server --config-file <that>`."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.models :as models]
            [grog.projects :as projects]
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
  (let [d (config/config-home-dir)]
    (.mkdirs d)
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
                     ["GROG_ODOO_USER" :user]
                     ["GROG_ODOO_PASSWORD" :password]])]
        (when (seq m) m)))))

(defn- grog-root
  "The grog project root (where deps.edn and the grog-* sibling dirs live)."
  ^String []
  (abs-path (System/getProperty "user.dir" ".")))

(defn memory-db-path
  "The grog-memory SQLite DB path. When a project is active it is the project's own
  state store (`~/grog-projects/<proj>/state/mem.db`); otherwise the repo-root
  default (`.grog-memory.db`). The GUI reconnects ECA with a fresh generated config
  on project switch, so the memory server follows the active project."
  ^String [root]
  (or (projects/active-memory-db-path)
      (str root "/.grog-memory.db")))

(defn default-eca-config-path
  "The standard ECA config file this generator starts from."
  ^String []
  (str (System/getProperty "user.home") "/.config/eca/config.json"))

(defn generated-config-path
  "Where grog writes its merged ECA config."
  ^String []
  (let [d (config/config-home-dir)]
    (.mkdirs d)
    (str d "/eca-config.generated.json")))

(defn approved-tools-path
  "Persistent store of tool names grog has been asked to always allow."
  ^String []
  (str (config/config-home-dir) "/approved-tools.edn"))

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
  (let [d (config/config-home-dir)]
    (.mkdirs d)
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
  "The grog MCP server specs, keyed by server id."
  []
  (let [root (grog-root)
        servers
        {"grog-imaging"
     (shell-wrapped (str root "/grog-imaging")
                    "clojure -M:mcp"
                    nil)

     "grog-memory"
     (shell-wrapped (str root "/grog-memory")
                    "PYTHONPATH=src .venv/bin/python -m grog_memory.server"
                    {"GROG_MEMORY_DB" (memory-db-path root)})

     "grog-office"
     (shell-wrapped (str root "/grog-office")
                    "clojure -M:mcp"
                    nil)

     "grog-search"
     (shell-wrapped (str root "/grog-search")
                    "clojure -M:mcp"
                    nil)

     "grog-big"
     (shell-wrapped (str root "/grog-big")
                    "clojure -M:mcp"
                    {"GROG_BIG_URL" "http://localhost:4000/v1"
                     "GROG_BIG_MODEL" "big"
                     "GROG_BIG_API_KEY" "sk-dummy"})

     "grog-babashka"
     (shell-wrapped (str root "/grog-babashka")
                    "clojure -M:mcp"
                    nil)

     "grog-fetch"
     (shell-wrapped (str root "/grog-fetch")
                    "clojure -M:mcp"
                    nil)

     "grog-rss"
     (shell-wrapped (str root "/grog-rss")
                    "clojure -M:mcp"
                    nil)

     "grog-project-search"
     (shell-wrapped (str root "/grog-project-search")
                    "clojure -M:mcp"
                    {"GROG_PROJECTS_DIR" (.getPath (config/projects-dir))
                     "GROG_PROJECT" (or (projects/project-name) "")})}]
    (cond-> servers
      (imap-configured?)
      (assoc "grog-imap"
             (shell-wrapped (str root "/grog-imap")
                            "clojure -M:mcp"
                            (imap-env)))
      (odoo-configured?)
      (assoc "grog-odoo"
             (shell-wrapped (str root "/grog-odoo")
                            "clojure -M:mcp"
                            (odoo-env))))))

(defn debug-dump-config!
  "Write the full ECA config map to the grog debug log.

  Prints to `System/err` explicitly (NOT the bound `*err*`) so the dump always
  lands in the real debug log (`grog-ui.log` via grog-ui's tee), even when
  called from a worker thread whose `*err*` is bound to the transcript pane.
  Called whenever the config is (re)written or ECA is (re)started."
  [^String path merged]
  (.println System/err (str "==== grog: ECA config (re)written -> " path))
  (.println System/err (str "==== model: " (or (:defaultModel merged) "(none)")))
  (.println System/err (json/generate-string merged {:pretty true}))
  (.println System/err "==== end grog: ECA config dump")
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
  "The absolute path of the generated ECA rules markdown for the active project.
  The file is the composed per-project standing context:
    * global SOUL.md (base personality),
    * the project's own SOUL.md (if present) — overrides global on conflicts,
    * the project's loaded context (banner + notes + dialog snapshot).

  Written under the project's `state/` dir so it stays out of the source tree.
  Returns nil if no active project can be resolved."
  ^String []
  (when-let [proj (projects/resolve-active-project)]
    (let [^java.io.File dir (projects/state-dir proj)
          f (io/file dir "eca-rules.md")
          global (soul/read-text)
          project-soul (soul/read-project-text proj)
          ctx (projects/load-context)
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
  "Add a `rules` entry pointing at the active project's generated rules file (if
  any). ECA loads rule files/dirs as standing context on every prompt."
  [cfg]
  (if-let [rules-file (project-rules-file)]
    (update cfg :rules conj {:path rules-file})
    cfg))

(defn generate-config!
  "Produce the merged ECA config map and write it to
  `(generated-config-path)`, dumping it to the debug log. Returns the written path."
  ([] (generate-config! (default-eca-config-path)))
  ([base-path]
   (let [base  (if (.exists (io/file base-path))
                 (try (json/parse-string (slurp (io/file base-path)) true)
                      (catch Exception _ {}))
                 {})
         raw-model (or (config/eca-model)
                       (when-let [m (:defaultModel base)] m))
         ;; ECA resolves models as `provider/name`, so the default model must be
         ;; provider-qualified (`ollama/…`, `openrouter/…`). A bare local id such
         ;; as `qwen3.5:4b-tweaked` would make ECA fail with
         ;; "API url not found … provider 'qwen3.5:4b-tweaked'".
         model (models/qualify-eca-model raw-model
                                         nil
                                         (try (config/llm-url) (catch Exception _ nil)))
         merged (-> base
                    (assoc :mcpServers (grog-mcp-servers))
                    (cond-> model (assoc :defaultModel model))
                    (add-approval!)
                    (add-rules!))
         out (generated-config-path)]
     (spit (io/file out) (json/generate-string merged {:pretty true}))
     (debug-dump-config! out merged)
     out)))
