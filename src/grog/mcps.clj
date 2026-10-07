(ns grog.mcps
  "The MCP servers grog ships, as data — for the onboarding page ('what is
  available, and what might you want to set up?') and anything else that wants
  the same list.

  Two deliberate choices:

    * CURATED, not introspected. The ids match grog_mcp's registry
      (`grog_mcp.main/servers`) and the tool names ECA sees (`grog-odoo__…`), but
      the one-liners are written for a human deciding what to switch on — a tool
      schema cannot tell you that.

    * readiness is checked with CHEAP, FILE-BASED predicates only. This is read
      on the startup path, and the secret store can block for seconds without a
      working keyring, so there are NO secret-store probes here. A nil
      `:configured?` means \"unknown — it wants a secret; see :needs\".

  The checks are resolved lazily (`requiring-resolve`) so this ns stays light and
  cannot take part in a require cycle."
  (:require [clojure.string :as str]))

(def ^:private entries
  [{:id "grog-babashka" :label "babashka"
    :what "Run short Clojure/Babashka scripts as a pure data transform."
    :needs nil}

   {:id "grog-fetch" :label "fetch"
    :what "Read a URL as text — articles, docs, changelogs — or pull an RSS/Atom feed."
    :needs nil}

   {:id "grog-rss" :label "rss"
    :what "List the recent entries of a feed (blogs, release notes, calendar exports)."
    :needs nil}

   {:id "grog-memory" :label "memory"
    :what "Remember facts across sessions — grog's associative store, per project."
    :needs nil}

   {:id "grog-project-search" :label "project search"
    :what "Search across your own grog projects and the notes you keep in them."
    :needs nil}

   {:id "grog-office" :label "office"
    :what "Read and edit Office documents — docx, xlsx, pptx — in-process."
    :needs "LibreOffice (soffice) for conversions and rendering"}

   {:id "grog-imaging" :label "imaging"
    :what "OCR and image geometry, so scanned documents and drawings can be read."
    :needs "the tesseract CLI for OCR"}

   {:id "grog-search" :label "web search"
    :what "Search the public web (Brave)."
    :needs "/secret set BRAVE_SEARCH_API <value>"}

   {:id "grog-odoo" :label "odoo"
    :what "Query your Odoo ERP through its own API — no direct database access."
    :needs "an Odoo instance, plus /secret set ODOO_<INSTANCE>_PASSWORD <value> (named per instance by :password-secret)"
    :check 'grog.eca-config/odoo-configured?}

   {:id "grog-imap" :label "email"
    :what "Read mailboxes over IMAP."
    :needs "IMAP account details, plus /secret set IMAP_<ACCOUNT>_PASSWORD <value> (named per account by :password-secret)"
    :check 'grog.eca-config/imap-configured?}

   {:id "grog-gitlab" :label "gitlab"
    :what "Work with GitLab projects, issues and merge requests."
    :needs "a GitLab URL, plus /secret set GITLAB_TOKEN <value> (or a per-instance :token-secret)"
    :check 'grog.eca-config/gitlab-configured?}

   {:id "grog-alpaca" :label "alpaca"
    :what "Market data and quotes — and trading, if you ever allow it."
    :needs "/secret set ALPACA_API_KEY <value> and /secret set ALPACA_SECRET_KEY <value>"}])

(defn- ready?
  "Run a file-based readiness check by symbol, or nil when there is none.
  Never throws — a broken check must not break the onboarding page."
  [check]
  (when check
    (try (boolean ((requiring-resolve check)))
         (catch Throwable _ nil))))

(defn available
  "The shipped MCP servers as `[{:id :label :what :needs :configured?}]`.

  `:configured?` is true/false when a cheap file-based check can say, and nil
  when it would need a secret-store probe (or when the server needs nothing at
  all — those carry no `:check` and always come back nil, i.e. \"nothing to set
  up\")."
  []
  (mapv (fn [e]
          (cond-> (dissoc e :check)
            (:check e) (assoc :configured? (ready? (:check e)))))
        entries))

(defn needs-setup
  "The subset that is NOT known to be ready — i.e. the ones worth mentioning as
  'might want to set up'. Servers needing nothing are excluded."
  []
  (->> (available)
       (remove #(nil? (:needs %)))
       (remove #(true? (:configured?)))
       vec))
