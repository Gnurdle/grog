(ns grog-imaging.main
  "grog-imaging — an MCP server (over stdio) exposing grog's heavyweight
  document/imaging tools so an ECA-driven agent loop can call them.

  These are the tools ECA does NOT provide natively, but grog does (boofcv
  PDF/OCR, office docs, image crop/write). The real implementations live in
  `grog-imaging.tools` (ported from grog's `fs.clj`/`boofcv_pdf.clj`, reusing
  the vendored `grog-imaging.image` engine); paths are taken as given.

  ECA discovers this over stdio exactly like its own mcp-server-sample:
    :mcpServers
      {\"grog-imaging\"
        {:command \"clojure\" :args [\"-M:server\" \"-m\" \"grog-imaging.main\"]}}
  "
  (:require [clojure.data.json :as json]
            [grog-imaging.tools :as tools])
  (:import [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
           [io.modelcontextprotocol.server McpServer]
           [io.modelcontextprotocol.server McpServerFeatures$AsyncToolSpecification]
           [io.modelcontextprotocol.spec
            McpSchema$ServerCapabilities
            McpSchema$Tool
            McpSchema$CallToolResult
            McpSchema$TextContent]
           [reactor.core.publisher Mono]
           [com.fasterxml.jackson.databind ObjectMapper]))

;; --- helpers ---------------------------------------------------------------

(defn- text-content [^String s] (McpSchema$TextContent. s))
(defn- text-result [^String s] (McpSchema$CallToolResult. [(text-content s)] false))
(defn- error-result [^String s] (McpSchema$CallToolResult. [(text-content s)] true))

(defn- tool
  "Build an async MCP tool spec from {:name :description :schema-json :fn}.
  `fn` receives the args map and must return a string; the returned string is
  surfaced to the client as the tool's text output."
  [{:keys [name description schema fn]}]
  (McpServerFeatures$AsyncToolSpecification.
    (McpSchema$Tool. name description schema)
    (reify java.util.function.BiFunction
      (apply [_this _exchange arguments]
        (Mono/create
          (reify java.util.function.Consumer
            (accept [_this sink]
              (try
                (.success sink (text-result (fn arguments)))
                (catch Throwable t
                  (.success sink (error-result (str "grog-imaging " name " failed: "
                                                    (.getMessage t)))))))))))))

;; --- tool definitions -------------------------------------------------------
;; Handlers call the real implementations in grog-imaging.tools, which return a
;; JSON string (the same shape grog's tools produced).

(def tools
  [{:name "read_pdf_document"
    :description "Extract text from a PDF file at `path` (optionally pages split by newline)."
    :schema (json/write-str {:type :object
                             :properties {:path {:type :string}
                                          :max_pages {:type :integer}}
                             :required [:path]})
    :fn (fn [a] (tools/run-read-pdf-document! a))}

   {:name "ocr_pdf_document"
    :description "OCR the raster pages of a PDF at `path` (handles scanned documents)."
    :schema (json/write-str {:type :object
                             :properties {:path {:type :string}
                                          :max_pages {:type :integer}
                                          :dpi {:type :integer}
                                          :language {:type :string}
                                          :preprocess {:type :boolean}}
                             :required [:path]})
    :fn (fn [a] (tools/run-ocr-pdf-document! a))}

   {:name "analyze_pdf_line_drawings"
    :description "BoofCV line-segment extraction from a rasterized PDF (edge detection + RANSAC). Geometry only (pixels), not OCR."
    :schema (json/write-str {:type :object
                             :properties {:path {:type :string}
                                          :max_pages {:type :integer}
                                          :dpi {:type :integer}
                                          :max_segments_per_page {:type :integer}
                                          :region_size {:type :integer}}
                             :required [:path]})
    :fn (fn [a] (tools/run-analyze-pdf-line-drawings! a))}

   {:name "read_office_document"
    :description "Extract text from an Office document (.docx/.xlsx/.xls) at `path`."
    :schema (json/write-str {:type :object
                             :properties {:path {:type :string}
                                          :offset {:type :integer}
                                          :limit {:type :integer}
                                          :sheet_index {:type :integer}
                                          :start_row {:type :integer}
                                          :row_limit {:type :integer}}
                             :required [:path]})
    :fn (fn [a] (tools/run-read-office-document! a))}

   {:name "write_workspace_png"
    :description "Write PNG bytes (base64 in `png_base64`) to `path`. Returns its location."
    :schema (json/write-str {:type :object
                             :properties {:path {:type :string}
                                          :png_base64 {:type :string}}
                             :required [:path "png_base64"]})
    :fn (fn [a] (tools/run-write-workspace-png! a))}

   {:name "crop_workspace_image"
    :description "Crop an image/PDF at `source_path` to a box (x y width height), write PNG to `out_path`. For PDF, `page` (1-based) and `dpi` are required."
    :schema (json/write-str {:type :object
                             :properties {:source_path {:type :string}
                                          :out_path {:type :string}
                                          :x {:type :integer}
                                          :y {:type :integer}
                                          :width {:type :integer}
                                          :height {:type :integer}
                                          :page {:type :integer}
                                          :dpi {:type :integer}
                                          :pad_px {:type :integer}}
                             :required [:source_path :out_path]})
    :fn (fn [a] (tools/run-crop-workspace-image! a))}

   {:name "read_png_image"
    :description "Decode a PNG/JPG at `path` and return metadata (dimensions, alpha, color depth, byte size) plus optional dominant-color statistics."
    :schema (json/write-str {:type :object
                             :properties {:path {:type :string}
                                          :color_stats {:type :boolean}
                                          :max_colors {:type :integer}}
                             :required [:path]})
    :fn (fn [a] (tools/run-read-png-image! a))}

   {:name "ocr_image"
    :description "OCR the pixels of a PNG/JPG at `path` (Tesseract). Returns plain text plus optional per-word bounding boxes + confidence via `with_boxes`."
    :schema (json/write-str {:type :object
                             :properties {:path {:type :string}
                                          :dpi {:type :integer}
                                          :language {:type :string}
                                          :page_seg_mode {:type :integer}
                                          :preprocess {:type :boolean}
                                          :with_boxes {:type :boolean}}
                             :required [:path]})
    :fn (fn [a] (tools/run-ocr-image! a))}

   {:name "analyze_image_shapes"
    :description "BoofCV geometry on a PNG/JPG at `path`: line segments (RANSAC), rectangles, ellipses, blobs (threshold + contours), and heuristic arrow candidates. All geometry in source-image pixel coordinates (x right, y down)."
    :schema (json/write-str {:type :object
                             :properties {:path {:type :string}
                                          :region_size {:type :integer}
                                          :max_lines {:type :integer}
                                          :max_rectangles {:type :integer}
                                          :max_ellipses {:type :integer}
                                          :max_blobs {:type :integer}
                                          :max_arrows {:type :integer}
                                          :min_blob_area {:type :number}}
                             :required [:path]})
    :fn (fn [a] (tools/run-analyze-image-shapes! a))}

   {:name "draw_overlay_png"
    :description "Draw overlay geometry (rectangles/lines/ellipses/text_boxes/blobs/arrows) from `overlays` onto the raster at `source_path` and write the annotated PNG to `out_path`."
    :schema (json/write-str {:type :object
                             :properties {:source_path {:type :string}
                                          :out_path {:type :string}
                                          :overlays {:type :object}}
                             :required [:source_path :out_path]})
    :fn (fn [a] (tools/run-draw-overlay-png! a))}])

;; --- server ----------------------------------------------------------------

(defn mcp-server []
  (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "grog-imaging" "0.1.0")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                      (.tools true)
                                      (.build)))
                   (.build))]
    (doseq [t tools]
      (-> (.addTool server (tool t)) (.subscribe)))
    server))

(defn -main [& _args]
  (mcp-server)
  ;; stdio MCP server: block forever; the client (ECA) owns our lifecycle and
  ;; kills the process when the session ends.
  (loop []
    (Thread/sleep 1000)
    (recur)))
