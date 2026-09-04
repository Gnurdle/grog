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

(defn native-absolute?
  "True if `s` looks like a native absolute path for the current OS.
  On Windows that is a drive letter + separator (`C:\\...`, `C:/...`) or a UNC
  root (`\\server\\...`); on POSIX it is a leading `/`."
  [s]
  (boolean
   (when (and s (not (str/blank? (str s))))
     (if (windows?)
       (or (re-matches #"(?i)^[a-z]:[\\/].*" (str s))
           (re-matches #"^\\\\[^\\].*" (str s)))
       (str/starts-with? (str s) "/")))))

(defn user-home
  "The user's home directory as a native (Windows or POSIX) path string.
  On Windows it checks the home candidates in order (USERPROFILE,
  `HOMEDRIVE`+`HOMEPATH`, HOME, `user.home`) and picks the **first that is a
  valid native absolute path**. This guards against the mangled values a JVM
  launched from Git Bash / MSYS / cmdtools can report (e.g. `C:Userschopper`
  instead of `C:\\Users\\chopper`, or `/c/Users/...`), which would otherwise
  break `io/file` canonicalization downstream. On POSIX it returns
  `user.home` (optionally overridden by a `HOME` env var when absolute)."
  ^String []
  (cond
    (windows?)
    (let [cands [(some-> (System/getenv "USERPROFILE") str str/trim not-empty)
                 (let [hd (some-> (System/getenv "HOMEDRIVE") str str/trim not-empty)
                       hp (some-> (System/getenv "HOMEPATH") str str/trim not-empty)]
                   (when (and hd hp) (str hd hp)))
                 (some-> (System/getenv "HOME") str str/trim not-empty)
                 (some-> (System/getProperty "user.home") str str/trim not-empty)]]
      (or (some (fn [^String c] (when (native-absolute? c) c)) cands)
          (str (System/getProperty "user.home"))))

    :else
    (or (some-> (System/getProperty "user.home") str str/trim not-empty)
        (some-> (System/getenv "HOME") str str/trim not-empty)
        (str (System/getProperty "user.home")))))

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

(defn fix-drive-relative
  "Best-effort repair of a Windows drive-relative path (`C:Userschopper\\grog`)
  into an absolute `C:\\Userschopper\\grog`, by inserting the separator the
  mangling dropped. Returns the input unchanged when it isn't a drive-relative
  form (`^[a-z]:` with no `/` or `\\` immediately after the colon). Only
  meaningful on Windows."
  ^String [^String s]
  (if (and (windows?) s
           (re-matches #"(?i)^[a-z]:[^/\\].*" s))
    (str (subs s 0 2) "\\" (subs s 2))
    s))

(defn expand-home
  "Expand a leading `~` to the user's home dir (handles `~/…`, `~\\…`, and a
  bare `~`). Returns the string unchanged when it doesn't start with `~`.
  Separator-agnostic so Windows (`~\\grog-projects`) and POSIX (`~/grog-projects`)
  home-relative paths both expand. The expanded home is converted to a native
  Windows path when running on Windows (MSYS `/c/...` -> `C:\\...`); a mangled
  drive-relative home (`C:Userschopper`) is repaired to `C:\\Userschopper` so
  downstream `io/file` sees an absolute path."
  ^String [^String s]
  (if (and s (str/starts-with? s "~"))
    (-> (str/replace-first s #"^~(?=[/\\]|$)" (msys-path->windows (user-home)))
        fix-drive-relative)
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
        f (io/file (fix-drive-relative (expand-home raw)))]
    (canonical-file f)))