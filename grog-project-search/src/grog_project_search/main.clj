(ns grog-project-search.main
  "grog-project-search — a standalone MCP server (over stdio) exposing **local
  search over grog project notes + chat history** so the model can answer from
  its own stored history instead of only live context.

  Pattern: this is a lightweight, zero-embedding RAG. It scans the active
  project's `notes/` text files and `dialog/thread.edn` (if the project has one),
  tokenizes on the query, and returns the best-scoring snippets with file/line
  context. No external vector DB; you can swap in embeddings later.

  Env:
    GROG_PROJECTS_DIR   projects home (default ~/grog-projects)
    GROG_PROJECT        the project dir to search under GROG_PROJECTS_DIR
                        (defaults to the first subdir, or 'default')

  Wire into ECA:
    :mcpServers
      {\"grog-project-search\"
        {:command \"bash\"
         :args [\"-lc\" \"cd '<root>/grog-project-search' && clojure -M:mcp -m grog-project-search.main\"]
         :env {\"GROG_PROJECTS_DIR\" \"<projects-home>\"}}}
  "
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
           [io.modelcontextprotocol.server McpServer]
           [io.modelcontextprotocol.server McpServerFeatures$AsyncToolSpecification]
           [io.modelcontextprotocol.spec
            McpSchema$ServerCapabilities McpSchema$Tool McpSchema$CallToolResult
            McpSchema$TextContent]
           [reactor.core.publisher Mono]
           [com.fasterxml.jackson.databind ObjectMapper]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Config
;; ---------------------------------------------------------------------------

(defn- env [k default]
  (or (some-> (System/getenv k) str str/trim not-empty) default))

(defn- projects-dir ^java.io.File []
  (let [raw (env "GROG_PROJECTS_DIR" "~/grog-projects")
        home (System/getProperty "user.home")]
    (io/file (str/replace-first raw #"^~(?=/|$)" home))))

(defn- project-root ^java.io.File []
  (let [root (projects-dir)
        explicit (env "GROG_PROJECT" nil)
        root (if (and root (.isDirectory root))
               (if explicit
                 (io/file root explicit)
                 (or (first (filter #(.isDirectory ^java.io.File %) (or (seq (.listFiles root)) [])))
                     (io/file root "default")))
               (io/file root))]
    root))

;; ---------------------------------------------------------------------------
;; Text extraction
;; ---------------------------------------------------------------------------

(def ^:private text-extensions #{"md" "txt" "markdown" "edn" "clj" "cljs" "cljc"})

(defn- walk-files
  "All text files under `dir` (recursive), path strings."
  [^java.io.File dir]
  (let [result (atom [])]
    (letfn [(walk [^java.io.File d]
              (when (.isDirectory d)
                (doseq [^java.io.File f (or (seq (.listFiles d)) [])]
                  (if (.isDirectory f)
                    (walk f)
                    (when (some #(str/ends-with? (str/lower-case (.getName f)) %) text-extensions)
                      (swap! result conj (.getAbsolutePath f)))))))]
      (walk dir))
    @result))

(defn- read-value
  "Read a File as a string (best effort, UTF-8)."
  ^String [^java.io.File f]
  (try (slurp f :encoding "UTF-8") (catch Exception _ "")))

(defn- edn-strings
  "Flatten a parsed EDN value into a concatenated string of all its string values."
  ^String [v]
  (cond
    (string? v) v
    (map? v) (str/join " " (map (fn [[k x]] (str k " " (edn-strings x))) v))
    (sequential? v) (str/join " " (map edn-strings v))
    (keyword? v) (name v)
    (number? v) (str v)
    :else ""))

(defn- project-texts
  "Map of file-path -> text for the project's notes/ + dialog/ files."
  []
  (let [root (project-root)
        nroot (io/file root "notes")
        diag (io/file root "dialog")
        files (->> (concat (walk-files nroot) (walk-files diag))
                   distinct)]
    (into {}
          (keep (fn [p]
                  (let [f (io/file p)
                        lower (str/lower-case (.getName f))
                        txt (cond
                              (str/ends-with? lower ".edn")
                              (edn-strings (try (read-string (read-value f)) (catch Exception _ "")))
                              :else (read-value f))]
                    (when (seq (str/trim txt))
                      [p txt]))))
          files)))

;; ---------------------------------------------------------------------------
;; Search
;; ---------------------------------------------------------------------------

(defn- tokenize [^String s]
  (->> (str/lower-case s)
       (re-seq #"[a-z0-9_]+")
       (remove #(< (count %) 2))
       vec))

(defn- score-text [^String text ^java.util.Set terms]
  (let [words (tokenize text)
        matches (filter #(contains? terms %) words)]
    (reduce + 0 (map (fn [w] (if (> (count w) 5) 3 1)) matches))))

(defn- snippet
  "A line-window around the first line containing any term."
  ^String [^String text ^java.util.Set terms]
  (let [lines (str/split text #"\n")
        idx (first (keep-indexed (fn [i l] (when (some #(str/includes? (str/lower-case l) %) terms) i)) lines))]
    (if idx
      (let [lo (max 0 (- idx 2)) hi (min (count lines) (+ idx 5))]
        (str/join "\n" (subvec lines lo hi)))
      (str/join "\n" (take 8 lines)))))

(defn- parse-args [arguments]
  (let [m (cond
            (map? arguments) arguments
            (instance? java.util.Map arguments)
            (into {} (map (fn [[k v]] [(keyword (name (str k))) v])) arguments)
            (string? arguments) (try (json/read-str arguments) (catch Exception _ {}))
            :else {})]
    {:query (some-> (or (:query m) (get m "query")) str str/trim not-empty)
     :limit (when-let [l (or (:limit m) (get m "limit"))]
              (when (number? l) (long l)))}))

(defn- run-search! [arguments]
  (let [{:keys [query limit]} (parse-args arguments)
        cap (or limit 10)]
    (cond
      (str/blank? query)
      "project_search error: missing or empty `query`."

      :else
      (let [root (project-root)
            texts (project-texts)]
        (cond
          (not (.isDirectory root))
          (str "project_search: project dir does not exist: " (.getPath root))

          (empty? texts)
          (json/write-str {:query query :root (.getPath root) :count 0
                           :message "No notes/ or dialog/ files found in this project."})

          :else
          (let [terms (set (tokenize query))
                scored (->> texts
                            (map (fn [[p txt]] {:path p :text txt :score (score-text txt terms)}))
                            (filter #(pos? (:score %)))
                            (sort-by :score >)
                            vec)
                out (take cap scored)
                results (mapv (fn [{:keys [path text score]}]
                                {:path path :score score :snippet (snippet text terms)})
                              out)]
            (json/write-str
             {:query query
              :root (.getPath root)
              :count (min cap (count scored))
              :results results})))))))

;; ---------------------------------------------------------------------------
;; MCP wiring (same as grog-search / grog-fetch / grog-rss)
;; ---------------------------------------------------------------------------

(defn- text-content [^String s] (McpSchema$TextContent. s))
(defn- text-result [^String s] (McpSchema$CallToolResult. [(text-content s)] false))
(defn- error-result [^String s] (McpSchema$CallToolResult. [(text-content s)] true))

(defn- tool
  [{:keys [name description schema fn]}]
  (McpServerFeatures$AsyncToolSpecification.
    (McpSchema$Tool. name description schema)
    (reify java.util.function.BiFunction
      (apply [_ _exchange arguments]
        (Mono/create
          (reify java.util.function.Consumer
            (accept [_ sink]
              (try (.success sink (text-result (fn arguments)))
                   (catch Throwable t
                     (.success sink (error-result
                                     (str "Error executing tool " name ": "
                                          (or (:message (ex-data t)) (.getMessage t))))))))))))))

(defn- tool-spec []
  {:name "project_search"
   :description (str "Search the current grog project's stored context: notes/ text files and "
                     "dialog/thread.edn chat history. Returns ranked snippets (keyword matching with "
                     "simple scoring, no embeddings). Use when you need to recall an earlier decision, "
                     "note, or conversation from this project instead of asking the user to repeat it. "
                     "Pass a `query` and optional `limit` (default 10).")
   :schema (json/write-str {:type :object
                            :properties {:query {:type :string
                                                 :description "Search query (keywords/terms)."}
                                         :limit {:type :integer
                                                 :description "Optional max snippets to return (default 10)."}}
                            :required ["query"]})
   :fn run-search!})

(defn mcp-server []
  (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "grog-project-search" "0.1.0")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                      (.tools true)
                                      (.build)))
                   (.build))]
    (-> (.addTool server (tool (tool-spec))) (.subscribe))
    server))

(defn -main [& _args]
  (mcp-server)
  (loop [] (Thread/sleep 1000) (recur)))