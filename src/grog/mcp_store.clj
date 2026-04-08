(ns grog.mcp-store
  "Persist MCP server definitions and cached `tools/list` under `:edn-store` (project-scoped like `memory_*`).
  Files: `grog-memory/grog-mcp/servers.edn`, `grog-memory/grog-mcp/tools-cache.edn`
  (or under `Projects/<project>/grog-mcp/` when a project is active)."
  (:require [clojure.string :as str]
            [grog.config :as cfg]
            [grog.edn-store :as store])
  (:import [java.net URLEncoder]))

(def ^:private ^String root-seg "grog-memory")

(defn- enc-seg ^String [^String s]
  (URLEncoder/encode s "UTF-8"))

(defn servers-keypath
  "Vector keypath for `read-leaf` / `write-leaf!`."
  []
  (if-let [p (cfg/active-project-name)]
    [root-seg "Projects" (enc-seg p) "grog-mcp" "servers"]
    [root-seg "grog-mcp" "servers"]))

(defn store-path-hint
  "Human-readable relative path under edn-store root."
  []
  (str (str/join "/" (servers-keypath)) ".edn"))

(defn tools-cache-keypath
  "Leaf keypath for persisted `tools/list` snapshot per server (`:by-server`)."
  []
  (if-let [p (cfg/active-project-name)]
    [root-seg "Projects" (enc-seg p) "grog-mcp" "tools-cache"]
    [root-seg "grog-mcp" "tools-cache"]))

(defn tools-cache-path-hint
  []
  (str (str/join "/" (tools-cache-keypath)) ".edn"))

(defn read-tools-cache-map
  "Returns `{:by-server {server-id {:fingerprint … :tools […]}}}` or `nil`."
  []
  (when (store/configured?)
    (store/read-leaf (tools-cache-keypath))))

(defn write-tools-cache-map!
  [m]
  (store/write-leaf! (tools-cache-keypath) m))

(defn read-servers-map
  "Returns `{:servers [...]}` from disk, or `nil` if missing / not configured."
  []
  (when (store/configured?)
    (store/read-leaf (servers-keypath))))

(defn write-servers-map!
  "`servers` must be a sequential of server maps. Writes `{:servers ...}`."
  [servers-seq]
  (store/write-leaf! (servers-keypath) {:servers (vec servers-seq)}))
