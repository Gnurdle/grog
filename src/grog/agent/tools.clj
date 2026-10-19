(ns grog.agent.tools
  "grog's OWN coding tools — the replacement for the ECA tools every grog session
  leans on (read_file, write_file, edit_file, list_dir, grep, shell).

  The design point is `edit_file`. Today's incident: an edit tool answered
  \"Successfully replaced content\" twice and changed nothing, and the only reason
  it was caught is that the caller went back and looked (`mistake.2026-10-14T…​
  eca-edit-file-silent-noop`). A tool that lies is worse than a tool that fails,
  because a failure is visible. So every write here RE-READS the file and reports
  what it found: `verified: true` plus a diff, or a hard error carrying the file's
  actual content where the edit was supposed to land. \"Success\" is not a claim,
  it is a measurement.

  Handlers return {:text <string for the model> :error? <bool>} and nothing else;
  the loop turns that into a tool result. Output is clipped with an explicit
  marker, never silently.

  Nothing here shells out except `grep` (ripgrep when present) and `shell` — and
  those two are the only ones with a timeout, because they are the only ones that
  can hang."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.fs.walk :as walk])
  (:import (java.nio.file FileSystems LinkOption Path Paths)
           (java.util.regex Pattern)
           (java.util.concurrent TimeUnit)))

;; --- output clipping --------------------------------------------------------

(def ^:private max-lines 800)
(def ^:private max-chars 24000)

(defn clip
  "Cut `s` to size WITH a marker saying so. A silent truncation reads as a
  complete answer, which is how a model ends up asserting half a file."
  [s]
  (let [s (str s)
        lines (str/split-lines s)
        by-lines (if (> (count lines) max-lines)
                   (str (str/join "\n" (take max-lines lines))
                        "\n… truncated: " (- (count lines) max-lines) " more line(s)")
                   s)]
    (if (> (count by-lines) max-chars)
      (str (subs by-lines 0 max-chars) "\n… truncated: " (- (count by-lines) max-chars) " more char(s)")
      by-lines)))

(defn- ok [s] {:text (clip s) :error? false})
(defn- bad [s] {:text (clip s) :error? true})

(defn- expand
  "~ and relative paths resolve against `cwd` (the loop's working directory)."
  [cwd p]
  (let [p (str p)
        p (cond (= p "~") (System/getProperty "user.home")
                (str/starts-with? p "~/") (str (System/getProperty "user.home") "/" (subs p 2))
                :else p)]
    (-> (if (fs/absolute? p) (fs/file p) (fs/file (or cwd ".") p))
        fs/normalize
        str)))

(defn- read-text [path]
  (let [f (io/file path)]
    (when-not (.exists f) (throw (ex-info (str "no such file: " path) {:path path})))
    (when (.isDirectory f) (throw (ex-info (str "is a directory: " path) {:path path})))
    (slurp f :encoding "UTF-8")))

;; --- the tool table ---------------------------------------------------------

(defn read-file* [{:keys [cwd path offset limit]}]
  (let [p (expand cwd path)
        text (read-text p)
        lines (str/split-lines text)
        off (max 0 (dec (long (or offset 1))))
        n (long (or limit (count lines)))
        slice (take n (drop off lines))]
    (ok (str p " (" (count lines) " lines)\n" (str/join "\n" slice)))))

(defn write-file* [{:keys [cwd path content]}]
  (let [p (expand cwd path)
        want (str content)]
    (when (fs/directory? p) (throw (ex-info (str "is a directory: " p) {:path p})))
    (when-let [parent (fs/parent p)] (fs/create-dirs parent))
    (spit p want :encoding "UTF-8")
    ;; the verification: never report a write you have not read back
    (let [got (read-text p)]
      (if (= want got)
        (ok (str "wrote " (count want) " chars to " p " — verified by reading it back"))
        (bad (str "wrote " p " but the file does NOT read back as written: "
                  (count want) " chars written, " (count got) " read. First difference at "
                  (let [i (first (keep-indexed (fn [i c] (when (not= c (nth got i nil)) i)) want))]
                    (str "char " i))))))))

(defn edit-file* [{:keys [cwd path original_content new_content all_occurrences]}]
  (let [p (expand cwd path)
        old (str original_content)
        new (str new_content)]
    (if (str/blank? old)
      (bad "edit_file needs a non-empty original_content anchor")
      (let [text (read-text p)
            hits (loop [from 0 acc []]
                   (if-let [i (str/index-of text old from)]
                     (recur (+ i (count old)) (conj acc i))
                     acc))
            n (count hits)]
        (cond
          (zero? n)
          (bad (str "anchor NOT found in " p ". Nothing was written.\n"
                    "The anchor must match the file BYTE FOR BYTE (read the file first). "
                    "First 900 chars of what is actually there:\n" (subs text 0 (min 900 (count text)))))

          (and (> n 1) (not all_occurrences))
          (bad (str "anchor appears " n " times in " p " — pass all_occurrences to replace all, "
                    "or extend the anchor so it is unique. Nothing was written."))

          :else
          (let [want (if all_occurrences
                       (str/replace text old new)
                       (str (subs text 0 (first hits)) new (subs text (+ (first hits) (count old)))))
                replaced (if all_occurrences n 1)
                ;; THE COUNT OF ANCHORS *BEFORE* THE EDIT. The success test used
                ;; to demand `(zero? old-left)` — zero anchors left in the result —
                ;; which is false for any edit that keeps the anchor and adds text
                ;; around it (an append below the anchor: the ordinary case). That
                ;; reported a LANDED write as "EDIT FAILED VERIFICATION ... written=N
                ;; read-back=N" — the write had happened, the report denied it. It is
                ;; the inverted twin of the 2026-10-14 incident (a tool that said
                ;; success and did nothing), and it hit twice on 2026-10-19.
                n-before (count (re-seq (re-pattern (java.util.regex.Pattern/quote old)) text))]
            (spit p want :encoding "UTF-8")
            (let [got (read-text p)
                  ok? (= want got)
                  old-left (count (re-seq (re-pattern (java.util.regex.Pattern/quote old)) got))
                  new-times (count (re-seq (re-pattern (java.util.regex.Pattern/quote new)) got))
                  ;; landed = the file is what we wrote, and we did not ADD an
                  ;; occurrence of the anchor. `>` not `>=`: a replacement whose
                  ;; new_content contains the anchor legitimately keeps the count.
                  landed? (and ok? (or all_occurrences (<= old-left n-before)))]
              (if landed?
                (ok (str "edited " p " — " replaced " replacement(s), verified by reading it back\n"
                         "  - " (clip old) "\n  + " (clip new)))
                (bad (str "EDIT FAILED VERIFICATION in " p " — the file does not contain what we wrote. "
                          "No success is being claimed.\n"
                          "written=" (count want) " read-back=" (count got)
                          " anchor-remaining=" old-left
                          " new-occurrences=" new-times))))))))))

(defn list-dir* [{:keys [cwd path]}]
  (let [p (expand cwd (or path "."))]
    (when-not (fs/directory? p) (throw (ex-info (str "not a directory: " p) {:path p})))
    (ok (str p "\n"
             (->> (fs/list-dir p)
                  (map (fn [f] (str (if (fs/directory? f) "d " "- ")
                                    (fs/file-name f)
                                    (when (fs/regular-file? f) (str "  (" (fs/size f) " B)")))))
                  sort
                  (str/join "\n"))))))

(defn- run-capture
  "Run argv with a timeout, merged stdout+stderr. Returns {:out :exit :timeout?}."
  [argv cwd timeout-ms]
  (let [pb (ProcessBuilder. ^java.util.List (vec argv))]
    (when cwd (.directory pb (io/file cwd)))
    (doto pb (.redirectErrorStream true))
    (let [proc (.start pb)
          out (future (slurp (io/reader (.getInputStream proc))))
          done? (.waitFor proc (long timeout-ms) TimeUnit/MILLISECONDS)]
      (if done?
        {:out @out :exit (.exitValue proc) :timeout? false}
        (do (.destroyForcibly proc)
            {:out (str @out "\n[killed after " timeout-ms "ms]") :exit nil :timeout? true})))))

(defn- name-matcher
  "A filename predicate from a glob (`*.clj`) or, failing that, a regex."
  [spec]
  (let [spec (str spec)]
    (try
      (let [pm (.getPathMatcher (FileSystems/getDefault) (str "glob:" spec))]
        (fn [^String n] (.matches pm (Paths/get n (make-array String 0)))))
      (catch Throwable _
        (let [re (try (Pattern/compile spec) (catch Throwable _ nil))]
          (if re (fn [^String n] (boolean (re-find re n)))
                 (fn [^String n] (= n spec))))))))

(defn- binary-text? [^String s]
  (boolean (some #(= \u0000 %) (take 8000 s))))

;; --- the project boundary: where a search is allowed to start ----------------
;;
;; The filesystem boundary (grog.fs.walk) is about the WALK. This is about the
;; STARTING POINT: a session is anchored to one project directory, reads inside
;; it freely, and must ask a human to point anywhere else. Shared by every
;; path-scoped read tool so the rule — and its wording — cannot drift per tool.

(defn project-root
  "The directory a session is anchored to — the loop's cwd, resolved."
  ^Path [cwd]
  (walk/real-path (or cwd ".")))

(defn outside-project?
  "True when `root` (resolved against `cwd`) is NOT under the project directory.
  An unresolvable path answers true — a refusal, never a silent walk."
  [cwd root]
  (try (not (walk/under? (project-root cwd) (walk/real-path (expand cwd root))))
       (catch Throwable _ true)))

(defn outside-root-refusal
  "The refusal message when `rt` (a resolved real path) is outside the project and
  `allow-outside` was not approved — else nil, meaning the call may proceed."
  [cwd ^Path rt allow-outside tool]
  (when (and rt
             (not (true? allow-outside))
             (try (not (walk/under? (project-root cwd) rt)) (catch Throwable _ true)))
    (str "refused: " rt " is OUTSIDE the project (" (project-root cwd) ").\n"
         tool " searches only where you point it and never wanders out on its own. "
         "To search there: ask the user with `ask_user` first, then re-call with "
         "allow_outside: true (their approval gates it).")))

(defn root-approval-required?
  "The loop's argument gate for every path-scoped read tool: a call that asks to
  leave the project is the one a human confirms. Inside the project, no approval
  is involved at all."
  [{:keys [args]}]
  (true? (:allow_outside args)))

(defn- scan-lines!
  "Collect `path:line:text` for every regular file under `rt` whose CONTENT
  matches `pattern` (regex; literal when it will not compile), optionally
  filtered by an `include` filename glob. Bounded to rt's own filesystem by
  `walk/bounded-walk` — the same walk finds/does not cross a mount.

  Returns the walk stats plus :hits (the vector of match strings)."
  [^Path rt pattern include max-hits max-bytes]
  (let [re     (when (seq (str pattern)) (try (Pattern/compile (str pattern)) (catch Throwable _ nil)))
        lit    (when (and (seq (str pattern)) (nil? re)) (str pattern))
        nmatch (when (and include (seq (str include)) (not= "*" (str include)))
                 (name-matcher include))
        !hits  (atom [])
        visit  (fn [{:keys [^Path path attrs]}]
                 (let [fname (str (.getFileName path))]
                   (when (and (or (nil? nmatch) (nmatch fname))
                              (<= (long (.size attrs)) (long max-bytes))
                              (< (count @!hits) (long max-hits)))
                     (when-let [txt (try (slurp (str path) :encoding "UTF-8")
                                         (catch Throwable _ nil))]
                       (when-not (binary-text? txt)
                         (doseq [[i line] (map-indexed vector (str/split-lines txt))
                                 :when (and (< (count @!hits) (long max-hits))
                                            (if re (re-find re line)
                                                   (str/includes? line lit)))]
                           (swap! !hits conj (str path ":" (inc i) ":" line))))))))]
    (assoc (walk/bounded-walk rt {:visit visit}) :hits @!hits)))

(defn grep* [{:keys [cwd pattern path include allow_outside]}]
  (if (str/blank? (str pattern))
    (bad "grep needs a pattern")
    (let [root (expand cwd (or path "."))
          rt   (try (walk/real-path root) (catch Throwable _ nil))]
      (cond
        (nil? rt)                      (bad (str "no such path: " root))
        (not (fs/directory? (str rt))) (bad (str "not a directory: " rt))
        :else
        (if-let [refuse (outside-root-refusal cwd rt allow_outside "grep")]
          (bad refuse)
          (let [{:keys [hits left]} (scan-lines! rt pattern include 200 2000000)]
            (ok (if (empty? hits)
                  (str "no match for " (pr-str pattern) " under " rt
                       (when (seq left)
                         (str "  (bounded to this one filesystem; skipped "
                              (count left) " other mount(s): "
                              (str/join ", " (take 8 left))
                              (when (> (count left) 8) " …")
                              ")")))
                  (str (str/join "\n" hits)
                       (when (seq left)
                         (str "\n… bounded to one filesystem; skipped "
                              (count left) " other mount(s), not searched")))))))))))

(defn shell* [{:keys [cwd command timeout_ms]}]
  (when (str/blank? (str command))
    (bad "shell needs a command"))
  (let [t (long (or timeout_ms 60000))
        {:keys [out exit timeout?]} (run-capture ["bash" "-lc" (str command)] cwd t)]
    (if timeout?
      (bad out)
      (ok (str "exit " exit "\n" out)))))

;; --- the rest of the belt ----------------------------------------------------
;; Everything below is either a thin, honest wrapper (tree/move/git) or a
;; SESSION-scoped tool whose behaviour needs the running chat (todo list, ask the
;; user, skills, subagents, images). Session tools are built by `session-tools`
;; with the callbacks injected, so this namespace never reaches into the loop or
;; the chat core — the dependency runs one way.

(def ^:private tree-skip
  #{"node_modules" "target" "dist" ".git" ".cpcache" ".shadow-cljs" ".clj-kondo"})

(defn- tree-visitor
  "Build the `:visit-dir`/`:visit` pair that renders a tree, one indented line per
  entry. Shared so the directory and file branches agree on indent and labels."
  [!lines depth-limit]
  (let [emit (fn [^Path rel dir?]
               (let [parts (mapv str rel)]
                 (when-not (str/blank? (str rel))
                   (let [depth (count parts)]
                     (when (<= depth depth-limit)
                       (swap! !lines conj (str (apply str (repeat (max 0 (dec depth)) "  "))
                                               (if dir? "d " "- ") (last parts))))))))]
    [(fn [{:keys [rel]}] (emit rel true))
     (fn [{:keys [rel]}] (emit rel false))]))

(defn directory-tree*
  "Recursive listing, dirs marked `d`. Bounded to the ONE filesystem the root
  lives on (`grog.fs.walk`) — it never crosses into another mount and never
  follows a symlink out. The project directory is free; a root outside it needs
  `allow_outside: true` and the human's approval."
  [{:keys [cwd path max_depth allow_outside]}]
  (let [root (expand cwd (or path "."))
        rt   (try (walk/real-path root) (catch Throwable _ nil))
        depth-limit (long (or max_depth 3))]
    (cond
      (nil? rt)                      (bad (str "no such path: " root))
      (not (fs/directory? (str rt))) (bad (str "not a directory: " rt))
      :else
      (if-let [refuse (outside-root-refusal cwd rt allow_outside "directory_tree")]
        (bad refuse)
        (let [!lines (atom [])
              [visit-dir visit] (tree-visitor !lines depth-limit)
              skip-dir? (fn [^Path rel]
                          (let [parts (mapv str rel)]
                            (or (> (count parts) depth-limit)
                                (some tree-skip parts)
                                (some #(str/starts-with? % ".") parts))))
              {:keys [left]} (walk/bounded-walk rt {:visit visit :visit-dir visit-dir
                                                    :skip-dir? skip-dir?})]
          (ok (str rt "\n" (clip (str/join "\n" @!lines))
                   (when (seq left)
                     (str "\n… (bounded to one filesystem; skipped " (count left)
                          " other mount(s): " (str/join ", " (take 8 left))
                          (when (> (count left) 8) " …") ")")))))))))

(defn move-file* [{:keys [cwd source destination]}]
  (let [s (expand cwd source)
        d (expand cwd destination)]
    (when-not (fs/exists? s) (throw (ex-info (str "no such path: " s) {:path s})))
    (when (fs/exists? d) (throw (ex-info (str "destination already exists: " d) {:path d})))
    (when-let [p (fs/parent d)] (fs/create-dirs p))
    (fs/move s d)
    ;; the same rule as write_file: never claim a move you have not looked at
    (if (fs/exists? d)
      (ok (str "moved " s " -> " d " (verified: destination exists)"))
      (bad (str "move reported no error but " d " does not exist")))))

(defn git* [{:keys [cwd args]}]
  (let [argv (into ["git"] (map str (or args [])))
        {:keys [out exit timeout?]} (run-capture argv cwd 60000)]
    (if timeout?
      (bad out)
      (ok (str "git " (str/join " " (or args [])) " -> exit " exit "\n" out)))))

;; --- find_files: a walk that never leaves its filesystem --------------------
;;
;; The point is not the searching — it is the STARTING POINT and the BOUNDARY.
;; There is no default root, so a model cannot "go find" on a whim; it must name
;; where the walk begins. The project directory is free. Anywhere else is
;; gated by the human (`allow_outside` → the loop's approval dialog). And once
;; walking, the traversal is bound to the ONE filesystem the root lives on
;; (`grog.fs.walk`), so it cannot wander into another mount, /proc, or a FUSE
;; share no matter what the path looks like.

(def ^:private find-default-results 100)
(def ^:private find-default-file-bytes 1000000)
(def ^:private find-default-depth 20)

(defn find-files*
  "Walk `root` — REQUIRED, no default and no \"/\" shortcut — bounded to the one
  filesystem it lives on, and return filename and/or content matches.

  `name` filters files (glob or regex); `query` matches content (regex) and
  emits `rel:line:text`. With neither, every (capped) file is listed. A root
  outside the project is refused unless `allow_outside` was approved."
  [{:keys [cwd root name query max_depth max_results max_file_bytes
           extensions exclude_dirs include_hidden follow_symlinks allow_outside]}]
  (if (str/blank? (str root))
    (bad (str "find_files needs an explicit `root` — there is no default and no \"/\" shortcut. "
              "Name the directory to walk (the project dir, or a path under it)."))
    (let [root-str (expand cwd root)
          rt (try (walk/real-path root-str) (catch Throwable _ nil))]
      (cond
        (nil? rt)
        (bad (str "root does not exist: " root-str))

        (not (fs/directory? (str rt)))
        (bad (str "not a directory: " rt))

        (and (outside-project? cwd root-str) (not (true? allow_outside)))
        (bad (outside-root-refusal cwd rt allow_outside "find_files"))

        :else
        (let [nmatch    (when (seq (str name)) (name-matcher name))
              re        (when (seq (str query)) (try (Pattern/compile (str query)) (catch Throwable _ nil)))
              lit       (when (and (seq (str query)) (nil? re)) (str query))
              content?  (boolean (or re lit))
              max-res   (long (or max_results find-default-results))
              max-bytes (long (or max_file_bytes find-default-file-bytes))
              max-depth (long (or max_depth find-default-depth))
              skip-set  (into tree-skip (map str (or exclude_dirs [])))
              ext-set   (when (seq extensions)
                          (set (map #(str/lower-case (str/replace (str %) #"^\." "")) extensions)))
              !hits     (atom [])
              skip-dir? (fn [^Path rel]
                          (let [parts (mapv str rel)]
                            (or (> (count parts) max-depth)
                                (some skip-set parts)
                                (and (not include_hidden)
                                     (some #(str/starts-with? % ".") parts)))))
              visit     (fn [{:keys [^Path path ^Path rel attrs]}]
                          (let [fname (str (.getFileName path))
                                fext  (str/lower-case (str (or (fs/extension (str path)) "")))]
                            (when (and (or (nil? ext-set) (contains? ext-set fext))
                                       (or (nil? nmatch) (nmatch fname)))
                              (if content?
                                (when (and (<= (long (.size attrs)) max-bytes)
                                           (< (count @!hits) max-res))
                                  (when-let [txt (try (slurp (str path) :encoding "UTF-8")
                                                      (catch Throwable _ nil))]
                                    (when-not (binary-text? txt)
                                      (doseq [[i line] (map-indexed vector (str/split-lines txt))
                                              :when (and (< (count @!hits) max-res)
                                                         (if re (re-find re line)
                                                                (str/includes? line lit)))]
                                        (swap! !hits conj (str rel ":" (inc i) ":" line))))))
                                (when (< (count @!hits) max-res)
                                  (swap! !hits conj (str rel)))))))
              {:keys [dirs files left device root]}
              (walk/bounded-walk rt {:visit visit :skip-dir? skip-dir?
                                     :follow-links? (boolean follow_symlinks)})]
          (ok (str "find_files: " (count @!hits) " match(es) under " root "\n"
                   "boundary: " device " (one filesystem — not crossed)"
                   "  ·  scanned " files " file(s) in " dirs " dir(s)"
                   (when (seq left)
                     (str "\nskipped " (count left) " other filesystem(s), NOT searched: "
                          (str/join ", " (take 12 left))
                          (when (> (count left) 12) " …")))
                   "\n" (str/join "\n" @!hits))))))))


(def ^:private task-statuses #{"open" "in-progress" "done"})

(defn task-handlers
  "A per-session todo list. `!tasks` is the session's atom (a vector of
  {:id :subject :status :description :blocked_by}); every op returns the WHOLE
  list so the model always reads the current state rather than its memory of it."
  [!tasks]
  {"task"
   (fn [{:keys [op id subject description status blocked_by]}]
     (let [op (keyword (or op "read"))]
       (case op
         :read (ok (if (seq @!tasks) (pr-str @!tasks) "(no tasks)"))
         :plan (let [ts (vec (map-indexed
                              (fn [i t] {:id (inc i)
                                         :subject (str (:subject t))
                                         :status "open"
                                         :description (str (:description t))
                                         :blocked_by (vec (or (:blocked_by t) []))})
                              (or (:tasks subject) [])))]
                 (reset! !tasks ts)
                 (ok (str "planned " (count ts) " task(s)\n" (pr-str ts))))
         :add (let [t {:id (inc (long (apply max 0 (map :id @!tasks))))
                      :subject (str subject)
                      :status "open"
                      :description (str description)
                      :blocked_by (vec (or blocked_by []))}]
                (swap! !tasks conj t)
                (ok (pr-str @!tasks)))
         :update (do (swap! !tasks (fn [ts] (mapv #(if (= (:id %) id)
                                                     (merge % (cond-> {}
                                                                subject (assoc :subject (str subject))
                                                                description (assoc :description (str description))
                                                                status (assoc :status (str status))
                                                                blocked_by (assoc :blocked_by (vec blocked_by))))
                                                     %) ts)))
                     (ok (pr-str @!tasks)))
         :start (do (swap! !tasks (fn [ts] (mapv #(if (= (:id %) id) (assoc % :status "in-progress") %) ts)))
                    (ok (pr-str @!tasks)))
         :complete (do (swap! !tasks (fn [ts] (mapv #(if (= (:id %) id) (assoc % :status "done") %) ts)))
                       (ok (pr-str @!tasks)))
         :delete (do (swap! !tasks (fn [ts] (vec (remove #(= (:id %) id) ts))))
                     (ok (pr-str @!tasks)))
         :clear (do (reset! !tasks []) (ok "(cleared)"))
         (bad (str "unknown task op " (pr-str op)
                   " — use read|plan|add|update|start|complete|delete|clear")))))})

(defn session-tools
  "The SESSION-scoped half of the belt. Returns {:tools <schemas> :handlers
  {name fn}}; the loop merges these over the static `tools` table. Callbacks are
  injected (`ask-user!`, `load-skill!`, `spawn-agent!`, `view-image!`) so this
  namespace stays independent of the loop and the chat core.

  `ask-user!` is the one that matters most: it is how the model asks a question,
  and it must BLOCK until a human answers — the same contract ECA's
  `chat/askQuestion` had."
  [{:keys [ask-user! load-skill! spawn-agent! view-image! !tasks]
    :or {!tasks (atom [])}}]
  (let [schemas
        [{:name "ask_user"
          :description (str "Ask the human a question and WAIT for the answer. Use when a "
                            "decision is genuinely the user's (an ambiguous instruction, a "
                            "destructive choice, a preference). Supply options when they are "
                            "enumerable; free-form answers are allowed unless allow_freeform "
                            "is false.")
          :parameters {:type "object"
                       :properties {:question {:type "string"}
                                    :options {:type "array" :items {:type "string"}}
                                    :allow_freeform {:type "boolean"}}
                       :required ["question"]}}

         {:name "skill"
          :description "Load a skill by id: its instructions are returned to you. Call with no id to list the available skills."
          :parameters {:type "object" :properties {:id {:type "string"}}}}

         {:name "spawn_agent"
          :description (str "Run a subagent on a task in an isolated context and return only its "
                            "final answer. Use for research or a self-contained subtask that would "
                            "otherwise flood this conversation with tool output.")
          :parameters {:type "object"
                       :properties {:prompt {:type "string"}
                                    :cwd {:type "string"}}
                       :required ["prompt"]}}

         {:name "view_image"
          :description (str "Look at an image file (png/jpg). The image is attached to the "
                            "conversation so you can see it on the NEXT step.")
          :parameters {:type "object" :properties {:path {:type "string"}}
                       :required ["path"]}}

         {:name "task"
          :description (str "The session todo list. op=read|plan|add|update|start|complete|delete|clear. "
                            "plan takes `tasks` (a vector of {subject description blocked_by}); "
                            "add takes subject/description; update/start/complete/delete take id.")
          :parameters {:type "object"
                       :properties {:op {:type "string"}
                                    :id {:type "integer"}
                                    :subject {:type "string"}
                                    :description {:type "string"}
                                    :status {:type "string"}
                                    :blocked_by {:type "array" :items {:type "integer"}}
                                    :tasks {:type "array" :items {:type "object"}}}
                       :required ["op"]}}]

        handlers
        (cond-> (task-handlers !tasks)
          ;; WRAPPED IN `ok`, like every other handler: returning the raw string
          ;; meant `dispatch!` got a String, read `:text` as nil, and called
          ;; `(assoc "…" :text …)` — the ClassCastException that killed a live
          ;; turn the moment the agent asked a question (2026-10-19).
          ask-user!    (assoc "ask_user" (fn [{:keys [question options allow_freeform]}]
                                           (ok (ask-user! {:prompt question
                                                           :options options
                                                           :allow-freeform? (not (false? allow_freeform))}))))
          load-skill!  (assoc "skill" (fn [{:keys [id]}]
                                        (try (ok (load-skill! id))
                                             (catch Throwable e (bad (.getMessage e))))))
          spawn-agent! (assoc "spawn_agent" (fn [{:keys [prompt cwd]}]
                                              (try (ok (spawn-agent! {:prompt (str prompt) :cwd cwd}))
                                                   (catch Throwable e (bad (str "subagent failed: " (.getMessage e)))))))
          view-image!  (assoc "view_image" (fn [{:keys [path]}]
                                             (try (view-image! {:cwd (System/getProperty "user.dir") :path path})
                                                  (catch Throwable e (bad (.getMessage e)))))))]
    {:tools (if (seq handlers) schemas [])
     :handlers handlers}))

(def tools
  "The toolbelt. Schemas are JSON Schema objects (the shape providers accept)."
  [{:name "read_file"
    :description "Read a UTF-8 text file. Returns the absolute path, the line count and the content."
    :parameters {:type "object" :properties {:path {:type "string"}
                                             :offset {:type "integer" :description "1-based first line"}
                                             :limit {:type "integer" :description "max lines"}}
                 :required ["path"]}
    :handler read-file*}

   {:name "write_file"
    :description "Write a file (creating parent directories). Reads it back and reports whether the bytes landed."
    :parameters {:type "object" :properties {:path {:type "string"} :content {:type "string"}}
                 :required ["path" "content"]}
    :handler write-file*}

   {:name "edit_file"
    :description (str "Replace exact text in a file. The anchor must match byte-for-byte; "
                      "the tool re-reads the file afterwards and reports verified true/false.")
    :parameters {:type "object"
                 :properties {:path {:type "string"}
                              :original_content {:type "string"}
                              :new_content {:type "string"}
                              :all_occurrences {:type "boolean"}}
                 :required ["path" "original_content" "new_content"]}
    :handler edit-file*}

   {:name "list_dir"
    :description "List a directory (dirs marked d)."
    :parameters {:type "object" :properties {:path {:type "string"}}}
    :handler list-dir*}

   {:name "grep"
    :description "Search file contents (regex; literal if it will not compile) under a root, bounded to the ONE filesystem the root lives on. The project directory is free; a root outside it is refused unless you ask the user and re-call with allow_outside: true. Returns path:line:text. Prefer find_files for a typed, capped, multi-filter search."
    :parameters {:type "object" :properties {:pattern {:type "string"}
                                             :path {:type "string" :description "root to search (default: the project dir)"}
                                             :include {:type "string" :description "filename glob, e.g. *.clj"}
                                             :allow_outside {:type "boolean" :description "search outside the project — requires the user's approval"}}
                 :required ["pattern"]}
    :handler grep*}

   {:name "find_files"
    :description (str "Search files under an EXPLICIT root: filename matches (glob or regex) and/or "
                      "content lines. STARTING POINT REQUIRED — there is no default root. The walk is "
                      "bound to the ONE filesystem the root lives on: it never crosses into another "
                      "mount, /proc, /sys or a FUSE share, and never follows symlinks out. The project "
                      "directory is free; a root outside it is refused unless you first ask the user "
                      "(ask_user) and re-call with allow_outside: true. Prefer this over `shell` "
                      "find/grep for locating files.")
    :parameters {:type "object"
                 :properties {:root {:type "string" :description "directory to walk — REQUIRED, no default"}
                              :name {:type "string" :description "glob or regex on the filename, e.g. *.clj"}
                              :query {:type "string" :description "regex matched against file CONTENT; emits rel:line:text"}
                              :max_depth {:type "integer" :description "default 20"}
                              :max_results {:type "integer" :description "default 100"}
                              :max_file_bytes {:type "integer" :description "skip larger files for content search (default 1000000)"}
                              :extensions {:type "array" :items {:type "string"} :description "allowlist, e.g. [clj cljs]"}
                              :exclude_dirs {:type "array" :items {:type "string"}}
                              :include_hidden {:type "boolean"}
                              :follow_symlinks {:type "boolean" :description "default false"}
                              :allow_outside {:type "boolean" :description "leave the project — requires the user's approval"}}
                 :required ["root"]}
    :handler find-files*}

   {:name "shell"
    :description "Run a shell command (bash -lc) in the working directory and return its merged output. Use for git, builds and one-off inspection."
    :parameters {:type "object" :properties {:command {:type "string"}
                                             :timeout_ms {:type "integer"}}
                 :required ["command"]}
    :handler shell*}

   {:name "directory_tree"
    :description "Recursive directory listing (dirs marked d), bounded to the ONE filesystem the root lives on. The project directory is free; a root outside it is refused unless you ask the user and re-call with allow_outside: true. Skips dotfiles and build/cache dirs."
    :parameters {:type "object" :properties {:path {:type "string"}
                                             :max_depth {:type "integer"}
                                             :allow_outside {:type "boolean" :description "list outside the project — requires the user's approval"}}
                 :required ["path"]}
    :handler directory-tree*}

   {:name "move_file"
    :description "Move or rename a path. Fails if the destination exists; verifies the destination afterwards."
    :parameters {:type "object" :properties {:source {:type "string"} :destination {:type "string"}}
                 :required ["source" "destination"]}
    :handler move-file*}

   {:name "git"
    :description "Run a git subcommand in the working directory and return its output."
    :parameters {:type "object" :properties {:args {:type "array" :items {:type "string"}}}
                 :required ["args"]}
    :handler git*}])

(defn provider-tools
  "OpenAI `tools` for the static belt plus any session tools the loop supplies."
  ([cwd] (provider-tools cwd []))
  ([_cwd extra-tools]
   (mapv (fn [{:keys [name description parameters]}]
           {:type "function" :function {:name name :description description :parameters parameters}})
         (concat tools extra-tools))))

(defn norm-args
  "JSON arrives with STRING keys; the handlers destructure KEYWORDS. Normalise at
  the boundary once, here, instead of making every handler guess — the same
  boundary that cost grog a day in memory.clj (see the java-collections
  arg-adapter mistake). Values are untouched: nested objects stay as they came."
  [m]
  (when m
    (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) v])) m)))

(defn handlers
  "The static belt as a name->fn table (`:cwd` injected), with any session
  handlers merged OVER it — a session tool may replace a static one by name."
  ([cwd] (handlers cwd {}))
  ([cwd extra]
   (merge (into {} (map (fn [{:keys [name handler]}]
                          [name (fn [args] (handler (assoc (norm-args args) :cwd cwd)))]))
                 tools)
          extra)))

(defn call
  "Dispatch one in-process tool by name. Always returns {:text :error?} — an
  exception becomes a reported failure, never a missing result."
  [table name args]
  (if-let [h (get table name)]
    (try (h (norm-args args))
         (catch Throwable t (bad (str "tool " name " threw: " (.getMessage t)
                                      (when-let [d (ex-data t)] (str " " (pr-str (dissoc d :cause))))))))
    (bad (str "no such tool: " name))))
