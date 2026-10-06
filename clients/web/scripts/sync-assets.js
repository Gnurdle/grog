// sync-assets.js — keep the renderer's SERVED image assets in sync with the
// repo-root sources.
//
// Why two copies exist: the splash image's SOURCE is <repo>/logo.jpg, but an
// Electron renderer can only load files from its own static root
// (clients/web/resources/public/), so the served copy has to be a real file
// there. Nothing linked the two, so they silently drifted — the served copy was
// still the old 1248x832 art after the new 1728x1152 logo was planted at the
// root. This script makes the served copy a PRODUCT of the source.
//
// Deliberately NOT synced: clients/web/assets/icon.{png,ico} — those are the
// application/packaging icons (electron-builder `icon`), a different asset set.
//
// Never fails a build: a missing source is a warning, not an error.
"use strict";
const fs = require("fs");
const path = require("path");

const webRoot = path.resolve(__dirname, "..");
const repoRoot = path.resolve(webRoot, "..", "..");

// [source, relative to <repo>] -> [destination, relative to <web>]
const ASSETS = [
  ["logo.jpg", "resources/public/logo.jpg"],
];

for (const [from, to] of ASSETS) {
  const src = path.join(repoRoot, from);
  const dst = path.join(webRoot, to);
  try {
    if (!fs.existsSync(src)) {
      console.warn(`[sync-assets] no source ${src} — leaving ${to} untouched`);
      continue;
    }
    const next = fs.readFileSync(src);
    const cur = fs.existsSync(dst) ? fs.readFileSync(dst) : null;
    if (cur && cur.equals(next)) {
      console.log(`[sync-assets] ${to} up to date`);
      continue;
    }
    fs.mkdirSync(path.dirname(dst), { recursive: true });
    fs.writeFileSync(dst, next);
    console.log(`[sync-assets] ${from} -> ${to}`);
  } catch (e) {
    console.warn(`[sync-assets] could not sync ${to}: ${e.message}`);
  }
}
