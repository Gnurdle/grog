# Speech models (voice input)

grog's voice feature transcribes **locally** with [whisper.cpp]: the web client
runs `whisper-cli` and needs a **ggml model file**. grog does not bundle one by
default (they are 30–150 MB), so **voice is off until a model is available** —
this is why a fresh / distro install shows the mic greyed out with
`speech model not found: …`.

Drop a model in here and it is **packaged into the app** (electron-builder
`extraResources` → `models/`), so a distro install works with no user setup:

    resources/models/ggml-base.en.bin   # ~148 MB — good English quality
    resources/models/ggml-tiny.en.bin   # ~75 MB  — faster / dumber

Fetch one from whisper.cpp:

    ./models/download-ggml-model.sh base.en

## Where the client looks (first hit wins)

1. `$GROG_VOICE_MODEL` — an explicit path
2. `<config home>/models/ggml-*.bin` — e.g. `~/.config/grog/models/`
3. `<app>/models/ggml-*.bin` — this directory, once packaged (AppImage/NSIS)
4. `<repo>/resources/models/ggml-*.bin` — this directory, running from source

`whisper-cli` must also be on `PATH` (or point `$GROG_VOICE_COMMAND` at your own
transcriber). When either the engine or the model is missing, the toolbar mic is
disabled and its tooltip says exactly which — set the env var, drop the file, or
`$GROG_VOICE_COMMAND`.

**Nothing here is committed:** `*.bin` is gitignored (see the repo `.gitignore`).
This README is the only tracked file in this directory; it exists so the
`extraResources` source path is always present at build time.

[whisper.cpp]: https://github.com/ggerganov/whisper.cpp
