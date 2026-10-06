// grog web client — preload: the ONLY surface the renderer sees.
// No Node, no transport, no secrets in the renderer
// (doc/clients/web-client-plan.md §4).
const { contextBridge, ipcRenderer, webUtils } = require("electron");

contextBridge.exposeInMainWorld("grogAPI", {
  // client API (== grog.client + server control): open/close/sessions/session/
  // connect/prompt/steer/stop/answer/set-model/set-trust/projects/create-project
  call: (method, params) => ipcRenderer.invoke("grog:call", method, params),
  // server notifications: {"method":"event","params":{...:sessionId}} and
  // {"method":"question", ...}
  onNotify: (f) => ipcRenderer.on("grog:notify", (_e, msg) => f(msg)),
  // window focus — required by the question-visibility rule (§3.4.1)
  onFocus: (f) => ipcRenderer.on("grog:focus", (_e, focused) => f(focused)),
  focused: () => ipcRenderer.invoke("grog:focused"),
  // where we expect the grog-server unix socket (for "unreachable at …" UX)
  socketPath: () => ipcRenderer.invoke("grog:socket-path"),
  // open a shipped documentation file (docs-relative) in the OS handler; an
  // empty rel opens the docs folder. The main process confines it to DOCS_DIR.
  openDoc: (rel) => ipcRenderer.invoke("grog:open-doc", rel),
  // client-side voice input: status of the local STT engine, and transcribe a
  // captured 16 kHz mono WAV (Uint8Array) → { text }. Stays entirely local.
  voiceStatus: () => ipcRenderer.invoke("grog:voice-status"),
  voiceTranscribe: (bytes) => ipcRenderer.invoke("grog:voice-transcribe", bytes),
  // attach: native multi-file picker → absolute paths; and the absolute path of
  // a dropped File (Electron's webUtils; File.path was removed in Electron 32+).
  pickFiles: () => ipcRenderer.invoke("grog:pick-files"),
  pathForFile: (file) => { try { return webUtils.getPathForFile(file) || ""; } catch { return ""; } },
  // an assistant image: open it full-size in the OS viewer/browser (pan+zoom),
  // or save it to the Downloads folder. Both take (mediaType, base64).
  openImage: (mediaType, base64) => ipcRenderer.invoke("grog:open-image", mediaType, base64),
  saveImage: (mediaType, base64) => ipcRenderer.invoke("grog:save-image", mediaType, base64),
});