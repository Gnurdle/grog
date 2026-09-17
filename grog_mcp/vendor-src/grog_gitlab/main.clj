(ns grog-gitlab.main
  "grog-gitlab — an MCP server (stdio) exposing the GitLab REST API.

  Co-opted from the (babashka) utility `/d/cms-aero/cms-devops/bbutils/gitlab.clj`
  that wraps a self-hosted GitLab instance. The source carries NO instance URL and
  NO token — both are personal config under ~/.config/grog.

  Multiple GitLab *instances* can be configured. The model can only ever select
  one of the pre-configured instance *names* (never a URL / endpoint). Tokens are
  per-instance, read from token files referenced by path; they NEVER appear in
  tool output or in source.

  Config is file-based: ~/.config/grog/gitlab.edn
    {:config \"~/.config/grog/gitlab-instances.edn\"}  → load an instances file
    {:url ... :token-file ...}                          → single \"default\" instance

  where the instances file is EDN:

    {:instances [
        {:name \"prod\", :url \"https://gitlab.example.com/api/v4\",
         :token-file \"~/.config/grog/keys/gitlab-prod.token\"},
        {:name \"stage\", :url \"https://stage.example.com/api/v4\",
         :token-file \"~/.config/grog/keys/gitlab-stage.token\"}]}

  Single-instance fallback (when gitlab.edn has no :config and no :url), or the
  default instances file ~/.config/grog/gitlab-instances.edn is used.
  ${ENV} / ${ENV:-default} interpolation inside the instances file is honored.

  Auth is PRIVATE-TOKEN (read from the active instance's :token-file). Every tool
  is read-only. A model sees the tools as `grog-gitlab__<tool>`."

  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clj-http.client :as http])
  (:import [java.io File]
           [java.net URLEncoder]
           [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
           [io.modelcontextprotocol.server McpServer]
           [io.modelcontextprotocol.server McpServerFeatures$AsyncToolSpecification]
           [io.modelcontextprotocol.spec
            McpSchema$ServerCapabilities McpSchema$Tool McpSchema$CallToolResult
            McpSchema$TextContent]
           [reactor.core.publisher Mono]
           [com.fasterxml.jackson.databind ObjectMapper]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Config (file-based, multi-instance, mirrors grog-odoo)
;; ---------------------------------------------------------------------------

(def ^{:private true} config*
  "Atom holding {:instances [..] :by-name {name inst}}; populated at startup."
  (atom nil))

(def ^{:private true} current*
  "Atom holding the name of the currently selected instance (nil = first)."
  (atom nil))

(def ^{:private true} auth*
  "Atom map instance-name -> {:url :token} (token cache). Tokens are held here
  only internally; they never appear in the maps returned to the model."
  (atom {}))

(defn- interp
  "Interpolate `${ENV}` / `${ENV:-default}` references in string fields from the
  process environment (so credentials can be injected per-process)."
  [v]
  (if (string? v)
    (str/replace v #"\$\{([^}]+)\}"
                 (fn [[_ spec]]
                   (let [[var-name default-val] (str/split spec #":-" 2)]
                     (or (System/getenv var-name) default-val ""))))
    v))

(defn- normalize-url [url]
  (str/replace (str url) #"/+$" ""))

(defn- expand-home ^String [^String p]
  (str/replace p "~" (or (System/getenv "HOME") "")))

(defn- gitlab-config-file ^File []
  (io/file (or (some-> (System/getenv "HOME") str not-empty) "~")
           ".config/grog/gitlab.edn"))

(defn- load-config-file!
  "Load ~/.config/grog/gitlab.edn. Returns {} when missing (normal — we fall back
  to the default instances file) or when it fails to parse."
  []
  (let [f (gitlab-config-file)]
    (if (.exists ^File f)
      (try (edn/read-string (slurp f))
           (catch Exception e (binding [*out* *err*] (println "gitlab config load error:" (.getMessage e))) {}))
      {})))

(defn- read-instances-file!
  "Load instances from the config file at `path`. Accepts EDN (the current grog
  writer format) or legacy JSON. Returns a vector of instance maps with
  :name / :url / :token-file. `${ENV}` / `${ENV:-default}` references in string
  fields are interpolated from the process environment."
  [path]
  (let [raw (slurp (java.io.File. path))
        data (try
               (edn/read-string {:eof nil} raw)
               (catch Exception _
                 (json/read-str raw :key-fn keyword)))
        raw-insts (get-in data [:instances])
        insts (if (sequential? raw-insts) (vec raw-insts) [])]
    (when-not (seq insts)
      (throw (ex-info (str "GitLab instances file contains no instances: " path) {:path path})))
    (mapv (fn [i]
            (let [name (str (or (:name i) "default"))
                  url  (normalize-url (interp (or (:url i) (throw (ex-info (str "instance '" name "' missing :url") {})))))]
              {:name name
               :url url
               :token-file (str (expand-home (str (interp (or (:token-file i) "")))))}))
          insts)))

(defn- load-config!
  "Populate `config*` from ~/.config/grog/gitlab.edn:
      {:config \"path\"}                        → load that instances file
      {:url ... :token-file ...}                → single \"default\" instance
    If gitlab.edn is missing or carries no :config/:url, the default instances
    file ~/.config/grog/gitlab-instances.edn is used. ${ENV} interpolation inside
    the instances file is still honored."
  []
  (let [cfg (load-config-file!)
        home (or (System/getenv "HOME") (System/getProperty "user.home"))
        instances
        (cond
          (and (not (:config cfg))
               (or (:url cfg) (:token-file cfg)))
          (let [missing (remove (fn [k] (not (str/blank? (str (get cfg k))))) [:url])]
            (when (seq missing)
              (throw (ex-info (str "gitlab.edn single-instance config is missing: "
                                   (str/join ", " (map name missing))) {})))
            [{:name "default"
              :url (normalize-url (interp (:url cfg)))
              :token-file (str (expand-home (str (interp (or (:token-file cfg) "")))))}])

          :else
          (let [cfg-file (not-empty (str/trim (str (or (:config cfg) "~/.config/grog/gitlab-instances.edn"))))
                cfg-file (str/replace-first cfg-file #"^~(?=/|$)" home)]
            (read-instances-file! cfg-file)))
        by-name (into {} (map (fn [i] [(:name i) i])) instances)]
    (when-not (seq instances)
      (throw (ex-info "No GitLab instances configured. Add :config (or :url/:token-file) to ~/.config/grog/gitlab.edn." {})))
    (reset! config* {:instances instances :by-name by-name})
    (reset! current* nil)
    @config*))

(defn- active-instance
  "Return the currently selected instance map. Only names present in the
  pre-configured allowlist can ever become current; anything else throws — the
  model cannot specify an endpoint directly."
  []
  (let [{:keys [instances by-name]} @config*
        name (or @current* (:name (first instances)))]
    (or (get by-name name)
        (throw (ex-info "No GitLab instance selected — call gitlab_use_instance first" {})))))

(defn- instance-auth!
  "Resolve the auth for `inst` lazily (cached) and return {:url :token}. The raw
  token is read from the instance's :token-file and kept only in the internal
  `auth*` cache — never returned to the model."
  [inst]
  (let [name (:name inst)]
    (if-let [c (get @auth* name)]
      c
      (let [token (if-let [tf (:token-file inst)]
                    (try (str/trim (slurp (expand-home (str tf))))
                         (catch Exception _ ""))
                    "")]
        (let [c {:url (:url inst) :token token :name name}]
          (swap! auth* assoc name c)
          c)))))

(defn- conn-timeout-ms []
  (long (or (:connect-timeout-ms (load-config-file!)) 15000)))

(defn- base-url []
  (or (:url (active-instance))
      (str "https://" (or (:host (load-config-file!)) "gitlab.example.com") "/api/v4")))

(defn- token []
  (:token (instance-auth! (active-instance))))

;; ---------------------------------------------------------------------------
;; Low-level HTTP (mirrors the babashka helpers; read-only)
;; ---------------------------------------------------------------------------

(defn- auth-map []
  {"PRIVATE-TOKEN" (token)})

(defn- url-encode ^String [s]
  (-> (URLEncoder/encode (str s) "UTF-8")
      (str/replace "+" "%20")))

(defn- api-url ^String [tail]
  (str (base-url) "/" tail))

(defn- body-preview [resp limit]
  (let [b (:body resp)]
    (when (string? b) (if (<= (count b) limit) b (subs b 0 limit)))))

(defn- assert-2xx! [method tail resp]
  (when-not (<= 200 (:status resp) 299)
    (throw (ex-info (str "GitLab " (name method) " " (api-url tail) " — HTTP " (:status resp))
                    {:http-status (:status resp) :url (api-url tail) :body-preview (body-preview resp 500)}))))

(defn- api-get
  "GET a GitLab API tail, asserting 2xx, returning the response."
  [tail]
  (let [resp (http/get (api-url tail) {:headers (auth-map)
                                       :throw-exceptions false
                                       :conn-timeout-ms (conn-timeout-ms)})]
    (assert-2xx! :GET tail resp)
    resp))

(defn- parse-body
  "json-parse the :body (edn-style keyword keys)."
  [resp]
  (-> resp :body (json/read-str)))

(defn- api-json
  "GET tail and return parsed JSON."
  [tail]
  (parse-body (api-get tail)))

(defn- add-arg
  "append ?k=v or &k=v to a url."
  [url arg]
  (str url (if (str/includes? url "?") \& \?) arg))

(defn- fetch-all
  "Eagerly paginate all pages of an endpoint; tail-fn is (fn [page] -> tail)."
  [tail-fn]
  (loop [page 1 acc []]
    (let [body (api-json (tail-fn page))]
      (if (seq body)
        (recur (inc page) (into acc body))
        acc))))

;; ---------------------------------------------------------------------------
;; Tools
;; ---------------------------------------------------------------------------

(defn ts-resolve
  "Resolve a project name -> id, or pass through a numeric id. param is the raw arg."
  [raw]
  (let [s (str/trim (str raw))]
    (if (re-matches #"^\d+$" s)
      (Long/parseLong s)
      (:id (api-json (str "projects/" (url-encode s)))))))

;; --- projects & groups -----------------------------------------------------

(defn t-get-project [args]
  (let [p (str (:project args))]
    (api-json (str "projects/" (url-encode p)))))

(defn t-project-id
  "Best-effort: return the project id for name-or-id; used internally."
  [raw]
  (try (ts-resolve raw) (catch Exception _ (str raw))))

(defn t-list-group-projects [args]
  (let [g (str (:group args))]
    (fetch-all #(str "groups/" (url-encode g) "/projects?per_page=100&page=" %))))

(defn t-list-group
  [args]
  (let [g (str (:group args))]
    (api-json (str "groups/" (url-encode g)))))

(defn t-list-subgroups [args]
  (let [g (str (:group args))]
    (fetch-all #(str "groups/" (url-encode g) "/subgroups?per_page=100&page=" %))))

;; --- repository ------------------------------------------------------------

(defn t-file-exists [args]
  (let [pid (t-project-id (:project args))
        path (str (:path args))
        ref (or (:ref args) "master")
        resp (http/head (api-url (str "projects/" pid "/repository/files/" (url-encode path)))
                        {:headers (auth-map) :throw-exceptions false
                         :conn-timeout-ms (conn-timeout-ms)})
        st (:status resp)]
    (<= 200 st 299)))

(defn t-get-file [args]
  (let [pid (t-project-id (:project args))
        path (str (:path args))
        ref (or (:ref args) "master")]
    (:body (api-get (str "projects/" pid "/repository/files/" (url-encode path)
                         "?ref=" ref)))))

(defn t-list-branches [args]
  (let [pid (t-project-id (:project args))]
    (fetch-all #(str "projects/" pid "/repository/branches?per_page=100&page=" %))))

(defn t-get-branch [args]
  (let [pid (t-project-id (:project args))
        b (str (:branch args))]
    (api-json (str "projects/" pid "/repository/branches/" (url-encode b)))))

(defn t-list-tags [args]
  (let [pid (t-project-id (:project args))]
    (fetch-all #(str "projects/" pid "/repository/tags?per_page=100&page=" %))))

(defn t-list-commits [args]
  (let [pid (t-project-id (:project args))
        ref (or (:ref args) "master")]
    (api-json (str "projects/" pid "/repository/commits?ref_name=" ref))))

(defn t-compare-refs [args]
  (let [pid (t-project-id (:project args))
        from (str (:from args)) to (str (:to args))]
    (api-json (str "projects/" pid "/repository/compare?from=" from "&to=" to))))

;; --- CI / pipelines / jobs -------------------------------------------------

(defn t-list-pipelines [args]
  (let [pid (t-project-id (:project args))
        ref (or (:ref args) "")]
    (api-json (str "projects/" pid "/pipelines"
                   (when-not (str/blank? ref) (str "?ref=" ref))))))

(defn t-last-successful-pipeline [args]
  (let [pid (t-project-id (:project args))
        ref (str (:ref args))]
    (first (api-json (-> (str "projects/" pid "/pipelines")
                         (add-arg (str "ref=" ref))
                         (add-arg "order_by=id")
                         (add-arg "sort=desc")
                         (add-arg "status=success"))))))

(defn t-list-jobs [args]
  (let [pid (t-project-id (:project args))
        pipe (str (:pipeline args))]
    (api-json (str "projects/" pid "/pipelines/" pipe "/jobs"))))

(defn t-last-successful-job [args]
  (let [pid (t-project-id (:project args))
        pipe (str (:pipeline args))]
    (first (api-json (-> (str "projects/" pid "/pipelines/" pipe "/jobs")
                         (add-arg "scope[]=success")
                         (add-arg "order_by=id")
                         (add-arg "sort=desc"))))))

;; --- merge requests / issues (read-only) -----------------------------------

(defn t-list-merge-requests [args]
  (let [pid (t-project-id (:project args))
        state (or (:state args) "opened")]
    (fetch-all #(str "projects/" pid "/merge_requests?state=" state "&per_page=100&page=" %))))

(defn t-list-project-issues [args]
  (let [pid (t-project-id (:project args))
        state (or (:state args) "opened")]
    (fetch-all #(str "projects/" pid "/issues?state=" state "&per_page=100&page=" %))))

;; ---------------------------------------------------------------------------
;; Tool specs
;; ---------------------------------------------------------------------------

(def ps
  {:project {:type "string" :description "Project id (numeric) or URL-encoded name."}})

(def resource-tools
  [{:name "gitlab_get_project"
    :description "Get a GitLab project by id or name."
    :schema (json/write-str {:type :object :properties {:project (:project ps)} :required ["project"]})
    :fn (fn [a] (t-get-project a))}

   {:name "gitlab_list_group_projects"
    :description "List all projects in a group (paginated). Returns :id/:name/:path_with_namespace."
    :schema (json/write-str {:type :object :properties {:group {:type "string"}} :required ["group"]})
    :fn (fn [a] (t-list-group-projects a))}

   {:name "gitlab_list_subgroups"
    :description "List direct subgroups of a group."
    :schema (json/write-str {:type :object :properties {:group {:type "string"}} :required ["group"]})
    :fn (fn [a] (t-list-subgroups a))}

   {:name "gitlab_list_branches"
    :description "List all branches of a project."
    :schema (json/write-str {:type :object :properties {:project (:project ps)} :required ["project"]})
    :fn (fn [a] (t-list-branches a))}

   {:name "gitlab_list_tags"
    :description "List all tags of a project."
    :schema (json/write-str {:type :object :properties {:project (:project ps)} :required ["project"]})
    :fn (fn [a] (t-list-tags a))}

   {:name "gitlab_list_commits"
    :description "List commits for a project at a ref."
    :schema (json/write-str {:type :object :properties {:project (:project ps) :ref {:type "string"}} :required ["project"]})
    :fn (fn [a] (t-list-commits a))}

   {:name "gitlab_get_file"
    :description "Get the raw contents of a file from a project at a ref."
    :schema (json/write-str {:type :object :properties {:project (:project ps) :path {:type "string"} :ref {:type "string"}} :required ["project" "path"]})
    :fn (fn [a] (t-get-file a))}

   {:name "gitlab_file_exists"
    :description "Check whether a file exists in a project at a ref (lightweight HEAD)."
    :schema (json/write-str {:type :object :properties {:project (:project ps) :path {:type "string"} :ref {:type "string"}} :required ["project" "path"]})
    :fn (fn [a] (t-file-exists a))}

   {:name "gitlab_list_pipelines"
    :description "List pipelines for a project (optionally by ref)."
    :schema (json/write-str {:type :object :properties {:project (:project ps) :ref {:type "string"}} :required ["project"]})
    :fn (fn [a] (t-list-pipelines a))}

   {:name "gitlab_last_successful_pipeline"
    :description "Get the last pipeline with status=success for a project/ref."
    :schema (json/write-str {:type :object :properties {:project (:project ps) :ref {:type "string"}} :required ["project" "ref"]})
    :fn (fn [a] (t-last-successful-pipeline a))}

   {:name "gitlab_list_jobs"
    :description "List jobs for a project pipeline."
    :schema (json/write-str {:type :object :properties {:project (:project ps) :pipeline {:type "string"}} :required ["project" "pipeline"]})
    :fn (fn [a] (t-list-jobs a))}

   {:name "gitlab_last_successful_job"
    :description "Get the last successful job for a project pipeline."
    :schema (json/write-str {:type :object :properties {:project (:project ps) :pipeline {:type "string"}} :required ["project" "pipeline"]})
    :fn (fn [a] (t-last-successful-job a))}

   {:name "gitlab_compare_refs"
    :description "Compare two refs: commits in to but not from."
    :schema (json/write-str {:type :object :properties {:project (:project ps) :from {:type "string"} :to {:type "string"}} :required ["project" "from" "to"]})
    :fn (fn [a] (t-compare-refs a))}

   {:name "gitlab_list_merge_requests"
    :description "List merge requests for a project (state default opened)."
    :schema (json/write-str {:type :object :properties {:project (:project ps) :state {:type "string"}} :required ["project"]})
    :fn (fn [a] (t-list-merge-requests a))}

   {:name "gitlab_list_issues"
    :description "List issues for a project (state default opened)."
    :schema (json/write-str {:type :object :properties {:project (:project ps) :state {:type "string"}} :required ["project"]})
    :fn (fn [a] (t-list-project-issues a))}])

(defn build-tools
  "Build the full tool list. Reads the current config once so the instance enum
  reflects exactly the pre-configured instances. Prepends the two selection tools
  (list/use) then the 15 per-instance resource tools, all of which route through
  the active instance."
  []
  (let [{:keys [instances by-name]} @config*
        instance-names (mapv :name instances)
        instance-summaries (mapv (fn [i] {:name (:name i) :url (:url i)}) instances)]
    (into
     [{:name "gitlab_list_instances"
       :description "List the pre-configured GitLab instances the model may use. Returns name/url only (never tokens)."
       :schema (json/write-str {:type :object :properties {} :required []})
       :fn (fn [_] {:instances instance-summaries})}

      {:name "gitlab_use_instance"
       :description (str "Select which pre-configured GitLab instance to use for all subsequent calls. "
                         "You can ONLY pick one of these names; arbitrary endpoints are not allowed. "
                         "Available: " (str/join ", " instance-names))
       :schema (json/write-str {:type :object
                                :properties {:instance {:type :string
                                                        :enum instance-names}}
                                :required [:instance]})
       :fn (fn [a]
             (let [name (str (or (:instance a) ""))]
               (if-let [inst (get by-name name)]
                 (do (reset! current* name)
                     (instance-auth! inst)  ; warm the token cache
                     {:instance name :url (:url inst) :authenticated true})
                 (throw (ex-info (str "Unknown GitLab instance '" name "'. Available: "
                                      (str/join ", " instance-names)) {})))))}
      ]
     resource-tools)))

;; ---------------------------------------------------------------------------
;; MCP server wiring (same pattern as the other grog-* servers)
;; ---------------------------------------------------------------------------

(defn- text-content ^McpSchema$TextContent [^String s] (McpSchema$TextContent. s))
(defn- text-result ^McpSchema$CallToolResult [^String s] (McpSchema$CallToolResult. [(text-content s)] false))
(defn- error-result ^McpSchema$CallToolResult [^String s] (McpSchema$CallToolResult. [(text-content s)] true))

(defn- parse-args [arguments]
  (cond (map? arguments) (into {} (map (fn [[k v]] [(keyword (name (str k))) v])) arguments)
        (instance? java.util.Map arguments) (into {} (map (fn [[k v]] [(keyword (name (str k))) v])) arguments)
        (string? arguments) (try (json/read-str arguments) (catch Exception _ {}))
        :else {}))

(defn- tool
  [{:keys [name description schema fn]}]
  (McpServerFeatures$AsyncToolSpecification.
    (McpSchema$Tool. name description schema)
    (reify java.util.function.BiFunction
      (apply [_ _exchange arguments]
        (Mono/create
          (reify java.util.function.Consumer
            (accept [_ sink]
              (try
                (.success sink (text-result (pr-str (fn (parse-args arguments)))))
                (catch Throwable t
                  (.success sink (error-result (str "Error executing tool " name ": " (.getMessage t)))))))))))))

(defn mcp-server []
  (load-config!)
  (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "grog-gitlab" "0.2.0")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder) (.tools true) (.build)))
                   (.build))]
    (doseq [t (build-tools)]
      (-> (.addTool server (tool t)) (.subscribe)))
    server))

(defn -main [& _args]
  (mcp-server)
  (loop [] (Thread/sleep 1000) (recur)))
