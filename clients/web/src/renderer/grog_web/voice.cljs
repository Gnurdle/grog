(ns grog-web.voice
  "Client-side voice **input** for the web client.

  Everything happens on THIS client: the renderer captures the microphone with
  the Web Audio API, encodes 16 kHz mono PCM WAV in JS, and hands the raw bytes
  to Electron main (via window.grogAPI) which runs the local transcription
  engine (whisper.cpp). **No audio crosses the grog-server socket** — the server
  never sees a byte of it.

  Capture compromise: ScriptProcessorNode is deprecated, but it is still the
  simplest capture node that works with zero extra deps in Electron/Chromium and
  avoids an AudioWorklet module file. We open the AudioContext at 16 kHz so the
  browser resamples the mic for us (whisper wants 16 kHz mono); if the platform
  refuses that rate we linearly resample from whatever we got.")

(defonce ^:private cap
  ;; {:stream stream :ctx ctx :src src :proc proc :mute mute :chunks js-array :rate n}
  (atom nil))

(def ^:private target-rate 16000)

;; --- capability -------------------------------------------------------------

(defn supported?
  "True when the runtime exposes getUserMedia (an Electron/Chromium renderer
  normally does; a plain sandboxed page may not)."
  []
  (boolean (and (exists? js/navigator)
                (.. js/navigator -mediaDevices -getUserMedia))))

;; --- audio helpers ----------------------------------------------------------

(defn- f32->i16
  "Clamp Float32 samples in [-1,1] to signed 16-bit."
  [^js f32]
  (let [n (.-length f32)
        out (js/Int16Array. n)]
    (dotimes [i n]
      (let [s (js/Math.max -1.0 (js/Math.min 1.0 (aget f32 i)))]
        (aset out i (js/Math.round (* s 32767.0)))))
    out))

(defn- resample
  "Linear resample mono Float32 from `src` Hz to `dst` Hz (identity if equal)."
  [^js f32 src dst]
  (if (= src dst)
    f32
    (let [ratio (/ src dst)
          n (js/Math.max 1 (js/Math.floor (/ (.-length f32) ratio)))
          out (js/Float32Array. n)
          last-i (dec (.-length f32))]
      (dotimes [i n]
        (let [pos (* i ratio)
              i0 (js/Math.floor pos)
              i1 (js/Math.min (js/Math.floor (inc pos)) last-i)
              frac (- pos i0)]
          (aset out i (+ (* (aget f32 i0) (- 1.0 frac))
                         (* (aget f32 i1) frac)))))
      out)))

(defn- concat-f32
  "Concatenate a seq of Float32Arrays into one."
  [chunks]
  (let [total (reduce (fn [a c] (+ a (.-length c))) 0 chunks)
        out (js/Float32Array. total)]
    (loop [i 0 cs chunks]
      (when (seq cs)
        (let [c (first cs)]
          (.set out c i)
          (recur (+ i (.-length c)) (rest cs)))))
    out))

(defn- put-str!
  "Write ASCII bytes of `s` into DataView `dv` at `off`."
  [^js dv off ^string s]
  (dotimes [i (count s)]
    (.setUint8 dv (+ off i) (.charCodeAt s i))))

(defn- encode-wav
  "16-bit PCM mono WAV of Float32 `f32` at `rate`. Returns a Uint8Array."
  [^js f32 rate]
  (let [samples (f32->i16 f32)
        n (.-length samples)
        buf (js/ArrayBuffer. (+ 44 (* n 2)))
        dv (js/DataView. buf)]
    (put-str! dv 0 "RIFF")
    (.setUint32 dv 4 (+ 36 (* n 2)) true)
    (put-str! dv 8 "WAVE")
    (put-str! dv 12 "fmt ")
    (.setUint32 dv 16 16 true)                 ; fmt chunk size
    (.setUint16 dv 20 1 true)                  ; PCM
    (.setUint16 dv 22 1 true)                  ; mono
    (.setUint32 dv 24 rate true)               ; sample rate
    (.setUint32 dv 28 (* rate 2) true)         ; byte rate = rate * blockAlign
    (.setUint16 dv 32 2 true)                  ; block align
    (.setUint16 dv 34 16 true)                 ; bits per sample
    (put-str! dv 36 "data")
    (.setUint32 dv 40 (* n 2) true)            ; data size
    (dotimes [i n]
      (.setInt16 dv (+ 44 (* i 2)) (aget samples i) true))
    (js/Uint8Array. buf)))

;; --- capture lifecycle ------------------------------------------------------

(defn start!
  "Open the mic and begin buffering audio. Returns a Promise<handle>. Throws /
  rejects if the mic is unavailable or permission is denied."
  []
  (if-not (supported?)
    (js/Promise.reject (js/Error. "microphone API unavailable"))
    (-> (js/navigator.mediaDevices.getUserMedia
         (clj->js {:audio {:channelCount 1 :echoCancellation true
                           :noiseSuppression true :autoGainControl true}}))
        (.then
         (fn [stream]
           (let [ctx  (js/AudioContext. (clj->js {:sampleRate target-rate}))
                 src  (.createMediaStreamSource ctx stream)
                 proc (.createScriptProcessor ctx 4096 1 1)
                 mute (.createGain ctx)
                 chunks (js/Array.)]
             (set! (.. mute -gain -value) 0.0)
             (set! (.-onaudioprocess proc)
                   (fn [e]
                     (let [ch (.getChannelData (.-inputBuffer e) 0)]
                       ;; copy: the input buffer is reused between callbacks
                       (.push chunks (js/Float32Array. ch)))))
             (.connect src proc)
             (.connect proc mute)          ; keep the graph alive without
             (.connect mute (.-destination ctx)) ; playing the mic back
             (reset! cap {:stream stream :ctx ctx :src src :proc proc
                          :mute mute :chunks chunks :rate (.-sampleRate ctx)})
             nil))))))

(defn- release! [{:keys [stream ctx src proc mute] :as _h}]
  (when proc (set! (.-onaudioprocess proc) nil))
  (doseq [node [src proc mute]]
    (try (.disconnect node) (catch :default _ nil)))
  (when ctx (try (.close ctx) (catch :default _ nil)))
  (when stream
    (doseq [t (.getTracks stream)]
      (try (.stop t) (catch :default _ nil)))))

(defn stop!
  "Stop capturing, release the mic, and return a Promise resolving to
  `{:wav <Uint8Array> :seconds <number>}` (empty wav when nothing was captured)."
  []
  (let [{:keys [rate chunks] :as h} @cap]
    (reset! cap nil)
    (when h (release! h))
    (let [raw (concat-f32 (or (some-> chunks vec) []))
          res (resample raw (or rate target-rate) target-rate)
          wav (encode-wav res target-rate)]
      (js/Promise.resolve {:wav wav :seconds (/ (.-length res) target-rate)}))))

(defn cancel!
  "Abort capture without producing a WAV."
  []
  (when-let [h @cap]
    (reset! cap nil)
    (release! h)))

(defn recording?
  "True while a capture is live."
  []
  (some? @cap))
