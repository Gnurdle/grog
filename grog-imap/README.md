# grog-imap

An **MCP server** (over stdio) exposing **IMAP mail box / message tools** so an
ECA-driven agent loop (or any MCP client) can read, search, and manage mail on
one or more IMAP servers (Gmail, iCloud, Exchange/Outlook, Dovecot, …).

Self-contained over plain `java.net.Socket` + `javax.net.ssl.SSLSocketFactory`
for TLS — **no JavaMail / Jakarta Mail dependency**. It speaks the **IMAP4rev1**
(RFC 3501) protocol directly, with IMAP4rev2 (RFC 9051) niceties where cheap.

## Scope: receive/manage only — no SMTP outbound

IMAP talks to the *mailbox store*: list/select folders, search, fetch bodies,
flags, copy/move/expunge, and inject a message into a folder with `APPEND`
(e.g. a Drafts/Outbox folder). **IMAP does not send mail.**

Outbound **sending** is **SMTP** (RFC 5321) — a *separate* server/protocol with
its own envelope, `MAIL FROM`/`RCPT TO`/`DATA`, and delivery semantics. That is
**out of scope for `grog-imap`**. If outbound send is ever wanted it belongs in a
distinct module (e.g. `grog-smtp`), not here. This skill is strictly the IMAP
inbox/manage surface.

## Language / runtime

**JVM Clojure / babashka** — deps.edn, stdlib socket + SSL only. Mirrors the
self-contained-client approach used by `grog-odoo`'s XML-RPC layer.

## Architecture

```
src/grog_imap/
  protocol.clj   <- IMAP protocol client: connection, parsing, ALL protocol commands
  main.clj       <- MCP stdio server exposing the tools to the model (frame/TODO)
```

### `protocol.clj` — the IMAP protocol surface

A slab of the full IMAP protocol, keyed on RFC 3501 / RFC 9051:

- **Connection & lifecycle** — `connect`, `ssl-connect` (or `open-connection`),
  `login`, `logout`, `authenticate`, `starttls`, `capability`, `noop`
- **Mailbox commands** — `select`, `examine`, `create`, `delete`, `rename`,
  `subscribe`, `unsubscribe`, `list`, `lsub`, `namespace`, `status`, `close`,
  `check`, `unselect`
- **Message commands** — `fetch`, `store`, `search`, `append`, `copy`, `move`,
  `uid`, `expunge`, `delete-messages` (store +\deleted), `idle`
- **Parsing** — tagged vs untagged vs continuation, literal `{N}` handling,
  parenthesized response data → idiomatic Clojure data

Each command returns the parsed result of the untagged responses plus the final
tagged status. Flags are represented as keywords (`:seen`, `:answered`,
`:flagged`, `:deleted`, `:draft`, `:recent` + freeform keywords); UIDs as ints;
FETCH items as a keyword-keyed map.

## Status

**Bootstrap in progress.** `protocol.clj` is the first layer: connection +
parse + command plumbing laid down and smoke-tested against local servers.
The MCP tool layer in `main.clj` is the next step (list servers → authenticate →
`imap_search` / `imap_fetch` / `imap_list_mailboxes`…).

## Configuration (planned)

| Env | Meaning |
|-----|---------|
| `GROG_IMAP_CONFIG` | JSON file of pre-configured accounts `[{name, host, port, tls, user, password}]` |
| `GROG_IMAP_ACCOUNT` | account name the model selects for subsequent calls |

Consistent with `grog-odoo`'s strict, pre-configured selection: the model picks
an account *name*, never a host/endpoint.