# Building a skill — template

A skill is a **small, self-contained, reusable capability**: a manifest (`skill.edn`),
a short guide (`SKILL.md`), and optionally resources/scripts. Skills live under
`skills/<skill-name>/`.

## Directory layout

```
skills/<skill-name>/
  skill.edn         ; manifest (required)
  SKILL.md          ; what it does, when to use it, gotchas (required)
  resources/        ; optional static data
  scripts/          ; optional tooling
```

## `skill.edn`

```clojure
{:skill/name "your-skill-name"
 :skill/version 1
 :skill/kind :skill
 :skill/description "One sentence: what this capability does and when you'd reach for it."
 :skill/entry "SKILL.md"          ; or the primary file the model should read
 :skill/portable true}            ; false if site-specific (non-portable)
```

- `:skill/name` — lowercase-hyphen, unique within the registry
- `:skill/version` — bump on any behavioral change; projects depend on this
- `:skill/portable` — false for skills that cannot travel (decided case-by-case)

## `SKILL.md`

Keep it short and operational:

```markdown
# <name>

## What it does
One paragraph.

## When to use it
- trigger phrases / situations
- not-when caveats

## How it runs
- entry file
- any commands

## Gotchas
- the things that bite (calibrations included)
```

## Declaring the dependency from a project

```clojure
;; in project.edn
:requires-skill [{:skill/name "your-skill-name" :skill/version 1}]
```

## Promotion path

When a project's core proves reusable and its site-specifics are separated out, you
promote it in place: extract the core to `skills/<name>/`, declare the dependency, bump
the version. That's the whole lifecycle — no skills-of-skills.