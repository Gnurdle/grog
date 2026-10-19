# Speech models (voice input)

grog's voice feature transcribes **locally** with [whisper.cpp]: the web client
runs `whisper-cli` and needs a **ggml model file**. **grog ships neither piece.**
The engine is your package manager's job, and the model is yours — because no
package manager provides one (Arch's `whisper-cpp` is engine + libraries only,
and scoop's manifest says it outright: *"Whisper Model files are not included"*).

| Piece | Where it comes from | Size |
|---|---|---|
| `whisper-cli` — the engine | your package manager: `sudo pacman -S whisper-cpp` (Arch), `scoop install whisper-cpp` (Windows) | ~1 MB |
| a `ggml-*.bin` — the model | **you**, with the command below | 75–150 MB |

## Get a model

**Put it anywhere on your `PATH`** — that is the default search, no configuration
needed. `~/.local/bin` is the usual per-user choice (it is already on `PATH` on
most Linux setups); anywhere else already on `PATH` works just as well, including
the directory the engine itself lives in:

    curl -L -o ~/.local/bin/ggml-base.en.bin \
      https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin

`base.en` (~148 MB) is the quality pick. `tiny.en` (~75 MB) is much faster and
noticeably worse at dictation; `small.en` / `medium.en` are better and bigger.
Built whisper.cpp yourself? `./models/download-ggml-model.sh base.en` drops it in
`~/whisper.cpp/models`, which grog also searches.

Any file named `ggml-*.bin` is recognised — it does not have to be one of the
names above.

## Where the client looks (first hit wins)

1. `$GROG_VOICE_MODEL` — an explicit path, if you want to pin one
2. **every directory on `PATH`** — the default, and the first place it looks
3. `$XDG_DATA_HOME/grog/models/ggml-*.bin` — i.e. `~/.local/share/grog/models`
4. `<app>/models/ggml-*.bin` — if a packager chose to bundle one anyway
5. `<repo>/models/ggml-*.bin` — this directory, running from source
6. `~/whisper.cpp/models/ggml-*.bin` — your own whisper.cpp checkout

The engine is on `PATH`, so a model dropped beside `whisper-cli` is found with
**no configuration at all**. When the engine or the model is missing the toolbar
mic is disabled and its tooltip says exactly which — put the file on `PATH`, or
set `$GROG_VOICE_COMMAND` to your own transcriber.

## Why this directory exists

It is a scratch spot for a **source tree** — `bb dist` fetches nothing into it and
the installers bundle no model. It is deliberately NOT under `resources/`: that
is a **classpath root** (`build.clj` copies `src` and `resources` into the spine
jar), so a 148 MB `.bin` in there gets baked into `target/grog-spine.jar`. On
2026-10-13 exactly that happened: the model shipped twice — once inside the jar —
and the installer went 282 MB → 550 MB for a feature most users never click.

`*.bin` is gitignored (see the repo `.gitignore`); this README is the only
tracked file here, and it exists so the directory itself survives a clean clone.

[whisper.cpp]: https://github.com/ggerganov/whisper.cpp
