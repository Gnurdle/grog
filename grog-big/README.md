# grog-big — the big model as a tool

**Pattern:** *local orchestrator, remote specialist.*

Your default agent runs a small, fast local model (e.g. Qwen3 8B on the RTX
4070 Max-Q). That's great for the everyday tool loop, but for hard problems a
bigger cloud model (DeepSeek via OpenRouter/LiteLLM) is higher quality. This
MCP server turns that big model into an ordinary tool the local agent can
**call on demand** — the same way it calls `run_babashka` or
`brave_web_search`.

## Tool

`big_model_ask(prompt, [system], [max_tokens])`

- **prompt** (required) — a **self-contained** prompt. The big model does **not**
  see the conversation; include all context the answer needs.
- **system** (optional) — override the default specialist identity.
- **max_tokens** (optional) — cap the response length in tokens.

Returns the big model's text (falls back to its `reasoning` field for models
that emit reasoning traces).

## Environment

| Var | Default | Meaning |
|---|---|---|
| `GROG_BIG_URL` | `http://localhost:4000/v1` | OpenAI-compatible base URL (LiteLLM relay) |
| `GROG_BIG_MODEL` | `big` | Model name on that endpoint |
| `GROG_BIG_API_KEY` | `sk-dummy` | Bearer key (matches the default LiteLLM master key) |

## Quick start (with LiteLLM as the relay)

1. Run a relay that routes a model named `big` to the big cloud model:

   ```yaml
   # litellm-config.yaml
   model_list:
     - model_name: big
       litellm_params:
         model: openrouter/deepseek/deepseek-v4-flash-0731
         api_key: os.environ/OPENROUTER_API_KEY
   litellm_settings:
     drop_params: true
   ```

   ```bash
   export OPENROUTER_API_KEY=sk-or-...
   litellm --config ./litellm-config.yaml --port 4000
   ```

2. grog wires `grog-big` into the ECA config automatically
   (`grog-mcp-servers` in `grog.eca-config`), passing the env vars above.

3. The local agent sees `big_model_ask` as a tool and per SOUL.md will escalate
   hard tasks to it. You can also call it from the GUI/console if you want to
   force the big model on a prompt.

## Testing without ECA

```bash
GROG_BIG_URL=http://localhost:4000/v1 GROG_BIG_MODEL=big \
clojure -M:mcp -m grog-big.main   # then speak MCP over stdio
```

Or point it straight at any OpenAI-compatible endpoint (e.g. Ollama) to smoke-test:

```bash
GROG_BIG_URL=http://localhost:11434/v1 GROG_BIG_MODEL=qwen3.5:4b \
clojure -M -e '(require (quote grog-big.main)) (println ((var-get (quote grog-big.main/run-ask!)) {:prompt "Say hi"}))'
```