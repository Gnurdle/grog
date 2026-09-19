(ns grog.voice
  "Push-to-talk voice **input** for grog: record the microphone (zero external
  dependencies, via `javax.sound.sampled`), write a WAV, and transcribe it with a
  configurable external command.

  Grog deliberately does NOT bundle an STT engine. `:voice :command` points at
  one that reads a WAV file and prints the transcript on stdout — e.g.
  whisper.cpp, openai-whisper, vosk, or a cloud CLI. `{wav}` in the command is
  replaced with the recorded file path.

  Config (grog.edn):
    :voice {:enabled true
            ;; whisper.cpp (local, offline):
            :command [\"whisper-cli\" \"-m\" \"/models/ggml-base.en.bin\"
                      \"-f\" \"{wav}\" \"-nt\" \"-otxt\"]
            :sample-rate 16000
            :max-seconds 60}

  Capture side needs no deps; transcription needs whatever `:command` names on
  PATH. With no `:command`, `record!` still produces a WAV you can transcribe
  elsewhere."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [grog.config :as config])
  (:import (javax.sound.sampled AudioFormat AudioFormat$Encoding AudioFileFormat$Type
                                AudioInputStream AudioSystem TargetDataLine)
           (java.io ByteArrayInputStream ByteArrayOutputStream File)))

(set! *warn-on-reflection* true)

;; --- config -----------------------------------------------------------------

(defn- voice-cfg []
  (:voice (config/grog) {}))

(defn enabled?
  "True when `:voice {:enabled true}`."
  []
  (true? (:enabled (voice-cfg))))

(defn command
  "The transcription argv (vector of strings), or nil when unset/empty."
  []
  (let [c (:command (voice-cfg))]
    (when (and (sequential? c) (seq c))
      (mapv str c))))

(defn sample-rate []
  (let [v (:sample-rate (voice-cfg))]
    (if (and (number? v) (pos? (long v))) (long v) 16000)))

(defn max-seconds []
  (let [v (:max-seconds (voice-cfg))]
    (if (and (number? v) (pos? (long v))) (long v) 60)))

(defn- audio-format ^AudioFormat [^long rate]
  ;; 16-bit signed little-endian mono — the de-facto STT input format.
  (AudioFormat. (float rate) 16 1 true false))

;; --- capture ----------------------------------------------------------------

(defn- capture-loop
  "Copy PCM frames from `line` into `out` until `stop?` is set."
  [^TargetDataLine line ^ByteArrayOutputStream out stop?]
  (let [buf (byte-array 4096)]
    (while (not @stop?)
      (let [n (.read line buf 0 (alength buf))]
        (when (pos? n)
          (.write out buf 0 n))))))

(defn start!
  "Open the default capture line and begin recording in a background thread.
  Returns an opaque handle for `stop!`. Options: `:sample-rate`.
  Throws if no capture device is available / permitted."
  ([] (start! {}))
  ([{:keys [sample-rate] :or {sample-rate nil}}]
   (let [rate (long (or sample-rate (grog.voice/sample-rate)))
         fmt  (audio-format rate)
         ^TargetDataLine line (AudioSystem/getTargetDataLine fmt)]
     (.open line fmt)
     (.start line)
     (let [out (ByteArrayOutputStream.)
           stop? (atom false)
           t (doto (Thread. #(capture-loop line out stop?) "grog-voice-capture")
               (.setDaemon true)
               (.start))]
       {:line line :out out :stop? stop? :thread t :format fmt
        :started-at (System/currentTimeMillis)}))))

(defn recording?
  "True while `h` is a live capture handle."
  [h]
  (boolean (and (map? h) (:line h) (not @(:stop? h)))))

(defn stop!
  "Stop recording, close the line, and write the captured audio to a temp WAV.
  Returns the `File`, or nil when nothing was captured. Idempotent-ish: calling
  it twice on the same handle yields nil the second time."
  ^File [h]
  (when (recording? h)
    (let [{:keys [^TargetDataLine line ^ByteArrayOutputStream out stop? ^Thread thread
                  ^AudioFormat format]} h]
      (reset! stop? true)
      (.stop line)
      (.close line)
      (try (.join thread 1000) (catch Throwable _))
      (let [bytes (.toByteArray out)
            frame (int (.getFrameSize format))
            frames (quot (alength bytes) frame)]
        (when (pos? frames)
          (let [f (File/createTempFile "grog-voice-" ".wav")
                ais (AudioInputStream. (ByteArrayInputStream. bytes) format (long frames))]
            (AudioSystem/write ais AudioFileFormat$Type/WAVE f)
            (.deleteOnExit f)
            f))))))

;; --- transcription ----------------------------------------------------------

(def ^:private silence-markers
  "Outputs whisper emits when there is no speech; treated as an empty transcript
  so nothing junk gets pasted into the prompt."
  #{"[blank_audio]" "[blank audio]" "[silence]" "[music]" "[ music ]"
    "(silence)" "(blank audio)"})

(defn- clean-transcript
  "Trim engine output and drop pure no-speech markers (→ \"\")."
  ^String [s]
  (let [t (str/trim (str s))]
    (if (or (str/blank? t)
            (contains? silence-markers (str/lower-case t))
            (re-matches #"(?i)\[?\s*(blank[ _]?audio|silence|music)\s*\]?\.?" t))
      ""
      t)))

(defn transcribe!
  "Run the configured `:voice :command` on `wav` (with `{wav}` substituted) and
  return the trimmed stdout transcript (\"\" when the engine reports silence).
  Throws when no command is configured or the command fails."
  ^String [^File wav]
  (let [cmd (command)]
    (when-not (seq cmd)
      (throw (ex-info "no :voice :command configured" {})))
    (let [argv (mapv #(str/replace (str %) "{wav}" (.getAbsolutePath wav)) cmd)
          {:keys [exit out err]} (apply shell/sh argv)]
      (if (zero? (int exit))
        (clean-transcript out)
        (throw (ex-info (str "voice transcription failed (exit " exit "): "
                             (str/trim (str err)))
                        {:argv argv :exit exit}))))))

(defn record-and-transcribe!
  "Convenience for tests/CLI: record for `secs` seconds, transcribe, return the
  text (or nil when no audio / no engine)."
  [^double secs]
  (let [h (start!)]
    (Thread/sleep (long (* 1000.0 (min secs (double (max-seconds))))))
    (when-let [wav (stop! h)]
      (when (seq (command))
        (transcribe! wav)))))

;; --- CLI --------------------------------------------------------------------
;; `clojure -M -e "(grog.voice/-main \"3\")"` — record N seconds, print transcript.

(defn -main [& [secs]]
  (let [n (if secs (Double/parseDouble (str secs)) 5.0)]
    (println (str "grog.voice: recording " n "s… (speak now)"))
    (let [txt (record-and-transcribe! n)]
      (if (seq txt)
        (println "transcript:" txt)
        (println "grog.voice: no transcript (no audio or no :voice :command configured).")))))
