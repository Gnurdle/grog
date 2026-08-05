(ns grog.md-stream
  "Incremental Markdown → ANSI streaming renderer.

   Incoming text is buffered until a Markdown block can be considered complete:
   paragraphs, headings, tables and similar leaf blocks emit when followed by a
   blank line; fenced code blocks emit when their closing fence arrives. Anything
   that does not yet form a complete block is retained for the next chunk.

   This is a heuristic on top of CommonMark: blocks that need lookahead (GFM
   pipe tables, fenced code blocks) still wait for their terminator, but prose
   can stream line-by-line."
  (:require [clojure.string :as str]
            [grog.md-render :as md-render]))

(defn empty-state
  "Returns a fresh streamer state. State keys are intentionally plain so callers
   can inspect them, but the streamer should normally be driven through `feed`
   and `finish`."
  []
  {:buffer ""      ; partial line at the end of the stream, not yet complete
   :pending []     ; complete lines waiting to form the next normal block
   :mode :normal   ; :normal or :code
   :code []        ; lines accumulated inside a fenced code block
   :fence nil      ; opening fence marker string
   :fence-len 0})  ; minimum length required for the closing fence

(defn- parse-fence
  "If `line` is a Markdown code fence (indented <= 3 spaces), return a map
   with the fence marker string, its length, and the rest of the line."
  [^String line]
  (when-let [[_ marker rest] (re-matches #"^ {0,3}(```+|~~~+)(.*)$" line)]
    {:marker marker
     :len    (count marker)
     :rest   (or rest "")}))

(defn- blank-line? [^String line]
  (str/blank? line))

(defn- closing-fence?
  "True when `line` closes the current fenced code block."
  [fence fence-len ^String line]
  (when-let [f (parse-fence line)]
    (and (= (:marker f) fence)
         (>= (:len f) fence-len)
         (str/blank? (:rest f)))))

(defn- emit-normal-block
  "Render the pending normal lines, if any. Returns a vector of 0 or 1 strings."
  [{:keys [pending]}]
  (if (seq pending)
    (let [block (str/join "\n" pending)]
      (if (seq (str/trim block))
        [(md-render/render-to-ansi block)]
        []))
    []))

(defn- process-normal-line
  "Handle one complete line while in :normal mode. Returns [emitted state]."
  [state ^String line]
  (if-let [f (parse-fence line)]
    ;; A fence line interrupts whatever normal block was building.
    (let [emitted (emit-normal-block state)]
      [emitted
       (-> state
           (assoc :pending [])
           (assoc :mode :code)
           (assoc :code [line])
           (assoc :fence (:marker f))
           (assoc :fence-len (:len f)))])
    (if (blank-line? line)
      (let [emitted (emit-normal-block state)]
        [emitted (assoc state :pending [])])
      [[] (update state :pending conj line)])))

(defn- process-code-line
  "Handle one complete line while inside a fenced code block."
  [state ^String line]
  (if (closing-fence? (:fence state) (:fence-len state) line)
    (let [block (str/join "\n" (conj (:code state) line))]
      [[(md-render/render-to-ansi block)]
       (-> state
           (assoc :mode :normal)
           (assoc :code [])
           (assoc :fence nil)
           (assoc :fence-len 0))])
    [[] (update state :code conj line)]))

(defn- process-lines
  "Process a sequence of complete lines. Returns [emitted-vec new-state]."
  [state lines]
  (loop [state state
         lines lines
         emitted []]
    (if (empty? lines)
      [emitted state]
      (let [line (first lines)
            rest-lines (rest lines)
            [e new-state] (case (:mode state)
                            :code   (process-code-line state line)
                            :normal (process-normal-line state line))]
        (recur new-state rest-lines (into emitted e))))))

(defn feed
  "Receive a fragment of Markdown text. Returns `[emitted-strings new-state]`,
   where `emitted-strings` is a vector of ANSI-rendered block strings that have
   become complete during this feed."
  [state ^String text]
  (let [combined (str (:buffer state) (or text ""))
        parts  (str/split combined #"\n" -1)
        ends-with-nl? (str/ends-with? combined "\n")]
    (if (= 1 (count parts))
      ;; No complete line yet; keep it all in the buffer.
      [[] (assoc state :buffer combined)]
      ;; `str/split` with -1 appends a trailing empty string when the input ends
      ;; in "\n"; that empty token is not itself a line, so always drop it.
      (let [complete-lines (butlast parts)
            trailing (when-not ends-with-nl? (last parts))
            [emitted new-state] (process-lines (assoc state :buffer "") complete-lines)]
        [emitted (assoc new-state :buffer (or trailing ""))]))))

(defn finish
  "Flush any remaining buffered content. Returns `[emitted-strings empty-state]`.
   Call this when the stream ends (e.g. SSE finish reason received)."
  [state]
  (case (:mode state)
    :normal
    (let [block-lines (cond-> (:pending state)
                        (seq (:buffer state))
                        (conj (:buffer state)))
          block (str/join "\n" block-lines)]
      [(if (seq (str/trim block))
         [(md-render/render-to-ansi block)]
         [])
       (empty-state)])

    :code
    (let [block-lines (cond-> (:code state)
                        (seq (:buffer state))
                        (conj (:buffer state)))
          block (str/join "\n" block-lines)]
      [[(md-render/render-to-ansi block)]
       (empty-state)])))
