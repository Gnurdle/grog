# grog's own agent loop (spike)

What ECA does for grog today, done in grog: **transport** (one OpenAI-compatible
streaming call), **loop** (messages → completion → tool calls → results), **MCP
client** (spawn grog's tool server, list tools, call them), and **the coding
tools** the loop hands the model.

Built 2026-10-14 as a spike, to replace estimates with facts. It is *not* wired
into the client yet, and it is not a drop-in replacement for ECA — see **Not
built** below, which is the part worth reading.

## Run it

```sh
# against grog's configured model (key comes from grog's own secret lookup)
clojure -M -m grog.agent.main --prompt "…" --cwd /d/gni/grog

# no key, no cost, local:
clojure -M -m grog.agent.main --url http://localhost:11434/v1 --model qwen3:8b \
  --prompt "list src/grog/agent and read the longest file" --cwd /d/gni/grog

# local tools only, no MCP process:
clojure -M -m grog.agent.main --prompt "…" --no-mcp
```

Flags: `--prompt` (required) · `--url` `--model` `--key` · `--cwd` ·
`--max-steps` · `--no-mcp` · `--quiet` · `--system FILE`.

## Files

| File | What |
|---|---|
| `transport.clj` | one streaming chat call: SSE parsing, tool-call deltas accumulated by index, reasoning passthrough, usage. `:post` is injectable → a canned SSE stream can drive the whole loop with no network (the eval fixture seam). |
| `mcp.clj` | MCP over stdio: spawn (using grog's OWN spec from `grog.eca-config/grog-mcp-servers`, so the jar/source choice and every per-server env stay in one place), handshake, `tools/list` (paged), `tools/call`. Refuses a string `inputSchema` by name. |
| `tools.clj` | `read_file` `write_file` `edit_file` `list_dir` `grep` `shell`. Schemas out, `{:text :error?}` back, explicit truncation markers. |
| `loop.clj` | the turn loop; emits grog's own segment kinds (`:answer` `:thinking` `:tool` `:usage`) so the client wire protocol is a translation, not a redesign. |
| `main.clj` | the CLI. |

## The one design decision to keep

**`edit_file` verifies itself.** It re-reads the file after writing and reports
`verified: true` with a diff, or a hard failure carrying what is actually there.
An edit tool that says "Successfully replaced content" and changes nothing is a
failure this project already paid for
(`mistake.2026-10-14T11:41:11Z.eca-edit-file-silent-noop`), and the fix is not
discipline — it is making the lie inexpressible. A write that does not land is
*catchable*: `write_file` against `/dev/null` reports the mismatch instead of a
byte count.

## Not built (the honest list)

- **Client integration.** Nothing here speaks the spine's wire protocol yet, so
  the desktop app cannot use it. The segment vocabulary is deliberately shared.
- **Approvals / questions / trust.** Today grog has an approval dialog and a
  question tool; this loop has none (`:toolCall :approval` in ECA is *config* —
  a grog-owned loop has to implement the policy and the UI, not just the hook).
- **Per-session state, cancel from the UI, the transcript store.** `run-turn!`
  takes a `:cancel` atom; nothing sets it yet.
- **Subagents.** ECA has an agent registry with `:mode "subagent"`; this has
  nothing. Spawning an isolated child loop is L3 work that only starts to make
  sense once the parent loop is real.
- **Budget and compaction.** No absolute token budget, no compaction, no
  cumulative tool-result accounting — the per-result clip is all there is. This
  is one of the *reasons* to own the loop, so it is the next thing to build, not
  a gap to apologise for.
- **Multi-provider quirks.** One provider family (OpenAI-compatible) is
  implemented. The ~25 providers grog's `providers.edn` lists are mostly that
  shape; the exceptions (reasoning dialects, auth flows, tool-call formats) are
  where ECA earns its keep today and where bugs would live.
- **Images as model input.** MCP can return image blocks; the loop counts them
  and says so, but does not feed them to the model yet.
- **Evals.** The loop is not yet covered by `scripts/`-style cases; the fixtures
  seam exists (`:post`).

## What it proved (2026-10-14, measured)

| Run | What | Result |
|---|---|---|
| MCP client alone (`scripts/agent-loop-test.clj`) | handshake, `tools/list`, one store round-trip against grog's real server | **59 tools**, every `inputSchema` a JSON object; `assoc_store` → `assoc_get` returned the value |
| live, local model (ollama `qwen3.5:4b`), local tools only | write a file, read it back | `:done` in **3 steps / 8.7 s**; the write reported *verified by reading it back* |
| live, grog's configured model, **59 MCP tools + 6 local** | write + verify a file, store + fetch a memory key | `:done` in **3 steps / 7.9 s / $0.0052**; the model issued two tool calls in parallel |

Prompt tokens are the number to note: with 65 tool schemas attached, **8 611 of the
8 611-token prompt was schema and conversation** (8 384 cached). That is the
measurable case for lazy tool loading — ECA's `:mcpToolSearch`, or a routing layer
here — i.e. the Q2 argument with a number attached.

Verified afterwards, from outside the loop: the file on disk contains what the loop
claimed, and the memory key reads back through a **different** MCP client (the
agent's own toolbelt) — a round trip across two clients, not a self-report.

Four bugs were found by *running* it, none by compilation: JSON string keys vs
keyword destructuring twice (tool arguments, then the MCP spec), a missing paren,
and a `dispatch!` branch that returned nil for every local tool whenever an MCP
connection was attached. The last is the instructive one — the model noticed the
blank results, said so, and **refused to claim the write had succeeded**, which is
exactly what honest tools are supposed to buy.
