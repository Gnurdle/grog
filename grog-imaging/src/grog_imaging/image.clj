(ns grog-imaging.image
  "Image processing utilities including OCR, PNG handling, and image manipulation."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.awt BasicStroke Color Font Graphics2D Rectangle RenderingHints]
           [java.awt.image BufferedImage RescaleOp]
           [java.io File]
           [java.util ArrayList Base64 List]
           [javax.imageio ImageIO]
           [net.sourceforge.tess4j ITessAPI$TessOcrEngineMode ITessAPI$TessPageIteratorLevel
            ITessAPI$TessPageSegMode Tesseract TesseractException Word]
           [org.apache.pdfbox Loader]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.rendering ImageType PDFRenderer]
           [boofcv.abst.feature.detect.line DetectLineSegment]
           [boofcv.alg.filter.binary BinaryImageOps ThresholdImageOps]
           [boofcv.alg.shapes.ellipse BinaryEllipseDetector]
           [boofcv.alg.shapes.polygon DetectPolygonBinaryGrayRefine]
           [boofcv.factory.feature.detect.line ConfigLineRansac FactoryDetectLine]
           [boofcv.factory.shape ConfigEllipseDetector ConfigPolygonDetector FactoryShapeDetector]
           [boofcv.io.image ConvertBufferedImage]
           [boofcv.struct ConnectRule]
           [boofcv.struct.image GrayU8]
           [georegression.struct.curve EllipseRotated_F64]
           [georegression.struct.line LineSegment2D_F32]
           [georegression.struct.point Point2D_F64 Point2D_I32]
           [georegression.struct.shapes Polygon2D_F64]))

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

;; --- raster read / metadata / colors ----------------------------------------

(def raster-max-file-bytes (* 64 1024 1024))
(def raster-max-pixels (* 40 1024 1024))

(defn load-raster-image!
  "Decode a raster image (PNG/JPG) from disk, guarding file size and pixel count
  (decompression-bomb protection)."
  ^BufferedImage [^File f]
  (cond
    (not (.exists f))
    (throw (ex-info "file not found" {:path (.getPath f)}))

    (> (.length f) raster-max-file-bytes)
    (throw (ex-info "image file too large"
                    {:path (.getPath f) :bytes (.length f)
                     :max_bytes raster-max-file-bytes}))

    :else
    (let [^BufferedImage img (ImageIO/read f)]
      (when (nil? img)
        (throw (ex-info "could not decode image (unsupported or corrupt)"
                        {:path (.getPath f)})))
      (let [w (.getWidth img) h (.getHeight img)]
        (when (> (* w h) raster-max-pixels)
          (throw (ex-info "image too large (pixel bomb)"
                          {:path (.getPath f) :width w :height h
                           :pixels (* w h) :max_pixels raster-max-pixels})))
        img))))

(defn- buffered-type-name [^BufferedImage img]
  (case (.getType img)
    BufferedImage/TYPE_INT_RGB "int_rgb"
    BufferedImage/TYPE_INT_ARGB "int_argb"
    BufferedImage/TYPE_INT_ARGB_PRE "int_argb_pre"
    BufferedImage/TYPE_3BYTE_BGR "3byte_bgr"
    BufferedImage/TYPE_4BYTE_ABGR "4byte_abgr"
    BufferedImage/TYPE_BYTE_GRAY "byte_gray"
    BufferedImage/TYPE_USHORT_GRAY "ushort_gray"
    BufferedImage/TYPE_BYTE_BINARY "byte_binary"
    (str "type_" (.getType img))))

(defn image-metadata
  "Decode-level metadata for an already-loaded raster image."
  [^BufferedImage img ^File f]
  (let [cm (.getColorModel img)
        w (.getWidth img) h (.getHeight img)]
    {:format "raster"
     :path (.getAbsolutePath f)
     :bytes (.length f)
     :width w
     :height h
     :pixels (* w h)
     :has_alpha (.hasAlpha cm)
     :components (.getNumComponents cm)
     :bits_per_pixel (.getPixelSize cm)
     :buffered_type (buffered-type-name img)}))

(defn dominant-colors
  "Sample a coarse grid of the image, quantize RGB into 5-bit buckets, and return
  the most frequent colors with percentages."
  [^BufferedImage img ^long max-buckets]
  (let [w (.getWidth img) h (.getHeight img)
        sx (max 1 (quot w 48)) sy (max 1 (quot h 48))
        buckets
        (loop [y 0 acc []]
          (if (>= y h)
            acc
            (recur (+ y sy)
                   (loop [x 0 acc2 acc]
                     (if (>= x w)
                       acc2
                       (let [rgb (.getRGB img x y)
                             r (bit-shift-right (bit-and rgb 0xff0000) 16)
                             g (bit-shift-right (bit-and rgb 0xff00) 8)
                             b (bit-and rgb 0xff)]
                         (recur (+ x sx)
                                (conj acc2 (format "%02x%02x%02x"
                                                   (bit-and r 0xf8)
                                                   (bit-and g 0xf8)
                                                   (bit-and b 0xf8))))))))))
        counts (frequencies buckets)
        total (max 1 (long (count buckets)))
        tops (->> counts (sort-by val >) (take (max 0 (long max-buckets))))]
    (mapv (fn [[hex n]]
            {:hex hex
             :count (long n)
             :percent (double (/ (* 100.0 (long n)) total))})
          tops)))

;; --- OCR on a raster image ---------------------------------------------------

(defn ocr-image!
  "OCR a decoded BufferedImage. Optional word-level boxes (text + confidence +
  bounding rect) via Tesseract's word iterator."
  [^BufferedImage rgb ^String datapath ^String language psm dpi
   preprocess? with-words?]
  (let [img (preprocess-page-image rgb preprocess?)
        tess (make-tesseract datapath language dpi psm)
        text (try (.doOCR tess img)
                  (catch TesseractException e
                    (throw (ex-info "OCR failed" {:detail (.getMessage e)}))))
        words (when with-words?
                (try
                  (let [^List ws (.getWords tess img
                                            (int ITessAPI$TessPageIteratorLevel/RIL_WORD))]
                    (mapv (fn [^Word w]
                            (let [^Rectangle r (.getBoundingBox w)]
                              {:text (.getText w)
                               :confidence (double (.getConfidence w))
                               :x (int (.x r)) :y (int (.y r))
                               :width (int (.width r)) :height (int (.height r))}))
                          ws))
                  (catch TesseractException e
                    (throw (ex-info "OCR word boxes failed"
                                    {:detail (.getMessage e)})))))]
    {:text (normalize-ocr-text-for-llm text)
     :language language
     :page_seg_mode psm
     :preprocess preprocess?
     :words words}))

;; --- BoofCV geometry kernel --------------------------------------------------
;; All geometry is expressed in source-image pixel coordinates (x right, y down).

(def shape-max-items 200)
(def blob-max-items 300)
(def arrow-max-items 100)
(def default-min-blob-area-px 30)
(def default-arrow-head-radius-px 26)

(defn segment->map
  "LineSegment2D_F32 -> clojure map (float endpoint coords)."
  [^LineSegment2D_F32 s]
  (let [a (.getA s) b (.getB s)]
    {:x1 (float (.x a)) :y1 (float (.y a))
     :x2 (float (.x b)) :y2 (float (.y b))
     :length_px (float (.getLength s))}))

(defn make-line-detector
  ^DetectLineSegment [region-size-or-nil]
  (let [^ConfigLineRansac cfg (ConfigLineRansac.)]
    (when (number? region-size-or-nil)
      (let [rs (max 20 (min 120 (long region-size-or-nil)))]
        (set! (.regionSize cfg) (int rs))))
    (.checkValidity cfg)
    (FactoryDetectLine/lineRansac cfg GrayU8)))

(defn gray-lines!
  "BoofCV RANSAC line segments on a grayscale image."
  [^GrayU8 gray region-size-or-nil max-seg]
  (let [^DetectLineSegment detector (make-line-detector region-size-or-nil)
        found (.detect detector gray)
        mapped (mapv segment->map found)
        sorted (sort-by (comp - :length_px) mapped)
        trimmed (vec (take max-seg sorted))]
    {:width_px (.getWidth gray)
     :height_px (.getHeight gray)
     :segment_count (count found)
     :segments_returned (count trimmed)
     :segments_truncated (> (count found) (count trimmed))
     :segments trimmed}))

(defn raster-lines!
  "Line segments on a decoded BufferedImage."
  [^BufferedImage rgb region-size-or-nil max-seg]
  (gray-lines! (ConvertBufferedImage/convertFromSingle rgb nil GrayU8)
               region-size-or-nil (long max-seg)))

(defn- threshold-binary ^GrayU8 [^GrayU8 gray threshold]
  (ThresholdImageOps/threshold gray (GrayU8. (.getWidth gray) (.getHeight gray))
                               (int threshold) true))

(defn- gray->threshold-binary ^GrayU8 [^GrayU8 gray]
  (threshold-binary gray 128))

(defn- pt->map [^Point2D_I32 p] {:x (double (.getX p)) :y (double (.getY p))})
(defn- ptf->map [^Point2D_F64 p] {:x (double (.x p)) :y (double (.y p))})
(defn- dist2 [a b]
  (let [dx (- (double (:x a)) (double (:x b)))
        dy (- (double (:y a)) (double (:y b)))]
    (+ (* dx dx) (* dy dy))))

(defn- farthest-pair
  "Two points with maximum pairwise distance from a small point set."
  [pts]
  (reduce (fn [best [a b]]
            (let [d (dist2 a b)]
              (if (> d (:d2 best)) {:a a :b b :d2 d} best)))
          {:a (first pts) :b (first pts) :d2 -1.0}
          (for [i (range (count pts)) j (range (inc i) (count pts))]
            [(nth pts i) (nth pts j)])))

(defn- angle-deg [a b]
  (let [dx (- (double (:x b)) (double (:x a)))
        dy (- (double (:y b)) (double (:y a)))]
    (* 180.0 (/ (Math/atan2 dy dx) Math/PI))))

(defn- polygon-shoelace-area [pts]
  (let [n (count pts)]
    (if (< n 3)
      0.0
      (/ (reduce (fn [acc i]
                   (let [p (nth pts i) q (nth pts (mod (inc i) n))]
                     (+ acc (- (* (double (:x p)) (double (:y q)))
                               (* (double (:x q)) (double (:y p)))))))
                 0.0
                 (range n))
         2.0))))

(defn- polygon->map [^Polygon2D_F64 p]
  (let [n (.size p)
        pts (vec (for [i (range n)
                       :let [^Point2D_F64 v (.get p i)]]
                   {:x (double (.x v)) :y (double (.y v))}))]
    (when (>= n 3)
      (let [xs (mapv :x pts) ys (mapv :y pts)
            minx (apply min xs) maxx (apply max xs)
            miny (apply min ys) maxy (apply max ys)
            {a :a b :b} (farthest-pair pts)]
        {:points pts
         :center {:x (/ (reduce + xs) n) :y (/ (reduce + ys) n)}
         :bbox {:x minx :y miny :width (- maxx minx) :height (- maxy miny)}
         :width (- maxx minx)
         :height (- maxy miny)
         :angle_deg (angle-deg a b)
         :area_px (Math/abs (polygon-shoelace-area pts))
         :convex (boolean (.isConvex p))}))))

(defn gray-quads!
  "Detect quadrilateral polygons (boxes / rectangles) in a grayscale image."
  [^GrayU8 gray max-items]
  (let [^DetectPolygonBinaryGrayRefine det
        (FactoryShapeDetector/polygon (ConfigPolygonDetector.) GrayU8)
        _ (when (some? (.getDetector det))
            (.setNumberOfSides (.getDetector det) 4 4))
        _ (.process det gray (gray->threshold-binary gray))
        results (java.util.ArrayList.)
        info (java.util.ArrayList.)
        ^List polys (.getPolygons det results info)
        mapped (keep polygon->map polys)
        trimmed (vec (take max-items mapped))]
    {:polygon_count (.size results)
     :returned (count trimmed)
     :truncated (> (.size results) (count trimmed))
     :items trimmed}))

(defn gray-ellipses!
  "Detect ellipses in a grayscale image."
  [^GrayU8 gray max-items]
  (let [^BinaryEllipseDetector det
        (FactoryShapeDetector/ellipse (ConfigEllipseDetector.) GrayU8)
        _ (.process det gray (gray->threshold-binary gray))
        out (java.util.ArrayList.)
        ^List ells (.getFoundEllipses det out)
        mapped (mapv (fn [^EllipseRotated_F64 e]
                       (let [^Point2D_F64 c (.getCenter e)
                             a (.getA e) b (.getB e)]
                         {:center {:x (.x c) :y (.y c)}
                          :radius_a a
                          :radius_b b
                          :phi_rad (double (.getPhi e))
                          :bbox {:x (- (.x c) a) :y (- (.y c) b)
                                 :width (* 2.0 a) :height (* 2.0 b)}}))
                     ells)
        trimmed (vec (take max-items mapped))]
    {:ellipse_count (.size out)
     :returned (count trimmed)
     :truncated (> (.size out) (count trimmed))
     :items trimmed}))

(defn- contour->blob [^boofcv.alg.filter.binary.Contour c]
  (let [pts (mapv pt->map (.external c))
        n (count pts)]
    (when (>= n 3)
      (let [xs (mapv :x pts) ys (mapv :y pts)
            minx (apply min xs) maxx (apply max xs)
            miny (apply min ys) maxy (apply max ys)
            area (Math/abs (polygon-shoelace-area pts))
            perim (reduce (fn [acc i]
                            (+ acc (Math/sqrt (dist2 (nth pts i)
                                                     (nth pts (mod (inc i) n))))))
                          0.0 (range n))]
        {:bbox {:x minx :y miny :width (- maxx minx) :height (- maxy miny)}
         :area_px area
         :perimeter_px perim
         :centroid {:x (/ (reduce + xs) n) :y (/ (reduce + ys) n)}
         :point_count n}))))

(defn gray-blobs!
  "Connected regions (contours) of a thresholded grayscale image."
  [^GrayU8 gray max-items ^double min-area]
  (let [^List contours (BinaryImageOps/contourExternal (gray->threshold-binary gray)
                                                       ConnectRule/FOUR)
        all-pts (mapv contour->blob contours)
        kept (filter #(and % (>= (:area_px %) min-area)) all-pts)
        sorted (sort-by (comp - :area_px) kept)
        trimmed (vec (take max-items sorted))]
    {:contour_count (count contours)
     :returned (count trimmed)
     :truncated (> (count kept) (count trimmed))
     :items trimmed}))

(defn- normalize180
  "Normalize an angle in degrees to the range [-90, 90] (undirected line angle)."
  [deg]
  (let [d (mod (+ (double deg) 90.0) 180.0)]
    (- d 90.0)))

(defn- endpoint-arrow-score
  "For an endpoint p of segment i with undirected shaft angle theta-line
  (in [-90,90]), search segments that form a V: length <= 2*radius with an
  endpoint within `radius` of p, and an undirected orientation 20-85 deg from
  the shaft. Note RANSAC often fuses the two barbs into one diagonal segment,
  so a single qualifying barb is enough to mark an arrowhead candidate here."
  [segments i p theta-line radius]
  (let [[px py] p
        barbs (keep (fn [j]
                      (let [{x1 :x1 y1 :y1 x2 :x2 y2 :y2 bl :length_px} (nth segments j)]
                        (when (and (not= i j) (<= bl (* 2.0 radius)))
                          (let [d1 (Math/sqrt (dist2 {:x x1 :y y1} {:x px :y py}))
                                d2 (Math/sqrt (dist2 {:x x2 :y y2} {:x px :y py}))
                                near (min d1 d2)
                                barb-line (normalize180
                                           (* 180.0 (/ (Math/atan2 (- y2 y1) (- x2 x1))
                                                       Math/PI)))
                                rel (normalize180 (- barb-line theta-line))]
                            (when (<= near radius)
                              {:rel rel :len bl})))))
                    (range (count segments)))
        barbs (filter #(and (>= (Math/abs (:rel %)) 20.0)
                            (<= (Math/abs (:rel %)) 85.0))
                      barbs)]
    (when (seq barbs)
      {:count (count barbs)
       :confidence (min 1.0 (+ 0.4 (* 0.12 (count barbs))
                               (/ 0.3 (inc (double (apply min (map :len barbs)))))))
       :max_barb (double (apply max (map :len barbs)))})))

(defn arrow-candidates!
  "Heuristic arrow-head detection on RANSAC line segments: a candidate is a
  segment (shaft) with a short barb segment near one endpoint at 20-85 deg
  from the shaft (undirected). Strong filters: shaft >= 25px and at least
  2.2x longer than its barbs, which rejects glyph/curve slivers. Heuristic —
  treat as candidates, not ground truth."
  [segments max-items]
  (let [radius default-arrow-head-radius-px
        min-shaft 25.0
        ratio 2.2
        n (count segments)
        found (vec
               (for [i (range n)
                     :let [{x1 :x1 y1 :y1 x2 :x2 y2 :y2 len :length_px} (nth segments i)
                           theta-line (normalize180 (angle-deg {:x x1 :y y1} {:x x2 :y y2}))
                           s1 (when (>= len min-shaft)
                                (endpoint-arrow-score segments i [x1 y1] theta-line radius))
                           s2 (when (>= len min-shaft)
                                (endpoint-arrow-score segments i [x2 y2] theta-line radius))]
                     :when (or s1 s2)]
                 (let [ok? (fn [s] (>= len (* ratio (max 4.0 (:max_barb s)))))]
                   (cond
                     (and s1 s2) (let [b (if (>= (:confidence s1) (:confidence s2)) s1 s2)]
                                   (when (ok? b)
                                     {:tail (if (identical? b s1) {:x x2 :y y2} {:x x1 :y y1})
                                      :head (if (identical? b s1) {:x x1 :y y1} {:x x2 :y y2})
                                      :confidence (:confidence b)
                                      :shaft_length len
                                      :line_index i}))
                     s1 (when (ok? s1)
                          {:tail {:x x2 :y y2} :head {:x x1 :y y1}
                           :confidence (:confidence s1) :shaft_length len :line_index i})
                     s2 (when (ok? s2)
                          {:tail {:x x1 :y y1} :head {:x x2 :y y2}
                           :confidence (:confidence s2) :shaft_length len :line_index i})))))]
    (let [found (remove nil? found)
          sorted (sort-by (juxt (comp - :confidence) (comp - :shaft_length)) found)
          trimmed (vec (take max-items sorted))]
      {:candidate_count (count found)
       :returned (count trimmed)
       :truncated (> (count found) (count trimmed))
       :items trimmed})))

(defn analyze-image-shapes!
  "Combined geometry pass over a decoded BufferedImage."
  [^BufferedImage rgb {:keys [region_size max_lines max_rectangles max_ellipses
                              max_blobs max_arrows min_blob_area]}]
  (let [gray (ConvertBufferedImage/convertFromSingle rgb nil GrayU8)
        lines (gray-lines! gray region_size (long (or max_lines 400)))
        quads (gray-quads! gray (long (or max_rectangles shape-max-items)))
        ellipses (gray-ellipses! gray (long (or max_ellipses shape-max-items)))
        blobs (gray-blobs! gray (long (or max_blobs blob-max-items))
                           (double (or min_blob_area default-min-blob-area-px)))
        arrows (arrow-candidates! (:segments lines) (long (or max_arrows arrow-max-items)))]
    {:width_px (.getWidth gray)
     :height_px (.getHeight gray)
     :lines lines
     :rectangles quads
     :ellipses ellipses
     :blobs blobs
     :arrows arrows}))

;; --- overlay drawing ---------------------------------------------------------

(def ^:private overlay-drawing-cap 500)

(defn- num-opt [m k default]
  (let [v (or (get m k) (get m (name k)))
        v (if (and (string? v) (not (str/blank? v))) (Double/parseDouble v) v)]
    (cond (nil? v) default
          (number? v) (double v)
          :else default)))

(defn- str-opt [m k]
  (let [v (or (get m k) (get m (name k)))]
    (cond (nil? v) nil
          (string? v) v
          :else (str v))))

(defn- draw-overlay-rects!
  [^Graphics2D g ^List rects]
  (doseq [r (take overlay-drawing-cap rects)
          :let [x (num-opt r :x 0.0) y (num-opt r :y 0.0)
                w (num-opt r :width 10.0) h (num-opt r :height 10.0)
                label (str-opt r :label)]]
    (.setColor g Color/GREEN)
    (.setStroke g (BasicStroke. (float 2.0)))
    (.drawRect g (int x) (int y) (int w) (int h))
    (when label
      (.setColor g (Color. 0 153 0))
      (.setFont g (Font. Font/SANS_SERIF Font/PLAIN 13))
      (.drawString g label (float (+ x 2.0)) (float (max 12.0 (- y 4.0)))))))

(defn- draw-overlay-lines!
  [^Graphics2D g ^List lines]
  (.setColor g Color/RED)
  (.setStroke g (BasicStroke. (float 2.0)))
  (doseq [l (take overlay-drawing-cap lines)
          :let [x1 (num-opt l :x1 0.0) y1 (num-opt l :y1 0.0)
                x2 (num-opt l :x2 0.0) y2 (num-opt l :y2 0.0)]]
    (.drawLine g (int x1) (int y1) (int x2) (int y2))))

(defn- draw-overlay-ellipses!
  [^Graphics2D g ^List ells]
  (doseq [e (take overlay-drawing-cap ells)
          :let [cx (num-opt e :center_x (num-opt e :cx 0.0))
                cy (num-opt e :center_y (num-opt e :cy 0.0))
                a (num-opt e :radius_a (num-opt e :a 10.0))
                b (num-opt e :radius_b (num-opt e :b 10.0))
                phi (num-opt e :phi_rad (num-opt e :phi 0.0))]]
    (.setColor g Color/BLUE)
    (.setStroke g (BasicStroke. (float 2.0)))
    (let [old (.getTransform g)]
      (try
        (.translate g (double cx) (double cy))
        (.rotate g (double phi))
        (.drawOval g (int (- a)) (int (- b)) (int (* 2.0 a)) (int (* 2.0 b)))
        (finally (.setTransform g old))))))

(defn- draw-overlay-text-boxes!
  [^Graphics2D g ^List boxes]
  (doseq [b (take overlay-drawing-cap boxes)
          :let [x (num-opt b :x 0.0) y (num-opt b :y 0.0)
                w (num-opt b :width 10.0) h (num-opt b :height 10.0)
                txt (str-opt b :text)]]
    (.setColor g (Color. 255 165 0))
    (.setStroke g (BasicStroke. (float 1.5)))
    (.drawRect g (int x) (int y) (int w) (int h))
    (when txt
      (.setColor g (Color. 255 140 0))
      (.setFont g (Font. Font/SANS_SERIF Font/PLAIN 12))
      (.drawString g txt (float x) (float (max 12.0 (- y 3.0)))))))

(defn- draw-overlay-blobs!
  [^Graphics2D g ^List blobs]
  (doseq [b (take overlay-drawing-cap blobs)
          :let [x (num-opt b :x 0.0) y (num-opt b :y 0.0)
                w (num-opt b :width 10.0) h (num-opt b :height 10.0)]]
    (.setColor g Color/MAGENTA)
    (.setStroke g (BasicStroke. (float 1.0) (int BasicStroke/CAP_BUTT)
                                (int BasicStroke/JOIN_MITER)
                                (float 10.0) (float-array [4.0 4.0]) (float 0.0)))
    (.drawRect g (int x) (int y) (int w) (int h))))

(defn- draw-overlay-arrows!
  [^Graphics2D g ^List arrows]
  (doseq [a (take overlay-drawing-cap arrows)
          :let [hx (num-opt a :head_x 0.0) hy (num-opt a :head_y 0.0)
                tx (num-opt a :tail_x 0.0) ty (num-opt a :tail_y 0.0)]]
    (.setColor g Color/ORANGE)
    (.setStroke g (BasicStroke. (float 2.5)))
    (.drawLine g (int tx) (int ty) (int hx) (int hy))
    ;; small head wedge
    (let [ang (Math/atan2 (- hy ty) (- hx tx))
          r 12.0
          p1 [(+ hx (* r (Math/cos (+ ang 0.5)))) (+ hy (* r (Math/sin (+ ang 0.5))))]
          p2 [(+ hx (* r (Math/cos (- ang 0.5)))) (+ hy (* r (Math/sin (- ang 0.5))))]
          xs (int-array [(int hx) (int (p1 0)) (int (p2 0))])
          ys (int-array [(int hy) (int (p1 1)) (int (p2 1))])]
      (.fillPolygon g xs ys 3))))

(defn draw-overlay!
  "Draw a set of overlays onto a copy of `rgb` and return the annotated image.
  `opts` keys: :rectangles (x y width height [+label]), :lines (x1 y1 x2 y2),
  :ellipses (center_x center_y radius_a radius_b phi_rad), :text_boxes (x y
  width height [+text]), :blobs (x y width height), :arrows (head_x head_y
  tail_x tail_y). Input maps may use keyword or string keys."
  [^BufferedImage rgb opts]
  (let [w (.getWidth rgb) h (.getHeight rgb)
        ^BufferedImage out (BufferedImage. w h BufferedImage/TYPE_INT_ARGB)
        ^Graphics2D g (.createGraphics out)]
    (try
      (doto g
        (.setRenderingHints
         (doto (RenderingHints. RenderingHints/KEY_ANTIALIASING
                                RenderingHints/VALUE_ANTIALIAS_ON)
           (.put RenderingHints/KEY_RENDERING RenderingHints/VALUE_RENDER_QUALITY)))
        (.drawImage rgb 0 0 nil))
      (draw-overlay-rects! g (get opts :rectangles []))
      (draw-overlay-lines! g (get opts :lines []))
      (draw-overlay-ellipses! g (get opts :ellipses []))
      (draw-overlay-text-boxes! g (get opts :text_boxes []))
      (draw-overlay-blobs! g (get opts :blobs []))
      (draw-overlay-arrows! g (get opts :arrows []))
      out
      (finally (.dispose g)))))

(defn save-png!
  "Write a BufferedImage to a .png file (creates parent dirs)."
  [^BufferedImage img ^File out]
  (let [parent (.getParentFile out)]
    (when (and parent (not (.exists parent))) (.mkdirs parent)))
  (when-not (ImageIO/write img "png" out)
    (throw (ex-info "failed to write PNG" {:out_path (.getPath out)})))
  (.getAbsolutePath out))