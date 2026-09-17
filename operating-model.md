# The GROG Operating Model — a system design

*What grog is, why it's built this way, and the invariants that make the model work.
This is the concept layer. For mechanics — config, commands, MCP setup, skill
authoring — see the other docs in the repo.*

---

## 1. The idea

Grog turns an LLM agent into a **persistent working companion**. The companion lives
inside a project, on your machine, and:

- **collects** information (whatever the project cares about),
- **interprets** it against rules a human has taught it,
- **stores** the judgment so it survives restarts,
- **produces** something useful, typically on a recurring basis.

Two properties make this worth building:

1. **Persistence.** The judgment lives in files, not in a process. Nothing survives a
   reboot by accident; everything survives deliberately.
2. **Transferability.** Because the capability is data, a teammate can take it: they
   clone the work, provision their own access, and run the same capability in their own
   environment. **Secrets never travel; the capability does.**

## 2. The taxonomy: two kinds of unit, and no deeper

The system has two kinds of reusable unit. That's all. There is deliberately no
six-layer model.

- **Skills** — small, self-contained capabilities. Each has a manifest describing what
  it does and how to use it.
- **Projects** — clusters of work built on skills, carrying their own state and history.

Projects **declare** which skills they depend on. A new installation resolves those
dependencies before first use; a missing dependency fails loudly and early, not later
when the work is already wrong.

Over time, a project's reusable core can be **promoted** to a skill. That is the only
lifecycle transition there is: project → skill, and back to being depended on.

## 3. The companion: identity and continuity

The companion is best understood as a **template plus a life**:

- The **canonical part** — rules, capabilities, structure — is shared. This is what a
  teammate clones and contributes to. It is reviewable, versioned, and transferable.
- The **personal part** — credentials, local state, conversation history, accumulated
  archives — belongs to one installation. It is never shared by default.

The two parts must be cleanly separated, and the boundary is the core invariant of the
whole design. When two teammates run the same project, they share the canonical part and
keep their personal parts separate; changes to the shared part merge through normal
version control, while personal state never clobbers anyone else's.

Memory follows the same rule: some knowledge is scoped to the project, some to the
person, and the shared knowledge that should travel is promoted to the canonical layer.

## 4. The operating loop

A project does its work in a loop:

1. **Collect** — gather what the project is watching.
2. **Interpret** — apply the project's rules. When the model encounters something the
   rules don't cover, the standing rule is: **flag it, don't guess.** The rules evolve
   when a human teaches the right reading.
3. **Persist** — write the results and any new understanding back to the canonical or
   personal layer as appropriate.
4. **Repeat** — each cycle builds on the last, so the quality of the output compounds
   without starting over.

Judgment learned in one place is promoted to the shared layer so every installation
benefits from it.

## 5. Trust and safety

The companion has real access to the machine — it runs commands, reaches systems, and
handles data. Access is the point, but it needs a trust model:

- **Consent** — actions outside an approved envelope require approval before running.
- **Allowlists** — capabilities are gated by a per-install list of what the companion
  may use.
- **Secrets held apart, in the system secrets store** — credentials are not environment
  variables and not config-file entries. They live in the OS keyring under a service
  name (with a fallback secrets file owned only by the installation, outside the shared
  work, for headless/remote setups). The companion reads them from the store when a
  capability needs them; nothing about them enters version control or the shared layer.
- **The shared layer stays clean** — nothing private or secret is committed; the shared
  layer is assumed visible to everyone who clones it.

Anyone taking over a project should be able to review, in a few minutes, what it
touches, what it reads and writes, where secrets are kept, and what footprint it leaves.
A project that can't be reviewed quickly shouldn't be trusted — and that's a property of
good design, not an afterthought.

## 6. Failure, drift, and divergence

The model anticipates failure as part of normal operation:

- **Missing dependency** — a project that declares capabilities its new home doesn't
  have fails loudly at setup, with a clear statement of what's missing.
- **Cache vs canon** — runtime state is derived from the shared rules, not the source
  of them. Local tweaks are ephemeral and may be re-derived; anything that must persist
  is promoted to the shared layer.
- **Non-portable work** — some projects are inherently tied to one environment: they
  depend on infrastructure only one installation has, or their value is in the
  accumulated private life rather than a reusable core. These are a recognized category,
  handled case by case, and not forced into the shared model. If they contain a
  reusable core, that core is promoted as a skill.
- **Divergence** — different installations may drift. That's accepted; the shared layer
  is the source of truth for anything that should converge, and everything else is
  locally owned.

## 7. Authoring and curation

Both humans and the companion can author capabilities. The companion writes them to the
same template and standards as a human would, and the human **curates** — deciding what
gets promoted, merged, and shared. The distinction between "the companion can write
this" and "a human approved putting it in the shared layer" is deliberate and
important.

## 8. Transfer

Transferring a project to a teammate is a first-class operation: package the canonical
layer, deliver it, and let the receiving installation provision its own personal layer —
own credentials in its own system secrets store, own paths, own state. The receiving
installation is then an equal instance of the same capability.

## 9. Ground rules

1. The shared layer is what travels; the personal layer stays home.
2. Secrets never enter the shared layer. Each installation provisions its own credentials
   in its own system secrets store, and the store content never travels with the work.
3. The shared layer is text, reviewable and mergeable; runtime state is derived from it.
4. The companion is a template, not a person — which is exactly why it can travel.
5. Rules evolve by promotion, from a local lesson to a shared truth, through curation.

## 10. Where this fits

| Doc | Job |
|---|---|
| `README.md` | Orientation, features, commands, quick start |
| `USERS-GUIDE.md` | Configuration and secrets, per-install |
| `eca-users-guide.md` | Enabling the tool servers in an editor |
| **`operating-model.md`** | **This file — the concept and invariants** |
| `SOUL.md` | The companion's standing behavior |
| `state.md` | Internals / session notes |

Specifics — commands, schemas, examples — live in the reference docs, not here. This
file is meant to stay true even as those specifics change.