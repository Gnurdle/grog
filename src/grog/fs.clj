(ns grog.fs
  "Repo-root file tools: read/write text, Office, PDF, OCR."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.config :as cfg])
  (:import [java.awt Graphics2D RenderingHints]
           [java.awt.image BufferedImage RescaleOp]
           [java.io File FileInputStream]
           [net.sourceforge.tess4j ITessAPI$TessOcrEngineMode ITessAPI$TessPageSegMode
            Tesseract TesseractException]
           [org.apache.pdfbox Loader]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.rendering ImageType PDFRenderer]
           [org.apache.pdfbox.text PDFTextStripper]
           [org.apache.poi.ss.usermodel DataFormatter WorkbookFactory]
           [org.apache.poi.xwpf.usermodel XWPFDocument XWPFParagraph
            XWPFTable XWPFTableCell XWPFTableRow]))

(defn- resolve-file!
  "Returns `File` for `path` as given: absolute as-is, or relative to the repo root."
  ^File [^String path]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      (.getCanonicalFile f)
      (.getCanonicalFile (io/file (cfg/repo-root) path)))))

(defn resolve-repo-path!
  "Canonical `java.io.File` for `path` (absolute or repo-root-relative)."
  ^File [path]
  (resolve-file! (str path)))

(defn- parse-args-map [arguments]
  (cond (map? arguments) arguments
        (string? arguments) (try (json/parse-string arguments true)
                                  (catch Exception _ {}))
        :else {}))

(def max-pdf-raster-dpi
  "Upper bound on PDF rasterization DPI for `ocr_pdf_document` and
  `analyze_pdf_line_drawings`. Use the same dpi when pairing OCR with line geometry or crops."
  1200)

(def ^:private default-ocr-dpi 300)
(def ^:private min-ocr-dpi 120)

(def ^:private default-office-max-chars (* 512 1024))         ; 512 KiB default text cap per call
(def ^:private office-max-chars-cap (* 4 1024 1024))          ; 4 MiB upper limit
(def ^:private office-max-elements-cap 10000)
(def ^:private default-office-max-rows 500)                   ; Excel rows per sheet per call
(def ^:private office-max-rows-cap 100000)

(defn read-office-document-tool-spec []
  {:type "function"
   :function
   {:name "read_office_document"
    :description (str "Extract plain text and tables from a Word or Excel file (absolute or relative path to the file). "
                      "Supports .docx, .xlsx, .xls; other extensions are probed as spreadsheet then document. "
                      "Returns JSON: format, path, text (paragraphs), tables (rows as string arrays), plus paging metadata. "
                      "Each Word table is one entry; each Excel sheet is one table. "
                      "Large documents should be read in chunks: for Word use offset/limit on body elements "
                      "(paragraphs+tables) and follow next_offset; for Excel use sheet_index/start_row/row_limit. "
                      "text can be capped with max_chars; if text_truncated is true, raise the cap or narrow the window.")
    :parameters {:type "object"
                 :required ["path"]
                 :properties {:path {:type "string"
                                     :description "Path to the file (absolute or relative; any extension; file must be readable as Office)."}
                              :offset {:type "integer"
                                       :description "Word only: number of body elements (paragraphs+tables) to skip before reading. Default 0."}
                              :limit {:type "integer"
                                      :description (str "Word only: max body elements to read in this call. Default all remaining (up to "
                                                        office-max-elements-cap "). Use with offset to page forward.")}
                              :max_chars {:type "integer"
                                          :description (str "Cap on characters of extracted text returned (default " default-office-max-chars
                                                            ", max " office-max-chars-cap "). Not needed if paging by offset/limit.")}
                              :sheet_index {:type "integer"
                                            :description "Excel only: 0-based sheet to read. Omit to read all sheets."}
                              :start_row {:type "integer"
                                          :description "Excel only: 0-based first data row to read within each sheet. Default 0."}
                              :row_limit {:type "integer"
                                          :description (str "Excel only: max rows to read per sheet (default " default-office-max-rows
                                                            ", max " office-max-rows-cap ").")}}}}})

(defn read-pdf-document-tool-spec []
  {:type "function"
   :function
   {:name "read_pdf_document"
    :description (str "Extract plain text from a PDF file (absolute or relative path to the file; must be a valid PDF). "
                      "Returns JSON: format, path, page_count, pages_read, text, truncated flags. "
                      "Does not run OCR — for scanned/image-only PDFs use ocr_pdf_document after this returns little text.")
    :parameters {:type "object"
                 :required ["path"]
                 :properties {:path {:type "string"
                                     :description "Path to the file (absolute or relative; any extension if file is a valid PDF)."}
                              :max_pages {:type "integer"
                                          :description "Max pages to extract (default 100, cap 500)."}}}}})

(defn ocr-pdf-document-tool-spec []
  {:type "function"
   :function
   {:name "ocr_pdf_document"
    :description (str "OCR for scanned or image-only PDF files (absolute or relative path to the file; must be a valid PDF). "
                      "Uses high-DPI render, LSTM engine, grayscale+contrast preprocessing, and text cleanup for LLM parsing. "
                      "If quality is poor, raise dpi (try 400–800; up to " max-pdf-raster-dpi
                      " for very fine print or dense diagrams — high RAM and slow; reduce max_pages). "
                      "page_seg_mode: 3=auto, 4=single column, 6=single block (default), 11=sparse text. "
                      "For line art on the same raster, call analyze_pdf_line_drawings at the same dpi (BoofCV). Requires tessdata.")
    :parameters {:type "object"
                 :required ["path"]
                 :properties {:path {:type "string"
                                     :description "Path to the file (absolute or relative; any extension if file is a valid PDF)."}
                              :max_pages {:type "integer"
                                          :description "Max pages to OCR (default 30, cap 100)."}
                              :dpi {:type "integer"
                                    :description (str "Render DPI (default " default-ocr-dpi "; min "
                                                      min-ocr-dpi ", max " max-pdf-raster-dpi
                                                      "; higher = sharper OCR, slower, more memory).")}
                              :language {:type "string"
                                         :description "Tesseract language code(s), e.g. eng, deu, eng+deu (default eng)."}
                              :page_seg_mode {:type "integer"
                                              :description "Tesseract PSM 0–13 (default 6 = uniform text block). Try 3 (auto) or 4 (single column) for layout issues."}
                              :preprocess {:type "boolean"
                                           :description "Grayscale + contrast boost before OCR (default true). Set false only for unusual color-dependent scans."}}}}})

(def ^:private default-pdf-max-pages 100)
(def ^:private pdf-max-pages-cap 500)
(def ^:private pdf-max-file-bytes (* 100 1024 1024))
(def ^:private pdf-max-text-chars (* 2 1024 1024))

(def ^:private default-ocr-max-pages 30)
(def ^:private ocr-max-pages-cap 100)
(def ^:private ocr-psm-max 13)

(defn- tessdata-dir-has-lang? [^File d]
  (boolean
   (when (and (.exists d) (.isDirectory d))
     (when-let [fs (.listFiles d)]
       (some #(str/ends-with? (.getName ^File %) ".traineddata")
             (seq fs))))))

(defn- tessdata-candidate-dirs []
  (let [pfx (System/getenv "TESSDATA_PREFIX")]
    (remove nil?
            (concat
             (when-not (str/blank? pfx)
               [(io/file pfx "tessdata") (io/file pfx)])
             [(io/file "/usr/share/tessdata")
              (io/file "/usr/share/tesseract-ocr/5/tessdata")
              (io/file "/usr/share/tesseract-ocr/4.00/tessdata")
              (io/file "/opt/homebrew/share/tessdata")
              (io/file "/usr/local/share/tessdata")
              (when-let [^String pf (System/getenv "ProgramFiles")]
                (io/file pf "Tesseract-OCR" "tessdata"))
              (when-let [^String pf (System/getenv "ProgramFiles(x86)")]
                (io/file pf "Tesseract-OCR" "tessdata"))]))))

(defn tessdata-path-or-nil
  "Directory containing `*.traineddata` (e.g. eng.traineddata), or nil."
  []
  (some #(when (tessdata-dir-has-lang? %) (.getAbsolutePath ^File %))
        (tessdata-candidate-dirs)))

(defn- json-bool [v default]
  (cond (boolean? v) v
        (string? v) (case (str/lower-case (str/trim ^String v))
                      ("false" "0" "no") false
                      ("true" "1" "yes") true
                      default)
        (number? v) (not (zero? (long v)))
        :else default))

(defn- parse-ocr-psm [m]
  (let [x (or (:page_seg_mode m) (get m "page_seg_mode")
              (:pageSegMode m) (get m "pageSegMode"))]
    (cond (number? x) (max 0 (min ocr-psm-max (long x)))
          :else ITessAPI$TessPageSegMode/PSM_SINGLE_BLOCK)))

(defn- make-tesseract ^Tesseract [^String datapath ^String language ^long dpi ^long psm]
  (doto (Tesseract.)
    (.setDatapath datapath)
    (.setLanguage (or (some-> language str str/trim not-empty) "eng"))
    (.setOcrEngineMode ITessAPI$TessOcrEngineMode/OEM_LSTM_ONLY)
    (.setPageSegMode (int psm))
    (.setVariable "user_defined_dpi" (str dpi))
    (.setVariable "preserve_interword_spaces" "1")))

(defn- image-to-grayscale ^BufferedImage [^BufferedImage src]
  (let [w (.getWidth src)
        h (.getHeight src)
        ^BufferedImage dst (BufferedImage. w h BufferedImage/TYPE_BYTE_GRAY)
        ^Graphics2D g (.createGraphics dst)]
    (try
      (.drawImage g src 0 0 nil)
      dst
      (finally (.dispose g)))))

(defn- boost-grayscale-contrast ^BufferedImage [^BufferedImage gray]
  (let [^BufferedImage out (BufferedImage. (.getWidth gray) (.getHeight gray) (.getType gray))
        ;; Mild stretch helps faint scans without blowing highlights.
        ^RescaleOp op (RescaleOp. 1.28 6.0 nil)]
    (.filter op gray out)
    out))

(defn- preprocess-page-image ^BufferedImage [^BufferedImage rgb preprocess?]
  (if preprocess?
    (-> rgb image-to-grayscale boost-grayscale-contrast)
    rgb))

(defn- normalize-ocr-text-for-llm [^String raw]
  (if (str/blank? raw)
    ""
    (let [dehyphen (-> raw
                       (str/replace #"\r\n?" "\n")
                       ;; Line-ending hyphenation from column breaks
                       (str/replace #"-\n([a-zA-Z])" "$1"))
          lines (str/split-lines dehyphen)
          tidied (map (fn [ln]
                        (-> (str/trim ln)
                            (str/replace #"[ \t\f\v]{2,}" " ")))
                      lines)
          joined (str/join "\n" tidied)]
      (-> joined (str/replace #"\n{3,}" "\n\n") str/trim))))

(defn- ocr-pdf-page!
  [^Tesseract tess ^PDFRenderer renderer page-idx dpi preprocess?]
  (let [^BufferedImage rgb (.renderImageWithDPI renderer (int page-idx) (float dpi) ImageType/RGB)
        ^BufferedImage img (preprocess-page-image rgb preprocess?)]
    (.doOCR tess img)))

(defn- extract-pdf-ocr!
  [^File f max-pages dpi lang-out ^String datapath psm preprocess?]
  (with-open [^PDDocument doc (Loader/loadPDF f)]
    (when (.isEncrypted doc)
      (throw (ex-info "encrypted PDFs are not supported" {:path (.getPath f)})))
    (let [page-count (.getNumberOfPages doc)]
      (if (zero? page-count)
        {:page_count 0 :pages_read 0 :pages_truncated false :dpi dpi :language lang-out
         :page_seg_mode psm :preprocess preprocess? :text ""}
        (let [end (int (min page-count max-pages))
              tess (make-tesseract datapath lang-out (long dpi) (long psm))
              ^PDFRenderer renderer
              (doto (PDFRenderer. doc)
                (.setSubsamplingAllowed false)
                (.setRenderingHints
                 (doto (RenderingHints. RenderingHints/KEY_INTERPOLATION
                                        RenderingHints/VALUE_INTERPOLATION_BICUBIC)
                   (.put RenderingHints/KEY_RENDERING RenderingHints/VALUE_RENDER_QUALITY)
                   (.put RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
                   (.put RenderingHints/KEY_TEXT_ANTIALIASING
                         RenderingHints/VALUE_TEXT_ANTIALIAS_ON))))
              parts
              (mapv (fn [pidx]
                      (try
                        (str "\n\n--- page " (inc pidx) " ---\n\n"
                             (ocr-pdf-page! tess renderer pidx dpi preprocess?))
                        (catch TesseractException e
                          (str "\n\n--- page " (inc pidx) " ---\n[OCR error: "
                               (.getMessage e) "]\n"))))
                    (range 0 end))
              raw (str/join parts)]
          {:page_count page-count
           :pages_read end
           :pages_truncated (> page-count end)
           :dpi dpi
           :language lang-out
           :page_seg_mode psm
           :preprocess preprocess?
           :text (normalize-ocr-text-for-llm raw)})))))

(defn- extract-docx
  "Extract text/tables from a .docx, reading only `limit` body elements starting at element `offset`.
  Returns text, tables (each tagged with its element index), total element count, and count actually read."
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
                (.append text-buf t)
                (.append text-buf "\n")))
            (instance? XWPFTable el)
            (let [^XWPFTable tbl el
                  rows (for [^XWPFTableRow row (.getRows tbl)]
                         (vec (for [^XWPFTableCell cell (.getTableCells row)]
                                (str/trim (.getText cell)))))]
              (vswap! tables conj {:source "word_table"
                                   :element_index idx
                                   :rows (vec rows)}))
            :else nil))
        (vswap! read-count inc))
      {:text (str text-buf)
       :tables @tables
       :total_elements (long total)
       :elements_read (long @read-count)})))

(defn- extract-xlsx
  "Extract text/tables from an .xlsx/.xls. When `sheet-index` is set, reads only that sheet; otherwise all sheets.
  Reads rows from `start-row` up to `row-limit` per sheet. Returns text, tables, and sheet metadata."
  [^File f sheet-index start-row row-limit]
  (with-open [wb (WorkbookFactory/create f)]
    (let [fmt (DataFormatter.)
          total-sheets (.getNumberOfSheets wb)
          _ (when (and (some? sheet-index) (or (neg? sheet-index) (>= sheet-index total-sheets)))
              (throw (ex-info (str "sheet_index " sheet-index " out of range (0.." (dec total-sheets) ")")
                              {:total_sheets total-sheets})))
          idxs (if (nil? sheet-index) (range total-sheets) [(long sheet-index)])
          row-limit* (when row-limit (min (long row-limit) office-max-rows-cap))
          sheets
          (mapv
           (fn [si]
             (let [sh (.getSheetAt wb (int si))
                   name (.getSheetName sh)
                   last-row (.getLastRowNum sh)
                   start (long (or start-row 0))
                   end-row (cond (nil? row-limit*) last-row
                                 :else (min last-row (dec (+ start (long row-limit*)))))
                   rows
                   (vec
                    (for [r (range start (inc (max -1 end-row)))
                          :let [row (.getRow sh r)]
                          :when row]
                      (vec
                       (for [c (range (max 1 (long (.getLastCellNum row))))
                             :let [cell (.getCell row c)]]
                         (if cell (.formatCellValue fmt cell) "")))))]
               {:source "excel_sheet"
                :sheet name
                :sheet_index (long si)
                :first_row start
                :last_data_row (long last-row)
                :next_start_row (if (and end-row (< (inc end-row) (inc last-row)))
                                  (inc end-row)
                                  nil)
                :rows_read (count rows)
                :rows rows}))
           idxs)]
      {:text (str/join "\n\n"
                       (map (fn [{:keys [sheet rows]}]
                              (str "## " sheet "\n"
                                   (str/join "\n"
                                             (map #(str/join "\t" %) rows))))
                            sheets))
       :tables sheets
       :total_sheets (long total-sheets)})))

(defn- extract-office-sliced
  "Dispatch to the right extractor based on extension, passing Word/Excel slicing params.
  Returns a data map (not JSON): format, path, text, tables, plus counts."
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

(defn- extract-pdf [^File f ^long max-pages]
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
              text (.getText stripper doc)]
          {:page_count page-count
           :pages_read end-page
           :pages_truncated (> page-count end-page)
           :text text})))))

(defn run-read-office-document!
  [arguments]
  (try
    (let [m (parse-args-map arguments)
          path-str (or (some-> (:path m) str str/trim not-empty)
                       (some-> (get m "path") str str/trim not-empty))
          offset (let [x (or (:offset m) (get m "offset"))]
                   (cond (number? x) (max 0 (long x)) :else 0))
          limit (let [x (or (:limit m) (get m "limit"))]
                  (cond (number? x) (min office-max-elements-cap (max 1 (long x)))
                        :else nil))
          max-chars (let [x (or (:max_chars m) (get m "max_chars"))]
                      (cond (number? x) (min office-max-chars-cap (max 1 (long x)))
                            :else default-office-max-chars))
          sheet-index (let [x (or (:sheet_index m) (get m "sheet_index"))]
                        (cond (number? x) (long x) :else nil))
          start-row (let [x (or (:start_row m) (get m "start_row"))]
                      (cond (number? x) (max 0 (long x)) :else nil))
          row-limit (let [x (or (:row_limit m) (get m "row_limit"))]
                      (cond (number? x) (min office-max-rows-cap (max 1 (long x)))
                            :else nil))
          ^File f (resolve-file! path-str)]
      (cond
        (not (.exists f))
        (json/generate-string {:error "file not found" :path path-str})

        (not (.isFile f))
        (json/generate-string {:error "not a regular file" :path path-str})

        :else
        (try
          (let [{:keys [text elements_read] :as extracted}
                (extract-office-sliced f path-str offset limit sheet-index start-row row-limit)
                ^String t (or text "")
                total-chars (count t)
                text-truncated (> total-chars max-chars)
                t-out (if text-truncated (subs t 0 (min total-chars max-chars)) t)
                docx? (= (:format extracted) "docx")
                result (cond-> (assoc extracted
                                      :text t-out
                                      :text_truncated text-truncated
                                      :text_char_limit (when text-truncated max-chars)
                                      :total_chars total-chars)
                         docx? (assoc :offset offset
                                      :limit limit
                                      :next_offset (+ offset (long (or elements_read 0)))))]
            (json/generate-string result))
          (catch Exception e
            (json/generate-string {:error "not a readable Office document (.docx / .xlsx / .xls)"
                                   :path path-str
                                   :detail (.getMessage e)})))))
    (catch Exception e
      (json/generate-string {:error (or (.getMessage e) "office extract failed")
                             :detail (str e)}))))

(defn run-read-pdf-document!
  [arguments]
  (try
    (let [m (parse-args-map arguments)
          path-str (or (some-> (:path m) str str/trim not-empty)
                       (some-> (get m "path") str str/trim not-empty))
          max-pages (let [x (or (:max_pages m) (get m "max_pages"))]
                      (cond (number? x) (min pdf-max-pages-cap (max 1 (long x)))
                            :else default-pdf-max-pages))
          ^File f (resolve-file! path-str)]
      (cond
        (not (.exists f))
        (json/generate-string {:error "file not found" :path path-str})

        (not (.isFile f))
        (json/generate-string {:error "not a regular file" :path path-str})

        (> (.length f) pdf-max-file-bytes)
        (json/generate-string {:error "PDF too large"
                               :path path-str
                               :size_bytes (.length f)
                               :max_bytes pdf-max-file-bytes})

        :else
        (let [{:keys [page_count pages_read pages_truncated text]} (extract-pdf f max-pages)
              ^String t text
              text-truncated (> (count t) pdf-max-text-chars)
              t-out (if text-truncated
                      (subs t 0 (min (count t) pdf-max-text-chars))
                      t)]
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

(defn run-ocr-pdf-document!
  [arguments]
  (try
    (let [m (parse-args-map arguments)
          path-str (or (some-> (:path m) str str/trim not-empty)
                       (some-> (get m "path") str str/trim not-empty))
          max-pages (let [x (or (:max_pages m) (get m "max_pages"))]
                      (cond (number? x) (min ocr-max-pages-cap (max 1 (long x)))
                            :else default-ocr-max-pages))
          dpi (let [x (or (:dpi m) (get m "dpi"))]
                (cond (number? x) (min max-pdf-raster-dpi (max min-ocr-dpi (long x)))
                      :else default-ocr-dpi))
          lang-out (or (some-> (:language m) str str/trim not-empty)
                       (some-> (get m "language") str str/trim not-empty)
                       "eng")
          psm (parse-ocr-psm m)
          preprocess? (json-bool (or (:preprocess m) (get m "preprocess")) true)
          ^File f (resolve-file! path-str)
          datapath (tessdata-path-or-nil)]
      (cond
        (str/blank? datapath)
        (json/generate-string {:error "Tesseract tessdata not found"
                               :path path-str
                               :hint (str "Install tesseract-ocr and language packs (e.g. tesseract-data-eng on Arch, "
                                          "tesseract-ocr-eng on Debian). Or set TESSDATA_PREFIX so a directory named "
                                          "tessdata (or the prefix itself) contains *.traineddata files.")})

        (not (.exists f))
        (json/generate-string {:error "file not found" :path path-str})

        (not (.isFile f))
        (json/generate-string {:error "not a regular file" :path path-str})

        (> (.length f) pdf-max-file-bytes)
        (json/generate-string {:error "PDF too large"
                               :path path-str
                               :size_bytes (.length f)
                               :max_bytes pdf-max-file-bytes})

        :else
        (let [{:keys [page_count pages_read pages_truncated dpi text language
                      page_seg_mode preprocess]}
              (extract-pdf-ocr! f max-pages dpi lang-out datapath psm preprocess?)
              ^String t text
              text-truncated (> (count t) pdf-max-text-chars)
              t-out (if text-truncated
                      (subs t 0 (min (count t) pdf-max-text-chars))
                      t)]
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
                                 :text_normalization "llm_cleanup_v1"
                                 :text_truncated text-truncated
                                 :text_char_limit (when text-truncated pdf-max-text-chars)
                                 :text t-out}))))
    (catch Exception e
      (json/generate-string {:error (or (.getMessage e) "pdf OCR failed")
                             :detail (str e)}))))

(defn tool-log-path
  "Path string for stderr logging, or nil."
  [arguments]
  (let [m (parse-args-map arguments)]
    (or (some-> (:path m) str str/trim not-empty)
        (some-> (get m "path") str str/trim not-empty))))

(defn startup-status-line []
  (str "File tools: Office extract, PDF text/OCR, BoofCV lines — "
       (if (tessdata-path-or-nil)
         "OCR: tessdata OK"
         "OCR: no tessdata (ocr_pdf_document needs Tesseract language data)")))
