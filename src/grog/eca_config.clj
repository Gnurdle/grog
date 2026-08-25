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
            [clojure.string :as str]
            [grog.config :as config]
            [grog.models :as models]))

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
  "Where grog writes the grog-odoo MCP instances config."
  ^String []
  (let [d (io/file (str (System/getProperty "user.home") "/.config/grog"))]
    (.mkdirs d)
    (str d "/odoo-instances.json")))

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
  "Instances list from grog.edn's `:odoo`.

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

(defn- odoo-env
  "Env map for the grog-odoo MCP server.

  New mode: grog.edn `:odoo {:instances [...]}` is written to
  `~/.config/grog/odoo-instances.json` and handed to the MCP server via
  `GROG_ODOO_CONFIG` — the server then lets the model select among exactly
  those pre-configured instances (no arbitrary endpoints).

  Legacy mode (no `:instances`): single-instance `:url`/`:db`/`:user`/`:password`
  is passed as `GROG_ODOO_*` env vars. Values may reference `${ENV}` /
  `${ENV:-default}`."
  []
  (let [instances (odoo-instances-data)]
    (if (seq instances)
      (let [path (odoo-instances-path)]
        (spit (io/file path) (json/generate-string {:instances instances} {:pretty true}))
        {"GROG_ODOO_CONFIG" path})
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

(defn default-eca-config-path
  "The standard ECA config file this generator starts from."
  ^String []
  (str (System/getProperty "user.home") "/.config/eca/config.json"))

(defn generated-config-path
  "Where grog writes its merged ECA config."
  ^String []
  (let [d (io/file (str (System/getProperty "user.home") "/.config/grog"))]
    (.mkdirs d)
    (str d "/eca-config.generated.json")))

(defn approved-tools-path
  "Persistent store of tool names grog has been asked to always allow."
  ^String []
  (str (System/getProperty "user.home") "/.config/grog/approved-tools.edn"))

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
  "Where grog writes the grog-imap MCP account metadata config."
  ^String []
  (let [d (io/file (str (System/getProperty "user.home") "/.config/grog"))]
    (.mkdirs d)
    (str d "/imap-accounts.json")))

(defn- imap-accounts-data
  "Account *metadata* from grog.edn's `:imap` (never secrets)."
  []
  (let [accts (get-in (config/grog) [:imap :accounts])]
    (when (seq accts)
      {:accounts (mapv #(clean-imap-account
                         (select-keys % [:name :host :port :tls :user :sasl
                                         :oauth :read-only]))
                       accts)})))

(defn- imap-env
  "Env map for the grog-imap MCP server: write account *metadata* to
  ~/.config/grog/imap-accounts.json and hand it via GROG_IMAP_CONFIG. The
  server resolves the secret itself from its per-account file — never here."
  []
  (let [data (imap-accounts-data)
        path (imap-instances-path)]
    (spit (io/file path) (json/generate-string data {:pretty true}))
    {"GROG_IMAP_CONFIG" path}))

(defn grog-mcp-servers
  "The grog MCP server specs, keyed by server id."
  []
  (let [root (grog-root)
        servers
        {"grog-docs"
     (shell-wrapped (str root "/grog-docs")
                    "clojure -M:mcp -m grog-docs.main"
                    nil)

     "grog-imaging"
     (shell-wrapped (str root "/grog-imaging")
                    "clojure -M:mcp -m grog-imaging.main"
                    nil)

     "grog-memory"
     (shell-wrapped (str root "/grog-memory")
                    "PYTHONPATH=src .venv/bin/python -m grog_memory.server"
                    {"GROG_MEMORY_DB" (str root "/.grog-memory.db")})

     "grog-odoo"
     (shell-wrapped (str root "/grog-odoo")
                    "clojure -M:mcp -m grog-odoo.main"
                    (odoo-env))

     "grog-office"
     (shell-wrapped (str root "/grog-office")
                    "clojure -M:mcp -m grog-office.main"
                    nil)

     "grog-search"
     (shell-wrapped (str root "/grog-search")
                    "clojure -M:mcp -m grog-search.main"
                    nil)

     "grog-babashka"
     (shell-wrapped (str root "/grog-babashka")
                    "clojure -M:mcp -m grog-babashka.main"
                    nil)}]
    (cond-> servers
      (seq (get-in (config/grog) [:imap :accounts]))
      (assoc "grog-imap"
             (shell-wrapped (str root "/grog-imap")
                            "clojure -M:mcp -m grog-imap.main"
                            (imap-env))))))

(defn debug-dump-config!
  "Write the full ECA config map to the grog debug log.

  Prints to `System/err` explicitly (NOT the bound `*err*`) so the dump always
  lands in the real debug log (`~/.grog-ui.log` via grog-ui's tee), even when
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
                    (add-approval!))
         out (generated-config-path)]
     (spit (io/file out) (json/generate-string merged {:pretty true}))
     (debug-dump-config! out merged)
     out)))
