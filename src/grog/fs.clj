(ns grog.fs
  "Workspace-scoped file tools for Ollama: read/write text, Office, PDF, OCR."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.config :as cfg]
            [grog.workspace-paths :as wsp])
  (:import [java.awt Graphics2D RenderingHints]
           [java.awt.image BufferedImage RescaleOp]
           [java.io File FileInputStream]
           [javax.imageio ImageIO]
           [java.nio.charset CodingErrorAction StandardCharsets]
           [java.nio.file Files]
           [java.util Base64]
           [net.sourceforge.tess4j ITessAPI$TessOcrEngineMode ITessAPI$TessPageSegMode
            Tesseract TesseractException]
           [org.apache.pdfbox Loader]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.rendering ImageType PDFRenderer]
           [org.apache.pdfbox.text PDFTextStripper]
           [org.apache.poi.ss.usermodel DataFormatter WorkbookFactory]
           [org.apache.poi.xwpf.usermodel IBodyElement XWPFDocument XWPFParagraph
            XWPFTable XWPFTableCell XWPFTableRow]
           (java.nio.file StandardOpenOption)))

(defn- resolve-file-under-workspace!
  "Returns `File` for `path` under workspace (symlinks allowed in `path`; I/O follows them).
  Throws on `..` escape or absolute path outside the workspace."
  ^File [^String path]
  (wsp/resolve-under-workspace! path))

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
    :description (str "Read any regular file under the workspace (any path at or below :workspace :default-root). "
                      "Valid UTF-8 is returned as text; otherwise bytes are returned as Base64 (`:type` binary). "
                      "For structured PDF/Office extraction prefer read_pdf_document / read_office_document / ocr_pdf_document. "
                      "To write text use write_workspace_file; PNG binary use write_workspace_png.")
    :parameters {:type "object"
                 :required ["path"]
                 :properties {:path {:type "string"
                                    :description "Path relative to workspace root, or absolute if still under that root."}
                              :max_bytes {:type "integer"
                                          :description "Max bytes to read (default 524288). Capped at 2097152."}}}}})

(def ^:private default-dir-max-entries 2000)
(def ^:private dir-max-entries-cap 10000)

(defn read-workspace-dir-tool-spec []
  {:type "function"
   :function
   {:name "read_workspace_dir"
    :description (str "List files and immediate subdirectories under a path inside the workspace (non-recursive, like ls). "
                      "Use path \".\" or the workspace root name to list the project root. "
                      "Returns JSON with :entries [{:name :type \"file\"|\"directory\" :size_bytes …}]; optional :max_entries caps count (default 2000, max 10000).")
    :parameters {:type "object"
                 :required ["path"]
                 :properties {:path {:type "string"
                                    :description "Directory relative to workspace root, or absolute under root; use \".\" for root."}
                              :max_entries {:type "integer"
                                            :description "Max entries to return (default 2000, cap 10000)."}}}}})

(defn write-workspace-file-tool-spec []
  {:type "function"
   :function
   {:name "write_workspace_file"
    :description (str "Write UTF-8 text to a path under the workspace. Creates parent directories. "
                      "Overwrites existing files unless append is true. "
                      "For PNG images use write_workspace_png (base64), not this tool. "
                      "For .docx/.xlsx/.pdf use appropriate tools, not raw writes. Max ~2 MiB per call.")
    :parameters {:type "object"
                 :required ["path" "content"]
                 :properties {:path {:type "string"
                                     :description "Relative to workspace root or absolute under that root."}
                              :content {:type "string"
                                        :description "Full file body as UTF-8 text (use \\n newlines)."}
                              :append {:type "boolean"
                                       :description "If true, append to existing file (creates file if missing). Default false (replace)."}}}}})

(defn write-workspace-png-tool-spec []
  {:type "function"
   :function
   {:name "write_workspace_png"
    :description (str "Write a binary PNG image under the workspace. "
                       "Pass `png_base64`: standard Base64 (RFC 4648) or a data URL `data:image/png;base64,...`. "
                       "Decoded file must begin with a valid PNG signature. "
                       "Path must end in .png (case-insensitive). Max ~15 MiB decoded.")
    :parameters {:type "object"
                 :required ["path" "png_base64"]
                 :properties {:path {:type "string"
                                    :description "Relative to workspace root or absolute under root; must end with .png"}
                              :png_base64 {:type "string"
                                           :description (str "PNG bytes as standard Base64 or data:image/png;base64,.... "
                                                               "Some models emit camelCase pngBase64 — same value.")}}}}})

(def ^:private crop-max-edge 4096)
(def ^:private crop-max-pad 256)
(def ^:private crop-default-pdf-dpi 220)

(defn crop-workspace-image-tool-spec []
  {:type "function"
   :function
   {:name "crop_workspace_image"
    :description (str "Crop a rectangular region from an image or a single PDF page and save as PNG under the workspace. "
                      "Pixel coordinates are top-left origin (same convention as analyze_pdf_line_drawings segment endpoints). "
                      "For PDF sources use the same :dpi as line analysis so crops align with detected segments. "
                      "Use after BoofCV line discovery to extract diagram/schematic regions, then reference the saved PNG or embed via write_workspace_png elsewhere.")
    :parameters {:type "object"
                 :required ["source_path" "out_path" "x" "y" "width" "height"]
                 :properties {:source_path {:type "string"
                                            :description "Workspace path to .png, .jpg, .jpeg, or .pdf"}
                              :out_path {:type "string"
                                         :description "Destination path; must end with .png (created under workspace)."}
                              :x {:type "integer" :description "Left edge of crop in pixels (≥ 0)."}
                              :y {:type "integer" :description "Top edge of crop in pixels (≥ 0)."}
                              :width {:type "integer" :description "Crop width in pixels (1–4096)."}
                              :height {:type "integer" :description "Crop height in pixels (1–4096)."}
                              :page {:type "integer"
                                     :description "1-based page index when source is PDF (required for .pdf)."}
                              :dpi {:type "integer"
                                    :description (str "Render DPI for PDF only (default " crop-default-pdf-dpi "; use same dpi as analyze_pdf_line_drawings).")}
                              :pad_px {:type "integer"
                                       :description "Optional uniform margin added around the box before clamping to image bounds (0–256)."}}}}})

(defn read-office-document-tool-spec []
  {:type "function"
   :function
   {:name "read_office_document"
    :description (str "Extract plain text and tables from a Word or Excel file under the workspace (any relative or absolute path under the workspace root). "
                         "Supports .docx, .xlsx, .xls; other extensions are probed as spreadsheet then document. "
                         "Returns JSON: format, path, text (paragraphs), tables (rows as string arrays). "
                         "Each Word table is one entry; each Excel sheet is one table.")
    :parameters {:type "object"
                 :required ["path"]
                 :properties {:path {:type "string"
                                     :description "Path under workspace root (any extension; file must be readable as Office)."}}}}})

(defn read-pdf-document-tool-spec []
  {:type "function"
   :function
   {:name "read_pdf_document"
    :description (str "Extract plain text from a PDF file under the workspace (any path under root; file must be a valid PDF). "
                      "Returns JSON: format, path, page_count, pages_read, text, truncated flags. "
                      "Does not run OCR — for scanned/image-only PDFs use ocr_pdf_document after this returns little text.")
    :parameters {:type "object"
                 :required ["path"]
                 :properties {:path {:type "string"
                                     :description "Path under workspace root (any extension if file is a valid PDF)."}
                              :max_pages {:type "integer"
                                          :description "Max pages to extract (default 100, cap 500)."}}}}})

(defn ocr-pdf-document-tool-spec []
  {:type "function"
   :function
   {:name "ocr_pdf_document"
    :description (str "OCR for PDF files under the workspace that are scanned or image-only (any path under root; must be a valid PDF). "
                      "Uses high-DPI render, LSTM engine, grayscale+contrast preprocessing, and text cleanup for LLM parsing. "
                      "If quality is poor, raise dpi (e.g. 400) or set page_seg_mode: 3=auto, 4=single column, 6=single block (default), "
                      "11=sparse text. For diagrams/line art on the same raster, also call analyze_pdf_line_drawings (BoofCV). Requires tessdata.")
    :parameters {:type "object"
                 :required ["path"]
                 :properties {:path {:type "string"
                                     :description "Path under workspace root (any extension if file is a valid PDF)."}
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

(def ^:private png-max-decoded-bytes (* 15 1024 1024))

(defn- png-extension? [^String path]
  (str/ends-with? (str/lower-case path) ".png"))

(defn- png-magic-matches? [^bytes bs]
  (when (>= (alength bs) 8)
    (let [expect [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A]]
      (every? true? (map (fn [i e] (= (bit-and 0xff (aget ^bytes bs i)) e))
                          (range 8)
                          expect)))))

(defn- strip-data-url-to-base64-payload [^String s]
  (let [t (str/trim s)]
    (if (str/starts-with? (str/lower-case t) "data:")
      (if-let [i (str/index-of t ",")]
        (subs t (inc i))
        (throw (ex-info "data URL has no comma separator" {})))
      t)))

(defn- decode-png-base64-bytes! [^String raw]
  (let [payload (strip-data-url-to-base64-payload raw)
        ^bytes bs (try (.decode (Base64/getMimeDecoder) payload)
                       (catch IllegalArgumentException e
                         (throw (ex-info (str "invalid base64: " (.getMessage e)) {}))))]
    (when (> (alength bs) png-max-decoded-bytes)
      (throw (ex-info "PNG exceeds max decoded size"
                      {:bytes (alength bs) :max_bytes png-max-decoded-bytes})))
    (when-not (png-magic-matches? bs)
      (throw (ex-info "decoded data is not a PNG (missing PNG file signature)" {})))
    bs))

(defn- bytes-valid-utf-8? [^bytes bs]
  (try
    (let [dec (doto (.newDecoder StandardCharsets/UTF_8)
                (.onMalformedInput CodingErrorAction/REPORT)
                (.onUnmappableCharacter CodingErrorAction/REPORT))]
      (.decode dec (java.nio.ByteBuffer/wrap bs))
      true)
    (catch java.nio.charset.CharacterCodingException _
      false)))

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

(defn run-read-workspace-dir!
  [arguments]
  (try
    (let [m (parse-args-map arguments)
          path-raw (or (:path m) (get m "path") ".")
          path-str (if (string? path-raw)
                     (str/trim path-raw)
                     (str path-raw))
          path-for-resolve (if (str/blank? path-str) "." path-str)
          max-ent (let [x (or (:max_entries m) (get m "max_entries")
                              (:maxEntries m) (get m "maxEntries"))]
                    (cond (number? x) (min dir-max-entries-cap (max 1 (long x)))
                          :else default-dir-max-entries))
          ^File d (resolve-file-under-workspace! path-for-resolve)]
      (cond
        (not (.exists d))
        (json/generate-string {:error "path not found" :path path-for-resolve})

        (not (.isDirectory d))
        (json/generate-string {:error "not a directory" :path path-for-resolve})

        :else
        (let [files (->> (.listFiles d)
                         (filter some?)
                         (sort-by #(str/lower-case (.getName ^File %)))
                         vec)
              n (count files)
              truncated (> n max-ent)
              slice (vec (take max-ent files))]
          (json/generate-string
           {:format "directory_listing"
            :path path-for-resolve
            :resolved (.getPath d)
            :entry_count n
            :entries_returned (count slice)
            :truncated truncated
            :entries
            (mapv (fn [^File f]
                    (let [nm (.getName f)]
                      (if (.isDirectory f)
                        {:name nm :type "directory"}
                        {:name nm :type "file" :size_bytes (.length f)})))
                  slice)}))))
    (catch Exception e
      (json/generate-string {:error (or (.getMessage e) "directory listing failed")
                             :detail (str e)}))))

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

        (> (.length f) max-bytes)
        (json/generate-string {:error "file too large for read_workspace_file"
                               :path path-str
                               :size_bytes (.length f)
                               :max_bytes max-bytes})

        :else
        (let [^bytes bs (Files/readAllBytes (.toPath f))]
          (if (bytes-valid-utf-8? bs)
            (json/generate-string {:type "text"
                                   :path path-str
                                   :size_bytes (alength bs)
                                   :content (String. bs StandardCharsets/UTF_8)})
            (json/generate-string {:type "binary"
                                   :path path-str
                                   :size_bytes (alength bs)
                                   :encoding "base64"
                                   :note "Not valid UTF-8; content is Base64."
                                   :content (.encodeToString (Base64/getEncoder) bs)})))))
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

(defn run-write-workspace-png!
  [arguments]
  (try
    (let [m (parse-args-map arguments)
          path-str (or (some-> (:path m) str str/trim not-empty)
                       (some-> (get m "path") str str/trim not-empty))
          b64 (or (some-> (:png_base64 m) str not-empty)
                  (some-> (get m "png_base64") str not-empty)
                  (some-> (:pngBase64 m) str not-empty)
                  (some-> (get m "pngBase64") str not-empty))]
      (cond
        (str/blank? path-str)
        (json/generate-string {:error "path is required"})

        (str/blank? b64)
        (json/generate-string {:error "png_base64 is required"})

        (not (png-extension? path-str))
        (json/generate-string {:error "path must end with .png" :path path-str})

        :else
        (let [^bytes bs (decode-png-base64-bytes! b64)
              ^File f (resolve-file-under-workspace! path-str)]
          (if (and (.exists f) (.isDirectory f))
            (json/generate-string {:error "path is a directory" :path path-str})
            (let [^File parent (.getParentFile f)]
              (when parent
                (.mkdirs parent))
              (Files/write (.toPath f) bs
                           (into-array StandardOpenOption
                                       [StandardOpenOption/CREATE
                                        StandardOpenOption/WRITE
                                        StandardOpenOption/TRUNCATE_EXISTING]))
              (json/generate-string {:ok true
                                     :path path-str
                                     :format "png"
                                     :bytes_written (alength bs)}))))))
    (catch Exception e
      (json/generate-string {:error (or (.getMessage e) "PNG write failed")
                             :detail (str e)}))))

(defn- pdf-renderer-for-crop ^PDFRenderer [^PDDocument doc]
  (doto (PDFRenderer. doc)
    (.setSubsamplingAllowed false)
    (.setRenderingHints
     (doto (RenderingHints. RenderingHints/KEY_INTERPOLATION
                            RenderingHints/VALUE_INTERPOLATION_BICUBIC)
       (.put RenderingHints/KEY_RENDERING RenderingHints/VALUE_RENDER_QUALITY)
       (.put RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
       (.put RenderingHints/KEY_TEXT_ANTIALIASING
             RenderingHints/VALUE_TEXT_ANTIALIAS_ON)))))

(defn- file-kind-raster-or-pdf [^String name]
  (let [n (str/lower-case name)]
    (cond (str/ends-with? n ".pdf") :pdf
          (or (str/ends-with? n ".png")
              (str/ends-with? n ".jpg")
              (str/ends-with? n ".jpeg")) :raster
          :else :unknown)))

(defn- get-long-opt [m kw & str-keys]
  (let [x (or (get m kw) (some #(get m %) str-keys))]
    (cond (number? x) (long x)
          (and (string? x) (not (str/blank? x)))
          (try (Long/parseLong (str/trim x))
               (catch NumberFormatException _ nil))
          :else nil)))

(defn- load-image-for-crop!
  ^BufferedImage [^File src-f kind page-1-based dpi]
  (case kind
    :raster
    (or (ImageIO/read src-f)
        (throw (ex-info "could not decode raster image" {:path (.getPath src-f)})))
    :pdf
    (do
      (when (> (.length src-f) pdf-max-file-bytes)
        (throw (ex-info "PDF too large" {:max_bytes pdf-max-file-bytes})))
      (with-open [^PDDocument doc (Loader/loadPDF src-f)]
        (when (.isEncrypted doc)
          (throw (ex-info "encrypted PDFs are not supported" {})))
        (let [pc (.getNumberOfPages doc)
              p (dec (long page-1-based))]
          (when (or (< p 0) (>= p pc))
            (throw (ex-info "page out of range" {:page page-1-based :page_count pc})))
          (let [^PDFRenderer r (pdf-renderer-for-crop doc)]
            (.renderImageWithDPI r (int p) (float dpi) ImageType/RGB)))))))

(defn run-crop-workspace-image!
  [arguments]
  (try
    (let [m (parse-args-map arguments)
          src (or (some-> (:source_path m) str str/trim not-empty)
                  (some-> (get m "source_path") str str/trim not-empty)
                  (some-> (:sourcePath m) str str/trim not-empty))
          out (or (some-> (:out_path m) str str/trim not-empty)
                  (some-> (get m "out_path") str str/trim not-empty)
                  (some-> (:outPath m) str str/trim not-empty))
          x (get-long-opt m :x "x")
          y (get-long-opt m :y "y")
          w (get-long-opt m :width "width")
          h (get-long-opt m :height "height")
          page (get-long-opt m :page "page")
          dpi (long (min 400 (max 72 (or (get-long-opt m :dpi "dpi") (long crop-default-pdf-dpi)))))
          pad-raw (or (get-long-opt m :pad_px "pad_px")
                      (get-long-opt m :padPx "padPx"))
          pad (if (nil? pad-raw) 0 (min crop-max-pad (max 0 (long pad-raw))))]
      (cond
        (or (str/blank? src) (str/blank? out))
        (json/generate-string {:error "source_path and out_path are required"})

        (not (png-extension? out))
        (json/generate-string {:error "out_path must end with .png" :out_path out})

        (some nil? [x y w h])
        (json/generate-string {:error "x, y, width, height must be integers"})

        (or (< w 1) (> w crop-max-edge) (< h 1) (> h crop-max-edge))
        (json/generate-string {:error "width and height must be between 1 and max (pixels)"
                               :max_edge crop-max-edge})

        (or (< x 0) (< y 0))
        (json/generate-string {:error "x and y must be >= 0"})

        :else
        (let [^File src-f (resolve-file-under-workspace! src)
              kind (file-kind-raster-or-pdf (.getName src-f))]
          (cond
            (not (.exists src-f))
            (json/generate-string {:error "source file not found" :source_path src})

            (not (.isFile src-f))
            (json/generate-string {:error "source is not a regular file" :source_path src})

            (= :unknown kind)
            (json/generate-string {:error "source must be .png, .jpg, .jpeg, or .pdf" :source_path src})

            (and (= :pdf kind) (or (nil? page) (< (long page) 1)))
            (json/generate-string {:error "page is required for PDF (1-based page index)" :source_path src})

            :else
            (let [^BufferedImage full (load-image-for-crop! src-f kind page dpi)
                  iw (.getWidth full)
                  ih (.getHeight full)
                  x0 (max 0 (- x pad))
                  y0 (max 0 (- y pad))
                  x1 (min iw (+ x w pad))
                  y1 (min ih (+ y h pad))
                  cw (max 1 (- x1 x0))
                  ch (max 1 (- y1 y0))]
              (when (or (> cw crop-max-edge) (> ch crop-max-edge))
                (throw (ex-info "padded crop exceeds max edge" {:width cw :height ch :max crop-max-edge})))
              (when (or (> (+ x0 cw) iw) (> (+ y0 ch) ih))
                (throw (ex-info "crop rectangle out of bounds"
                                {:image_width iw :image_height ih :x x0 :y y0 :width cw :height ch})))
              (let [^BufferedImage sub (.getSubimage full (int x0) (int y0) (int cw) (int ch))
                    ^File out-f (resolve-file-under-workspace! out)
                    parent (.getParentFile out-f)]
                (when (and (.exists out-f) (.isDirectory out-f))
                  (throw (ex-info "out_path is a directory" {:out_path out})))
                (when parent (.mkdirs parent))
                (when-not (ImageIO/write sub "png" out-f)
                  (throw (ex-info "failed to write PNG" {:out_path out})))
                (json/generate-string
                 (cond-> {:format "image_crop"
                          :source_path src
                          :out_path out
                          :source_kind (name kind)
                          :crop_applied {:x x0 :y y0 :width cw :height ch}
                          :requested {:x x :y y :width w :height h :pad_px pad}
                          :source_dimensions {:width iw :height ih}
                          :hint (str "Saved PNG crop. Use same dpi as analyze_pdf_line_drawings when cropping from PDF; "
                                     "pair with ocr_pdf_document on the same region path if you need text.")}
                   (= :pdf kind) (assoc :pdf {:page (long page) :dpi dpi})))))))))
    (catch Exception e
      (json/generate-string {:error (or (.getMessage e) "image crop failed")
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

(defn- read-office-as-json! [^File f path-str]
  (let [lower (str/lower-case path-str)
        ext (some-> (re-find #"\.([^.]+)$" lower) second)]
    (cond
      (= ext "docx")
      (let [{:keys [text tables]} (extract-docx f)]
        (json/generate-string {:format "docx" :path path-str :text text :tables tables}))
      (or (= ext "xlsx") (= ext "xls"))
      (let [{:keys [text tables]} (extract-xlsx f)]
        (json/generate-string {:format "xlsx" :path path-str :text text :tables tables}))
      :else
      (try
        (let [{:keys [text tables]} (extract-xlsx f)]
          (json/generate-string {:format "xlsx" :path path-str :text text :tables tables}))
        (catch Exception _
          (let [{:keys [text tables]} (extract-docx f)]
            (json/generate-string {:format "docx" :path path-str :text text :tables tables})))))))

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

        :else
        (try (read-office-as-json! f path-str)
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
          ^File f (resolve-file-under-workspace! path-str)]
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

(defn tool-log-crop-line
  "Brief source -> out for stderr logging, or nil."
  [arguments]
  (let [m (parse-args-map arguments)
        src (or (some-> (:source_path m) str str/trim not-empty)
                (some-> (get m "source_path") str str/trim not-empty)
                (some-> (:sourcePath m) str str/trim not-empty))
        out (or (some-> (:out_path m) str str/trim not-empty)
                (some-> (get m "out_path") str str/trim not-empty)
                (some-> (:outPath m) str str/trim not-empty))]
    (when (and src out)
      (str (pr-str src) " -> " (pr-str out)))))

(defn startup-status-line []
  (str "Workspace file tools (paths must stay under :workspace :default-root): read_workspace_dir, read/write text, write_workspace_png, crop_workspace_image, "
       "Office extract, PDF text/OCR, BoofCV lines — root "
       (pr-str (cfg/workspace-root))
       " — "
       (if (tessdata-path-or-nil)
         "OCR: tessdata OK"
         "OCR: no tessdata (ocr_pdf_document needs Tesseract language data)")))
