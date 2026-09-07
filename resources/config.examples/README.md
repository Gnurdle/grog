# grog config examples — starter bundle

Prototype config files for a **fresh grog install**. Copy the **whole
directory** into your config home and start hacking:

```bash
# Linux / macOS / Windows (same path on every OS)
mkdir -p ~/.config/grog
cp config.examples/* ~/.config/grog/
```

On Windows that expands to `C:\Users\you\.config\grog\`.

## What each file is for

| File | Read by | Purpose | Copy it? |
|------|---------|---------|----------|
| `grog.edn.example` | grog core | main config: model, LLM, chron, appearance | ✅ rename to `grog.edn` and edit |
| `imaging.edn.example` | grog-imaging | Tesseract `tessdata` dir for OCR tools | ✅ rename to `imaging.edn` |
| `gitlab.edn.example` | grog-gitlab | GitLab instance(s) config | ✅ rename to `gitlab.edn` |
| `gitlab-instances.edn.example` | grog-gitlab | multi-instance list (token-file paths) | ✅ rename to `gitlab-instances.edn` |
| `odoo-instances.edn.example` | grog-odoo | Odoo instances (url/db/user/password/sql) | ✅ rename to `odoo-instances.edn` |
| `imap-accounts.edn.example` | grog-imap | IMAP account metadata (never secrets) | ✅ rename to `imap-accounts.edn` |
| `secrets.edn.example` | grog core | OS-keyring **fallback** file (owner-only) | ⚠️ generated automatically; only needed if you have no keyring |

## Do NOT copy these

The files below are **generated** by grog, not hand-edited. Copying a stale
example can confuse the runtime — delete them from `~/.config/grog` instead:

- `eca-config.generated.json` — ECA config built from `grog.edn` at startup
- `approved-tools.edn` — tool-approval decisions
- `mem.db` / `global-mem.db` — per-project + global memory stores
- `servers.edn` / `tools-cache.edn` — MCP server/tool registry (under the EDN store)

## Config home & merge order (grog core)

- Location: `${XDG_CONFIG_HOME:-~/.config}/grog/grog.edn` on every OS
  (Windows uses the same `~/.config/grog`), or override with `$GROG_CONFIG_HOME`.
- Merge order, later wins: classpath `resources/grog.edn` → config-home
  `grog.edn` → legacy `~/.config/grog/grog.edn` → `./grog.edn` in the run dir.

## Notes

- Secrets normally live in the **OS keyring** (`/secret set <ACCOUNT> <value>`),
  never in config files. `secrets.edn` is only the headless fallback.
- File paths support a leading `~` (home-relative) in most servers, and
  `${ENV}` / `${ENV:-default}` interpolation in many EDN fields.
- After changing any server config, **restart grog** (MCP tool lists and config
  are snapshotted at session start).