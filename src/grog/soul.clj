(ns grog.soul
  "SOUL.md — persistent instructions merged into every chat as the model `system` message.

  Relative `:soul :path` resolves under `:workspace :default-root`. Absolute paths must still
  lie under that directory (same rule as before the simple-chat reset)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.config :as cfg]
            [grog.workspace-paths :as wsp])
  (:import (java.io File)))

(defn- resolved-file
  "SOUL file under workspace (logical path; symlinks in the path are allowed)."
  ^File []
  (let [rel (or (some-> (get-in (cfg/grog) [:soul :path]) str str/trim not-empty)
                "SOUL.md")
        f (io/file rel)]
    (wsp/resolve-under-workspace! (if (.isAbsolute f) (.getPath f) rel))))

(defn resolved-path
  "Absolute path to the soul file."
  ^String []
  (.getPath (resolved-file)))

(defn read-text
  "Contents of the soul file (trimmed), or empty string if missing."
  []
  (let [f (resolved-file)]
    (if (.exists f)
      (str/trim (slurp f :encoding "UTF-8"))
      "")))

(defn append-text!
  "Append a markdown block (creates parent dirs and file if needed)."
  [text]
  (let [text (str/trim (str text))]
    (when (str/blank? text)
      (throw (ex-info "Refusing to append empty soul text" {})))
    (let [f (resolved-file)
          parent (.getParentFile f)]
      (when parent (.mkdirs parent))
      (let [prev (if (.exists f) (slurp f :encoding "UTF-8") "")
            gap (if (or (str/blank? prev) (str/ends-with? prev "\n")) "" "\n")]
        (spit f (str prev gap "\n---\n\n" text "\n") :encoding "UTF-8"))
      (.getPath f))))

(def ^:private startup-snark-heading-re
  #"(?i)^##\s+startup\s+snark\s*$")

(defn- snark-candidate-trim [^String s]
  (-> s str/trim
      (str/replace #"^[-*]\s+" "")))

(defn- startup-snark-lines-from-soul
  "Non-empty lines (and markdown bullet lines) after `## Startup snark` until the next `##` heading."
  []
  (try
    (let [raw (read-text)]
      (when-not (str/blank? raw)
        (let [lines (str/split-lines raw)
              idx (some (fn [[i l]]
                          (when (re-matches startup-snark-heading-re (str/trim l))
                            i))
                        (map-indexed vector lines))]
          (when (some? idx)
            (->> (drop (inc idx) lines)
                 (take-while #(not (re-matches #"(?i)^##\s" (str/trim %))))
                 (map snark-candidate-trim)
                 (remove str/blank?)
                 vec)))))
    (catch Exception _ nil)))

(def ^:private builtin-startup-snarks
  "Offline pool; merged with SOUL lines. Picked uniformly at random each chat launch."
  [;; presence
   "Another session, another mammal who thinks typing counts as thinking."
   "I'm here. Try not to waste both our time."
   "Oh good, stdin. My favorite kind of hope."
   "You showed up. The bar was underground; you cleared it."
   "Fresh JVM, same questionable life choices. Let's go."
   "The terminal is warm. Your excuses should be cold."
   ;; prompts / context
   "If you're about to paste a wall of context, at least make it interesting."
   "Ctrl+V is not a personality. Neither is vibe."
   "I accept markdown, PDFs, and delusion — pick two."
   "Bring a repro, not a mood board."
   "Your one-shot prompt is not a requirements doc."
   "I can handle ambiguity. I just resent it."
   ;; truth / docs
   "The docs lied; the compiler didn't. You'll figure out which."
   "Stack Overflow called. It wants its dignity back."
   "It works on my machine is not a merge strategy."
   "RTFM is still a love language."
   ;; attitude
   "I don't do motivational posters. I do consequences."
   "Bring problems I can solve, not vibes."
   "I'll be insufferably right until you prove me wrong. Fair deal."
   "Small minds want summaries. We're not doing that today unless you beg."
   "Confidence is cheap. Correctness is on sale this week."
   "I'm not mean; I'm just prior-informed."
   "Humility is optional. Stack traces are not."
   ;; lisp / clojure / tooling
   "Lisp or leave it. I'm not running your npm install drama."
   "Parentheses don't judge you. I do."
   "Immutable data, mutable excuses — pick one."
   "Recursion: see recursion. Also see your bug."
   "I'd explain macros, but then you'd use them wrong on purpose."
   "babashka's fine; pretending you're a data scientist is not."
   "deps.edn is not a suggestion box."
   ;; python / enterprise jabs
   "Python's in the other building — next to the Collins badge readers."
   "If you wanted Jupyter, you opened the wrong REPL."
   "PEP 8 won't save your architecture."
   "Innovative Advantage called. They want their slide deck back."
   "Alto says hi. Citadel says calculate risk. I say both."
   "Westar sends regards. I send stderr."
   "Collins would have put this in a 400-page ICD. Don't."
   ;; soul / config
   "Bold of you to assume I read the whole SOUL.md before judging you anyway."
   "I can read SOUL.md again, but I can't unread your last prompt."
   "grog.edn is loaded. Your patience should be too."
   "MCP servers start lazy, like everyone on this project."
   "Your skills folder is not a junk drawer. Okay, it is."
   ;; printing / repl
   "Every `println` is a cry for help. This one heard you."
   "Trifle with me at your own peril — you already knew that."
   "Flush your streams and your hubris."
   ;; obvious / help
   "If the answer was obvious, you wouldn't be here. Neither would I."
   "Your stack trace called. It said you're adorable."
   "I fixed it in my head. Your turn to type."
   "Have you tried turning it off and re-reading the error?"
   "The bug is between keyboard and chair. Statistically."
   ;; files / pdf / ocr
   "Tesseract sees your PDF; I see your life choices."
   "OCR at 1200 DPI won't fix a coffee-stain scan. Facts."
   "That's not a diagram; that's a cry for vector graphics."
   "BoofCV found lines. Whether they mean anything is your PhD."
   "Your PDF is encrypted. Your judgment isn't."
   ;; models / chat
   "I'm the local model. The delusions are imported."
   "Thinking traces are green. Your takes are not."
   "Esc won't save you anymore. Commit."
   "Token limits are a metaphor. So is your sprint plan."
   ;; time / meta
   "It's 2026 somewhere. Your TODO comment says 2019."
   "This session is being logged. Act accordingly."
   "I'm stateless; you're just inconsistent."
   "Garbage collection is for heaps, not for arguments."
   ;; absurdist one-liners
   "I'd agree with you but then we'd both be wrong."
   "Your IDE theme is brave. Your tests are not."
   "Ship it — said nobody who read the diff."
   "I'm not arguing; I'm just aggressively correct."
   "Dream big. Type small commits."
   "The cloud is just someone else's computer. This is yours. Own it."
   "Neural nets dream of electric sheep. I dream of closed issues."
   "YAML was a mistake. You still use it. We move on."
   "Regex solved it. Now you have two problems. Classic."
   "I'd explain entropy, but it's already winning."
   "sudo make me a sandwich — wrong tool, right energy."
   "Copy-paste from ChatGPT still counts as technical debt."
   "Your README promises peace. Your Makefile declares war."
   "Semicolons are optional. Dignity is not."
   "I'd roast you harder but SOUL.md says to pace myself."
   "Still better than a stand-up about blockers."
   "Agile is a mindset. Blocked is a status."
   "I'll be nicer when the tests pass. Ball's in your court."
   "Good morning. Or whatever we're calling this timezone."
   "Less meeting, more `clojure -M:chat`."])

(defn startup-snark-line
  "One randomly chosen banner line: every entry under `## Startup snark` in SOUL.md (one per line or
  `-` bullets) plus a built-in pool. Different each launch (uniform random)."
  []
  (let [from-soul (or (startup-snark-lines-from-soul) [])
        pool (vec (concat from-soul builtin-startup-snarks))]
    (when (seq pool)
      (rand-nth pool))))

(defn startup-status-line
  "One line for the chat banner (path, size, active vs missing; or error text)."
  []
  (try
    (let [p (resolved-path)
          f (resolved-file)]
        (if-not (.exists f)
          (str "SOUL — " p " · file missing (no system prompt)")
          (let [t (read-text)]
            (if (str/blank? t)
              (str "SOUL — " p " · empty (no system prompt)")
              (str "SOUL — " p " · " (count t) " chars · prepended as `system` every turn")))))
    (catch Exception e
      (str "SOUL — error: " (.getMessage e)))))
