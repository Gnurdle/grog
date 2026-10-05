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
| `odoo-instances.edn.example` | grog-odoo | Odoo instances (url/db/user/password/allow-write) | ✅ rename to `odoo-instances.edn` |
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
  `grog.edn` → legacy `~/.config/grog/grog.edn`. There is no `./grog.edn`
  (run-dir) layer — nothing may override the user's config.

## Notes

- Secrets normally live in the **OS keyring** (`/secret set <ACCOUNT> <value>`),
  never in config files. `secrets.edn` is only the headless fallback.
- File paths support a leading `~` (home-relative) in most servers, and
  `${ENV}` / `${ENV:-default}` interpolation in many EDN fields.
- After changing any server config, **restart grog** (MCP tool lists and config
  are snapshotted at session start).

## grog.edn — the rest of the knobs

`grog.edn.example` is kept deliberately short: it mirrors a *working* config, so
you can read it in one go. Everything below is optional and lives in the same
file — add a key when you need it.

```clojure
;; --- the LLM block ---
:llm {:url    "https://openrouter.ai/api/v1"
      :model  "moonshotai/kimi-k3"          ; grog's OWN model, not the chat's
      :temperature 0.7
      :max-tokens 4096
      :max-context-tokens 200000            ; token budget; oldest msgs dropped
      :max-tool-result-chars 50000          ; cap tool-result length
      :conn-timeout-sec 60
      :socket-timeout-sec 300
      :provider-name "OpenRouter · DeepSeek"
      :ollama-host "http://localhost:11434" ; used to list local models
      :extra-payload {:transforms ["middle-out"]}
      :profiles {:local {:url "http://localhost:11434/v1"
                         :model "qwen3:8b"
                         :api-key nil}}}    ; named alternate endpoints

;; --- ECA (the agent) ---
:binary "C:\\Users\\you\\scoop\\shims\\eca.exe"   ; if `eca` isn't on PATH
:eca {:model "openrouter/deepseek/deepseek-v4.1-flash"}
;;   ^ OPTIONAL — there is ONE model by default (:llm :model), used by both
;;     grog's own calls and the agent. Set :eca :model only to run the AGENT on
;;     a different model. Provider-qualified: openrouter/… ollama/… openai/…

;; --- optional features ---
:edn-store {:root "edn-store"}              ; memory_* tools, MCP persistence, /jobs
:chron     {:enabled true
            :tasks [{:id "rss-tech"
                     :every-minutes 240
                     :instruction "Check https://hnrss.org/frontpage and flag the top items."}]}
:projects  {:dir "~/grog-projects" :default "gmail-cleanup"}
:mcp       {:idle-timeout-ms 900000}        ; stop idle MCP servers
:terminal  {:shell "bash"}                  ; terminal + /shell
:imaging   {:tessdata "/usr/share/tesseract-ocr/5/tessdata"}
:jobs      {:max-thread-turns 40}
:secrets   {:accounts [{:account "GITHUB_TOKEN"
                        :description "GitHub PAT for with_api_key"}]}
:soul      {:path "SOUL.md"}
:skills    {:roots ["skills"]}

;; --- voice (push-to-talk) ---
:voice {:enabled true
        :command ["whisper-cli" "-m" "/models/ggml-base.en.bin" "-f" "{wav}" "-nt"]
        :sample-rate 16000
        :max-seconds 60
        ;; press = record, release = transcribe. Avoid alt+SPACE (window menu)
        ;; and ctrl+SPACE (Linux IME toggle).
        :push-to-talk-key "ctrl shift SPACE"}

;; --- appearance (normally edited via the GUI Settings panel) ---
:appearance {:chat {:font-family "Monospaced" :font-size 18
                    :user {:rgb [165 138 25]} :thinking {:rgb [55 165 95]}
                    :answer {:rgb [100 220 255]} :tool-call {:rgb [255 0 255]}}
             :terminal {:font-family "Monospaced" :font-size 18}}
```

Client logs are controlled by **environment variables**, not this file:
`$GROG_LOG` (base path, default `~/grog`), `$GROG_UI_LOG_KEEP` (default 5), and
`$GROG_LOG_WIRE=1` to also record the raw NDJSON driver traffic.