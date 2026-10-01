// grog web client — preload: the ONLY surface the renderer sees.
// No Node, no transport, no secrets in the renderer
// (doc/clients/web-client-plan.md §4).
const { contextBridge, ipcRenderer } = require("electron");

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
  // client-side voice input: status of the local STT engine, and transcribe a
  // captured 16 kHz mono WAV (Uint8Array) → { text }. Stays entirely local.
  voiceStatus: () => ipcRenderer.invoke("grog:voice-status"),
  voiceTranscribe: (bytes) => ipcRenderer.invoke("grog:voice-transcribe", bytes),
});