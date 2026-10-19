(ns grog.agent.mcp
  "The MCP CLIENT half — what ECA does for grog today, and what a grog-owned loop
  has to do itself: spawn the tool server, handshake, list tools, call them.

  The spawn spec is NOT invented here: it comes from grog's own
  `grog.eca-config/grog-mcp-servers`, which already knows the jar-versus-source
  choice and merges every per-server config file into the child's environment.
  One process serves the whole toolbelt (`grog_mcp.main` registers every server's
  tools in a single McpServer), so the model sees the same tool names it sees
  under ECA — hence the same `grog-mcp__` prefix.

  Protocol: JSON-RPC 2.0 over stdio, one JSON object per line. A reader thread
  routes frames by `:id` into promises (notifications have no id and are dropped
  for now), so interleaving cannot mis-route a response the way a naive
  line-scanner can.

  The schema check is deliberate: an `inputSchema` that is a STRING rather than a
  JSON Schema object is the bug that cost this project days in September 2026
  (strict providers reject the entire request). This client refuses to ship one,
  by name and with the tool's id, instead of forwarding it."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io PrintWriter)
           (java.util.concurrent TimeUnit)))

(def ^:private default-timeout-ms 60000)

(defn- norm-spec
  "The spec arrives from grog's generated-config shape, which uses STRING keys
  (it is written to JSON as-is), while this client reads keywords. The same
  boundary that bit the tool arguments — normalise it here rather than requiring
  callers to know which of the two shapes they hold."
  [spec]
  (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) v])) spec))

(defn start!
  "Spawn a server. `spec` is {:command :args :env} (grog's own shape, or the
  string-keyed JSON form); `:env` is merged over the current environment.
  Returns a connection map."
  [spec]
  (let [{:keys [command args env cwd url]} (norm-spec spec)]
    (when url
      ;; grog.eca-config returns a Streamable-HTTP spec ({:url …}) when its daemon
      ;; is running. This spike speaks stdio only — say so, by name, instead of
      ;; failing later with a confusing "no :command".
      (throw (ex-info (str "mcp: the spec is a Streamable-HTTP url (" url ") — this spike implements "
                           "stdio only; unset GROG_MCP_HTTP (or stop the daemon) to get the stdio spec")
                      {:spec spec})))
    (when-not command
      (throw (ex-info "mcp: no :command in the server spec" {:spec spec})))
    (let [pb (ProcessBuilder. ^java.util.List (into [command] (vec args)))]
      (when cwd (.directory pb (io/file cwd)))
      (when (seq env)
        (let [^java.util.Map e (.environment pb)]
          (doseq [[k v] env] (when v (.put e (name k) (str v))))))
    (let [proc (.start pb)
          writer (PrintWriter. (io/writer (.getOutputStream proc)) true)
          !pending (atom {})
          !id (atom 100)
          log (java.util.concurrent.LinkedBlockingQueue.)
          _ (doto (Thread. (fn []
                             (try
                               (with-open [r (io/reader (.getInputStream proc))]
                                 (loop []
                                   (when-let [line (.readLine r)]
                                     (let [m (try (json/parse-string line true)
                                                  (catch Throwable _ nil))]
                                       (when m
                                         (if-let [i (:id m)]
                                           (when-let [p (get @!pending i)]
                                             (deliver p (if (:error m) {:error (:error m)}
                                                                         {:result (:result m)}))
                                             (swap! !pending dissoc i))
                                           ;; a notification (e.g. logging) — ignored
                                           nil)))
                                     (recur))))
                               (catch Throwable t
                                 (swap! !pending
                                        (fn [m] (doseq [[_ p] m] (deliver p {:error {:message (str "mcp reader died: " t)}}))
                                          {}))))))
                    (.setDaemon true)
                    (.start))
          _ (doto (Thread. (fn []
                             (try
                               (with-open [r (io/reader (.getErrorStream proc))]
                                 (loop [] (when-let [l (.readLine r)] (.put log l) (recur))))
                               (catch Throwable _ nil))))
                    (.setDaemon true)
                    (.start))]
      {:proc proc :writer writer :pending !pending :id !id :stderr log :command command}))))

(defn- request!
  "Send a request and wait for its response. Throws on timeout or a JSON-RPC error."
  [{:keys [writer pending id]} method params & [timeout-ms]]
  (let [i (swap! id inc)
        p (promise)]
    (swap! pending assoc i p)
    (locking writer
      (.println ^PrintWriter writer (json/generate-string {:jsonrpc "2.0" :id i
                                                           :method method :params (or params {})}))
      (.flush ^PrintWriter writer))
    (let [r (deref p (long (or timeout-ms default-timeout-ms)) ::timeout)]
      (swap! pending dissoc i)
      (cond
        (= r ::timeout) (throw (ex-info (str "mcp: no answer to " method " within " (or timeout-ms default-timeout-ms) "ms")
                                        {:method method}))
        (:error r) (throw (ex-info (str "mcp: " method " failed: " (get-in r [:error :message] (pr-str (:error r))))
                                   {:method method :error (:error r)}))
        :else (:result r)))))

(defn- notify! [{:keys [writer]} method params]
  (locking writer
    (.println ^PrintWriter writer (json/generate-string {:jsonrpc "2.0" :method method :params (or params {})}))
    (.flush ^PrintWriter writer)))

(defn handshake!
  "initialize + notifications/initialized, per the MCP spec."
  [conn]
  (let [res (request! conn "initialize"
                      {:protocolVersion "2024-11-05"
                       :capabilities {}
                       :clientInfo {:name "grog-agent" :version "0.1.0"}}
                      20000)]
    (notify! conn "notifications/initialized" {})
    res))

(defn list-tools!
  "Every tool the server advertises (follows nextCursor when it pages)."
  [conn]
  (loop [acc [] cursor nil]
    (let [res (request! conn "tools/list" (cond-> {} cursor (assoc :cursor cursor)))
          tools (:tools res)]
      (if-let [next-cursor (:nextCursor res)]
        (recur (into acc tools) next-cursor)
        (into acc tools)))))

(defn ->provider-tools
  "MCP tool descriptors → OpenAI `tools`. The schema must be an OBJECT; a string
  is refused loudly (see the ns docstring)."
  [tools prefix]
  (mapv (fn [{:keys [name description inputSchema]}]
          (when (string? inputSchema)
            (throw (ex-info (str "mcp: " name " advertises inputSchema as a STRING — "
                                 "strict providers reject the whole request (September 2026 bug)")
                            {:tool name})))
          {:type "function"
           :function {:name (str prefix name)
                      :description (or description "")
                      :parameters (or inputSchema {:type "object" :properties {}})}})
        tools))

(defn call-tool!
  "Call one tool by its SERVER-side name. Returns {:text :error? :images :raw}."
  [conn name arguments & [timeout-ms]]
  (let [res (request! conn "tools/call" {:name name :arguments (or arguments {})} timeout-ms)
        content (:content res)
        text (->> content
                  (keep (fn [c] (when (= "text" (:type c)) (:text c))))
                  (str/join "\n"))
        images (count (filter #(= "image" (:type %)) content))]
    {:text text
     :error? (boolean (:isError res))
     ;; MCP can return image blocks; this loop does NOT feed them to the model yet
     ;; (that needs the multimodal message path) — counted so the gap is visible.
     :images images
     :raw res}))

(defn active-project
  "The project grog is working in, resolved grog's own way. nil outside a session
  (the spike's CLI can pass one explicitly)."
  []
  (try ((requiring-resolve 'grog.projects/resolve-active-project))
       (catch Throwable _ nil)))

(defn server-spec
  "grog's own spec for the consolidated tool server, or a clear failure.

  The PROJECT matters: every per-server config is written per project (the memory
  store, project search, odoo, imap), so the server's toolbelt depends on it.
  Resolved through `grog.eca-config/grog-mcp-servers` so the jar/source choice and
  those env vars stay in ONE place rather than being duplicated here."
  ([] (server-spec nil))
  ([project]
   (let [p (or project (active-project))]
     (when-not p
       (throw (ex-info "mcp: no project resolved — pass one explicitly (grog.projects/resolve-active-project returned nil)"
                       {})))
     (let [specs (try (requiring-resolve 'grog.eca-config/grog-mcp-servers)
                      (catch Throwable e
                        (throw (ex-info "mcp: grog.eca-config is not loadable" {:cause (str e)}))))
           m (specs p)]
       (or (some-> (get m "grog-mcp") norm-spec)
           (throw (ex-info (str "mcp: grog.eca-config returned no grog-mcp spec for project " (pr-str p))
                           {:project p :keys (keys m)})))))))

(defn stop! [{:keys [^Process proc]}]
  (when (and proc (.isAlive proc))
    (.destroy proc)
    (when-not (.waitFor proc 3 TimeUnit/SECONDS) (.destroyForcibly proc))))

(defn stderr-lines
  "Whatever the child has written to stderr so far (for error reports)."
  [{:keys [stderr]}]
  (let [out (java.util.ArrayList.)]
    (.drainTo ^java.util.concurrent.LinkedBlockingQueue stderr out)
    (mapv str out)))
