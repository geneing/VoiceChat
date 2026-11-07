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

1. **API keys never enter git.** `secrets/` is git-ignored. Gradle reads
   `secrets/qwen-apiKey-*.csv` and search-provider `secrets/*.key` files at
   configuration time and injects the values into `BuildConfig`. Never copy a
   key into source, resources, tests, docs, logs or commit messages. Never log
   `Authorization` headers, provider key headers or full request URLs with
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
- Search keys: `secrets/exa.key` and `secrets/brave.key`. Each may contain a bare
  key or a `name=value` / `name,value` line — `keyFile()` extracts only the value
  after the last separator. Passing the whole line as the API key made Exa answer
  HTTP 401 on the device.
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
    audio/                   PcmRecorder, PcmPlayer + TimeStretcher (WSOLA speed control), SpeechGate (device VAD), level meter, focus/routing
    tools/                   ToolDefinitions (schemas) + ToolRegistry (execution)
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
`smart_turn_v2` (semantic + acoustic, much less self-interruption). Every
system prompt gets a delivery line asking for a brisk, slightly faster pace;
the server-side `speech_rate` option is ignored, so speech rate is adjusted in
local WebSocket playback instead.

WebSocket voice mode puts Android into `MODE_IN_COMMUNICATION`, selects a
connected headset's communication device when available (otherwise loudspeaker),
uses `VOICE_COMMUNICATION` capture/playback attributes and enables
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
- Server-side search is the default: `enable_search` + `search_options` go out
  with the session and the client `web_search` tool is dropped from `tools`
  (`ToolDefinitions.json(includeWebSearch = false)`), so the model has exactly
  one way to search. `get_current_datetime` and `get_location` stay available
  alongside it; turning native search off brings the client `web_search` back.
  Installs from before the switch are moved over once by
  `SettingsRepository.applySearchDefaultMigration`.
- `enable_search` runs the model in "agent mode", which rejects any `tools`
  array ("Agent mode does not support tools"). Only models with
  `ModelInfo.supportsToolsWithSearch` can do both; Qwen3.8 Omni cannot, so while
  client tools are on, `ModelInfo.resolveNativeSearch` keeps them and falls back
  to the client `web_search` (turn client tools off to use Omni's server search).
  Qwen Audio 3.1 supports both (verified on the device). Qwen Audio's function
  calling schema parser requires `required` on every tool. When client or native
  search is configured, session instructions explicitly require search for
  current/recent questions (news, headlines, weather, prices and events); do not
  claim live information is inaccessible before attempting the configured search.
- The client `web_search` provider is user-selectable: Exa (default, key from
  `secrets/exa.key`), Brave Search (key from `secrets/brave.key`), or DuckDuckGo.
  Keys are read at Gradle configuration time into `BuildConfig` and never logged.
  `SearchProvider` is persisted in settings. A search tries the selected provider
  first and then the remaining providers that have credentials
  (`searchFallbackChain`), so an empty result still answers. A provider that
  answers 401/403 is remembered as rejected for the session and reported to the
  user once (`onSearchIssue` → snackbar) instead of failing silently.
- `get_location` resolves the device position (network provider first, then GPS)
  and falls back to a fresh `LocationManager.getCurrentLocation` fix when the
  last-known location is older than 2 minutes. It also reverse-geocodes a place
  name. The tool result includes a reason when permission or the provider is
  missing. Location permission is requested when voice mode starts (optional: a
  denial only disables these tools). The session instructions require the model
  to call the tool for location questions instead of claiming it has no access.
- `get_weather` returns current conditions and today's high/low from Open-Meteo,
  which needs no API key and no search provider. Weather questions use it rather
  than `web_search`: searching raw coordinates returned unrelated results.

## On-device voice gate

Android exposes no public VAD API, so `realtime/audio/SpeechGate` derives speech
from the WebSocket capture stream. `PcmRecorder` keeps reading the microphone but
only forwards audio that belongs to a spoken turn:

- 1 s pre-roll ring buffer, flushed on onset so the first syllable is never lost.
- Onset needs 100 ms of speech above the trigger, which follows the measured
  ambient noise floor (fast down, slow up) to adapt to a quiet room or a noisy one.
- 1.2 s hangover keeps short pauses inside one turn.
- 1.5 s of digital silence is appended after the last syllable. Without it the
  server never closes the turn: a capture that stops right after speech produces
  no `speech_stopped` and the response dies with a 60 s
  "Response stream timeout" (measured).
- `onLevel` still fires for every chunk, so the mic meter and barge-in behave
  exactly as before.
- Capture stop logs `sent=Xms of Yms (Z%)`, the learned floor and the trigger.

Billing facts measured against the live API (both models, same 2.96 s utterance,
silence padded before and after):

| Turn detection | Audio streamed | `input_tokens_details.audio_tokens` |
| --- | --- | --- |
| `semantic_vad` (Omni), gate off | 13.96 s | 28 |
| `semantic_vad` (Omni), gate on | 6.35 s | 28 |
| `smart_turn_v2` (Qwen Audio), gate off | 13.96 s | 23 |
| `smart_turn_v2` (Qwen Audio), gate on | 6.35 s | 25 |
| `turn_detection: null` (manual), gate off | 13.96 s | 98 |
| `turn_detection: null` (manual), gate on | 6.35 s | 49 |

With server-side turn detection the server tokenizes only its own VAD window, so
silence around the turn is already free and the gate mainly buys bandwidth,
battery and privacy. Manual mode bills the whole client buffer (~7 audio tokens
per second), so there the gate is what keeps the cost down. Neither mode changes
the transcript.

Settings: "On-device voice gate" (`onDeviceVad`) and "Voice gate trigger above
noise" (`vadSensitivityDb`, dB above the noise floor) travel through
`SessionConfig` → `WebSocketTransport` → `PcmRecorder.vadConfig` and apply when
capture starts. WebRTC and AOQ cannot gate: the vendor SDK owns the live media
stream and a pre-roll cannot be replayed into it.

## Audio routing and speech speed

**Bluetooth headsets**: Android only exposes a headset microphone once the
system is in communication mode *and* the headset link is requested. The app's
`AudioFocus.enterVoiceMode()` selects the communication device on API 31+ or
starts SCO on API 26–30. Android 12+ asks for the `BLUETOOTH_CONNECT` runtime
permission before starting voice mode. On API 31+, Android automatically pairs
the selected communication output with its matching microphone; capture is
pinned to that input when exposed. Playback is pinned to the same communication
device with `AudioTrack.setPreferredDevice`, because the platform routed the
assistant to the phone speaker while the microphone was already on the headset.
If Android drops the SCO route mid-session (observed once: the active device fell
back to the earpiece while the headset stayed connected), the
`OnCommunicationDeviceChangedListener` re-applies `MODE_IN_COMMUNICATION` and the
headset route. A headset that connects mid-session triggers an `AudioDeviceCallback`, which reapplies the route and restarts capture against
the new input. Headset types are preferred in order BLE headset → SCO → USB →
hearing aid → wired → USB device; A2DP-only headsets have no microphone and are
not selected as capture inputs. When nothing external is connected, the
loudspeaker is forced for AEC reference.

**Speech speed**: the realtime API ignores every server-side hint (verified by
measuring audio duration at `speech_rate` 1.0/1.5/2.0 and nested variants like
`audio.output.speech_rate` and `voice_settings.speed`; all produced ~9.7 s of
audio for the same sentence). `AudioTrack.playbackParams` is not usable either:
on the Pixel 10 every combination of usage (voice communication, media,
assistant), sample rate (24/48 kHz) and performance mode (none, low latency)
throws `IllegalArgumentException: arguments out of range`, so the platform time
stretcher can never be enabled. The app therefore stretches its own PCM with
`realtime/audio/TimeStretcher` (WSOLA: 20 ms Hann windows, 50 % overlap, ±10 ms
similarity search) before writing to the track, which preserves pitch. The
selected rate is also included in the session delivery instructions so the model
phrases for that pace. Speed changes take effect immediately without
reconnecting. WebRTC and AOQ playback follow the vendor audio stack and cannot
be stretched locally. Settings expose a "Speech speed" slider (0.75×–2.0×,
default 1.25×) that travels through `AppSettings.speechRate` →
`SessionConfig.speechRate` → `WebSocketTransport.player.setSpeed()`. Bluetooth
headsets are selected as the capture route on WebSocket voice mode when Android
exposes a matching communication input.

## Transports

- **WebSocket** — full feature set: mic capture, streaming TTS playback with
  pitch-preserving speed control, barge-in, images, tools, works on emulators.
  The on-device voice gate is WebSocket-only. The realtime API ignores every
  server-side speech-rate hint (verified by measuring audio duration at
  `speech_rate` 1.0/1.5/2.0 and nested variants), and `AudioTrack.playbackParams`
  is rejected by the device, so the app stretches its own PCM with
  `TimeStretcher` before playback.
- **WebRTC** — lowest latency media path; `org.webrtc` owns mic, AEC, noise
  suppression and playback, so `PcmRecorder`/`PcmPlayer` are not used. VAD
  turns only. Attachments are not supported (protocol carries images on the
  video track, which this app does not publish). Surface that limitation in
  the UI instead of failing silently. Speech speed is not available for WebRTC
  because the vendor stack controls playback timing.
- **AOQ** — implemented in `realtime/aoq/` with Alibaba's
  `AoqClientSdk-release.aar` (v1.4.0) and `libPluginOpus.so` (in
  `app/libs` / `app/src/main/jniLibs`). The SDK owns capture, Opus, playback,
  AEC and the QUIC relay; realtime events travel as Data messages using the
  same payloads as WebSocket. Speech speed is not available for AOQ because the
  vendor SDK controls playback.
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
  conversation (system prompt + presets, speech speed slider, VAD, on-device
  voice gate + trigger, transcription, client tools + native search),
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
argument handling, text-turn vs voice-turn modality policy, and the
`SpeechGate` state machine (silence dropped, pre-roll flushed, tail silence,
adaptive floor, disabled pass-through). UI tests are not required; verify
screens on the emulator with `adb` screenshots.

## Known limitations / roadmap

- AOQ requires an arm64/armv7 device; the emulator is x86_64.
- WebRTC sessions cannot send image attachments yet.
- Image turns pause live mode: they need manual commits on WebSocket. Camera
  shots are sent as still images; no video track is published.
- Cost rates must be entered manually; there is no billing API integration.
- No automatic reconnect; no wake word or background/locked-screen service.
- The on-device voice gate only covers the WebSocket transport.
- No release signing, by design.
