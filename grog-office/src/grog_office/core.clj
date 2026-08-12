(ns grog-office.core
  "grog-office — Apache POI document server core.

  Loads a .docx into memory keyed by a server-side `handle` (returned by
  `import-document`) and exposes a stable, ordered *block model* over the
  document body: `para.N` for paragraphs and `table.M` for tables, exactly as
  the `.map.edn` calibration rows address them.

  Editing is run-aware and formatting-preserving: `replace-text` only mutates
  character data inside the runs and places the replacement entirely in the
  *first* matched run so its `rPr` (the pixel-critical style) is preserved.
  `delete-table-row` removes a visible table row, skipping ghost (vertically
  merged / vMerge-continuation) rows."
  (:require [clojure.string :as str])
  (:import [java.io File FileInputStream FileOutputStream]
           [org.apache.poi.xwpf.usermodel
            XWPFDocument XWPFParagraph XWPFTable XWPFTableRow XWPFTableCell XWPFRun]
           [org.openxmlformats.schemas.wordprocessingml.x2006.main CTText]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; handle store (server-side document state)
;; ---------------------------------------------------------------------------

(defonce ^:private !handles (atom {}))
(def ^:private next-handle (atom 0))

(defn- new-handle! []
  (str "doc." (swap! next-handle inc)))

(defn import-document!
  "Open `path` (a .docx) and store it under a fresh handle. Returns the handle.
  Throws on missing/unsupported files."
  [path]
  (let [f (File. (str path))]
    (when-not (.exists f)
      (throw (ex-info (str "File not found: " path) {:path path})))
    (let [ext (str/lower-case (or (some-> (re-find #"\.([^.]+)$" (str path)) second) ""))]
      (when-not (= ext "docx")
        (throw (ex-info (str "Unsupported format ." (or ext "?")
                             " — grog-office edits .docx. Convert .doc/.odt to .docx first")
                        {:path path :format ext})))
      (let [h (new-handle!)
          in (FileInputStream. f)
          doc (XWPFDocument. in)]
      ;; Keep the XWPFDocument (the editable package) open in the handle map —
      ;; closing it here would discard all later edits/saves. Only the input
      ;; stream is disposed.
      (.close in)
      (swap! !handles assoc h {:document doc :path path})
      h))))

(defn- doc! [handle]
  (or (get-in @!handles [handle :document])
      (throw (ex-info (str "Unknown document handle " (pr-str handle)
                           " — import_document first (or re-import)")
                      {:handle handle}))))

(defn list-handles []
  (vec (for [[h {:keys [path]}] @!handles] {:handle h :path path})))

(defn close-handle! [handle]
  (when-let [d (get-in @!handles [handle :document])]
    (try (.close ^XWPFDocument d)
         (catch Exception _)))
  (swap! !handles dissoc handle)
  handle)

;; ---------------------------------------------------------------------------
;; block model
;; ---------------------------------------------------------------------------

(defn- body-elements ^java.util.List [^XWPFDocument doc]
  (.getBodyElements doc))

(defn- elem-kind [el]
  (cond (instance? XWPFParagraph el) :paragraph
        (instance? XWPFTable el)     :table
        :else nil))

(defn- row-visible?
  "A vertical-merge ghost row (first cell has vMerge=continue) is hidden; treat
  it as invisible so delete/list operate on *display* rows only."
  [^XWPFTableRow r]
  (let [cells (.getTableCells r)]
    (if (empty? cells)
      true
      (try
        (let [^XWPFTableCell c0 (first cells)
              tcpr (some-> (.getCTTc c0) (.getTcPr))
              vm (when tcpr (.getVMerge tcpr))
              val (when vm (.getVal vm))]
          (or (nil? val) (= "restart" (str val))))
        (catch Throwable _ true)))))

(defn- table-visible-rows
  "XWPFTableRow vector in display order (ghost rows excluded)."
  [^XWPFTable tbl]
  (->> (.getRows tbl)
       (filter row-visible?)
       vec))

(defn- table-rows
  "[[cell-text ...] ...] — visible rows in display order."
  [^XWPFTable tbl]
  (mapv (fn [^XWPFTableRow r]
          (mapv (fn [^XWPFTableCell c] (str/trim (or (.getText c) "")))
                (.getTableCells r)))
        (table-visible-rows tbl)))

(defn- para-text ^String [^XWPFParagraph p]
  (or (.getText p) ""))

(defn- block-descriptor [el para-n table-n include-runs?]
  (cond
    (instance? XWPFParagraph el)
    (let [^XWPFParagraph p el
          n @para-n]
      {:block_id (str "para." n)
       :kind     "paragraph"
       :text     (para-text p)
       :runs     (when include-runs?
                   (mapv (fn [^XWPFRun r] (or (.text r) "")) (.getRuns p)))})

    (instance? XWPFTable el)
    (let [^XWPFTable tbl el
          n @table-n]
      {:block_id (str "table." n)
       :kind     "table"
       :rows     (table-rows tbl)})

    :else nil))

(defn list-blocks
  "Ordered block model of the whole document body."
  ([handle] (list-blocks handle false))
  ([handle include-runs?]
   (let [^XWPFDocument doc (doc! handle)
         para-n (atom 0)
         table-n (atom 0)
         blocks (vec
                 (keep (fn [el]
                         (when-let [kind (elem-kind el)]
                           (if (= kind :paragraph)
                             (swap! para-n inc)
                             (swap! table-n inc))
                           (block-descriptor el para-n table-n include-runs?)))
                       (body-elements doc)))]
     {:count (count blocks) :blocks blocks})))

;; ---------------------------------------------------------------------------
;; element lookup by block id
;; ---------------------------------------------------------------------------

(defn- parse-block-id [block-id]
  (let [[kind-str n-str] (str/split (str block-id) #"\." 2)
        n (try (Long/parseLong (or n-str "")) (catch Exception _ nil))]
    (when (and n (#{"para" "table"} kind-str))
      {:kind (if (= kind-str "para") :paragraph :table) :n (long n)})))

(defn- find-element-by-id
  "Return [el kind] for a block id, or throw."
  [^XWPFDocument doc block-id]
  (let [{:keys [kind n]} (parse-block-id block-id)]
    (when-not n
      (throw (ex-info (str "Bad block_id " (pr-str block-id)
                           " — expected para.N or table.N") {:block_id block-id})))
    (let [para-n (atom 0)
          table-n (atom 0)
          found (first
                 (keep (fn [el]
                         (when (= kind (elem-kind el))
                           (let [nn (if (= kind :paragraph) (swap! para-n inc) (swap! table-n inc))]
                             (when (= nn n) [el kind]))))
                       (body-elements doc)))]
      (or found
          (throw (ex-info (str "block_id " (pr-str block-id) " not found")
                          {:block_id block-id}))))))

;; ---------------------------------------------------------------------------
;; get_text
;; ---------------------------------------------------------------------------

(defn get-text
  "Logical (run-concatenated) text of a block. For a table, `cell` (\"r,c\",
  0-based) selects one cell; otherwise returns all rows joined."
  [handle block-id cell]
  (let [^XWPFDocument doc (doc! handle)
        [el kind] (find-element-by-id doc block-id)]
    (case kind
      :paragraph {:block_id block-id :kind "paragraph" :text (para-text ^XWPFParagraph el)}
      :table
      (let [^XWPFTable tbl el]
        (if cell
          (let [[r c] (mapv #(Long/parseLong %) (str/split (str cell) #"," 2))
                ^XWPFTableRow row (.getRow tbl (int r))]
            (when-not row
              (throw (ex-info (str "row out of range: " r) {:block_id block-id :row r})))
            (let [cells (.getTableCells row)]
              (when (or (nil? c) (>= (long c) (count cells)))
                (throw (ex-info (str "cell out of range: " cell)
                                {:block_id block-id :cell cell :cells (count cells)})))
              {:block_id block-id :kind "table" :cell cell
               :text (str/trim (or (.getText ^XWPFTableCell (nth cells (long c))) ""))}))
          (let [rows (table-rows tbl)]
            {:block_id block-id :kind "table"
             :text (str/join "\n" (map #(str/join "\t" %) rows))
             :rows rows}))))))

;; ---------------------------------------------------------------------------
;; find_text
;; ---------------------------------------------------------------------------

(defn- all-occurrences [^String text ^String query limit]
  (let [q (str query)
        n (count text)
        m (count q)]
    (if (or (zero? m) (zero? n) (> m n))
      []
      (loop [i 0 acc []]
        (cond (>= (count acc) limit) acc
              (> (+ i m) n)         acc
              (= (subs text i (+ i m)) q) (recur (+ i m) (conj acc [i m]))
              :else (recur (inc i) acc))))))

(defn find-text
  "Locate `query` across paragraphs and visible table cells."
  [handle query limit]
  (let [^XWPFDocument doc (doc! handle)
        limit (long (or limit 100))
        para-n (atom 0)
        table-n (atom 0)
        found (reduce
               (fn [acc el]
                 (if-let [kind (elem-kind el)]
                   (if (= kind :paragraph)
                     (let [n (swap! para-n inc)
                           block-id (str "para." n)
                           text (para-text ^XWPFParagraph el)]
                       (reduce (fn [a [off len]]
                                 (conj a {:block_id block-id :kind "paragraph"
                                          :text (subs text off (min (count text) (+ off len)))
                                          :offset off}))
                               acc
                               (all-occurrences text query limit)))
                     (let [n (swap! table-n inc)
                           block-id (str "table." n)
                           ^XWPFTable tbl el
                           rows (table-visible-rows tbl)]
                       (reduce (fn [a [r ^XWPFTableRow row]]
                                 (reduce (fn [a2 c]
                                           (let [^XWPFTableCell cell (.getCell row (int c))
                                                 ctext (str/trim (or (.getText cell) ""))]
                                             (reduce (fn [a3 [off len]]
                                                       (conj a3 {:block_id block-id :kind "table"
                                                                 :cell (str r "," c)
                                                                 :text (subs ctext off (min (count ctext) (+ off len)))
                                                                 :offset off}))
                                                     a2
                                                     (all-occurrences ctext query limit))))
                                         a
                                         (range (count (.getTableCells row)))))
                               acc
                               (map-indexed vector rows))))
                   acc))
               []
               (body-elements doc))
        matches (vec (take limit found))]
    {:query query :count (count matches) :matches matches}))

;; ---------------------------------------------------------------------------
;; replace_text (run/rPr preserving)
;; ---------------------------------------------------------------------------

(defn- run-info [^XWPFParagraph p]
  (let [runs (vec (.getRuns p))
        texts (mapv (fn [^XWPFRun r] (or (.text r) "")) runs)
        sizes (mapv count texts)
        offsets (loop [i 0 acc []]
                  (if (>= i (count texts)) acc
                    (recur (inc i) (conj acc (reduce + 0 (take i sizes))))))]
    {:runs runs :texts texts :offsets offsets}))

(defn- run-containing [offsets idx]
  (let [n (count offsets)]
    (loop [i (dec n)]
      (if (or (neg? i) (<= (nth offsets i) idx)) (max 0 i) (recur (dec i))))))

(defn- rewrite-match
  "Rewrite one match at [ms ml] in the run-concatenated text. `replacement` goes
  entirely into the *first* matched run (preserving its rPr); downstream runs
  that covered the match are blanked or de-sliced."
  [{:keys [runs texts offsets]} ms ml replacement]
  (let [n (count runs)
        sizes (mapv count texts)
        ri (run-containing offsets ms)
        lo (- ms (nth offsets ri))
        new-ri (str (subs (nth texts ri) 0 lo)
                    replacement
                    (subs (nth texts ri) (+ lo (min ml (- (nth sizes ri) lo)))))
        rj (run-containing offsets (dec (+ ms ml)))
        new-texts (vec
                   (for [k (range n)]
                     (cond
                       (= k ri) new-ri
                       (and (> k ri) (< k rj)) ""
                       (= k rj) (subs (nth texts k)
                                      (min (- (+ ms ml) (nth offsets k)) (count (nth texts k))))
                       :else (nth texts k))))]
    new-texts))

(defn- set-run-text!
  "Replace a run's character data, preserving its rPr. POI's `XWPFRun.setText`
  *appends* a new `<w:t>` node rather than replacing, so we clear the existing
  text elements first — this keeps the run (and therefore its formatting)
  intact while making the character data exact."
  [^XWPFRun r ^String s]
  (let [ctr (.getCTR r)]
    (doseq [^CTText t (vec (.getTList ctr))]
      (.setStringValue t ""))
    (when (seq s)
      (.setText r s))
    r))

(defn- replace-in-paragraph!
  [^XWPFParagraph p match replacement all?]
  (let [info (run-info p)
        cat (apply str (:texts info))
        occurrences (all-occurrences cat match 100000)]
    (if (empty? occurrences)
      {:changed false :replaced 0}
      (let [to-apply (if all? occurrences [(first occurrences)])]
        (doseq [[ms ml] (reverse to-apply)]
          (let [new-texts (rewrite-match info ms ml replacement)]
            (doseq [[k r] (map-indexed vector (:runs info))]
              (set-run-text! ^XWPFRun r (nth new-texts k)))))
        {:changed true :replaced (count to-apply)}))))

(defn replace-text
  "Replace `match` with `replacement`. When `block-id` is provided, scope to that
  paragraph; otherwise scan the whole document. `all?` replaces every
  occurrence. Always reports `layout_risk` — character-data-only edits return
  \"none\"."
  [handle match replacement {:keys [block-id all?]}]
  (let [^XWPFDocument doc (doc! handle)
        results (atom [])]
    (if block-id
      (let [[el kind] (find-element-by-id doc block-id)]
        (when (= kind :paragraph)
          (swap! results conj (assoc (replace-in-paragraph! ^XWPFParagraph el match replacement all?)
                                     :block_id block-id))))
      (let [para-n (atom 0)]
        (doseq [el (body-elements doc)]
          (when (instance? XWPFParagraph el)
            (let [n (swap! para-n inc)
                  r (replace-in-paragraph! ^XWPFParagraph el match replacement all?)]
              (when (:changed r)
                (swap! results conj (assoc r :block_id (str "para." n)))))))))
    {:match match
     :replacement replacement
     :layout_risk "none"
     :changed? (boolean (seq @results))
     :blocks @results}))

;; ---------------------------------------------------------------------------
;; delete_table_row (ghost aware)
;; ---------------------------------------------------------------------------

(defn delete-table-row
  "Remove visible row `row` (0-based display order) from table block `block_id`."
  [handle block-id row]
  (let [^XWPFDocument doc (doc! handle)
        [el kind] (find-element-by-id doc block-id)]
    (when-not (= kind :table)
      (throw (ex-info (str "block " block-id " is not a table") {:block_id block-id})))
    (let [^XWPFTable tbl el
          visible (table-visible-rows tbl)
          total (count visible)
          row (long row)]
      (when (or (neg? row) (>= row total))
        (throw (ex-info (str "row " row " out of range (table has " total " visible rows)")
                        {:block_id block-id :row row :rows total})))
      (let [all-rows (vec (.getRows tbl))
            ^XWPFTableRow target (nth visible row)
            idx (.indexOf all-rows target)]
        ;; removeRow(int) is the only XWPFTable overload; `idx` is the target's
        ;; position in the full (including ghost) row list.
        (.removeRow tbl (int idx)))
      {:block_id block-id :removed_row row :rows_remaining (dec total)})))

;; ---------------------------------------------------------------------------
;; save
;; ---------------------------------------------------------------------------

(defn save!
  "Flush the in-memory document to `out-path`."
  [handle out-path]
  (let [^XWPFDocument doc (doc! handle)]
    (with-open [out (FileOutputStream. (str out-path))]
      (.write doc out))
    {:saved (str out-path)}))

;; ---------------------------------------------------------------------------
;; render (LibreOffice/soffice headless; POI has no layout engine)
;; ---------------------------------------------------------------------------

(defn- sh! [cmd]
  (let [pb (ProcessBuilder. ^java.util.List (mapv str cmd))
        _ (.redirectErrorStream pb true)
        proc (.start pb)
        out (with-open [in (.getInputStream proc)] (slurp in))
        code (.waitFor proc)]
    {:exit code :output out}))

(defn render!
  "Render to PDF (or PNG) via headless LibreOffice/`soffice` (set GROG_OFFICE_BIN
  to override). Returns produced file paths. POI cannot render docx itself, so
  this is the pragmatic ground-truth proxy for pixel-diffing."
  [handle {:keys [format pages dpi]}]
  (let [dir (System/getProperty "java.io.tmpdir")
        src (File. (str dir "/grog-office-edit-" handle ".docx"))
        outdir (doto (File. (str dir "/grog-office-render-" handle)) (.mkdirs))
        _ (save! handle (str src))
        bin (or (System/getenv "GROG_OFFICE_BIN") "soffice")
        convert (sh! [bin "--headless" "--convert-to" "pdf"
                      "--outdir" (str outdir) (str src)])]
    (when-not (zero? (:exit convert))
      (throw (ex-info (str "render failed — is LibreOffice/soffice installed? (" bin ")\n"
                           (:output convert))
                      {:render (:output convert)})))
    (let [pdf (File. outdir (str (.getName src) ".pdf"))]
      (if (= (str format) "png")
        (let [ppnum (or (System/getenv "GROG_PDFTOPM") "pdftoppm")
              out (sh! (concat [ppnum "-r" (str (long (or dpi 150))) "-png" (str pdf)]
                               (when (sequential? pages)
                                 ["-f" (str (first pages)) "-l" (str (last pages))])
                               [(str (File. outdir "/grog-page"))]))
              produced (->> (.listFiles outdir)
                            (filter #(.exists %) )
                            (mapv #(.getAbsolutePath %)))]
          {:format "png"
           :dpi (long (or dpi 150))
           :pdf (str pdf)
           :pages produced
           :exit (:exit out) :output (:output out)})
        {:format "pdf" :path (str pdf) :exit (:exit convert)}))))
