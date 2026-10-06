(ns grog.docs
  "Where grog's own documentation lives at runtime, and what is in it.

  The docs ship INSIDE the application bundle (Electron `extraResources` →
  `resources/docs`). A packaged AppImage mounts them read-only inside the
  squashfs, so they are found through `GROG_DOCS_DIR`, which the client sets
  from the one place that knows the bundle location. From a source tree the
  variable is absent and we locate the repo root by looking for `USERS-GUIDE.md`.

  Nothing here COPIES documentation. The bootstrap project (`grog.bootstrap`)
  REFERENCES this directory — it becomes an extra ECA workspace folder — so the
  shipped docs stay the single source of truth and a rebuild flows straight
  into the project. Truth flows bundle → project, never the other way."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)))

(def ^:private marker
  "A file that only exists at the documentation root — used to locate the docs."
  "USERS-GUIDE.md")

(defn- has-docs? [^File d]
  (and d (.isDirectory d) (.exists (io/file d marker))))

(defn- walk-up
  "First ancestor of `start` (including itself) that contains the docs marker,
  or nil. Bounded, so a pathological tree cannot loop forever."
  ^File [^File start]
  (loop [d (when start (.getAbsoluteFile start)) n 0]
    (cond
      (nil? d) nil
      (has-docs? d) d
      (> n 8) nil
      :else (recur (.getParentFile d) (inc n)))))

(defn docs-dir
  "Absolute `File` of the documentation root, or nil when it cannot be found.

  Resolution: `GROG_DOCS_DIR` (set by the Electron client, absolute) → else walk
  up from the process working directory looking for `USERS-GUIDE.md`."
  ^File []
  (or (when-let [raw (some-> (System/getenv "GROG_DOCS_DIR") str str/trim not-empty)]
        (let [f (io/file raw)] (when (.isDirectory f) f)))
      (walk-up (io/file (System/getProperty "user.dir")))))

(defn doc-index
  "The docs grog ships, as `[{:path … :title … :answers …}]`.

  `:path` is RELATIVE to `docs-dir`, so the same entry resolves identically in a
  source tree (docs-dir = the repo root) and inside the bundle (docs-dir =
  `resources/docs`) — the packaged tree mirrors the repo layout under `docs/`."
  []
  [{:path "operating-model.md" :title "Operating model"
    :answers "what grog is FOR: the friend, projects, skills, and how a teammate inherits the capability"}
   {:path "USERS-GUIDE.md" :title "Users guide"
    :answers "configuring grog: grog.edn, secrets, providers, the secret store"}
   {:path "README.md" :title "README"
    :answers "the overview, the tool list, chat commands, and the full documentation map"}
   {:path "mcp-servers.md" :title "MCP servers & tools"
    :answers "every built-in tool and what it does"}
   {:path "eca-users-guide.md" :title "ECA users guide"
    :answers "enabling grog's MCP servers inside ECA on another machine"}
   {:path "SOUL.md" :title "SOUL (agent rules)"
    :answers "the agent's persistent rules of behavior"}
   {:path "skills/SKILL-TEMPLATE.md" :title "Skill template"
    :answers "how to build a reusable skill"}
   {:path "doc/linux-quick-start.md" :title "Linux quick start"
    :answers "installing and running grog on Linux"}
   {:path "doc/windows-quick-start.md" :title "Windows quick start"
    :answers "installing and running grog on Windows"}
   {:path "doc/desktop-kit.md" :title "Desktop kit"
    :answers "the Electron desktop client, its launchers, and packaging"}
   {:path "doc/multi-client-layout.md" :title "Multi-client layout"
    :answers "how the server, clients, and sessions fit together"}])

(defn doc-path
  "Absolute path string for a docs-relative path (a `doc-index` `:path` or any
  other file under the docs root), or nil when the docs dir is unknown."
  ^String [rel]
  (when-let [^File d (docs-dir)]
    (.getPath (io/file d (str rel)))))

(defn doc-map-markdown
  "A compact markdown table of the doc index, for the getting-started project's
  START-HERE note (and any CLI that wants to print the map).

  `prefix` is prepended to every `:path` — pass `\"grog-docs/\"` when the docs dir
  is an ECA workspace folder by that name, so the pointers read as stable
  workspace-relative paths rather than an ephemeral bundle mount.

  That note IS loaded into standing context (notes/ is slurped every turn), so
  this stays a table of POINTERS — it must never inline the documents."
  ([] (doc-map-markdown ""))
  ([prefix]
   (let [prefix (str prefix)]
     (str "| doc | answers |\n|---|---|\n"
          (str/join "\n"
                    (for [{:keys [path title answers]} (doc-index)]
                      (str "| `" prefix path "` — " title " | " answers " |")))))))
