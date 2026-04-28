(ns grog.image
  "Image processing utilities including OCR, PNG handling, and image manipulation."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.awt Graphics2D RenderingHints]
           [java.awt.image BufferedImage RescaleOp]
           [java.io File]
           [java.util Base64]
           [javax.imageio ImageIO]
           [net.sourceforge.tess4j ITessAPI$TessOcrEngineMode ITessAPI$TessPageSegMode
            Tesseract TesseractException]
           [org.apache.pdfbox Loader]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.rendering ImageType PDFRenderer]))

(def crop-max-edge 4096)
(def crop-max-pad 256)
(def crop-default-pdf-dpi 220)

(def max-pdf-raster-dpi
  "Upper bound on PDF rasterization DPI for `ocr_pdf_document`, PDF `crop_workspace_image`, and
  `analyze_pdf_line_drawings`. Use the same dpi when pairing OCR with line geometry or crops."
  1200)

(def default-ocr-dpi 300)
(def min-ocr-dpi 120)

(def default-pdf-max-pages 100)
(def pdf-max-pages-cap 500)
(def pdf-max-file-bytes (* 100 1024 1024))
(def pdf-max-text-chars (* 2 1024 1024))

(def default-ocr-max-pages 30)
(def ocr-max-pages-cap 100)
(def ocr-psm-max 13)

(def png-max-decoded-bytes (* 15 1024 1024))

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

(defn json-bool [v default]
  (cond (boolean? v) v
        (string? v) (case (str/lower-case (str/trim ^String v))
                      ("false" "0" "no") false
                      ("true" "1" "yes") true
                      default)
        (number? v) (not (zero? (long v)))
        :else default))

(defn parse-ocr-psm [m]
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

(defn extract-pdf-ocr!
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

(defn png-extension? [^String path]
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

(defn decode-png-base64-bytes! [^String raw]
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

(defn pdf-renderer-for-crop ^PDFRenderer [^PDDocument doc]
  (doto (PDFRenderer. doc)
    (.setSubsamplingAllowed false)
    (.setRenderingHints
     (doto (RenderingHints. RenderingHints/KEY_INTERPOLATION
                            RenderingHints/VALUE_INTERPOLATION_BICUBIC)
       (.put RenderingHints/KEY_RENDERING RenderingHints/VALUE_RENDER_QUALITY)
       (.put RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
        (.put RenderingHints/KEY_TEXT_ANTIALIASING
              RenderingHints/VALUE_TEXT_ANTIALIAS_ON)))))

(defn file-kind-raster-or-pdf [^String name]
  (let [n (str/lower-case name)]
    (cond (str/ends-with? n ".pdf") :pdf
          (or (str/ends-with? n ".png")
              (str/ends-with? n ".jpg")
              (str/ends-with? n ".jpeg")) :raster
          :else :unknown)))

(defn get-long-opt [m kw & str-keys]
  (let [x (or (get m kw) (some #(get m %) str-keys))]
    (cond (number? x) (long x)
          (and (string? x) (not (str/blank? x)))
          (try (Long/parseLong (str/trim x))
               (catch NumberFormatException _ nil))
          :else nil)))

(defn load-image-for-crop!
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