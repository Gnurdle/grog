(ns grog.mcp
  "Model Context Protocol over stdio: **newline-delimited JSON** (same as `@modelcontextprotocol/sdk`
  `serializeMessage` / `ReadBuffer`). Spawns subprocess servers, merges `tools/list`, dispatches `tools/call`."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [grog.edn-store :as store]
            [grog.mcp-store :as mcp-store])
  (:import [java.io ByteArrayOutputStream InputStream OutputStreamWriter]
           [java.lang ProcessBuilder]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent ConcurrentHashMap]
           [java.util.concurrent.atomic AtomicLong]))

(def ^:private rpc-timeout-ms 120000)

(defn- read-line-utf8 ^String [^InputStream in]
  "One LF-terminated line; strip trailing CR. Body is UTF-8 (MCP JSON lines)."
  (let [baos (ByteArrayOutputStream.)]
    (loop []
      (let [b (.read in)]
        (when (neg? b) (throw (ex-info "EOF reading MCP line" {})))
        (if (= b 10)
          (let [raw (.toString baos StandardCharsets/UTF_8)]
            (if (str/ends-with? raw "\r")
              (subs raw 0 (dec (count raw)))
              raw))
          (do (.write baos (bit-and b 0xff))
              (recur)))))))

(defn- read-mcp-json! ^String [^InputStream in]
  "Read one JSON-RPC message line (NDJSON). Skip blank lines."
  (loop []
    (let [line (read-line-utf8 in)
          t (str/trim line)]
      (if (str/blank? t)
        (recur)
        t))))

(defn- write-mcp-json! [^OutputStreamWriter w lock obj]
  (locking lock
    ;; Node MCP SDK: JSON.stringify(message) + '\\n'
    (let [^String line (str (json/generate-string obj) "\n")]
      (.write w line)
      (.flush w))))

(defn- sanitize-server-id [s]
  (-> (str/trim (str s))
      (str/replace #"[^a-zA-Z0-9_-]+" "_")
      (str/replace #"^_+|_+$" "")))

(defonce ^:private !declared-servers (atom []))

(defn- normalize-server-cfg [m]
  (when (and (map? m) (not (false? (:enabled m))))
    (let [id-raw (some-> (:id m) str str/trim not-empty)
          sid (when id-raw (sanitize-server-id id-raw))
          cmd (cond
                (sequential? (:command m)) (vec (map str (:command m)))
                (string? (:command m)) ["/bin/sh" "-lc" (str (:command m))]
                :else nil)
          env (:env m)
          cwd (some-> (:cwd m) str str/trim not-empty)]
      (when (and sid (not (str/blank? sid)) (seq cmd))
        {:id sid
         :command cmd
         :env (when (map? env)
                (into {} (for [[k v] env]
                           [(str (if (keyword? k) (name k) k)) (str v)])))
         :cwd cwd}))))

(defn declared-servers-raw
  "In-memory MCP server maps (same shape as persisted `{:servers [...]}` entries)."
  []
  @!declared-servers)

(defn- normalized-declared-cfgs []
  (->> @!declared-servers (keep normalize-server-cfg) vec))

(defn has-declared-servers?
  "True when in-memory declaration has at least one valid server entry."
  []
  (boolean (seq (normalized-declared-cfgs))))

(defn- configured-mcp-server-ids-longest-first
  "Sanitized `:id` values from declared servers, longest first (prefix-safe split for `<id>_<tool>`)."
  []
  (->> @!declared-servers
       (keep (fn [m]
               (when (and (map? m) (not (false? (:enabled m))))
                 (let [raw (some-> (:id m) str str/trim not-empty)
                       sid (when raw (sanitize-server-id raw))]
                   (when-not (str/blank? sid) sid)))))
       distinct
       (sort-by count >)))

(defn- mcp-tool-fn-name [server-id mcp-tool-name]
  (str server-id "_" mcp-tool-name))

(defn- parse-mcp-tool-fn-name ^clojure.lang.PersistentVector [^String nm]
  (when (and nm (pos? (count nm)) (has-declared-servers?))
    (some (fn [^String sid]
            (let [p (str sid "_")]
              (when (str/starts-with? nm p)
                (let [tname (subs nm (count p))]
                  (when-not (str/blank? tname)
                    [sid tname])))))
          (configured-mcp-server-ids-longest-first))))

(def ^:private admin-tool-names
  #{"mcp_config_load" "mcp_config_save" "mcp_servers_set" "mcp_reload"})

(defn mcp-admin-tool-name?
  [nm]
  (boolean (admin-tool-names (str nm))))

(defn tool-name-mcp?
  "True if `nm` matches `<server-id>_<mcp-tool>` for a declared server (longest-first); not admin tools."
  [nm]
  (when-not (mcp-admin-tool-name? nm)
    (boolean (some-> nm str parse-mcp-tool-fn-name))))

(defn- input-schema->parameters [schema]
  (let [s (or schema {:type "object" :properties {}})]
    (if (map? s)
      (cond-> s
        (not (:type s)) (assoc :type "object"))
      {:type "object" :properties {}})))

(defn- log-mcp-spawn!
  "Announce MCP subprocess (stderr so it stays with other grog diagnostics)."
  [server-id effective-cwd command-vec]
  (binding [*out* *err*]
    (println "grog mcp" (str "[" server-id "]") "cwd" (pr-str effective-cwd))
    (println "grog mcp" (str "[" server-id "]") "command" (pr-str command-vec))
    (println "grog mcp" (str "[" server-id "]") "stderr:")
    (flush)))

(defn- stderr-drain! [^Process proc ^String server-id]
  (doto (Thread.
         ^Runnable
         (fn []
           (try
             (with-open [r (io/reader (.getErrorStream proc))]
               (loop []
                 (when-let [line (.readLine r)]
                   (binding [*out* *err*]
                     (println " " line)
                     (flush))
                   (recur))))
             (catch Exception _ nil))))
    (.setDaemon true)
    (.start)))

(defonce ^:private registry-lock (Object.))

(defonce ^:private !state (atom {:servers {}}))

(defn- next-id! ^long [^AtomicLong al]
  (.incrementAndGet al))

(defn- pending-key
  "Normalize JSON-RPC `id` for the pending map (servers may echo number or string)."
  [rid]
  (cond
    (number? rid) (str (long rid))
    (string? rid) rid
    :else nil))

(defn- make-server!
  [{:keys [id command env cwd]}]
  (let [pb (ProcessBuilder. ^java.util.List command)
        pending (ConcurrentHashMap.)
        al (AtomicLong. 0)
        write-lock (Object.)
        _ (when cwd (.directory pb (java.io.File. cwd)))
        _ (when env
            (let [^java.util.Map m (.environment pb)]
              (doseq [[k v] env]
                (.put m k v))))
        _ (.redirectErrorStream pb false)
        effective-cwd (try
                        (if-let [d (.directory pb)]
                          (.getCanonicalPath d)
                          (System/getProperty "user.dir"))
                        (catch Exception _
                          (or cwd (System/getProperty "user.dir"))))
        _ (log-mcp-spawn! id effective-cwd command)
        proc (.start pb)
        in (.getInputStream proc)
        out (OutputStreamWriter. (.getOutputStream proc) StandardCharsets/UTF_8)]
    (stderr-drain! proc id)
    (let [reader-thread
          (doto (Thread.
                 ^Runnable
                 (fn []
                   (try
                     (while (.isAlive proc)
                       (try
                         (let [line (read-mcp-json! in)
                               t (str/trim line)]
                           ;; Node/Java MCP SDKs use one JSON object per line. Some servers (e.g. xlisp/datascript-mcp-server)
                           ;; print a plain-text banner on stdout first; skip without logging.
                           (when (str/starts-with? t "{")
                             (try
                               (let [msg (json/parse-string t true)]
                                 (when-some [rid (:id msg)]
                                   (when-some [k (pending-key rid)]
                                     (when-some [p (.remove ^ConcurrentHashMap pending k)]
                                       (deliver p msg)))))
                               (catch Exception e
                                 (when (.isAlive proc)
                                   (binding [*out* *err*]
                                     (println "grog mcp [" id "] bad JSON line:" (.getMessage e)))
                                   (Thread/sleep 20))))))
                         (catch Exception e
                           (when (.isAlive proc)
                             (binding [*out* *err*]
                               (println "grog mcp [" id "] read error:" (.getMessage e)))
                             (Thread/sleep 50)))))
                     (catch Exception _ nil))))
            (.setDaemon true)
            (.start))]
      {:id id
       :process proc
       :in in
       :out out
       :write-lock write-lock
       :pending pending
       :req-seq al
       :reader-thread reader-thread})))

(defn- rpc! [srv method params]
  (let [id (long (next-id! (:req-seq srv)))
        kid (str id)
        p (promise)
        _ (.put ^ConcurrentHashMap (:pending srv) kid p)
        msg (cond-> {:jsonrpc "2.0" :id id :method method}
              params (assoc :params params))]
    (try
      (write-mcp-json! (:out srv) (:write-lock srv) msg)
      (let [res (deref p rpc-timeout-ms ::timeout)]
        (if (= ::timeout res)
          {:error {:message "MCP RPC timeout" :code -32000 :data {:method method}}}
          (if-let [err (:error res)]
            {:error err}
            {:ok (:result res)})))
      (finally
        (.remove ^ConcurrentHashMap (:pending srv) kid)))))

(defn- notify! [srv method params]
  (write-mcp-json! (:out srv) (:write-lock srv)
                   (cond-> {:jsonrpc "2.0" :method method}
                     params (assoc :params params))))

(defn- init-server! [srv]
  (let [init (rpc! srv "initialize"
                    {:protocolVersion "2024-11-05"
                     :capabilities {:roots {:listChanged false}}
                     :clientInfo {:name "grog" :version "0.1.0"}})]
    (if (:error init)
      init
      (do (notify! srv "notifications/initialized" {})
          {:ok true :init init}))))

(defn- list-tools-page! [srv cursor]
  (rpc! srv "tools/list" (if cursor {:cursor cursor} {})))

(defn- all-mcp-tools! [srv]
  (loop [cursor nil acc []]
    (let [r (list-tools-page! srv cursor)]
      (if (:error r)
        r
        (let [res (:ok r)
              tools (vec (or (:tools res) []))
              acc2 (into acc tools)
              nxt (:nextCursor res)]
          (if (and nxt (not (str/blank? (str nxt))))
            (recur (str nxt) acc2)
            {:ok acc2}))))))

(defn- stop-server! [srv]
  (when srv
    (try (.destroy (:process srv)) (catch Exception _))
    (try (.interrupt ^Thread (:reader-thread srv)) (catch Exception _))))

(defn stop-all!
  "Kill MCP subprocesses (e.g. when leaving chat)."
  []
  (locking registry-lock
    (doseq [[_ srv] (:servers @!state)]
      (stop-server! srv))
    (swap! !state assoc :servers {})))

(defn- ensure-servers!
  []
  (locking registry-lock
    (when (empty? (:servers @!state))
      (let [cfgs (normalized-declared-cfgs)
            started (reduce (fn [acc {:keys [id] :as c}]
                              (try
                                (let [srv (make-server! c)
                                      in (init-server! srv)]
                                  (if (:error in)
                                    (do (binding [*out* *err*]
                                          (println "grog mcp: server" (pr-str id) "init failed:" (:error in)))
                                        (stop-server! srv)
                                        acc)
                                    (let [tools (all-mcp-tools! srv)]
                                      (if (:error tools)
                                        (do (binding [*out* *err*]
                                              (println "grog mcp: server" (pr-str id) "tools/list failed:" (:error tools)))
                                            (stop-server! srv)
                                            acc)
                                        (assoc acc id (assoc srv :tools (:ok tools)))))))
                                (catch Exception e
                                  (binding [*out* *err*]
                                    (println "grog mcp: server" (pr-str id) "start error:" (.getMessage e)))
                                  acc)))
                            {}
                            cfgs)]
        (swap! !state assoc :servers started)))))

(defn configured?
  "True when at least one valid server is declared in memory (disk may differ until load)."
  []
  (has-declared-servers?))

(defn mcp-servers-running?
  []
  (boolean (seq (:servers @!state))))

(defn tool-specs-dynamic
  "Ollama tools from running MCP servers only (after `mcp_reload` / `reload-running-servers!`)."
  []
  (when (mcp-servers-running?)
    (let [servers (:servers @!state)]
      (vec
       (for [[sid srv] servers
             t (:tools srv)
             :let [tn (str (or (:name t) ""))
                   td (str (or (:description t) ""))
                   schema (:inputSchema t)]
             :when (not (str/blank? tn))]
         {:type "function"
          :function
          {:name (mcp-tool-fn-name sid tn)
           :description (str "[MCP " sid "] " td)
           :parameters (input-schema->parameters schema)}})))))

(defn mcp-admin-tool-specs
  "Load/save/replace declaration and start subprocesses — requires `:edn-store` in grog.edn."
  []
  (when (store/configured?)
    [{:type "function"
      :function
      {:name "mcp_config_load"
       :description (str "Load MCP server list from edn-store file (project-scoped: "
                         (mcp-store/store-path-hint)
                         "). Replaces in-memory list and stops running MCP; call mcp_reload to spawn.")
       :parameters {:type "object" :properties {}}}}
     {:type "function"
      :function
      {:name "mcp_config_save"
       :description (str "Save current in-memory MCP server list to edn-store at " (mcp-store/store-path-hint))
       :parameters {:type "object" :properties {}}}}
     {:type "function"
      :function
      {:name "mcp_servers_set"
       :description (str "Replace MCP server declarations entirely. Each entry: :id, :command (vector or shell string), optional :cwd :env :enabled. "
                         "Stops running MCP. Call mcp_reload to start.")
       :parameters {:type "object"
                    :properties {:servers {:type "array"
                                           :description "Vector of server maps"}}
                    :required ["servers"]}}}
     {:type "function"
      :function
      {:name "mcp_reload"
       :description "Stop MCP subprocesses, spawn all declared servers, refresh tools/list for Ollama (next round includes fs_* etc.)."
       :parameters {:type "object" :properties {}}}}]))

(defn tool-specs-for-chat
  "Admin tools + dynamic MCP tools (if processes are up)."
  []
  (vec (concat (or (mcp-admin-tool-specs) []) (or (tool-specs-dynamic) []))))

(defn run-tool!
  "Execute tools/call; returns JSON string for Ollama tool result."
  [^String fn-name arguments]
  (if-let [[sid tname] (parse-mcp-tool-fn-name fn-name)]
    (try
      (if-not (mcp-servers-running?)
        (json/generate-string
         {:error true
          :message "MCP servers are not running — call tool mcp_reload (or /mcp reload) after declaring servers."})
        (if-let [srv (get (:servers @!state) sid)]
          (let [args (cond
                       (map? arguments) arguments
                       (string? arguments) (try (json/parse-string arguments true)
                                                (catch Exception _ {}))
                       :else {})
                r (rpc! srv "tools/call" {:name tname :arguments args})]
            (if (:error r)
              (json/generate-string {:error true :mcp (:error r)})
              (let [content (get-in r [:ok :content])]
                (json/generate-string
                 {:ok true
                  :mcp_tool tname
                  :content (if (sequential? content)
                             (str/join "\n\n"
                                       (for [c content]
                                         (case (keyword (str/lower-case (str (:type c))))
                                           :text (str (:text c))
                                           (pr-str c))))
                             (pr-str (:ok r)))}))))
          (json/generate-string {:error true :message (str "MCP server not in running set: " sid)})))
      (catch Exception e
        (json/generate-string {:error true :message (.getMessage e)})))
    (json/generate-string {:error true :message "not an mcp tool name"})))

(defn tool-log-summary [^String fn-name arguments]
  (cond
    (mcp-admin-tool-name? fn-name)
    {:mcp-admin fn-name :args (if (string? arguments)
                                 (subs arguments 0 (min 120 (count arguments)))
                                 (str arguments))}
    :else
    (when-let [[sid tname] (parse-mcp-tool-fn-name fn-name)]
      {:server sid :tool tname :args (if (string? arguments)
                                       (subs arguments 0 (min 120 (count arguments)))
                                       (str arguments))})))

(defn- parse-tool-args [arguments]
  (cond (map? arguments) arguments
        (string? arguments) (try (json/parse-string arguments true)
                                 (catch Exception _ {}))
        :else {}))

(defn try-load-declared-config!
  "Read MCP servers from edn-store (`grog-mcp/servers.edn`, project-scoped). Clears running subprocesses."
  []
  (when (store/configured?)
    (let [raw (mcp-store/read-servers-map)
          sv (when (map? raw) (:servers raw))]
      (reset! !declared-servers (vec (if (sequential? sv) sv [])))
      (stop-all!))))

(defn save-declared-config-to-store!
  "Persist current in-memory declarations."
  []
  (if-not (store/configured?)
    (throw (ex-info "edn-store not configured" {}))
    (mcp-store/write-servers-map! @!declared-servers)))

(defn set-declared-servers-from-edn-string!
  "Parse `{:servers [...]}` or a vector of server maps; replaces in-memory list and stops MCP."
  [^String s]
  (when (str/blank? s)
    (throw (ex-info "expected EDN after /mcp set" {})))
  (let [o (edn/read-string s)]
    (cond
      (and (map? o) (sequential? (:servers o)))
      (do (reset! !declared-servers (mapv walk/keywordize-keys (:servers o)))
          (stop-all!))
      (sequential? o)
      (do (reset! !declared-servers (mapv walk/keywordize-keys o))
          (stop-all!))
      :else
      (throw (ex-info "expected {:servers [...]} or a vector of server maps" {:read o})))))

(defn reload-running-servers!
  "Stop MCP, spawn from declared list, return summary (for tool + /mcp)."
  []
  (stop-all!)
  (ensure-servers!)
  (let [live (:servers @!state)]
    {:ok (boolean (seq live))
     :server-ids (vec (keys live))
     :tool-count (reduce + (map (comp count :tools val) live))}))

(defn run-mcp-admin-tool!
  "Returns JSON string for Ollama tool role."
  [^String nm arguments]
  (try
    (let [m (parse-tool-args arguments)]
      (case nm
        "mcp_config_load"
        (if-not (store/configured?)
          (json/generate-string {:error true :message "edn-store not configured"})
          (do (try-load-declared-config!)
              (json/generate-string {:ok true
                                     :declared (count (normalized-declared-cfgs))
                                     :file (mcp-store/store-path-hint)})))
        "mcp_config_save"
        (if-not (store/configured?)
          (json/generate-string {:error true :message "edn-store not configured"})
          (do (save-declared-config-to-store!)
              (json/generate-string {:ok true :file (mcp-store/store-path-hint)
                                     :servers (count @!declared-servers)})))
        "mcp_servers_set"
        (let [sv (or (:servers m) (get m "servers"))]
          (if-not (sequential? sv)
            (json/generate-string {:error true :message ":servers must be an array"})
            (let [norm (mapv walk/keywordize-keys sv)]
              (reset! !declared-servers norm)
              (stop-all!)
              (json/generate-string {:ok true :declared (count (normalized-declared-cfgs))
                                     :hint "call mcp_reload to start subprocesses"}))))
        "mcp_reload"
        (if-not (has-declared-servers?)
          (json/generate-string {:error true :message "No valid servers declared — mcp_servers_set or mcp_config_load first"})
          (json/generate-string (reload-running-servers!)))
        (json/generate-string {:error true :message (str "unknown mcp admin tool: " nm)})))
    (catch Exception e
      (json/generate-string {:error true :message (.getMessage e)}))))

(defn startup-status-line
  "Does not start subprocesses. See /mcp and tools mcp_config_* / mcp_reload."
  []
  (if-not (store/configured?)
    "mcp: needs :edn-store — then /mcp, tools mcp_config_load | mcp_config_save | mcp_servers_set | mcp_reload"
    (str "mcp: " (mcp-store/store-path-hint)
         " — " (count (normalized-declared-cfgs)) " declared"
         (when (mcp-servers-running?)
           (str ", " (count (:servers @!state)) " running, "
                (reduce + (map (comp count :tools val) (:servers @!state))) " tools"))
         " — /mcp help")))
