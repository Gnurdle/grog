(ns grog.platform
  "Cross-platform OS/path helpers shared by config, secrets, and ECA config
  generation. Kept free of other grog namespaces so both `grog.config` (which
  requires `grog.secrets`) and `grog.secrets` can use it without a require cycle."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)))

(defn windows?
  "True when running on a Microsoft Windows OS."
  []
  (str/includes? (str/lower-case (System/getProperty "os.name" "unknown")) "win"))

(defn user-home
  "The user's home directory as a native (Windows or POSIX) path string.
  On Windows this prefers the `USERPROFILE` env var (and falls back to
  `HOMEDRIVE`+`HOMEPATH`, then `user.home`), because a JVM launched from Git
  Bash / MSYS can otherwise report a `/c/Users/...` or `/home/...` POSIX-style
  home that `WinNTFileSystem` cannot canonicalize. On POSIX it returns
  `user.home` (optionally overridden by a `HOME` env var)."
  ^String []
  (if-let [h (or (when (windows?)
                   (or (some-> (System/getenv "USERPROFILE") str str/trim not-empty)
                       (some-> (System/getenv "HOMEDRIVE") str str/trim not-empty
                               (str (or (some-> (System/getenv "HOMEPATH") str str/trim not-empty) "")))
                       (some-> (System/getenv "HOME") str str/trim not-empty)))
                 (some-> (System/getProperty "user.home") str str/trim not-empty))]
    h
    (str (System/getProperty "user.home"))))

(defn msys-path->windows
  "Translate a POSIX/MSYS style path (`/c/Users/foo` or `/c/Users/foo/bar`)
  into a Windows form (`C:\\Users\\foo\\bar`). Leaves Windows-native paths and
  plain relative paths untouched. Only meaningful on Windows; on POSIX the
  input is returned unchanged. Mirrors the helper in `grog.eca`."
  ^String [^String s]
  (if (or (nil? s) (not (windows?))
          (str/includes? s "\\")       ; already Windows-ish (the common native case)
          (re-matches #"(?i)^[a-z]:[/\\]" s)  ; already drive-qualified
          (not (re-matches #"(?i)^/[a-z]/.*$" (str s))))  ; not `/c/...`
    s
    (str (str/upper-case (subs s 1 2)) ":" (subs s 2))))

(defn expand-home
  "Expand a leading `~` to the user's home dir (handles `~/…`, `~\\…`, and a
  bare `~`). Returns the string unchanged when it doesn't start with `~`.
  Separator-agnostic so Windows (`~\\grog-projects`) and POSIX (`~/grog-projects`)
  home-relative paths both expand. The expanded home is converted to a native
  Windows path when running on Windows (MSYS `/c/...` -> `C:\\...`)."
  ^String [^String s]
  (if (and s (str/starts-with? s "~"))
    (let [home (msys-path->windows (user-home))]
      (str/replace-first s #"^~(?=[/\\]|$)" home))
    s))

(defn canonical-file
  "A canonical `File` for `f`, but resilient to the Windows JVM's surprising
  `WinNTFileSystem.canonicalize0` failures on MSYS-derived paths: if
  canonicalization throws, fall back to the plain absolute form so the caller
  still gets a usable path instead of killing the app at startup."
  ^File [^File f]
  (try
    (.getCanonicalFile f)
    (catch Exception _
      (when f (.getAbsoluteFile f)))))

(defn config-home-dir
  "Where grog keeps its user-level config, generated ECA config, and the secret
  store. Resolution order:
    1. `GROG_CONFIG_HOME` env var (absolute or home-relative path)
    2. Windows: `%APPDATA%\\grog` (falls back to `~/AppData/Roaming/grog`)
    3. Linux/macOS: `${XDG_CONFIG_HOME:-~/.config}/grog`
  Returns a canonical File (may not exist yet)."
  ^File []
  (let [raw (or (some-> (System/getenv "GROG_CONFIG_HOME") str str/trim not-empty)
                (when (windows?)
                  (or (some-> (System/getenv "APPDATA") str str/trim not-empty
                              (str "/grog"))
                      (str (msys-path->windows (user-home)) "/AppData/Roaming/grog")))
                (str/join "/" [(or (some-> (System/getenv "XDG_CONFIG_HOME") str str/trim not-empty)
                                   (str (user-home) "/.config"))
                               "grog"]))
        f (io/file (expand-home raw))]
    (canonical-file f)))