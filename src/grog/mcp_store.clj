(ns grog.mcp-store
  "Persist MCP server definitions under `:edn-store` (project-scoped like `memory_*`).
  File: `grog-memory/grog-mcp/servers.edn` or `grog-memory/Projects/<project>/grog-mcp/servers.edn`."
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

(defn read-servers-map
  "Returns `{:servers [...]}` from disk, or `nil` if missing / not configured."
  []
  (when (store/configured?)
    (store/read-leaf (servers-keypath))))

(defn write-servers-map!
  "`servers` must be a sequential of server maps. Writes `{:servers ...}`."
  [servers-seq]
  (store/write-leaf! (servers-keypath) {:servers (vec servers-seq)}))
