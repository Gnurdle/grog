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

(defn- host-of
  "The host of an http(s) url, lower-cased, or nil."
  [u]
  (try
    (some-> (java.net.URI. (str u)) .getHost str/lower-case not-empty)
    (catch Throwable _ nil)))

(defn host-matches?
  "True when the catalogue entry `id` — and therefore the url grog would use for
  that provider — shares its HOST with `url`.

  Used to decide whether an explicit `:llm :provider` may label a given
  endpoint: it may, only when they point at the same host, so a stale provider id
  left by an earlier pick can never mislabel a transport the user has since
  switched to. Host-level (not port-level) on purpose — two Strata launches share
  a host and differ only by port, which is exactly the case the explicit id
  exists to disambiguate."
  [id url]
  (let [a (some-> (by-id id) :url host-of)
        b (host-of url)]
    (boolean (and a b (= a b)))))

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

(defn- endpoint-shape
  "`\"<scheme>://<host><path>\"` with any port stripped — for matching a
  configured endpoint to a catalogue entry regardless of which port it runs on."
  [u]
  (try
    (let [x (java.net.URI. (str u))]
      (when (and (.getScheme x) (.getHost x))
        (str (str/lower-case (.getScheme x)) "://" (str/lower-case (.getHost x))
             (or (.getPath x) ""))))
    (catch Throwable _ nil)))

(defn by-url-ignoring-port
  "Like `by-url`, but a configured endpoint matches a catalogue entry even when
  its PORT differs — and ONLY when that match is UNAMBIGUOUS.

  This lets a known-kind local server that is not on its default port still be
  named after that kind, but it refuses to guess: every local catalogue entry
  shares the shape `http://localhost/v1`, so a loopback url on some other port
  (8081, say) matches ALL of them and names none — the port is the only thing
  that distinguishes two local servers, and stripping it destroys the evidence.
  An ambiguous shape therefore returns nil, and the caller falls back to the host
  heuristic (or, better, to an explicitly persisted provider id — see
  `grog.config/llm-provider`)."
  [url]
  (let [shape (endpoint-shape url)]
    (when shape
      (let [matches (->> (entries)
                         (keep (fn [e] (let [es (endpoint-shape (:url e))]
                                         (when (and es (str/starts-with? shape es))
                                           [es e]))))
                         (sort-by (comp count first))
                         reverse
                         (partition-by first)
                         first
                         (map second))]
        (when (= 1 (count matches)) (first matches))))))

(defn id-for-endpoint
  "The catalogue `:id` for a configured base, matching with OR without a port,
  or nil. Falls back to the exact-port `id-for-url` first so an exact entry
  always wins."
  [url]
  (or (id-for-url url)
      (:id (by-url-ignoring-port url))))

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

;; --- ports -----------------------------------------------------------------
;;
;; Several LOCAL providers are servers you run yourself, more than one at a time
;; and on whatever port you choose: two Strata launches (8080, 8081), a vLLM on
;; 8000, an ollama on 11434. The catalogue can only name ONE default base for
;; each, so "pick Strata" alone cannot say WHICH Strata. These helpers let a pick
;; carry a port and let a configured endpoint be labelled with it, so two servers
;; of the same kind are tellable apart in the picker and the status line.

(defn loopback-url?
  "True when `url` points at THIS machine — loopback only.

  A private-range address is deliberately NOT loopback: a model server on the LAN
  is somewhere else, and treating it as \"this machine\" would make the Local tab
  list ollama's models instead of the ones that server actually serves."
  [url]
  (let [u (str/lower-case (str url))]
    (boolean (or (str/includes? u "localhost")
                 (str/includes? u "127.0.0.1")
                 (str/includes? u "[::1]")
                 (str/includes? u "0.0.0.0")))))

(defn port-of-url
  "The explicit port in an http(s) `url`, as an integer, or nil when the url
  carries none (or cannot be parsed)."
  [url]
  (try
    (let [u (java.net.URI. (str url))
          p (.getPort u)]
      (when (and (pos? p) (<= p 65535)) p))
    (catch Throwable _ nil)))

(defn- parse-port
  "An integer port from an int or numeric string, in range; nil otherwise."
  [port]
  (let [n (cond
            (integer? port) port
            (number? port)  (long port)
            :else           (try (Integer/parseInt (str/trim (str port)))
                                 (catch Throwable _ nil)))]
    (when (and n (<= 1 (long n) 65535)) (long n))))

(defn url-with-port
  "`url` with its port replaced by `port` (an int or numeric string), keeping
  scheme, host and path. A nil/blank/invalid `port` returns `url` unchanged.

  This is how a local provider pick carries the port the user chose: the entry
  supplies the base (host + path), the user supplies the port, and the result is
  the exact base grog will append `/models` and `/chat/completions` to."
  [url port]
  (let [base (some-> url str str/trim not-empty)
        p (parse-port port)]
    (if (or (nil? base) (nil? p))
      base
      (try
        (let [u (java.net.URI. base)
              scheme (.getScheme u)
              host (.getHost u)]
          (if (and scheme host)
            (str scheme "://" host ":" p
                 (or (.getRawPath u) "")
                 (when-let [q (.getRawQuery u)] (str "?" q))
                 (when-let [f (.getRawFragment u)] (str "#" f)))
            base))
        (catch Throwable _ base)))))

(defn port-suffix
  "A `\":<port>\"` suffix for `url` when it is on a port that is NOT the catalogue
  default for the provider that base names — otherwise an empty string.

  So `http://localhost:8081/v1` (a second Strata launch) labels as `:8081` while
  the ordinary `http://localhost:8080/v1` labels as nothing, and a url on some
  other host entirely is left alone. Used to tell two same-kind local servers
  apart in a status line or a picker row."
  [url]
  (let [p (port-of-url url)
        entry (when url (by-url url))
        default-p (some-> (:url entry) port-of-url)]
    (if (and p (not= p default-p))
      (str ":" p)
      "")))
