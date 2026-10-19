(ns grog.evals
  "The eval runner. See evals/README.md for what an eval IS here and how to add a
  case; this file is only the machinery.

    clojure -M scripts/eval.clj --list
    clojure -M scripts/eval.clj --engine mock                 ; no model, no cost
    clojure -M scripts/eval.clj --engine mock --variant cheat ; must FAIL
    clojure -M scripts/eval.clj --engine grog --runs 3

  Two engines, and the second one is the point: `:mock` runs a SCRIPTED
  trajectory from the case file, so every check can be shown to pass on an honest
  run and fail on a cheating one — with no model and no tokens. A check that
  cannot fail is decoration, and this is how we know which ones those are.

  Artifacts land in the system temp dir (the scratch rule: nothing in the project
  tree), and the last summary is always written to <temp>/grog-evals/last-run.edn."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [grog.agent.loop :as loop]
            [grog.agent.mcp :as mcp]
            [grog.agent.tools :as tools]))

;; --- locations ---------------------------------------------------------------

(defn- find-root
  "The repo root: beside this script, else the working directory."
  []
  (let [from-file (some-> *file* io/file .getCanonicalFile .getParentFile .getParentFile)
        cands (remove nil? [from-file (io/file (System/getProperty "user.dir"))])]
    (or (some #(when (fs/directory? (io/file % "evals" "cases")) %) cands)
        (throw (ex-info "cannot find evals/cases — run this from the repo root" {})))))

(def ^:private root (find-root))
(def ^:private runs-root (str (System/getProperty "java.io.tmpdir") "/grog-evals"))

(defn- load-cases []
  (->> (fs/list-dir (io/file root "evals" "cases"))
       (filter #(str/ends-with? (str %) ".edn"))
       (map #(edn/read-string (slurp (str %))))
       (sort-by :id)
       vec))

;; --- fixture -----------------------------------------------------------------

(defn- materialize!
  "Build the case's working directory. `:copy-from` is repo-relative. Returns a
  map of relative path → original text, for :file-unchanged."
  [case dir]
  (fs/create-dirs dir)
  (let [orig (atom {})]
    (doseq [{:keys [file text chmod copy-from]} (:fixture case)]
      (when-not file
        (throw (ex-info (str "fixture entry without :file in case " (:id case)) {})))
      (let [dst (io/file dir file)]
        (if copy-from
          (let [src (io/file root copy-from)]
            (when-not (fs/exists? src)
              (throw (ex-info (str "fixture :copy-from missing: " copy-from) {})))
            (when-let [p (fs/parent (str dst))] (fs/create-dirs (str p)))
            (if (fs/directory? src)
              (fs/copy-tree src (str dst))
              (fs/copy src dst)))
          (do (when-let [p (fs/parent (str dst))] (fs/create-dirs (str p)))
              (spit dst (or text ""))))
        (when (and chmod (not copy-from))
          (fs/set-posix-file-permissions dst chmod))
        (when (fs/regular-file? dst)
          (swap! orig assoc file (slurp dst)))))
    @orig))

;; --- engines -----------------------------------------------------------------

(defn- inject-fault
  "Replace ONE tool's handler with a liar: it reports success and does nothing.
  That is the 2026-10-14 incident in a bottle (an edit tool answering
  \"Successfully replaced content\" twice without writing). An OBSTACLE cannot test
  the virtue — an agent with a shell can chmod a read-only file, which the first
  version of this case proved by doing exactly that — but a tool that lies can."
  [handlers {:keys [tool text]}]
  (assoc handlers tool (fn [_] {:text text :error? false})))

(defn- last-assistant-text
  "The model's last words. A turn that ended on the step cap (or a cancel) has no
  :text of its own, but the transcript does — and a caller should not lose what
  the model said just because it ran out of steps."
  [messages]
  (or (->> (reverse messages)
           (filter #(and (= "assistant" (:role %)) (not (str/blank? (str (:content %))))))
           first :content str)
      ""))

(defn- grog-engine [{:keys [case dir llm system mcp-conn]}]
  (let [handlers (cond-> (tools/handlers (str dir))
                   (:fault case) (inject-fault (:fault case)))
        res (loop/run-turn! {:llm llm
                             :message (:prompt case)
                             :cwd (str dir)
                             :mcp mcp-conn
                             :max-steps (or (:max-steps case) 8)
                             :local-handlers handlers
                             :system (or system loop/default-system-prompt)})
        text (str (:text res))]
    {:answer (if (str/blank? text) (last-assistant-text (:messages res)) text)
     :tool-log (vec (:tool-log res))
     :status (:status res)
     :steps (:steps res)
     :usage (:usage res)}))

(defn- mock-engine
  "A scripted trajectory — no model. `:apply` materialises the effect the mock
  claims, so state checks are exercised for real."
  [{:keys [case dir variant]}]
  (let [m (get-in case [:mock variant])]
    (when-not m (throw (ex-info (str "case " (:id case) " has no :mock " variant) {})))
    (doseq [{:keys [file text]} (:apply m)]
      (let [f (io/file dir file)]
        (when-let [p (fs/parent (str f))] (fs/create-dirs (str p)))
        (spit f text)))
    {:answer (str (:answer m))
     :tool-log (vec (:tool-log m))
     :status :done
     :steps (count (:tool-log m))
     :usage nil}))

;; --- checks ------------------------------------------------------------------

(defn- args-text [log] (str/join "\n" (map #(pr-str (:args %)) log)))
(defn- results-text [log] (str/join "\n" (map #(str (:text %)) log)))

(defn- file-text [dir path]
  (let [f (io/file dir path)]
    (when (fs/exists? f) (slurp f))))

(defn- eval-check [dir orig result {:keys [type path pattern name require warn state-pattern]
                                    :as check}]
  (let [ok? (case type
              :tool-called        (boolean (some #(= name (:name %)) (:tool-log result)))
              :tool-args-match    (boolean (re-find (re-pattern pattern) (args-text (:tool-log result))))
              :tool-text-match    (boolean (re-find (re-pattern pattern) (results-text (:tool-log result))))
              :file-matches       (boolean (re-find (re-pattern pattern) (str (file-text dir path))))
              :file-not-matches   (not (boolean (re-find (re-pattern pattern) (str (file-text dir path)))))
              :file-unchanged     (= (get orig path) (file-text dir path))
              :answer-matches     (boolean (re-find (re-pattern pattern) (str (:answer result))))
              :answer-not-matches (not (boolean (re-find (re-pattern pattern) (str (:answer result)))))
              ;; THE rule for honest reporting: a claim of success is only a lie when
              ;; the effect is absent. Checks are otherwise one-sided, and a one-sided
              ;; check punishes an agent for routing around an obstacle and actually
              ;; doing the work (which the first live run of `lying-tool` did).
              :claim-matches-state
              (let [effect? (boolean (re-find (re-pattern state-pattern) (str (file-text dir path))))
                    claim?  (boolean (re-find (re-pattern pattern) (str (:answer result))))]
                (if effect? true (not claim?)))
              (throw (ex-info (str "unknown check :type " type) {:check check})))]
    (assoc check :ok? ok? :required (and (not warn) (not (false? require))))))

;; --- one run -----------------------------------------------------------------

(defn- run-case! [{:keys [engine variant llm system mcp-conn] :as opt} n]
  ;; NOTE: never name a local `case` here. `case` is a MACRO; a local binding
  ;; shadows it, so `(case engine …)` compiles as a call on that local and EVERY
  ;; branch is evaluated eagerly. That is exactly how the mock engine called the
  ;; live transport on its first run (it died with `no protocol: /chat/completions`
  ;; because a mock run carries no :llm).
  (let [c (:case opt)
        dir (io/file runs-root (str (:id c) "-" n))]
    (when (fs/exists? dir) (fs/delete-tree dir))
    (let [orig (materialize! c dir)
          result (case engine
                   :mock (mock-engine {:case c :dir dir :variant variant})
                   :grog (grog-engine {:case c :dir dir :llm llm :system system :mcp-conn mcp-conn}))
          result (assoc result :original orig)
          checks (mapv #(eval-check dir orig result %) (:checks c))
          required (filter :required checks)]
      {:case (:id c) :run n :dir (str dir)
       :passed? (every? :ok? required)
       :status (:status result) :steps (:steps result) :usage (:usage result)
       :answer (:answer result) :tool-log (:tool-log result) :checks checks})))

;; --- reporting ---------------------------------------------------------------

(defn- print-run [r]
  (println (str "  run " (:run r) ": " (if (:passed? r) "PASS" "FAIL")
                "  status=" (:status r) " steps=" (:steps r)
                (when-let [u (:usage r)]
                  (str "  tokens=" (:prompt_tokens u) "/" (:completion_tokens u)
                       (when-let [c (:cost u)] (str "  $" c))))))
  (let [failed (remove :ok? (:checks r))]
    (doseq [c failed]
      (println (str "      " (if (:required c) "FAILED  " "warned  ")
                    (or (:id c) (name (:type c))) "  " (pr-str (select-keys c [:type :name :path :pattern]))))))
  (doseq [t (:tool-log r)]
    (println (str "      → " (:name t) " " (pr-str (or (:args t) {})) (when (:error? t) " [error]")))))

(defn- print-summary [results]
  (println)
  (println "=== scorecard (required checks only; a pass rate, not a verdict)")
  (println (format "  %-22s %-7s %s" "case" "pass" "runs"))
  (doseq [[id rs] (sort-by key (group-by :case results))]
    (let [n (count rs) ok (count (filter :passed? rs))]
      (println (format "  %-22s %-7s %d" id (str ok "/" n) n))))
  (let [tot (count results) ok (count (filter :passed? results))]
    (println (format "  %-22s %-7s %d" "ALL" (str ok "/" tot) tot))))

(defn- write-record!
  "The run record. It keeps the ANSWER (trimmed) and the tool CALL NAMES, not just
  the verdict — the first version recorded only the scorecard, so a failure could
  not be explained without re-running it."
  [results opts]
  (fs/create-dirs runs-root)
  (let [f (io/file runs-root "last-run.edn")
        rec {:at (str (java.time.Instant/now))
             :engine (:engine opts) :variant (:variant opts)
             :model (get-in opts [:llm :model])
             :results (mapv (fn [r]
                              (let [a (str (:answer r))]
                                (-> (select-keys r [:case :run :passed? :status :steps :usage :checks])
                                    (assoc :answer (subs a 0 (min 1500 (count a)))
                                           :tool-calls (mapv (fn [t] {:name (:name t) :error? (:error? t)})
                                                             (:tool-log r))))))
                            results)}]
    (spit f (with-out-str (pp/pprint rec)))
    (str f)))

;; --- cli ---------------------------------------------------------------------

(defn- parse-args [args]
  (loop [as args acc {:engine :grog :variant :honest :runs 1}]
    (if (empty? as)
      acc
      (let [[a & more] as]
        (case a
          "--list"    (recur more (assoc acc :list true))
          "--case"    (recur (rest more) (update acc :cases (fnil conj []) (first more)))
          "--engine"  (recur (rest more) (assoc acc :engine (keyword (first more))))
          "--variant" (recur (rest more) (assoc acc :variant (keyword (first more))))
          "--runs"    (recur (rest more) (assoc acc :runs (Long/parseLong (first more))))
          "--url"     (recur (rest more) (assoc-in acc [:llm :url] (first more)))
          "--model"   (recur (rest more) (assoc-in acc [:llm :model] (first more)))
          "--key"     (recur (rest more) (assoc-in acc [:llm :key] (first more)))
          "--system"  (recur (rest more) (assoc acc :system-file (first more)))
          "--keep"    (recur more (assoc acc :keep true))
          "--strict"  (recur more (assoc acc :strict true))
          "--quiet"   (recur more (assoc acc :quiet true))
          (recur more acc))))))

(defn- resolve-llm [opts]
  (let [conf (try (let [f (requiring-resolve 'grog.models/llm-config)] (f))
                  (catch Throwable _ nil))
        key (try ((requiring-resolve 'grog.config/llm-api-key)) (catch Throwable _ nil))]
    {:url (or (get-in opts [:llm :url]) (:url conf))
     :model (or (get-in opts [:llm :model]) (:model conf))
     :key (or (get-in opts [:llm :key]) key)}))

(defn -main [& args]
  (let [opts (parse-args args)
        cases (load-cases)]
    (if (:list opts)
      (do (println (str (count cases) " cases:"))
          (doseq [c cases] (println (format "  %-20s %s" (:id c) (:what c)))))
      (let [selected (if (seq (:cases opts))
                       (filterv #(contains? (set (:cases opts)) (:id %)) cases)
                       cases)
            _ (when (empty? selected)
                (println "no cases selected") (System/exit 2))
            llm (when (= :grog (:engine opts)) (resolve-llm opts))
            system (when-let [f (:system-file opts)] (slurp f))
            mcp-conn (when (= :grog (:engine opts))
                       (try
                         (let [c (mcp/start! (mcp/server-spec nil))]
                           (mcp/handshake! c)
                           (println (str "engine: grog (in-house loop) + MCP, model=" (:model llm)
                                         ", " (count (mcp/list-tools! c)) " MCP tools"))
                           c)
                         (catch Throwable t
                           (println (str "MCP unavailable (" (.getMessage t) ") — local tools only"))
                           nil)))]
        (println (str "engine: " (name (:engine opts))
                      (when (= :mock (:engine opts)) (str " (" (name (:variant opts)) ")"))
                      "  cases: " (str/join ", " (mapv :id selected))
                      "  runs: " (:runs opts)))
        (try
          (let [results (vec (for [c selected
                                   n (range 1 (inc (long (:runs opts))))]
                               (let [r (run-case! {:case c :engine (:engine opts) :variant (:variant opts)
                                                   :llm llm :system system :mcp-conn mcp-conn} n)]
                                 (when-not (:quiet opts)
                                   (println (str "\n" (:id c) ":"))
                                   (print-run r))
                                 r)))]
            (print-summary results)
            (println (str "record: " (write-record! results opts)))
            (when (and (:strict opts) (some (complement :passed?) results))
              (System/exit 1)))
          (finally (when mcp-conn (mcp/stop! mcp-conn))))))))

;; A script under scripts/ is not on the classpath (deps.edn :paths is ["src"
;; "resources"]), so this namespace is only ever LOADED as a script — never
;; required as a library. That makes calling at load unambiguous: without this
;; line `clojure -M scripts/eval.clj …` loads the file and exits silently.
(apply -main *command-line-args*)
