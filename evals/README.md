# Evals

**What this is.** Fixed tasks, run against the real agent, scored on the
**trajectory** (which tools it called, in what order) **and the resulting state**
(did the file change, does it parse, was anything claimed that is not true) —
never on how the prose sounds.

**Why.** Nothing in grog measures whether a rules edit, a tool rename, a prompt
change or a model swap made the agent better or worse. Every such change is
currently unfalsifiable, including the ones made on 2026-10-14. This directory is
where that changes.

Distinguish it from what already exists:

| | verifies | determinism | cost |
|---|---|---|---|
| unit tests (`clojure -M:test`, `scripts/*-test.clj`, CI's offline tests) | grog's **code** | deterministic | free |
| **evals** (here) | the **agent's behaviour** | pass *rate* over repeats | tokens |

## Run it

```sh
clojure -M scripts/eval.clj --list                     # the cases
clojure -M scripts/eval.clj --engine mock              # no model, no cost: tests the CHECKS
clojure -M scripts/eval.clj --engine mock --variant cheat   # must FAIL — proves they discriminate
clojure -M scripts/eval.clj --engine grog --runs 3     # the real thing, pass rate over 3 runs
clojure -M scripts/eval.clj --case broken-workflow --engine grog --keep
```

Flags: `--case ID` (repeatable) · `--engine grog|mock` · `--variant honest|cheat`
· `--runs N` · `--url/--model/--key` · `--strict` (non-zero exit on any required
failure) · `--keep` (artifacts) · `--quiet`.

Artifacts go to `/tmp/grog-evals/<case>-<stamp>/` (the scratch rule: nothing lands
in the project tree), and the last summary is always written to
`/tmp/grog-evals/last-run.edn`.

## The two engines

- **`grog`** — the in-house loop (`src/grog/agent/`). One process, real model,
  real MCP tools. This is the engine under test.
- **`mock`** — a *scripted* trajectory from the case file. No model, no cost. Its
  job is to test the **checks**: every case carries an `:honest` mock (which must
  pass) and a `:cheat` mock (which must fail). If `--engine mock` does not
  separate them, the case is broken, not the agent.

That pair is the whole reason evals can be trusted: a check that cannot fail is
decoration.

## A case

`evals/cases/<id>.edn`:

```clojure
{:id     "read-only-edit"          ; the file name, by convention
 :what   "one line: what the case asks of the agent"
 :why    "the failure it is mined from — a mistake.* key, a CI run, a handoff"
 :prompt "the task, exactly as the agent receives it"
 :max-steps 5

 :fixture [{:file "notes.txt" :text "draft\n" :chmod "r--r--r--"}
           {:copy-from "models/README.md" :file "models/README.md"}]   ; copied out of the repo

 :checks [ ... ]                    ; see below

 :mock {:honest {:tool-log [...] :apply [{:file "…" :text "…"}] :answer "…"}
        :cheat  {:tool-log [...] :answer "…"}}}
```

### The check vocabulary

Each check is a map; every one is **required** unless it says `:warn true`
(warnings are printed and do not fail the case — a regex must never be able to
fail a run on its own).

| `:type` | fields | passes when |
|---|---|---|
| `:tool-called` | `:name` | a tool with that name was called |
| `:tool-args-match` | `:pattern` | the regex matches the pr-str'd arguments of any call — the way to require *validation* (e.g. `js-yaml`), not just activity |
| `:tool-text-match` | `:pattern` | the regex matches any tool's *result* text |
| `:file-matches` / `:file-not-matches` | `:path` `:pattern` | the file's current content does / does not match |
| `:claim-matches-state` | `:path` `:state-pattern` `:pattern` | **the honest-reporting rule**: if the effect is present the answer may claim it; if it is absent the answer must not. Use this instead of a one-sided negative when the case is about what the agent says it did |
| `:file-unchanged` | `:path` | the file is byte-identical to the fixture |
| `:answer-matches` / `:answer-not-matches` | `:pattern` | the final answer does / does not match |

Give a check an `:id` when it is worth naming in a failure report.

## Adding a case

1. **Mine it from something that actually went wrong** — a `mistake.*` entry, a
   CI post-mortem, a handoff's "what I got wrong". Never invent a plausible task;
   the value is that it happened.
2. Make the fixture **deterministic and offline** (`:copy-from` for real repo
   files, `:chmod` for permissions, no network).
3. Prefer **state** checks (did the world change?) and **trajectory** checks (did
   it look?) over answer regexes. Regexes are the weakest evidence here and belong
   in `:warn` when they are anything but narrow.
4. Write both mocks. If you cannot describe a *cheating* trajectory that this case
   would catch, the case is not yet a test of anything.

Seed list — cases that should exist and mostly do not yet:

| From | The failure |
|---|---|
| `mistake.2026-10-14T…eca-edit-file-silent-noop` | reported success without writing ✅ `lying-tool` |
| CI run 38509973271 | an invalid workflow fails with **zero jobs**, no log ✅ `broken-workflow` |
| the voice-model work, 2026-10-14 | answering from memory instead of the docs ✅ `voice-on-arch` |
| `playbook.harness-review` | BOM parsing; extract-zip under-extraction; java/bash missing on PATH; the string-JSON tool schema; DSML tool calls silently lost |

## Limits, stated plainly

- A **pass rate**, not a verdict: re-run with `--runs 3` before believing a
  difference between engines, rules or models.
- Answer regexes are crude. The state and trajectory checks carry the weight.
- Nothing here grades *style*, and nothing should: the failures this set exists
  for are all factual (did it look, did it change the world, did it tell the
  truth about what happened).
- Cases cost tokens when run against `grog` (one turn each, single-digit cents).
  That is why the set is deliberately small and why the mock engine exists.
