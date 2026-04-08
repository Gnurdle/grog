(ns grog.mcp
  "Model Context Protocol over stdio: **newline-delimited JSON** (same as `@modelcontextprotocol/sdk`
  `serializeMessage` / `ReadBuffer`).

  Declarations live in edn-store (`servers.edn`). On **define/reload**, each server is started briefly,
  `tools/list` is fetched, results are persisted to `tools-cache.edn`, then processes stop. Ollama sees
  tools from that cache without subprocesses running. A server process is started **on first** matching
  `tools/call`, then kept alive until `stop-all!` (e.g. chat exit or config reload)."
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

(defonce ^:private !tool-cache
  ;; server-id string → {:fingerprint long :tools [mcp-tool-map …]}
  (atom {}))

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

(defn parse-shell-ish-argv
  "Split a line into argv tokens; double-quoted and single-quoted segments stay one token."
  [^String s]
  (let [s (str/trim (or s ""))]
    (if (str/blank? s)
      []
      (->> (re-seq #"\"([^\"]*)\"|'([^']*)'|(\S+)" s)
           (mapv (fn [[_ dq sq w]] (first (filter some? [dq sq w]))))
           (vec)))))

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
                     (letfn [(read-one! []
                               (try
                                 (let [line (read-mcp-json! in)
                                       t (str/trim line)]
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
                                           (Thread/sleep 20)))))
                                   :continue)
                                 (catch Exception e
                                   (let [msg (.getMessage e)]
                                     (if (= "EOF reading MCP line" msg)
                                       ;; Child closed stdout (normal when the process is stopped).
                                       :stop
                                       (do
                                         (when (.isAlive proc)
                                           (binding [*out* *err*]
                                             (println "grog mcp [" id "] read error:" msg))
                                           (Thread/sleep 50))
                                         :continue))))))]
                       (loop []
                         (when (.isAlive proc)
                           (case (read-one!)
                             :stop nil
                             :continue (recur)))))
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

(defn- cfg-fingerprint
  "Invalidate cached `tools/list` when command/cwd/env/id change."
  [c]
  (hash (select-keys c [:id :command :cwd :env])))

(defn- probe-one-server-for-tools!
  "Start server, initialize, return tools/list, always stop process."
  [{:keys [id] :as c}]
  (try
    (let [srv (make-server! c)
          in (init-server! srv)]
      (try
        (if (:error in)
          {:error (str "init failed: " (pr-str (:error in))) :id id}
          (let [tools (all-mcp-tools! srv)]
            (if (:error tools)
              {:error (str "tools/list failed: " (pr-str (:error tools))) :id id}
              {:ok (:ok tools) :id id})))
        (finally (stop-server! srv))))
    (catch Exception e
      {:error (.getMessage e) :id id})))

(defn- start-new-mcp-server! [^String sid cfg]
  (let [srv (make-server! cfg)
        in (init-server! srv)]
    (if (:error in)
      (do (stop-server! srv)
          (throw (ex-info "MCP initialize failed" {:id sid :mcp (:error in)})))
      (let [tools (all-mcp-tools! srv)]
        (if (:error tools)
          (do (stop-server! srv)
              (throw (ex-info "MCP tools/list failed" {:id sid :mcp (:error tools)})))
          (let [srv2 (assoc srv :tools (:ok tools))]
            (swap! !state update :servers assoc sid srv2)
            srv2))))))

(defn- ensure-server-running!
  "Start `sid` if needed; returns server state map with :process :tools …"
  ^clojure.lang.IPersistentMap [^String sid]
  (locking registry-lock
    (or (get-in @!state [:servers sid])
        (let [cfg (some #(when (= sid (:id %)) %) (normalized-declared-cfgs))]
          (when-not cfg
            (throw (ex-info "unknown MCP server id" {:id sid})))
          (start-new-mcp-server! sid cfg)))))

(defn configured?
  "True when at least one valid server is declared in memory (disk may differ until load)."
  []
  (has-declared-servers?))

(defn mcp-servers-running?
  []
  (boolean (seq (:servers @!state))))

(defn- mcp-tool-maps->specs [^String sid tools]
  (vec
   (for [t tools
         :let [tn (str (or (:name t) ""))
               td (str (or (:description t) ""))
               schema (:inputSchema t)]
         :when (not (str/blank? tn))]
     {:type "function"
      :function
      {:name (mcp-tool-fn-name sid tn)
       :description (str "[MCP " sid "] " td)
       :parameters (input-schema->parameters schema)}})))

(defn tool-specs-from-cache
  "Ollama tools from persisted `tools/list` (fingerprint must match current declaration)."
  []
  (vec
   (mapcat
    (fn [c]
      (let [sid (:id c)
            fp (cfg-fingerprint c)
            ent (get @!tool-cache sid)]
        (when (and ent (= fp (:fingerprint ent)))
          (mcp-tool-maps->specs sid (:tools ent)))))
    (normalized-declared-cfgs))))

(defn tool-specs-dynamic
  "Deprecated name: specs come from cache, not running processes."
  []
  (tool-specs-from-cache))

(defn mcp-admin-tool-specs
  "Load/save/replace declarations and refresh cached `tools/list` — requires `:edn-store` in grog.edn."
  []
  (when (store/configured?)
    [{:type "function"
      :function
      {:name "mcp_config_load"
       :description (str "Load MCP server list from edn-store (" (mcp-store/store-path-hint)
                         ") and tools cache (" (mcp-store/tools-cache-path-hint)
                         "). Stops running MCP subprocesses.")
       :parameters {:type "object" :properties {}}}}
     {:type "function"
      :function
      {:name "mcp_config_save"
       :description (str "Save current in-memory MCP server list to edn-store at " (mcp-store/store-path-hint))
       :parameters {:type "object" :properties {}}}}
     {:type "function"
      :function
      {:name "mcp_servers_set"
       :description (str "Replace MCP server declarations. Each entry: :id, :command (vector or shell string), optional :cwd :env :enabled. "
                         "Probes each server, updates " (mcp-store/tools-cache-path-hint) ", stops processes.")
       :parameters {:type "object"
                    :properties {:servers {:type "array"
                                           :description "Vector of server maps"}}
                    :required ["servers"]}}}
     {:type "function"
      :function
      {:name "mcp_reload"
       :description (str "Re-probe every declared server (tools/list), refresh " (mcp-store/tools-cache-path-hint)
                         ", stop subprocesses. Ollama then sees updated MCP tools without leaving servers running.")
       :parameters {:type "object" :properties {}}}}]))

(defn tool-specs-for-chat
  "Admin tools + MCP tools from cached tools/list (subprocesses start on first tools/call)."
  []
  (vec (concat (or (mcp-admin-tool-specs) []) (tool-specs-from-cache))))

(defn run-tool!
  "Execute tools/call; starts the backing MCP process on demand if needed."
  [^String fn-name arguments]
  (if-let [[sid tname] (parse-mcp-tool-fn-name fn-name)]
    (try
      (let [srv (ensure-server-running! sid)
            args (cond
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

(defn- cache-map-key->sid [k]
  (str (if (keyword? k) (name k) k)))

(defn load-tool-cache-from-disk!
  "Read `tools-cache.edn` into `!tool-cache` (project-scoped). Call after declarations are loaded."
  []
  (reset! !tool-cache {})
  (when (store/configured?)
    (when-let [raw (mcp-store/read-tools-cache-map)]
      (when-let [by (:by-server raw)]
        (reset! !tool-cache
                (into {}
                      (for [[k v] by
                            :when (map? v)]
                        [(cache-map-key->sid k)
                         {:fingerprint (:fingerprint v)
                          :tools (vec (or (:tools v) []))}])))))))

(defn probe-all-and-persist-cache!
  "Start each declared server briefly, fetch tools/list, write tools-cache.edn, leave no processes running."
  []
  (if-not (store/configured?)
    (throw (ex-info "edn-store not configured" {}))
    (do
      (stop-all!)
      (let [results (for [c (normalized-declared-cfgs)]
                      (let [sid (:id c)
                            fp (cfg-fingerprint c)
                            r (probe-one-server-for-tools! c)]
                        [sid fp r]))
            by-server
            (into {}
                  (keep (fn [[sid fp r]]
                          (when (:ok r)
                            [sid {:fingerprint fp :tools (vec (:ok r))}])))
                  results)]
        (doseq [[sid _ r] results :when (:error r)]
          (binding [*out* *err*]
            (println "grog mcp: probe" (pr-str (or (:id r) sid)) "-" (:error r))))
        (reset! !tool-cache by-server)
        (mcp-store/write-tools-cache-map! {:by-server by-server})
        {:ok true
         :probed (count results)
         :cached-servers (count by-server)
         :tool-count (reduce + (map (comp count :tools val) by-server))}))))

(defn- find-server-index-by-sanitized-id [^String id-str]
  (let [want (sanitize-server-id id-str)]
    (first (keep-indexed (fn [i m]
                           (when (= want (sanitize-server-id (str (:id m))))
                             i))
                         @!declared-servers))))

(defn- replace-declared-and-probe!
  "Replace declaration vector, stop subprocesses, re-probe or clear cache.
  Returns the probe summary map (or `{:ok true :no-edn-store true}` when store is off)."
  [servers-vec]
  (reset! !declared-servers (vec servers-vec))
  (stop-all!)
  (if (store/configured?)
    (probe-all-and-persist-cache!)
    (do (reset! !tool-cache {})
        {:ok true :no-edn-store true})))

(defn add-declared-server-with-command!
  "`argv` is command vector (e.g. from `parse-shell-ish-argv`). Id is normalized via `sanitize-server-id`."
  [^String id-str argv]
  (when (empty? argv)
    (throw (ex-info "command argv empty (need tokens after --)" {})))
  (let [sid (sanitize-server-id id-str)]
    (when (str/blank? sid)
      (throw (ex-info "invalid server id" {:id id-str})))
    (when (some? (find-server-index-by-sanitized-id sid))
      (throw (ex-info "server id already exists" {:id sid})))
    (replace-declared-and-probe!
     (conj @!declared-servers {:id sid :command (vec (map str argv))}))))

(defn remove-declared-server-by-id!
  [^String id-str]
  (let [want (sanitize-server-id id-str)
        sv (vec (remove (fn [m] (= want (sanitize-server-id (str (:id m)))))
                        @!declared-servers))]
    (when (= (count sv) (count @!declared-servers))
      (throw (ex-info "no such server id" {:id id-str})))
    (replace-declared-and-probe! sv)))

(defn replace-declared-server-command-by-id!
  [^String id-str argv]
  (when (empty? argv)
    (throw (ex-info "command argv empty" {})))
  (if-let [i (find-server-index-by-sanitized-id id-str)]
    (replace-declared-and-probe!
     (update @!declared-servers i assoc :command (vec (map str argv))))
    (throw (ex-info "no such server id" {:id id-str}))))

(defn set-declared-server-cwd-by-id!
  "`path` blank, `-`, `clear`, or `none` (case-insensitive) clears :cwd."
  [^String id-str path]
  (if-let [i (find-server-index-by-sanitized-id id-str)]
    (replace-declared-and-probe!
     (update @!declared-servers i
             (fn [m]
               (let [raw (some-> path str str/trim)
                     p (when raw
                         (if (contains? #{"-" "clear" "none"} (str/lower-case raw))
                           nil
                           raw))]
                 (if (str/blank? p)
                   (dissoc m :cwd)
                   (assoc m :cwd p))))))
    (throw (ex-info "no such server id" {:id id-str}))))

(defn set-declared-server-env-by-id!
  "Merge one env var (`val` nil removes key). Keys are strings."
  [^String id-str ^String key ^String val]
  (when (str/blank? key)
    (throw (ex-info "env key empty" {})))
  (if-let [i (find-server-index-by-sanitized-id id-str)]
    (replace-declared-and-probe!
     (update @!declared-servers i
             (fn [m]
               (let [k (str key)
                     env (or (:env m) {})]
                 (assoc m :env
                        (if (and val (not (str/blank? (str val))))
                          (assoc env k (str val))
                          (dissoc env k)))))))
    (throw (ex-info "no such server id" {:id id-str}))))

(defn set-declared-server-enabled-by-id!
  [^String id-str enabled?]
  (if-let [i (find-server-index-by-sanitized-id id-str)]
    (replace-declared-and-probe!
     (update @!declared-servers i
             (fn [m]
               (if enabled?
                 (dissoc m :enabled)
                 (assoc m :enabled false)))))
    (throw (ex-info "no such server id" {:id id-str}))))

(defn try-load-declared-config!
  "Read MCP servers from edn-store (`grog-mcp/servers.edn`, project-scoped). Clears running subprocesses; loads tools cache."
  []
  (when (store/configured?)
    (let [raw (mcp-store/read-servers-map)
          sv (when (map? raw) (:servers raw))]
      (reset! !declared-servers (vec (if (sequential? sv) sv [])))
      (stop-all!)
      (load-tool-cache-from-disk!))))

(defn save-declared-config-to-store!
  "Persist current in-memory declarations."
  []
  (if-not (store/configured?)
    (throw (ex-info "edn-store not configured" {}))
    (mcp-store/write-servers-map! @!declared-servers)))

(defn set-declared-servers-from-edn-string!
  "Parse `{:servers [...]}` or a vector of server maps; replaces in-memory list, probes and caches tools."
  [^String s]
  (when (str/blank? s)
    (throw (ex-info "expected EDN after /mcp set" {})))
  (let [o (edn/read-string s)]
    (cond
      (and (map? o) (sequential? (:servers o)))
      (replace-declared-and-probe! (mapv walk/keywordize-keys (:servers o)))
      (sequential? o)
      (replace-declared-and-probe! (mapv walk/keywordize-keys o))
      :else
      (throw (ex-info "expected {:servers [...]} or a vector of server maps" {:read o})))))

(defn reload-running-servers!
  "Re-probe all servers, refresh tools cache, stop subprocesses. Return summary (for tool + /mcp)."
  []
  (probe-all-and-persist-cache!))

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
            (let [norm (mapv walk/keywordize-keys sv)
                  sum (replace-declared-and-probe! norm)]
              (json/generate-string
               (merge sum
                      {:declared (count norm)}
                      (when (:no-edn-store sum)
                        {:hint "edn-store off — no tools cache persisted"}))))))
        "mcp_reload"
        (if-not (has-declared-servers?)
          (json/generate-string {:error true :message "No valid servers declared — mcp_servers_set or mcp_config_load first"})
          (if-not (store/configured?)
            (json/generate-string {:error true :message "edn-store not configured"})
            (json/generate-string (reload-running-servers!))))
        (json/generate-string {:error true :message (str "unknown mcp admin tool: " nm)})))
    (catch Exception e
      (json/generate-string {:error true :message (.getMessage e)}))))

(defn startup-status-line
  "Does not start subprocesses. See /mcp and tools mcp_config_* / mcp_reload."
  []
  (if-not (store/configured?)
    "mcp: needs :edn-store — then /mcp, tools mcp_config_load | mcp_config_save | mcp_servers_set | mcp_reload"
    (str "mcp: " (mcp-store/store-path-hint)
         " + " (mcp-store/tools-cache-path-hint)
         " — " (count (normalized-declared-cfgs)) " declared"
         ", " (count (tool-specs-from-cache)) " cached tool(s)"
         (when (mcp-servers-running?)
           (str ", " (count (:servers @!state)) " server process(es) up"))
         " — /mcp help")))
