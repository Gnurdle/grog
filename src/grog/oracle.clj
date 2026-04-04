(ns grog.oracle
  "Stronger remote model via OpenAI-compatible `POST …/chat/completions` (e.g. xAI Grok).
  Configure `:oracle` in grog.edn; legacy `:god` is still read. Ollama tool name **`oracle`**
  (aliases `pray`, `pray_about` accepted by the executor for old sessions)."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.image-png :as image-png]
            [grog.secrets :as secrets]))

(defn oracle-api-key
  "Bearer token: `ORACLE_API_KEY` in OS keyring, else legacy `GOD_API_KEY`."
  []
  (or (secrets/get-secret secrets/oracle-api-account)
      (secrets/get-secret secrets/god-api-account)))

(defn oracle-configured?
  "True when oracle URL, model, and an API key are all present."
  []
  (boolean
    (when (and (some-> (config/oracle-url) not-empty)
               (some-> (config/oracle-model) not-empty))
      (some-> (oracle-api-key) not-empty))))

(defn tool-spec
  []
  {:type "function"
   :function
   {:name "oracle"
    :description
    (str "Consult the **oracle** — a stronger remote model (`:oracle` in grog.edn; legacy `:god` supported). "
         "**Activate proactively** when the system message **Tool: oracle** applies: after a real try you still lack "
         "depth, the user wants expert-level help, or you are materially uncertain on something high-stakes. "
         "**Do not** use for casual chat, obvious answers, or tasks you can finish with files, web search, memory, "
         "or skills alone. One call per distinct need; **`query`** must be **self-contained**.")
    :parameters
    {:type "object"
     :required ["query"]
     :properties
     {:query {:type "string"
              :description "Single focused question for the oracle (all context it needs; it does not see other chat turns)."}}}}})

(defn system-prompt-block
  "Aligned with SOUL.md: when the oracle is configured, tell the model the tool name and when to use it."
  []
  (when (oracle-configured?)
    (str "## Tool: oracle (strong remote model)\n\n"
         "You have the **`oracle`** tool. It sends **one** user message (your **`query`**) to a **more capable remote "
         "model** and returns the response under the heading **Oracle reply** in the tool result.\n\n"
         "Use it **without waiting for the user to say “oracle”** when the following matches.\n\n"
         "### Call `oracle` when\n\n"
         "- You have **tried in good faith** (other tools where relevant) and still **cannot** answer well enough: "
         "hard logic, math, coding design, nuanced judgment, or domain depth beyond you.\n"
         "- The user **signals** they want stronger help (second opinion, expert-level answer, “ask something smarter”, "
         "or similar).\n"
         "- The task is **high-stakes** for the user and you remain **materially uncertain** after checking what you can.\n\n"
         "### Do **not** call `oracle` when\n\n"
         "- **Small talk**, simple lookups, or work solvable with workspace files, **brave_web_search**, **memory_***, "
         "or **skills** alone.\n"
         "- You would only be **avoiding** doing the work yourself.\n"
         "- **Cost / latency:** each call uses the remote API — do **not** send multiple `oracle` calls for one question.\n\n"
         "### How\n\n"
         "- **One** **`query`** per call; include every fact, constraint, and question the oracle must see (it does not "
         "see the rest of the chat unless you paste it into `query`).\n"
         "- In your reply to the user, **integrate** the oracle text honestly: quote, summarize, or disagree — do not "
         "pretend you authored it alone.\n"
         "- The oracle’s answer text may include **`<image-png>workspace/path.png</image-png>`** (or **`<image-png/>`** close); "
         "Grog opens those PNGs in a viewer when the **oracle** tool returns (same rules as normal assistant text).\n")))

(defn parse-oracle-args
  [arguments]
  (let [m (cond
            (map? arguments) arguments
            (string? arguments) (try (json/parse-string arguments true)
                                     (catch Exception _ {}))
            :else {})
        q (or (some-> (:query m) str str/trim not-empty)
              (some-> (get m "query") str str/trim not-empty)
              (some-> (:Query m) str str/trim not-empty)
              (some-> (get m "Query") str str/trim not-empty))]
    {:query q}))

(defn- extract-completion-text [parsed]
  (when (map? parsed)
    (some-> parsed :choices first :message :content str str/trim not-empty)))

(defn- finalize-tool-text
  "Run `<image-png>` handling on oracle tool output (opens viewer, strips tags) before returning to Ollama."
  ^String [^String s]
  (image-png/process-tags! s))

(defn run-oracle!
  [arguments]
  (if-not (oracle-configured?)
    (finalize-tool-text
     (str "oracle is not configured: set :oracle {:url … :model …} in grog.edn (legacy :god still works). "
          "Store the API token in the OS keyring as " (pr-str secrets/oracle-api-account)
          " (or legacy " (pr-str secrets/god-api-account) ") — e.g. /secret in chat."))
    (let [{:keys [query]} (parse-oracle-args arguments)]
      (if (str/blank? query)
        (finalize-tool-text "oracle error: missing or empty `query`.")
        (try
          (let [url (config/oracle-url)
                api-key (oracle-api-key)
                body-map {:model (config/oracle-model)
                          :messages [{:role "user" :content query}]
                          :max_tokens (config/oracle-max-tokens)
                          :temperature (config/oracle-temperature)}
                resp (http/post url
                                {:headers {"Authorization" (str "Bearer " api-key)
                                            "Content-Type" "application/json"
                                            "Accept" "application/json"}
                                 :body (json/generate-string body-map)
                                 :as :json
                                 :throw-exceptions false})
                st (:status resp)
                parsed (:body resp)]
            (finalize-tool-text
             (cond
               (= 200 st)
               (let [text (some-> (extract-completion-text parsed) str/trim not-empty)]
                 (if (str/blank? text)
                   "oracle: remote model returned an empty reply."
                   (str "## Oracle reply\n\n" text)))

               (and (map? parsed) (map? (:error parsed)))
               (str "oracle error: " (or (some-> parsed :error :message str)
                                         (pr-str (:error parsed))))

               :else
               (str "oracle HTTP " st ": " (pr-str parsed)))))
          (catch Exception e
            (finalize-tool-text (str "oracle failed: " (.getMessage e)))))))))
