# grog-imap — reference

IMAP tooling for grog: a dependency-light **library** with a **thin MCP adapter**
over it, so the same functions are usable from application code and from the
model's tools.

## Layering

```
src/grog_imap/
  protocol.clj   L0  low-level IMAP protocol: socket/TLS, framing, literal
                     handling, response parsing, raw commands
                     (SELECT/FETCH/STORE/SEARCH/...); returns the normalized
                     {:tagged ... :untagged [...]} shape.
  core.clj       L1  the public library: account/config model, connection and
                     session registry, high-level operations returning plain
                     Clojure data. No dependency on the MCP SDK.
  main.clj       L2  the MCP stdio server: thin wrappers that parse tool args,
                     call a core function, and JSON-encode the result.
```

**Rule:** `core.clj` is the contract. New behavior belongs in `core.clj`, not
`main.clj`; `protocol.clj` stays low-level, and the library must not depend on
the MCP SDK (it stays reusable in ordinary JVM/Clojure/babashka apps).

## Config and secrets

`GROG_IMAP_CONFIG` (a path or inline JSON) holds account **metadata** only —
`{:name :host :port :tls :user}` — with strict selection by pre-selected account
**name** against an allowlist (never a host the model supplies). Authentication
is lazy.

**The library never acquires secrets itself.** It separates account metadata
from credentials and is always handed a living credential by its caller, so the
application-code and MCP paths are disjoint:

- **Application code** resolves the secret itself (env, Vault, OS keychain) and
  connects in-process:
  `(imap/connect (assoc account :password (app/get-secret ...)))`. The secret
  never leaves the app process and never touches the MCP or the model.
- **The MCP** (`main.clj`) holds account metadata plus a **credential provider**
  wired at process startup — per-account env `GROG_IMAP_PASSWORD_<NAME>` /
  `GROG_IMAP_REFRESH_<NAME>`, or a credential file `~/.grog-imap-<name>` /
  `GROG_IMAP_PASSWORD_FILE_<NAME>`, or a pluggable secret-resolver fn — and
  injects the resolved credential into the same `core` connect call.

**Hard rule:** credentials are never (a) accepted as MCP tool arguments, (b)
returned in tool results, or (c) included in account-listing / selection
metadata. Tool arguments and outputs transit the model context, so secrets must
not travel those paths; `imap_use_account` resolves a *name* against the
allowlist and the password is filled from the provider, never from the model.

## Mutation posture

IMAP is inherently stateful (delete/move/copy/append mutate the mailbox).
**`:read-only` defaults to `true`; mutations are opt-in**, set explicitly with
`:read-only false` on an account. When `:read-only` is true, the
mutation-oriented tools are hidden for, or rejected for, that account.
