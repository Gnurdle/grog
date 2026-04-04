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
(def ^:private max-line-dpi 400)
(def ^:private default-max-segments-per-page 400)
(def ^:private max-segments-per-page-cap 800)

(defn- parse-args [arguments]
  (cond (map? arguments) arguments
        (string? arguments) (try (json/parse-string arguments true)
                                 (catch Exception _ {}))
        :else {}))

(defn analyze-pdf-line-drawings-tool-spec []
  {:type "function"
   :function
   {:name "analyze_pdf_line_drawings"
    :description (str "Extract line segments from rasterized .pdf pages using BoofCV (RANSAC grid on edges). "
                      "For technical drawings, schematics, axes, and diagrams where geometry matters. "
                      "Use together with ocr_pdf_document on the same path/dpi when the page mixes text and line art. "
                      "Returns JSON: per-page segment lists (endpoints + length), sorted longest-first; counts and truncation flags.")
    :parameters {:type "object"
                 :required ["path"]
                 :properties {:path {:type "string"
                                     :description "Path to .pdf under workspace root."}
                              :max_pages {:type "integer"
                                          :description "Max pages to analyze (default 15, cap 40)."}
                              :dpi {:type "integer"
                                    :description "Raster DPI (default 220; 100–400). Match ocr_pdf_document dpi when combining."}
                              :max_segments_per_page {:type "integer"
                                                      :description "Cap segments returned per page (default 400, cap 800), longest first."}
                              :region_size {:type "integer"
                                            :description "Optional BoofCV RANSAC tile size (e.g. 40–80). Omit for library defaults."}}}}})

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
                (cond (number? x) (min max-line-dpi (max min-line-dpi (long x)))
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

        (not (str/ends-with? (str/lower-case path-str) ".pdf"))
        (json/generate-string {:error "only .pdf is supported" :path path-str})

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
              :hint (str "Segments are longest-first in pixel coords at the given dpi. "
                         "Pair with ocr_pdf_document (same path/dpi) for labels and text.")})))))
    (catch Exception e
      (json/generate-string {:error (or (.getMessage e) "line drawing analysis failed")
                             :detail (str e)}))))

(defn startup-status-line []
  "BoofCV: analyze_pdf_line_drawings — line segments on rasterized PDFs (github.com/lessthanoptimal/BoofCV)")
