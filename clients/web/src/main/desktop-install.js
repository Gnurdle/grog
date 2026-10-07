// desktop-install.js — let the AppImage put ITSELF in the application menu.
//
// Every other route needs a helper: AppImageLauncher/appimaged (a daemon or a
// package) to rewrite the embedded `Exec=AppRun` into an absolute path, or a
// hand-written .desktop that breaks the moment you move the file. The app can
// do this itself, because at runtime it knows the one thing a helper is needed
// for: its own absolute path — `$APPIMAGE`, set by the AppImage runtime.
//
// So: on first run we OFFER, on accept we write the entry + icon, and on every
// later run we re-point the entry if the AppImage moved (self-healing).
//
// This module plans, writes and refreshes. Asking the user is the caller's job
// (main.js shows a native dialog) — same split as config-seed.js.
"use strict";
const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawnSync } = require("child_process");
const { configHome } = require("./config-seed");

const APP_NAME = "grog";                 // Name= and the icon theme name
const DESKTOP_FILE = "grog.desktop";
const ICON_FILE = "grog.png";
// Marks the entry as OURS, so we only ever rewrite a file we wrote.
const MANAGED_KEY = "X-Grog-Managed=true";
// Written when the user declines, so we ask once and never nag.
const DECLINE_MARKER = ".desktop-prompt-declined";

/** The AppImage we are running as, or null (any other launch shape). */
function isAppImage(env) {
  const v = (env || process.env).APPIMAGE;
  return typeof v === "string" && v.trim() ? path.resolve(v.trim()) : null;
}

/** `$XDG_DATA_HOME` or `~/.local/share`. */
function dataHome(env) {
  const v = (env || process.env).XDG_DATA_HOME;
  return typeof v === "string" && v.trim() ? path.resolve(v.trim()) : path.join(os.homedir(), ".local", "share");
}

/** One `Exec` argument, quoted per the Desktop Entry spec. */
function quoteExecArg(s) {
  return '"' + String(s).replace(/([\\"`$])/g, "\\$1") + '"';
}

/** The `Exec=` line for this AppImage: absolute path, sandbox off, WM class set. */
function execLine(appImage) {
  // --no-sandbox: Electron refuses to start without it on many distros.
  // --class=grog: makes the WM_CLASS match StartupWMClass below, so the window
  // groups under this icon in the taskbar/dock.
  return `Exec=${quoteExecArg(appImage)} --no-sandbox --class=${APP_NAME} %U`;
}

/** The full .desktop text. */
function entryContents(appImage) {
  return [
    "[Desktop Entry]",
    "Type=Application",
    "Version=1.0",
    `Name=${APP_NAME}`,
    "GenericName=AI Chat Assistant",
    "Comment=AI coding assistant with on-machine tools (files, shell, office, OCR)",
    execLine(appImage),
    `Icon=${APP_NAME}`,
    "Terminal=false",
    "StartupNotify=true",
    `StartupWMClass=${APP_NAME}`,
    "Categories=Development;",
    `Keywords=${APP_NAME};ai;chat;llm;assistant;coder;`,
    MANAGED_KEY,
    "",
  ].join("\n");
}

function readIfExists(f) {
  try {
    return fs.readFileSync(f, "utf8");
  } catch (_e) {
    return null;
  }
}

/**
 * Is this entry grog's — of ANY version? `X-Grog-Managed=true` marks one we
 * wrote; an older grog (before that marker existed) still wrote a `grog.desktop`
 * with `Name=grog`, and that is the tell. We take those over on purpose: a newer
 * AppImage launched from a different directory must be able to re-point the
 * entry, not sit behind a stale one from an older copy.
 */
function looksLikeOurs(content) {
  if (content == null) return false;
  return content.includes(MANAGED_KEY) || /^Name=grog\s*$/m.test(content);
}

/** The `Exec=` line already in an entry, or null. */
function existingExec(content) {
  if (content == null) return null;
  const m = content.match(/^Exec=(.*)$/m);
  return m ? m[1].trim() : null;
}

/** The first quoted argument of an Exec line (the program), or null. */
function execProgram(execValue) {
  if (!execValue) return null;
  const m = execValue.match(/^"([^"]+)"/);
  return m ? m[1] : (execValue.split(/\s+/)[0] || null);
}

/**
 * What the integration WOULD do. Reads the filesystem; writes nothing.
 *
 *   state : "n/a"     not an AppImage — nothing to do
 *           "install" no entry yet
 *           "update"  a grog entry exists but runs another copy (moved, or an
 *                     older version installed elsewhere) — re-point it
 *           "ok"      the entry already runs this AppImage
 *           "foreign" a grog.desktop that is not grog's — leave it alone
 */
function plan(opts) {
  const o = opts || {};
  const env = o.env || process.env;
  const appImage = isAppImage(env);
  const data = o.dataHome || dataHome(env);
  const apps = path.join(data, "applications");
  const iconDir = path.join(data, "icons", "hicolor", "512x512", "apps");
  const desktopFile = path.join(apps, DESKTOP_FILE);
  const iconSource = path.join(o.resourcesPath || process.resourcesPath, "icon.png");
  const home = o.configHome || configHome(env);
  const existing = appImage ? readIfExists(desktopFile) : null;
  const ours = looksLikeOurs(existing);
  const prevExec = existingExec(existing);
  return {
    appImage,
    dataHome: data,
    desktopFile,
    iconDir,
    iconDest: path.join(iconDir, ICON_FILE),
    iconSource,
    iconSourceExists: fs.existsSync(iconSource),
    entry: appImage ? entryContents(appImage) : null,
    marker: path.join(home, DECLINE_MARKER),
    declined: fs.existsSync(path.join(home, DECLINE_MARKER)),
    previousExec: prevExec,
    previousProgram: execProgram(prevExec),
    state: !appImage
      ? "n/a"
      : existing == null
        ? "install"
        : !ours
          ? "foreign"
          : existing.includes(execLine(appImage))
            ? "ok"
            : "update",
  };
}

/**
 * Write the icon and the entry. Refuses to touch a file we did not write.
 * Returns the paths written.
 */
function install(p) {
  if (!p || !p.appImage || p.state === "n/a") throw new Error("not an AppImage — nothing to install");
  if (p.state === "foreign") throw new Error(`${p.desktopFile} is not ours — refusing to overwrite`);
  const written = [];
  fs.mkdirSync(path.dirname(p.desktopFile), { recursive: true });
  if (p.iconSourceExists) {
    fs.mkdirSync(p.iconDir, { recursive: true });
    fs.copyFileSync(p.iconSource, p.iconDest);
    written.push(p.iconDest);
  }
  fs.writeFileSync(p.desktopFile, p.entry, "utf8");
  written.push(p.desktopFile);
  return written;
}

/** Record that the user said no, so we ask once and never nag. */
function decline(p) {
  fs.mkdirSync(path.dirname(p.marker), { recursive: true });
  fs.writeFileSync(p.marker, new Date().toISOString() + "\n", "utf8");
  return p.marker;
}

/**
 * Refresh the desktop/icon caches so the entry shows up without a re-login.
 * Best effort: a missing tool is not an error. `exists`/`run` are injectable
 * for tests.
 */
function refresh(opts) {
  const o = opts || {};
  const env = o.env || process.env;
  const data = o.dataHome || dataHome(env);
  const exists = o.exists || ((cmd) => {
    for (const dir of String(env.PATH || "").split(":")) {
      try {
        fs.accessSync(path.join(dir, cmd), fs.constants.X_OK);
        return true;
      } catch (_e) { /* keep looking */ }
    }
    return false;
  });
  const run = o.run || ((cmd, args) => {
    try {
      spawnSync(cmd, args, { stdio: "ignore", timeout: 20000 });
    } catch (_e) { /* best effort */ }
  });
  const ran = [];
  const try_ = (cmd, args) => {
    if (!exists(cmd)) return;
    try {
      run(cmd, args);
    } catch (_e) {
      /* a failing cache refresh must never break startup */
    }
    ran.push(cmd);
  };
  try_("update-desktop-database", [path.join(data, "applications")]);
  try_("gtk-update-icon-cache", ["-f", "-t", path.join(data, "icons", "hicolor")]);
  try_("kbuildsycoca6", []);            // KDE
  try_("kbuildsycoca5", []);            // KDE 5 fallback
  return ran;
}

module.exports = {
  APP_NAME,
  DESKTOP_FILE,
  ICON_FILE,
  MANAGED_KEY,
  DECLINE_MARKER,
  isAppImage,
  dataHome,
  quoteExecArg,
  execLine,
  entryContents,
  looksLikeOurs,
  execProgram,
  plan,
  install,
  decline,
  refresh,
};
