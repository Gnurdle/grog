(ns grog.doctor
  "`grog doctor` — what is installed, and is the config actually sane.

  Two jobs:

  1. DEPENDENCIES. grog shells out to a handful of external tools. When one is
     missing the failure is usually obscure (a spawn retry storm, a
     `CreateProcess error=2`, an OCR tool that only fails at call time). Each
     probe reports the resolved path, the version, and — when missing — what it
     would have enabled plus a one-line install hint.

  2. CONFIG SOLVENCY, WITH PROVENANCE. `grog.edn` is merged from several files
     (classpath resource, config-home, legacy `~/.config/grog`), later wins.
     That layering is exactly how a value can look set and be overridden, so
     every effective key is reported WITH THE FILE IT CAME FROM,
     unparseable files are reported by file, and a few known-unusable shapes
     (`:max-tokens` outside `:llm`, a model id with no `provider/` prefix, a url
     that is not a url) are flagged.

  Output is data (`report`) so both the CLI (`--json`) and the Electron Setup
  panel can consume it."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.platform :as platform])
  (:import (java.io File)))

(def ^:private windows?
  (str/includes? (str/lower-case (or (System/getProperty "os.name") "")) "win"))

;; ---------------------------------------------------------------------------
;; Dependency probing
;; ---------------------------------------------------------------------------

(def ^:private deps
  [{:id "bash"
    :names ["bash"] :version ["--version"] :required? true
    :capability "MCP servers are spawned as `bash -lc \"cd ... && java ... --server <id>\"`"
    :hint {:windows "scoop install git;  scoop shim add bash \"$(scoop prefix git)\\bin\\bash.exe\"  (scoop does not shim bash itself)"
           :posix   "apt install bash  |  dnf install bash"}}
   {:id "java" :names ["java"] :version ["-version"] :required? true
    :capability "runs grog-spine.jar and the grog-mcp tool jar"
    :hint {:windows "scoop install java/temurin-lts-jdk"
           :posix   "apt install openjdk-21-jre-headless"}}
   {:id "bb" :names ["bb"] :version ["--version"] :required? false
    :capability "run_babashka, and build_dist"
    :hint {:windows "scoop install babashka" :posix "curl -s https://raw.githubusercontent.com/babashka/babashka/master/install | bash"}}
   {:id "eca" :names ["eca" "eca.exe"] :version ["--version"] :required? true
    :capability "the agent loop itself (grog drives `eca server`)"
    :hint {:windows "download the ECA release and point :eca :binary at it"
           :posix   "install ECA and point :eca :binary at it"}}
   {:id "soffice" :names ["soffice" "soffice.exe"] :version ["--version"] :required? false
    :capability "grog-office render/convert/present (OOXML/ODF and slide renders)"
    :hint {:windows "scoop install extras/libreoffice"
           :posix   "apt install libreoffice"}}
   {:id "tesseract" :names ["tesseract" "tesseract.exe"] :version ["--version"] :required? false
    :capability "OCR (ocr_image / ocr_pdf_document)"
    :hint {:windows "scoop install tesseract tesseract-languages"
           :posix   "apt install tesseract-ocr"}}
   {:id "pdftoppm" :names ["pdftoppm" "pdftoppm.exe"] :version ["-v"] :required? false
    :capability "PDF -> PNG page images (render, cropping)"
    :hint {:windows "scoop install poppler"
           :posix   "apt install poppler-utils"}}
   {:id "rg" :names ["rg" "rg.exe"] :version ["--version"] :required? false
    :capability "fast project search (grog-project-search)"
    :hint {:windows "scoop install ripgrep" :posix "apt install ripgrep"}}
   {:id "jq" :names ["jq" "jq.exe"] :version ["--version"] :required? false
    :capability "shell one-liners in the terminal pane"
    :hint {:windows "scoop install jq" :posix "apt install jq"}}
   {:id "node" :names ["node" "node.exe"] :version ["--version"] :required? false
    :capability "the Electron client (not needed when driving the spine directly)"
    :hint {:windows "scoop install nodejs-lts" :posix "apt install nodejs"}}])

(defn- search-path
  "First executable named one of `names` on PATH (PATHEXT-aware on Windows)."
  [names]
  (let [path (or (System/getenv "PATH") "")
        dirs (remove str/blank? (str/split path (re-pattern (java.util.regex.Pattern/quote File/pathSeparator))))
        exts (if windows?
               (str/split (or (System/getenv "PATHEXT") ".EXE;.CMD;.BAT") #";")
               [""])]
    (some (fn [d]
            (some (fn [n]
                    (some (fn [e]
                            (let [f (io/file d (str n e))]
                              (when (and (.exists f) (.isFile f)
                                         (or windows? (.canExecute f)))
                                (.getAbsolutePath f))))
                          exts))
                  names))
          dirs)))

(defn- first-line [s]
  (->> (str/split-lines (or s ""))
       (map str/trim)
       (remove str/blank?)
       (first)
       (#(when % (subs % 0 (min 120 (count %)))))))

(defn- run-argv
  "Run argv, merged stdout/stderr, bounded wait. Never throws."
  [argv timeout-ms]
  (try
    (let [pb (doto (ProcessBuilder. ^java.util.List (mapv str argv))
               (.redirectErrorStream true))
          p (.start pb)
          done (.waitFor p timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
          out (try (with-open [r (io/reader (.getInputStream p))] (slurp r))
                   (catch Throwable _ ""))]
      {:exit (when done (try (.exitValue p) (catch Throwable _ nil))) :output out})
    (catch Throwable e {:exit nil :output (.getMessage e)})))

(defn- probe-dep [{:keys [id names version capability hint required?]}]
  (if-let [path (search-path names)]
    (let [{:keys [output]} (run-argv (into [path] version) 8000)]
      {:id id :status "ok" :path path :version (first-line output)
       :required? (boolean required?) :capability capability})
    {:id id :status "missing" :path nil :version nil
     :required? (boolean required?) :capability capability
     :hint (if windows? (:windows hint) (:posix hint))}))

(defn probe-deps [] (mapv probe-dep deps))

;; ---------------------------------------------------------------------------
;; Config: fragments, provenance, solvency
;; ---------------------------------------------------------------------------

(defn- candidate-files
  "Every grog.edn location we consider, in merge order, as [source path]."
  []
  (let [home (io/file (platform/config-home-dir) "grog.edn")
        legacy (io/file (System/getProperty "user.home") ".config" "grog" "grog.edn")
        ;; when config-home IS ~/.config/grog, the two entries are one file
        same? (= (.getPath home) (.getPath legacy))]
    (cond-> [[:classpath (some-> (io/resource "grog.edn") str)]
             [:home (.getPath home)]]
      (not same?) (conj [:legacy (.getPath legacy)]))))

(defn- parse-file
  "Parse one EDN file for real (NOT the silent loader): a broken file must be
  reported by file + message, not skipped as 'not loaded'."
  [source path]
  (when (and path (.exists (io/file path)))
    (try
      {:source source :path path :data (edn/read-string {:eof nil} (slurp (io/file path) :encoding "UTF-8"))}
      (catch Throwable e
        {:source source :path path :error (or (.getMessage e) (str (class e)))}))))

(defn scan-config
  "Parsed fragments (merge order) plus any file that failed to parse."
  []
  (let [parsed (keep (fn [[s p]] (parse-file s p)) (candidate-files))]
    {:fragments (vec (filter :data parsed))
     :broken (vec (filter :error parsed))}))

(defn- leaf-paths
  "All leaf paths in a nested map, e.g. [:llm :model]."
  [m]
  (letfn [(walk [prefix m]
            (if (and (map? m) (seq m))
              (mapcat (fn [[k v]] (walk (conj prefix k) v)) m)
              (when (seq prefix) [prefix])))]
    (walk [] m)))

(defn provenance
  "path -> {:file :source :value} for every effective leaf, last writer wins."
  [fragments]
  (reduce (fn [acc {:keys [source path data]}]
            (reduce (fn [a p] (assoc a p {:file path :source source :value (get-in data p)}))
                    acc (leaf-paths data)))
          {} fragments))

(def ^:private known-top-keys
  "Curated top-level grog.edn keys. A key that is not here is a likely typo."
  #{:llm :eca :appearance :secrets :babashka :chron :edn-store :jobs :soul :skills
    :cli :imap :odoo :memory :projects :server :with-api-key :models
    :project-search :voice :imaging :office :fetch :terminal :gitlab :alpaca
    :rules :allowlist :mcp})

(defn- url-problem? [s]
  (let [s (str/trim (str s))]
    (cond
      (str/blank? s) "empty url"
      (not (re-matches #"(?i)https?://[^\s/]+.*" s)) (str "not an http(s) url: " s)
      :else nil)))

(defn- model-problem? [s]
  (let [s (str/trim (str s))]
    (cond
      (str/blank? s) "empty model id"
      (str/includes? s " ") (str "model id contains a space: " s)
      (not (str/includes? s "/")) (str "model id has no `provider/model` form: " s)
      :else nil)))

(defn- value-problems
  "Known-unusable shapes, each as {:path [...] :file … :problem …}."
  [prov]
  (let [getp (fn [p] (get prov p))]
    (concat
     ;; :max-tokens is an :llm key; anywhere else it is a silent no-op
     (for [[p {:keys [file value]}] prov
           :when (and (= :max-tokens (last p)) (not= [:llm :max-tokens] p))]
       {:path p :file file :problem (str ":max-tokens outside :llm is ignored (got " (pr-str value) ")")})
     ;; model ids
     (for [p [[:llm :model] [:eca :model]]
           :let [{:keys [file value]} (getp p)]
           :when (some? value)
           :let [prob (model-problem? value)]
           :when prob]
       {:path p :file file :problem prob})
     ;; urls end in the key :url anywhere
     (for [[p {:keys [file value]}] prov
           :when (and (= :url (last p)) (string? value))
           :let [prob (url-problem? value)]
           :when prob]
       {:path p :file file :problem prob}))))

(defn config-report []
  (let [{:keys [fragments broken]} (scan-config)
        prov (provenance fragments)
        merged (reduce config/deep-merge {} (map :data fragments))
        unknown (for [k (keys merged) :when (not (contains? known-top-keys k))]
                  {:path [k] :problem "unknown top-level key (typo?)"
                   :file (:file (get prov [k]))})]
    {:fragments (mapv (fn [{:keys [source path data]}]
                        {:source source :path path :top-keys (vec (sort (map str (keys data))))})
                      fragments)
     :broken broken
     :provenance (->> prov
                      (map (fn [[p v]] {:path p :file (:file v) :source (:source v)}))
                      (sort-by (comp count :path))
                      vec)
     :problems (vec (concat (value-problems prov) unknown))}))

;; ---------------------------------------------------------------------------
;; Report
;; ---------------------------------------------------------------------------

(defn report []
  (let [deps (probe-deps)
        cfg (config-report)]
    {:platform {:os (System/getProperty "os.name") :windows? windows?
                :config-home (str (platform/config-home-dir))
                :user-home (System/getProperty "user.home")}
     :deps deps
     :config cfg
     :summary {:deps-ok (count (filter #(= "ok" (:status %)) deps))
               :deps-missing (vec (map :id (filter #(= "missing" (:status %)) deps)))
               :deps-missing-required (vec (map :id (filter #(and (= "missing" (:status %))
                                                                  (:required? %)) deps)))
               :config-files (count (:fragments cfg))
               :config-broken (count (:broken cfg))
               :config-problems (count (:problems cfg))}}))

(defn print-human! []
  (let [r (report)
        {:keys [deps config summary]} r]
    (println "grog doctor")
    (println (str "  " (:os (:platform r)) "   config home: " (get-in r [:platform :config-home])))
    (println)
    (println "Dependencies:")
    (doseq [{:keys [id status path version hint capability required?]} deps]
      (println (format "  %-10s %-7s %s%s" id (if (= status "ok") "ok" "MISSING")
                     (or path "")
                     (str (when version (str "   " version))
                          (when (and required? (= status "missing")) "   [required]"))))
      (println (str "             " capability))
      (when hint (println (str "             install: " hint))))
    (println)
    (println "Config files (merge order, later wins):")
    (doseq [{:keys [source path top-keys]} (:fragments config)]
      (println (format "  %-10s %s" (name source) path))
      (println (str "             keys: " (str/join " " top-keys))))
    (doseq [{:keys [source path error]} (:broken config)]
      (println (format "  %-10s %s" (name source) path))
      (println (str "             BROKEN: " error)))
    (when (seq (:problems config))
      (println)
      (println "Config problems:")
      (doseq [{:keys [path file problem]} (:problems config)]
        (println (format "  %-24s %s" (pr-str path) problem))
        (when file (println (str "             from " file)))))
    (println)
    (println (str "Summary: " (:deps-ok summary) "/" (count deps) " dependencies present"
                  (when (seq (:deps-missing summary)) (str "; missing: " (str/join ", " (:deps-missing summary))))
                  (when (seq (:deps-missing-required summary)) (str "; REQUIRED missing: " (str/join ", " (:deps-missing-required summary))))
                  "; " (:config-files summary) " config file(s)"
                  (when (pos? (:config-broken summary)) (str "; " (:config-broken summary) " BROKEN"))
                  "; " (:config-problems summary) " config problem(s)"))
    (println)
    (println "Tip: grog never sets `:llm :max-tokens` for ECA-driven turns; see doc/eca-output-limit-issue.md.")))

(defn -main [& args]
  (if (some #(= "--json" (str %)) args)
    (println (json/generate-string (report) {:pretty true}))
    (print-human!)))
