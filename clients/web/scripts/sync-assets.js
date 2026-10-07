// sync-assets.js — keep the renderer's SERVED image assets in sync with the
// repo-root sources.
//
// Why two copies exist: an Electron renderer can only load files from its own
// static root (clients/web/resources/public/), so every image it shows has to be
// a real file there — while the SOURCE art lives at the repo root. Nothing
// linked the two, so they silently drifted (the served logo was still the old
// 1248x832 art after a new one was planted at the root). This script makes each
// served copy a PRODUCT of its source.
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
  ["logo.jpg", "resources/public/logo.jpg"],   // the splash art
  ["snake.png", "resources/public/snake.png"], // the ouroboros emblem (onboarding)
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
