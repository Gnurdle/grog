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

    mkdir -p ~/.local/share/grog/models
    curl -L -o ~/.local/share/grog/models/ggml-base.en.bin \
      https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin

`base.en` (~148 MB) is the quality pick. `tiny.en` (~75 MB) is much faster and
noticeably worse at dictation; `small.en` / `medium.en` are better and bigger.
Built whisper.cpp yourself? `./models/download-ggml-model.sh base.en` drops it in
`~/whisper.cpp/models`, which grog also searches.

## Where the client looks (first hit wins)

1. `$GROG_VOICE_MODEL` — an explicit path; any filename
2. `<app>/models/ggml-*.bin` — if a packager chose to bundle one anyway
3. `<repo>/models/ggml-*.bin` — this directory, running from source
4. `$XDG_DATA_HOME/grog/models/ggml-*.bin` — i.e. `~/.local/share/grog/models`,
   which is what the recipe above fills in. **Not the config home**: that holds
   configuration, and a 148 MB model is not a config file.
5. `~/whisper.cpp/models/ggml-*.bin` — your own whisper.cpp checkout

The searched names are `ggml-base.en.bin`, `ggml-tiny.en.bin`,
`ggml-small.en.bin`, `ggml-base.bin`. When the engine or the model is missing the
toolbar mic is disabled and its tooltip says exactly which — set the env var,
drop the file, or set `$GROG_VOICE_COMMAND` to your own transcriber.

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
