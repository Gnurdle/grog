(ns grog-rss.main
  "grog-rss — a standalone MCP server (over stdio) exposing **RSS/Atom feed
  reading** so an ECA-driven agent loop can monitor feeds without fitting the
  whole web in its context.

  Tool: `fetch_feed(url, [limit])` — download a feed (RSS 2.0 / Atom), parse
  entries, and return the newest items (title/link/summary/published).

  Wire into ECA:
    :mcpServers
      {\"grog-rss\"
        {:command \"bash\" :args [\"-lc\" \"cd '<root>/grog-rss' && clojure -M:mcp -m grog-rss.main\"]}}
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
           [com.fasterxml.jackson.databind ObjectMapper]
           [java.io ByteArrayInputStream StringReader]
           [javax.xml.parsers DocumentBuilderFactory]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Config
;; ---------------------------------------------------------------------------

(defn- env [k default]
  (or (some-> (System/getenv k) str str/trim not-empty) default))
(defn- feed-timeout-ms [] (Long/parseLong (env "GROG_RSS_TIMEOUT_MS" "20000")))

;; ---------------------------------------------------------------------------
;; Feed fetch + parse
;; ---------------------------------------------------------------------------

(defn- parse-args [arguments]
  (let [m (cond
            (map? arguments) arguments
            (instance? java.util.Map arguments)
            (into {} (map (fn [[k v]] [(keyword (name (str k))) v])) arguments)
            (string? arguments) (try (json/read-str arguments) (catch Exception _ {}))
            :else {})]
    {:url (some-> (or (:url m) (get m "url")) str str/trim not-empty)
     :limit (when-let [l (or (:limit m) (get m "limit"))]
              (when (number? l) (long l)))}))

(defn- tag-text
  "Text of first child with `tag` in element `el`."
  [^org.w3c.dom.Element el ^String tag]
  (let [nodes (.getElementsByTagName el tag)
        node (when (pos? (.getLength nodes)) (.item nodes 0))]
    (when node
      (let [v (some-> (.getTextContent node) str str/trim not-empty)]
        (or v (some-> (.getAttribute node "href") str str/trim not-empty))))))

(defn- attr
  "Attribute `name` (or child element of `name` with an href attr) on `el`."
  [^org.w3c.dom.Element el ^String name]
  (or (some-> (.getAttribute el name) str str/trim not-empty)
      (tag-text el name)))

(defn- parse-feed
  "Parse RSS or Atom XML into a vector of item maps {:title :link :summary :published}."
  [^String xml]
  (let [factory (doto (DocumentBuilderFactory/newInstance)
                  (.setNamespaceAware false)
                  (.setFeature "http://apache.org/xml/features/nonvalidating/load-external-dtd" false)
                  (.setFeature "http://xml.org/sax/features/external-general-entities" false)
                  (.setFeature "http://xml.org/sax/features/external-parameter-entities" false)
                  (.setXIncludeAware false)
                  (.setExpandEntityReferences false))
        doc (-> factory (.newDocumentBuilder) (.parse (ByteArrayInputStream. (.getBytes xml "UTF-8"))))
        root (.getDocumentElement doc)
        rss? (= "rss" (str/lower-case (.getTagName root)))
        items (if rss?
                (let [items (.getElementsByTagName root "item")
                      n (.getLength items)]
                  (mapv (fn [i]
                          (let [it (.item items i)]
                            {:title (tag-text it "title")
                             :link (or (tag-text it "link") (attr it "guid"))
                             :summary (or (tag-text it "description") (tag-text it "summary"))
                             :published (or (tag-text it "pubDate") (tag-text it "dc:date"))}))
                        (range (int n))))
                (let [items (.getElementsByTagName root "entry")
                      n (.getLength items)]
                  (mapv (fn [i]
                          (let [it (.item items i)]
                            {:title (tag-text it "title")
                             :link (attr it "link")
                             :summary (tag-text it "summary")
                             :published (tag-text it "updated")}))
                        (range (int n)))))]
    (remove nil? items)))

(defn- run-fetch-feed! [arguments]
  (let [{:keys [url limit]} (parse-args arguments)]
    (if (str/blank? url)
      "fetch_feed error: missing or empty `url` parameter."
      (try
        (let [resp (http/get url
                             {:headers {"User-Agent" "Mozilla/5.0 (compatible; grog-rss/1.0)"}
                              :as :string
                              :throw-exceptions false
                              :socket-timeout (feed-timeout-ms)
                              :conn-timeout 10000})
              st (:status resp)
              ^String body (or (:body resp) "")]
          (cond
            (and (>= st 200) (< st 300) (seq (str/trim body)))
            (try
              (let [items (parse-feed body)
                    items (vec (take (or limit 20) items))]
                (if (empty? items)
                  (str "fetch_feed: " url " parsed but contained no entries.")
                  (json/write-str {:url url :count (count items) :items items} {:escape-unicode true})))
              (catch Exception e
                (str "fetch_feed: could not parse feed from " url " : " (.getMessage e))))

            :else
            (str "fetch_feed: HTTP " st " from " url)))
        (catch Exception e
          (str "fetch_feed failed: " (.getMessage e) " (URL " url ")"))))))

;; ---------------------------------------------------------------------------
;; MCP wiring (same as grog-search / grog-big / grog-fetch)
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
  {:name "fetch_feed"
   :description (str "Fetch an RSS/Atom feed and return its recent entries "
                     "(title, link, summary/description, published date). Use to monitor "
                     "news feeds, blogs, release notes, Outlook/Google calendar exports, etc. "
                     "Pass a feed URL and an optional limit (default 20). Returns JSON.")
   :schema (json/write-str {:type :object
                            :properties {:url {:type :string
                                               :description "RSS or Atom feed URL."}
                                         :limit {:type :integer
                                                 :description "Optional max entries to return (default 20)."}}
                            :required ["url"]})
   :fn run-fetch-feed!})

(defn mcp-server []
  (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "grog-rss" "0.1.0")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                      (.tools true)
                                      (.build)))
                   (.build))]
    (-> (.addTool server (tool (tool-spec))) (.subscribe))
    server))

(defn -main [& _args]
  (mcp-server)
  (loop [] (Thread/sleep 1000) (recur)))