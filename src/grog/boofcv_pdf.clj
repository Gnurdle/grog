(ns grog.boofcv-pdf
  "Rasterize PDF pages and extract line segments with BoofCV (https://github.com/lessthanoptimal/BoofCV).
  Complements ocr_pdf_document: OCR for text, this tool for line drawings / diagrams on the same raster."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [grog.fs :as fs])
  (:import [java.io File]
           [java.awt RenderingHints]
           [org.apache.pdfbox Loader]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.rendering ImageType PDFRenderer]
           [boofcv.io.image ConvertBufferedImage]
           [boofcv.struct.image GrayU8]
           [boofcv.factory.feature.detect.line ConfigLineRansac FactoryDetectLine]
           [boofcv.abst.feature.detect.line DetectLineSegment]
           [georegression.struct.line LineSegment2D_F32]))

(def ^:private pdf-max-file-bytes (* 100 1024 1024))
(def ^:private default-line-max-pages 15)
(def ^:private line-max-pages-cap 40)
(def ^:private default-line-dpi 220)
(def ^:private min-line-dpi 100)
(def ^:private default-max-segments-per-page 400)
(def ^:private max-segments-per-page-cap 800)

(defn- parse-args [arguments]
  (cond (map? arguments) arguments
        (string? arguments) (try (json/parse-string arguments true)
                                 (catch Exception _ {}))
        :else {}))

(def ^:private reading-guide-for-model
  "Structured hints returned with every successful analysis so the model interprets the JSON correctly."
  {:coordinate_system
   {:origin "top-left of the rendered page image"
    :axes "x increases right, y increases down (standard image / PDF raster convention)"
    :units "pixels on the bitmap produced at the given dpi (not PDF points unless you convert)"}
   :what_each_segment_is
   "One straight line segment: BoofCV detected edges on the grayscale page and fitted a line from (x1,y1) to (x2,y2). length_px is Euclidean length in pixels. This is raw geometry, not semantics — not a text box, not a labeled net, not an OCR region."
   :what_this_is_not
   ["Not OCR — call ocr_pdf_document on the same path/dpi for text and labels."
    "Not a list of rectangles or symbols — only straight segments; curves appear as many short chords."
    "Not guaranteed complete — faint lines, anti-aliasing, or dense hatching can split or miss strokes."]
   :count_fields
   {:segment_count "Segments detected on that page before applying max_segments_per_page."
    :segments_returned "How many entries appear in segments (after cap; longest kept first)."
    :segments_truncated "true if segment_count exceeded the cap and shorter segments were dropped."}
   :suggested_use
   "Infer structure by grouping parallel/collinear segments and corner junctions; cross-reference endpoint clusters with OCR word boxes; for a visual check, crop_workspace_image the same PDF page at the same dpi."})

(defn analyze-pdf-line-drawings-tool-spec []
  {:type "function"
   :function
   {:name "analyze_pdf_line_drawings"
    :description (str "BoofCV line-segment extraction from a rasterized PDF (edge detection + RANSAC line fitting on a grid). "
                      "Output is **geometry only**: each item is a straight segment (x1,y1)→(x2,y2) in **image pixels** at the chosen dpi, "
                      "origin **top-left**, y **down**. It is **not** OCR, not labeled objects, and not word bounding boxes — use ocr_pdf_document "
                      "for text on the same path/dpi. Long diagrams may return many short segments (noise, curves, hatching). "
                      "The JSON includes a reading_guide object: read it before inferring schematics. "
                      "Workspace path to a PDF; pair with crop_workspace_image (same dpi) to snapshot a region as PNG.")
    :parameters {:type "object"
                 :required ["path"]
                 :properties {:path {:type "string"
                                     :description "Path to PDF under workspace root (any extension if file is PDF)."}
                              :max_pages {:type "integer"
                                          :description "Max pages to analyze (default 15, cap 40)."}
                              :dpi {:type "integer"
                                    :description (str "Raster DPI (default 220; min 100, max " fs/max-pdf-raster-dpi
                                                      "). Same dpi as ocr_pdf_document when mixing line geometry with OCR.")}
                              :max_segments_per_page {:type "integer"
                                                      :description "Max segments listed per page (default 400, cap 800); list is longest-first, extras omitted."}
                              :region_size {:type "integer"
                                            :description "Optional BoofCV RANSAC tile size in pixels (e.g. 40–80). Omit for defaults."}}}}})

(defn- segment->map [^LineSegment2D_F32 s]
  (let [a (.getA s) b (.getB s)]
    {:x1 (float (.x a)) :y1 (float (.y a))
     :x2 (float (.x b)) :y2 (float (.y b))
     :length_px (float (.getLength s))}))

(defn- make-line-detector ^DetectLineSegment [region-size-or-nil]
  (let [^ConfigLineRansac cfg (ConfigLineRansac.)]
    (when (number? region-size-or-nil)
      (let [rs (max 20 (min 120 (long region-size-or-nil)))]
        (set! (.regionSize cfg) (int rs))))
    (.checkValidity cfg)
    (FactoryDetectLine/lineRansac cfg GrayU8)))

(defn- pdf-renderer ^PDFRenderer [^PDDocument doc]
  (doto (PDFRenderer. doc)
    (.setSubsamplingAllowed false)
    (.setRenderingHints
     (doto (RenderingHints. RenderingHints/KEY_INTERPOLATION
                            RenderingHints/VALUE_INTERPOLATION_BICUBIC)
       (.put RenderingHints/KEY_RENDERING RenderingHints/VALUE_RENDER_QUALITY)
       (.put RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
       (.put RenderingHints/KEY_TEXT_ANTIALIASING
             RenderingHints/VALUE_TEXT_ANTIALIAS_ON)))))

(defn- analyze-one-page!
  [^DetectLineSegment detector ^PDFRenderer renderer page-idx dpi max-per-page]
  (let [img (.renderImageWithDPI renderer (int page-idx) (float dpi) ImageType/RGB)
        ^GrayU8 gray (ConvertBufferedImage/convertFromSingle img nil GrayU8)
        found (.detect detector gray)
        mapped (mapv segment->map found)
        sorted (sort-by (comp - :length_px) mapped)
        trimmed (vec (take max-per-page sorted))]
    {:width_px (.getWidth gray)
     :height_px (.getHeight gray)
     :segment_count (count found)
     :segments_returned (count trimmed)
     :segments_truncated (> (count found) (count trimmed))
     :segments trimmed}))

(defn run-analyze-pdf-line-drawings!
  [arguments]
  (try
    (let [m (parse-args arguments)
          path-str (or (some-> (:path m) str str/trim not-empty)
                       (some-> (get m "path") str str/trim not-empty))
          max-pages (let [x (or (:max_pages m) (get m "max_pages"))]
                      (cond (number? x) (min line-max-pages-cap (max 1 (long x)))
                            :else default-line-max-pages))
          dpi (let [x (or (:dpi m) (get m "dpi"))]
                (cond (number? x) (min fs/max-pdf-raster-dpi (max min-line-dpi (long x)))
                      :else default-line-dpi))
          max-seg (let [x (or (:max_segments_per_page m) (get m "max_segments_per_page"))]
                    (cond (number? x) (min max-segments-per-page-cap (max 10 (long x)))
                          :else default-max-segments-per-page))
          region-opt (or (:region_size m) (get m "region_size"))
          ^File f (fs/resolve-workspace-path! path-str)]
      (cond
        (str/blank? path-str)
        (json/generate-string {:error "path is required"})

        (not (.exists f))
        (json/generate-string {:error "file not found" :path path-str})

        (not (.isFile f))
        (json/generate-string {:error "not a regular file" :path path-str})

        (> (.length f) pdf-max-file-bytes)
        (json/generate-string {:error "PDF too large" :path path-str
                               :size_bytes (.length f) :max_bytes pdf-max-file-bytes})

        :else
        (with-open [^PDDocument doc (Loader/loadPDF f)]
          (when (.isEncrypted doc)
            (throw (ex-info "encrypted PDFs are not supported" {:path path-str})))
          (let [page-count (.getNumberOfPages doc)
                end (int (min page-count max-pages))
                ^DetectLineSegment detector (make-line-detector region-opt)
                ^PDFRenderer renderer (pdf-renderer doc)
                pages (mapv (fn [pidx]
                              (assoc (analyze-one-page! detector renderer pidx dpi max-seg)
                                     :page (inc pidx)))
                            (range 0 end))]
            (json/generate-string
             {:format "pdf_line_segments"
              :library "BoofCV"
              :library_version "1.2.2"
              :upstream "https://github.com/lessthanoptimal/BoofCV"
              :method "line_ransac_grid"
              :path path-str
              :dpi dpi
              :page_count page-count
              :pages_analyzed end
              :pages_truncated (> page-count end)
              :max_segments_per_page max-seg
              :pages pages
              :reading_guide reading-guide-for-model
              :hint (str "Read reading_guide first. Each segments[] element: straight line in pixel space at this dpi; "
                         "not text. To save a region as PNG, bbox segment endpoints (optional pad_px) and crop_workspace_image "
                         "same path, page, dpi. Pair with ocr_pdf_document for labels.")})))))
    (catch Exception e
      (json/generate-string {:error (or (.getMessage e) "line drawing analysis failed")
                             :detail (str e)}))))

(defn startup-status-line []
  "BoofCV: analyze_pdf_line_drawings — line segments on rasterized PDFs (github.com/lessthanoptimal/BoofCV)")
