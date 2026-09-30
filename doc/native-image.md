# GraalVM native image — the grog backend

`grog.server` (the JSON-RPC backend: stdio + TCP/unix socket) builds to a
standalone native executable with GraalVM `native-image`.

## TL;DR

```bash
GRAALVM_HOME=/usr/lib/jvm/java-25-graalvm scripts/native-image.sh
# → target/native/grog-server   (~75 MB, ~1–2 min)

# smoke test it (deep sequence, not just `projects`):
node scripts/smoke-server.js ./target/native/grog-server

# run it (stdio mode; NB this box exports GROG_SERVER_DAEMON=1 — unset it):
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"projects","params":{}}' \
  | env -u GROG_SERVER_DAEMON GROG_SERVER_NO_SOCKET=1 ./target/native/grog-server
```

## Pieces

| File | Role |
|---|---|
| `deps.edn` `:native` alias | `com.github.clj-easy/graal-build-time` (Clojure build-time init) |
| `src/grog/server/main.clj` | `:gen-class` entry — native-image needs a Java `main`; delegates to `grog.server/-main` |
| `scripts/native-image.sh` | AOT → `native-image` (uses the committed config) |
| `scripts/smoke-server.js` | deep smoke driver — drives `open`/`prompt`/`close`; also generates the agent config |
| `native-image/reachability-metadata.json` | **committed** reflection/resource/JNI config (see "Reproducibility") |

## Why the decoupling (the load-bearing part)

native-image does *static* reachability from the entry point. `grog.server` →
`grog.client.local` → `grog.chat` → `grog.core`, and `core` used to `:require`
**`grog.fs`** (POI + PDFBox + Tess4J + `java.awt`), **`grog.boofcv-pdf`**, and
**`grog.pager`** (→ `grog.image-png`, **Swing**). None is in the server's run
loop, but static reach dragged them all in, and AWT class-init fought the tool
libs' class-init forever (X11 `XToolkit`, `ICC_ColorSpace`, `Point2D`, JNA
`Library$Handler`, JBIG2 …).

**Fix:** `grog.core` references those namespaces **lazily** (`opt-var` /
`opt-call`, i.e. `requiring-resolve` returning nil when absent). The CLI keeps
every tool; the native server's reach is AWT/POI/PDF/Tess4J/BoofCV-free.

**Trade-off:** the native server does **not** advertise the
`read_office_document` / `read_pdf_document` / `ocr_pdf_document` /
`analyze_pdf_line_drawings` tools, nor the `<image-png>` pager. The CLI still
has them. If wanted in a native server, they belong behind `grog-mcp`
(out-of-process), not linked in.

## native-image flags

```
--features=clj_easy.graal_build_time.InitClojureClasses   # init Clojure at build time
--initialize-at-build-time
--initialize-at-run-time=com.sun.jna,com.github.javakeyring,org.jline,org.apache.http
-H:ConfigurationFileDirectories=native-image
-H:+ReportExceptionStackTraces
```

The run-time list is the four libs whose class-init must not run at build time
(JNA cleaner thread; keyring's JNA proxy; JLine's JNA pty; HttpClient's
`NTLMEngineImpl` SecureRandom) — each found with `--trace-object-instantiation=`.

## Reproducibility (important)

native-image only fails **at runtime** for reflective paths the tracing agent
never saw. Driving the agent with just `projects`/`sessions` produces an image
that **crashes on `open`** with:

```
No matching field found: start for class java.lang.Thread
```

So the config must come from a **deep** run (`open` → `prompt`), which
`scripts/smoke-server.js` does. That output (`native-image/reachability-metadata.json`,
~310 KB) is **committed**, so ordinary builds are reproducible and offline.
Regenerate only when dependencies change:

```bash
AGENT=1 scripts/native-image.sh     # needs network + a model key for the prompt
```

## Smoke test — what's actually verified

Run against the native binary (and `clojure -M -m grog.server`, for differential):

| Path | Native | JVM |
|---|:--:|:--:|
| `projects`, `create-project` | ✅ | ✅ |
| `debug/jni` — sqlite-jdbc + java-keyring **JNI** | ✅ | ✅ |
| `open` (spawns ECA child, session) | ✅ | ✅ |
| `connect`, `set-trust`, `prompt` → real LLM reply `"pong"`, `close` | ✅ | ✅ |
| **Socket/TCP daemon** bind + connect + `projects`/`sessions` | ✅ | — |
| **Multi-client fan-out** (2 clients both receive all events) | ✅ | — |

Cold wall time: **native ~2.3 s** vs **JVM ~4.4 s** (JVM figure includes the
`clojure` CLI bootstrap).

> `grog.server` exposes a `debug/jni` method (a build-verification aid) that loads
> sqlite-jdbc and java-keyring and reports whether each works. The smoke driver
> calls it, so `AGENT=1` regeneration records their reflection config — remove it
> only if you also stop needing that coverage.

### Still NOT verified — do not call it production-ready

- ~~sqlite/keyring JNI unverified~~ → **now verified** via the `debug/jni` RPC
  (`sqlite` opens an in-memory DB → `{ok:true, value:42}`; `keyring` reaches the
  OS secret service → `PasswordAccessException` for an unset account). That
  proves the JNI/reflection layer, **not** every call site — the real
  assoc-memory writes / live credential reads are still only indirectly covered.
- `answer-question`, tool-approval, `stop` mid-stream, `set-model` over the wire.
- Multi-client fan-out is verified for events, not for **responses routing under
  concurrency**.
- No real client attach (Swing / Electron) against the native server.
- Long-run stability / memory.
- Built on **GraalVM/JDK 25**; the project otherwise runs **JDK 27**.
- Not wired into `grog-server.service` / packaging.

## systemd — two switchable services (user units)

Both live in `~/.config/systemd/user/` and are **mutually exclusive** (they share
TCP 9640 + MCP base 9700 and own the same project locks), enforced with
`Conflicts=`:

| Unit | Backend | State |
|---|---|---|
| `grog-server.service` | JVM (`clojure -M -m grog.server`) | enabled, active by default |
| `grog-server-native.service` | native image (`~/.local/bin/grog-server`) | disabled, start on demand |

Switch (starting one auto-stops the other — the port/conflict does the work):

```bash
systemctl --user start grog-server-native     # → native (stops the JVM)
systemctl --user start grog-server            # → back to the JVM
systemctl --user status grog-server-native --no-pager | head -6
journalctl --user -u grog-server-native -n 80 # logs (reflection/JNI failures land here)
```

Make one the **boot default** (enable exactly one; enabling both races at boot):

```bash
systemctl --user disable grog-server          && systemctl --user enable grog-server-native
# or back:
systemctl --user disable grog-server-native   && systemctl --user enable grog-server
```

> ⚠️ Switching kills every live session on the current server — including an ECA
> session you might be driving. Do it from a plain terminal.

After a rebuild, refresh the running native service:

```bash
scripts/native-image.sh && install -m0755 target/native/grog-server ~/.local/bin/grog-server
systemctl --user restart grog-server-native
```

Remove the native option entirely:

```bash
systemctl --user disable --now grog-server-native
rm ~/.config/systemd/user/grog-server-native.service && systemctl --user daemon-reload
```

## Caveats / still external

- **`grog-mcp`** is an external `clojure -M:http` subprocess (the native image
  does not bundle MCP tool servers). `eca` and whisper are likewise external.
- **JNI / host libs:** `java-keyring` needs libsecret at runtime;
  `sqlite-jdbc` bundles its own `.so`.
- Config still comes from `~/.config/grog/grog.edn` (not baked in).
- Linux/Bash build; Windows/macOS need their own invocation (same flags) + host libs.
- This box exports `GROG_SERVER_DAEMON=1` (a `grog-server.service`), so any manual
  test of the binary needs `env -u GROG_SERVER_DAEMON …` or it just sits there as
  a daemon.
