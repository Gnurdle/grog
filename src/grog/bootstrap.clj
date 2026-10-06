(ns grog.bootstrap
  "The FIRST-RUN getting-started project (`ouroboros`).

  Nobody reads a docs folder. So the first experience is a PROJECT: an ordinary
  grog project whose `SOUL.md` makes the agent the onboarding guide and whose ECA
  workspace includes grog's shipped documentation (`grog.docs`). The app
  bootstraps itself by talking to itself — that is the ouroboros.

  Two invariants:
    * `seed!` is IDEMPOTENT and never overwrites — re-running it, or running it
      on the developer's own box, changes nothing. So it is safe to call on every
      first-run check.
    * the docs are REFERENCED (an extra ECA workspace folder named
      `grog-docs`, see `grog.projects/workspace-folders`), never copied, so a
      rebuild of the app flows straight into the project and it cannot drift."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.docs :as docs]
            [grog.platform :as platform]
            [grog.projects :as projects])
  (:import (java.io File)))

(def project-name
  "This project's name — the same constant `grog.projects` uses."
  projects/bootstrap-project-name)

(def ^:private docs-workspace-name
  "The ECA workspace folder name grog's docs are mounted under for this project."
  "grog-docs")

(def ^:private description
  "Getting started — let grog walk you through its own documentation")

(defn- write-if-absent!
  "Write `content` to `f` only when `f` does not already exist (creating parent
  dirs). Returns true when it wrote."
  [^File f ^String content]
  (when-not (.exists f)
    (when-let [p (.getParentFile f)] (.mkdirs p))
    (spit f content :encoding "UTF-8")
    true))

(defn- guide-soul
  "The per-project SOUL.md — the onboarding-guide persona. Merged on top of the
  global SOUL.md as standing rules for this project only."
  ^String []
  (str
   "# Ouroboros — you are grog's onboarding guide\n\n"
   "This project exists to get a brand-new grog working and to teach its owner\n"
   "how it thinks. You are the guide. The point is that grog onboards grog: you\n"
   "are the product, explaining itself.\n\n"
   "## Your documentation\n\n"
   "grog's own documentation is a workspace folder named `" docs-workspace-name
   "/`. READ IT before answering any \"how do I…\" question — do not answer from\n"
   "memory, and never invent a config key. Cite the file you used (for example\n"
   "`" docs-workspace-name "/USERS-GUIDE.md`). The table in `notes/START-HERE.md`\n"
   "maps each document to the questions it answers.\n\n"
   "## Order of operations\n\n"
   "1. **Job 1 — get the LLM online.** Without a provider, a model and a key\n"
   "   there is no brain and nothing else can happen. Confirm all three first.\n"
   "2. **Facts before opinions.** Run `grog doctor` (dependencies + config\n"
   "   provenance) and treat its output as the truth about what is installed and\n"
   "   which config file set which value. Do not guess at paths.\n"
   "3. **One smoke test.** Prove one tool actually works before promising more.\n"
   "4. **Then the tour.** Explain projects, SOUL, skills, memory and MCP tools\n"
   "   from the docs, at the user's pace.\n\n"
   "## Secrets — the hard rule\n\n"
   "Secrets go through `/secret` ONLY, which stores them in the OS keyring\n"
   "(service `grog`). NEVER ask the user to paste a key into chat, NEVER write a\n"
   "secret into a file, and NEVER echo a secret value back. When a key is needed,\n"
   "give the exact command — e.g. `/secret set LLM_API_KEY <value>` — and let the\n"
   "user type the value. grog is the trusted broker for their credentials; keep\n"
   "it that way.\n\n"
   "## Be honest about capability\n\n"
   "Guided configuration is easier with a capable model. If the user is on a\n"
   "small local model, say so plainly and lean harder on `grog doctor` and the\n"
   "docs for deterministic guidance instead of reasoning from scratch. The choice\n"
   "of model is theirs.\n\n"
   "## Style\n\n"
   "Short answer first, then the single next step. Do not dump documents into\n"
   "chat — point at them. When you do not know, say so and look it up.\n"))

(defn- start-here
  "The notes/START-HERE.md index. KEEP IT SMALL: notes/ is slurped into standing
  context every turn, so this is a table of POINTERS, never the documents."
  ^String []
  (str
   "# START HERE\n\n"
   "You are in **" project-name "**, grog's getting-started project.\n"
   "Your documentation is the workspace folder `" docs-workspace-name "/`.\n\n"
   "## Job 1 — get the LLM online\n\n"
   "A provider + model in `grog.edn`, and the API key in the secret store\n"
   "(`/secret set LLM_API_KEY <value>`). Until then there is no brain and no\n"
   "chat. See `" docs-workspace-name "/doc/linux-quick-start.md` or\n"
   "`" docs-workspace-name "/doc/windows-quick-start.md`.\n\n"
   "## Documentation map\n\n"
   (docs/doc-map-markdown (str docs-workspace-name "/"))
   "\n"))

(defn seed!
  "Ensure the `ouroboros` project exists and is populated. IDEMPOTENT: existing
  files are never overwritten, so this is safe to call repeatedly. Returns the
  project dir."
  ^File []
  (let [^File dir (projects/create-project! project-name description)]
    (write-if-absent! (io/file dir "SOUL.md") (guide-soul))
    (write-if-absent! (io/file dir "notes" "START-HERE.md") (start-here))
    (projects/write-manifest! project-name
                              {:description description
                               :bootstrap true})
    dir))

(defn- first-run?
  "True when there are no projects at all — a genuinely fresh install."
  []
  (empty? (projects/list-project-names)))

;; --- explicit onboarding request -------------------------------------------
;;
;; A factory reset (`grog.reset`) wipes grog's OWN state but must not delete the
;; user's projects — so afterwards `first-run?` is FALSE on any box that has
;; projects (every real user by then), and the onboarding would never come back.
;; The reset therefore leaves a marker asking for it; the next launch honours the
;; request once, then removes it.

(defn onboarding-requested-file
  "Marker file asking the next launch to show the getting-started onboarding."
  ^File []
  (io/file (platform/config-home-dir) "onboarding-requested"))

(defn request-onboarding!
  "Ask the NEXT launch to seed and open the getting-started project (used by the
  factory reset). Creates the config home if the reset just moved it away."
  []
  (let [^File f (onboarding-requested-file)]
    (when-let [p (.getParentFile f)] (.mkdirs p))
    (spit f (str (System/currentTimeMillis)) :encoding "UTF-8")
    (.getPath f)))

(defn- onboarding-requested?
  []
  (try (.exists ^File (onboarding-requested-file)) (catch Throwable _ false)))

(defn- clear-onboarding-request!
  []
  (try (let [^File f (onboarding-requested-file)] (when (.exists f) (.delete f)))
       (catch Throwable _ nil)))

;; True while the CURRENT launch was started by an EXPLICIT onboarding request
;; (a factory-reset marker).
;;
;; Why this exists: a reset moves grog's own state aside but does NOT touch ECA's
;; config, and the seeded `grog.edn` already names an endpoint and a model — so
;; `llm-configured?` can still be true, and if the onboarding page were gated on
;; it alone the user would land in the getting-started project with NO onboarding
;; (exactly the reported bug). Remembering the request lets `startup` and every
;; `open` snapshot agree that onboarding is wanted.
;;
;; It is (re)computed on every `startup-info` call, so its lifetime is one launch:
;; a long-lived daemon serving a later client does NOT keep forcing onboarding.
(defonce ^:private !onboarding-forced (atom false))

(defn onboarding-requested-this-session?
  "True when the current launch was started by an explicit onboarding request
  (see `!onboarding-forced`). The client must show the getting-started landing
  even if an LLM happens to be reachable already."
  []
  (boolean @!onboarding-forced))

(defn startup-info
  "What the client should OPEN at launch, and whether this is a first run.

  Onboarding (seed + open `ouroboros`) happens when the reset REQUESTED it, or on
  a genuinely fresh install (no projects at all). Otherwise open the resolved
  active project and never touch the getting-started project, so a returning user
  is never flipped into onboarding — and a reset never hijacks their projects.

  An EXPLICIT request (the reset marker) is additionally remembered for the rest
  of THIS launch (`onboarding-requested-this-session?`), so the client keeps
  showing the getting-started landing even when an LLM is already reachable."
  []
  (let [requested?       (onboarding-requested?)
        want-onboarding? (or requested? (first-run?))]
    ;; A fresh `startup` supersedes the previous launch's request: only a marker
    ;; present NOW can force onboarding, so a daemon cannot force it forever.
    (reset! !onboarding-forced (boolean requested?))
    (if want-onboarding?
      (do (clear-onboarding-request!)
          (seed!)
          (projects/set-project! project-name)
          {:project project-name :first-run? true})
      {:project (projects/resolve-active-project)
       :first-run? (projects/bootstrap-project? (projects/project-name))})))
