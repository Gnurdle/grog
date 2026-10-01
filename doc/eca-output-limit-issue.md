# Upstream issue: output-limit guard guarantees a 400 on large-context models

The fix belongs in ECA; grog has no lever for it (see "What grog can and cannot
do" below).

## Symptom

A **fresh** chat on a model with a large context window fails on the *first*
message:

```
400 … maximum context length is 1048576 tokens.
(179k in the text, 9k in the tools, 943718 in the output)
```

ECA then reports this as a context overflow and enters prune/auto-compact/retry
theatre, which re-sends the same oversized reservation and 400s again — so the
failure looks like corruption of the conversation rather than an arithmetic
mistake in the output reservation.

## Cause

ECA's `sane-output-limit` (see `models.clj`, ~146–154) rejects a catalogue value
only when `output >= context`. Everything below that is accepted verbatim, and
the accepted value is used as the request's output reservation
(`llm_api.clj` ~256 → `openai_chat.clj` ~568, `max-output-tokens`).

But models.dev publishes `output = 0.9 * context` for several models. Verified:

| model | context | output (models.dev) |
|---|---|---|
| `openrouter/deepseek/deepseek-v4.1-flash` | 1,048,576 | 943,718 |

With a ~179k-token baseline input (system prompt + rule files + tool schemas —
a normal grog profile), the request becomes

```
input (179k) + output (0.9 * context) > context   →   400, every time
```

The guard's `output >= context` test cannot see this: it never accounts for the
*input* that will accompany the reservation.

## Suggested fix

Require headroom rather than a bare comparison — reject a catalogue output value
that leaves no room for input, and fall back to the provider default:

```clojure
;; reject when the reservation cannot coexist with a typical input
(when (and output context (< output (- context 16384)))
  output)
;; else nil -> the 32000 default applies
```

(or whatever the local spelling is; the point is `output >= context - headroom`
→ `nil`, not `output >= context` → `nil`.)

## Related robustness notes

* The catalogue fetch has a short timeout against a ~5.2 MB payload
  (`models.clj` ~20–21); a partial/empty catalogue changes the decision, so the
  guard should be correct for both a present and an absent catalogue.
* Providers differ on whether `max_tokens` / `max_completion_tokens` is honoured;
  a reservation derived from `context` is only meaningful if the provider
  treats it as an upper bound *including* input.

## What grog can and cannot do

* grog's **own** direct OpenAI-compatible chat client
  (`src/grog/core.clj`, `post-chat-stream!`) *does* send `:max_tokens` from
  `:llm :max-tokens`. That path is used by the headless CLI chat — the knob is
  real there.
* The **ECA path** (what the desktop client uses) never sends it:
  `grog.eca/prompt!` accepts only `:chatId :model :agent :variant :trust
  :contexts` (`src/grog/eca.clj` ~492). So on ECA-driven turns `:llm
  :max-tokens` is a genuine no-op — editing it changes nothing, which matches
  the observed "my config edits did nothing".
* Therefore there is **no grog-side lever** for this. The fix belongs upstream.
