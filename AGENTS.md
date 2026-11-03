# AGENTS.md — VoiceChat

Guidance for AI agents and humans working in this repository. Keep this file in
sync with the code: if a rule stops being true, fix it here in the same change.

## What this app is

A private Android assistant with a Gemini Live / ChatGPT Live style interface:
one conversation that accepts **text turns** (answer as text) and **voice turns**
(answer as audio + live transcript), with barge-in, interruption and continuous
full-duplex voice mode. It talks to Alibaba Cloud Model Studio (DashScope) using
the Qwen realtime models over WebSocket or WebRTC.

The goal specification lives in `GOAL.md`. Treat it as the product contract.

## Non-negotiable rules

1. **The API key never enters git.** `secrets/` is git-ignored. Gradle reads
   `secrets/qwen-apiKey-*.csv` at configuration time and injects the values into
   `BuildConfig`. Never copy a key into source, resources, tests, docs, logs or
   commit messages. Never log `Authorization` headers or full request URLs with
   credentials. `secrets/opencode-go.key` is the coding agent's own key, not
   the app's.
2. **This is a private build.** Debug builds may embed the key. Do not add
   release signing, publishing, Play Store metadata or CI that uploads APKs.
3. **One conversation, two modalities.** Text and voice share a single realtime
   session/context. Do not create a second "chat" model connection per
   modality, and do not route text through a different model.
4. **Voice output only for voice input.** A text turn asks the server for
   `modalities: ["text"]`; a live/voice turn uses `["text", "audio"]`. The
   transcript of an audio answer is still shown as a normal assistant message.
5. **Barge-in must stay instant.** On `input_audio_buffer.speech_started` stop
   local playback immediately (clear the PCM queue / mute the remote track) and
   cancel the in-flight response. Never wait for the server round trip to mute.
6. **Voice mode starts only from the sound-wave button.** Sending a typed
   message while live stops the microphone first (`applyLiveMode(false)`), so
   text turns never leave a hot mic. Do not start capture from the composer's
   send action.
7. **No blocking work on the main thread.** Audio I/O, sockets, Room writes and
   tool execution go through coroutines or dedicated threads.
8. **Never change turn detection on a live session.** The server rejects
   `session.update` that modifies `turn_detection`. Open every session with the
   configured VAD; live mode toggles capture only. In AOQ text mode, also pause
   AOQ audio publishing so the microphone is not transmitted.
9. **Logs are opt-in and redacted.** See "Logging" below.

## Build and run (Windows host)

The Android SDK and a JDK are not on `PATH` by default. Use PowerShell:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_HOME = "$env:USERPROFILE\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2"
.\gradlew.bat :app:assembleDebug --no-daemon
```

- Gradle wrapper: **Gradle 8.13**, AGP 8.13.2, Kotlin 2.2.20, KSP, Compose
  (BOM 2024.10.01), compileSdk 36, minSdk 26.
- `ANDROID_HOME` must be set. Use Adoptium JDK 21 from the Gradle toolchain
  cache (the Android Studio JBR is Java 25 and OpenJ9 hangs the build):
  `$env:JAVA_HOME="$env:USERPROFILE\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2"`.
  Add `--no-daemon` if the daemon disconnects.
- Install/debug: `"$env:ANDROID_HOME\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk`.
  An AVD named `Pixel_10` exists (`"$env:ANDROID_HOME\emulator\emulator.exe" -avd Pixel_10`).
- Unit tests: `.\gradlew.bat :app:testDebugUnitTest`.
- WSL is available for POSIX shell work, but **Gradle runs on Windows**.

Secrets resolution (`app/build.gradle.kts`):
- Reads `secrets/qwen-apiKey-1341022.csv` (simple `name,value` rows).
- Injects `QWEN_API_KEY`, `QWEN_WORKSPACE_ID`, `QWEN_API_HOST`,
  `QWEN_OPENAI_BASE`, `QWEN_DASHSCOPE_BASE`.
- If the file is absent the build still succeeds with empty values; the app
  then shows "API key not configured" and the key can be entered in Settings.
- A git hook (`.githooks/pre-commit`) rejects commits that look like they
  contain `sk-...` keys. Enable it with
  `git config core.hooksPath .githooks`.

## Module layout

Single Gradle module `app`, package `com.voicechat.assistant`.

```
app/src/main/java/com/voicechat/assistant/
  VoiceChatApp.kt            Application, WebRTC init, AppContainer
  MainActivity.kt            single activity, permission wiring
  core/                      AppLog, time/format helpers, Result helpers
  data/
    db/                      Room entities, DAOs, database
    prefs/                   DataStore settings + usage totals
    repo/                    ChatRepository (chats, messages, titles)
    attachments/             AttachmentManager (copy, scale, base64, text)
    model/                   UI/domain models, attachments, metrics
  realtime/
    SessionConfig.kt         endpoint + config snapshot
    SessionPayloads.kt       client → server JSON builders (pure)
    RealtimeEvent.kt         parsed server events (sealed types)
    RealtimeEventParser.kt   JSON -> RealtimeEvent (tolerant)
    RealtimeTransport.kt     transport contract
    ws/WebSocketTransport.kt OkHttp WS + PCM mic/speaker
    webrtc/WebRtcTransport.kt org.webrtc peer connection + data channel
    aoq/AoqTokenClient.kt    AppServer token allocation + response mapping
    aoq/AoqTransport.kt      Alibaba AOQ SDK: QUIC media + Data events
    audio/                   PcmRecorder, PcmPlayer, level meter, focus
    tools/                   ToolRegistry + date/location/web_search tools
    RealtimeSession.kt       protocol state machine: turns, tools, metrics
  ui/
    theme/                   Material 3 theme
    AppRoot.kt               drawer + routes
    chat/                    ChatViewModel, chat screen, composer, live card
    drawer/ChatDrawer.kt     history list, long-press menu
    settings/                settings screen + prompt editor
    logs/                    in-app debug log viewer
    metrics/                 latency/token/cost sheet
```

## Realtime protocol

Base URL is derived from the workspace host (Singapore for the provided key):

- WebSocket: `wss://{host}/api-ws/v1/realtime?model={model}`
- WebRTC signalling: `POST https://{host}/api/v1/webrtc/realtime?model={model}`,
  `Content-Type: application/sdp`, body = offer SDP, response = answer SDP.
  Media flows over RTP; control/events use the `oai-events` data channel and
  the server pushes events on the `txt` data channel.
- Auth: `Authorization: Bearer <api key>` (WebSocket header / HTTP header).
- Models: `qwen3.8-omni-flash-realtime` (preferred, supports
  `semantic_vad`/`semantic_vad_ec` and client tools) and
  `qwen-audio-3.1-realtime-flash` (voice + text over WebSocket, ASR model
  `fun-asr`, supports function calling; WebSocket only). Both models accept the
  client tool schemas in `ToolRegistry`. Qwen Audio's parser is stricter: every
  `parameters` object must contain a `required` array (an empty array is fine),
  otherwise it fails the response with an internal service error.

Voices are model-specific: `ModelInfo.voices` contains the official lists (56
for Qwen3.8 Omni, 89 for Qwen Audio 3.1 = `longanqian_v3.1` + the union of the
3.1 TTS plus/flash lists). Never send a voice from another model — the server
answers "voice not supported". `ModelInfo.resolveVoice` validates on every
settings load and before `session.update`, falling back to the model default.
Cloned voices are accepted when their ID starts with the target model ID.

Client events used: `session.update`, `input_audio_buffer.append` /
`commit` / `clear`, `input_image_buffer.append`, `conversation.item.create`
(text and `function_call_output`), `response.create`, `response.cancel`.
Server events consumed: `session.created|updated`, speech started/stopped/
committed, `conversation.item.input_audio_transcription.*`,
`response.created|done|cancelled`, `response.audio.delta`,
`response.audio_transcript.delta|done`, `response.text.delta|done`,
`conversation.item.created` / `response.output_item.done` with
`item.type == "function_call"`, and `error`.

Session defaults (see `RealtimeDefaults.kt` / `SessionConfig`): 16 kHz s16le
mono input, 24 kHz PCM output, server-side turn detection, input audio
transcription model `qwen3-asr-flash-realtime`. Qwen Audio 3.1 defaults to
`smart_turn_v2` (semantic + acoustic, much less self-interruption) and
`speech_rate: 1.15`; Omni also sends `speech_rate: 1.15`. Every system prompt
gets a delivery line asking for a brisk, slightly faster pace.

WebSocket voice mode puts Android into `MODE_IN_COMMUNICATION` with the
loudspeaker, uses `VOICE_COMMUNICATION` capture/playback attributes and enables
`AcousticEchoCanceler` + `NoiseSuppressor` when available. Keep that path intact:
it is what stops the assistant from hearing its own audio and barging in.

Two vendor quirks this code compensates for:

- `qwen3.8-omni-flash-realtime` can keep streaming `response.audio.delta` even
  when a turn was requested with `modalities: ["text"]`. Text turns mute the
  transport for the whole turn and ignore audio deltas, so a typed message never
  produces sound.
- The server rejects a `session.update` that changes `turn_detection` after the
  session is established ("cannot change turn detection"). Never send one:
  every session opens with the configured VAD, and live mode only starts/stops
  capture (`AOQ` additionally pauses media publishing). Reconnecting to switch
  modes would lose the conversation context.
- libwebrtc rejects the SDP answer unless it uses CRLF line endings and ends with
  a newline. `WebRtcTransport.normalizeSdp` does that; without it
  `setRemoteDescription` fails with "SessionDescription is NULL".

Text turns in an open live session:

```json
{"type":"conversation.item.create","item":{"type":"message","role":"user",
 "content":[{"type":"input_text","text":"..."}]}}
{"type":"response.create","response":{"modalities":["text"]}}
```

Image turns (WebSocket only) append a JPEG (base64, < 256 KB) via
`input_image_buffer.append` after at least one audio append, then send silence +
image + `input_audio_buffer.commit` + the text item + `response.create`. Images
are tied to the audio buffer; without the commit the model never sees them.
Sessions are always VAD now, so `sendText` stops the microphone for an image
turn without changing `turn_detection`. The commit may auto-trigger a response
on some server builds; if you touch this path, verify it against the live API.

Function calling: tool results go back as
`{"type":"conversation.item.create","item":{"type":"function_call_output",
"call_id":"...","output":"<json string>"}}` followed by a single
`response.create` after `response.done` for the tool-call response. Do not send
`response.create` per call: a response with two tool calls would otherwise hit
"Conversation already has an active response". Only act on
`response.function_call_arguments.done` or completed `function_call` items;
`conversation.item.created` for an in-progress call has empty arguments.
- The native `enable_search` option and client tools are mutually exclusive in
  the app settings. Qwen Audio 3.1 supports function calling; unlike Omni, its
  schema parser requires `required` on every tool.

## Transports

- **WebSocket** — full feature set: mic capture, streaming TTS playback,
  barge-in, images, tools, works on emulators.
- **WebRTC** — lowest latency media path; `org.webrtc` owns mic, AEC, noise
  suppression and playback, so `PcmRecorder`/`PcmPlayer` are not used. VAD
  turns only. Attachments are not supported (protocol carries images on the
  video track, which this app does not publish). Surface that limitation in
  the UI instead of failing silently.
- **AOQ** — implemented in `realtime/aoq/` with Alibaba's
  `AoqClientSdk-release.aar` (v1.4.0) and `libPluginOpus.so` (in
  `app/libs` / `app/src/main/jniLibs`). The SDK owns capture, Opus, playback,
  AEC and the QUIC relay; realtime events travel as Data messages using the
  same payloads as WebSocket.
  - **Token authentication**: production puts this call on an AppServer so the
    API key never reaches the device. This private build performs the AppServer
    step in-process (`AoqTokenClient`): `POST /api/v1/webrtc/realtime?model=…`
    with `x-dashscope-rtc-transport: moq`, then maps `aoqTokenForClient`,
    `sid`, `clientRelayEndpoints`, `clientRelayCertFingerprint` and
    `extraInfo.workspaceIdHash` into `AoqConnectConfig`. Set "AOQ token service
    URL" in Settings to delegate the exchange to an AppServer instead (no API
    key leaves the device). Tokens are fetched for every connection and never
    logged.
  - **ABI**: native libraries exist for `arm64-v8a` and `armeabi-v7a` only
    (no x86_64), so AOQ works on the physical Pixel and not on the emulator.
    `createEngine` failures are reported, not crashed.
  - **Model support**: `qwen-audio-3.1-realtime-flash` is WebSocket-only. Its
    AOQ token request returns a credential-less `{"status_name":"Success"}` and
    WebRTC signalling answers HTTP 500, so `ModelInfo.supportedTransports`
    restricts it to WebSocket and the app falls back automatically.
  - **Audio routing**: when Bluetooth/USB/hearing-aid output is connected the
    SDK runs in media mode so audio follows that route; with no external
    output it uses VoIP mode + loudspeaker + hardware AEC. Barge-in interrupts
    and stops the player immediately; text turns stop and purge playback.
  - **Images** are WebSocket-only for now (AOQ images would need a video track).
- **Text-only fallback** — none. All turns use the realtime session so context
  is shared. If the session cannot be opened, show the error and retry; do not
  silently fall back to another model.

## Metrics, cost and logging

- `response.done.response.usage` provides `input_tokens`, `output_tokens`,
  `total_tokens` and `input_tokens_details`/`output_tokens_details`. Store
  per-message usage. Conversation totals = sum of messages; lifetime totals
  live in DataStore with a resettable billing start date.
- TTFT = first text/audio/transcript delta minus the turn trigger
  (`speech_stopped` for voice, `response.create` send for text). Connect
  latency = `session.updated` minus connect start.
- Cost is an estimate: `input_tokens/1e6*priceIn + output_tokens/1e6*priceOut`
  with prices entered in Settings (defaults 0 → show tokens only). DashScope
  does not expose per-key billing through this API.
- `AppLog` keeps a bounded in-memory ring buffer, mirrors to Logcat in debug,
  optionally writes a rotating file (`filesDir/logs/app.log`, 1 MB x 2), and is
  filtered by category (`TRANSPORT`, `PROTOCOL`, `AUDIO`, `TOOLS`, `SESSION`,
  `COST`, `UI`, `DB`). Protocol dumps redact audio/image payloads to
  `"<audio N bytes>"` / `"<image N bytes>"` and never print the API key.

## UI conventions

- Material 3, dark/light + dynamic colour on Android 12+, no XML layouts.
- Drawer: new chat, history (grouped by day), long-press → rename/delete,
  footer → Settings, Debug logs, Usage.
- Top bar: menu, chat title, connection dot, gear.
- Chat: reverse `LazyColumn`, user right / assistant left, streaming text
  updates in place, audio answers show a small waveform badge.
- Composer: `+` (photo / camera / document), expanding text field, send,
  sound-wave button for live mode. Send and sound-wave are separate 42 dp
  targets with a gap; never let IconButton's 48 dp touch target overlap them.
  Typing and sending stops live mode; capture only starts from the sound wave.
- Audio traffic strip above the composer shows media sent/received (B/KB/MB)
  and "speaking"/"receiving"/"VAD idle"/"text mode". The top-bar dot is green
  when audio is received, blue while VAD detects the user speaking, amber when
  connected but silent, gray when disconnected.
- Settings: connection (transport, model, voice, workspace host, API key),
  conversation (system prompt + presets, VAD, transcription, tools vs search),
  metrics/cost rates, debug categories + log viewer.
- Keep strings in `res/values/strings.xml` when reused; UI copy may also be
  inline for one-off labels. No hard-coded colours outside the theme.

## Coding conventions

- Kotlin, 4-space indent, trailing commas not required, no wildcard imports.
- Immutable UI state via `StateFlow`; events flow one way down, callbacks up.
- `RealtimeSession` is transport-agnostic: it never references OkHttp or
  WebRTC types directly.
- Do not catch `Throwable`; catch specific exceptions, log with category and
  surface a user-visible message. Cancellation must propagate.
- Room writes on `Dispatchers.IO`; DAO flows drive the UI.
- New settings must be added to `AppSettings`, its DataStore mapping, the
  settings UI and `SessionConfig` in the same change.
- Prefer extending the tolerant parser over adding strict serializable DTOs;
  the vendor adds event fields without notice.

## Tests

Unit tests live in `app/src/test`. Cover: event parsing (including unknown
events and redaction), URL/session-config building, cost/TTFT math, tool
argument handling, text-turn vs voice-turn modality policy. UI tests are not
required; verify screens on the emulator with `adb` screenshots.

## Known limitations / roadmap

- AOQ requires an arm64/armv7 device; the emulator is x86_64.
- WebRTC sessions cannot send image attachments yet.
- Image turns pause live mode: they need manual commits on WebSocket. Camera
  shots are sent as still images; no video track is published.
- Cost rates must be entered manually; there is no billing API integration.
- No automatic reconnect; no wake word or background/locked-screen service.
- No release signing, by design.
