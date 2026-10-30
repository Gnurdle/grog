# Improvements to grog's own agent loop

**Where this comes from.** On 2026-10-19 grog's loop was moved in-house — the turn
loop, the streaming transport, the MCP client, the toolbelt and the approval gate
stopped being ECA's code (`src/grog/agent/`, `src/grog/client/loop.clj`). This file
is the follow-on: what to do *next*, mined from the leaked Claude Code harness and
the community analysis of it, checked against what this loop actually is.

**How to read it.** Every item says what it is, **why it applies HERE** (with a
number, a mistake, or a line of grog's own code as the evidence), and what it
costs. Items are ordered by (evidence × cheapness), not by ambition — #1 is done.
Nothing here is a wish: an item that cannot name the grog failure it prevents was
left out (several were, and the sources list says what they were).

**Status key:** ✅ done · 🔜 next · ⬜ open · 🅿️ parked (deliberate, with a reason)

| # | Improvement | Source pattern | Status |
|---|---|---|---|
| 1 | Deferred / lazy tool loading (`tool_search`) | `ToolSearch` / `defer_loading` | ✅ 2026-10-19 |
| 2 | Compaction with a circuit breaker + origin tagging | `MAX_CONSECUTIVE_*`, microcompact | 🔜 |
| 3 | The loop as an explicit state machine, resumable | `queryLoop` async generator | ⬜ |
| 4 | Per-tool risk: `isReadOnly` / `isDestructive` declared by the tool | `Tool.ts` defaults | ⬜ |
| 5 | Denial tracking — degrade when the user stops trusting | `denialTracking.ts` (46 lines) | ⬜ |
| 6 | Tool results offloaded to disk, preview in context | microcompact / `apiMicrocompact` | ⬜ |
| 7 | Prompt-cache discipline: stable prefix, snapshot after it | `promptCacheBreakDetection` | ⬜ |
| 8 | A verification pass before claiming complex work is done | `verificationAgent.ts` | ⬜ |
| 9 | Plan / act separation (read-only mode) — needs #4 | plan mode + `isReadOnly` | ⬜ |
| 10 | Subagents: Explore (read-only) + isolated context | agent registry | 🅿️ partly built |
| 11 | Background memory consolidation (`/dream`) | `autoDream.ts` | 🅿️ |
| 12 | Hooks (PreToolUse / PostToolUse / Stop) | hook system | ⬜ |
| 13 | Self-verifying `edit_file` (already grog's, mine the general rule) | — | ✅ exists |
| 14 | `edit_file`'s own verification was decided by an anchor COUNT | — | ✅ 2026-10-23 |
| 15 | Explicit stop conditions in the task, not just a step cap | stop conditions | 🅿️ (b) done |
| 16 | Conditional autonomy (asks when a human is present, runs when not) | cooperative/headless modes | ⬜ |
| 17 | A stalled stream: retry before data, diagnose after it | retry/backoff on transient transport failure | ✅ 2026-10-22 |
| 18 | One model + recents (the big/small slots and relay are gone) | — (grog's own) | ✅ 2026-10-26 |
| 19 | Context overflow: derive the budget per model, and DISTILL + RETRY instead of dying | — (grog's own) | ✅ 2026-10-28 |
| 20 | Model selection as ONE dialog, with a NAME as the unit | — (grog's own) | ✅ 2026-10-30 |

---

## ✅ #1 — Deferred / lazy tool loading

**Done 2026-10-19.** `src/grog/agent/tool_search.clj` + the lazy path in
`grog.agent.loop`, wired through `grog.client.loop` and `grog.config`
(`:llm :tool-search-threshold`).

**The number it was argued from.** This loop's own README recorded a real turn
with 65 tool schemas attached where **8,611 of 8,611 prompt tokens were schema +
conversation**. Every step of every turn paid it; the model typically calls two
tools.

**What it does.** Above `:tool-threshold` (default 20) tools, the MCP long tail is
**deferred**: the model sees the local/session tools, `tool_search`, and whatever a
previous turn already loaded. `tool_search` ranks deferred tools by name and
description, attaches the matches for the next step, and the loaded set is carried
turn-to-turn by the session so a conversation does not re-search forever.

**Evidence.** `clojure -M scripts/tool-search-test.clj` — 13 checks, all pass:
ranking is deterministic and name-beats-description; a fixture-transport turn
searches, then calls the tool it attached; a 60-tool belt sends **10** schemas on a
default turn (would be 60+ without it).

**Known limitation, asserted in the test on purpose:** the scorer is *lexical*.
"read a spreadsheet" does not find `read_office_document`, because "spreadsheet"
appears in neither its name nor its description. **Cheap fix: enrich the tool
descriptions** (they are grog's own strings in the MCP servers). Expensive fix:
embeddings — a second thing to be wrong. Prefer the first.

**Also true:** `tool_search` attaches on the step *after* the search, so a turn
that needs a tool costs one extra round trip. Below 20 tools the loop is not lazy
at all, because there the round trip costs more than the schemas.

---

## 🔜 #2 — Compaction: a circuit breaker, and origin tagging

**Source.** `MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES = 3`, added after an internal
query (BigQuery, dated in a source comment) found 1,279 sessions with 50+
consecutive compaction failures, up to 3,272 in one session — **~250K wasted API
calls/day**. Plus `microcompact` for tool outputs and — the security finding —
compaction prompts that do **not** distinguish *instructions the user typed* from
*instructions found inside a file the agent read*, so injected text survives
summarisation.

**Why it applies here.** `grog.agent.loop/compact-messages` elides the middle when
the estimate exceeds the budget. It has **no failure counter and no retry loop**
today, and it is purely positional — it keeps the system message, the first user
message and the last 12, and drops everything else with a marker. Two honest gaps:
(1) if elision ever fails to bring the estimate under budget, the next call either
400s or loops with no breaker; (2) it makes no distinction between a user message
and a tool result, so an instruction planted in a file the agent `read_file`d is
kept exactly as faithfully as a user instruction. grog reads untrusted text all
day (file contents, MCP results, web pages).

**Do.** (a) Count consecutive compactions that did not reduce the estimate and stop
retrying after 3, surfacing it instead — the three-line fix, one floor. (b) Keep a
`:role`-aware marker: elided tool results are labelled as *tool output, possibly
untrusted* so the model has the origin it needs to discount injected instructions.
(c) Log `estimate before → after` per compaction so "compaction ran 12 times this
turn" is visible rather than inferred.

---

## ⬜ #3 — The loop as an explicit state machine, resumable

**Source.** `query.ts` is 1,729 lines: an async generator (`queryLoop`) wrapping
`while(true)` with an internal comment naming **"7 continue sites"** — seven points
where control yields and the loop decides. The analysis is blunt about why it
matters: "pause a session, resume it later, serialize state, handle errors
mid-turn, or compose multiple agents" all fall out of the generator shape, and none
fall out of `while { call; act }`.

**Why it applies here.** `grog.agent.loop/run-turn!` is a `loop/recur` with ~4
exits (`:done`, `:max-steps`, `:cancelled`, throw). State is three atoms
(`!msgs`, `!log`, `!usage`) closed over — **not serializable**. A turn interrupted
by a crash, a restart or a closed tab is gone; `grog.client.loop` keeps
`!messages` in memory only. grog's whole SOUL is persistence across restarts, and
the loop is the one component that does not persist.

**Do.** Make the step a data transition (`{:state :messages :loaded-tools …} →
{:state' :effects}`) with an explicit `:continue-site`, so a turn can be written to
disk mid-flight and resumed. This is L-sized and it is the enabler for #8 and #10,
not an end in itself.

---

## ⬜ #4 — Tools declare their own risk

**Source.** `Tool.ts`: `isReadOnly` defaults **false** and `isDestructive` defaults
**false**, and tools cannot bypass via defaults — the permission system reads the
declaration, not a heuristic.

**Why it applies here.** `grog.agent.loop` hard-codes a set — `mutating-tools =
#{"write_file" "edit_file" "move_file" "shell" "git"}` — and everything else is
assumed safe. That set is already wrong in both directions: `shell` can read or
destroy at will (so it is over-gated for `ls`), and `spawn_agent` can mutate the
world through a child loop while being **absent** from the set (under-gated — see
the comment in `grog.client.loop/spawn-agent!` admitting the child must not be a
way around the gate). A property declared on the tool travels with the tool; a
set in the loop does not.

**Do.** Add `:read-only?` / `:destructive?` to each tool schema; derive the gate
from it; default both to the SAFE value (false read-only, true destructive) so a
new tool is restrictive until it says otherwise.

---

## ⬜ #5 — Denial tracking: degrade when the user stops trusting

**Source.** `denialTracking.ts`, 46 lines: **3 consecutive denials, or 20 in a
session**, and auto-mode falls back to asking on everything. Quoted as *"you're
uncomfortable with what I'm doing, so I'll check before each step"* — the graceful
degradation, not a hard stop and not a retry loop.

**Why it applies here.** grog has an approval dialog and a trust/YOLO mode, and
nothing measures repeated refusal. A user who rejects five tool calls in a row is
telling the loop something, and the loop currently keeps proposing at the same
tempo. It is ~30 lines to count denials and emit one visible line
("3 rejections in a row — switching to ask-each-step").

**Do.** Count rejections; at 3-in-a-row set a session flag that turns the
assumption to "ask", and say so in one line the user can see.

---

## ⬜ #6 — Tool results offloaded to disk, preview in context

**Source.** `microCompact`: a 5,000-line read is written to disk and the model sees
a summary plus a reference. Called "a big deal" — one uncompressed result can eat
half the window.

**Why it applies here.** `tools/clip` caps a result at 800 lines / 24,000 chars —
better than nothing, but the cap is **destructive**: the bytes past the cap are
gone, so a model that needed line 900 must re-read (or re-run) to get it. grog
already has a scratch convention (`/tmp/grog-evals/…`, the MCP write path).

**Do.** Past the clip, write the FULL result to a scratch file and put
`… truncated at 800 lines; full output: /tmp/grog-tool-<id>.txt` in the context.
Then the model can `read_file` the tail instead of re-running the command.

---

## ⬜ #7 — Prompt-cache discipline

**Source.** `promptCacheBreakDetection.ts` tracks **14** cache-break vectors with
"sticky latches" (once broken, not restored); the system prompt is split at
`SYSTEM_PROMPT_DYNAMIC_BOUNDARY` so the global prefix caches across *all* users;
tool schemas are assembled once at session start and held stable.

**Why it applies here.** grog's prompt puts `load-context` output (project notes,
which change) and the git snapshot in the **same region** as the stable
instructions, and `grog.eca-config/project-rules-file` re-embeds project context
per session. Any note edit therefore invalidates the cached prefix. Measured
already: of 8,611 prompt tokens **8,384 were cached** — the mechanism works here,
which is exactly why polluting the prefix is expensive.

**Do.** Order the system prompt stable-first / volatile-last with an explicit
boundary, and snapshot the volatile half at session start rather than re-reading it
per turn. (Related and already done on 2026-10-14: per-session models.)

---

## ⬜ #8 — Verification before claiming done

**Source.** `verificationAgent.ts` ships a list of the rationalisations to
resist, and it is unusually specific: *"The code looks correct based on my reading"
— reading is not verification. Run it.* / *"probably is not verified. Run it."*
Required before reporting completion of non-trivial work (3+ file edits).

**Why it applies here.** This is grog's existing virtue stated as a rule — the
`tools.clj` docstring on `edit_file` ("Success is not a claim, it is a
measurement") and the `evals/` `:claim-matches-state` check are the same idea.
What is missing is the *loop-level* requirement: nothing obliges a turn that
edited 3 files to run anything before answering. grog already pays for the
absence — `mistake.2026-10-14T11:41:11Z.eca-edit-file-silent-noop`.

**Do.** Track mutations per turn; past a threshold, the loop's answer is not
accepted as final until a tool *result* (not prose) confirms the effect. The
subagent form (#10) is the strong version; the cheap version is a check in the
loop.

---

## ⬜ #9 — Plan / act separation

**Source.** Two modes; in plan mode **all tools are locked read-only** before
exploration, and execution starts only after the user approves the plan. The
analysis: "planning makes errors cheap, execution does not."

**Why it applies here.** grog has no read-only mode. A "look at this repo and
propose a fix" request has the full mutating belt attached.

**Do.** Depends on #4 (a tool must know it is read-only to be lockable). Then
`/plan` = the same turn with mutations gated to reject, ending in a proposal.

---

## 🅿️ #10 — Subagents

**Partly built.** `spawn_agent` exists (`tools/session-tools`), runs a nested
`run-turn!` with an isolated message list and the SAME approval gate — which is the
right call and is documented. What is missing versus the source: a named
**read-only Explore** agent (gated on #4), background execution, and per-agent
cache boundaries.

**Parked because** the source's own lesson is that multi-agent coordination is
mostly *prompt* architecture (`coordinatorMode.ts` is system-prompt instructions),
and grog has no orchestration need yet. Build it when a task actually needs
isolation, not before.

---

## 🅿️ #11 — Background memory consolidation

**Source.** `autoDream.ts`: a `/dream` prompt forked as a subagent when a triple
gate passes (24h since last consolidation, 5+ sessions accumulated, advisory lock
acquired) — cheapest check first. `consolidationLock.ts` uses **file mtime as the
`lastConsolidatedAt` timestamp** with rollback on failure.

**Why it is parked.** grog has the ingredients (`assoc_*` store, journal,
`skills/`, per-project `mem.db`) and a *stale* version of the problem:
`notes/handoff-2026-10-10.md` records the loop sitting OPEN for days while sessions
wrote handoff files instead of journal entries. So consolidation has real value
here. But it is a genuinely separate subsystem, and #2 (compaction) will change the
shape of what is worth consolidating. Revisit after #2.

---

## ⬜ #12 — Hooks

**Source.** 25+ event points (`PreToolUse`, `PostToolUse`, `SessionStart`,
`CwdChanged`, …). The principle worth stealing: *"if you repeat the same
instruction in every prompt, it should be a hook."*

**Why it applies here.** grog's `eca-rules.md` is exactly that — standing
instructions re-stated every prompt. The `evals/` runner already needs a
PostToolUse-ish seam, and `scripts/eval.clj`'s `inject-fault` is a hand-rolled
version of one.

**Do.** A small `:hooks {:pre-tool-use […] :post-tool-use […] :stop […]}` map on
`run-turn!`, run in-process. Note the source's cautionary tale: a `SessionStart`
hook that spawned instances recursively became a **fork bomb** (1→2→4→…→2^n), only
stopped by the machine running out of memory. Any spawn-capable hook needs an
idempotence guard.

---

## ✅ #13 — Self-verifying edits (the rule already proven here)

**Exists.** `tools.clj`: `write_file` re-reads and compares; `edit_file` refuses a
missing or ambiguous anchor, refuses an empty anchor, and re-reads after writing.
`write_file` against `/dev/null` reports a mismatch instead of a byte count.

**Generalise it.** The rule is *a tool must not be able to say "success" without
measuring* — and it is currently applied to two tools. `move_file` does it. The
MCP tools do not (they are ECA's, and one of them is what lied). #14 is the same
rule applied to the verifier itself.

---

## ✅ #14 — `edit_file`'s verification was decided by an anchor COUNT (fixed 2026-10-23)

**The shape of the bug.** For two years' worth of sessions this tool has asked the
wrong question. A self-verifying writer must prove the **effect** — the bytes on
disk are what we set out to write — and `edit_file` instead graded itself on a
derived statistic, *how many times the anchor still appears in the re-read file*.
That statistic is a property of the **replacement text**, not of whether the edit
happened, so every rule built on it eventually rejects a correct edit:

| rule | fails on |
|---|---|
| `(zero? old-left)` — original | any edit that keeps its anchor: the ordinary "append a line below" → reported failure for a landed write (hit twice 2026-10-19) |
| `(<= old-left n-before)` — the first fix | any `new_content` **containing** the anchor: replacing `a` with `aa` → reported failure for a landed write (measured 2026-10-23) |

`written=` `read-back=` being equal was the proof the write landed, and the code
printed it *inside* the failure message.

**Why it matters more than it looks.** This is the inverted twin of the incident
`tools.clj` was written to prevent. That tool lied "success" while doing nothing;
this one told the truth in the file and lied in the report. Both cost trust, and an
agent that re-does a landed edit because the tool claimed failure will eventually
**duplicate** it.

**The fix (2026-10-23).** `ok?` — `(= want got)` — IS the verdict. It is sound
rather than a relaxation: `want` is computed from `text`, which was read and
checked for a present, unambiguous anchor *before* the file was touched, so
`(= want got)` proves the edit landed — and it still catches 2026-10-14 (there
`got` = `text` ≠ `want`). The anchor counts are now **diagnostics**, reported in the
failure text as evidence, never asserted on. The threshold-free rule generalises:
*if the last two attempts to fix a check both made it stricter or looser in one
direction, the check is measuring the wrong thing.*

Two more changes in the same function:

- **A no-op is no longer reported as a replacement.** `edit_file` with an anchor
  and `new_content` that change nothing used to answer *"1 replacement(s), verified
  by reading it back"* for a file that did not move — the same lie in miniature. It
  now says `NO CHANGE` so the model re-reads instead of believing it changed
  something.
- **The message names what was measured** (`1 replacement(s)`, or the real count
  under `all_occurrences`).

**Tests.** `scripts/edit-file-verify-test.clj` — 19 checks over the REAL
`edit-file*` with real temp files (append-below, replacement-containing-anchor,
removal, re-add, prepend, `all_occurrences`; refusals leave the file untouched; a
no-op is a no-op; an unwritable file is still caught, so the fix did not buy
credulity). **Proved discriminating, not decorative**: reverted to the previous
code, the same file reports **2 FAILURES** (the anchor-containing replacement and
the no-op); with the fix, 19/19. Wired into CI's agent-loop offline step.

**Still worth doing:** an evals case of the `:claim-matches-state` shape, inverted —
"the tool reports a failure and the state says success". The offline test covers the
verifier; an eval would cover an agent's *response* to a lying report.

---

## 🅿️ #15 — Explicit stop conditions (b done; (a) shipped earlier)

**Source.** The agentic loop stops on a task-completion signal, an unrecoverable
tool error, or a turn cap; the practical advice is to define "done" in the task
rather than the process ("check the logs; if you find an error in the last 100
lines explain it, else say none" beats "keep checking the logs").

**Why it applies here.** Today's evidence, from grog's own log: two turns ended
`:max-steps steps=12` at 22s and 61s, and one hit **216,947 ms** (3.6 minutes)
before giving up. `:max-steps` is a ceiling, not a stop condition — and when the
model is looping, the ceiling is the only thing that ends the turn, 12 steps late.

**Do.** Two cheap parts. (a) On `:max-steps`, the loop's answer should say *which*
step it died on and what it was doing — `scripts/eval.clj` already recovers the
last assistant text for exactly this reason; the live loop does not. (b) A
repetition guard: identical tool call with identical arguments twice in a turn is a
loop, and the source's `repeated outputs` detector is the model. Warn on the
second, refuse the third.

**DONE 2026-10-19.** Both parts are in `grog.agent.loop`. (a) The `:max-steps`
exit names the last four tool calls and reports the last assistant text. (b) The
guard: a call is signatured by name + canonicalised arguments (keys sorted
recursively — Cheshire has no `:sort-keys`, which this bug proved), **warned** on
the 2nd occurrence and **refused** on the 3rd, the refusal reaching the model as a
tool error rather than being swallowed. See `scripts/stop-condition-test.clj`.

**And the number that started it is gone.** `:max-steps` was hard-coded to 12 and
the client coerced an *unset* `:cli :chat-tool-loop-limit` into it
(`(or (config/chat-tool-loop-limit) 12)`) — so the "unlimited by default" this
project documents (README.md:87, the knob's own docstring) was silently a 12-step
ceiling. It is now genuinely uncapped unless you ask for a cap, which is what
makes (b) the *substitute* for the cap rather than an add-on. The residual (a
ceiling is still not a stop condition) is why this stays parked rather than done:
the guard catches the *identical* loop, not a model making twenty *different*
useless calls.

---

## ⬜ #16 — Conditional autonomy

**Source.** The system infers whether a human is in the loop and adjusts: it asks
and confirms in cooperative mode, and runs autonomously in headless/background
contexts, logging decisions. Most systems wait for input; this one notices there is
nobody to ask.

**Why it applies here.** The same loop runs three ways — the desktop client
(human present), `scripts/eval.clj --engine grog` (no human at all), and
`grog.agent.main` (CLI). Today the approval policy is passed in by each caller
(`:approval :none` for the CLI, `:approval :mutating` for the client) — which is
fine and explicit — but nothing *derives* it, and a headless run that hits an
`ask_user` currently blocks for `answer-timeout-ms` = **10 minutes** before giving
up. In a scripted run that is a 10-minute hang for a question nobody can answer.

**Do.** Pass an explicit `:human-in-the-loop?`; when false, `ask_user` returns
immediately ("no human is present in this run — decide and record the assumption")
instead of blocking, and mutations follow the caller's policy without a dialog.

---

## ✅ #17 — A stalled stream: retry before data, diagnose after it

**Done 2026-10-22.** `src/grog/agent/transport.clj` (`stream-chat!`), the knobs in
`grog.config`, the wiring in `grog.client.loop`, and `scripts/stall-test.clj`
(22 checks, no model, no network).

**The evidence.** A turn died and the ONLY thing the user could report was the
seven words the client rendered:

```
[grog] loop error: Read timed out
```

The cause was a `java.net.SocketTimeoutException` escaping `stream-chat!` uncaught
and unretried. The read timeout was **hard-coded to 300000 ms** in the transport
(ignoring `:llm :socket-timeout-sec`, which existed but only fed the *other*, ECA
transport in `grog.core`), there was no retry, and the exception's own message was
the entire diagnosis. Endpoint, model, how long it waited, whether any text had
arrived, and what to raise — all absent, all known to the code that threw.

**What it does.**

- **Retry before data.** An attempt that received **zero** frames is re-sent
  (`:llm :stream-retries`, default 2 → three tries, 1s then 2s backoff). Nothing
  was received, so re-sending the identical body cannot duplicate anything. A
  transient stall is now survivable — the test's turn recovers on the third try.
- **No retry after data.** Once a frame has arrived the user has watched an answer
  stream in; re-sending would replay it. Such a stall fails **once**, and the
  partial answer is carried out on the exception (`:partial-text`) and rendered,
  rather than thrown away.
- **A diagnosis.** The failure names the endpoint, the model, the attempt count,
  the elapsed time, whether it stalled before the first token or mid-answer, and
  the knob to raise. The original exception is preserved as the cause for the log.
- **HTTP errors are not stalls.** A 400/401/429 is the request's fault; it is
  thrown on the first attempt and its body's `error.message` now appears **in the
  message** (the client renders `getMessage`, so a body kept only in ex-data was
  never seen).

**Two bugs this found in existing code**, both fixed here:

1. The DSML streaming filter holds back a ~32-char tail so an opening tag split
   across chunks is recognised — and on a stall that tail was **never flushed**, so
   the partial answer handed back was silently short (measured: "the answer so
   far" → "the ans"). The stall path now flushes it.
2. `:socket-timeout-sec` was documented as the LLM read timeout but the native loop
   ignored it. The transport now takes `:socket-timeout-ms`, and the client passes
   it from config, so the documented knob finally does what it says.

**Known limitation.** The retry budget is per **step**, not per turn: a turn whose
provider stalls intermittently on many steps could still spend several retries. That
is deliberate — each step is an independent request and a fresh stall is
independently worth riding out — but it means `:stream-retries` is not a cap on a
turn's total attempts.

---

## ✅ #19 — A full context distills and retries; it does not kill the thread (2026-10-28)

**The report.** *"It is operationally intolerable to have the thread get DESTROYED
by overflowing context with no recovery."* It was true, and it was three defects:

1. the budget was a **global constant** (`:llm :max-context-tokens`, default
   200000) in grog.config plus a 120000 fallback in the loop. One number for every
   model: 200k against a 32k local server is a hard 400 with no answer, and 200k
   against a 1M-context model silently elides a conversation that fits fine;
2. the estimate counted only `:content`, so **tool schemas** (~40 of them, riding
   every request) and `tool_calls` were invisible — the proactive check could pass
   on a prompt the server refuses;
3. when the guess was wrong, the 400 **raised out of the loop**. The turn died,
   and the caller kept the oversized history — so the NEXT turn re-sent it and died
   identically. One overflow cost the conversation.

**What it does now.**

- **The budget is DERIVED** (`grog.models/context-budget`): the endpoint's window,
  minus room to answer. The window comes from the cache → `/models` → `/props`,
  and the cache is keyed **(url, MODEL)** — a catalogue endpoint serves hundreds of
  models with different windows over one url, and a url-keyed cache answers for the
  wrong model the moment you switch. Learned windows persist in
  `context-windows.edn`.
- **UNKNOWN means NO GUESS**: nil budget, no proactive trim, send the conversation
  intact. There is no default constant left to be wrong.
- **A real refusal is EVIDENCE, not a failure.** `grog.agent.context/parse-overflow`
  reads the provider's body (Strata/llama.cpp's `context (32768)`, OpenAI's
  `maximum context length is N`, Anthropic's `prompt is too long`), separating the
  WINDOW from the PROMPT size — and never calling a 401 an overflow. The loop then
  **distills** (`grog.agent.context/distill`: system + task + tail, clipped results,
  images dropped, a ladder of harsher rungs), **remembers** the window the provider
  named, emits a visible `[context overflow]` line, and **re-sends the same step**.
  Bounded at two recoveries; a provider that refuses everything gets ONE exception
  that names both sizes and says `/clear`, `/model`, or shorten.
- **The thread survives even then**: the client's handler distills the stored
  messages in place and says so, instead of leaving an oversized history to fail
  again.
- The CLI tool loop (`grog.core/chat-with-tools!`, `/jobs`, chron) gets the same
  recovery, since it has its own transport.

**Tests.** `scripts/context-overflow-test.clj` — 30 checks, no model, no network:
the estimate counts schemas; compaction and distillation always keep the system +
task and NAME what they dropped; six real wire messages parse (and a 401 does not);
the loop's turn SUCCEEDS after a refusal, the second request is measurably smaller,
`:on-overflow` fires; recovery is bounded at 3 attempts; a 401 is thrown once.
`scripts/context-window-test.clj` gained the per-(url, model) cache and
no-default-budget cases. Both wired into CI.

---

## ✅ #18 — One model, and recents (2026-10-26)

**Done 2026-10-26.** The big/small model SLOTS are removed, along with `/big`,
`/small` and `/relay`. There is one model (`:llm :url` + `:llm :model`), a
`/model` command that lists and switches, and a **recents** shortlist.

**Why it went.** The slot pair was justified by the relay cascade: a small frontline
model answering cheap turns and deferring to a big one. It did not work well enough
to use, and it was not free — it doubled every picker, every config key
(`:big-model` / `:small-model` / per-project `:small-model`), every wire field
(`:slot` / `:big` / `:small`), and every status-bar branch, and it made the model
the user sees depend on a mode they had to remember. The honest summary is that the
feature's cost was certain and its benefit was not.

**What replaced it.** Two things, each doing one job:

- **`/model`** with no argument prints the current model and the numbered list; `/model <n>`
  switches to entry n; `/model <name>` takes a `:llm :profiles` entry or a raw id. The
  numbers printed are the numbers accepted — the current model is always 1.
- **Recents** — an LRU list in grog.edn (`:llm :recents`, cap `:llm :recents-max`,
  default 10). Every pick pushes to the front; re-picking an existing entry MOVES it
  rather than duplicating it, which is what makes the cap an LRU bound and not
  FIFO. The picker renders it as a click-to-switch shortlist. Each row stores the
  **pair** `{:url :model}`, so a recent switches transport too.

**The status line names the provider.** A model id alone does not say where it runs
— `qwen3.8-flash-next-unsloth-ud-q4_k_xl` is a local Strata server, `aion-2.0` is
OpenRouter, and the same id can exist on two endpoints. The status bar and
`/model` both render `provider · model`, and the raw endpoint is beside it.

**Compatibility.** The legacy keys are still READ: `:big-model` fills in a
`:url`/`:model` that `:llm` does not carry (so a config written before this keeps
its model), and `:small-model` is ignored outright. Nothing writes them any more.
`set-model-slot` is gone from the wire; a client that calls it gets an
"unknown method" error rather than silent nonsense.

**Evidence.** `scripts/recents-test.sh` — 22 checks. The discriminating one re-picks
the OLDEST entry and asserts it moved to the front; **verified by mutation**: a
FIFO variant (`into (vec kept) [entry]`) fails 3 checks, so the test is not
decoration. It runs against a throwaway `GROG_CONFIG_HOME` and refuses to run
without one — the first version of this test set a JVM system property instead,
which the config loader ignores, and wrote the developer's real `grog.edn`. That
is recorded in the file's own docstring, because the failure mode is invisible.

---

## ✅ #20 — Model selection is ONE dialog, and a NAME is the unit (2026-10-30)


**Done 2026-10-30.** Model selection moved out of Settings into its own dialog
(`Ctrl+M`), and a model became a **named record** — `{name url model}` — that is
picked, edited and deleted **by name**, written in **one call**. The baked-in
providers are now **templates** for a new row, not a menu of fixed choices.

**The report.** *"I am extremely disappointed on how the model selection is
implemented. It is a damn mess, and virtually impossible to use. … The model
selection should be a self contained dialog (break out of settings) and it needs
to MAKE SENSE. The baked in models should be used as a template, and user needs to
be able to specify/edit a URL, and give that a NAME that can be then used to pick
the model."*

**What was actually wrong.** Not the widgets — the shape. One decision (which
model runs) was spread over four controls that only make sense **together**: a
provider combo, a separate port box, a raw model-id box, and a catalogue list.
Landing one change took **three RPCs**. That shape had already produced this
string of reports, one per session:

| Report | Cause, in that shape |
|---|---|
| "the port doesn't stick" | the port lived in its own db key; **Apply** dispatched `:set-model` only, so nothing wrote it |
| "picking strata gives a BOGUS model" | a transport pick wrote `local-model` — a placeholder nobody serves — because the id box and the transport were separate |
| "a history pick doesn't set it correctly" | a recents row carries its **own url**; the click set the id and left the endpoint alone |
| "there's no help for your last change" | `/help` pointed at a GUI field the user could not find |

Each fix **added another field to keep in sync**. That is the diagnosis: the panel
was a set of writers racing over one record, so every repair created the next
bug. The redesign removes the second writer rather than reconciling writers.

**The rule now.** `name` **is** the model. Everything else is a field of it.

```
:llm :choices [{:name "workstation" :url "http://localhost:8081/v1" :model "qwen3-32b"}]
```

- **One list.** Open `Ctrl+M` (or the toolbar **model** button, or the status bar's
  model line) and it opens on your named models — it loads its own data, so the
  dialog never depends on Settings having been opened.
- **Three fields, one Save.** Name, URL (**port inside the URL** — there is no
  port control to desync), model id. `save-choice` writes all three.
- **Pick is one click.** The spine resolves the NAME to its `[url model]` pair
  (`pick-choice`) and the client writes that pair through the SAME `set-model`
  every other pick uses. One write path for the dialog, `/model <name>` and a
  project's `:model` pin.
- **Templates, not a menu.** Picking `strata` opens a NEW row pre-filled with its
  URL and — for a running server — the id it actually answers to. Every pre-filled
  value is an editable field.
- **The endpoint's own ids, under the field.** `list ↻` asks that URL what it
  serves; a click fills the model field. A click never writes — Save does.
- **Errors land in the form**, in the spine's own sentence, beside the field at
  fault; a rejected save keeps the half-typed text instead of wiping it.
- **Console parity**: `/model new strata [name]`, `/model rm <name>`,
  `/model <name>` — the same list, so the GUI and the console cannot drift.

**What was deleted.** The provider combo, the port box, `models-apply`,
`provider-pick`, `models-draft`, the `:slot`-shaped catalogue fetch, and Settings'
whole model tab (Settings now shows one read-only line and a **change…** button).
Removing those is the point: 16 renderer checks about port-draft staleness and
Apply ordering stopped being a thing that could be wrong, because the state they
described no longer exists.

**The bug the rewrite's own test caught.** Driving `save-choice` against a real
spine showed every typed URL being stored with a **doubled scheme**:

```
save-choice {:url "http://127.0.0.1:9/v1"}  ->  stored "http://http://127.0.0.1:9/v1"
```

`re-matches` was used with a pattern that describes a **prefix**
(`^[a-z][a-z0-9+.-]*://`). `re-matches` demands a whole-string match, so a URL that
already had a scheme failed the test and got `http://` glued on the front. Every
model saved from the dialog would have been stored wrong, surfacing much later as
"connection refused" against a host that never existed. Nothing in the repo tested
the URL field, because nothing tested the URL field. `scripts/url-normalization-test.sh`
now pins that one function's boundaries (bare host → scheme added, odd path left
alone, doubled scheme and non-http refused rather than "repaired").

**A gap the redesign itself missed, caught by re-reading the request.** The first
version of the dialog offered only "start from a known endpoint" as the way to
*add* a model — i.e. the baked-in catalogue as the only door. That is the opposite
of what was asked ("the user needs to be able to specify/edit a URL"). Fixed: the
add section now leads with **`+ add a model (your own URL)`** — a blank form, name
first — and the shipped endpoints sit beneath it as the shortcut they were meant
to be. A gateway, a relay, or a server on a port nobody has heard of has to be
typeable, not selectable from a list grog chose.

**A second class of failure, recorded honestly.** Rewriting ~1300 lines of
`core.cljs` in one pass broke the *reader* of two files (`core.clj`,
`client/loop.clj`) and left `server.clj`'s `ns` docstring closed early by literal
quotes inside it — all three compiled nothing, and the breakage surfaced only when
a spine test ran. Bracket imbalance is invisible to a text diff. The fix is in the
toolbox, not in care: `pcheck.py`-style balance scanning per top-level form, and
running the **spine** compile after any spine edit, not just the client's.

**Evidence.**
- `npm run test:renderer` — 63 checks, 38 of them the dialog: it opens on the list
  with its own data and the editor **closed**; a named pick writes the resolved
  `[url model]` pair; an unpickable name says why **in the form**; Save writes all
  three fields in ONE call and no `set-provider` rides along; a rejected save keeps
  the half-typed URL; editing a row browses **that row's** endpoint, not the one in
  force; a template pre-fills but writes nothing; a `models` broadcast fills only
  its own URL's box; Escape steps out one level. The stub now supports per-method
  **replies**, including rejections — the old stub answered every call with `nil`,
  which is why a whole class of "the response was never read" bug was untestable.
- `scripts/model-picker-test.sh` §10 — the dialog's RPC contract: name in, pair
  out, and the three kinds of half-filled form refused with a message that names
  the field.
- `scripts/url-normalization-test.sh` — the URL field.
- **Verified by mutation**: restoring `re-matches` for one line makes §10 fail 7
  checks. The tests discriminate.
- The 16 obsolete port/Apply checks were **deleted, not left failing** — a green
  suite that skips a removed feature is worse than one that no longer asks.

**What this does NOT claim.** No screenshot was taken of the new dialog: the
renderer has no DOM in tests, and driving Electron headless to click into a dialog
was not worth the complexity here. What is verified is that the bundle **compiles
clean** (`shadow-cljs compile app`, 0 warnings), that Electron brings up the built
renderer (`scripts/electron-smoke.js`: *transport up + renderer loaded*), and that
every handler and RPC the components call does the right thing. The first real
clicks are the user's.

---

## Sources

- **"What Claude Code's Leaked Source Actually Teaches Us About Building AI
  Agents"** — Kubesimplify, Saiyam Pathak, 2026-04-01.
  https://blog.kubesimplify.com/claude-code-leak-what-the-source-actually-teaches
  *(the source of #1 (deferred loading), #3 (the generator loop), #2 (five
  compaction strategies), #4 (default-deny tool properties) and #5 (denial
  tracking). The most engineering-dense of the write-ups; it also names the
  `claw-code` clean-room rewrite and warns about malware in "leaked source" repos.)*
- **"What the Claude Code Source Leak Reveals"** — Blake Crosley, 2026-04-02.
  https://blakecrosley.com/blog/claude-code-source-leak
  *(the source of #2's circuit-breaker number, #6 (bash security depth), #7 (14
  cache vectors, sticky latches), #11 (autoDream), and the fork-bomb story in #12.)*
- **"Practical Lessons From the Claude Code Leak"** — Bilgin Ibryam, 2026-04-03.
  https://generativeprogrammer.com/p/practical-lessons-from-the-claude
  *(the source of #8 (subagents as context isolation), #9 (explore→plan→code), and
  the framing that "the moat is not the model, it is the harness".)*
- **"How Claude Code Builds a System Prompt"** — dbreunig, 2026-04-04.
  https://www.dbreunig.com/2026/04/04/how-claude-code-builds-a-system-prompt.html
  *(the source of #7: the dynamic boundary, and the observation that much of the
  system prompt is conditional assembly, not one string.)*
- **"Comprehensive Analysis of Claude Code Source Leak"** — Sabrina Ramonov,
  2026-04-02. https://www.sabrina.dev/p/claude-code-source-leak-analysis
  *(the compaction prompt-injection finding in #2, the verification agent's
  rationalisation list in #8, and `SYSTEM_PROMPT_DYNAMIC_BOUNDARY`.)*
- **"Claude Code Leak: 16 Lessons on Building Production-Ready AI Systems"** —
  Analytics Vidhya, Riya Bansal, 2026-04-02.
  https://www.analyticsvidhya.com/blog/2026/04/claude-code-leak-insights-for-ai-builders/
  *(#16 (conditional autonomy), #9 (plan/act), #12 (hooks) and the four-layer
  memory model behind #11.)*
- **"Claude Code Source Code Leak: 8 Hidden Features"** — MindStudio, 2026-04-01.
  *(#15 (stop conditions) and the diff-based edit behaviour that #13 already
  implements.)*
- The infosecwriteups piece ("The Accidental Open Source", 403 to this agent's
  fetcher) is the one source not read directly; it is the origin of the *phrase*
  in the task, not of any item here.

**Patterns deliberately NOT taken** (so the omissions are visible): the companion
pet system, the undercover module and anti-distillation decoys (they protect a
vendor, not a user), the `ToolSearch`-as-a-service idea, and the Zsh-specific bash
security rules (grog runs `bash -lc` across platforms, and #4 is the real fix for
tool risk).