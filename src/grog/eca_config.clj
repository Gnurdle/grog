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
            [clojure.java.io :as io]
            [grog.config :as config]))

(defn- abs-path
  "Absolute, normalized filesystem path string."
  ^String [p]
  (str (.toAbsolutePath (.normalize (.toPath (java.io.File. (str p)))))))

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

(defn- shell-wrapped
  "An MCP stdio server spec that first `cd`s into `dir`, so `clojure -M:...`
  resolves that project's deps.edn regardless of ECA's working directory."
  [dir cmdline env]
  (cond-> {"command" "bash"
           "args" ["-lc" (str "cd '" dir "' && " cmdline)]}
    env (assoc "env" env)))

(defn grog-mcp-servers
  "The three grog MCP server specs, keyed by server id."
  []
  (let [root (grog-root)]
    {"grog-imaging"
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
                    {"GROG_ODOO_URL" ""
                     "GROG_ODOO_DB" ""
                     "GROG_ODOO_USER" ""
                     "GROG_ODOO_PASSWORD" ""})}))

(defn generate-config!
  "Produce the merged ECA config map and write it to
  `(generated-config-path)`. Returns the written path."
  ([] (generate-config! (default-eca-config-path)))
  ([base-path]
   (let [base  (if (.exists (io/file base-path))
                 (try (json/parse-string (slurp (io/file base-path)) true)
                      (catch Exception _ {}))
                 {})
         model (or (config/eca-model)
                   (when-let [m (:defaultModel base)] m))
         merged (cond-> base
                  true (assoc :mcpServers (grog-mcp-servers))
                  model (assoc :defaultModel model))
         out (generated-config-path)]
     (spit (io/file out) (json/generate-string merged {:pretty true}))
     out)))
