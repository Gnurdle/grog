(ns grog.skills
  "Skills live under workspace-relative `:skills :roots` (default `skills/`). Each skill is one directory:

    <root>/<skill-dir>/skill.edn   — metadata only
    <root>/<skill-dir>/SKILL.md    — full instructions (body for read_skill)

  Paths in grog.edn are relative to `:workspace :default-root` unless absolute (still must stay under root).

  New skills are written under the **first** entry of `:skills :roots` (see `primary-write-root-file!`)."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.config :as cfg])
  (:import (java.io File)))

(defn- workspace-root-path ^java.nio.file.Path []
  (-> ^File (.getCanonicalFile (io/file (cfg/workspace-root)))
      .toPath
      .normalize
      .toAbsolutePath))

(defn- resolve-under-workspace!
  "Canonical File for path relative to workspace or absolute under workspace."
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

(def ^:private skill-md-filename "SKILL.md")
(def ^:private skill-edn-filename "skill.edn")

(defn- parse-skill-tool-args [arguments]
  (cond (map? arguments) arguments
        (string? arguments) (try (json/parse-string arguments true) (catch Exception _ {}))
        :else {}))

(defn- skill-id-valid? [^String s]
  (boolean (and s
                (not (str/blank? s))
                (<= (count s) 64)
                (re-matches #"[a-zA-Z0-9][a-zA-Z0-9_-]*" s))))

(defn- primary-write-root-file!
  "First `:skills :root` resolved under workspace; created if missing."
  ^File []
  (let [roots (cfg/skills-roots)]
    (when (empty? roots)
      (throw (ex-info "no skills roots configured" {})))
    (let [^File root (resolve-under-workspace! (first roots))]
      (.mkdirs root)
      root)))

(defn- skill-home-for-id!
  "Canonical directory `<first-root>/<id>`; must be a direct child of the root."
  ^File [^String id]
  (let [^File base (primary-write-root-file!)
        ^File sh (.getCanonicalFile (io/file base id))
        bp (-> sh .getParentFile .getCanonicalFile)]
    (when-not (= bp (.getCanonicalFile base))
      (throw (ex-info "skill id resolves outside skills root" {:id id})))
    sh))

(defn- normalize-tags-arg [x]
  (vec (distinct (for [t (cond (sequential? x) x (nil? x) [] :else [x])]
                   (str/trim (str t))))))

(defn- delete-file-recursive! [^File f]
  (when (.exists f)
    (if (.isDirectory f)
      (when-let [xs (.listFiles f)]
        (doseq [^File c xs]
          (delete-file-recursive! c)))
      (.delete f)))
  (when (.exists f)
    (.delete f)))

(defn- id-str [x]
  (str/trim (str (or x ""))))

(defn- desc-str [x]
  (str/trim (str (or x ""))))

(defn- parse-skill-edn!
  "Returns {:ok true :rec ...} or {:ok false :error ...}. Body always comes from SKILL.md in skill-home."
  [^File skill-edn-file ^File skill-home data]
  (if-not (map? data)
    {:ok false :error "skill.edn must be a map"}
    (let [id (id-str (or (:id data) (get data "id")))
          desc (desc-str (or (:description data) (get data "description")))
          title (or (some-> (or (:title data) (get data "title")) str str/trim not-empty) id)
          tags (vec (for [t (or (:tags data) (get data "tags") [])]
                      (str/trim (str t))))]
      (cond
        (str/blank? id)
        {:ok false :error ":id is required"}

        (str/blank? desc)
        {:ok false :error ":description is required"}

        :else
        {:ok true
         :rec {:id id
               :title title
               :description desc
               :tags tags
               :skill-home skill-home
               :skill-edn-path (.getPath skill-edn-file)}}))))

(defn- slurp-skill-file! [^File f]
  (when (and f (.exists f) (.isFile f))
    (slurp f :encoding "UTF-8")))

(defn- discover-in-root!
  "Each immediate subdirectory with skill.edn + SKILL.md."
  [^File root-dir]
  (if-not (and (.exists root-dir) (.isDirectory root-dir))
    []
    (let [^File arr (or (.listFiles root-dir) (make-array File 0))
          out (java.util.ArrayList.)]
      (doseq [^File ch arr]
        (when (and (.isDirectory ch)
                   (.exists (io/file ch "skill.edn"))
                   (.exists (io/file ch skill-md-filename)))
          (let [md-f (io/file ch skill-md-filename)
                md-preview (slurp-skill-file! md-f)]
            (if (str/blank? (str/trim (or md-preview "")))
              (.add out {:ok false
                         :error "SKILL.md is empty"
                         :path (.getPath md-f)})
              (let [edn-f (io/file ch "skill.edn")]
                (when-some [raw (try (edn/read-string {:eof nil} (or (slurp-skill-file! edn-f) ""))
                                     (catch Exception e
                                       (.add out {:ok false
                                                  :error (str "invalid EDN: " (.getMessage e))
                                                  :path (.getPath edn-f)})
                                       nil))]
                  (.add out (parse-skill-edn! edn-f ch raw))))))))
      (vec out))))

(defn- root-relative-path [^File abs-file]
  (let [root (workspace-root-path)
        ap (-> abs-file .getCanonicalFile .toPath .normalize)]
    (if (= ap root)
      "."
      (if (.startsWith ap root)
        (-> root (.relativize ap) .toString)
        (.getPath abs-file)))))

(defn discover-skill-records!
  "Scan configured :skills :roots. Returns {:by-id {id rec} :errors [{:path :error}] :roots [...]}.
  rec has :id :title :description :tags :skill-home :skill-edn-path (internal)."
  []
  (let [errors (volatile! [])
        by-id (volatile! {})]
    (doseq [root-str (cfg/skills-roots)]
      (try
        (let [root (resolve-under-workspace! root-str)]
          (doseq [item (discover-in-root! root)]
            (if (:ok item)
              (let [rec (:rec item)
                    id (:id rec)]
                (if (contains? @by-id id)
                  (vswap! errors conj {:path (:skill-edn-path rec)
                                       :error (str "duplicate skill id " (pr-str id) " — keeping first")})
                  (vswap! by-id assoc id rec)))
              (vswap! errors conj {:path (:path item "unknown")
                                   :error (:error item "invalid skill")}))))
        (catch Exception e
          (vswap! errors conj {:path root-str :error (.getMessage e)}))))
    {:by-id @by-id
     :errors @errors
     :roots (vec (cfg/skills-roots))}))

(defn- skill-body-text!
  [rec max-chars]
  (let [^File home (:skill-home rec)
        ^File md (io/file home skill-md-filename)
        combined (when (and (.exists md) (.isFile md))
                   (slurp md :encoding "UTF-8"))]
    (if (str/blank? (str/trim (or combined "")))
      {:ok false :error "SKILL.md missing or empty"}
      (let [n (count combined)]
        (if (<= n max-chars)
          {:ok true :body combined :truncated false}
          {:ok true :body (subs combined 0 max-chars) :truncated true
           :truncated_from_chars n})))))

(defn list-skills-tool-spec []
  {:type "function"
   :function
   {:name "list_skills"
    :description (str "List discoverable skills under :skills :roots (each directory has skill.edn + SKILL.md). "
                      "Returns id, title, description, tags, workspace-relative path. "
                      "Call read_skill for full SKILL.md; after save_skill, call list_skills to confirm.")
    :parameters {:type "object"
                 :properties {}}}})

(defn read-skill-tool-spec []
  {:type "function"
   :function
   {:name "read_skill"
    :description (str "Load the full text of one skill by :id (from list_skills). "
                      "Returns title, description, tags, and body (markdown/instructions). "
                      "Before updating a skill with save_skill, call read_skill for that id. "
                      "Large bodies may be truncated per :skills :max-body-chars in grog.edn.")
    :parameters {:type "object"
                 :required ["id"]
                 :properties {:id {:type "string"
                                   :description "Skill id exactly as returned by list_skills."}}}}})

(defn save-skill-tool-spec []
  {:type "function"
   :function
   {:name "save_skill"
    :description (str "Create or overwrite a skill: writes skill.edn and SKILL.md under the **first** :skills :root "
                      "(see system prompt). For updates, call read_skill first, then send the full skill_md body.")
    :parameters {:type "object"
                 :required ["id" "description" "skill_md"]
                 :properties {:id {:type "string"
                                   :description "Directory name: [a-zA-Z0-9][a-zA-Z0-9_-]*, max 64 chars."}
                              :description {:type "string"
                                            :description "Short summary for discovery (stored in skill.edn)."}
                              :title {:type "string"
                                      :description "Optional display title (defaults to id)."}
                              :tags {:type "array"
                                     :items {:type "string"}
                                     :description "Optional tags."}
                              :skill_md {:type "string"
                                         :description "Full SKILL.md body (markdown instructions)."}}}}})

(defn delete-skill-tool-spec []
  {:type "function"
   :function
   {:name "delete_skill"
    :description (str "Remove a skill directory under the first :skills :root. "
                      "Only deletes if the folder contains skill.edn and/or SKILL.md (refuses arbitrary dirs).")
    :parameters {:type "object"
                 :required ["id"]
                 :properties {:id {:type "string"
                                   :description "Skill id (directory name under the skills root)."}}}}})

(defn run-list-skills!
  [_arguments]
  (try
    (if-not (cfg/skills-configured?)
      (json/generate-string {:error "skills not configured"
                             :hint "Set :skills {:roots [\"skills\"]} in grog.edn (under :workspace :default-root)."})
      (let [{:keys [by-id errors roots]} (discover-skill-records!)
            skills (->> (vals by-id)
                        (sort-by (comp str/lower-case :id))
                        (mapv (fn [r]
                                {:id (:id r)
                                 :title (:title r)
                                 :description (:description r)
                                 :tags (:tags r)
                                 :path (root-relative-path (:skill-home r))})))]
        (json/generate-string
         {:skills skills
          :count (count skills)
          :roots roots
          :errors (when (seq errors) errors)})))
    (catch Exception e
      (json/generate-string {:error (.getMessage e) :detail (str e)}))))

(defn run-read-skill!
  [arguments]
  (try
    (if-not (cfg/skills-configured?)
      (json/generate-string {:error "skills not configured"})
      (let [m (parse-skill-tool-args arguments)
            id (id-str (or (:id m) (get m "id")))
            max-chars (cfg/skills-max-body-chars)]
        (if (str/blank? id)
          (json/generate-string {:error "id is required"})
          (let [{:keys [by-id]} (discover-skill-records!)
                rec (get by-id id)]
            (if-not rec
              (json/generate-string {:error "unknown skill id" :id id})
              (let [{:keys [ok body truncated truncated_from_chars error]}
                    (skill-body-text! rec max-chars)]
                (if ok
                  (json/generate-string
                   (cond-> {:id (:id rec)
                            :title (:title rec)
                            :description (:description rec)
                            :tags (:tags rec)
                            :body body}
                     truncated (assoc :truncated true
                                      :max_body_chars max-chars
                                      :original_char_count truncated_from_chars)))
                  (json/generate-string {:error error :id id}))))))))
    (catch Exception e
      (json/generate-string {:error (.getMessage e) :detail (str e)}))))

(defn run-save-skill!
  [arguments]
  (try
    (if-not (cfg/skills-configured?)
      (json/generate-string {:error "skills not configured"
                             :hint "Set :skills {:roots [\"skills\"]} in grog.edn (under :workspace :default-root)."})
      (let [m (parse-skill-tool-args arguments)
            id (id-str (or (:id m) (get m "id")))
            desc (desc-str (or (:description m) (get m "description")))
            title-raw (or (:title m) (get m "title"))
            title (when-not (str/blank? (str/trim (str title-raw)))
                    (str/trim (str title-raw)))
            tags (normalize-tags-arg (or (:tags m) (get m "tags")))
            skill-md (str (or (:skill_md m) (get m "skill_md")
                              (:skillMd m) (get m "skillMd") ""))
            max-ch (cfg/skills-max-body-chars)]
        (cond
          (not (skill-id-valid? id))
          (json/generate-string {:error "invalid skill id"
                                 :hint "Use [a-zA-Z0-9][a-zA-Z0-9_-]*, max 64 characters."})

          (str/blank? desc)
          (json/generate-string {:error "description is required"})

          (str/blank? (str/trim skill-md))
          (json/generate-string {:error "skill_md is required"})

          (> (count skill-md) max-ch)
          (json/generate-string {:error "skill_md too large"
                                 :max_body_chars max-ch
                                 :char_count (count skill-md)})

          :else
          (let [^File sh (skill-home-for-id! id)
                final-title (or title id)
                edn-m (array-map :id id :title final-title :description desc :tags tags)
                edn-f (io/file sh skill-edn-filename)
                md-f (io/file sh skill-md-filename)]
            (.mkdirs sh)
            (spit edn-f (str (pr-str edn-m) "\n") :encoding "UTF-8")
            (spit md-f skill-md :encoding "UTF-8")
            (json/generate-string {:ok true :id id :path (root-relative-path sh)})))))
    (catch Exception e
      (json/generate-string {:error (.getMessage e) :detail (str e)}))))

(defn run-delete-skill!
  [arguments]
  (try
    (if-not (cfg/skills-configured?)
      (json/generate-string {:error "skills not configured"})
      (let [m (parse-skill-tool-args arguments)
            id (id-str (or (:id m) (get m "id")))]
        (cond
          (str/blank? id)
          (json/generate-string {:error "id is required"})

          (not (skill-id-valid? id))
          (json/generate-string {:error "invalid skill id"})

          :else
          (let [^File sh (skill-home-for-id! id)
                edn-f (io/file sh skill-edn-filename)
                md-f (io/file sh skill-md-filename)]
            (cond
              (not (.exists sh))
              (json/generate-string {:error "not found" :id id})

              (not (.isDirectory sh))
              (json/generate-string {:error "path is not a directory" :id id})

              (not (or (.exists edn-f) (.exists md-f)))
              (json/generate-string {:error "refusing delete"
                                     :hint "Folder must contain skill.edn and/or SKILL.md."
                                     :id id})

              :else
              (do
                (delete-file-recursive! sh)
                (json/generate-string {:ok true :id id})))))))
    (catch Exception e
      (json/generate-string {:error (.getMessage e) :detail (str e)}))))

(defn- truncate-desc [^String s lim]
  (let [s (str/trim (str s))]
    (if (<= (count s) lim)
      s
      (str (subs s 0 (max 0 (- lim 1))) "…"))))

(defn system-prompt-block
  "System text: authoring tools + optional inventory; nil if skills disabled."
  []
  (when (cfg/skills-configured?)
    (try
      (let [write-rel (try (root-relative-path (primary-write-root-file!))
                           (catch Exception _ "first :skills :root"))
            {:keys [by-id errors]} (discover-skill-records!)
            lim (cfg/skills-prompt-skill-lines)
            skills (->> (vals by-id)
                        (sort-by (comp str/lower-case :id))
                        vec)
            lines (for [r (take lim skills)]
                    (str "- **" (:id r) "** — " (truncate-desc (:description r) 140)))
            inventory (if (seq skills)
                        (str "### Installed skills (sample)\n\n"
                             (str/join "\n" lines)
                             (when (> (count skills) lim)
                               (str "\n\n(" (- (count skills) lim) " more — call list_skills.)"))
                             (when (seq errors)
                               (str "\n\n(Note: " (count errors) " path(s) failed to load; list_skills includes :errors.)")))
                        "### Installed skills\n\n_(No valid skills discovered yet under configured roots.)_")]
        (str "## Skills\n\n"
             "Each skill is a directory with `skill.edn` (metadata) and `SKILL.md` (instructions), under `:skills :roots` in grog.edn. "
             "**New and updated skills are written only under the first root:** `" write-rel "/<id>/`.\n\n"
             "### Tools\n\n"
             "- **list_skills** — ids, descriptions, workspace-relative paths.\n"
             "- **read_skill** — full SKILL.md; **call this before save_skill** when editing an existing skill.\n"
             "- **save_skill** — create or replace `skill.edn` + SKILL.md (`id`, `description`, `skill_md`; optional `title`, `tags`).\n"
             "- **delete_skill** — remove a skill directory (only if it contains skill.edn and/or SKILL.md).\n\n"
             "Use list_skills/read_skill when a skill matches the user's task. Use save_skill/delete_skill when they want to add, change, or remove a reusable playbook; after save_skill, call list_skills to confirm.\n\n"
             inventory
             "\n"))
      (catch Exception e
        (str "## Skills\n\n(Skills are configured but inventory failed: " (.getMessage e) ")\n\n"
             "You still have **list_skills**, **read_skill**, **save_skill**, and **delete_skill**; new files go under the first `:skills :root`.\n")))))

(defn print-cli-summary!
  "Human-readable skill list for chat `/skills`."
  []
  (if-not (cfg/skills-configured?)
    (do (println "Skills are not configured.")
        (println "Add :skills {:roots [\"skills\"]} to grog.edn (under :workspace :default-root)."))
    (try
      (let [{:keys [by-id errors roots]} (discover-skill-records!)
            rows (->> (vals by-id) (sort-by (comp str/lower-case :id)) vec)]
        (if (empty? rows)
          (do (println "No skills found under roots:" (str/join ", " roots))
              (println "Add one directory per skill with skill.edn + SKILL.md inside each."))
          (do (println "EDN skills (Ollama tools list_skills / read_skill; same data). Roots:" (str/join ", " roots))
              (doseq [r rows]
                (println " " (:id r) "—" (:title r))
                (println "   " (truncate-desc (:description r) 220))
                (when (seq (:tags r))
                  (println "   tags:" (str/join ", " (:tags r))))
                (println "   path:" (root-relative-path (:skill-home r))))))
        (when (seq errors)
          (println "Load warnings:")
          (doseq [e errors]
            (println " " (pr-str (:path e)) "-" (:error e)))))
      (catch Exception e
        (println "Skills error:" (.getMessage e))))))

(defn print-cli-skill-body!
  "Print full skill instructions for chat `/skills <id>`."
  [raw-id]
  (if-not (cfg/skills-configured?)
    (println "Skills not configured.")
    (try
      (let [sid (id-str raw-id)
            {:keys [by-id]} (discover-skill-records!)
            rec (get by-id sid)]
        (cond
          (str/blank? sid)
          (println "Usage: /skills — list skills; /skills <id> — print full body for that id.")

          (nil? rec)
          (println "Unknown skill id:" (pr-str sid) "— use /skills to list.")

          :else
          (let [{:keys [ok body error truncated truncated_from_chars]}
                (skill-body-text! rec (cfg/skills-max-body-chars))]
            (if-not ok
              (println "Could not load body:" (or error "?"))
              (do (println (str "--- " (:title rec) " [" (:id rec) "] ---"))
                  (when truncated
                    (println "(truncated to" (cfg/skills-max-body-chars) "chars; original length" truncated_from_chars ")"))
                  (println)
                  (println body))))))
      (catch Exception e
        (println "Skills error:" (.getMessage e))))))

(defn startup-status-line []
  (if-not (cfg/skills-configured?)
    "Skills: off — set :skills {:roots [\"skills\"]} (under workspace); each skill dir needs skill.edn + SKILL.md"
    (try
      (let [{:keys [by-id errors]} (discover-skill-records!)]
        (str "Skills: " (count by-id) " in " (count (cfg/skills-roots)) " root(s)"
             " · tools: list/read/save/delete_skill"
             (when (seq errors) (str " · " (count errors) " load warning(s)"))))
      (catch Exception e
        (str "Skills: error — " (.getMessage e))))))
