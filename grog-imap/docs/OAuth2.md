# OAuth2 setup for Gmail / Microsoft 365 IMAP

The library has everything needed for XOAUTH2 (SASL wire + `grog-imap.oauth`
token handling). The only remaining step is a **one-time registration + consent**
per provider, done by you (secrets never transit the model/MCP context).

## 1. Register an OAuth client (once per provider)

**Google (Gmail IMAP)**
- Google Cloud Console → APIs & Services → Credentials → Create credentials →
  **OAuth client ID** → Application type: **Desktop app**.
- Add the account you'll test as a test user on the OAuth consent screen
  (or publish the app if you want the refresh token to last past 7 days).
- Note the client ID. Desktop apps are public clients — no secret needed.

**Microsoft (Outlook/365 IMAP)**
- Azure portal → App registrations → New registration → redirect URI
  `http://localhost` (public client / mobile & desktop).
- API permissions → Add delegated permission →
  **Office 365 Exchange Online** → `IMAP.AccessAsUser.All`.
- Note the client ID (public client: no secret needed; if you enable a client
  secret, pass it too).

## 2. Get a refresh token (one-time, per account)

Run the interactive consent from the REPL:

```clojure
(require '[grog-imap.oauth :as oauth])

(oauth/authorize! {:provider :google
                   :client-id "YOUR_CLIENT_ID"})
;; prints a URL -> open it, sign in, authorize
;; returns {:access_token "..." :refresh_token "..." :expires_in 3600}
```

For Microsoft, the same with `:provider :microsoft` (scope defaults to
`https://outlook.office.com/IMAP.AccessAsUser.All offline_access`). Store the
`refresh_token` as a secret (env var or secrets file) — never in config
metadata or the MCP context.

## 3. Connect with XOAUTH2

Account metadata (no secrets):

```clojure
{:name "gmail"
 :host "imap.gmail.com" :port 993 :tls true
 :user "you@gmail.com"
 :sasl :xoauth2
 :oauth {:provider :google :client-id "YOUR_CLIENT_ID"}}
```

Credential (injected by your app / credential provider):

```clojure
;; simplest: a pre-fetched access token
{:access-token "ya29...."}

;; or: let the library refresh from a refresh token
{:refresh-token "1//...."}           ; uses :oauth from account metadata
```

Then everything else is the normal API:

```clojure
(require '[grog-imap.core :as imap])
(imap/connect-account! config "gmail" {:refresh-token "1//...."})
(let [conn (imap/connection "gmail")]
  (imap/list-mailboxes conn)
  (imap/search conn :unseen))
```

Access tokens are cached in memory and auto-refreshed before they expire.
