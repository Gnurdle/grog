(ns grog.core
  (:gen-class)
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [grog.brave :as brave]
            [grog.config :as config]
            [grog.babashka :as babashka]
            [grog.boofcv-pdf :as boofcv-pdf]
            [grog.chat-context :as chat-ctx]
            [grog.chron :as chron]
            [grog.fs :as fs]
            [grog.jobs :as jobs]
            [grog.edn-store :as edn-store]
            [grog.memory-tools :as memory-tools]
            [grog.mcp :as mcp]
            [grog.mcp-store :as mcp-store]
            [grog.oracle :as oracle]
            [grog.pager :as pager]
            [grog.project-dialog :as project-dialog]
            [grog.readline :as gread]
            [grog.secrets :as secrets]
            [grog.skills :as skills]
            [grog.soul :as soul]
            [grog.with-api-key :as wkey]))

(def ^:private ansi-reset "\u001B[0m")
(def ^:private ansi-hot-pink "\u001B[38;2;255;105;180m")
(def ^:private ansi-thinking "\u001B[38;2;55;165;95m")
(def ^:private ansi-answer "\u001B[38;2;218;228;248m")

(def ^:private chat-initial-heading "I am Grog, Ye shall not Trifle with me.")

(defn- print-thinking! [s & {:keys [iter max]}]
  (when-not (str/blank? s)
    (print ansi-thinking)
    (println (cond
                 (and iter max) (str "── thinking " iter "/" max " ──")
                 iter (str "── thinking " iter " ──")
                 :else "── thinking ──"))
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


(defn- print-chat-context-line! [history]
  (let [msgs (chat-ctx/chat-context-messages history)
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
  "POST /api/chat with `stream: true` (always; supports Esc cancel via closing the body).
  Prints thinking / answer per :cli live-stream settings."
  [messages tools stream-opts]
  (let [answer-prefix (or (:answer-prefix stream-opts) (chat-answer-prefix))
        thinking-iter (:thinking-iter stream-opts)
        thinking-max (:thinking-max stream-opts)]
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
               :live-thinking-printed? false :live-content-printed? false
               :cancelled? false})
          (with-open [^java.io.Closeable stream (:body resp)]
            (gread/reset-chat-cancel!)
            (let [stop-watch (or (gread/start-escape-cancel-watcher!
                                  (fn []
                                    (gread/mark-chat-cancel!)
                                    (binding [*out* *err*]
                                      (println "\ngrog: cancelled (Esc)"))
                                    (try (.close ^java.io.Closeable stream)
                                         (catch Exception _))))
                                (fn []))
                  rdr (io/reader stream)
                  think-buf (StringBuilder.)
                  content-buf (StringBuilder.)
                  in-thinking (atom false)
                  any-live (atom false)
                  started-answer (atom false)
                  any-content-live (atom false)]
              (try
                (letfn [(finish! [last-msg last-tc {:keys [cancelled?] :or {cancelled? false}}]
                          (when @in-thinking
                            (print ansi-reset)
                            (println)
                            (reset! in-thinking false))
                          (let [md? (config/format-markdown?)
                                stream-live? (config/chat-stream-live-content?)
                                buf-len (.length content-buf)]
                            (when (and (pos? buf-len) (or (not stream-live?) md?))
                              (pager/emit-final-reply! {:answer-prefix answer-prefix
                                                        :raw-content (str content-buf)
                                                        :ansi-answer ansi-answer
                                                        :ansi-reset ansi-reset})))
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
                             :live-content-printed? (boolean (or streamed? dumped-buf?))
                             :cancelled? (boolean cancelled?)}))]
                  (loop [last-seen-msg nil
                         last-tc nil]
                    (let [line (try (.readLine ^java.io.BufferedReader rdr)
                                    (catch java.io.IOException e
                                      (if (gread/chat-cancel-requested?)
                                        ::io-cancel
                                        (throw e))))]
                      (cond
                        (= line ::io-cancel)
                        (finish! last-seen-msg last-tc {:cancelled? true})

                        (nil? line)
                        (finish! last-seen-msg last-tc nil)

                        (str/blank? line)
                        (recur last-seen-msg last-tc)

                        :else
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
                                  (print (cond
                                           (and thinking-iter thinking-max)
                                           (str "── thinking " thinking-iter "/" thinking-max " ──\n")
                                           thinking-iter
                                           (str "── thinking " thinking-iter " ──\n")
                                           :else "── thinking ──\n")))
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
                                (finish! last-msg' last-tc' nil)
                                (recur last-msg' last-tc')))))))))
                (finally
                  (stop-watch)
                  (gread/reset-chat-cancel!)))))))
      (catch Exception e
        {:ok false :error (.getMessage e)
         :live-thinking-printed? false :live-content-printed? false
         :cancelled? false}))))

(defn- ollama-chat-round!
  [messages tools stream-opts]
  (ollama-post-chat-stream! messages tools stream-opts))

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
        (= nm "oracle")
        (let [q (:query (oracle/parse-oracle-args args))]
          (if (str/blank? q)
            (println "grog: tool" nm "(query missing or empty)")
            (println "grog: tool" nm (pr-str (if (> (count q) 120) (str (subs q 0 120) "…") q)))))
        (= nm "crop_workspace_image")
        (if-let [ln (some-> (fs/tool-log-crop-line args) not-empty)]
          (println "grog: tool" nm ln)
          (println "grog: tool" nm "(source_path or out_path missing)"))
        (#{"read_workspace_file" "read_workspace_dir" "write_workspace_file" "write_workspace_png" "read_office_document"
           "read_pdf_document" "ocr_pdf_document" "analyze_pdf_line_drawings"} nm)
        (if-let [p (some-> (fs/tool-log-path args) not-empty)]
          (println "grog: tool" nm (pr-str p))
          (println "grog: tool" nm "(path missing or empty)"))
        (#{"memory_save" "memory_load" "memory_list_keys" "memory_namespaces"
           "memory_create_namespace" "memory_delete"} nm)
        (println "grog: tool" nm (pr-str (memory-tools/tool-log-summary nm args)))
        (#{"list_skills" "read_skill" "save_skill" "delete_skill"} nm)
        (println "grog: tool" nm
                 (when (#{"read_skill" "save_skill" "delete_skill"} nm)
                   (let [m (cond (map? args) args
                                 (string? args) (try (json/parse-string args true) (catch Exception _ {}))
                                 :else {})
                         id (or (:id m) (get m "id"))]
                     (str " " (pr-str id)))))
        (= nm "with_api_key")
        (println "grog: tool" nm (wkey/tool-log-summary args))
        (= nm "run_babashka")
        (println "grog: tool" nm (babashka/tool-log-summary args))
        (mcp/mcp-admin-tool-name? nm)
        (when-let [s (mcp/tool-log-summary nm args)]
          (println "grog: tool" nm (pr-str s)))
        (mcp/tool-name-mcp? nm)
        (when-let [s (mcp/tool-log-summary nm args)]
          (println "grog: tool" nm (pr-str s)))
        :else (println "grog: tool" nm)))
    (cond
      (= nm "brave_web_search") (brave/run-web-search! args)
      (= nm "read_workspace_file") (fs/run-read-workspace-file! args)
      (= nm "read_workspace_dir") (fs/run-read-workspace-dir! args)
      (= nm "write_workspace_file") (fs/run-write-workspace-file! args)
      (= nm "write_workspace_png") (fs/run-write-workspace-png! args)
      (= nm "crop_workspace_image") (fs/run-crop-workspace-image! args)
      (= nm "read_office_document") (fs/run-read-office-document! args)
      (= nm "read_pdf_document") (fs/run-read-pdf-document! args)
      (= nm "ocr_pdf_document") (fs/run-ocr-pdf-document! args)
      (= nm "analyze_pdf_line_drawings") (boofcv-pdf/run-analyze-pdf-line-drawings! args)
      (= nm "memory_save") (memory-tools/run-memory-save! args)
      (= nm "memory_load") (memory-tools/run-memory-load! args)
      (= nm "memory_list_keys") (memory-tools/run-memory-list-keys! args)
      (= nm "memory_namespaces") (memory-tools/run-memory-namespaces! args)
      (= nm "memory_create_namespace") (memory-tools/run-memory-create-namespace! args)
      (= nm "memory_delete") (memory-tools/run-memory-delete! args)
      (= nm "list_skills") (skills/run-list-skills! args)
      (= nm "read_skill") (skills/run-read-skill! args)
      (= nm "save_skill") (skills/run-save-skill! args)
      (= nm "delete_skill") (skills/run-delete-skill! args)
      (= nm "oracle") (oracle/run-oracle! args)
      (= nm "with_api_key") (wkey/run-with-api-key! args)
      (= nm "run_babashka") (babashka/run-babashka! args)
      (mcp/mcp-admin-tool-name? nm) (mcp/run-mcp-admin-tool! nm args)
      (mcp/tool-name-mcp? nm) (mcp/run-tool! nm args)
      :else
      (str "Unknown tool \"" nm "\". Available: read_workspace_file, read_workspace_dir, write_workspace_file, write_workspace_png, crop_workspace_image, read_office_document, read_pdf_document, ocr_pdf_document, analyze_pdf_line_drawings"
           (when (config/skills-configured?) ", list_skills, read_skill, save_skill, delete_skill")
           (when (brave/brave-search-configured?) ", brave_web_search")
           (when (oracle/oracle-configured?) ", oracle")
           (when (config/with-api-key-configured?) ", with_api_key")
           (when (config/babashka-configured?) ", run_babashka")
           (when (edn-store/configured?)
             ", memory_save, memory_load, memory_list_keys, memory_namespaces, memory_create_namespace, memory_delete; mcp_config_load, mcp_config_save, mcp_servers_set, mcp_reload; after mcp_reload also <serverId>_<toolName> for MCP")
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
                (fs/read-workspace-dir-tool-spec)
                (fs/write-workspace-file-tool-spec)
                (fs/write-workspace-png-tool-spec)
                (fs/crop-workspace-image-tool-spec)
                (fs/read-office-document-tool-spec)
                (fs/read-pdf-document-tool-spec)
                (fs/ocr-pdf-document-tool-spec)
                (boofcv-pdf/analyze-pdf-line-drawings-tool-spec)]
               (when (brave/brave-search-configured?)
                 [(brave/tool-spec)])
               (when (oracle/oracle-configured?)
                 [(oracle/tool-spec)])
               (when (edn-store/configured?)
                 (memory-tools/tool-specs))
               (when (config/skills-configured?)
                 [(skills/list-skills-tool-spec)
                  (skills/read-skill-tool-spec)
                  (skills/save-skill-tool-spec)
                  (skills/delete-skill-tool-spec)])
               (when (config/with-api-key-configured?)
                 [(wkey/tool-spec)])
               (when (config/babashka-configured?)
                 [(babashka/tool-spec)])
               (or (mcp/tool-specs-for-chat) []))))

(defn- tool-spec-name [spec]
  (str (or (get-in spec [:function :name])
           (get-in spec ["function" "name"])
           "")))

(defn- tool-spec-description [spec]
  (str (or (get-in spec [:function :description])
           (get-in spec ["function" "description"])
           "")))

(defn- description-one-liner [desc]
  (let [s (-> (str desc) str/trim (str/replace #"\s+" " "))]
    (if (str/blank? s)
      "(no description)"
      (let [by-sentence (first (str/split s #"(?<=[.!?])\s+"))
            cand (if (str/blank? by-sentence) s by-sentence)]
        (if (> (count cand) 130)
          (str (subs cand 0 127) "…")
          cand)))))

(defn- active-tool-rows []
  (->> (chat-tools-payload)
       (map (fn [spec]
              (let [n (tool-spec-name spec)]
                {:name n
                 :role (description-one-liner (tool-spec-description spec))})))
       (remove (comp str/blank? :name))
       (sort-by :name)
       vec))

(defn- print-active-tools! []
  (println "Ollama tools in this session (same set sent to the model):")
  (doseq [{:keys [name role]} (active-tool-rows)]
    (println " " name "—" role))
  (println "Optional tools: grog.edn (:oracle, :with-api-key, :edn-store, …); MCP via edn-store + /mcp or mcp_* tools; Brave/oracle/with_api_key use the OS keyring (/secret)."))

(defn- handle-tools-command! [line]
  (when (re-matches #"(?i)^/tools$" (str/trim line))
    (print-active-tools!)
    true))

(defn- handle-skills-command! [line]
  (when-let [m (re-matches #"(?i)^/skills(?:\s+(.+))?$" (str/trim line))]
    (if-let [id-raw (some-> (second m) str str/trim not-empty)]
      (skills/print-cli-skill-body! id-raw)
      (skills/print-cli-summary!))
    true))

(defn- print-buffered-reply!
  "Show full answer via `grog.pager` (less-style when enabled) or inline.
  `<image-png>path</image-png>` opens a PNG from the workspace in a Swing viewer (see `grog.image-png`)."
  [content answer-prefix]
  (pager/emit-final-reply! {:answer-prefix answer-prefix
                          :raw-content content
                          :ansi-answer ansi-answer
                          :ansi-reset ansi-reset}))

(defn- chat-with-tools!
  "Run /api/chat, executing Ollama `tool_calls` (workspace file/dir read/write/PNG, Office/PDF, OCR, BoofCV lines, optional Brave,
  optional oracle tool, optional memory_* when :edn-store is set; with active project, dialog turns append to edn-store) until a text reply or error."
  ([messages] (chat-with-tools! messages {}))
  ([messages opts]
   (let [answer-prefix (or (:answer-prefix opts) (chat-answer-prefix))
         tools (chat-tools-payload)
         tool-limit (config/chat-tool-loop-limit)
         iter-max (when tool-limit (inc tool-limit))]
     (loop [msgs messages
            n 0
            last-thinking nil]
       (if (and tool-limit (> n tool-limit))
         {:ok false
          :error (str "Tool loop limit exceeded (" tool-limit " rounds). Set :cli :chat-tool-loop-limit higher or remove it for no limit.")
          :thinking last-thinking}
         (let [stream-opts (cond-> {:answer-prefix answer-prefix
                                    :thinking-iter (inc n)}
                            iter-max (assoc :thinking-max iter-max))
               {:keys [ok body error live-thinking-printed? live-content-printed? cancelled?]
                :or {cancelled? false}}
               (ollama-chat-round! msgs tools stream-opts)]
           (if-not ok
             {:ok false :error error :thinking last-thinking}
             (let [m (:message body)
                   thinking (message-thinking body m)
                   tcalls (message-tool-calls m)
                   content (str (or (:content m) ""))
                   ti (inc n)]
               (if cancelled?
                 {:ok true :content content :thinking (or thinking last-thinking)
                  :live-thinking-printed? (boolean live-thinking-printed?)
                  :live-content-printed? (boolean live-content-printed?)
                  :answer-prefix answer-prefix
                  :cancelled? true
                  :thinking-iter ti
                  :thinking-max iter-max}
                 (if (seq tcalls)
                   (recur (into (conj msgs m) (tool-result-messages tcalls))
                          (inc n)
                          (or thinking last-thinking))
                   {:ok true :content content :thinking (or thinking last-thinking)
                    :live-thinking-printed? (boolean live-thinking-printed?)
                    :live-content-printed? (boolean live-content-printed?)
                    :answer-prefix answer-prefix
                    :cancelled? false
                    :thinking-iter ti
                    :thinking-max iter-max}))))))))))

(defn run-tool-loop-on-messages
  "Run one full Ollama tool loop from initial `messages` (for `/jobs`, chron, etc.). Returns same map as internal chat round."
  [messages & {:keys [answer-prefix] :or {answer-prefix "\n\n[grog] "}}]
  (chat-with-tools! messages {:answer-prefix answer-prefix}))

(defn- brave-status-line []
  (if (brave/brave-search-configured?)
    "Brave web search: enabled (Ollama may call `brave_web_search`)"
    (str "Brave web search: off — store token in OS keyring (service \"grog\", account \""
         secrets/brave-search-api-account
         "\") or /secret in chat")))

(defn- oracle-status-line []
  (if (oracle/oracle-configured?)
    "oracle: enabled (remote model per :oracle in grog.edn — use sparingly per SOUL.md)"
    (str "oracle: off — set :oracle {:url … :model …} and keyring "
         (pr-str secrets/oracle-api-account) " (or /secret)")))

(defn- with-api-key-status-line []
  (if (config/with-api-key-configured?)
    (str "with_api_key: enabled — allowed secret names " (pr-str (vec (config/with-api-key-allowed-accounts)))
         (if-let [ps (seq (config/with-api-key-url-prefixes))]
           (str " · URL prefix allowlist " (pr-str (vec ps)))
           ""))
    "with_api_key: off — set :with-api-key {:allowed-secrets […]} in grog.edn (legacy :allowed-accounts; /secret keys only)"))

(defn- mcp-status-line []
  (mcp/startup-status-line))

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
    "          :skills {:roots [\"skills\"]} — each skill is <root>/<name>/skill.edn + SKILL.md; /skills in chat; list_skills, read_skill, save_skill, delete_skill (writes use first root only); :max-body-chars, :prompt-skill-lines"
    "API keys (Brave, oracle): OS keyring only — service \"grog\", accounts BRAVE_SEARCH_API and ORACLE_API_KEY; set via /secret in chat"
    "          :oracle {:url \"https://…/v1/chat/completions\" :model \"…\"} — optional remote model for tool oracle; :max-tokens, :temperature"
    "          :with-api-key {:allowed-secrets [\"BRAVE_SEARCH_API\" …]} — tool with_api_key (HTTP + keyring secret via secret_method; legacy :allowed-accounts); each name must exist in /secret; optional :allowed-url-prefixes, :max-response-chars, :allow-insecure-http"
    "Tools (Ollama): workspace read_workspace_dir + read/write files + write_workspace_png + crop_workspace_image; Office/PDF/OCR/BoofCV; list_skills/read_skill/save_skill/delete_skill if :skills :roots set; brave_web_search if configured; oracle if :oracle + ORACLE_API_KEY set; with_api_key if :with-api-key :allowed-secrets (or :allowed-accounts) set; run_babashka if :babashka :enabled (Babashka bb on PATH); memory_* and MCP admin tools (mcp_config_load, mcp_config_save, mcp_servers_set, mcp_reload) if :edn-store is set; after mcp_reload, MCP remote tools as serverId_toolName (stdio, NDJSON — same as Node/Java MCP SDK)."
    "          MCP server list is stored in edn-store (project-scoped): grog-memory/grog-mcp/servers.edn or under Projects/<project>/ — configure with /mcp or the mcp_* tools; subprocesses start only after mcp_reload (not at chat startup)."
    "          :chron {:enabled true :tasks [{:id \"…\" :every-minutes 30 :instruction \"…\"}]} — periodic Ollama+tools while chat runs (stderr banner); optional :interval-seconds"
    "          :jobs {:max-thread-turns 40} — project dialog turns injected for /jobs and chron (default 40)"
    "          :cli {:chat-history-turns N} — 0 = no memory; omit = unlimited"
    "              {:chat-show-thinking true|false} — Ollama thinking traces"
    "              {:chat-stream-live-thinking false} — buffer thinking; omit/true streams it live"
    "              {:chat-stream-live-content false} — buffer the answer; omit/true streams it in cyan"
    "              {:format-markdown false} — plain cyan text; omit/true renders replies as styled Markdown"
    "              {:reply-pager false} — print assistant replies inline; omit/true uses less -R -F -X when possible"
    "              {:chat-tool-loop-limit N} — optional positive cap on tool round-trips; omit for no limit"
    ""
    "Thinking: dark green; assistant reply: cyan (or ANSI-styled Markdown when :format-markdown is true)."
    "Markdown in <text/markdown>…</text/markdown> or <text/markdown>…<text/markdown/> is parsed (legacy <text-markdown> still works); GFM pipe tables draw as box tables."
    "<image-png>workspace-relative/path.png</image-png> or <image-png>…<image-png/> (case-insensitive) opens that PNG in a Swing window; path must be under :workspace :default-root (requires display / non-headless JVM)."
    "Chat: prompt chat> or <project> >. Before each line, stderr reports context size (JSON kB + rough token est.). When :chat-show-thinking is true, each Ollama round opens with a thinking banner `── thinking k/n ──` if :chat-tool-loop-limit is set, else `── thinking k ──`. Esc during assistant output cancels generation (partial reply kept); needs JLine terminal (not plain stdin)."
    "Models must support Ollama tool calling for these tools (many recent instruct models)."
    ""
    "Usage:"
    "  clojure -M:chat                  Interactive chat (same JVM opts as :run)"
    "  clojure -M:run chat              same as -M:chat"
    "  clojure -M:run \"your message\"    One-shot reply, then exit"
    "  clojure -M:run help"
    ""
    "In chat:"
    "  /help /clear /fresh  — help; clear session history"
    "  /tools — list Ollama tool names plus a one-line role from each tool’s description (reflects current grog.edn)"
    "  /skills — list skills when :skills :roots is set; /skills <id> — print SKILL.md (same as read_skill)"
    "  /project — list project dirs, or leave project mode if inside one; /project <name> — enter or switch project"
    "  /jobs — add|list|next|status (needs :edn-store + active project); queue in memory namespace grog-jobs under the project"
    "  /chron — show chron scheduler status"
    "  /shell [command] — run one line via sh -lc under workspace cwd, or /shell alone for interactive subshell (exit to return)"
    "  /secret — list known secret keys and set/unset status (never prints values); /secret <KEY> <value> — store in OS keyring (service grog)"
    "  /mcp — MCP server list in edn-store (/mcp help); load|save|reload|set <edn>|show [memory|disk]"
    "  /soul show|path|add <text>|reload — SOUL.md (reload re-reads grog.edn + SOUL path + MCP store file for active project)"
    "  @path — inline a file (whitespace-separated tokens; echoed when read)"
    "  quit | exit | /quit"
    "  Esc — while the model is streaming a reply, press Esc to stop early (partial text kept; JLine TTY only)"
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

(defn- handle-jobs-command! [line]
  (when-let [[_ rest] (re-matches #"(?i)^/jobs(?:\s+(.*))?$" (str/trim line))]
    (let [r (str/trim (or rest ""))]
      (cond
        (str/blank? r)
        (do (println "/jobs add <goal…>  — enqueue (requires active project + :edn-store)")
            (println "/jobs list   — queue items for current project")
            (println "/jobs next   — run next pending job (Ollama + tools)")
            (println "/jobs status — counts"))
        (re-matches #"(?i)^add$" r)
        (println "Usage: /jobs add <goal>")
        (re-matches #"(?i)^add\s+.+" r)
        (let [goal (str/trim (subs r 3))]
          (if-let [p (config/active-project-name)]
            (let [out (jobs/add-job! p goal)]
              (println (if (:ok out) (str "Job enqueued " (:id out)) (str "Error: " (:error out)))))
            (println "Set a project first: /project <name>")))
        (re-matches #"(?i)^list$" r)
        (if-let [p (config/active-project-name)]
          (doseq [i (jobs/list-items p)]
            (println (str (or (:status i) :pending)) (:id i) "-" (str/trim (str (:goal i)))))
          (println "No active project."))
        (re-matches #"(?i)^status$" r)
        (if-let [p (config/active-project-name)]
          (let [xs (jobs/list-items p)
                pend (count (filter #(= :pending (or (:status %) :pending)) xs))]
            (println "Project" (pr-str p) "-" (count xs) "job(s)," pend "pending"))
          (println "No active project."))
        (re-matches #"(?i)^next$" r)
        (if-let [p (config/active-project-name)]
          (let [out (jobs/run-next-job! p)]
            (if (:ok out)
              (println "Job finished" (:job-id out))
              (println "Job run:" (:error out))))
          (println "No active project."))
        :else
        (println "Unknown /jobs — try /jobs with no args for help")))
    true))

(defn- handle-chron-command! [line]
  (when (re-matches #"(?i)^/chron$" (str/trim line))
    (println (chron/status-line))
    true))

(defn- handle-mcp-command! [line]
  (when-let [[_ rest] (re-matches #"(?i)^/mcp(?:\s+(.*))?$" (str/trim line))]
    (let [tail (str/trim (or rest ""))
          tl (str/lower-case tail)]
      (try
        (cond
          (str/blank? tail)
          (do (println "/mcp help | status | show [memory|disk] | load | save | reload | set <edn>")
              (when (edn-store/configured?)
                (println "Persisted:" (mcp-store/store-path-hint)))
              (println (mcp/startup-status-line)))
          (= tl "help")
          (do (println "/mcp status | show [memory|disk] | load | save | reload | set <edn>")
              (when (edn-store/configured?)
                (println "Persisted:" (mcp-store/store-path-hint))))
          (= tl "status")
          (println (mcp/startup-status-line))
          (str/starts-with? tl "set ")
          (let [s (str/trim (subs tail (count "set ")))]
            (mcp/set-declared-servers-from-edn-string! s)
            (println "MCP declarations updated in memory."
                     (count (mcp/declared-servers-raw))
                     "entries — /mcp reload or tool mcp_reload to spawn."))
          :else
          (let [parts (str/split tail #"\s+")
                c (str/lower-case (first parts))
                arg (str/trim (str/join " " (rest parts)))]
            (case c
              "show" (case (str/lower-case arg)
                       "" (do (println "— in-memory —")
                              (prn (mcp/declared-servers-raw))
                              (if (edn-store/configured?)
                                (do (println "— disk —")
                                    (prn (mcp-store/read-servers-map)))
                                (println "— disk: :edn-store not set —")))
                       "memory" (do (println "— in-memory —")
                                    (prn (mcp/declared-servers-raw)))
                       "disk" (if (edn-store/configured?)
                                (do (println "— disk —")
                                    (prn (mcp-store/read-servers-map)))
                                (println ":edn-store not configured."))
                       (println "/mcp show | show memory | show disk"))
              "load" (if (edn-store/configured?)
                       (do (mcp/try-load-declared-config!)
                           (println "Loaded from store. Declared entries:"
                                    (count (mcp/declared-servers-raw))))
                       (println ":edn-store not configured — cannot load."))
              "save" (if (edn-store/configured?)
                       (do (mcp/save-declared-config-to-store!)
                           (println "Saved" (mcp-store/store-path-hint)))
                       (println ":edn-store not configured — cannot save."))
              "reload" (if-not (mcp/has-declared-servers?)
                         (println "No valid server entries — /mcp set <edn> or /mcp load first.")
                         (println (pr-str (mcp/reload-running-servers!))))
              (println "Unknown /mcp — try /mcp with no args."))))
        (catch Exception e
          (println "grog:/mcp error:" (.getMessage e)))))
    true))

(defn- handle-project-command! [line]
  (when-let [[_ r] (re-matches #"(?i)^/project(?:\s+(.*))?$" (str/trim line))]
    (let [tail (str/trim (or r ""))]
        (if (config/active-project-name)
        (if (str/blank? tail)
          (do (config/set-active-project! nil)
              (mcp/try-load-declared-config!)
              (println "Left project mode (prompt is chat> again)."))
          (do (config/set-active-project! tail)
              (mcp/try-load-declared-config!)
              (println "Switched to project:" (pr-str tail))))
        (if (str/blank? tail)
          (let [names (edn-store/list-memory-project-display-names)]
            (if (seq names)
              (do (println "Projects:")
                  (doseq [n names] (println " " n))
                  (println "Use /project <name> to enter."))
              (println "No projects yet (nothing under grog-memory/Projects/). /project <name> to start one.")))
          (do (config/set-active-project! tail)
              (mcp/try-load-declared-config!)
              (println "Project:" (pr-str tail))))))
    true))

(defn- parse-secret-key-value [tail]
  (when-not (str/blank? tail)
    (when-let [[_ k v] (re-matches #"(\S+)\s+([\s\S]+)" tail)]
      (let [k (str/trim k)
            v (str/trim v)]
        (when-not (or (str/blank? k) (str/blank? v))
          [k v])))))

(defn- handle-secret-command! [line]
  (when-let [[_ rest] (re-matches #"(?i)^/secret(?:\s+(.*))?$" (str/trim line))]
    (let [tail (str/trim (or rest ""))]
      (if (str/blank? tail)
        (secrets/print-known-secrets-summary!)
        (if-let [[k v] (parse-secret-key-value tail)]
          (try
            (secrets/set-secret! k v)
            (println "Stored" (pr-str k) "in OS secret store (service \"grog\").")
            (catch Exception e
              (println "grog:/secret error:" (.getMessage e))))
          (println "grog:/secret: need /secret <KEY> <value> (value may contain spaces). Use /secret alone to list keys."))))
    true))

(defn- handle-soul-command! [line]
  (when (str/starts-with? line "/soul")
    (let [rest (str/trim (subs line (count "/soul")))]
      (cond
        (str/blank? rest)
        (println "SOUL: /soul show | path | add <markdown> | reload")
        (= "reload" rest)
        (try (mcp/stop-all!)
             (config/reload!)
             (mcp/try-load-declared-config!)
             (println "Config reloaded.")
             (println (soul/startup-status-line))
             (println (skills/startup-status-line))
             (println (config/active-project-status-line))
             (println (mcp/startup-status-line))
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
  (mcp/try-load-declared-config!)
  (let [user-text (if (str/includes? user-text "@")
                    (expand-line-at-paths user-text false)
                    user-text)
        msgs (conj (vec (chat-ctx/system-messages)) {:role "user" :content user-text})]
    (try
      (let [{:keys [ok content thinking error live-thinking-printed? live-content-printed? answer-prefix
                    cancelled? thinking-iter thinking-max]
             :or {answer-prefix "\n" cancelled? false}}
            (chat-with-tools! msgs {:answer-prefix "\n"})]
        (if ok
          (do (when (and (config/chat-show-thinking?) thinking (not live-thinking-printed?))
                (print-thinking! thinking :iter thinking-iter :max thinking-max))
              (when-not live-content-printed?
                (print-buffered-reply! content answer-prefix))
              (when cancelled?
                (binding [*out* *err*]
                  (println "grog: partial reply (Esc stopped generation).")))
              (try
                (project-dialog/append-turn! :user user-text)
                (project-dialog/append-turn! :assistant (str content))
                (catch Exception _)))
          (do (binding [*out* *err*] (println error))
              (System/exit 1))))
      (catch Exception e
        (config/print-ollama-failure-hint! e)
        (System/exit 1))
      (finally
        (mcp/stop-all!)))))

(defn run-chat! []
  (println "grog chat —" (chat-history-hint (config/chat-history-turns)))
    (println (soul/startup-status-line))
    (println (skills/startup-status-line))
    (println (secrets/startup-status-line))
    (println (brave-status-line))
    (println (oracle-status-line))
    (println (with-api-key-status-line))
    (println (fs/startup-status-line))
    (println (edn-store/startup-status-line))
    (println (config/active-project-status-line))
    (println (boofcv-pdf/startup-status-line))
    (println (babashka/startup-status-line))
    (mcp/try-load-declared-config!)
    (println (mcp-status-line))
    (println (chron/status-line))
    (println "grog: active :cli"
             (pr-str {:chat-history-turns (config/chat-history-turns)
                      :chat-tool-loop-limit (config/chat-tool-loop-limit)
                      :reply-pager (config/reply-pager?)}))
    (config/warn-if-ollama-model-missing!)
    (print ansi-hot-pink)
    (println chat-initial-heading)
    (print ansi-reset)
    (flush)
    (println "Type /help, /tools, or /skills. /secret for OS keyring; /shell runs host commands; /jobs and /chron when :edn-store / :chron are set. quit / exit / /quit to stop. Blank line does nothing. Ctrl-D ends. Esc during assistant output stops generation (partial reply kept).")
    (println)
    (try
      (chron/start!)
      (loop [history []]
        (if-some [line (read-chat-line-with-context! history)]
          (cond
            (#{"quit" "exit" "/quit" "/exit"} (str/lower-case line))
            nil
            (= "/help" (str/lower-case line))
            (do (println (help-text)) (println) (recur history))
            (handle-tools-command! line)
            (recur history)
            (handle-skills-command! line)
            (recur history)
            (#{"/clear" "/fresh"} (str/lower-case line))
            (do (println "History cleared.") (recur []))
            (handle-shell-command! line)
            (recur history)
            (handle-project-command! line)
            (recur history)
            (handle-jobs-command! line)
            (recur history)
            (handle-chron-command! line)
            (recur history)
            (handle-mcp-command! line)
            (recur history)
            (handle-secret-command! line)
            (recur history)
            (handle-soul-command! line)
            (recur history)
            :else
            (let [prompt (if (str/includes? line "@")
                           (expand-line-at-paths line true)
                           line)
                  recent (chat-ctx/recent-history-for-cap history (config/chat-history-turns))
                  msgs (conj (chat-ctx/history->messages (chat-ctx/system-messages) recent)
                             {:role "user" :content prompt})]
              (recur
                (try
                  (let [{:keys [ok content thinking error live-thinking-printed? live-content-printed?
                                answer-prefix cancelled? thinking-iter thinking-max]
                         :or {cancelled? false}}
                        (chat-with-tools! msgs)]
                    (if ok
                      (do (when (and (config/chat-show-thinking?) thinking (not live-thinking-printed?))
                            (print-thinking! thinking :iter thinking-iter :max thinking-max))
                          (when-not live-content-printed?
                            (print-buffered-reply! content answer-prefix))
                          (when live-content-printed?
                            (println))
                          (when cancelled?
                            (binding [*out* *err*]
                              (println "grog: assistant output stopped early (Esc) — partial reply kept in history.")))
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
          nil))
      (finally
        (chron/stop!)
        (mcp/stop-all!))))

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
