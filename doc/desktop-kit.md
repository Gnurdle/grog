# Desktop integration

How grog becomes an app on the system: an icon in the menu, dock or taskbar that
launches it. Brings up the client without a terminal and without a hand-typed
command; the client writes its own per-instance log (`<base>.<pid>.log`).

There are two install paths, and both use the same launcher and icon:

* **Packaged** — `bb dist` → electron-builder → NSIS (Windows) / AppImage and a
  `.desktop` entry (Linux). The installer creates the shortcuts itself.
* **From a source tree** — `./install-desktop.sh` (Linux) or
  `scripts\install-desktop.ps1` (Windows) create the same entries pointing at
  the in-tree launcher.

## Files

| File | Role |
|---|---|
| `clients/web/build/icon.png` | 512×512 master icon |
| `clients/web/build/icon.ico` | multi-size Windows icon (16/32/48/64/128/256) |
| `scripts/grog-client` | Linux launcher — the `.desktop` `Exec=` |
| `scripts/grog-client.bat` | Windows launcher — the shortcut target |
| `install-desktop.sh` | Linux: installs the icon theme and the `.desktop` entry |
| `scripts/install-desktop.ps1` | Windows: creates the Desktop and Start Menu shortcuts |
| `clients/web/package.json` (`build`) | electron-builder: icon, shortcut options, `.desktop` fields |

Regenerate the icons from any source PNG:

```sh
magick icon.png -background none -gravity center -extent 512x512 clients/web/build/icon.png
magick clients/web/build/icon.png -define icon:auto-resize=256,128,64,48,32,16 clients/web/build/icon.ico
```

`clients/web/build/` is committed — electron-builder reads the icons from there.

## Linux

### AppImage — it installs itself (no helper needed)

Running the AppImage, grog offers once, on first launch:

```
Add grog to your application menu?
```

Say yes and it writes its own entry + icon and refreshes the caches — **no
AppImageLauncher, no appimaged, no hand-written `.desktop`**. It can do this
because at runtime it knows the one thing those helpers exist for: its own
absolute path (`$APPIMAGE`).

```sh
# what it writes
~/.local/share/applications/grog.desktop
~/.local/share/icons/hicolor/512x512/apps/grog.png
```

The entry is marked `X-Grog-Managed=true` and carries an **absolute** `Exec=`:

```ini
Exec="/path/to/grog-<version>-x86_64.AppImage" --no-sandbox --class=grog %U
```

- **It follows the file.** Move or rename the AppImage and the next launch
  re-points the entry automatically.
- **It takes over older copies.** An entry written by an earlier grog (no
  marker, `Exec=AppRun`) is re-pointed to the running one rather than ignored.
- It never touches a `grog.desktop` that isn't grog's.

Say "Not now" and it writes `~/.config/grog/.desktop-prompt-declined` so you are
asked exactly once; delete that file to be asked again. `GROG_DESKTOP_INSTALL=1`
installs without asking, `=0` never asks (for scripted setups).

### From a source tree

```sh
./install-desktop.sh              # install
./install-desktop.sh --debug      # print the generated entry
./install-desktop.sh --uninstall  # remove
```

It installs the icon into the hicolor theme at 16–512 px (plus a scalable copy),
writes `~/.local/share/applications/grog.desktop`, validates it, and refreshes
the menu, icon and task-manager caches. Its `Exec=` is `scripts/grog-client`,
which prefers a packaged build (`dist/linux-unpacked/grog`, then the newest
`dist/*.AppImage`) and falls back to the dev tree — so it is tied to the
checkout, unlike the AppImage's self-install above.

Both use `StartupWMClass=grog` and start the app with `--class=grog`, so the
window is grouped under the grog icon in the dock and taskbar.

## Windows

```powershell
powershell -ExecutionPolicy Bypass -File scripts\install-desktop.ps1
powershell -ExecutionPolicy Bypass -File scripts\install-desktop.ps1 -Uninstall
```

Creates `%USERPROFILE%\Desktop\grog.lnk` and
`%APPDATA%\Microsoft\Windows\Start Menu\Programs\grog.lnk`, both launching
`scripts\grog-client.bat` with `clients\web\build\icon.ico`.

The packaged installer does the same, per user, and lets the user pick the
directory (`clients/web/package.json`):

```json
"win":  { "target": ["nsis"], "icon": "build/icon.ico" },
"nsis": { "oneClick": false, "perMachine": false,
          "allowToChangeInstallationDirectory": true,
          "createDesktopShortcut": true, "createStartMenuShortcut": true,
          "shortcutName": "grog" }
```

## What the launchers do

`scripts/grog-client` and `scripts/grog-client.bat` behave the same way:

1. Run a packaged build if there is one (`dist/linux-unpacked/grog`,
   `dist\win-unpacked\grog.exe` — both under the repo-root `dist/`); otherwise
   run from `clients/web`.
2. Put `java` and `bash` on PATH — the backend is started with `java`, and each
   tool server is started through `bash`. On Windows the launcher also adds the
   JDK `bin` directory, which is not on PATH by default.
3. Set `NODE_ENV=production` and `GROG_MCP_BASE_PORT`; set `GROG_DISABLE_GPU=1`
   when there is no display.
4. Write a fresh log file each run, and show its tail if the app exits with an
   error. A fresh name each time matters on Windows: if a stale process holds
   the log file open, the launcher cannot redirect into it and the app would not
   start at all. Separately, the client always writes its own per-instance log
   (`<base>.<pid>.log`; `$GROG_LOG` / `$GROG_UI_LOG_KEEP`) — that file, not the
   launcher's copy, is the primary place to look (see the quick-start guides).

## Verifying an install

| Check | Linux | Windows |
|---|---|---|
| entry exists | `desktop-file-validate ~/.local/share/applications/grog.desktop` | `Get-Item "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\grog.lnk"` |
| icon installed | `ls ~/.local/share/icons/hicolor/*/apps/grog.png` | shortcut → Properties → Change Icon |
| launches | `scripts/grog-client --console` | double-click the shortcut |
| window uses the grog icon | dock shows it while running | — |

If the window appears but the dock shows a generic icon on Linux, the launcher
did not pass `--class=grog`, or the entry's `StartupWMClass` does not say `grog`.
