# Building a skill — template

A skill is a **small, self-contained, reusable capability**: a manifest
(`skill.edn`), a short guide (`SKILL.md`), and optionally resources/scripts.

## Where skills live

`:skills {:roots ["skills"]}` in grog.edn. Each root is resolved **relative to the
repo root** (it may also be absolute, and `${ENV}` interpolation works).
`list_skills`/`read_skill` scan **all** roots; `save_skill` writes to the
**first** one — so the order of `:roots` *is* the scope choice: the first root is
where new skills land. Global root = helps every project; add a project root to
grow skills for that project only.

```
skills/<skill-name>/
  skill.edn         ; manifest (required)
  SKILL.md          ; what it does, when to use it, gotchas (required, non-empty)
  resources/        ; optional static data
  scripts/          ; optional tooling
```

## `skill.edn`

```clojure
{:id "prove-it"                      ; REQUIRED — the name list_skills returns
 :title "prove-it"                   ; optional — defaults to :id
 :description "One sentence: what this capability does and when you'd reach for it."  ; REQUIRED
 :tags ["verification" "testing"]}   ; optional
```

Only `:id` and `:description` are required. A manifest missing either is reported
by `list_skills` in its **errors** list — not silently skipped, so check it.

> There is **no** version field, no `:skill/…` namespace, and no cross-skill
> dependency mechanism. An earlier version of this template described
> `:skill/name`, `:skill/version`, `:skill/entry`, `:skill/portable` and
> `:requires-skill [{:skill/name …}]` — none of those appear anywhere in the
> source, and a skill written to that schema is simply not discovered.

## `SKILL.md`

Required and non-empty. Returned in full by `read_skill`, truncated to
`:skills :max-body-chars` when longer. Keep it operational:

```markdown
# <name>

## What it does
One paragraph.

## When to use it
- trigger situations
- not-when caveats

## How it runs
- entry file
- any commands (e.g. bb skills/<name>/scripts/<script>.bb)

## Gotchas
- the things that bite — with the evidence that they bite
```

## Managing skills

In chat: `/skills` (list) and `/skills <id>` (print SKILL.md). As tools:
`list_skills`, `read_skill`, `save_skill` (create/overwrite; needs `id`,
`description` and `skill_md`; first root only), `delete_skill`.

After `save_skill`, call `list_skills` to confirm it was discovered.

## Promotion path

When something done bespoke proves reusable and you can give it a **name, a
trigger and a gotcha list**, promote it in place: write `skills/<name>/`, and
reference the associative store's letters rather than copying them into the skill.
A skill that copies a playbook entry has created a second copy of one fact — and
one of the two will rot.
