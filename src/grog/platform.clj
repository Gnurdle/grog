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
                      (str (System/getProperty "user.home") "/AppData/Roaming/grog")))
                (str/join "/" [(or (some-> (System/getenv "XDG_CONFIG_HOME") str str/trim not-empty)
                                   (str (System/getProperty "user.home") "/.config"))
                               "grog"]))
        f (io/file (str/replace-first raw #"^~(?=/|$)" (str (System/getProperty "user.home"))))]
    (.getCanonicalFile f)))