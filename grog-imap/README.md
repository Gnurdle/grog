# grog-imap

IMAP inbox/manage for both **application code** and an **MCP server** (over
stdio) so an ECA-driven agent loop (or any MCP client) can read, search, and
manage mail on one or more IMAP servers (Gmail, iCloud, Exchange/Outlook,
Dovecot, …).

Self-contained over plain `java.net.Socket` + `javax.net.ssl.SSLSocketFactory`
for TLS — **no JavaMail / Jakarta Mail dependency**. It speaks **IMAP4rev1**
(RFC 3501) directly, with IMAP4rev2 (RFC 9051) niceties where cheap. OAuth2 for
Gmail/Microsoft uses the JDK's `java.net.http` — still zero extra dependencies.

## Scope: receive/manage only — no SMTP outbound

IMAP talks to the *mailbox store*: list/select folders, search, fetch bodies,
flags, copy/move/expunge, and inject a message with `APPEND`. **IMAP does not
send mail.** Outbound sending is **SMTP** (RFC 5321) — a separate protocol, out
of scope for `grog-imap` (belongs in a distinct `grog-smtp` module).

## Architecture — one library, two consumers

Three layers, strictly separated. The MCP server is a *thin adapter* over the
same public library that app code uses — there is no second implementation of
IMAP logic.

```
src/grog_imap/
  protocol.clj   L0  low-level IMAP protocol (connection, TLS, framing,
                     literals, response parsing, raw commands).
                     Returns normalized {:tagged ... :untagged [...]} results.
  core.clj       L1  PUBLIC LIBRARY — what app code AND the MCP both call.
                     Account/config model, session registry, high-level ops
                     returning plain Clojure data. NO MCP SDK dependency.
  oauth.clj          OAuth2 token support (Google/Microsoft) for XOAUTH2.
  main.clj       L2  MCP stdio server: each tool parses args -> calls a
                     core.clj fn -> JSON-encodes the result. No logic of its own.
```

### Dual use

**From application code** (no MCP on the classpath):

```clojure
(require '[grog-imap.core :as imap])

(def config (imap/load-config))                 ; GROG_IMAP_CONFIG (metadata only)
(imap/connect-account! config "gmail" {:password "..."})   ; or a credential fn
(let [conn (imap/connection "gmail")]
  (imap/list-mailboxes conn)
  (imap/search conn :unseen)
  (imap/fetch conn "1:10" :uid :flags :rfc822.size))
```

**From the MCP**: `clojure -M:mcp` (or `java -cp grog-imap.jar clojure.main -m
grog-imap.main`) exposes `imap_list_accounts`, `imap_use_account`,
`imap_authenticate`, `imap_list_mailboxes`, `imap_count`, `imap_search`,
`imap_fetch`, and — for writable accounts — `imap_set_flags`, `imap_delete`,
`imap_move`, `imap_move_sender`, `imap_copy`, `imap_append`. Each tool calls the
exact same `core.clj` function app code calls.

## Configuration

`GROG_IMAP_CONFIG` is an **EDN** file path (or inline EDN/JSON) of account
*metadata* — **never credentials**:

```edn
{:accounts [
  {:name "gmail" :host "imap.gmail.com" :port 993 :tls true
   :user "you@gmail.com"
   :password-secret "IMAP_GMAIL_PASSWORD"}
  {:name "work"  :host "mail.example.com" :port 993 :tls true
   :user "you@example.com" :sasl "xoauth2"
   :oauth {:provider "google" :client-id "..."}
   :refresh-secret "IMAP_WORK_REFRESH"
   :read-only true}
]}
```

- `:read-only` defaults to **true** (safe by default). Set `false` to allow
  mutations; the MCP hides/rejects mutation tools for read-only accounts.
- `:sasl` — `login` (default), `plain`, or `xoauth2`.

### Credentials

The library never acquires or stores secrets. The caller injects them:

| Path | How |
|------|-----|
| App code | pass a credential map (`{:password ...}` or `{:refresh-token ...}`) or a `[account] -> credential` fn to `connect-account!` |
| MCP | each account **NAMES** the store account holding its credential — `:password-secret` (LOGIN/PLAIN), `:refresh-secret` (XOAUTH2) — and the server reads the value from **grog's secret store**: the OS keyring (service `grog`), with `<config-home>/secrets.edn` as the headless fallback. Set one with `/secret set <NAME> <value>` |

There is no env-var credential and no `~/.grog-imap-<name>` file: several mail
accounts can be configured, so each carries the *name* of its store entry rather
than a value.

Secrets **never** appear in tool arguments, tool results, or account-listing
metadata. `imap_list_accounts` / `imap_use_account` return names only.

### OAuth2 (Gmail / Microsoft 365)

- **Gmail**: a Google "Desktop app" OAuth client → run
  `clojure -M -m grog-imap.oauth-authorize <client-id> IMAP_GMAIL_REFRESH` once →
  the refresh token is written straight into the OS keyring under that account
  name (never a file, never printed) → point the account at it with
  `:refresh-secret "IMAP_GMAIL_REFRESH"`. An app password via `:password-secret`
  also works for Gmail.
- **Microsoft 365**: requires OAuth2 (basic auth is retired) → register an app
  with `IMAP.AccessAsUser.All`, `authorize!` once, store the refresh token.

See `docs/OAuth2.md` for the full setup.

## Language / runtime

JVM Clojure — deps.edn; stdlib sockets/SSL + `java.net.http` only. Mirrors the
self-contained-client approach of `grog-odoo`'s XML-RPC layer.
