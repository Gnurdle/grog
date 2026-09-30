(ns grog.server.main
  "Native-image entry point for the backend.

  GraalVM needs a class with a Java `main(String[])`. Clojure's `-main` is just a
  function, so with `:gen-class` AOT emits `grog.server.main` exposing `main`,
  which delegates to `grog.server/-main`. This is the main class the native
  image is built with (scripts/native-image.sh)."
  (:gen-class)
  (:require [grog.server :as server]))

(defn -main [& args]
  (apply server/-main args))
