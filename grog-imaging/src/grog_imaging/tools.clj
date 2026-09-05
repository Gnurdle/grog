(ns grog-imaging.tools
  "Real implementations of grog's document/imaging tools, ported from grog's
  `fs.clj` / `image.clj` / `boofcv_pdf.clj`. Paths are taken **as given** (no
  grog workspace-root semantics — this is a standalone MCP server, so callers
  pass absolute paths or paths relative to their own root).

  Reuses the vendored self-contained engine `grog-imaging.image` for OCR, PNG
  decode/write and image cropping."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog-imaging.image :as img])
  (:import [java.awt Graphics2D RenderingHints]
           [java.awt.image BufferedImage]
           [java.io File FileInputStream]
           [java.nio.file Files StandardOpenOption]
           [javax.imageio ImageIO]
           [org.apache.pdfbox Loader]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.text PDFTextStripper]
           [org.apache.poi.ss.usermodel DataFormatter WorkbookFactory]
           [org.apache.poi.xwpf.usermodel XWPFDocument XWPFParagraph
            XWPFTable XWPFTableCell XWPFTableRow]))

(set! *warn-on-reflection* true)

;; --- shared helpers ---------------------------------------------------------

(defn- parse-args [arguments]
  (cond
    (nil? arguments) {}
    (map? arguments) arguments
    (instance? java.util.Map arguments) (into {} arguments)
    (instance? com.fasterxml.jackson.databind.JsonNode arguments)
    (try (json/parse-string (str arguments) true) (catch Exception _ {}))
    (string? arguments) (try (json/parse-string arguments true)
                             (catch Exception _ {}))
    :else {}))

(defn- as-file ^File [s] (io/file s))

(defn- fnum [m]
  (or (some-> (:path m) str str/trim not-empty)
      (some-> (get m "path") str str/trim not-empty)))

;; --- read_pdf_document (PDFBox text) ---------------------------------------

(def ^:private default-pdf-max-pages 100)
(def ^:private pdf-max-pages-cap 500)
(def ^:private pdf-max-file-bytes (* 100 1024 1024))
(def ^:private pdf-max-text-chars (* 2 1024 1024))

(defn- extract-pdf-text!
  [^File f ^long max-pages]
  (with-open [^PDDocument doc (Loader/loadPDF f)]
    (when (.isEncrypted doc)
      (throw (ex-info "encrypted PDFs are not supported" {:path (.getPath f)})))
    (let [page-count (.getNumberOfPages doc)]
      (if (zero? page-count)
        {:page_count 0 :pages_read 0 :pages_truncated false :text ""}
        (let [end-page (int (min page-count max-pages))
              ^PDFTextStripper stripper (doto (PDFTextStripper.)
                                          (.setStartPage 1)
                                          (.setEndPage end-page))
              text (or (.getText stripper doc) "")]
          {:page_count page-count
           :pages_read end-page
           :pages_truncated (> page-count end-page)
           :text text})))))

(defn run-read-pdf-document!
  [arguments]
  (try
    (let [m (parse-args arguments)
          path-str (fnum m)
          max-pages (let [x (or (:max_pages m) (get m "max_pages"))]
                      (cond (number? x) (min pdf-max-pages-cap (max 1 (long x)))
                            :else default-pdf-max-pages))
          ^File f (as-file path-str)]
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
        (let [{:keys [page_count pages_read pages_truncated text]} (extract-pdf-text! f max-pages)
              ^String t text
              text-truncated (> (count t) pdf-max-text-chars)
              t-out (if text-truncated (subs t 0 (min (count t) pdf-max-text-chars)) t)]
          (json/generate-string {:format "pdf"
                                 :path path-str
                                 :page_count page_count
                                 :pages_read pages_read
                                 :pages_truncated pages_truncated
                                 :text_truncated text-truncated
                                 :text_char_limit (when text-truncated pdf-max-text-chars)
                                 :text t-out}))))
    (catch Exception e
      (json/generate-string {:error (or (.getMessage e) "pdf extract failed")
                             :detail (str e)}))))

;; --- ocr_pdf_document (Tesseract via grog-imaging.image) -------------------

(defn run-ocr-pdf-document!
  [arguments]
  (try
    (let [m (parse-args arguments)
          path-str (fnum m)
          max-pages (let [x (or (:max_pages m) (get m "max_pages"))]
                      (cond (number? x) (min img/ocr-max-pages-cap (max 1 (long x)))
                            :else img/default-ocr-max-pages))
          dpi (let [x (or (:dpi m) (get m "dpi"))]
                (cond (number? x) (min img/max-pdf-raster-dpi (max img/min-ocr-dpi (long x)))
                      :else img/default-ocr-dpi))
          lang-out (or (some-> (:language m) str str/trim not-empty)
                       (some-> (get m "language") str str/trim not-empty)
                       "eng")
          psm (img/parse-ocr-psm m)
          preprocess? (img/json-bool (or (:preprocess m) (get m "preprocess")) true)
          ^File f (as-file path-str)
          datapath (img/tessdata-path-or-nil)]
      (cond
        (str/blank? datapath)
        (json/generate-string {:error "Tesseract tessdata not found"
                               :path path-str
                               :hint (str "Install tesseract-ocr + language packs, or set TESSDATA_PREFIX "
                                          "so a tessdata dir contains *.traineddata files.")})

        (not (.exists f))       (json/generate-string {:error "file not found" :path path-str})
        (not (.isFile f))       (json/generate-string {:error "not a regular file" :path path-str})
        (> (.length f) img/pdf-max-file-bytes)
        (json/generate-string {:error "PDF too large" :path path-str
                               :size_bytes (.length f) :max_bytes img/pdf-max-file-bytes})

        :else
        (let [{:keys [page_count pages_read pages_truncated dpi text language
                      page_seg_mode preprocess]}
              (img/extract-pdf-ocr! f max-pages dpi lang-out datapath psm preprocess?)
              ^String t text
              text-truncated (> (count t) img/pdf-max-text-chars)
              t-out (if text-truncated (subs t 0 (min (count t) img/pdf-max-text-chars)) t)]
          (json/generate-string {:format "pdf_ocr"
                                 :path path-str
                                 :tessdata datapath
                                 :page_count page_count
                                 :pages_read pages_read
                                 :pages_truncated pages_truncated
                                 :dpi dpi
                                 :language language
                                 :page_seg_mode page_seg_mode
                                 :preprocess preprocess
                                 :ocr_engine "lstm"
                                 :text_truncated text-truncated
                                 :text_char_limit (when text-truncated img/pdf-max-text-chars)
                                 :text t-out}))))
    (catch Exception e
      (json/generate-string {:error (or (.getMessage e) "pdf OCR failed")
                             :detail (str e)}))))

;; --- read_office_document (POI docx/xlsx) ----------------------------------

(def ^:private default-office-max-chars (* 512 1024))
(def ^:private office-max-chars-cap (* 4 1024 1024))
(def ^:private office-max-elements-cap 10000)
(def ^:private office-max-rows-cap 100000)

(defn- extract-docx
  [^File f offset limit]
  (with-open [in (FileInputStream. f)
              doc (XWPFDocument. in)]
    (let [els (vec (.getBodyElements doc))
          total (count els)
          text-buf (StringBuilder.)
          tables (volatile! [])
          read-count (volatile! 0)
          limit* (when limit (min (long limit) office-max-elements-cap))
          stop-at (if limit* (+ (long offset) (long limit*)) total)]
      (doseq [idx (range (long offset) (min total stop-at))]
        (let [el (nth els idx)]
          (cond
            (instance? XWPFParagraph el)
            (let [t (.getText ^XWPFParagraph el)]
              (when-not (str/blank? t)
                (.append text-buf t) (.append text-buf "\n")))
            (instance? XWPFTable el)
            (let [^XWPFTable tbl el
                  rows (for [^XWPFTableRow row (.getRows tbl)]
                         (vec (for [^XWPFTableCell cell (.getTableCells row)]
                                (str/trim (.getText cell)))))]
              (vswap! tables conj {:source "word_table" :element_index idx :rows (vec rows)}))
            :else nil))
        (vswap! read-count inc))
      {:text (str text-buf)
       :tables @tables
       :total_elements (long total)
       :elements_read (long @read-count)})))

(defn- extract-xlsx
  [^File f sheet-index start-row row-limit]
  (with-open [wb (WorkbookFactory/create f)]
    (let [fmt (DataFormatter.)
          total-sheets (.getNumberOfSheets wb)
          _ (when (and (some? sheet-index) (or (neg? sheet-index) (>= sheet-index total-sheets)))
              (throw (ex-info (str "sheet_index " sheet-index " out of range (0.." (dec total-sheets) ")")
                              {:total_sheets total-sheets})))
          idxs (if (nil? sheet-index) (range total-sheets) [(long sheet-index)])
          row-limit* (when row-limit (min (long row-limit) office-max-rows-cap))
          sheets (mapv
                  (fn [si]
                    (let [sh (.getSheetAt wb (int si))
                          name (.getSheetName sh)
                          last-row (.getLastRowNum sh)
                          start (long (or start-row 0))
                          end-row (cond (nil? row-limit*) last-row
                                        :else (min last-row (dec (+ start (long row-limit*)))))
                          rows (vec
                                (for [r (range start (inc (max -1 end-row)))
                                      :let [row (.getRow sh r)]
                                      :when row]
                                  (vec (for [c (range (max 1 (long (.getLastCellNum row))))
                                             :let [cell (.getCell row c)]]
                                         (if cell (.formatCellValue fmt cell) "")))))]
                      {:source "excel_sheet"
                       :sheet name
                       :sheet_index (long si)
                       :first_row start
                       :last_data_row (long last-row)
                       :next_start_row (if (and end-row (< (inc end-row) (inc last-row)))
                                         (inc end-row) nil)
                       :rows_read (count rows)
                       :rows rows}))
                  idxs)]
      {:text (str/join "\n\n"
                       (map (fn [{:keys [sheet rows]}]
                              (str "## " sheet "\n"
                                   (str/join "\n" (map #(str/join "\t" %) rows))))
                            sheets))
       :tables sheets
       :total_sheets (long total-sheets)})))

(defn- extract-office-sliced
  [^File f path-str offset limit sheet-index start-row row-limit]
  (let [lower (str/lower-case path-str)
        ext (some-> (re-find #"\.([^.]+)$" lower) second)]
    (cond
      (= ext "docx")
      (assoc (extract-docx f offset limit) :format "docx" :path path-str)

      (or (= ext "xlsx") (= ext "xls"))
      (assoc (extract-xlsx f sheet-index start-row row-limit) :format "xlsx" :path path-str)

      :else
      (try
        (assoc (extract-xlsx f sheet-index start-row row-limit) :format "xlsx" :path path-str)
        (catch Exception _
          (assoc (extract-docx f offset limit) :format "docx" :path path-str))))))

(defn run-read-office-document!
  [arguments]
  (try
    (let [m (parse-args arguments)
          path-str (fnum m)
          offset (let [x (or (:offset m) (get m "offset"))]
                   (cond (number? x) (max 0 (long x)) :else 0))
          limit (let [x (or (:limit m) (get m "limit"))]
                  (cond (number? x) (min office-max-elements-cap (max 1 (long x))) :else nil))
          max-chars (let [x (or (:max_chars m) (get m "max_chars"))]
                      (cond (number? x) (min office-max-chars-cap (max 1 (long x)))
                            :else default-office-max-chars))
          sheet-index (let [x (or (:sheet_index m) (get m "sheet_index"))]
                        (cond (number? x) (long x) :else nil))
          start-row (let [x (or (:start_row m) (get m "start_row"))]
                      (cond (number? x) (max 0 (long x)) :else nil))
          row-limit (let [x (or (:row_limit m) (get m "row_limit"))]
                      (cond (number? x) (min office-max-rows-cap (max 1 (long x))) :else nil))
          ^File f (as-file path-str)]
      (cond
        (not (.exists f)) (json/generate-string {:error "file not found" :path path-str})
        (not (.isFile f)) (json/generate-string {:error "not a regular file" :path path-str})

        :else
        (try
          (let [{:keys [text elements_read] :as extracted}
                (extract-office-sliced f path-str offset limit sheet-index start-row row-limit)
                ^String t (or text "")
                total-chars (count t)
                text-truncated (> total-chars max-chars)
                t-out (if text-truncated (subs t 0 (min total-chars max-chars)) t)
                docx? (= (:format extracted) "docx")
                result (cond-> (assoc extracted :text t-out :text_truncated text-truncated
                                      :text_char_limit (when text-truncated max-chars)
                                      :total_chars total-chars)
                         docx? (assoc :offset offset :limit limit
                                      :next_offset (+ offset (long (or elements_read 0)))))]
            (json/generate-string result))
          (catch Exception e
            (json/generate-string {:error "not a readable Office document (.docx / .xlsx / .xls)"
                                   :path path-str :detail (.getMessage e)})))))
    (catch Exception e
      (json/generate-string {:error (or (.getMessage e) "office extract failed")
                             :detail (str e)}))))

;; --- write_workspace_png / crop_workspace_image ----------------------------

(defn run-write-workspace-png!
  [arguments]
  (try
    (let [m (parse-args arguments)
          path-str (fnum m)
          b64 (or (some-> (:png_base64 m) str not-empty)
                  (some-> (get m "png_base64") str not-empty)
                  (some-> (:pngBase64 m) str not-empty)
                  (some-> (get m "pngBase64") str not-empty))]
      (cond
        (str/blank? path-str) (json/generate-string {:error "path is required"})
        (str/blank? b64)      (json/generate-string {:error "png_base64 is required"})
        (not (img/png-extension? path-str)) (json/generate-string {:error "path must end with .png" :path path-str})

        :else
        (let [^bytes bs (img/decode-png-base64-bytes! b64)
              ^File f (as-file path-str)]
          (if (and (.exists f) (.isDirectory f))
            (json/generate-string {:error "path is a directory" :path path-str})
            (let [^File parent (.getParentFile f)]
              (when parent (.mkdirs parent))
              (Files/write (.toPath f) bs
                           (into-array StandardOpenOption
                                       [StandardOpenOption/CREATE
                                        StandardOpenOption/WRITE
                                        StandardOpenOption/TRUNCATE_EXISTING]))
              (json/generate-string {:ok true :path path-str :format "png"
                                     :bytes_written (alength bs)}))))))
    (catch Exception e
      (json/generate-string {:error (or (.getMessage e) "PNG write failed") :detail (str e)}))))

(defn- gnum [m kw & str-keys]
  (let [x (or (get m kw) (some #(get m %) str-keys))]
    (cond (number? x) (long x)
          (and (string? x) (not (str/blank? x)))
          (try (Long/parseLong (str/trim x)) (catch NumberFormatException _ nil))
          :else nil)))

(defn run-crop-workspace-image!
  [arguments]
  (try
    (let [m (parse-args arguments)
          src (or (some-> (:source_path m) str str/trim not-empty)
                  (some-> (get m "source_path") str str/trim not-empty)
                  (some-> (:sourcePath m) str str/trim not-empty))
          out (or (some-> (:out_path m) str str/trim not-empty)
                  (some-> (get m "out_path") str str/trim not-empty)
                  (some-> (:outPath m) str str/trim not-empty))
          x (gnum m :x "x") y (gnum m :y "y")
          w (gnum m :width "width") h (gnum m :height "height")
          page (gnum m :page "page")
          dpi (long (min img/max-pdf-raster-dpi (max 72 (or (gnum m :dpi "dpi") img/crop-default-pdf-dpi))))
          pad-raw (or (gnum m :pad_px "pad_px") (gnum m :padPx "padPx"))
          pad (if (nil? pad-raw) 0 (min img/crop-max-pad (max 0 (long pad-raw))))]
      (cond
        (or (str/blank? src) (str/blank? out))
        (json/generate-string {:error "source_path and out_path are required"})

        (not (img/png-extension? out))
        (json/generate-string {:error "out_path must end with .png" :out_path out})

        (some nil? [x y w h])
        (json/generate-string {:error "x, y, width, height must be integers"})

        (or (< w 1) (> w img/crop-max-edge) (< h 1) (> h img/crop-max-edge))
        (json/generate-string {:error "width and height must be between 1 and max (pixels)" :max_edge img/crop-max-edge})

        (or (< x 0) (< y 0))
        (json/generate-string {:error "x and y must be >= 0"})

        :else
        (let [^File src-f (as-file src)
              kind (img/file-kind-raster-or-pdf (.getName src-f))]
          (cond
            (not (.exists src-f)) (json/generate-string {:error "source file not found" :source_path src})
            (not (.isFile src-f)) (json/generate-string {:error "source is not a regular file" :source_path src})
            (= :unknown kind)     (json/generate-string {:error "source must be .png, .jpg, .jpeg, or .pdf" :source_path src})
            (and (= :pdf kind) (or (nil? page) (< (long page) 1)))
            (json/generate-string {:error "page is required for PDF (1-based page index)" :source_path src})

            :else
            (let [^BufferedImage full (img/load-image-for-crop! src-f kind page dpi)
                  iw (.getWidth full) ih (.getHeight full)
                  x0 (max 0 (- x pad)) y0 (max 0 (- y pad))
                  x1 (min iw (+ x w pad)) y1 (min ih (+ y h pad))
                  cw (max 1 (- x1 x0)) ch (max 1 (- y1 y0))]
              (when (or (> cw img/crop-max-edge) (> ch img/crop-max-edge))
                (throw (ex-info "padded crop exceeds max edge" {:width cw :height ch :max img/crop-max-edge})))
              (when (or (> (+ x0 cw) iw) (> (+ y0 ch) ih))
                (throw (ex-info "crop rectangle out of bounds"
                                {:image_width iw :image_height ih :x x0 :y y0 :width cw :height ch})))
              (let [^BufferedImage sub (.getSubimage full (int x0) (int y0) (int cw) (int ch))
                    ^File out-f (as-file out)
                    parent (.getParentFile out-f)]
                (when (and (.exists out-f) (.isDirectory out-f))
                  (throw (ex-info "out_path is a directory" {:out_path out})))
                (when parent (.mkdirs parent))
                (when-not (ImageIO/write sub "png" out-f)
                  (throw (ex-info "failed to write PNG" {:out_path out})))
                (json/generate-string
                 (cond-> {:format "image_crop"
                          :source_path src :out_path out
                          :source_kind (name kind)
                          :crop_applied {:x x0 :y y0 :width cw :height ch}
                          :requested {:x x :y y :width w :height h :pad_px pad}
                          :source_dimensions {:width iw :height ih}
                          :hint (str "Saved PNG crop. Use same dpi as analyze_pdf_line_drawings from PDF; "
                                     "pair with ocr_pdf_document on the region if you need text.")}
                   (= :pdf kind) (assoc :pdf {:page (long page) :dpi dpi})))))))))
    (catch Exception e
      (json/generate-string {:error (or (.getMessage e) "image crop failed") :detail (str e)}))))

;; --- analyze_pdf_line_drawings (BoofCV, via shared grog-imaging.image kernel) -

(def ^:private default-line-max-pages 15)
(def ^:private line-max-pages-cap 40)
(def ^:private default-line-dpi 220)
(def ^:private min-line-dpi 100)
(def ^:private default-max-segments-per-page 400)
(def ^:private max-segments-per-page-cap 800)

(defn- pdf-renderer ^org.apache.pdfbox.rendering.PDFRenderer [^PDDocument doc]
  (doto (org.apache.pdfbox.rendering.PDFRenderer. doc)
    (.setSubsamplingAllowed false)
    (.setRenderingHints
     (doto (RenderingHints. RenderingHints/KEY_INTERPOLATION
                            RenderingHints/VALUE_INTERPOLATION_BICUBIC)
       (.put RenderingHints/KEY_RENDERING RenderingHints/VALUE_RENDER_QUALITY)
       (.put RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
       (.put RenderingHints/KEY_TEXT_ANTIALIASING RenderingHints/VALUE_TEXT_ANTIALIAS_ON)))))

(defn run-analyze-pdf-line-drawings!
  [arguments]
  (try
    (let [m (parse-args arguments)
          path-str (fnum m)
          max-pages (let [x (or (:max_pages m) (get m "max_pages"))]
                      (cond (number? x) (min line-max-pages-cap (max 1 (long x)))
                            :else default-line-max-pages))
          dpi (let [x (or (:dpi m) (get m "dpi"))]
                (cond (number? x) (min img/max-pdf-raster-dpi (max min-line-dpi (long x)))
                      :else default-line-dpi))
          max-seg (let [x (or (:max_segments_per_page m) (get m "max_segments_per_page"))]
                    (cond (number? x) (min max-segments-per-page-cap (max 10 (long x)))
                          :else default-max-segments-per-page))
          region-opt (or (:region_size m) (get m "region_size"))
          ^File f (as-file path-str)]
      (cond
        (str/blank? path-str)      (json/generate-string {:error "path is required"})
        (not (.exists f))          (json/generate-string {:error "file not found" :path path-str})
        (not (.isFile f))          (json/generate-string {:error "not a regular file" :path path-str})
        (> (.length f) pdf-max-file-bytes)
        (json/generate-string {:error "PDF too large" :path path-str
                               :size_bytes (.length f) :max_bytes pdf-max-file-bytes})

        :else
        (with-open [^PDDocument doc (Loader/loadPDF f)]
          (when (.isEncrypted doc)
            (throw (ex-info "encrypted PDFs are not supported" {:path path-str})))
          (let [page-count (.getNumberOfPages doc)
                end (int (min page-count max-pages))
                renderer (pdf-renderer doc)
                pages (mapv (fn [pidx]
                              (let [^BufferedImage rgb
                                    (.renderImageWithDPI renderer (int pidx) (float dpi)
                                                         org.apache.pdfbox.rendering.ImageType/RGB)]
                                (assoc (img/raster-lines! rgb region-opt max-seg)
                                       :page (inc pidx))))
                            (range 0 end))]
            (json/generate-string
             {:format "pdf_line_segments"
              :library "BoofCV" :method "line_ransac_grid"
              :path path-str :dpi dpi
              :page_count page-count :pages_analyzed end
              :pages_truncated (> page-count end)
              :max_segments_per_page max-seg
              :pages pages})))))
    (catch Exception e
      (json/generate-string {:error (or (.getMessage e) "line drawing analysis failed")
                             :detail (str e)}))))

;; --- raster tools: read_png_image / ocr_image / analyze_image_shapes /
;;     draw_overlay_png --------------------------------------------------------

(defn- deep-keywordize
  "Recursively convert string map keys to keywords (nested MCP JSON args)."
  [x]
  (cond
    (map? x) (into {} (map (fn [[k v]]
                             [(if (string? k) (keyword k) k) (deep-keywordize v)])
                           x))
    (vector? x) (mapv deep-keywordize x)
    (seq? x) (map deep-keywordize x)
    :else x))

(defn- raster-kind-check
  "Returns an error JSON string if the file is not a decodable raster, else nil."
  [^File f path-str]
  (cond
    (str/blank? path-str) (json/generate-string {:error "path is required"})
    (not (.exists f))     (json/generate-string {:error "file not found" :path path-str})
    (not (.isFile f))     (json/generate-string {:error "not a regular file" :path path-str})
    (= :unknown (img/file-kind-raster-or-pdf (.getName f)))
    (json/generate-string {:error "path must be .png, .jpg, or .jpeg" :path path-str})
    :else nil))

(defn run-read-png-image!
  "Decode a PNG/JPG and return metadata (dimensions, colorspace, alpha) and
  optionally dominant-color statistics."
  [arguments]
  (try
    (let [m (parse-args arguments)
          path-str (fnum m)
          want-stats (img/json-bool (or (:color_stats m) (get m "color_stats")) false)
          max-colors (let [x (or (:max_colors m) (get m "max_colors"))]
                       (cond (number? x) (min 24 (max 1 (long x))) :else 8))
          ^File f (as-file path-str)]
      (if-let [err (raster-kind-check f path-str)]
        err
        (let [^BufferedImage img (img/load-raster-image! f)
              meta (img/image-metadata img f)]
          (json/generate-string
           (cond-> {:ok true
                    :format "image_metadata"
                    :path path-str
                    :width (:width meta)
                    :height (:height meta)
                    :pixels (:pixels meta)
                    :has_alpha (:has_alpha meta)
                    :components (:components meta)
                    :bits_per_pixel (:bits_per_pixel meta)
                    :buffered_type (:buffered_type meta)
                    :bytes (:bytes meta)}
             want-stats (assoc :dominant_colors (img/dominant-colors img (long max-colors))))))))
    (catch Exception e
      (json/generate-string {:error (or (.getMessage e) "image read failed")
                             :detail (str e)}))))

(defn run-ocr-image!
  "OCR a PNG/JPG at `path`. Optional `with_boxes` returns per-word bounding
  boxes + confidence for relating text to geometry."
  [arguments]
  (try
    (let [m (parse-args arguments)
          path-str (fnum m)
          dpi (let [x (or (:dpi m) (get m "dpi"))]
                (cond (number? x) (min img/max-pdf-raster-dpi (max img/min-ocr-dpi (long x)))
                      :else img/default-ocr-dpi))
          lang-out (or (some-> (:language m) str str/trim not-empty)
                       (some-> (get m "language") str str/trim not-empty)
                       "eng")
          psm (img/parse-ocr-psm m)
          preprocess? (img/json-bool (or (:preprocess m) (get m "preprocess")) true)
          with-boxes (img/json-bool (or (:with_boxes m) (get m "with_boxes")) false)
          ^File f (as-file path-str)
          datapath (img/tessdata-path-or-nil)]
      (cond
        (str/blank? datapath)
        (json/generate-string {:error "Tesseract tessdata not found"
                               :path path-str
                               :hint (str "Install tesseract-ocr + language packs, or set TESSDATA_PREFIX "
                                          "so a tessdata dir contains *.traineddata files.")})

        :else
        (if-let [err (raster-kind-check f path-str)]
          err
          (let [^BufferedImage rgb (img/load-raster-image! f)
                {:keys [text words]} (img/ocr-image! rgb datapath lang-out psm dpi
                                                      preprocess? with-boxes)
                ^String t text
                text-truncated (> (count t) img/pdf-max-text-chars)
                t-out (if text-truncated (subs t 0 (min (count t) img/pdf-max-text-chars)) t)]
            (json/generate-string
             (cond-> {:format "image_ocr"
                      :path path-str
                      :dpi dpi
                      :language lang-out
                      :page_seg_mode psm
                      :preprocess preprocess?
                      :ocr_engine "lstm"
                      :text_truncated text-truncated
                      :text_char_limit (when text-truncated img/pdf-max-text-chars)
                      :text t-out}
               with-boxes (assoc :words words)))))))
    (catch Exception e
      (json/generate-string {:error (or (.getMessage e) "image OCR failed")
                             :detail (str e)}))))

(defn run-analyze-image-shapes!
  "BoofCV geometry on a PNG/JPG: lines (RANSAC), rectangles (polygon
  detector), ellipses, blobs (threshold + contours), and heuristic arrow
  candidates. All geometry in source-image pixel coordinates."
  [arguments]
  (try
    (let [m (parse-args arguments)
          path-str (fnum m)
          m' (fn [k d] (let [x (or (get m k) (get m (name k)))]
                         (cond (nil? x) d
                               (number? x) (long x)
                               (and (string? x) (not (str/blank? x)))
                               (try (Long/parseLong (str/trim x)) (catch NumberFormatException _ d))
                               :else d)))
          opts {:region_size (m' :region_size nil)
                :max_lines (m' :max_lines 400)
                :max_rectangles (m' :max_rectangles img/shape-max-items)
                :max_ellipses (m' :max_ellipses img/shape-max-items)
                :max_blobs (m' :max_blobs img/blob-max-items)
                :max_arrows (m' :max_arrows img/arrow-max-items)
                :min_blob_area (let [x (or (:min_blob_area m) (get m "min_blob_area"))]
                                 (cond (nil? x) nil
                                       (number? x) (double x)
                                       (and (string? x) (not (str/blank? x)))
                                       (try (Double/parseDouble (str/trim x)) (catch NumberFormatException _ nil))
                                       :else nil))}
          ^File f (as-file path-str)]
      (if-let [err (raster-kind-check f path-str)]
        err
        (let [^BufferedImage rgb (img/load-raster-image! f)
              {:keys [width_px height_px lines rectangles ellipses blobs arrows]}
              (img/analyze-image-shapes! rgb opts)]
          (json/generate-string
           {:format "image_shapes"
            :library "BoofCV"
            :path path-str
            :width_px width_px
            :height_px height_px
            :lines lines
            :rectangles rectangles
            :ellipses ellipses
            :blobs blobs
            :arrows arrows
            :arrow_hint "arrow detection is heuristic — check :confidence"}))))
    (catch Exception e
      (json/generate-string {:error (or (.getMessage e) "image shape analysis failed")
                             :detail (str e)}))))

(defn run-draw-overlay-png!
  "Draw overlay geometry (rectangles/lines/ellipses/text_boxes/blobs/arrows)
  onto a raster source and write the annotated PNG to `out_path`."
  [arguments]
  (try
    (let [m (parse-args arguments)
          src (or (some-> (:source_path m) str str/trim not-empty)
                  (some-> (get m "source_path") str str/trim not-empty))
          out (or (some-> (:out_path m) str str/trim not-empty)
                  (some-> (get m "out_path") str str/trim not-empty))
          overlays (deep-keywordize (or (:overlays m) (get m "overlays")))
          ^File src-f (as-file src)]
      (cond
        (or (str/blank? src) (str/blank? out))
        (json/generate-string {:error "source_path and out_path are required"})

        (not (img/png-extension? out))
        (json/generate-string {:error "out_path must end with .png" :out_path out})

        :else
        (if-let [err (raster-kind-check src-f src)]
          err
          (let [^BufferedImage base (img/load-raster-image! src-f)
                out-path (img/save-png! (img/draw-overlay! base (or overlays {}))
                                        (as-file out))]
            (json/generate-string {:format "image_overlay"
                                   :source_path src
                                   :out_path out-path
                                   :ok true
                                   :width (.getWidth base)
                                   :height (.getHeight base)})))))
    (catch Exception e
      (json/generate-string {:error (or (.getMessage e) "overlay draw failed")
                             :detail (str e)}))))
