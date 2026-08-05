#!/usr/bin/env bash
# install-desktop.sh — install a proper grog launcher for the freedesktop
# application menu + taskbar (KDE/GNOME/XFCE).
#
# Does:
#   1. Installs the app icon into the hicolor icon theme at standard sizes.
#   2. Writes $XDG_DATA_HOME/applications/grog.desktop with Icon=grog and
#      StartupWMClass=clojure-main so a pinned taskbar entry shows the icon.
#   3. Validates the .desktop file and refreshes the menu / icon / taskmanager
#      caches so "grog" appears (and updates) immediately.
#
# Usage:   ./install-desktop.sh [--debug]
# Note:    If your desktop sees this repo under a different mount, set repo=
#          below to that path.
set -euo pipefail

DEBUG=0
if [ "${1:-}" = "--debug" ]; then DEBUG=1; fi
log() { if [ "$DEBUG" = "1" ]; then echo "[debug] $*"; fi; }

repo="$(cd "$(dirname "$0")" && pwd -P)"
# repo="/mnt/d/gni/grog"   # <-- uncomment if desktop uses a different mount

data="${XDG_DATA_HOME:-$HOME/.local/share}"
apps="$data/applications"
icons="$data/icons/hicolor"
mkdir -p "$apps" "$icons"
desktop="$apps/grog.desktop"

chmod +x "$repo/grog-ui" 2>/dev/null || true

# --- 1. hicolor icon theme ----------------------------------------------------
src="$repo/icon.png"
if [ -f "$src" ]; then
  # ImageMagick 7 uses `magick`; fall back to the deprecated `convert`.
  if command -v magick >/dev/null 2>&1; then IMG=magick; else IMG=convert; fi
  for s in 16 22 24 32 48 64 128 256 512; do
    d="$icons/${s}x${s}/apps"; mkdir -p "$d"
    "$IMG" "$src" -background none -resize "${s}x${s}" -gravity center \
           -extent "${s}x${s}" "$d/grog.png" 2>/dev/null
  done
  mkdir -p "$icons/scalable/apps"
  cp "$src" "$icons/scalable/apps/grog.png"
  if [ ! -f "$icons/index.theme" ]; then
    cat > "$icons/index.theme" <<'EOF'
[Icon Theme]
Name=hicolor
Comment=Fallback icon theme
Inherits=hicolor
Directories=16x16/apps,22x22/apps,24x24/apps,32x32/apps,48x48/apps,64x64/apps,128x128/apps,256x256/apps,512x512/apps,scalable/apps
EOF
  fi
  echo "Icon theme installed -> $icons"
else
  echo "WARNING: $repo/icon.png not found; launcher will fall back to a default icon."
fi

# --- 2. grog.desktop ----------------------------------------------------------
cat > "$desktop" <<EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=grog
GenericName=AI Chat Assistant
Comment=Grog AI chat with an integrated shell window
Exec=$repo/grog-ui
Icon=grog
StartupWMClass=clojure-main
Terminal=false
StartupNotify=false
Categories=Development;
Keywords=grog;ai;chat;llm;assistant;
EOF
echo "Wrote: $desktop"
log "$(cat "$desktop")"

# --- 3. verify ----------------------------------------------------------------
if command -v desktop-file-validate >/dev/null 2>&1; then
  if desktop-file-validate "$desktop" 2>&1; then
    echo "Validation: OK"
  else
    echo "Validation: FAILED — see above; the menu entry may not be accepted." >&2
  fi
fi

# --- 4. refresh caches --------------------------------------------------------
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$apps" >/dev/null 2>&1 || true
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
  gtk-update-icon-cache -f -t "$icons" >/dev/null 2>&1 || true
fi
if command -v gio >/dev/null 2>&1; then
  gio mime x-scheme-handler/grog >/dev/null 2>&1 || true
fi
# KDE menu database
if command -v kbuildsycoca6 >/dev/null 2>&1; then kbuildsycoca6 >/dev/null 2>&1 || true
elif command -v kbuildsycoca5 >/dev/null 2>&1; then kbuildsycoca5 >/dev/null 2>&1 || true
elif command -v kbuildsycoca4 >/dev/null 2>&1; then kbuildsycoca4 >/dev/null 2>&1 || true; fi

echo
echo "Installed grog launcher. To launch now:"
echo "    $repo/grog-ui"
echo "From the applications menu, search for \"grog\"."
echo "If a pinned taskbar entry shows a stale icon: Unpin, then re-pin," 
echo "or restart the taskbar (Alt+F2 -> 'kquitapp5 plasmashell && plasmashell')."
