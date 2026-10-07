(ns grog.providers
  "grog's catalogue of well-known OpenAI-compatible endpoints.

  The DATA lives in `resources/providers.edn` on the classpath, so it ships
  inside the spine jar and is available identically from a source tree. Nothing
  here is provider logic — this namespace is a thin, cached reader plus a little
  matching, so the settings picker (over an RPC), the config example, and the
  URL→provider naming in `grog.models` all agree on ONE list.

  Degrades gracefully: a build with no embedded resources (a bare native image)
  simply sees an empty catalogue rather than throwing."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private resource-name
  "The classpath resource holding the catalogue."
  "providers.edn")

(defonce ^:private catalog* (atom nil))

(def ^:private empty-catalog {:version 0 :providers []})

(defn- read-resource []
  (try
    (when-let [r (io/resource resource-name)]
      (let [c (edn/read-string (slurp r :encoding "UTF-8"))]
        (when (map? c) c)))
    (catch Throwable _ nil)))

(defn catalog
  "The parsed catalogue `{:version n :providers [ … ]}`.

  Reads the classpath resource once and caches it; returns an empty catalogue
  when the resource is missing or unreadable (never throws)."
  []
  (or @catalog*
      (reset! catalog* (or (read-resource) empty-catalog))))

(defn entries
  "Every provider entry, in file order."
  []
  (:providers (catalog)))

(defn remote-entries
  "Provider entries that need an API key (`:kind :remote`)."
  []
  (filterv #(= :remote (:kind %)) (entries)))

(defn local-entries
  "Provider entries served on this machine (`:kind :local`)."
  []
  (filterv #(= :local (:kind %)) (entries)))

(defn by-id
  "The entry with this `:id`, or nil."
  [id]
  (let [id (some-> id name str/trim str/lower-case not-empty)]
    (when id (first (filter #(= id (:id %)) (entries))))))

(defn- norm-url
  "Lower-case, trim, drop trailing slashes — for tolerant comparison only."
  [u]
  (some-> (str u) str/trim (str/replace #"/+$" "") str/lower-case not-empty))

(defn by-url
  "The catalogue entry whose `:url` is the LONGEST prefix of `url`, or nil.

  Longest-prefix so that a more specific entry wins over a coarser one. Used to
  name a provider from the configured `:llm :url` — `integrate.api.nvidia.com`
  and `api.z.ai` derive badly by host, but match their catalogue id exactly."
  [url]
  (let [u (norm-url url)]
    (when u
      (->> (entries)
           (keep (fn [e] (let [eu (norm-url (:url e))]
                           (when (and eu (str/starts-with? u eu)) [eu e]))))
           (sort-by (comp count first))
           last
           second))))

(defn id-for-url
  "The catalogue `:id` for a configured base URL, or nil when unknown."
  [url]
  (:id (by-url url)))

(defn profile-map
  "`{<id> {:url … :model …}}` for every entry that carries a `:sample` — the
  shape the example config uses under `:llm :profiles`. Local servers get
  `:api-key nil` so they never ask the keyring for a key."
  []
  (into {}
        (keep (fn [{:keys [id url sample kind]}]
                (when (and id url sample)
                  [(keyword id) (cond-> {:url url :model sample}
                                  (= :local kind) (assoc :api-key nil))])))
        (entries)))
