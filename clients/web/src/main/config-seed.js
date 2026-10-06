// config-seed.js — the FIRST-RUN CONFIG SEED.
//
// grog needs a config file. Without one it runs on built-in defaults, which is
// rarely what anyone wants: no model, no provider, no API key. The examples
// that make this easy ship INSIDE the app package (resources/config.examples/),
// which for an AppImage is a read-only squashfs mount a human cannot browse —
// and in a source tree they live in the repo's resources/config.examples.
//
// So the client SEEDS the config home at startup: grog.edn (renamed from the
// example) plus the optional files as `*.example`. Nothing is overwritten, and
// the caller logs the exact path, so the user always knows the starter exists
// and where it went. The starter names a provider and a model but carries no
// API KEY, so it cannot reach a model until the user stores one — the caller
// says so plainly.
//
// A fresh grog.edn also means a FRESH GROG, so seeding writes the same
// `onboarding-requested` marker `grog.reset` does: the spine reads it at
// `startup` and shows the getting-started onboarding. Without it, deleting
// ~/.config/grog would silently drop the user into an old project, unwelcomed.
//
// This module only plans and writes; resolving the example directory (packaged
// vs source tree) and reporting the paths is the caller's job (main.js).
"use strict";
const fs = require("fs");
const os = require("os");
const path = require("path");

const MAIN_CONFIG = "grog.edn";
const EXAMPLE_SUFFIX = ".example";
// The marker the spine checks at `startup` to force the getting-started
// onboarding. Mirrors `grog.bootstrap/onboarding-requested-file` — keep the two
// in step. Written only when a fresh grog.edn is actually seeded.
const ONBOARDING_MARKER = "onboarding-requested";

/**
 * grog's config home. Mirrors `grog.platform/config-home-dir`:
 *   1. $GROG_CONFIG_HOME (absolute, or `~`-relative)
 *   2. ${XDG_CONFIG_HOME:-~/.config}/grog   (on every OS, Windows included)
 */
function configHome(env) {
  env = env || process.env;
  const raw = typeof env.GROG_CONFIG_HOME === "string" ? env.GROG_CONFIG_HOME.trim() : "";
  if (raw) {
    const expanded =
      raw === "~" ? os.homedir() : raw.startsWith("~/") ? path.join(os.homedir(), raw.slice(2)) : raw;
    return path.resolve(expanded);
  }
  const xdg = typeof env.XDG_CONFIG_HOME === "string" ? env.XDG_CONFIG_HOME.trim() : "";
  return path.join(xdg || path.join(os.homedir(), ".config"), "grog");
}

/**
 * Every `*.example` shipped with the app, sorted by name. `[]` when the package
 * has none (a dev tree that never ran `bb dist`, say).
 * Each entry: {name "grog.edn.example", target "grog.edn", from "<abs path>"}.
 */
function availableExamples(resourcesPath) {
  const dir = path.join(resourcesPath || process.resourcesPath, "config.examples");
  let names;
  try {
    names = fs.readdirSync(dir);
  } catch (_e) {
    return [];
  }
  return names
    .filter((n) => n.endsWith(EXAMPLE_SUFFIX))
    .sort()
    .map((n) => ({
      name: n,
      target: n.slice(0, -EXAMPLE_SUFFIX.length),
      from: path.join(dir, n),
    }));
}

/**
 * What the first-run seed WOULD do. Reads the filesystem, writes nothing.
 *
 *   home        the config home (created when the seed is written)
 *   grogEdn     <home>/grog.edn — the file the user must actually edit
 *   mainExample the shipped grog.edn.example, or nil
 *   needsMain   TRUE when grog.edn is absent and its example is available —
 *               this is the trigger for seeding
 *   optional    the remaining examples (odoo/imap/gitlab/…), offered as
 *               `*.example` so an AppImage user can reach them at all
 */
function planOffer(opts) {
  const o = opts || {};
  const home = o.configHome || configHome(o.env);
  const examples = availableExamples(o.resourcesPath);
  const main = examples.find((e) => e.target === MAIN_CONFIG) || null;
  return {
    home,
    grogEdn: path.join(home, MAIN_CONFIG),
    mainExample: main,
    needsMain: !!main && !fs.existsSync(path.join(home, MAIN_CONFIG)),
    optional: examples.filter((e) => e.target !== MAIN_CONFIG),
  };
}

/**
 * Write `<home>/grog.edn` from the example, plus the optional examples as
 * `*.example`. NEVER overwrites an existing file. Returns the paths written
 * (so the caller can report them).
 */
function createConfig(plan) {
  const written = [];
  fs.mkdirSync(plan.home, { recursive: true });
  let seededMain = false;
  if (plan.mainExample && !fs.existsSync(plan.grogEdn)) {
    fs.copyFileSync(plan.mainExample.from, plan.grogEdn);
    written.push(plan.grogEdn);
    seededMain = true;
  }
  for (const e of plan.optional) {
    const dest = path.join(plan.home, e.name);
    if (fs.existsSync(dest)) continue;
    fs.copyFileSync(e.from, dest);
    written.push(dest);
  }
  // A fresh grog.edn = a fresh grog: ask the spine to onboard on the next start.
  // (`grog.bootstrap/startup-info` consumes the marker once, then deletes it.)
  // Not added to `written` — that is the list of CONFIG files to report.
  if (seededMain) {
    const marker = path.join(plan.home, ONBOARDING_MARKER);
    if (!fs.existsSync(marker)) fs.writeFileSync(marker, String(Date.now()));
  }
  return written;
}

module.exports = { configHome, availableExamples, planOffer, createConfig, ONBOARDING_MARKER };
