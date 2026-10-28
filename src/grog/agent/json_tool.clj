(ns grog.agent.json-tool
  "Recover tool calls that a small model wrote as JSON TEXT instead of using
  the API's native tool-calling.

  A weak local model often answers with a literal JSON object in its message
  text — `{\"name\": \"brave_web_search\", \"arguments\": {\"query\": \"…\"}}`
  — believing that IS the call. Nothing runs: the text goes to the user as the
  answer and the tool never fires.
  Prompting helps, but the robust move is to accept the format: scan the
  finished assistant text, and if it parses as a plausible tool call, run it
  exactly as if the provider had sent a structured `tool_calls` entry.

  The same failure already had one fix here — `grog.agent.dsml` recovers
  DeepSeek's markup the same way — so this mirrors that shape: parse only
  COMPLETE, plausible calls; never execute something half-parsed; and strip
  the recovered JSON from the text so the user sees the answer, not the wire.

  Shapes accepted (both spellings appear in the wild):

    {\"name\": \"tool\", \"arguments\": {..}}     ;; OpenAI / our own prompt
    {\"tool\": \"tool\", \"args\":    {..}}       ;; the other common one

  `arguments`/`args` may be an object or a JSON-encoded string. Anything else
  (no name, unknown tool, extra prose AROUND the JSON) is left as text — a
  false positive here would run something the user only meant to show."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private candidate-re
  "A `{ ... }` block that looks like a tool call. Deliberately simple — the
  parse below is the real gate; this only finds where to try."
  #"(?s)\{[^{}]*(?:\"name\"|\"tool\")\s*:.*\}")

(defn- parse-args
  "The `arguments`/`args` value → a map, tolerating a JSON-encoded string."
  [v]
  (cond
    (map? v)    v
    (string? v) (try (let [p (json/parse-string v true)]
                       (when (map? p) p))
                     (catch Throwable _ nil))
    (nil? v)    {}
    :else       nil))

(defn- ->call
  "One parsed JSON object → `{:name :arguments}` when it is a plausible tool
  call, else nil. Plausible = a non-blank name under `name`/`tool` (or
  `function.name` for the nested OpenAI shape) with parseable arguments."
  [m]
  (when (map? m)
    (let [name (or (some-> (:name m) str str/trim not-empty)
                   (some-> (:tool m) str str/trim not-empty)
                   (some-> (get-in m [:function :name]) str str/trim not-empty))
          args (parse-args (or (:arguments m) (:args m) (:parameters m)
                               (get-in m [:function :arguments])))]
      (when (and name args)
        {:name name :arguments args}))))

(defn extract
  "Scan a complete assistant `text` for JSON tool calls.

  Returns `{:tool-calls [{:name :arguments} …] :text <text with the call JSON
  removed>}`. When nothing plausible is found, `:tool-calls` is empty and
  `:text` is the input unchanged.

  A call is only recovered when the WHOLE text is the call (plus whitespace):
  a model that writes prose AND a JSON block is explaining, not calling, and
  the text is left alone. That is the false-positive guard — the recovery only
  fires when the JSON was the entire reply, which is exactly the failure mode."
  [text]
  (let [text (str text)
        t (str/trim text)]
    (if-not (and (str/starts-with? t "{") (str/ends-with? t "}"))
      {:tool-calls [] :text text}
      (let [parsed (try (json/parse-string t true) (catch Throwable _ nil))
            call (->call parsed)]
        (if call
          {:tool-calls [call] :text ""}
          {:tool-calls [] :text text})))))

(defn recover
  "Like `extract`, but accepts a single tool call written as a bare JSON text
  and returns the tool-call vector (the transport's recovered-calls shape)."
  [text]
  (:tool-calls (extract text)))
