# Grog Session State

## Date
2026-01-20 (current in-project year)

## What was being worked on
User reported that assistant output in Grog was losing line breaks inside code blocks. During investigation we ended up doing a larger cleanup:

1. **Removed the Ollama-native `/api/chat` path**
   - Deleted `src/grog/image_analysis.clj` (unused Ollama vision tool).
   - Removed Ollama-specific streaming, tool-call extraction, config detection, and model-list probing from `src/grog/core.clj` and `src/grog/config.clj`.
   - Chat now always uses the OpenAI-compatible `/v1/chat/completions` path.
   - Config key changed from `:ollama` to `:llm`.

2. **Added raw-response debug logging**
   - New config flag `:llm {:debug-response true}` prints the accumulated raw response buffer before rendering.

3. **Fixed inline-code line-break collapse**
   - Added `rewrite-single-backtick-code-with-newlines` in `src/grog/md_render.clj`.
   - Single-backtick inline code spans at the start of a line that contain newlines are rewritten as fenced code blocks before CommonMark parsing.
   - This works around models that misuse single backticks for multi-line code.

4. **Added multi-line paste support**
   - Enabled JLine `BRACKETED_PASTE` in `src/grog/readline.clj` so pasted blocks with newlines stay in the buffer until Enter.
   - Added `/paste` chat command as an explicit fallback multi-line input mode.

5. **Updated docs**
   - `README.md` and `resources/grog.edn.example` now describe OpenAI-compatible-only config, `/paste`, and `:debug-response`.

6. **Renamed internal `openai-*` function prefixes to `llm-*` / `sse-*`**
   - `openai-merge-tool-call-chunks` → `llm-merge-tool-call-chunks`
   - `openai-tool-calls-from-accum` → `llm-tool-calls-from-accum`
   - `openai-sse-line->json` → `sse-line->json`
   - `extract-openai-delta` → `extract-llm-delta`

7. **Replaced remaining "Ollama" terminology in docstrings**
   - Updated namespace docstrings and function docstrings across `fs.clj`, `memory_tools.clj`, `oracle.clj`, `chron.clj`, `chat_context.clj`, `skills.clj`, `mcp.clj`, and `brave.clj`.
   - All references now use neutral "LLM" or generic phrasing.

8. **Fixed newline stripping in streamed content deltas**
   - `handle-delta!` in `core.clj` used `(not (str/blank? content))` which discarded whitespace-only deltas — including newlines that the model streamed as separate tokens.
   - Changed the guards to `(seq content)` and `(seq thinking)` so whitespace-only deltas (notably `\n`) are preserved in the buffer and printed during live streaming.
   - This fixes fenced code blocks and all other multi-line output that was collapsing into a single line.

9. **Added context budget management**
   - New config options `:llm {:max-context-tokens N}` and `:llm {:max-tool-result-chars N}`.
   - `trim-messages-to-budget` (in `core.clj`) keeps system messages and drops oldest non-system messages when the rough token estimate exceeds the budget. Prints a warning to stderr when trimming occurs.
   - `tool-result-messages` now truncates individual tool outputs that exceed `:max-tool-result-chars`, appending a "[grog: tool result truncated...]" note.
   - This prevents the "maximum context length exceeded" errors when long tool outputs accumulate across chat rounds.

10. **Added provider-specific extra-payload passthrough**
    - New config option `:llm {:extra-payload {…}}` — deep-merged into every `/v1/chat/completions` request payload after standard fields.
    - This lets you use OpenRouter's context compression plugin: `{:extra-payload {:transforms ["middle-out"]}}`.
    - The provider intelligently compresses the prompt instead of throwing a 400 error.

11. **Set sensible defaults for context protection**
    - `max-context-tokens` now defaults to **200000** (was nil). This automatically drops oldest non-system messages before each request to stay under common 256K–262K provider limits. Users can override in grog.edn or set to `nil` to disable.
    - `max-tool-result-chars` now defaults to **50000** (was nil). This automatically truncates oversized individual tool results with a note, preventing a single massive file read from blowing the context window. Users can override or set to `nil` to disable.
    - Combined, these defaults mean Grog works safely with large context models (e.g. 256K–262K) out of the box without manual configuration.

## Files modified
- `README.md`
- `resources/grog.edn.example`
- `src/grog/config.clj`
- `src/grog/core.clj`
- `src/grog/image_analysis.clj` (deleted)
- `src/grog/md_render.clj`
- `src/grog/readline.clj`
- `src/grog/secrets.clj` (added `LLM_API_KEY` as a known secret)
- `src/grog/fs.clj`
- `src/grog/memory_tools.clj`
- `src/grog/oracle.clj`
- `src/grog/chron.clj`
- `src/grog/chat_context.clj`
- `src/grog/skills.clj`
- `src/grog/mcp.clj`
- `src/grog/brave.clj`

## Verification status
- `clojure -M -e "(require 'grog.core 'grog.readline 'grog.md-render)"` compiles.
- `clojure -M:run help` runs and prints updated help text.
- Manual test confirmed the user's original malformed output now renders code blocks with preserved line breaks.
- Post-rename compilation verified for all affected namespaces.
- Context trimming and tool result truncation compile cleanly.

12. **Added new file exploration tools**
    - `read_workspace_file` now accepts an `offset` parameter for chunked byte-range reading. Returns `has_more`, `offset`, `bytes_read`, and `size_bytes` so you can page through large files.
    - `grep_workspace_file` — regex search inside a single file, returns matching lines with line numbers. Supports `case_sensitive` and `max_results`.
    - `stat_workspace_file` — returns `size_bytes`, `line_count`, and `newline_type` (LF or CRLF) to plan chunk sizes.
    - `read_workspace_file_lines` — line-based extraction with `start_line` and `max_lines`. Returns lines with line numbers and `has_more`.
    - All new tools are registered in `chat-tools-payload` and `execute-tool-call!` dispatch in `core.clj`.
    - Fixed a paren/brace mismatch introduced while adding the new tool specs in `fs.clj`; compilation now passes.

## Verification status
- `clojure -M -e "(require 'grog.core 'grog.readline 'grog.md-render 'grog.fs)"` compiles.
- `clojure -M:run help` runs and prints updated help text.
- Manual test confirmed the user's original malformed output now renders code blocks with preserved line breaks.
- Post-rename compilation verified for all affected namespaces.
- Context trimming and tool result truncation compile cleanly.
- New file tools (`grep_workspace_file`, `stat_workspace_file`, `read_workspace_file_lines`, and updated `read_workspace_file`) compile cleanly.

## Open / possible next steps
- Test actual chat round-trip against a real OpenAI-compatible endpoint.
- Evaluate `:chat-stream-live-markdown` on real model output (prose, code blocks, tables, lists, blockquotes).
- Test `/model <profile>` and `/model <model-name>` against live endpoints.
- Any further polish or features the user wants to add.

14. **Added on-the-fly LLM model/profile switching**
    - `grog.config` now holds a session-scoped `:llm` override map (`set-llm-override!` / `clear-llm-override!`) deep-merged on top of file-based `:llm` config via `effective-llm-cfg`.
    - All `:llm` getters (`llm-url`, `llm-model`, `llm-api-key`, `llm-max-tokens`, `llm-temperature`, `llm-debug-payload?`, `llm-debug-response?`, `max-context-tokens`, `max-tool-result-chars`, `llm-extra-payload`, `provider-name`) read from the effective config.
    - New chat commands: `/model` (show current + profiles), `/model reset`, `/model <model-name>`, `/model <profile>`.
    - Profiles can be defined under `:llm :profiles` in `grog.edn`, each overriding `:url`, `:model`, `:api-key`, etc. A profile can set `:api-key nil` to explicitly suppress the base API key/keyring lookup (useful for local Ollama).
    - Updated `help-text`, `README.md`, `resources/grog.edn.example`, and `state.md`.

## Files modified
- `README.md`
- `resources/grog.edn.example`
- `src/grog/config.clj`
- `src/grog/core.clj`
- `src/grog/md_stream.clj` (new)
- `state.md`

13. **Added block-wise streaming Markdown rendering option**
    - New namespace `grog.md-stream` buffers incoming Markdown and emits completed blocks (paragraphs, headings, tables after terminator, fenced code blocks after closing fence) through `grog.md-render/render-to-ansi`.
    - New config flag `:cli {:chat-stream-live-markdown true}` (default `false`).
    - Wired into `grog.core/post-chat-stream!`: content deltas feed the streamer; `finish!` flushes any trailing tail and suppresses the regular single-round markdown render when streaming occurred.
    - Updated `help-text`, `README.md`, and `resources/grog.edn.example` to document the flag.
    - Limitations: list continuations and blockquotes across blank lines may split; tables still wait for a blank line because GFM column widths require the full table.

## Files modified
- `README.md`
- `resources/grog.edn.example`
- `src/grog/config.clj`
- `src/grog/core.clj`
- `src/grog/md_stream.clj` (new)
- `state.md`
