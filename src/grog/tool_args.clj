(ns grog.tool-args
  "One-line previews of tool-call parameters.

  Shared by the GUI transcript's tool cards (collapsed header, selection text
  and hover tooltip), by `grog.ui/summarize-content` in the ECA debug log, and
  by the CLI tool log in `grog.core` — anywhere a tool call is reduced to a
  single line and the tool name alone isn't enough to know what happened.

  Deliberately dependency-free (no Swing, no JSON parsing): the parameters
  arrive from ECA as a map, as `:argumentsText` JSON/EDN text, or from an LLM
  `tool_call` as a JSON string, and every one of those is useful to a human
  as-is once whitespace is collapsed and the result is length-capped."
  (:require [clojure.string :as str]))

(def ^:private default-max-chars
  "Cap for a preview when the caller doesn't pick one. Short enough to sit on
  one card-header line, long enough to show the interesting part of a command
  or path."
  160)

(defn- one-line
  "Collapse runs of whitespace to single spaces and trim. nil when blank."
  [s]
  (let [t (str/trim (str/replace (str s) #"\s+" " "))]
    (when (seq t) t)))

(defn preview
  "A single-line, whitespace-collapsed, length-capped preview of a tool call's
  parameters `args`, or nil when there is nothing worth showing.

  `args` may be a map (`{:command \"ls -la\"}` → `{\\:command \"ls -la\"}`), a
  JSON/EDN string (passed through, just collapsed and capped), or any other
  scalar. Empty maps/vectors and blank strings yield nil rather than `{}`.

  The result never exceeds `max-chars` (default 160); a cut is marked with `…`."
  ([args] (preview args default-max-chars))
  ([args max-chars]
   (let [cap (long max-chars)
         raw (cond
               (nil? args)  nil
               (map? args)  (when (seq args) (pr-str args))
               (coll? args) (when (seq args) (pr-str args))
               :else        (str args))
         s (some-> raw one-line)]
     (when s
       (if (<= (count s) cap)
         s
         (str (subs s 0 (max 1 (dec cap))) "…"))))))
