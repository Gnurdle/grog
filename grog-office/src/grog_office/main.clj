(ns grog-office.main
  "grog-office — an MCP server (over stdio) exposing Apache-POI-powered .docx
  manipulation tools: import/list/get_text/find_text/replace_text/
  delete_table_row/render/save, keyed by a server-side document `handle` and a
  stable `block_id` block model (para.N / table.M) that maps 1:1 to the
  `.map.edn` calibration rows.

  ECA discovers this over stdio, e.g.:
    :mcpServers {\"grog-office\"
      {:command \"clojure\" :args [\"-M:mcp\" \"-m\" \"grog-office.main\"]}

  Rendering (`render`) shells out to headless LibreOffice/soffice (set
  GROG_OFFICE_BIN; PNG pages via pdftoppm, set GROG_PDFTOPM)."
  (:require [clojure.data.json :as json]
            [grog-office.core :as core])
  (:import [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
           [io.modelcontextprotocol.server McpServer]
           [io.modelcontextprotocol.server McpServerFeatures$AsyncToolSpecification]
           [io.modelcontextprotocol.spec
            McpSchema$ServerCapabilities McpSchema$Tool McpSchema$CallToolResult
            McpSchema$TextContent]
           [reactor.core.publisher Mono]
           [com.fasterxml.jackson.databind ObjectMapper]))

(set! *warn-on-reflection* true)

;; --- helpers ---------------------------------------------------------------

(defn- text-content [^String s] (McpSchema$TextContent. s))
(defn- text-result [^String s] (McpSchema$CallToolResult. [(text-content s)] false))
(defn- error-result [^String s] (McpSchema$CallToolResult. [(text-content s)] true))
(defn- ok [data] (json/write-str data))

(defn- kargs
  "Normalize MCP argument keys to keywords (the SDK delivers string-keyed maps)."
  [m]
  (into {} (map (fn [[k v]] [(keyword (str k)) v])) m))

(defn- tool
  "Build an async MCP tool spec (same helper as grog-odoo / grog-imaging)."
  [{:keys [name description schema fn]}]
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. name description schema)
   (reify java.util.function.BiFunction
     (apply [_ _exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [_ sink]
            (try (->> (fn arguments)
                      (json/write-str)
                      (text-result)
                      (.success sink))
                 (catch Throwable t
                   (.success sink
                             (error-result
                              (str "Error executing tool " name ": "
                                   (or (:message (ex-data t)) (.getMessage t))))))))))))))

;; --- tool definitions ------------------------------------------------------

(def tools
  [{:name "import_document"
    :description "Open a .docx for structured manipulation. Returns a server-side document handle used by all other tools."
    :schema (json/write-str {:type :object
                             :properties {:path {:type :string
                                                 :description "Absolute path to the source document"}}
                             :required ["path"]})
    :fn (fn [a]
          (let [a (kargs a)
                h (core/import-document! (:path a))]
            (ok {:handle h :path (str (:path a))})))}

   {:name "list_handles"
    :description "List currently imported document handles and their source paths."
    :schema (json/write-str {:type :object :properties {} :required []})
    :fn (fn [_] (ok {:handles (core/list-handles)}))}

   {:name "list_blocks"
    :description "Enumerate the document body in order as a block model: paragraphs (with runs) and tables (rows/cells). Each block gets a stable id (para.N / table.M) for later targeted edits."
    :schema (json/write-str {:type :object
                             :properties {:handle {:type :string}
                                          :include_runs {:type :boolean :default false}}
                             :required ["handle"]})
    :fn (fn [a]
          (let [a (kargs a)]
            (ok (core/list-blocks (:handle a) (boolean (:include_runs a))))))}

   {:name "get_text"
    :description "Return the logical (run-concatenated) text of a block, paragraph, or table cell — i.e. what the reader sees, independent of how it's split across OOXML runs."
    :schema (json/write-str {:type :object
                             :properties {:handle {:type :string}
                                          :block_id {:type :string
                                                     :description "Block id from list_blocks (e.g. para.512, table.3)"}
                                          :cell {:type :string
                                                 :description "Optional 'row,column' within a table block, e.g. '3,2' (0-based)"}}
                             :required ["handle" "block_id"]})
    :fn (fn [a]
          (let [a (kargs a)]
            (ok (core/get-text (:handle a) (:block_id a) (:cell a)))))}

   {:name "find_text"
    :description "Locate all occurrences of a string in the document (run-aware). Returns block/cell/offset matches for mapping."
    :schema (json/write-str {:type :object
                             :properties {:handle {:type :string}
                                          :query {:type :string}
                                          :limit {:type :integer :default 100}}
                             :required ["handle" "query"]})
    :fn (fn [a]
          (let [a (kargs a)]
            (ok (core/find-text (:handle a) (:query a) (or (:limit a) 100)))))}

   {:name "replace_text"
    :description "Replace matched text in place, PRESERVING the run formatting (rPr) of the first matched run. Only character data changes; styles/layout untouched. Optionally scope to one block_id. Reports layout_risk (always \"none\" for character-only edits)."
    :schema (json/write-str {:type :object
                             :properties {:handle {:type :string}
                                          :match {:type :string}
                                          :replacement {:type :string}
                                          :block_id {:type :string
                                                     :description "Scope to one paragraph block (para.N)"}
                                          :scope {:type :string
                                                  :description "Alias for block_id (para.N)"}
                                          :all {:type :boolean :default false}
                                          :layout_risk {:type :string
                                                        :enum ["none" "reflow" "unknown"]
                                                        :description "Server's assessment of whether this edit could move page layout"}}
                             :required ["handle" "match" "replacement"]})
    :fn (fn [a]
          (let [a (kargs a)
                scope (or (:block_id a) (:scope a))]
            (ok (core/replace-text (:handle a)
                                   (:match a)
                                   (:replacement a)
                                   {:block-id scope :all? (:all a)}))))}

   {:name "delete_table_row"
    :description "Structurally remove a visible table row (ghost/vertically-merged rows are skipped). Row removal can re-flow a table, so it is intentionally flagged."
    :schema (json/write-str {:type :object
                             :properties {:handle {:type :string}
                                          :block_id {:type :string}
                                          :row {:type :integer
                                                :description "0-based row index in display order"}}
                             :required ["handle" "block_id" "row"]})
    :fn (fn [a]
          (let [a (kargs a)]
            (ok (core/delete-table-row (:handle a) (:block_id a) (:row a)))))}

   {:name "render"
    :description "Render the document to PDF (default) or per-page PNG via a headless LibreOffice/soffice engine — the ground-truth source for pixel-diffing against the original. Returns written file paths."
    :schema (json/write-str {:type :object
                             :properties {:handle {:type :string}
                                          :format {:type :string :enum ["pdf" "png"] :default "pdf"}
                                          :pages {:type :array :items {:type :integer}
                                                  :description "Only render these 1-based pages (PNG)"}
                                          :dpi {:type :integer :default 150}}
                             :required ["handle"]})
    :fn (fn [a]
          (let [a (kargs a)]
            (ok (core/render! (:handle a)
                              {:format (or (:format a) "pdf")
                               :pages (:pages a)
                               :dpi (:dpi a)}))))}

   {:name "save"
    :description "Flush edits to disk as the specified path."
    :schema (json/write-str {:type :object
                             :properties {:handle {:type :string}
                                          :out_path {:type :string}}
                             :required ["handle" "out_path"]})
    :fn (fn [a]
          (let [a (kargs a)]
            (ok (core/save! (:handle a) (:out_path a)))))}

   {:name "close_document"
    :description "Close a document handle and free its memory."
    :schema (json/write-str {:type :object
                             :properties {:handle {:type :string}}
                             :required ["handle"]})
    :fn (fn [a]
          (let [a (kargs a)]
            (ok {:closed (core/close-handle! (:handle a))})))}])

;; --- server ----------------------------------------------------------------

(defn mcp-server []
  (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "grog-office" "0.1.0")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder) (.tools true) (.build)))
                   (.build))]
    (doseq [t tools]
      (-> (.addTool server (tool t)) (.subscribe)))
    server))

(defn -main [& _args]
  (mcp-server)
  (loop [] (Thread/sleep 1000) (recur)))
