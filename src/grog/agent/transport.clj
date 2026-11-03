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

  A STALL IS NOT A CRASH, AND IT IS NOT A MYSTERY. The read has a socket timeout
  (`:llm :socket-timeout-sec`, default 300s) so a provider that accepts the POST
  and then goes quiet cannot block a turn forever. When that fires the old
  behaviour was to let a bare `java.net.SocketTimeoutException` escape, which the
  client rendered as the seven words `[grog] loop error: Read timed out` —
  naming no endpoint, no model, no elapsed time, no way forward (seen
  2026-10-22). Now:

    * a read that fails BEFORE any data arrived is RETRIED (`:llm :stream-retries`,
      default 2) — nothing was received, so re-sending the identical body cannot
      duplicate anything, and a transient provider/network stall just works;
    * a read that fails AFTER data arrived is NOT retried, because re-sending
      would replay the answer the user already watched stream in — the partial
      text is carried out on the exception (`:partial-text`) instead of vanishing;
    * either way the failure is thrown as an `ex-info` that says what happened,
      to WHICH endpoint and model, how long it waited, and which knob to raise.

  No new dependencies: clj-http for the POST, cheshire for JSON, and the body is
  consumed on the CALLING thread so `on-delta` fires as data actually arrives."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [grog.agent.context :as ctx]
            [grog.agent.dsml :as dsml]
            [grog.agent.json-tool :as json-tool])
  (:import (java.io BufferedReader IOException InputStreamReader InputStream)))

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

;; --- a stalled read: retry it if nothing arrived, explain it if it did ---------

(def ^:private default-socket-timeout-ms
  "How long a silent provider is tolerated on one attempt. 300s matches the
  historical hard-coded value and `:llm :socket-timeout-sec`'s default."
  300000)

(def ^:private default-stream-retries
  "Attempts BEYOND the first, for a stall that received nothing. Two means three
  total tries — enough to ride out a dropped connection or a cold model endpoint
  without turning a dead provider into a five-minute hang."
  2)

(defn- http-error
  "The `ex-info` thrown for a non-2xx answer, or nil when `e` is not one."
  [e]
  (when (and (instance? clojure.lang.ExceptionInfo e)
             (contains? (ex-data e) :status))
    e))

(defn- transient-read-failure?
  "Is `e` the kind of failure a RE-SEND of the same request can plausibly fix?

  True for a socket/read timeout, a connection reset or a broken pipe — the wire
  gave up, nothing was received. False for everything else, and deliberately so:
  an HTTP 400/401/429 is the request's fault (or the account's), and re-sending it
  produces the same answer more slowly.

  `http-error` is checked FIRST and answers false unconditionally: an HTTP failure
  is a refusal by the provider, and the request should not be re-sent."
  [^Throwable e]
  (let [msg (str (.getMessage e))]
    (boolean
     (and (not (http-error e))
          (or (instance? java.net.SocketTimeoutException e)
              (instance? java.net.ConnectException e)
              (instance? java.io.InterruptedIOException e) ; SocketTimeoutException's parent
              (instance? java.net.SocketException e)
              (instance? IOException e)
              (re-find #"(?i)read timed out|timed out|connection reset|broken pipe|connection closed"
                       msg))))))

(defn- stall-error
  "The diagnosable replacement for a bare `Read timed out`.

  The old message was seven words with no endpoint, no model, no elapsed time and
  no way forward, so the user's report was literally the text of the exception
  (`[grog] loop error: Read timed out`, 2026-10-22). Say what happened, where,
  for how long, and what to raise.

  An HTTP failure is NOT a stall and is passed through untouched — it already
  carries the provider's own words, and calling a 400 a timeout would be a new lie
  in place of the old one."
  [endpoint model ^Throwable cause {:keys [attempts retries frames partial-text
                                           started? elapsed-ms socket-timeout-ms]}]
  (if (http-error cause)
    cause
    (let [secs (fn [ms] (format "%.1fs" (/ (double (or ms 0)) 1000.0)))
          where (str endpoint " (model " (str model) ")")
          what (if started?
                 (str "the stream stalled MID-ANSWER — " frames " frame(s) arrived, then "
                      "the provider went quiet for " (secs socket-timeout-ms))
                 (str "the provider accepted the request but sent nothing for "
                      (secs socket-timeout-ms) " — a stall before the first token"))]
      (ex-info
       (str "LLM stream stalled: " what ", at " where ". "
            "Tried " attempts " time(s)" (when (pos? (long (or retries 0)))
                                            (str " (:llm :stream-retries " retries ")"))
            ", " (secs elapsed-ms) " elapsed. "
            (if (and started? (seq (str partial-text)))
              (str "The partial answer WAS kept — it is attached to this error as "
                   ":partial-text, so it is not lost.")
              "No answer text was received.")
            " To ride out a slow or flaky provider, raise :llm :socket-timeout-sec "
            "(currently " (long (/ (long (or socket-timeout-ms 0)) 1000)) "s) "
            "or :llm :stream-retries in grog.edn.")
       {:stalled? true
        :endpoint endpoint :model model
        :attempts attempts :retries retries
        :frames (long (or frames 0)) :started? (boolean started?)
        :partial-text (str partial-text)
        :elapsed-ms (long (or elapsed-ms 0))
        :socket-timeout-ms (long (or socket-timeout-ms 0))
        :cause-message (str (.getMessage cause))}
       cause))))

(defn stream-chat!
  "Run one chat completion with `messages`, streaming.

  Returns {:text :reasoning :tool-calls :malformed :usage :finish-reason}.
  Throws on a non-2xx answer, carrying the provider's own message — a failure
  must never look like an empty success.

  `:post` overrides the HTTP call (a fixture transport for tests/evals: hand it
  a canned SSE stream and the whole loop can run with no network).

  `:socket-timeout-ms` (default 300s) is the READ timeout — how long a silent
  provider is tolerated before the attempt is abandoned. `:stream-retries`
  (default 2) is how many times an attempt that received NOTHING is re-sent.
  `:on-retry` is called with `{:attempt :retries :error :wait-ms}` so the caller
  can say out loud that a stall happened instead of the user seeing a 5-minute
  pause with no explanation."
  [{:keys [url key model messages tools temperature max-tokens
           on-delta on-reasoning post stream-usage?
           socket-timeout-ms stream-retries on-retry]
    :or {stream-usage? true
         socket-timeout-ms default-socket-timeout-ms
         stream-retries default-stream-retries}}]
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
        retries (max 0 (long (or stream-retries 0)))
        ;; ONE ATTEMPT. Returns {:ok result} on success; on failure returns
        ;; {:error e :frames n :partial-text s :started? bool} — it does NOT
        ;; throw, because the retry decision needs to know WHETHER ANYTHING
        ;; ARRIVED, and an exception cannot carry that.
        attempt
        (fn []
          (let [!text (atom []) !reason (atom []) !tools (atom {})
                !usage (atom nil) !finish (atom nil)
                ;; FRAMES SEEN, and when the first one landed. This is the whole
                ;; retry policy in two atoms: 0 frames = nothing was received, so
                ;; re-sending the identical body cannot duplicate anything;
                ;; >0 frames = the user already watched an answer arrive, so a
                ;; retry would replay it.
                !frames (atom 0)
                !first-frame-ms (atom nil)
                t0 (System/currentTimeMillis)
                ;; DSML (DeepSeek) tool-call markup is stripped from the stream as
                ;; it arrives, so raw markup never reaches the client; captured
                ;; blocks are recovered into tool calls below if the provider sent
                ;; none.
                dsml-filt (dsml/make-filter)
                emit-text! (fn [s]
                             (when (seq s)
                               (swap! !text conj s)
                               (when on-delta (on-delta s))))
                fail (fn [e]
                       ;; FLUSH THE STRIPPER BEFORE REPORTING. The DSML filter holds
                       ;; back a short tail so an opening tag split across chunks is
                       ;; recognised — so at the moment of a stall up to ~32 chars of
                       ;; real answer are still inside it. Without this the partial
                       ;; text handed back on the error is silently short (measured:
                       ;; "the answer so far" came back as "the ans").
                       (let [tail (try ((:finish! dsml-filt))
                                       (catch Throwable _ ""))]
                         (when (seq tail) (emit-text! tail)))
                       {:error e
                        :frames (long @!frames)
                        :partial-text (apply str @!text)
                        :started? (some? @!first-frame-ms)
                        :elapsed-ms (- (System/currentTimeMillis) t0)})]
            (try
              (let [resp ((or post http/post)
                          endpoint
                          {:headers (cond-> {"Content-Type" "application/json"}
                                      (seq key) (assoc "Authorization" (str "Bearer " key)))
                           :body (json/generate-string body)
                           :as :stream
                           :throw-exceptions false
                           :connection-timeout 15000
                           :socket-timeout socket-timeout-ms})
                    status (:status resp)]
                (if-not (and status (<= 200 status 299))
                  ;; An HTTP status is the REQUEST's fault, not the wire's: a 400
                  ;; or a 401 re-sent is the same 400 or 401, only later. Not
                  ;; retried — thrown with the provider's own words, as before.
                  ;;
                  ;; Those words go IN THE MESSAGE, not only in the ex-data. The
                  ;; client renders `(.getMessage e)`, so a 400 whose message was
                  ;; just "HTTP 400 from <url>" put the useful part — the provider
                  ;; saying WHICH tool schema it rejected — somewhere the user
                  ;; never sees. The body is already clipped to 2000 chars for
                  ;; exactly this purpose.
                  (let [raw (try (slurp (:body resp)) (catch Throwable _ "<body unreadable>"))
                        text (str raw)
                        ;; OpenAI-shaped errors carry the human sentence in
                        ;; error.message; prefer it when the body parses, else use
                        ;; the body itself. Clipped, because this goes in a line.
                        msg-detail (try (not-empty (str/trim (str (:message (:error (json/parse-string text true))))))
                                        (catch Throwable _ nil))
                        detail (subs (or msg-detail (str/trim text) "")
                                     0 (min 500 (count (or msg-detail (str/trim text) ""))))
                        ;; IS THIS A CONTEXT OVERFLOW? The one HTTP failure the
                        ;; loop can FIX rather than report: the prompt was too big
                        ;; for the model's window. It is parsed here, where the
                        ;; body and the status are both in hand, and carried on
                        ;; the exception so the loop can distill and re-send the
                        ;; SAME step instead of killing the turn. The provider's
                        ;; own ceiling, when it names one, is the best evidence
                        ;; about the real window that grog will ever get.
                        overflow (ctx/parse-overflow status (or detail text))]
                    (throw (ex-info (str "LLM call failed: HTTP " status " from " endpoint
                                         (when (seq detail) (str " — " detail)))
                                    (cond-> {:status status :endpoint endpoint :model model
                                             :body (subs text 0 (min 2000 (count text)))}
                                      overflow (assoc :overflow? true
                                                      :context-limit (:context-limit overflow)
                                                      :prompt-tokens (:prompt-tokens overflow))))))
                  (do
                    (pump-sse! (:body resp)
                               (fn [payload]
                                 (let [frame (try (json/parse-string payload true)
                                                  (catch Throwable _ nil))]
                                   (when frame
                                     ;; record WHEN the first frame landed; this
                                     ;; is what distinguishes a stall before any
                                     ;; output from one mid-answer, and only the
                                     ;; former may be retried.
                                     (compare-and-set! !first-frame-ms nil t0)
                                     (swap! !frames inc)
                                     (when-let [u (:usage frame)] (reset! !usage u))
                                     (when-let [ch (first (:choices frame))]
                                       (when-let [fr (:finish_reason ch)] (reset! !finish fr))
                                       (let [d (:delta ch)]
                                         (when-let [c (:content d)]
                                           (when (seq c)
                                             (emit-text! ((:push! dsml-filt) c))))
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
                    ;; flush whatever the stripper still holds (its look-back tail,
                    ;; and a block cut off mid-stream), then recover DSML if no
                    ;; STRUCTURED calls arrived.
                    (emit-text! ((:finish! dsml-filt)))
                    (let [{:keys [calls malformed]} (assemble-tool-calls @!tools)
                          text (apply str @!text)
                          ;; No STRUCTURED calls? Two text fallbacks, in order:
                          ;; DeepSeek's DSML markup (stripped from the stream
                          ;; above, captured here), then a bare JSON tool call —
                          ;; a weak model answering with literal
                          ;; `{"name": …, "arguments": …}` text. The last one is
                          ;; the reported failure: the JSON would otherwise be
                          ;; shown to the user AS the answer and nothing would run.
                          dsml-calls (let [blocks ((:blocks dsml-filt))]
                                       (when (dsml/dsml? blocks) (dsml/recover blocks)))
                          json-extract (when (and (empty? calls) (empty? dsml-calls))
                                         (json-tool/extract text))
                          calls (cond
                                  (seq calls) calls
                                  (seq dsml-calls) dsml-calls
                                  :else (:tool-calls json-extract))
                          ;; a recovered call IS the whole message — strip it from
                          ;; the text so the transcript shows the tool run, not the
                          ;; wire format.
                          text (if (seq (:tool-calls json-extract)) (:text json-extract) text)]
                      {:ok {:text text
                            :reasoning (apply str @!reason)
                            :tool-calls calls
                            :malformed malformed
                            :usage @!usage
                            :finish-reason @!finish
                            :frames (long @!frames)
                            :elapsed-ms (- (System/currentTimeMillis) t0)
                            ;; WHEN the first frame landed, measured from the
                            ;; request — not a flag but a DURATION, because the
                            ;; only way to report a token RATE that is not a lie
                            ;; is to subtract the wait for the first token from
                            ;; the total. Prompt processing is exactly that wait:
                            ;; a big prompt on a local server can spend 20s there,
                            ;; and counting it as "time spent producing tokens"
                            ;; makes a fast model look slow by an order of
                            ;; magnitude. nil when nothing arrived at all.
                            :first-frame-ms (some-> @!first-frame-ms (- t0))}}))))
              (catch Throwable e (fail e)))))]
    (loop [n 1]
      (let [{:keys [ok error frames partial-text started? elapsed-ms]} (attempt)]
        (cond
          ok ok
          ;; RETRY ONLY WHAT A RETRY CAN FIX: a transient read/connection failure
          ;; that received NOTHING. Anything else is thrown immediately — a
          ;; retried 400 is still a 400, and a retried mid-stream stall would
          ;; re-send the answer the user already saw arrive.
          (and (< n (inc retries))
               (zero? (long (or frames 0)))
               (transient-read-failure? error))
          (let [wait-ms (* 1000 n)]              ; 1s, then 2s — backoff, no jitter needed
            (when on-retry
              (on-retry {:attempt n :retries retries :error error :wait-ms wait-ms}))
            (Thread/sleep (long wait-ms))
            (recur (inc n)))
          :else
          (throw (stall-error endpoint model error
                              {:attempts n :retries retries
                               :frames (long (or frames 0))
                               :partial-text (str partial-text)
                               :started? (boolean started?)
                               :elapsed-ms (long (or elapsed-ms 0))
                               :socket-timeout-ms (long socket-timeout-ms)})))))))
