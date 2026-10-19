(ns grog.agent.transport
  "The LLM half of grog's OWN agent loop — one OpenAI-compatible chat call, streamed.

  Why this file exists: grog has been running ECA as its agent loop, which means
  the loop, the transport, the MCP client, the context budget and the coding
  tools are all somebody else's code — code we forked and patch (doc/eca-fork.md).
  This is the spike that asks what owning that loop costs instead. See
  src/grog/agent/README.md for what is built and what is not.

  STREAMING is SSE: `data: {json}` frames, `data: [DONE]` to finish, other lines
  (comments, keep-alives) ignored. Tool calls arrive the way OpenAI streams them —
  `delta.tool_calls[]` FRAGMENTS, each carrying an `index`, `function.name` only
  on the first fragment and `function.arguments` split across many — so they are
  accumulated per index here and assembled once, at the end. Reasoning models
  send their chain of thought in `delta.reasoning` (deepseek) or
  `delta.reasoning_content`; both pass through, because grog's client renders
  :thinking.

  A malformed argument fragment is NOT swallowed: the raw text is kept and
  reported, because 'tool arguments that are not what the model sent' is a
  failure this project has already paid for (see doc/eca-fork.md, the DSML
  recovery patch) and it must be visible rather than plausible.

  No new dependencies: clj-http for the POST, cheshire for JSON, and the body is
  consumed on the CALLING thread so `on-delta` fires as data actually arrives."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str])
  (:import (java.io BufferedReader InputStreamReader InputStream)))

(def ^:private sse-prefix "data:")

(defn- pump-sse!
  "Read `in` line by line, calling `on-frame` with each SSE data payload.
  Blocking and eager on purpose — a lazy seq would defer the callbacks and
  defeat streaming."
  [^InputStream in on-frame]
  (let [rdr (BufferedReader. (InputStreamReader. in "UTF-8"))]
    (loop []
      (when-let [line (.readLine rdr)]
        (let [l (str/trim line)]
          (when (str/starts-with? l sse-prefix)
            (let [payload (str/trim (subs l (count sse-prefix)))]
              (when (and (seq payload) (not= payload "[DONE]"))
                (on-frame payload)))))
        (recur)))))

(defn- assemble-tool-calls
  "The accumulated fragments → [{:id :name :arguments}] with arguments PARSED.
  `:malformed` carries any call whose argument text is not valid JSON, raw and
  labelled, instead of vanishing into a silently empty argument map."
  [acc]
  (reduce (fn [out [idx tc]]
            (let [raw (get-in tc [:function :arguments] "")
                  name (get-in tc [:function :name])]
              (if (str/blank? raw)
                (update out :calls conj {:id (:id tc) :name name :arguments {}})
                (try
                  (update out :calls conj {:id (:id tc) :name name
                                           :arguments (json/parse-string raw true)})
                  (catch Throwable e
                    (-> out
                        (update :calls conj {:id (:id tc) :name name :arguments {} :invalid-json true})
                        (update :malformed conj
                                {:index idx :name name :raw raw
                                 :why (str "tool call arguments are not valid JSON: "
                                           (.getMessage e))})))))))
          {:calls [] :malformed []}
          (sort-by key acc)))

(defn stream-chat!
  "Run one chat completion with `messages`, streaming.

  Returns {:text :reasoning :tool-calls :malformed :usage :finish-reason}.
  Throws on a non-2xx answer, carrying the provider's own message — a failure
  must never look like an empty success.

  `:post` overrides the HTTP call (a fixture transport for tests/evals: hand it
  a canned SSE stream and the whole loop can run with no network)."
  [{:keys [url key model messages tools temperature max-tokens
           on-delta on-reasoning post stream-usage?]
    :or {stream-usage? true}}]
  ;; A NIL/blank url used to become the string "" here, so the endpoint was
  ;; "/chat/completions" and the provider call died with `no protocol:
  ;; /chat/completions` — a crash that names neither the missing config nor the
  ;; model. Fail loudly, at the top, naming the knobs (seen 2026-10-18).
  (when (str/blank? (str url))
    (throw (ex-info (str "no LLM url configured: the model '" (str model) "' has no provider endpoint. "
                         "Set :llm :url in grog.edn, pass --url, or pick a provider-qualified model "
                         "(e.g. \"openai/gpt-…\") so the provider catalogue supplies the url.")
                    {:model model :url url})))
  (let [endpoint (str (str/replace (str url) #"/+$" "") "/chat/completions")
        body (cond-> {:model model :messages messages :stream true}
               (seq tools) (assoc :tools tools)
               temperature (assoc :temperature temperature)
               max-tokens (assoc :max-tokens max-tokens)
               stream-usage? (assoc :stream_options {:include_usage true}))
        resp ((or post http/post)
              endpoint
              {:headers (cond-> {"Content-Type" "application/json"}
                          (seq key) (assoc "Authorization" (str "Bearer " key)))
               :body (json/generate-string body)
               :as :stream
               :throw-exceptions false
               :connection-timeout 15000
               :socket-timeout 300000})
        status (:status resp)]
    (if-not (and status (<= 200 status 299))
      (let [text (try (slurp (:body resp)) (catch Throwable _ "<body unreadable>"))]
        (throw (ex-info (str "LLM call failed: HTTP " status " from " endpoint)
                        {:status status :endpoint endpoint
                         :body (subs (str text) 0 (min 2000 (count (str text))))})))
      (let [!text (atom []) !reason (atom []) !tools (atom {})
            !usage (atom nil) !finish (atom nil)]
        (pump-sse! (:body resp)
                   (fn [payload]
                     (let [frame (try (json/parse-string payload true)
                                      (catch Throwable _ nil))]
                       (when frame
                         (when-let [u (:usage frame)] (reset! !usage u))
                         (when-let [ch (first (:choices frame))]
                           (when-let [fr (:finish_reason ch)] (reset! !finish fr))
                           (let [d (:delta ch)]
                             (when-let [c (:content d)]
                               (when (seq c)
                                 (swap! !text conj c)
                                 (when on-delta (on-delta c))))
                             (let [r (or (:reasoning d) (:reasoning_content d))]
                               (when (seq r)
                                 (swap! !reason conj r)
                                 (when on-reasoning (on-reasoning r))))
                             (doseq [tc (:tool_calls d)]
                               (let [idx (:index tc)]
                                 (swap! !tools update idx
                                        (fn [cur]
                                          (-> (or cur {})
                                              (cond-> (:id tc) (assoc :id (:id tc)))
                                              (update :function
                                                      (fn [f]
                                                        (let [f (or f {})]
                                                          (-> f
                                                              (cond-> (get-in tc [:function :name])
                                                                (assoc :name (get-in tc [:function :name])))
                                                              (update :arguments
                                                                      (fn [a] (str (or a "")
                                                                                   (or (get-in tc [:function :arguments]) "")))))))))))))))))))
        (let [{:keys [calls malformed]} (assemble-tool-calls @!tools)]
          {:text (apply str @!text)
           :reasoning (apply str @!reason)
           :tool-calls calls
           :malformed malformed
           :usage @!usage
           :finish-reason @!finish})))))
