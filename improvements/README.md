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
| 14 | `edit_file`'s own verification is wrong when the edit contains the anchor | — | 🔜 (bug) |
| 15 | Explicit stop conditions in the task, not just a step cap | stop conditions | 🅿️ (b) done |
| 16 | Conditional autonomy (asks when a human is present, runs when not) | cooperative/headless modes | ⬜ |

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

## 🔜 #14 — `edit_file`'s verification is wrong when the replacement contains the anchor (a BUG, hit twice on 2026-10-19)

**What happened.** On 2026-10-19, editing a file by appending a line *below* the
anchor made `edit_file` answer:

> `EDIT FAILED VERIFICATION … No success is being claimed. written=N read-back=N anchor-remaining=1 new-occurrences=1`

…while the bytes **had** landed and `grep` confirmed them. It happened twice in one
session, each time reading as churn in the transcript.

**The defect.** The success test is

```clojure
(if (and ok? (or all_occurrences (zero? old-left))) (ok …) (bad …))
```

`old-left` is "how many times does the anchor still appear in the re-read file". An
edit that ADDS text around the anchor leaves the anchor present — so `old-left` is
1, and a perfectly good write is reported as a failed verification. `written=`
`read-back=` being equal is the proof the write landed, and the code even prints it
in the failure message.

**Why it matters more than it looks.** This is the inverted twin of the incident
`tools.clj` was written to prevent. That tool lied "success" while doing nothing;
this one tells the truth in the file and lies in the report. Both cost trust. An
agent that re-does a landed edit because the tool claimed failure will eventually
**duplicate** it.

**Do.** The check should be: the file now equals `want` (`ok?`), AND (for a
non-`all_occurrences` edit) the anchor's occurrence count did not *increase*, AND
the new text is present. Concretely, `old-left` should be compared against its
count **before** the edit, not against zero. Add a test case to `evals/` — this is
exactly the `:claim-matches-state` shape, inverted.

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