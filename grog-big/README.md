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

## Configuration (file)

`~/.config/grog/big.edn`:

```clojure
{:url "http://localhost:4000/v1"          ;; OpenAI-compatible base URL (LiteLLM relay)
 :model "big"                             ;; model name on that endpoint
 :api-key-file "~/.config/grog/keys/grog-big.key"}  ;; bearer key (optional)
```

Defaults: `http://localhost:4000/v1`, `big`, `sk-dummy`. (Env vars are
intentionally **not** read — file-config normalization, like the other servers.)

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
   (`grog-mcp-servers` in `grog.eca-config`); the server reads its settings from
   `~/.config/grog/big.edn`.

3. The local agent sees `big_model_ask` as a tool and per SOUL.md will escalate
   hard tasks to it. You can also call it from the GUI/console if you want to
   force the big model on a prompt.

## Testing without ECA

```bash
clojure -M:mcp -m grog-big.main   # reads ~/.config/grog/big.edn; speak MCP over stdio
```

Or point it straight at any OpenAI-compatible endpoint (e.g. Ollama) to smoke-test:

```bash
GROG_BIG_URL=http://localhost:11434/v1 GROG_BIG_MODEL=qwen3.5:4b \
clojure -M -e '(require (quote grog-big.main)) (println ((var-get (quote grog-big.main/run-ask!)) {:prompt "Say hi"}))'
```