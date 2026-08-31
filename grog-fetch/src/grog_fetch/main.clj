(ns grog-fetch.main
  "grog-fetch — a standalone MCP server (over stdio) exposing **real page
  fetching** so an ECA-driven agent loop can read live web pages — not just
  Brave search snippets.

  Tool: `fetch_url(url, [max_chars])` — GET the URL, strip HTML tags/scripts/style,
  and return the readable text (with title). A `jina`/`playwright` backend would
  give richer JS-driven extraction; this first version is fast, dependency-free,
  and works for the majority of static pages.

  Wire into ECA:
    :mcpServers
      {\"grog-fetch\"
        {:command \"bash\" :args [\"-lc\" \"cd '<root>/grog-fetch' && clojure -M:mcp -m grog-fetch.main\"]}}
  "
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-http.client :as http])
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

(defn- fetch-timeout-ms [] (Long/parseLong (env "GROG_FETCH_TIMEOUT_MS" "20000")))
(defn- default-max-chars [] (Long/parseLong (env "GROG_FETCH_MAX_CHARS" "12000")))

;; ---------------------------------------------------------------------------
;; Fetch
;; ---------------------------------------------------------------------------

(defn- parse-args [arguments]
  (let [m (cond
            (map? arguments) arguments
            (instance? java.util.Map arguments)
            (into {} (map (fn [[k v]] [(keyword (name (str k))) v])) arguments)
            (string? arguments) (try (json/read-str arguments) (catch Exception _ {}))
            :else {})]
    {:url (some-> (or (:url m) (get m "url")) str str/trim not-empty)
     :max_chars (when-let [mc (or (:max_chars m) (get m "max_chars"))]
                  (when (number? mc) (long mc)))}))

(defn- extract-title [^String html]
  (let [m (re-find #"(?is)<title[^>]*>(.*?)</title>" html)]
    (some-> m second str/trim (subs 0 (min 200 (count (some-> m second str/trim)))))))

(defn- strip-html
  "Best-effort HTML → readable text: drop scripts/styles/comments, then strip tags
  and collapse whitespace while preserving paragraph/newline boundaries."
  ^String [^String html]
  (let [without-scripts (str/replace html #"(?is)<(script|style|noscript|svg)[^>]*>.*?</\1>" " ")
        no-comments (str/replace without-scripts #"(?s)<!--.*?-->" " ")
        with-lines (-> no-comments
                       (str/replace #"(?i)</(p|div|li|h[1-6]|tr|br|table|blockquote)>" "\n")
                       (str/replace #"<(br|/p|/li|/h[1-6])[^>]*>" "\n"))
        stripped (str/replace with-lines #"(?s)<[^>]+>" " ")
        decoded (-> stripped
                    (str/replace "&nbsp;" " ")
                    (str/replace "&amp;" "&")
                    (str/replace "&lt;" "<")
                    (str/replace "&gt;" ">")
                    (str/replace "&quot;" "\"")
                    (str/replace "&#39;" "'")
                    (str/replace "&#x27;" "'")
                    (str/replace "&apos;" "'"))
        collapsed (-> decoded
                      (str/replace #"[ \t]+" " ")
                      (str/replace #"\n[ \t]+" "\n")
                      (str/replace #"\n{3,}" "\n\n"))]
    (str/trim collapsed)))

(defn- run-fetch! [arguments]
  (let [{:keys [url max_chars]} (parse-args arguments)
        cap (or max_chars (default-max-chars))]
    (if (str/blank? url)
      "fetch_url error: missing or empty `url` parameter."
      (try
        (let [resp (http/get url
                             {:headers {"User-Agent" "Mozilla/5.0 (compatible; grog-fetch/1.0)"
                                        "Accept" "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"}
                              :as :bytes
                              :throw-exceptions false
                              :socket-timeout (fetch-timeout-ms)
                              :conn-timeout 10000})
              st (:status resp)
              raw (:body resp)
              ^bytes raw-bytes (cond
                                 (bytes? raw) raw
                                 (string? raw) (.getBytes ^String raw "UTF-8")
                                 :else (byte-array 0))
              ct (or (get-in resp [:headers "content-type"]) (get-in resp [:headers "Content-Type"]) "")
              txt (if (and (seq raw-bytes) (> (count raw-bytes) 0))
                    (try (String. raw-bytes "UTF-8")
                         (catch Exception _ (String. raw-bytes)))
                    "")]
          (cond
            (and (>= st 200) (< st 300) (seq (str/trim txt)))
            (let [title (extract-title txt)
                  body (strip-html txt)
                  body (subs body 0 (min (count body) (int cap)))]
              (str (when (seq title) (str "# " title "\n"))
                   "URL: " url "\n"
                   (when (seq ct) (str "Content-Type: " (str/trim ct) "\n"))
                   "\n" body))

            (>= st 300)
            (str "fetch_url: HTTP redirect/status " st " for " url)

            (= st 200)
            (str "fetch_url: page returned empty body or non-HTML (Content-Type: " (str/trim ct) ")")

            :else
            (str "fetch_url: HTTP " st " from " url)))
        (catch Exception e
          (str "fetch_url failed: " (.getMessage e) " (URL " url ")"))))))

;; ---------------------------------------------------------------------------
;; MCP wiring (same as grog-search / grog-big)
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
  {:name "fetch_url"
   :description (str "Fetch a URL and return its readable text content. Use when you need the full "
                     "content of a page (not just a search snippet): read a headline, an article body, "
                     "a changelog, docs, etc. Pass a full http(s) URL. Optional max_chars caps the body "
                     "length (default 12000).")
   :schema (json/write-str {:type :object
                            :properties {:url {:type :string
                                               :description "Full http(s) URL to fetch."}
                                         :max_chars {:type :integer
                                                     :description "Optional max characters of readable text to return."}}
                            :required ["url"]})
   :fn run-fetch!})

(defn mcp-server []
  (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "grog-fetch" "0.1.0")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                      (.tools true)
                                      (.build)))
                   (.build))]
    (-> (.addTool server (tool (tool-spec))) (.subscribe))
    server))

(defn -main [& _args]
  (mcp-server)
  (loop [] (Thread/sleep 1000) (recur)))