#!/usr/bin/env bash
# install-desktop.sh -- install a proper grog launcher for the freedesktop
# application menu + taskbar/dock (KDE/GNOME/XFCE/sway).
#
# Installs the DESKTOP CLIENT (clients/web, Electron). The menu entry runs
# scripts/grog-client, which finds a packaged build first and falls back to
# the dev tree.
#
# Does:
#   1. Installs the app icon into the hicolor icon theme at standard sizes.
#   2. Writes $XDG_DATA_HOME/applications/grog.desktop (Exec=grog-client,
#      StartupWMClass=grog -- the launcher passes `--class=grog` so the window
#      groups under the right icon).
#   3. Validates the .desktop file and refreshes the menu/icon/taskmanager
#      caches so "grog" appears (and updates) immediately.
#
# Usage:   ./install-desktop.sh [--debug] [--uninstall]
set -euo pipefail

DEBUG=0; UNINSTALL=0
for a in "$@"; do
  case "$a" in
    --debug) DEBUG=1 ;;
    --uninstall) UNINSTALL=1 ;;
  esac
done
log() { [ "$DEBUG" = "1" ] && echo "[debug] $*" || true; }

repo="$(cd "$(dirname "$0")" && pwd -P)"
data="${XDG_DATA_HOME:-$HOME/.local/share}"
apps="$data/applications"
icons="$data/icons/hicolor"
desktop="$apps/grog.desktop"
icon_src="$repo/clients/web/build/icon.png"
[ -f "$icon_src" ] || icon_src="$repo/icon.png"

if [ "$UNINSTALL" = 1 ]; then
  rm -f "$desktop"
  rm -f "$icons"/*/apps/grog.png "$icons/scalable/apps/grog.png"
  command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$apps" >/dev/null 2>&1 || true
  echo "Removed grog launcher."
  exit 0
fi

mkdir -p "$apps" "$icons"
chmod +x "$repo/scripts/grog-client" 2>/dev/null || true

# --- 1. hicolor icon theme ----------------------------------------------------
if [ -f "$icon_src" ]; then
  if command -v magick >/dev/null 2>&1; then IMG=magick; else IMG=convert; fi
  for s in 16 22 24 32 48 64 128 256 512; do
    d="$icons/${s}x${s}/apps"; mkdir -p "$d"
    "$IMG" "$icon_src" -background none -resize "${s}x${s}" -gravity center \
           -extent "${s}x${s}" "$d/grog.png" 2>/dev/null
  done
  mkdir -p "$icons/scalable/apps"
  cp "$icon_src" "$icons/scalable/apps/grog.png"
  echo "Icon theme installed -> $icons"
else
  echo "WARNING: no icon found ($icon_src); launcher will use a default icon."
fi

# --- 2. grog.desktop ----------------------------------------------------------
# Exec points at the launcher, Icon at the absolute PNG (so it never depends on
# the icon-theme cache), StartupWMClass=grog matches the client's --class.
cat > "$desktop" <<EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=grog
GenericName=AI Chat Assistant
Comment=AI coding assistant with on-machine tools (files, shell, office, OCR)
Exec=$repo/scripts/grog-client
Icon=$icon_src
StartupWMClass=grog
Terminal=false
StartupNotify=true
Categories=Development;
Keywords=grog;ai;chat;llm;assistant;coder;
EOF
echo "Wrote: $desktop"
log "$(cat "$desktop")"

# --- 3. verify ----------------------------------------------------------------
if command -v desktop-file-validate >/dev/null 2>&1; then
  if desktop-file-validate "$desktop" 2>&1; then echo "Validation: OK"
  else echo "Validation: FAILED — see above." >&2; fi
fi

# --- 4. refresh caches --------------------------------------------------------
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$apps" >/dev/null 2>&1 || true
command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -f -t "$icons" >/dev/null 2>&1 || true
if command -v kbuildsycoca6 >/dev/null 2>&1; then kbuildsycoca6 >/dev/null 2>&1 || true
elif command -v kbuildsycoca5 >/dev/null 2>&1; then kbuildsycoca5 >/dev/null 2>&1 || true; fi

echo
echo "Installed the grog launcher (menu + taskbar). Launch:"
echo "    $repo/scripts/grog-client --console"
echo "or search the applications menu for \"grog\"."
echo "Uninstall:  $0 --uninstall"
