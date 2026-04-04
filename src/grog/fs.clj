(ns grog.fs
  "Workspace-scoped file tools for Ollama: read/write text, Office, PDF, OCR."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.config :as cfg])
  (:import [java.awt Graphics2D RenderingHints]
           [java.awt.image BufferedImage RescaleOp]
           [java.io File FileInputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [net.sourceforge.tess4j ITessAPI$TessOcrEngineMode ITessAPI$TessPageSegMode
            Tesseract TesseractException]
           [org.apache.pdfbox Loader]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.rendering ImageType PDFRenderer]
           [org.apache.pdfbox.text PDFTextStripper]
           [org.apache.poi.ss.usermodel DataFormatter WorkbookFactory]
           [org.apache.poi.xwpf.usermodel IBodyElement XWPFDocument XWPFParagraph
            XWPFTable XWPFTableCell XWPFTableRow]
           (java.nio.file Path StandardOpenOption)))

(defn- workspace-root-path
  (^Path []
   (-> ^File (.getCanonicalFile (io/file (cfg/workspace-root)))
       .toPath
       .normalize
       .toAbsolutePath)))

(defn- resolve-file-under-workspace!
  "Returns canonical `File` for `path` (relative to workspace root or absolute). Throws if outside root."
  ^File [^String path]
  (when (str/blank? path)
    (throw (ex-info "path is empty" {:path path})))
  (let [f (io/file path)
        abs (.getCanonicalFile (if (.isAbsolute f)
                                 f
                                 (io/file (cfg/workspace-root) path)))
        p (-> abs .toPath .normalize .toAbsolutePath)
        root (workspace-root-path)]
    (when-not (.startsWith p root)
      (throw (ex-info "path escapes workspace :default-root"
                      {:path path :resolved (.getPath abs) :workspace (cfg/workspace-root)})))
    abs))

(defn resolve-workspace-path!
  "Canonical `java.io.File` for `path` under :workspace :default-root (same rules as file tools)."
  ^File [path]
  (resolve-file-under-workspace! (str path)))

(defn- parse-args-map [arguments]
  (cond (map? arguments) arguments
        (string? arguments) (try (json/parse-string arguments true)
                                  (catch Exception _ {}))
        :else {}))

(defn read-workspace-file-tool-spec []
  {:type "function"
   :function
   {:name "read_workspace_file"
    :description (str "Read a UTF-8 text file under the workspace (:workspace :default-root in grog.edn). "
                      "Use for source, config, markdown, logs, JSON, etc. "
                      "To create or overwrite text files use write_workspace_file. "
                      "For .pdf use read_pdf_document; for .docx or .xlsx use read_office_document.")
    :parameters {:type "object"
                 :required ["path"]
                 :properties {:path {:type "string"
                                    :description "Path relative to workspace root, or absolute if still under that root."}
                              :max_bytes {:type "integer"
                                          :description "Max bytes to read (default 524288). Capped at 2097152."}}}}})

(defn write-workspace-file-tool-spec []
  {:type "function"
   :function
   {:name "write_workspace_file"
    :description (str "Write UTF-8 text to a path under the workspace. Creates parent directories. "
                      "Overwrites existing files unless append is true. "
                      "For .docx/.xlsx/.pdf use appropriate tools, not raw writes. Max ~2 MiB per call.")
    :parameters {:type "object"
                 :required ["path" "content"]
                 :properties {:path {:type "string"
                                     :description "Relative to workspace root or absolute under that root."}
                              :content {:type "string"
                                        :description "Full file body as UTF-8 text (use \\n newlines)."}
                              :append {:type "boolean"
                                       :description "If true, append to existing file (creates file if missing). Default false (replace)."}}}}})

(defn read-office-document-tool-spec []
  {:type "function"
   :function
   {:name "read_office_document"
    :description (str "Extract plain text and tables from a .docx (Word) or .xlsx (Excel) file under the workspace. "
                         "Returns JSON: format, path, text (paragraphs), tables (rows as string arrays). "
                         "Each Word table is one entry; each Excel sheet is one table.")
    :parameters {:type "object"
                 :required ["path"]
                 :properties {:path {:type "string"
                                     :description "Path to .docx or .xlsx under workspace root."}}}}})

(defn read-pdf-document-tool-spec []
  {:type "function"
   :function
   {:name "read_pdf_document"
    :description (str "Extract plain text from a .pdf file under the workspace (page order). "
                      "Returns JSON: format, path, page_count, pages_read, text, truncated flags. "
                      "Does not run OCR — for scanned/image-only PDFs use ocr_pdf_document after this returns little text.")
    :parameters {:type "object"
                 :required ["path"]
                 :properties {:path {:type "string"
                                     :description "Path to .pdf under workspace root."}
                              :max_pages {:type "integer"
                                          :description "Max pages to extract (default 100, cap 500)."}}}}})

(defn ocr-pdf-document-tool-spec []
  {:type "function"
   :function
   {:name "ocr_pdf_document"
    :description (str "OCR for .pdf files that are scanned or image-only (no selectable text). "
                      "Uses high-DPI render, LSTM engine, grayscale+contrast preprocessing, and text cleanup for LLM parsing. "
                      "If quality is poor, raise dpi (e.g. 400) or set page_seg_mode: 3=auto, 4=single column, 6=single block (default), "
                      "11=sparse text. For diagrams/line art on the same raster, also call analyze_pdf_line_drawings (BoofCV). Requires tessdata.")
    :parameters {:type "object"
                 :required ["path"]
                 :properties {:path {:type "string"
                                     :description "Path to .pdf under workspace root."}
                              :max_pages {:type "integer"
                                          :description "Max pages to OCR (default 30, cap 100)."}
                              :dpi {:type "integer"
                                    :description "Render DPI (default 300; higher = sharper OCR, slower; 120–400)."}
                              :language {:type "string"
                                         :description "Tesseract language code(s), e.g. eng, deu, eng+deu (default eng)."}
                              :page_seg_mode {:type "integer"
                                              :description "Tesseract PSM 0–13 (default 6 = uniform text block). Try 3 (auto) or 4 (single column) for layout issues."}
                              :preprocess {:type "boolean"
                                           :description "Grayscale + contrast boost before OCR (default true). Set false only for unusual color-dependent scans."}}}}})

(def ^:private default-text-max 524288)
(def ^:private text-max-cap 2097152)

(defn- office-extension? [^String path]
  (let [l (str/lower-case path)]
    (or (str/ends-with? l ".docx")
        (str/ends-with? l ".xlsx"))))

(defn- pdf-extension? [^String path]
  (str/ends-with? (str/lower-case path) ".pdf"))

(def ^:private default-pdf-max-pages 100)
(def ^:private pdf-max-pages-cap 500)
(def ^:private pdf-max-file-bytes (* 100 1024 1024))
(def ^:private pdf-max-text-chars (* 2 1024 1024))

(def ^:private default-ocr-max-pages 30)
(def ^:private ocr-max-pages-cap 100)
(def ^:private default-ocr-dpi 300)
(def ^:private min-ocr-dpi 120)
(def ^:private max-ocr-dpi 400)
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

(defn run-read-workspace-file!
  [arguments]
  (try
    (let [m (parse-args-map arguments)
          path-str (or (some-> (:path m) str str/trim not-empty)
                       (some-> (get m "path") str str/trim not-empty))
          max-bytes (let [x (or (:max_bytes m) (get m "max_bytes"))]
                      (cond (number? x) (min text-max-cap (max 1 (long x)))
                            :else default-text-max))
          ^File f (resolve-file-under-workspace! path-str)]
      (cond
        (not (.exists f))
        (json/generate-string {:error "file not found" :path path-str})

        (not (.isFile f))
        (json/generate-string {:error "not a regular file" :path path-str})

        (office-extension? path-str)
        (json/generate-string {:error "use read_office_document for .docx and .xlsx"
                               :path path-str})

        (pdf-extension? path-str)
        (json/generate-string {:error "use read_pdf_document for .pdf"
                               :path path-str})

        (> (.length f) max-bytes)
        (json/generate-string {:error "file too large for read_workspace_file"
                               :path path-str
                               :size_bytes (.length f)
                               :max_bytes max-bytes})

        :else
        (let [^bytes bs (Files/readAllBytes (.toPath f))
              content (try (String. bs StandardCharsets/UTF_8)
                           (catch Exception e
                             (throw (ex-info "not valid UTF-8 (binary file?)"
                                             {:path path-str :cause (.getMessage e)}))))]
          (json/generate-string {:type "text"
                                 :path path-str
                                 :size_bytes (alength bs)
                                 :content content}))))
    (catch Exception e
      (json/generate-string {:error (or (.getMessage e) "read failed")
                             :detail (str e)}))))

(defn- coerce-write-content [v]
  (cond (nil? v) ""
        (string? v) v
        :else (json/generate-string v)))

(defn run-write-workspace-file!
  [arguments]
  (try
    (let [m (parse-args-map arguments)
          path-str (or (some-> (:path m) str str/trim not-empty)
                       (some-> (get m "path") str str/trim not-empty))
          content (coerce-write-content (or (:content m) (get m "content")))
          append? (json-bool (or (:append m) (get m "append")) false)
          ^File f (resolve-file-under-workspace! path-str)
          ^bytes bs (.getBytes ^String content StandardCharsets/UTF_8)]
      (cond
        (str/blank? path-str)
        (json/generate-string {:error "path is required"})

        (and (.exists f) (.isDirectory f))
        (json/generate-string {:error "path is a directory" :path path-str})

        (> (alength bs) text-max-cap)
        (json/generate-string {:error "content too large"
                               :path path-str
                               :bytes (alength bs)
                               :max_bytes text-max-cap})

        :else
        (let [^File parent (.getParentFile f)]
          (when parent
            (.mkdirs parent))
          (if append?
            (Files/write (.toPath f) bs
                         (into-array StandardOpenOption
                                     [StandardOpenOption/CREATE
                                      StandardOpenOption/APPEND]))
            (Files/write (.toPath f) bs
                         (into-array StandardOpenOption
                                     [StandardOpenOption/CREATE
                                      StandardOpenOption/WRITE
                                      StandardOpenOption/TRUNCATE_EXISTING])))
          (json/generate-string {:ok true
                                 :path path-str
                                 :bytes_written (alength bs)
                                 :mode (if append? "append" "write")
                                 :exists_after true}))))
    (catch Exception e
      (json/generate-string {:error (or (.getMessage e) "write failed")
                             :detail (str e)}))))

(defn- extract-docx [^File f]
  (with-open [in (FileInputStream. f)
              doc (XWPFDocument. in)]
    (let [text-buf (StringBuilder.)
          tables (volatile! [])]
      (doseq [^IBodyElement el (.getBodyElements doc)]
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
            (vswap! tables conj {:source "word_table" :rows (vec rows)}))
          :else nil))
      {:text (str text-buf)
       :tables @tables})))

(defn- extract-xlsx [^File f]
  (with-open [wb (WorkbookFactory/create f)]
    (let [fmt (DataFormatter.)
          sheets
          (mapv
           (fn [si]
             (let [sh (.getSheetAt wb si)
                   name (.getSheetName sh)
                   last-row (.getLastRowNum sh)
                   rows
                   (vec
                    (for [r (range (inc (max -1 last-row)))
                          :let [row (.getRow sh r)]
                          :when row]
                      (vec
                       (for [c (range (max 1 (long (.getLastCellNum row))))
                             :let [cell (.getCell row c)]]
                         (if cell (.formatCellValue fmt cell) "")))))]
               {:source "excel_sheet" :sheet name :rows rows}))
           (range (.getNumberOfSheets wb)))]
      {:text (str/join "\n\n"
                       (map (fn [{:keys [sheet rows]}]
                              (str "## " sheet "\n"
                                   (str/join "\n"
                                             (map #(str/join "\t" %) rows))))
                            sheets))
       :tables sheets})))

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
          ^File f (resolve-file-under-workspace! path-str)]
      (cond
        (not (.exists f))
        (json/generate-string {:error "file not found" :path path-str})

        (not (.isFile f))
        (json/generate-string {:error "not a regular file" :path path-str})

        (pdf-extension? path-str)
        (json/generate-string {:error "use read_pdf_document for .pdf" :path path-str})

        (not (office-extension? path-str))
        (json/generate-string {:error "only .docx and .xlsx are supported" :path path-str})

        :else
        (let [ext (some-> path-str str/lower-case (str/split #"\.") last)]
          (case ext
            "docx"
            (let [{:keys [text tables]} (extract-docx f)]
              (json/generate-string {:format "docx"
                                     :path path-str
                                     :text text
                                     :tables tables}))
            "xlsx"
            (let [{:keys [text tables]} (extract-xlsx f)]
              (json/generate-string {:format "xlsx"
                                     :path path-str
                                     :text text
                                     :tables tables}))
            (json/generate-string {:error "unhandled office extension" :path path-str})))))
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
          ^File f (resolve-file-under-workspace! path-str)]
      (cond
        (not (.exists f))
        (json/generate-string {:error "file not found" :path path-str})

        (not (.isFile f))
        (json/generate-string {:error "not a regular file" :path path-str})

        (not (pdf-extension? path-str))
        (json/generate-string {:error "only .pdf is supported" :path path-str})

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
                (cond (number? x) (min max-ocr-dpi (max min-ocr-dpi (long x)))
                      :else default-ocr-dpi))
          lang-out (or (some-> (:language m) str str/trim not-empty)
                       (some-> (get m "language") str str/trim not-empty)
                       "eng")
          psm (parse-ocr-psm m)
          preprocess? (json-bool (or (:preprocess m) (get m "preprocess")) true)
          ^File f (resolve-file-under-workspace! path-str)
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

        (not (pdf-extension? path-str))
        (json/generate-string {:error "only .pdf is supported" :path path-str})

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
  (str "Workspace file tools: read_workspace_file + write_workspace_file (UTF-8), read_office_document (.docx/.xlsx), "
       "read_pdf_document (.pdf text), ocr_pdf_document (Tesseract OCR), analyze_pdf_line_drawings (BoofCV lines) — "
       ":workspace :default-root " (pr-str (cfg/workspace-root))
       " — "
       (if (tessdata-path-or-nil)
         "OCR: tessdata OK"
         "OCR: no tessdata (ocr_pdf_document needs Tesseract language data)")))
