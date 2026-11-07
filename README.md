# VoiceChat

A private Android assistant with a Gemini Live / ChatGPT Live style interface.
One conversation accepts typed messages and full-duplex voice, with instant
barge-in and a shared model context. It talks to Alibaba Cloud Model Studio
(DashScope) Qwen realtime models over WebSocket or WebRTC.

```
Text input  ──► Qwen realtime session ──► text answer
Voice input ──► Qwen realtime session ──► spoken answer + live transcript
                         ▲
                  same conversation
```

## Features

- **Bluetooth microphone routing** — voice mode selects a connected headset's
  communication route and matching microphone; a headset connected while
  listening is picked up automatically. Android 12+ prompts for Bluetooth
  permission before voice mode starts.
- **Live voice mode** — continuous listening, server VAD (`semantic_vad` /
  `smart_turn_v2` / `server_vad`), platform echo cancellation, streaming
  playback, tap or just talk to interrupt. Qwen Audio 3.1 defaults to
  `smart_turn_v2` so its own playback does not trigger a barge-in.
- **Bluetooth microphone routing** — voice mode selects a connected headset's
  communication route and its matching microphone; a headset connected while
  listening is picked up automatically.
- **Audio traffic indicator** — media sent/received per current session in
  B/KB/MB, plus a top-bar dot that is green while receiving audio, blue while
  the user is speaking, amber during VAD silence and gray when disconnected.
- **Speech speed** — a delivery instruction includes the selected rate, and
  WebSocket playback applies pitch-preserving time-stretching to the PCM stream
  in-app (0.75×–2.0× slider in Settings). The realtime API ignores server-side
  `speech_rate` hints, and `AudioTrack.playbackParams` is rejected by the
  device, so rate control is done by `TimeStretcher`; WebRTC/AOQ use the vendor
  audio stack and do not expose local playback-rate control.
- **Text mode** — type in the same session; sending stops the microphone,
  answers are requested as text and audio is suppressed even if the server
  streams it. Voice only resumes from the sound-wave button.
- **Seamless switching** — the session stays open between modalities; context is
  never rebuilt.
- **Chat history** — drawer with day grouping, long-press to rename or delete.
- **Attachments** — photo/camera/document picker, images sent through the image
  buffer (< 256 KB JPEG), text documents inlined into the turn.
- **Tools** — `get_current_datetime`, `get_location` (with a fresh fix and a
  place name), `get_weather` (Open-Meteo, no key) and web search: server-side
  native search where supported, or the client `web_search` when using Qwen3.8
  Omni with client tools. The client provider is selectable (Exa by default,
  using `secrets/exa.key`, Brave Search using `secrets/brave.key`, or
  DuckDuckGo) and automatically falls back to the other credentialed providers
  when one fails or returns nothing. API keys are loaded from ignored `secrets/`
  files at build time. Both models support function calling; Qwen Audio 3.1 is
  stricter about JSON Schema (`required` must be present).
- **Metrics** — connect latency, TTFT per answer, token usage per conversation,
  and lifetime token/cost totals since the billing start. Prices are editable.
- **Debug logging** — category filters, protocol dumps (audio/image redacted),
  in-app log viewer, optional rotating file log.
- **Settings** — transport, model, voice, workspace host, system prompt presets,
  speech speed (0.75×–2.0× pitch-preserving time-stretch), VAD tuning, on-device
  voice gate, transcription, tools/search, cost rates, prompts. Each model shows
  its own official voice list (56 for Omni, 89 for Audio 3.1); an unsupported or
  migrated voice falls back to that model's default.

## Requirements

- Android 8.0+ (API 26), compile/target SDK 36.
- A DaschScope API key with access to:
  - `qwen3.8-omni-flash-realtime` (recommended)
  - `qwen-audio-3.1-realtime-flash`
- The Singapore workspace host used by the provided key
  (`ws-….ap-southeast-1.maas.aliyuncs.com`). Change it in Settings if you use
  Beijing or another workspace.

## Build

The key is **not in git**. Put it in `secrets/qwen-apiKey-<id>.csv` (simple
`name,value` rows; `secrets/` is git-ignored). Gradle injects the values into
`BuildConfig`; the app falls back to the key field in Settings when the file is
missing.

Search providers use `secrets/exa.key` and `secrets/brave.key`. Either a bare key
or a pasted `name=value` line works; only the value is read.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_HOME    = "$env:USERPROFILE\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2"
.\gradlew.bat :app:assembleDebug --no-daemon
```

Install on a device/emulator:

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

Run unit tests: `.\gradlew.bat :app:testDebugUnitTest`.

Enable the secret-scanning hook once per clone:

```powershell
git config core.hooksPath .githooks
```

## Transports

| Transport | Media | Notes |
| --- | --- | --- |
| WebSocket | 16 kHz PCM up, 24 kHz PCM down | Full feature set; works on emulators and with both models. |
| WebRTC | RTP, 48 kHz, AEC/NS | Lowest latency. Control over `oai-events`/`txt` data channels. Voice turns only. The audio model currently returns HTTP 500 from the signalling endpoint, so use WebSocket with it. |
| AOQ | QUIC/MoQ via Alibaba's AOQ SDK | Lowest latency on arm64 phones. The app allocates a token (`x-dashscope-rtc-transport: moq`), connects with the SDK, and carries realtime events on the Data track. Audio follows Bluetooth/USB when connected, otherwise VoIP + loudspeaker with AEC. Not available for Qwen Audio 3.1. |

`qwen-audio-3.1-realtime-flash` is WebSocket-only: its AOQ token request returns
no credentials and WebRTC signalling answers HTTP 500. The app marks the model
as WebSocket-only and switches transport automatically if needed.

AOQ token authentication: production should run the allocation on an AppServer so
the API key never reaches the device. This private build performs the documented
AppServer call in-app (`AoqTokenClient`) and keeps the key in `BuildConfig`; the
SDK only ever sees the short-lived `aoqTokenForClient`. Set an HTTPS token
service URL in Settings to delegate the exchange to a server instead. The SDK's
AAR and Opus plugin are checked in under `app/libs` and
`app/src/main/jniLibs` (arm64-v8a and armeabi-v7a).

WebRTC signalling is a single `POST` of the SDP offer; the server returns the
answer SDP. The answer must be normalised to CRLF line endings before handing it
to libwebrtc (already handled in `WebRtcTransport`). Tracked media is muted while
the app is not in live mode.

## Metrics and cost

`response.done` carries `usage.input_tokens`, `output_tokens` and
`total_tokens`; the app stores them per assistant message and sums the
conversation. TTFT is measured from the turn trigger (`speech_stopped` for
voice, `response.create` for text) to the first text/audio delta. Cost is an
estimate based on the per-million prices entered in Settings; DashScope does not
expose per-key billing through this API.

## Known limitations

- AOQ native libraries are arm64-v8a/armeabi-v7a only, so AOQ is unavailable on
  the x86_64 emulator; the physical Pixel works.
- WebRTC sessions cannot attach images: the protocol carries images on a video
  track, which this build does not publish.
- No wake word, lock-screen/background conversation service, or release signing.
- No automatic reconnect after a dropped socket; send a message to retry.
- Image turns switch voice mode off (they need manual commits) and are not
  available on WebRTC or AOQ.
- Emulator microphones are zeroed unless the emulator starts with
  `-allow-host-audio`.

See `AGENTS.md` for architecture, conventions and the protocol reference.
