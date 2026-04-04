(ns grog.core
  (:gen-class)
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [grog.brave :as brave]
            [grog.config :as config]
            [grog.boofcv-pdf :as boofcv-pdf]
            [grog.fs :as fs]
            [grog.edn-store :as edn-store]
            [grog.memory-tools :as memory-tools]
            [grog.md-render :as md-render]
            [grog.project-dialog :as project-dialog]
            [grog.readline :as gread]
            [grog.soul :as soul]))

(def ^:private ansi-reset "\u001B[0m")
(def ^:private ansi-hot-pink "\u001B[38;2;255;105;180m")
(def ^:private ansi-thinking "\u001B[38;2;55;165;95m")
(def ^:private ansi-answer "\u001B[38;2;218;228;248m")

(def ^:private chat-initial-heading "I am Grog, Thou shalt NOT Trifle")

(defn- print-thinking! [s]
  (when-not (str/blank? s)
    (print ansi-thinking)
    (println "── thinking ──")
    (println (str/trim s))
    (print ansi-reset)
    (flush)))

(defn- path-for-display [p]
  (try
    (str (-> (java.nio.file.Paths/get (str p) (into-array String []))
             .toAbsolutePath
             .normalize))
    (catch Exception _
      (str p))))

(defn- slurp-prompt-file! [raw-path]
  (let [p (str/trim (str raw-path))]
    (if (str/blank? p)
      (do (binding [*out* *err*] (println "grog: `@` needs a non-empty file path"))
          "[grog: @ path was empty]")
      (let [f (io/file p)]
        (cond
          (not (.exists f))
          (do (binding [*out* *err*] (println "grog: file not found:" (path-for-display p)))
              (str "[grog: file not found: " (path-for-display p) "]"))
          (not (.isFile f))
          (do (binding [*out* *err*] (println "grog: not a regular file:" (path-for-display p)))
              (str "[grog: not a regular file: " (path-for-display p) "]"))
          :else
          (try (slurp f :encoding "UTF-8")
               (catch Exception e
                 (binding [*out* *err*]
                   (println "grog: could not read file:" (path-for-display p) "-" (.getMessage e)))
                 (str "[grog: could not read " (path-for-display p) "]"))))))))

(defn- expand-at-token [^String token]
  (if (str/starts-with? token "@")
    (slurp-prompt-file! (subs token 1))
    token))

(defn- expand-line-at-paths
  ([line] (expand-line-at-paths line false))
  ([line echo-files?]
   (->> (str/split (str line) #"\s+")
        (remove str/blank?)
        (map (fn [token]
               (if (str/starts-with? token "@")
                 (let [path-raw (subs token 1)
                       content (slurp-prompt-file! path-raw)]
                   (when echo-files?
                     (println)
                     (println (str "── @" (str/trim path-raw) " ——"))
                     (doseq [ln (str/split-lines content)]
                       (println ln))
                     (println "——"))
                   content)
                 token)))
        (str/join " "))))

(defn- wrap-soul-as-system-prompt [raw]
  (str "## Persistent instructions (SOUL.md)\n\n"
       raw
       "\n\n---\nTreat the above as standing rules for your replies unless the user clearly overrides them for a single turn."))

(defn- system-messages []
  (try
    (vec
     (concat
      (when-let [t (some-> (soul/read-text) str str/trim not-empty)]
        [{:role "system" :content (wrap-soul-as-system-prompt t)}])
      (when-let [p (config/active-project-name)]
        [{:role "system"
          :content (str "Active project: **" p "**. `memory_*` tools persist under `Projects/" p "/…` in the configured edn-store; your turns are also logged to `Projects/" p "/dialog/thread.edn`.")}])))
    (catch Exception e
      (binding [*out* *err*]
        (println "grog: SOUL not applied:" (.getMessage e)))
      nil)))

(defn- history->messages [system-msgs history]
  (vec (concat system-msgs
               (mapcat (fn [{:keys [user assistant]}]
                         [{:role "user" :content user}
                          {:role "assistant" :content assistant}])
                       history))))

(defn- recent-history-for-cap [history cap]
  (cond
    (nil? cap) history
    (and (number? cap) (zero? (long cap))) []
    (and (number? cap) (pos? (long cap))) (vec (take-last cap history))
    :else history))

(defn- chat-context-messages [history]
  (history->messages (system-messages)
                     (recent-history-for-cap history (config/chat-history-turns))))

(defn- print-chat-context-line! [history]
  (let [msgs (chat-context-messages history)
        ^String json (json/generate-string msgs)
        bytes (alength (.getBytes json "UTF-8"))
        kB (/ bytes 1024.0)
        ;; Rough estimate only (English-ish text; real tokenizer is model-specific).
        est-tok (max 1 (long (/ (count json) 4)))]
    (binding [*out* *err*]
      (printf "grog: chat context %.1f kB JSON, %d tok (est.)\n" kB est-tok)
      (flush))))

(defn- chat-prompt-str []
  (if-let [p (config/active-project-name)]
    (str p " > ")
    "chat> "))

(defn- chat-answer-prefix []
  (if-let [p (config/active-project-name)]
    (str "\n\n" p " > ")
    "\n\nchat> "))

(defn- read-chat-line-with-context! [history]
  (print-chat-context-line! history)
  (loop []
    (if-some [line (gread/read-prompt! (chat-prompt-str))]
      (if (str/blank? line)
        (recur)
        line)
      nil)))

(defn- chat-request-payload
  [messages tools stream?]
  (cond-> {:model (config/model) :messages messages :stream stream?}
    (seq tools) (assoc :tools tools)
    (config/chat-show-thinking?) (assoc :think true)))

(defn- use-streaming-chat-round? []
  (or (and (config/chat-show-thinking?) (config/chat-stream-live-thinking?))
      (config/chat-stream-live-content?)))

(defn- ollama-post-chat!
  "POST /api/chat (non-streaming)."
  [messages tools]
  (try
    (let [url (str (str/trim (config/ollama-url)) "/api/chat")
          payload (chat-request-payload messages tools false)
          resp (http/post url {:content-type "application/json"
                               :body (json/generate-string payload)
                               :as :json
                               :throw-exceptions false})
          status (:status resp)
          body (:body resp)]
      (if (= 200 status)
        {:ok true :body body}
        {:ok false :error (str "HTTP " status " — " (pr-str body))}))
    (catch Exception e
      {:ok false :error (.getMessage e)})))

(defn- tool-calls-from-chunk
  "Ollama may put `tool_calls` on `message` and/or only on earlier stream chunks; merge paths."
  [obj]
  (let [msg (:message obj)
        t-msg (when msg (or (:tool_calls msg) (:toolCalls msg)))
        t-root (or (:tool_calls obj) (:toolCalls obj))
        t (or t-msg t-root)]
    (cond
      (and (sequential? t) (seq t)) (vec t)
      (map? t) [t]
      :else nil)))

(defn- build-message-from-stream-bufs
  [final-msg ^StringBuilder think-buf ^StringBuilder content-buf streamed-tool-calls]
  (let [from-final (when final-msg (or (:tool_calls final-msg) (:toolCalls final-msg)))
        tc (or (cond
                 (and (sequential? from-final) (seq from-final)) (vec from-final)
                 (map? from-final) [from-final]
                 :else nil)
               streamed-tool-calls)]
    {:thinking (str/trim (str think-buf))
     :content (str content-buf)
     :tool_calls tc}))

(defn- ollama-post-chat-stream!
  "POST /api/chat with `stream: true`; prints thinking (dark green) and answer (cyan) as deltas arrive."
  [messages tools stream-opts]
  (let [answer-prefix (or (:answer-prefix stream-opts) (chat-answer-prefix))]
    (try
    (let [url (str (str/trim (config/ollama-url)) "/api/chat")
          payload (chat-request-payload messages tools true)
          resp (http/post url {:content-type "application/json"
                               :body (json/generate-string payload)
                               :as :stream
                               :throw-exceptions false})
          status (:status resp)]
      (if-not (= 200 status)
        (do (try (slurp ^java.io.InputStream (:body resp)) (catch Exception _ nil))
            {:ok false :error (str "HTTP " status)
             :live-thinking-printed? false :live-content-printed? false})
        (with-open [^java.io.Closeable stream (:body resp)]
          (let [rdr (io/reader stream)
                think-buf (StringBuilder.)
                content-buf (StringBuilder.)
                in-thinking (atom false)
                any-live (atom false)
                started-answer (atom false)
                any-content-live (atom false)]
            (letfn [(finish! [last-msg last-tc]
                      (do
                        (when @in-thinking
                          (print ansi-reset)
                          (println)
                          (reset! in-thinking false))
                        (let [md? (config/format-markdown?)
                              stream-live? (config/chat-stream-live-content?)
                              buf-len (.length content-buf)]
                          (when (and (pos? buf-len) (or (not stream-live?) md?))
                            (print answer-prefix)
                            (if md?
                              (print (md-render/render-to-ansi (str content-buf)))
                              (do (print ansi-answer)
                                  (print (str content-buf))))
                            (println)))
                        (print ansi-reset)
                        (println)
                        (flush)
                        (let [md? (config/format-markdown?)
                              stream-live? (config/chat-stream-live-content?)
                              streamed? (and stream-live? (not md?) @any-content-live)
                              dumped-buf? (and (pos? (.length content-buf))
                                               (or (not stream-live?) md?))]
                          {:ok true
                           :body {:message (build-message-from-stream-bufs last-msg think-buf content-buf last-tc)}
                           :live-thinking-printed? @any-live
                           :live-content-printed? (boolean (or streamed? dumped-buf?))})))]
              (loop [last-seen-msg nil
                     last-tc nil]
                (let [line (.readLine rdr)]
                  (if (nil? line)
                    (finish! last-seen-msg last-tc)
                    (if (str/blank? line)
                      (recur last-seen-msg last-tc)
                      (let [obj (try (json/parse-string line true)
                                     (catch Exception _ nil))]
                        (if-not (map? obj)
                          (recur last-seen-msg last-tc)
                          (let [done (:done obj)
                                msg (:message obj)
                                th (some-> (:thinking msg) str)
                                ct (some-> (:content msg) str)
                                chunk-tc (tool-calls-from-chunk obj)
                                last-tc' (or chunk-tc last-tc)
                                last-msg' (if (some? msg) msg last-seen-msg)]
                            (when (and (config/chat-show-thinking?) (not (str/blank? th)))
                              (when-not @in-thinking
                                (reset! in-thinking true)
                                (reset! any-live true)
                                (print ansi-thinking)
                                (print "── thinking ──\n"))
                              (print th)
                              (flush))
                            (when (not (str/blank? th))
                              (.append think-buf th))
                            (when (not (str/blank? ct))
                              (when @in-thinking
                                (print ansi-reset)
                                (println)
                                (reset! in-thinking false))
                              (.append content-buf ct)
                              (when (and (config/chat-stream-live-content?)
                                         (not (config/format-markdown?)))
                                (when-not @started-answer
                                  (reset! started-answer true)
                                  (reset! any-content-live true)
                                  (print answer-prefix)
                                  (print ansi-answer))
                                (print ct)
                                (flush)))
                            (if done
                              (finish! last-msg' last-tc')
                              (recur last-msg' last-tc'))))))))))))))
      (catch Exception e
        {:ok false :error (.getMessage e)
         :live-thinking-printed? false :live-content-printed? false}))))

(defn- ollama-chat-round!
  [messages tools stream-opts]
  (if (use-streaming-chat-round?)
    (ollama-post-chat-stream! messages tools stream-opts)
    (-> (ollama-post-chat! messages tools)
        (assoc :live-thinking-printed? false :live-content-printed? false))))

(defn- message-thinking [body m]
  (or (some-> (:thinking body) str str/trim not-empty)
      (some-> (:thought body) str str/trim not-empty)
      (some-> (:thinking m) str str/trim not-empty)
      (some-> (:thought m) str str/trim not-empty)))

(defn- message-tool-calls [m]
  (vec (or (seq (:tool_calls m)) (seq (:toolCalls m)) [])))

(defn- execute-tool-call! [tc]
  (let [f (or (:function tc) (get tc "function"))
        nm (str (or (:name f) (get f "name") ""))
        args (or (:arguments f) (get f "arguments"))]
    (binding [*out* *err*]
      (cond
        (= nm "brave_web_search")
        (let [q (:query (brave/parse-web-search-args args))]
          (if (str/blank? q)
            (println "grog: tool" nm "(query missing or empty)")
            (println "grog: tool" nm (pr-str q))))
        (#{"read_workspace_file" "write_workspace_file" "read_office_document" "read_pdf_document" "ocr_pdf_document"
           "analyze_pdf_line_drawings"} nm)
        (if-let [p (some-> (fs/tool-log-path args) not-empty)]
          (println "grog: tool" nm (pr-str p))
          (println "grog: tool" nm "(path missing or empty)"))
        (#{"memory_save" "memory_load" "memory_list_keys"
           "memory_create_namespace" "memory_delete"} nm)
        (println "grog: tool" nm (pr-str (memory-tools/tool-log-summary nm args)))
        :else (println "grog: tool" nm)))
    (case nm
      "brave_web_search" (brave/run-web-search! args)
      "read_workspace_file" (fs/run-read-workspace-file! args)
      "write_workspace_file" (fs/run-write-workspace-file! args)
      "read_office_document" (fs/run-read-office-document! args)
      "read_pdf_document" (fs/run-read-pdf-document! args)
      "ocr_pdf_document" (fs/run-ocr-pdf-document! args)
      "analyze_pdf_line_drawings" (boofcv-pdf/run-analyze-pdf-line-drawings! args)
      "memory_save" (memory-tools/run-memory-save! args)
      "memory_load" (memory-tools/run-memory-load! args)
      "memory_list_keys" (memory-tools/run-memory-list-keys! args)
      "memory_create_namespace" (memory-tools/run-memory-create-namespace! args)
      "memory_delete" (memory-tools/run-memory-delete! args)
      (str "Unknown tool \"" nm "\". Available: read_workspace_file, write_workspace_file, read_office_document, read_pdf_document, ocr_pdf_document, analyze_pdf_line_drawings"
           (when (config/brave-search-configured?) ", brave_web_search")
           (when (edn-store/configured?)
             ", memory_save, memory_load, memory_list_keys, memory_create_namespace, memory_delete")
           "."))))

(defn- tool-result-messages [tool-calls]
  (mapv (fn [tc]
          (let [nm (str (or (get-in tc [:function :name])
                            (get-in tc ["function" "name"])
                            ""))]
            {:role "tool"
             :tool_name nm
             :content (execute-tool-call! tc)}))
        tool-calls))

(defn- chat-tools-payload []
  (vec (concat [(fs/read-workspace-file-tool-spec)
                (fs/write-workspace-file-tool-spec)
                (fs/read-office-document-tool-spec)
                (fs/read-pdf-document-tool-spec)
                (fs/ocr-pdf-document-tool-spec)
                (boofcv-pdf/analyze-pdf-line-drawings-tool-spec)]
               (when (config/brave-search-configured?)
                 [(brave/tool-spec)])
               (when (edn-store/configured?)
                 (memory-tools/tool-specs)))))

(defn- print-buffered-reply!
  "Print full answer (ANSI CommonMark when `format-markdown?`, else plain cyan)."
  [content answer-prefix]
  (when-not (str/blank? content)
    (print (or answer-prefix "\n"))
    (if (config/format-markdown?)
      (print (md-render/render-to-ansi content))
      (do (print ansi-answer)
          (print content)))
    (println)
    (print ansi-reset)
    (flush)))

(defn- chat-with-tools!
  "Run /api/chat, executing Ollama `tool_calls` (workspace read/write, Office/PDF, OCR, BoofCV lines, optional Brave,
  optional memory_* when :edn-store is set; with active project, dialog turns append to edn-store) until a text reply or error."
  ([messages] (chat-with-tools! messages {}))
  ([messages opts]
   (let [answer-prefix (or (:answer-prefix opts) (chat-answer-prefix))
         tools (chat-tools-payload)
         stream-opts {:answer-prefix answer-prefix}
         tool-limit (config/chat-tool-loop-limit)]
     (loop [msgs messages
            n 0
            last-thinking nil]
       (if (> n tool-limit)
         {:ok false
          :error (str "Tool loop limit exceeded (" tool-limit " rounds).")
          :thinking last-thinking}
         (let [{:keys [ok body error live-thinking-printed? live-content-printed?]}
               (ollama-chat-round! msgs tools stream-opts)]
           (if-not ok
             {:ok false :error error :thinking last-thinking}
             (let [m (:message body)
                   thinking (message-thinking body m)
                   tcalls (message-tool-calls m)
                   content (str (or (:content m) ""))]
               (if (seq tcalls)
                 (recur (into (conj msgs m) (tool-result-messages tcalls))
                        (inc n)
                        (or thinking last-thinking))
                 {:ok true :content content :thinking (or thinking last-thinking)
                  :live-thinking-printed? (boolean live-thinking-printed?)
                  :live-content-printed? (boolean live-content-printed?)
                  :answer-prefix answer-prefix})))))))))

(defn- brave-status-line []
  (if (config/brave-search-configured?)
    "Brave web search: enabled (Ollama may call `brave_web_search`)"
    "Brave web search: off — set :secrets :brave-search-api-key in grog.edn"))

(defn help-text []
  (str/join
   \newline
   ["Grog — simple local chat (Ollama /api/chat)."
    ""
    "Config merges: resources/grog.edn → ~/.config/grog/grog.edn → ./grog.edn"
    "Required: :ollama {:url … :model …}"
    "Optional: :workspace {:default-root \".\"} — SOUL path resolves here; SOUL file must stay under this root"
    "          :edn-store {:root \"edn-store\"} — optional .edn tree + memory_* tools; root under workspace"
    "          :soul {:path \"SOUL.md\"} — persistent instructions → model `system` message every request"
    "          :secrets {:brave-search-api-key \"…\"} — optional; enables Brave web search tool"
    "Tools (Ollama): read_workspace_file / write_workspace_file (UTF-8 under workspace); read_office_document (.docx/.xlsx); read_pdf_document (.pdf text); ocr_pdf_document (Tesseract OCR); analyze_pdf_line_drawings (BoofCV lines); brave_web_search if API key set; memory_* if :edn-store is set."
    "          :cli {:chat-history-turns N} — 0 = no memory; omit = unlimited"
    "              {:chat-show-thinking true|false} — Ollama thinking traces"
    "              {:chat-stream-live-thinking false} — buffer thinking; omit/true streams it live"
    "              {:chat-stream-live-content false} — buffer the answer; omit/true streams it in cyan"
    "              {:format-markdown false} — plain cyan text; omit/true renders replies as styled Markdown"
    "              {:chat-tool-loop-limit N} — max tool round-trips (default 8, max 1000)"
    ""
    "Thinking: dark green; assistant reply: cyan (or ANSI-styled Markdown when :format-markdown is true)."
    "Markdown in <text-markdown>…</text-markdown> or <text-markdown>…<text-markdown/> is parsed; GFM pipe tables draw as box tables."
    "Chat: before each chat> (or \"<project> >\") prompt, stderr reports context size (JSON kB + rough token est.) for messages on the next turn."
    "Models must support Ollama tool calling for these tools (many recent instruct models)."
    ""
    "Usage:"
    "  clojure -M:run chat              Interactive chat (use :run alias — see :run :jvm-opts for JDK 21+)"
    "  clojure -M:run \"your message\"    One-shot reply, then exit"
    "  clojure -M:run help"
    ""
    "In chat:"
    "  /help /clear /fresh  — help; clear session history"
    "  /project — list project dirs, or leave project mode if inside one; /project <name> — enter or switch project"
    "  /shell [command] — run one line via sh -lc under workspace cwd, or /shell alone for interactive subshell (exit to return)"
    "  /soul show|path|add <text>|reload — SOUL.md (reload re-reads grog.edn + SOUL path)"
    "  @path — inline a file (whitespace-separated tokens; echoed when read)"
    "  quit | exit | /quit"
    ""
    "Lines are sent as user messages; prior turns are included per :chat-history-turns."]))

(defn- chat-history-hint [cap]
  (cond
    (nil? cap) "history: all prior turns (/clear to reset)"
    (and (number? cap) (zero? (long cap))) "history: off (current line only)"
    (number? cap) (str "history: up to " (long cap) " prior user/assistant pair(s)")
    :else "history: invalid :cli :chat-history-turns"))

(defn- shell-cwd-file
  ^java.io.File []
  (.getCanonicalFile (io/file (config/workspace-root))))

(defn- run-shell-one-liner! [^String cmd]
  (try
    (let [{:keys [exit out err]} (sh/sh "sh" "-lc" cmd :dir (shell-cwd-file))]
      (when (some-> out not-empty) (print out) (flush))
      (when (some-> err not-empty)
        (binding [*out* *err*] (print err) (flush)))
      (when (not= 0 exit)
        (binding [*out* *err*] (println "Exit code:" exit))))
    (catch Exception e
      (binding [*out* *err*] (println "grog:/shell error:" (.getMessage e))))))

(defn- run-interactive-shell! []
  (try
    (let [sh-bin (or (not-empty (System/getenv "SHELL")) "/bin/sh")
          pb (doto (ProcessBuilder. [sh-bin])
               (.directory (shell-cwd-file))
               (.inheritIO))]
      (.waitFor (.start pb)))
    (catch Exception e
      (binding [*out* *err*] (println "grog:/shell error:" (.getMessage e))))))

(defn- handle-shell-command! [line]
  (when-let [[_ rest] (re-matches #"(?i)^/shell(?:\s+(.*))?$" (str/trim line))]
    (let [tail (str/trim (or rest ""))]
      (if (str/blank? tail)
        (run-interactive-shell!)
        (run-shell-one-liner! tail)))
    true))

(defn- handle-project-command! [line]
  (when-let [[_ r] (re-matches #"(?i)^/project(?:\s+(.*))?$" (str/trim line))]
    (let [tail (str/trim (or r ""))]
      (if (config/active-project-name)
        (if (str/blank? tail)
          (do (config/set-active-project! nil)
              (println "Left project mode (prompt is chat> again)."))
          (do (config/set-active-project! tail)
              (println "Switched to project:" (pr-str tail))))
        (if (str/blank? tail)
          (let [names (edn-store/list-memory-project-display-names)]
            (if (seq names)
              (do (println "Projects:")
                  (doseq [n names] (println " " n))
                  (println "Use /project <name> to enter."))
              (println "No projects yet (nothing under grog-memory/Projects/). /project <name> to start one.")))
          (do (config/set-active-project! tail)
              (println "Project:" (pr-str tail))))))
    true))

(defn- handle-soul-command! [line]
  (when (str/starts-with? line "/soul")
    (let [rest (str/trim (subs line (count "/soul")))]
      (cond
        (str/blank? rest)
        (println "SOUL: /soul show | path | add <markdown> | reload")
        (= "reload" rest)
        (try (config/reload!)
             (println "Config reloaded.")
             (println (soul/startup-status-line))
             (println (config/active-project-status-line))
             (catch Exception e (println "Error:" (.getMessage e))))
        (= "show" rest)
        (try (println (or (not-empty (soul/read-text)) "(empty or missing)"))
             (catch Exception e (println "Error:" (.getMessage e))))
        (= "path" rest)
        (try (println (soul/resolved-path))
             (catch Exception e (println "Error:" (.getMessage e))))
        (str/starts-with? rest "add ")
        (let [chunk (str/trim (subs rest (count "add ")))]
          (if (str/blank? chunk)
            (println "SOUL: /soul add <text> — text was empty")
            (try (soul/append-text! chunk)
                 (println "Appended to" (soul/resolved-path))
                 (println (soul/startup-status-line))
                 (catch Exception e (println "Error:" (.getMessage e))))))
        :else
        (println "SOUL: unknown; try show | path | add … | reload")))
    true))

(defn run-once!
  "Single user message → print assistant reply (stderr on failure)."
  [user-text]
  (when (str/blank? user-text)
    (binding [*out* *err*] (println "Empty message."))
    (System/exit 1))
  (config/warn-if-ollama-model-missing!)
  (let [user-text (if (str/includes? user-text "@")
                    (expand-line-at-paths user-text false)
                    user-text)
        msgs (conj (vec (system-messages)) {:role "user" :content user-text})]
    (try
      (let [{:keys [ok content thinking error live-thinking-printed? live-content-printed? answer-prefix]
             :or {answer-prefix "\n"}}
            (chat-with-tools! msgs {:answer-prefix "\n"})]
        (if ok
          (do (when (and (config/chat-show-thinking?) thinking (not live-thinking-printed?))
                (print-thinking! thinking))
              (when-not live-content-printed?
                (print-buffered-reply! content answer-prefix))
              (try
                (project-dialog/append-turn! :user user-text)
                (project-dialog/append-turn! :assistant (str content))
                (catch Exception _)))
          (do (binding [*out* *err*] (println error))
              (System/exit 1))))
      (catch Exception e
        (config/print-ollama-failure-hint! e)
        (System/exit 1)))))

(defn run-chat! []
  (let [cap (config/chat-history-turns)]
    (println "grog chat —" (chat-history-hint cap))
    (println (soul/startup-status-line))
    (println (brave-status-line))
    (println (fs/startup-status-line))
    (println (edn-store/startup-status-line))
    (println (config/active-project-status-line))
    (println (boofcv-pdf/startup-status-line))
    (config/warn-if-ollama-model-missing!)
    (print ansi-hot-pink)
    (println chat-initial-heading)
    (print ansi-reset)
    (flush)
    (println "Type /help. /shell runs host commands. quit / exit / /quit to stop. Blank line does nothing. Ctrl-D ends.")
    (println)
    (loop [history []]
      (if-some [line (read-chat-line-with-context! history)]
        (cond
            (#{"quit" "exit" "/quit" "/exit"} (str/lower-case line))
            nil
            (= "/help" (str/lower-case line))
            (do (println (help-text)) (println) (recur history))
            (#{"/clear" "/fresh"} (str/lower-case line))
            (do (println "History cleared.") (recur []))
            (handle-shell-command! line)
            (recur history)
            (handle-project-command! line)
            (recur history)
            (handle-soul-command! line)
            (recur history)
            :else
            (let [prompt (if (str/includes? line "@")
                           (expand-line-at-paths line true)
                           line)
                  recent (recent-history-for-cap history cap)
                  msgs (conj (history->messages (system-messages) recent)
                             {:role "user" :content prompt})]
              (recur
                (try
                  (let [{:keys [ok content thinking error live-thinking-printed? live-content-printed?
                                answer-prefix]}
                        (chat-with-tools! msgs)]
                    (if ok
                      (do (when (and (config/chat-show-thinking?) thinking (not live-thinking-printed?))
                            (print-thinking! thinking))
                          (when-not live-content-printed?
                            (print-buffered-reply! content answer-prefix))
                          (when live-content-printed?
                            (println))
                          (try
                            (project-dialog/append-turn! :user prompt)
                            (project-dialog/append-turn! :assistant (str content))
                            (catch Exception _))
                          (conj history {:user line :assistant content}))
                      (do (binding [*out* *err*] (println error))
                          history)))
                  (catch Exception e
                    (do (config/print-ollama-failure-hint! e)
                        history))))))
        nil))))

(defn- parse-chat-args [args]
  (doseq [a args]
    (when-not (#{"--verbose" "-v"} a)
      (binding [*out* *err*] (println "Unknown argument for chat:" a))
      (println (help-text))
      (System/exit 1))))

(defn -main [& args]
  (cond
    (empty? args) (println (help-text))
    (#{"help" "-h" "--help"} (first args)) (println (help-text))
    (= "chat" (first args))
    (do (parse-chat-args (rest args))
        (run-chat!))
    :else
    (run-once! (str/join " " (map expand-at-token args)))))
