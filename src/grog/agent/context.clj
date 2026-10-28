(ns grog.agent.context
  "Token accounting, COMPACTION and DISTILLATION for a conversation — the pure half
  of 'grog must not die on an overflowing context'.

  Why this ns exists at all. The budget used to be a GLOBAL CONSTANT
  (`:llm :max-context-tokens`, default 200000) and a compaction that fired only
  when the estimate crossed it. Three things were wrong with that, and they are
  the same thing three times:

    1. The number is a guess about ONE model. The user runs different models with
       different windows, and grog's config is per-machine. A 200k budget against
       a 32k local server is a hard 400 with no answer.
    2. The estimate only counted `:content`, so tool schemas (~8k tokens for a
       lazy-but-loaded belt) and `tool_calls` were invisible. The compaction could
       report 'fitting' on a request the server refuses.
    3. When the guess was wrong there was NO RECOVERY. The turn threw, the thread
       kept the oversized history, and every later turn re-sent it — so one
       overflow destroyed the conversation rather than one request.

  (1) and (2) are fixed by asking the endpoint (`grog.models/context-budget`) and
  by counting the whole message here; (3) is fixed by `loop`'s recovery path,
  which uses `distill` below: a REAL 400 is evidence about the real window, and
  the answer to it is to shrink the prompt and re-send the SAME step, not to
  abandon the turn.

  Nothing here does I/O. Every function is pure over messages, so the arithmetic
  and the message surgery can be tested without a model (see
  scripts/context-window-test.clj and scripts/context-overflow-test.clj)."
  (:require [clojure.string :as str]))

;; --- token accounting --------------------------------------------------------

(def ^:private chars-per-token
  "Deliberately rough (≈4 chars/token). This is a TRIPWIRE, not a meter: it only
  has to fire before the provider's hard limit, and after an overflow the loop
  recalibrates from the server's own numbers rather than trusting this."
  4)

(defn- approx-chars
  "Approximate wire size of `x` — the whole map for a message, so role,
  `tool_calls` and non-string content are all counted. Counting only `:content`
  is what made the old estimate under-report by the size of every tool schema and
  every tool call in the history."
  [x]
  (count (if (string? x) x (pr-str x))))

(defn est-tokens
  "Rough token count for `msgs`, plus `extra` (the tool schemas sent in the same
  request) when given.

  Including `extra` matters: a lazy-but-loaded belt is ~40 schemas and several
  thousand tokens that ride EVERY request and cannot be elided by compaction.
  A budget check that ignores them is a check that can pass on a prompt the
  server will refuse."
  ([msgs] (est-tokens msgs nil))
  ([msgs extra]
   (+ (quot (reduce + 0 (map approx-chars msgs)) chars-per-token)
      (if (nil? extra) 0 (quot (approx-chars extra) chars-per-token)))))

;; --- compaction: routine, cheap, reversible ---------------------------------

(defn- marker
  "A visible stand-in for the messages that were dropped. A model that believes it
  has seen the whole history is worse than one that knows it has not, so the gap
  is always NAMED — never a silent splice."
  [n why]
  {:role "user"
   :content (str "[" n " earlier message(s) were elided to fit " why ". Re-read a file "
                 "or re-run a command if you need that detail again.]")})

(defn- split-system
  "`msgs` as [system-message-or-nil the rest]."
  [msgs]
  (if (= "system" (:role (first msgs)))
    [(first msgs) (vec (rest msgs))]
    [nil (vec msgs)]))

(defn compact
  "The ROUTINE shrink: elide the middle of the conversation when `msgs` exceeds
  `budget`.

  Keeps the system message, the FIRST user message (the task) and the last 12
  messages (the live working set). What goes is the middle — overwhelmingly old
  tool results — and it goes with a marker saying so. This runs before every
  step and is deliberately conservative; it must not be the thing that throws
  away the detail the model still needs.

  A nil/non-positive `budget` means 'no proactive trimming' (nil is the honest
  value when the endpoint's window could not be learned — the loop's overflow
  recovery is then the safety net, and it only fires on real evidence).

  `budget` should already be net of the tool schemas: see `est-tokens`'s `extra`
  and the caller's `(- budget tool-tokens)`."
  [msgs budget]
  (if (or (nil? budget) (not (pos? (long budget))) (<= (est-tokens msgs) (long budget)))
    msgs
    (let [[sys rest-msgs] (split-system msgs)
          head (first rest-msgs)
          tail (vec (take-last 12 rest-msgs))
          elided (- (count rest-msgs) (count tail) (if head 1 0))
          compose (fn [tail n-elided]
                    (vec (concat (when sys [sys])
                                 (when head [head])
                                 (when (pos? (long n-elided))
                                   [(marker n-elided "the context budget")])
                                 tail)))
          ;; THE TAIL ALONE CAN BE OVER BUDGET. Twelve recent tool results from a
          ;; coding turn are easily 60k tokens, so "elide the middle" can leave a
          ;; prompt that is STILL too big — and a proactive trim that does not
          ;; actually get under the limit is a trim that lies. Drop from the FRONT
          ;; of the tail until it fits, always keeping the most recent message
          ;; (the step in hand) — that is the one thing a model cannot do without.
          fitting (loop [t tail]
                    (let [c (compose t (+ elided (- (count tail) (count t))))]
                      (if (or (<= (est-tokens c) (long budget)) (<= (count t) 1))
                        c
                        (recur (vec (rest t))))))]
      fitting)))

;; --- distillation: the recovery shrink, after a REAL overflow ----------------

(defn- content-text
  "`content` as a string. A multimodal message (a vector of text/image blocks) is
  reduced to its text plus a note — the base64 in an image block is worth
  thousands of tokens and is the first thing that must go when space is short."
  [c]
  (cond
    (nil? c) ""
    (string? c) c
    (sequential? c) (str (str/join "\n"
                                   (keep #(when (= "text" (:type %)) (:text %)) c))
                         (when (some #(= "image_url" (:type %)) c)
                           "\n[an attached image was omitted to fit the context window]"))
    :else (pr-str c)))

(defn- clip
  "Cut `s` to `max-chars` WITH a marker. A silent truncation reads as a complete
  answer, which is how a model ends up asserting half a file."
  [s max-chars]
  (let [s (str s)]
    (if (and (pos? (long (or max-chars 0))) (> (count s) (long max-chars)))
      (str (subs s 0 (long max-chars))
           "\n… truncated: " (- (count s) (long max-chars))
           " more char(s) were cut to fit the model's context window")
      s)))

(defn- shrink-message
  "One message, made smaller: long text clipped, multimodal reduced to text,
  `tool_calls` arguments clipped (they are re-readable from the transcript, and
  they are frequently the largest thing in a coding turn)."
  [m max-chars]
  (let [c (content-text (:content m))]
    (cond-> (assoc m :content (clip c max-chars))
      (seq (:tool_calls m))
      (update :tool_calls
              (fn [tcs]
                (mapv (fn [tc]
                        (if-let [f (:function tc)]
                          (update tc :function assoc :arguments (clip (:arguments f) max-chars))
                          tc))
                      tcs))))))

(defn- build
  "The distilled conversation: system, the ORIGINAL task, a marker for what went,
  then the last `keep-tail` messages with everything clipped."
  [msgs keep-tail max-chars]
  (let [[sys rest-msgs] (split-system msgs)
        head (first rest-msgs)
        tail (vec (take-last (max 1 (long keep-tail)) rest-msgs))
        elided (- (count rest-msgs) (count tail) (if head 1 0))]
    (vec (concat (when sys [(shrink-message sys max-chars)])
                 (when head [(shrink-message head max-chars)])
                 (when (pos? elided) [(marker elided "the model's context window")])
                 (map #(shrink-message % max-chars) tail)))))

(def ^:private distillation-ladder
  "Progressively harsher (keep-tail, max-chars) pairs. A real overflow is EVIDENCE,
  so the first rung is already much tighter than `compact`; the later rungs exist
  because a coding turn's tool results can be huge and the first clip may still
  be too big. The last rung keeps only the task and the most recent message —
  which is still a CONVERSATION, so the thread survives even in the worst case."
  [[10 4000] [6 2000] [3 1000] [2 400] [1 200]])

(defn distill
  "The RECOVERY shrink, used after the endpoint has actually refused the prompt.

  Unlike `compact`, this does not try to be gentle: it keeps the system prompt,
  the original task, and a short tail, clips every long message, drops images,
  and — when `:budget` is given — walks a ladder of harsher settings until the
  estimate fits. Returns messages, never nil, and always reaches a state that is
  smaller than its input (or the input, when it was already tiny).

  :budget      target token estimate for messages alone (nil = no target, take rung 1)
  :keep-tail   override the tail length of the first rung
  :max-chars   override the clip size of the first rung"
  ([msgs] (distill msgs {}))
  ([msgs {:keys [budget keep-tail max-chars]}]
   (let [ladder (cond-> (vec distillation-ladder)
                  (and keep-tail max-chars) (assoc 0 [(long keep-tail) (long max-chars)])
                  (and keep-tail (not max-chars)) (assoc-in [0 0] (long keep-tail))
                  (and max-chars (not keep-tail)) (assoc-in [0 1] (long max-chars)))
         fitted (when (and budget (pos? (long budget)))
                  (first (filter (fn [[t c]] (<= (est-tokens (build msgs t c)) (long budget)))
                                 ladder)))]
     (if fitted
       (apply build msgs fitted)
       ;; No target, or nothing on the ladder fits: take the harshest rung. It is
       ;; still a conversation (system + task + last message), so "we could not
       ;; fit" can never mean "we threw the thread away".
       (apply build msgs (last ladder))))))

;; --- what the provider says when the prompt is too big -----------------------

(def ^:private overflow-capable-statuses
  "Statuses on which a body is worth reading for a context overflow. Everything
  else is somebody else's failure mode (a 401, an overloaded 503) and must not be
  called an overflow — mislabelling those would trade one wrong diagnosis for
  another."
  #{400 413 422})

(def ^:private overflow-patterns
  "How providers SAY 'the prompt did not fit'. Every one of these has been seen in
  the wild; the list is long on purpose, because the cost of a miss is the very
  failure this whole change exists to prevent — a dead thread."
  [#"(?i)exceeds? the (?:model'?s )?(?:context|available|maximum)"
   #"(?i)context[ _]length[ _]exceeded"
   #"(?i)context window"
   #"(?i)maximum context"
   #"(?i)exceeds the (?:maximum )?context"
   #"(?i)prompt is too long"
   #"(?i)(?:input|prompt)[^.\n]{0,40}too (?:long|large|big)"
   #"(?i)too many tokens"
   #"(?i)reduce the length"
   #"(?i)context size"
   #"(?i)request exceeds"])

(def ^:private limit-patterns
  "Where providers put the CEILING. First match wins, so the more specific shapes
  come first: llama.cpp's `context (32768)` must beat the generic
  `…(\\d+) tokens` trap — note the prompt count in the same sentence
  (`prompt (38683 tokens)`) is MATCHED SEPARATELY below, never as the limit."
  [#"(?i)context\s*\((\d{3,})\)"
   #"(?i)maximum context length is\s*(\d{2,9})"
   #"(?i)maximum number of tokens allowed\s*\(?(\d{2,9})\)?"
   #"(?i)context (?:window|length|size)[^0-9\n]{0,40}(\d{3,})"
   #"(?i)max(?:imum)?[^0-9\n]{0,40}(\d{3,})\s*tokens"
   #"(?i)allowed\D{0,20}(\d{3,})"])

(def ^:private prompt-patterns
  "Where providers put WHAT WE SENT — useful evidence (it calibrates the estimate
  and proves the refusal is about size) but never the limit."
  [#"(?i)prompt\s*\((\d{3,})\s*tokens\)"
   #"(?i)resulted in\s*(\d{3,})\s*tokens"
   #"(?i)(?:input token count|prompt tokens?)\D{0,20}(\d{3,})"
   #"(?i)(\d{3,})\s*tokens?\s*(?:>|exceeds)"
   #"(?i)contains\s*(\d{3,})\s*tokens"])

(defn- first-int [patterns s]
  (some (fn [re] (when-let [m (re-find re (str s))] (parse-long (second m)))) patterns))

(defn parse-overflow
  "Classify an HTTP FAILURE BODY as a context overflow, or nil.

  Pure and total: takes the status and the body text, returns
    {:overflow? true :context-limit n-or-nil :prompt-tokens n-or-nil}
  or nil. `:context-limit` is the provider's own ceiling when it names one — that
  number is the best evidence grog will ever get about the real window, so it is
  returned rather than thrown away.

  Statuses other than 400/413/422 are never called an overflow: a 401 that
  happens to mention 'context' is a credential problem, and naming it an overflow
  would replace one wrong diagnosis with another."
  [status text]
  (let [s (str text)
        status (when (number? status) (long status))]
    (when (and (contains? overflow-capable-statuses (long (or status 0)))
               (some #(re-find % s) overflow-patterns))
      {:overflow? true
       :status (long status)
       :context-limit (first-int limit-patterns s)
       :prompt-tokens (first-int prompt-patterns s)})))
