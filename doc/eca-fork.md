# The ECA we ship

grog does not use "whatever `eca` this machine has". It builds and ships its own,
because the fork below carries a fix grog needs — and an install that drives some
other copy silently runs without it.

## What ships, and where

| Artifact | Path in the repo | Path in a packaged app |
|---|---|---|
| the ECA server | `target/eca.jar` (~33 MB) | `resources/jars/eca.jar` |

- Built by `scripts/eca-jar.sh`. Which ECA that is — repo and commit — is the **two constants at
  the top of that script**; there is nothing else to set, no fallback to a local checkout, and no
  reuse of a jar that happens to be lying around. It shallow-clones exactly that ref, builds with
  ECA's own `prod-cli`, and refuses to continue if the checkout is not the sha the file names.
- `ECA_REF` is set to the branch **`dmsl-tools`** on purpose: it tracks ECA as it is being worked
  on, so shipping a new ECA needs no edit in grog. The price is that the ref moves, and two
  builds of one grog version can therefore differ — which is why the resolved **commit** is
  recorded in `target/eca.jar.provenance.edn` beside the jar. Pin a sha or a tag instead if you
  want two builds to be identical; a push preserves a sha, a rebase or force-push does not.
- Authentication is git's own — an SSH key or an https credential helper. This script never
  sees, prints, or stores a credential, and CI supplies its own the same way.
- Every build also writes `target/eca.jar.provenance.edn` (repo, ref, resolved commit, time) via
  a Clojure printer, so a jar can always be tied back to a commit.
- Declared in `clients/web/package.json` → `extraResources` (`../../target/eca.jar`
  → `jars/eca.jar`), and included in the portable tarball by `scripts/build_dist.clj`,
  which **refuses to pack a bundle without it** (`run scripts/eca-jar.sh`).
- `target/` is gitignored: a jar is a build artifact, never committed.

## How grog picks which ECA to run

`grog.eca/make-connection!`, in order:

1. **an explicit `:eca :binary`** from `grog.edn` — the caller's own choice, so it wins
   outright. This is how a dev checkout drives live source (e.g. `~/.local/bin/eca-local`,
   which is a wrapper around a source tree, not a binary).
2. **the bundled jar** (`grog.eca/bundled-eca-jar`): `GROG_ECA_JAR` when the desktop client
   set it (a packaged app — the jar lives inside the read-only squashfs, so it cannot be
   found by searching), else `target/eca.jar` beside the running `grog-spine.jar`. It is run
   as `java <flags> -jar <jar> server …`.
3. **the fallback** (`grog.eca/resolve-eca-binary!`): a PATH hit, then well-known locations
   — grog's own `%LOCALAPPDATA%\eca`, scoop shims, `%APPDATA%\npm`, `~/.local/bin`, `~/bin`,
   `/usr/local/bin`, `/usr/bin`.

Step 3 is the one that bites. It used to probe editor-extension directories too, which on
Linux put a VS Code extension's ECA *first* (grog's own entry is a Windows-only path); that
was removed. It can still reach a foreign ECA through the PATH — on this box there is a
98 MB `/usr/local/bin/eca` native binary dated 2026-05-19, which is exactly what step 3
would drive were nothing configured. Hence step 2 existing at all.

The spine logs which one it chose, into the client log (`~/grog.<pid>.log`):

    [grog-debug] eca: eca binary: /…/jars/eca.jar (bundled jar, via java -jar)
    [grog-debug] eca: eca binary: /home/…/.local/bin/eca-local

## The fork, and what changed — Apache-2.0 §4(b) statement of changes

**Upstream:** [editor-code-assistant/eca](https://github.com/editor-code-assistant/eca),
**Apache License 2.0**. The built jar carries `LICENSE.txt` and `NOTICE.txt` at its root, so
the licence and the notice travel with every copy we ship.

**Our fork:** the patched ECA lives on **GitLab**. The URL is not hard-coded anywhere — it goes
in `$ECA_GIT_URL`, and `scripts/eca-jar.sh` is the only place that needs it. The line so far:

| commit | what |
|---|---|
| `41aa3f150` | "DSML tool support" — the recovery patch; 1 commit ahead of upstream `master` |
| `d2e766d84` | "working on catching DMSL tool calls" — commits the `sane-output-limit` tweak that had been sitting uncommitted, and was therefore inside a jar that matched no commit |

Keep this table honest when it moves: it is the §4(b) statement of changes, and the sha is what
the build pins.

**The change:** recovery for DeepSeek's native **DSML** (DeepSeek Markup Language) tool-call
format.

- `src/eca/llm_providers/dsml.clj` (new) — parses `<｜tool_calls>` / `<｜invoke name=…>` /
  `<｜parameter name=… string="true">` blocks.
- `src/eca/llm_providers/openai_chat.clj` — calls it when a response carries no structured
  `tool_calls` (`maybe_recover_dsml_tool_calls`).
- `test/eca/llm_providers/dsml_test.clj` (new), `CHANGELOG.md`, `src/eca/features/chat.clj`.

**Why it matters here:** DeepSeek V4/V4.1 models — including the
`openrouter/deepseek/deepseek-v4.1-flash` grog defaults to — may serialise tool calls as DSML
tag blocks inside the assistant *content* instead of the OpenAI `tool_calls` field. When the
serving stack (or an OpenAI-compatible gateway in front of it) does not translate them, the
raw markup is delivered as ordinary assistant text and **the call is silently lost — the turn
simply looks like the model answered without calling anything.** With the patch, the calls are
recovered.

**A jar must correspond to a commit, and now cannot fail to.** The build takes its ECA only from
the pinned clone, so drift is structurally impossible rather than policed. That was not always
true: a jar was built — and shipped — from a local checkout whose working tree was dirty, so it
carried `src/eca/models.clj` + `test/eca/models_test.clj` (a `sane-output-limit` tweak whose
effect was never isolated; the real fix turned out to be a config change) that existed in no
commit anywhere. `d2e766d84` committed them. Building only from the pinned remote is what makes
that class of artifact impossible instead of merely discouraged.

## Why a jar, and not a native binary

ECA can also build native images (`clojure -T:build native-cli`, tooling already in its
`build.clj`), and grog knows how to choose a binary. `native-image` **cannot cross-compile**,
though: a Windows artifact has to be produced on Windows. One jar is platform-independent, and
grog already requires a JRE (the spine ships as `target/grog-spine.jar`), so a jar adds **zero**
new prerequisites. If ECA's startup latency ever becomes worth paying for, ECA's `native-cli`
plus its per-OS CI matrix is the recipe — measure before adopting.

## Verified when this was written

- `java -jar target/eca.jar --version` → `eca 0.162.0`; manifest has `Main-Class: eca.main`, so
  the launch shape is `java -jar eca.jar server --config-file …`.
- The jar contains `eca/llm_providers/dsml.clj` and
  `eca/llm_providers/openai_chat$maybe_recover_dsml_tool_calls` — the patch is really compiled in.
- **A full session through the bundled jar.** `scripts/smoke-server.js` against the packaged
  launch shape (`java -cp target/grog-spine.jar clojure.main -m grog.server`), with no explicit
  `:eca :binary`: `open` OK, and `prompt` answered `pong` from
  `openrouter/deepseek/deepseek-v4.1-flash` — the very model whose DSML output the patch is for.
  The log named the right ECA:
  `eca binary: …/target/eca.jar (bundled jar, via java -jar)`.
- **Precedence checked both ways.** The same run with `:eca :binary` configured reports
  `eca binary: …/.local/bin/eca-local` — the explicit choice wins, as intended.
- **The sibling lookup.** Without `GROG_ECA_JAR`, a spine run from `target/grog-spine.jar` finds
  `target/eca.jar` next to it (this is what a portable tarball relies on, where no client sets
  the env var).
