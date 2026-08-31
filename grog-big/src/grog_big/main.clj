(ns grog-big.main
  "grog-big — a standalone MCP server (over stdio) exposing the **big remote
  model as a tool** for a small local model.

  Pattern (\"local orchestrator, remote specialist\"):
    * The primary agent runs a small local model (Qwen3 8B on the RTX 4070
      Max-Q) that is fast and cheap for the everyday tool loop.
    * When the local model decides a task is too hard / needs deep reasoning,
      a big, current model (DeepSeek via OpenRouter / LiteLLM relay) is better.
    * This server exposes that big model as a normal MCP tool, so the local
      agent can *call it* the same way it calls `run_babashka` or `brave_web_search`.

  Endpoint configuration via env vars:
    GROG_BIG_URL    OpenAI-compatible /v1 base (default http://localhost:4000/v1 — LiteLLM relay)
    GROG_BIG_MODEL  model name to call on that endpoint (default \"big\")
    GROG_BIG_API_KEY bearer key (default \"sk-dummy\", matches the default LiteLLM master key)

  ECA discovers this over stdio like the other grog servers:
    :mcpServers
      {\"grog-big\"
        {:command \"bash\"
         :args [\"-lc\" \"cd '<root>/grog-big' && clojure -M:mcp -m grog-big.main\"]
         :env {\"GROG_BIG_URL\" \"http://localhost:4000/v1\"
               \"GROG_BIG_MODEL\" \"big\"
               \"GROG_BIG_API_KEY\" \"sk-dummy\"}}}
  "
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clj-http.client :as http])
  (:import [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
           [io.modelcontextprotocol.server McpServer]
           [io.modelcontextprotocol.server McpServerFeatures$AsyncToolSpecification]
           [io.modelcontextprotocol.spec
            McpSchema$ServerCapabilities McpSchema$Tool McpSchema$CallToolResult
            McpSchema$TextContent]
           [reactor.core.publisher Mono]
           [com.fasterxml.jackson.databind ObjectMapper]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(defn- env [k default]
  (or (some-> (System/getenv k) str str/trim not-empty) default))

(defn- base-url [] (env "GROG_BIG_URL" "http://localhost:4000/v1"))
(defn- model-name [] (env "GROG_BIG_MODEL" "big"))
(defn- api-key [] (env "GROG_BIG_API_KEY" "sk-dummy"))

(def ^:private default-system
  (str "You are the 'big model' specialist consulted by a small local agent. "
       "Give a thorough, correct, and well-structured answer to the prompt you receive. "
       "Do not ask clarifying questions unless truly necessary; the local agent needs a "
       "decisive result. Return only the answer content (no preamble, no tool calls)."))

;; ---------------------------------------------------------------------------
;; Big-model call
;; ---------------------------------------------------------------------------

(defn- parse-args
  "Parse tool arguments (map, java.util.Map, or JSON string) to a keyword map."
  [arguments]
  (let [m (cond
            (map? arguments) arguments
            (instance? java.util.Map arguments)
            (into {} (map (fn [[k v]] [(keyword (name (str k))) v])) arguments)
            (string? arguments) (try (json/read-str arguments) (catch Exception _ {}))
            :else {})
        prompt (some-> (or (:prompt m) (get m "prompt")) str str/trim not-empty)
        system (some-> (or (:system m) (get m "system")) str str/trim not-empty)
        max-tokens (let [mt (or (:max_tokens m) (get m "max_tokens"))]
                     (when (number? mt) (long mt)))]
    {:prompt prompt
     :system system
     :max_tokens max-tokens}))

(defn- chat-completions!
  "POST to the big model's /v1/chat/completions. Returns the assistant message
  text (string), or throws. Falls back to the model's `reasoning`/
  `reasoning_content` field (some models emit only that for a while, or when
  `content` is empty but a reasoning trace is present)."
  ^String [^String prompt ^String system max-tokens]
  (let [url (str/join "/" [(str/replace (base-url) #"/+$" "") "chat/completions"])
        payload (cond-> {:model (model-name)
                         :messages [{:role "system" :content system}
                                    {:role "user" :content prompt}]
                         :temperature 0.3}
                  max-tokens (assoc :max_tokens (long max-tokens)))
        resp (http/post url
                        {:headers {"Authorization" (str "Bearer " (api-key))
                                   "Content-Type" "application/json"}
                         :body (json/write-str payload)
                         :as :string
                         :throw-exceptions false
                         :socket-timeout 120000
                         :conn-timeout 10000})
        st (:status resp)
        ^String raw (or (:body resp) "")]
    (cond
      (= 200 st)
      (try
        (let [body (json/read-str raw)
              choices (get body "choices")
              first-choice (first choices)
              msg (get first-choice "message")
              content (some-> (get msg "content") str not-empty)
              reasoning (or (some-> (get msg "reasoning_content") str not-empty)
                            (some-> (get msg "reasoning") str not-empty))]
          (or content
              reasoning
              (str "big_model_ask: empty content from " (model-name))))
        (catch Exception e
          (str "big_model_ask: invalid JSON in response: " (.getMessage e))))

      :else
      (str "big_model_ask: HTTP " st " from " (base-url)
           (when (seq (str/trim raw)) (str ": " (pr-str (str/trim raw))))))))

(defn- run-ask!
  "The big_model_ask tool implementation. Returns a string for the model."
  [arguments]
  (let [{:keys [prompt system max_tokens]} (parse-args arguments)]
    (if (str/blank? prompt)
      "big_model_ask error: missing or empty `prompt` parameter. Pass a self-contained prompt: the big model only sees the prompt and its system message, not your full conversation."
      (try
        (chat-completions! prompt (or system default-system) max_tokens)
        (catch Exception e
          (str "big_model_ask failed: " (.getMessage e)))))))

;; ---------------------------------------------------------------------------
;; MCP server wiring (same pattern as grog-search / grog-odoo)
;; ---------------------------------------------------------------------------

(defn- text-content [^String s] (McpSchema$TextContent. s))
(defn- text-result [^String s] (McpSchema$CallToolResult. [(text-content s)] false))
(defn- error-result [^String s] (McpSchema$CallToolResult. [(text-content s)] true))

(defn- tool
  "Build an async MCP tool spec from {:name :description :schema-json :fn}."
  [{:keys [name description schema fn]}]
  (McpServerFeatures$AsyncToolSpecification.
    (McpSchema$Tool. name description schema)
    (reify java.util.function.BiFunction
      (apply [_ _exchange arguments]
        (Mono/create
          (reify java.util.function.Consumer
            (accept [_ sink]
              (try
                (.success sink (text-result (fn arguments)))
                (catch Throwable t
                  (.success sink (error-result
                                  (str "Error executing tool " name ": "
                                       (or (:message (ex-data t)) (.getMessage t))))))))))))))

(defn- tool-spec []
  {:name "big_model_ask"
   :description (str "Ask a large, high-quality remote model (e.g. DeepSeek) for a self-contained "
                     "response. Use this when the task needs deeper reasoning, more knowledge, or "
                     "higher quality than you can provide — summarizing it is the point. The big "
                     "model does NOT see this conversation: pass a FULL, self-contained prompt "
                     "including any necessary context. Optional: system overrides its identity, "
                     "max_tokens caps the response length.")
   :schema (json/write-str {:type :object
                            :properties {:prompt {:type :string
                                                  :description "Full self-contained prompt for the big model (context included)."}
                                         :system {:type :string
                                                  :description "Optional system message overriding the default specialist identity."}
                                         :max_tokens {:type :integer
                                                      :description "Optional cap on response length in tokens."}}
                            :required ["prompt"]})
   :fn run-ask!})

(defn mcp-server []
  (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "grog-big" "0.1.0")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                      (.tools true)
                                      (.build)))
                   (.build))]
    (-> (.addTool server (tool (tool-spec))) (.subscribe))
    server))

(defn -main [& _args]
  (mcp-server)
  ;; stdio MCP server: block forever; the client (ECA) owns our lifecycle and
  ;; kills the process when the session ends.
  (loop []
    (Thread/sleep 1000)
    (recur)))