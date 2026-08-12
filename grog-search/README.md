# grog-search

MCP server (over stdio) exposing **Brave Web Search** as `brave_web_search` so an
ECA-driven agent loop can search the public web.

This is the *"Keep → grog MCP"* path for grog's old `brave_web_search` tool (see
`doc/gap-analysis-grog-vs-eca.md` §6.5). After the GUI was rewired onto ECA, the
model loop only sees MCP servers — the old grog-own tool loop that dispatched
`brave_web_search` was superseded, so the tool had to be re-exposed here.

## API key

The subscription token is read from the **OS keyring** (service `grog`, account
`BRAVE_SEARCH_API`) — exactly like the original grog tool. Set it from a grog
chat with:

```
/secret BRAVE_SEARCH_API <token>
```

or via the keyring directly (`secret-tool store --label grog service grog
account BRAVE_SEARCH_API`, pass, `security` on macOS, …).

When the key is missing or the keyring is unreachable, the tool returns a
helpful setup message instead of failing the server.

## Tool

`brave_web_search` — query the Brave Search API:

| param   | type    | required | notes                          |
|---------|---------|----------|--------------------------------|
| `query` | string  | yes      | concise search query           |
| `count` | integer | no       | 1–10, default 5                |

Results come back as numbered hits with title, URL, and description.

## Run

```sh
clojure -M:mcp -m grog-search.main
```

Needs: the Clojure CLI, an API key in the keyring, and Java 11+.

## ECA config

grog's generated ECA config (via `grog.eca-config/grog-mcp-servers`) registers
this server alongside the other grog servers:

```json
"grog-search": {
  "command": "bash",
  "args": ["-lc", "cd '…/grog-search' && clojure -M:mcp -m grog-search.main"]
}
```