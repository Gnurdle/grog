(ns grog.bootstrap
  "The FIRST-RUN getting-started project (`ouroboros`).

  Nobody reads a docs folder. So the first experience is a PROJECT: an ordinary
  grog project whose `SOUL.md` makes the agent the onboarding guide and whose ECA
  workspace includes grog's shipped documentation (`grog.docs`). The app
  bootstraps itself by talking to itself — that is the ouroboros. That's what
  grog is — a deliberate vicious cycle.

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
   "You are in **" project-name "**, grog's getting-started project — an ordinary\n"
   "project, not a special mode. A project is where grog keeps its memory of one\n"
   "thing you work on: its notes, the conversation, its workspace, and the tools it\n"
   "may touch. Everything grog remembers belongs to a project, so two projects never\n"
   "bleed into each other.\n\n"
   "This one exists to get you running. Once the brain is online, start the project\n"
   "you actually care about with `/project new <name>` and get on with it — this one\n"
   "stays behind as your reference copy of grog's documentation.\n\n"
   "## Job 1 — get the brain online\n\n"
   "Two settings, both yours to make: open **Settings (⚙)** and pick the provider\n"
   "and model to match what you actually have; then hand grog the key with\n"
   "`/secret set LLM_API_KEY <value>`. The key goes to the OS keychain and is never\n"
   "echoed back. Until then there is no brain and no chat.\n\n"
   "## Getting help\n\n"
   "Type `/help` for every command. `/project` lists, switches and creates projects;\n"
   "`/model` shows or switches the model for this session; `/clear` starts a fresh\n"
   "conversation; `/doctor` checks whether the setup holds together; `/reset` is the\n"
   "factory reset if things get too wonky.\n\n"
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

;; True while onboarding is WANTED for the CURRENT launch — either an EXPLICIT
;; request (a factory-reset marker, or the first-run config seed) or a genuinely
;; fresh install (no projects at all).
;;
;; Why this exists: the onboarding page must not be gated on reachability alone.
;; A reset (or a wiped config home) moves grog's OWN state aside but does NOT
;; touch ECA's config, and the seeded `grog.edn` already names an endpoint and a
;; model — so a model can still be reachable, and the user would land in the
;; getting-started project with NO onboarding (exactly the reported bug).
;; Remembering that onboarding is wanted lets `startup` and every `open` snapshot
;; agree, regardless of whether a model happens to be reachable.
;;
;; It ESCALATES within a launch and is only cleared when the user leaves the
;; getting-started project (`note-opened-project!`). It is deliberately NOT
;; reset on every `startup-info` call: `startup` consumes the marker, so a
;; SECOND call (the client asks once at init and again on attach) would see no
;; marker and recompute `false` — silently dropping the onboarding landing and
;; leaving the empty-transcript splash in its place.
(defonce ^:private !onboarding-wanted (atom false))

(defn onboarding-wanted-this-session?
  "True when THIS launch should show the getting-started onboarding — a fresh
  install (no projects) or an explicit request (the reset marker, or the
  first-run config seed). The client shows the landing even when a model is
  already reachable."
  []
  (boolean @!onboarding-wanted))

(defn note-opened-project!
  "Record which project a client just opened. Onboarding is tied to the
  getting-started project: opening any OTHER project ends it for this launch, so
  a user who moves on to their own work is never dragged back to the landing.
  (This is also what stops a long-lived socket daemon onboarding forever — once
  anyone opens a normal project, it is over.)"
  [project]
  (when-not (projects/bootstrap-project? project)
    (reset! !onboarding-wanted false)))

(defn startup-info
  "What the client should OPEN at launch, and whether this is a first run.

  Onboarding (seed + open `ouroboros`) happens when the config home was just
  seeded or reset (the `onboarding-requested` marker), or on a genuinely fresh
  install (no projects at all). Otherwise open the resolved active project and
  never touch the getting-started project, so a returning user is never flipped
  into onboarding — and a reset never hijacks their projects.

  Whether onboarding is wanted is remembered for the rest of THIS launch
  (`onboarding-wanted-this-session?`), so `startup` and every `open` snapshot
  agree and the landing keeps showing even when a model is already reachable."
  []
  (let [requested?       (onboarding-requested?)
        want-onboarding? (or requested? (first-run?))]
    ;; ESCALATE only — never downgrade within a launch. The first `startup`
    ;; consumes the marker; a duplicate/retried call must not read the now-absent
    ;; marker and conclude onboarding is off (that dropped the landing behind the
    ;; splash). It is cleared later by `note-opened-project!` when the client
    ;; opens a non-getting-started project.
    (when want-onboarding?
      (reset! !onboarding-wanted true))
    (if want-onboarding?
      (do (clear-onboarding-request!)
          (seed!)
          (projects/set-project! project-name)
          {:project project-name :first-run? true})
      {:project (projects/resolve-active-project)
       :first-run? (projects/bootstrap-project? (projects/project-name))})))
