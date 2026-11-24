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
3. **One conversation, two modalities.** Typed turns go directly to the configured
   reasoning model. Their completed question and answer are added to the realtime
   context without starting a second response, so subsequent voice turns can refer
   to them. The local transcript also supplies prior voice turns to the reasoning model.
4. **Voice output only for voice input.** A typed turn returns text from the reasoning
   model; a live/voice turn uses `["text", "audio"]`. The
   transcript of an audio answer is still shown as a normal assistant message.
5. **Barge-in must stay instant.** On `input_audio_buffer.speech_started` stop
   local playback immediately (clear the PCM queue / mute the remote track) and
   cancel the in-flight response. Never wait for the server round trip to mute.
   WebSocket clears playback directly in the socket callback and gates PCM by
   response ID. Normal completion lets queued audio drain. Interrupting or
   leaving voice mode discards queued PCM and invalidates the writer's current
   chunk before the communication audio route is released.
   The WebSocket PCM queue budgets up to 120 seconds of audio by byte count.
   If the limit is reached, it plays the contiguous queued prefix and drops
   the rest of that response. Audio logs record received and queued durations
   at response completion and flag truncation.
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
  core/                      AppLog, PromptCatalog (assets/prompts.json),
                             time/format helpers, Result helpers
  data/
    db/                      Room entities, DAOs, database
    prefs/                   DataStore settings + usage totals
    repo/                    ChatRepository (chats, messages, titles)
    attachments/             AttachmentManager (copy, scale, base64, text)
    model/                   UI/domain models, attachments, metrics
  memory/                    MemoryStore (CRUD, caps), MemoryExtractor (rules),
                             MemoryRanker (lexical scoring), MemoryPrompt (blocks,
                             tool JSON), MemoryGuard (sanitize, redact)
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
    audio/                   PcmRecorder, PcmPlayer, SpeechGate (device VAD), level meter, focus/routing
    tools/                   ToolDefinitions (schemas) + ToolRegistry (execution)
    handoff/                 deep_reasoning: ReasoningClient (SSE), HandoffPrompt, HandoffRouter, SpeculativeHandoff
    RealtimeSession.kt       protocol state machine: turns, tools, metrics
  ui/
    theme/                   Material 3 theme
    AppRoot.kt               drawer + routes
    chat/                    Chat screen, message bubbles, composer, voice waveform
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

Cloned voices (`qwen-voice-enrollment`, the "Voice Cloning" guide) belong to the
model passed as `target_model` and are named
`qwen-omni-vc-<name>-voice-<timestamp>-<suffix>` — no model id in front, so the
prefix rule cannot recognise them. `ModelInfo.clonedVoices` lists the ones this
workspace enrolled for that model, each with the label Settings shows and the
`ConversationMode` it belongs to, and `allowedVoices(mode)` is exactly that
mode's list: once a mode has clones of its own it offers and accepts **only**
those, so the vendor's system voices are neither listed nor used, and a voice
recorded for the other conversation never reaches it. The `<model>-myvoice-…`
form is accepted only by a mode with no clone listed. Keeping the system list
behind that switch matters: the dropdown clips its last entry, and 56 system
voices buried their own ones.

This workspace has eight clones on `qwen3.8-omni-flash-realtime`. Assistant mode:
`CLONED_OMNI_VOICE` ("Herm", from `Herm.wav`), its default,
`CLONED_OMNI_VOICE_ZACH` ("Zach", from `Zach.wav`),
`CLONED_OMNI_VOICE_JANICE` ("Janice", from `Janice.wav`), and
`CLONED_OMNI_VOICE_HOPE`, `CLONED_OMNI_VOICE_KRISTEN` and `CLONED_OMNI_VOICE_HANK`
("Hope", "Kristen", "Hank", from their `.mp3` files). Spanish tutor mode:
`CLONED_TUTOR_VOICE` ("Nacho", from `Nacho.wav`), its default, and
`CLONED_TUTOR_VOICE_ALT` ("Alejandro", from `Alejandro.wav`). Each mode keeps its
own choice (`AppSettings.voice` and `AppSettings.tutorVoice`, read through
`voiceFor(mode)`), so switching modes never overwrites the other, and the Voice
dropdown lists the voices of the mode currently selected in the app. A stored
system voice from an older build (an old "Tina" or "Evan") resolves to the
mode's default, so no migration is needed.

Enrolling another recording: it must be mono, ≥24 kHz, 16-bit WAV/MP3/M4A,
10–20 s of clean speech (the source recordings are 44.1 kHz 24-bit, some stereo,
so they are converted first with
`ffmpeg -i <file>.wav -ac 1 -ar 24000 -sample_fmt s16`), then
`POST https://{host}/api/v1/services/audio/tts/customization` with
`{"model":"qwen-voice-enrollment","parameters":{"voice_clone_mode":"normal"},
"input":{"action":"create","target_model":"qwen3.8-omni-flash-realtime",
"preferred_name":"…","audio":{"data":"data:audio/wav;base64,…"}}}` and the
workspace API key. The response's `output.voice` becomes a `ClonedVoice` entry
with the mode it serves (and `defaultVoice` if it should be the default). Action
`list` shows the account's clones. Cloning is billed per voice and a clone
expires after a year unused.

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
mono input, 24 kHz PCM output, server-side turn detection with a 0.35 VAD
threshold (lowered from 0.5 because the user had to speak up and repeat
themselves), input audio
transcription model `qwen3-asr-flash-realtime`. Qwen Audio 3.1 defaults to
`smart_turn_v2` (semantic + acoustic, much less self-interruption). Every
system prompt gets a delivery line asking for a brisk conversational pace and
the current date ("Today is …"), because the models have no clock of their own
and the vendor ignores every server-side `speech_rate` option — the pace comes
from the prompt, not from the client (see "Speech speed").

The default system prompt and the "Prompt preset" picker are data, not code:
they ship as `app/src/main/assets/prompts.json` (`version`, `default`,
`presets`, `knownDefaults`) and are read by `core/PromptCatalog`. A preset
without `prompt` applies `default`; that is how "General assistant" stays in
sync — its news recipe (top 3 world stories, today's market, 5 technical AI
items, every story dated) lives in `default`, so an install that never opened
the preset picker still gets it. Edit that file to change the wording, and bump
`version` in the same change: `SettingsRepository.applyPromptMigration` rewrites
a stored prompt that is still an empty value or an entry of `knownDefaults`, so
an install that never customised the prompt gets the new wording, while a prompt
the user wrote is left alone. `knownDefaults` therefore has to list every
default an earlier build stored *plus the current one* (a bump then recognises a
stored copy), but not preset texts — replacing a chosen preset with the default
prompt would be wrong. `applyConversationDefaultsMigration` still owns the
one-time VAD-threshold move.

`prompts.json` is the only prompt source: the fallback string in
`PromptCatalog` covers a missing or invalid asset and must stay a one-liner,
never a real prompt.

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
- `qwen3.8-omni-flash-realtime` sometimes ends a turn on a promise: the response
  completes with only a `message` item ("I'll pull the latest info on that") and
  never emits a `function_call`, so the user hears the preamble and waits
  forever. `response.done` reports `completed` and nothing errors. `TurnProgress`
  recognises the shape — the last sentence is a first-person promise of a lookup
  (`I'll check …`, `I need to check …`, `let me look …`) with no question mark —
  and
  `RealtimeSession.retryDeadTurn` answers it with one corrective
  `response.create` carrying per-response instructions; a second promise raises
  an error instead of hanging. The `Response done` log line lists `items=[…]`, so
  a missing `function_call` is visible in one line of the log.
- libwebrtc rejects the SDP answer unless it uses CRLF line endings and ends with
  a newline. `WebRtcTransport.normalizeSdp` does that; without it
  `setRemoteDescription` fails with "SessionDescription is NULL".

The following is the legacy realtime text-turn wire format, still used by the
protocol helpers. Normal typed questions now go directly to the reasoning model;
`typedExchangeContext` carries the completed exchange into the voice session.

```json
{"type":"conversation.item.create","item":{"type":"message","role":"user",
 "content":[{"type":"input_text","text":"..."}]}}
{"type":"response.create","response":{"modalities":["text"]}}
```

Typed image turns are sent as JPEG data URLs to a supported multimodal reasoning
model, independently of the voice transport. Legacy WebSocket image payload
helpers remain for protocol compatibility.

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
  search is configured, session instructions explicitly require a tool call for
  current/recent questions — `get_news` for news and markets, `web_search` for
  prices, products, people, schedules and events; do not claim live information
  is inaccessible before attempting the configured search.
- The client `web_search` provider is user-selectable: Exa (default, key from
  `secrets/exa.key`), Brave Search (key from `secrets/brave.key`), or DuckDuckGo.
  Keys are read at Gradle configuration time into `BuildConfig` and never logged.
  `SearchProvider` is persisted in settings. A search tries the selected provider
  first and then the remaining providers that have credentials
  (`searchFallbackChain`), so an empty result still answers. A provider that
  answers 401/403 is remembered as rejected for the session and reported to the
  user once (`onSearchIssue` → snackbar) instead of failing silently.
- **News is composed by the large text model, not the realtime model.** A
  generic search for "top news today" ranks a homepage first, and its cached
  highlight mixed a fresh story with months-old sidebar text (the Lindsay Clancy
  trial, a Bartiromo ouster) that the realtime model read out as today's news.
  `get_news` (`ToolDefinitions.NEWS_NAME`, no arguments, called once) now runs
  `RealtimeSession.startNews` off the session mutex: the app collects dated
  items (`ToolRegistry.newsReference`, `NewsSections`: world "top world news
  today", markets "stock market today … what moved the market", AI releases and
  papers; Exa `category=news` + `startPublishedDate` two days + `livecrawl`,
  Brave `/res/v1/news/search?freshness=pd`, DuckDuckGo `df=d`), and the reasoning
  model writes one speak-ready digest (`NewsPrompt`: most impactful world news
  in 5-6 sentences, US/world markets with bonds and technology trends in 5-6
  sentences, then 2-3 AI paragraphs) that the realtime model only reads out.
  This is the fallback when the configured reasoning model lacks native search.
  For supported reasoning models, built-in search is preferred instead.
  Every call is billed through `billReasoning`; a digest failure falls back to
  handing the raw dated items to the realtime model, so news still answers.
  Sections are cached for 10 minutes, fetched at most once at a time (prefetch
  and tool call share the query) and warmed from a news-like transcript. Do not stage this into one call per
  section: three tool calls in a turn produced garbled audio and no earlier
  speech.
- `web_search` still covers everything that is not news (prices, products,
  people, schedules, events). `searchExa` livecrawls and, when `SearchRecency`
  recognises a news-ish query ("news", "today", "latest", "this weekend", …),
  limits results with `startPublishedDate`; every result's publication date is
  passed on as `published` (Brave's `page_age`) and the tool result carries
  `today`, so a stray news query is still dated rather than recalled.
- `get_location` resolves the device position (network provider first, then GPS)
  and falls back to a fresh `LocationManager.getCurrentLocation` fix when the
  last-known location is older than 2 minutes. It also reverse-geocodes a place
  name. The tool result includes a reason when permission or the provider is
  missing. Location permission is requested when voice mode starts (optional: a
  denial only disables these tools). The session instructions require the model
  to call the tool for location questions instead of claiming it has no access.
- `get_weather` uses Open-Meteo for current conditions, a date or relative day,
  a multi-day forecast, or a local hour at the requested location. The default
  is today at the device location. Open-Meteo needs no API key.
- `find_nearby`, `start_navigation` and `get_trip_eta` support local discovery,
  handoff to the installed Maps app and driving estimates. Maps handoff needs
  no key. OpenStreetMap provides local search and routing without live traffic
  when the optional `secrets/google-maps.key` is absent. A restricted key with
  Places API (New) and Routes API enables Google results, search along a
  planned route and traffic-aware estimates. Android does not expose the
  live navigation session through the Maps handoff.
- Phone actions resolve contacts on-device. Settings grants Contacts and
  Phone permissions separately. A pending action requires the corresponding
  later user confirmation. A WhatsApp handoff creates a personal draft and
  WhatsApp handles the send. Pocket Casts receives its documented Android
  playback intents. Android can continue an explicitly started live session
  after a handoff through the microphone foreground service.
- `get_reddit_briefing` searches dated, indexed threads via Exa, with no
  Reddit login. Treat snippets as search evidence and keep the links in chat.
  `save_for_later` retains up to 100 notes and links on the device.

## Model handoff (deep reasoning)

The realtime models are tuned for conversation. Hard turns — multi-step maths,
code, derivations, careful analysis, long documents — are handed to a stronger
text model on the same OpenAI-compatible endpoint, and its answer comes back
through the same session. Nothing reconnects, the model picker never appears and
the modality policy is unchanged: text turns answer as text, voice turns answer
as audio.

`realtime/handoff/`:

- `ReasoningModels` — catalogue and `ThinkingMode` (see the table below).
- `HandoffConfig` — per-session snapshot: model, thinking budget, timeout,
  context turns, `forceForTypedTurns`, `speculativeVoice`, price/1M tokens.
- `HandoffPrompt` — pure: system prompt, dossier, request body, `## Answer` /
  `## Spoken summary` parsing, `function_call_output` JSON, chat card text.
- `ReasoningClient` — SSE over OkHttp; per-call cancellation (a cancelled
  coroutine is registered on its own `Call`, so a speculative call and a tool
  call never abort each other), error mapping (401/403/404/429), `usage` and
  `completion_tokens_details.reasoning_tokens` from the final chunk.
- `HandoffRouter` — deterministic scorer for typed pre-reasoning and for
  speculative voice warm starts.
- `SpeculativeHandoff` — one warm call at a time, reused only when the tool's
  `task` overlaps the transcript (Jaccard ≥ 0.5).

Escalation has three triggers and one engine:

1. **Voice, model-driven.** The session advertises a client tool
   `deep_reasoning` (`task`, `depth: brief|detailed`). The model calls it; the
   answer returns as `function_call_output`
   `{"ok":true,"model":…,"spoken_summary":…,"answer_omitted":…,"reasoning_tokens":…,"elapsed_ms":…}`
   followed by the usual single `response.create` after `response.done`, with
   `["text","audio"]` for a voice turn. A spoken turn gets `spoken_summary` only —
   the full answer makes the realtime model read a wall of text aloud, so it stays
   in the chat card (`HandoffPrompt.toolOutput(trace, includeAnswer = false)`); a
   typed turn still receives `answer`. The delivery instructions tell the model to
   acknowledge in one short spoken sentence while it runs, lead with
   `spoken_summary`, and never read `answer` verbatim.
2. **Typed, direct.** Every typed turn is answered by the configured reasoning
   model, regardless of the handoff heuristic. The answer is displayed directly
   and the completed exchange is added to the realtime voice context. Native web
   search is enabled for documented supported models; Qwen 3.8 Flash/Max also
   receive image attachments. Other reasoning models reject image attachments
   with a visible error. Requests for on-device actions need voice tools.
3. **Voice, speculative (default on).** On `input_audio_buffer` transcription
   completion, a transcript that clears the same bar as escalation warms a
   reasoning call while the realtime model composes its acknowledgement. If the
   model then calls `deep_reasoning` for the same question, that call is awaited
   (often already finished); otherwise it is dropped at `response.done`, on
   barge-in, live-mode off, interrupt, stop, shutdown or reconnect.

Handoff runs off the session mutex and reports through
`SessionListener.onHandoff` (chat card + snackbar on failure) and `onUsage`.
Every reasoning call is billed exactly once where it returns, including a warm
call the model never uses; cost uses the two optional price fields. A failure is
never fatal: the tool returns `ok:false` with a hint, and the realtime model
answers from its own knowledge and says briefly that deep reasoning was
unavailable.

Verified against this workspace (streaming, `include_usage:true`):

| Model | Thinking | Notes |
| --- | --- | --- |
| `qwen3.8-flash` | budget | default; newest fast thinker |
| `qwen3.8-max` | budget | strongest |
| `qwen3.7-max` | budget | previous generation |
| `qwen-plus`, `qwen-flash` | budget | cheaper thinkers |
| `qwq-plus-2025-03-05` | always | ignores and rejects `thinking_budget` |
| `qwen-max` | none | no `reasoning_content` |

`ThinkingMode` decides the body: `BUDGET` sends `enable_thinking` plus
`thinking_budget` (or `enable_thinking:false` when the budget is 0), `ALWAYS` and
`NONE` send neither. A budget sent to a model that rejects it fails the whole
request (`kimi-k3` answers "Parameter thinking_budget is not supported"), which
is why the mode is per model. `HandoffConfig.resolvedModel` falls back to the
catalogue default when a stored id disappears.

The handoff is a client tool, so it needs client tools enabled. The
`qwen3.8-omni-flash-realtime` model in server-search agent mode rejects a `tools`
array; `ModelInfo.resolveNativeSearch` already falls back to the client
`web_search` in that case, so enabling the handoff keeps working instead of
silently never firing.

## Spanish tutor

- The top-of-chat Assistant / Spanish tutor buttons select separate conversations.
  Explicit spoken commands such as "switch to Spanish tutor" and "back to voice
  chat" work locally from final transcripts, including with client tools disabled.
  `switch_conversation_mode` handles other explicit requests when tools are enabled.
- `ConversationMode` is independent of input modality: each mode still shares a
  realtime session between its own text and voice turns. Switching closes the old
  session and drains pending database saves before selecting the other mode. A
  previously enabled microphone resumes; a button never starts an idle microphone.
- `spanish-tutor.db` is a separate `AppDatabase` instance with the same migrations
  as `voicechat.db`. It owns tutor chats and a separate `MemoryStore`. Never route
  tutor memory tools, chat deletion, or history queries to the assistant database.
- `spanish_tutor_progress` DataStore holds a cumulative summary, estimated CEFR
  level (initial A2), vocabulary/grammar attempts, corrections, 60 recent exercises
  and 24 recent dialog turns. `TutorProgress` merges partial updates by canonical
  topic. Its opening and recap context includes the cumulative summary, up to 12
  weak/old entries per category, 12 recent activities and 6 short dialog excerpts;
  `recall_tutor_progress` retrieves older items. New summaries target 600 characters
  while existing summaries remain intact. `record_tutor_progress` records model-evaluated outcomes;
  saved user/assistant turns automatically retain a bounded redacted dialog too.
  `TutorLearning` backstops skipped realtime tool calls with a serialized background
  text-model recap after a completed exchange. It uses the configured reasoning
  model without thinking, bills lifetime and message usage, and deduplicates updates
  by learner turn ID against the tool path. Its prompt is `spanishTutorMemory` in
  the same asset. Failure preserves raw dialogue and shows a notification.
  Level changes require several evaluated turns, evidence and at most one CEFR step.
- Tutor mode enables supported client tools and transcription for learning records,
  and disables native search agent mode. Its Progress panel can inspect/reset the
  learning record. Generic memory management applies to the selected mode's facts.
  Progress reset preserves chats and personal facts. Learning context is sent to
  the vendor as prompt text, just like regular device memory.
- The tutor's voice is its own setting (`AppSettings.tutorVoice`, Nacho by
  default) and its dropdown lists only the clones recorded for the tutor, never
  the assistant's English voices — see "Cloned voices".
- `assets/prompts.json` field `spanishTutor` is the tutor prompt source, loaded by
  `PromptCatalog`. Keep it separate from the customizable assistant prompt. It
  explicitly uses both Spanish and English in voice/text lessons, teaches Mexican
  Spanish, prioritizes conversation, chooses varied exercises,
  includes translation in both directions and explains corrections in English.
  Lesson questions adapt to ability, one at a time, with brief feedback and an
  immediate next question. Word translations include a source-language example
  without revealing the answer; scenes and activities change regularly. Requests for learner instructions
  or clarifications use basic Spanish or English. Tutor delivery overrides
  the assistant's brisk pace with a learner-friendly pace. Pronunciation feedback
  requires actual voice evidence; never infer it from text/ASR alone. Automatic
  personal-fact extraction is skipped for fictional tutor practice sentences.

## Device memory

The assistant remembers durable facts about the user between chats: name, home,
preferences and dislikes, allergies, people, places, routines, projects and
anything the user explicitly asked to remember. Memory is a **device** feature —
SQLite rows plus string work in `memory/`. No model runs locally, no embedding is
computed, no vector runtime is bundled, and nothing is uploaded as a collection.
The facts that a turn uses do travel to the vendor as prompt text, exactly like
the system prompt; say that plainly in Settings rather than calling the feature
"private".

- **Storage**: Room table `memory_facts` (`AppDatabase` v2, `MIGRATION_1_2`).
  Never bump the version without a migration: `fallbackToDestructiveMigration`
  would wipe every chat. There is no FTS table on purpose — a few hundred rows
  are ranked in one pass, which also handles scripts written without spaces.
- **Extraction** (`MemoryExtractor`, pure): narrow regex families run over each
  final user turn — typed or the server's voice transcript — in `AppViewModel`
  and, off the turn path, in `MemoryStore.learn`. `cleanValue` stops a capture at
  the first conjunction (speech has no punctuation), rejects pronoun objects
  ("I like that idea"), and `MemoryGuard` refuses secrets, card numbers and
  paragraphs. Facts dedupe on `kind:subject`, so saying the same thing twice
  updates the row; preference polarity lives in the subject (`likes:` /
  `dislikes:` / `allergy:`), so changing your mind does not leave both behind.
- **Ranking** (`MemoryRanker`): token IDF over the corpus with a pinned boost,
  a 30-day recency half-life and a use-count term. Stopwords plus the "user"
  word itself keep generic text from matching. CJK tokens are bigrammed. There is
  no absolute score floor — one real word hit is signal in a small corpus, and
  the block size is the cap.
- **Injection**: `SessionPayloads.sessionUpdate(…, memoryBlock = …)` appends the
  standing block after the prompt and before the date/tool rules. It is read by a
  lambda passed to `RealtimeSession`, deliberately not carried in `SessionConfig`:
  a `remember_fact` call during a conversation changes memory, and a changing
  config would make `ensureSession` restart the session and drop its context.
  Refresh happens on the next open or reconnect.
- **Typed turns** also carry the top matching facts in the same user item
  (`MemoryPrompt.withTurnContext`). Voice turns cannot: the server transcribes
  them, so they rely on the session block and the `recall_memory` tool.
- **Tools**: `recall_memory`, `remember_fact`, `forget_fact` in
  `ToolDefinitions`/`ToolRegistry`, gated on the memory setting *and* client tools.
  Their instructions live in `SessionPayloads` and are gated on the schema's own
  name, like weather and location. Every schema carries a `required` array.
- **Caps**: 400 facts (`MemoryStore.MAX_FACTS`, least used unpinned pruned first),
  12 facts / 700 chars in the session block, 4 facts / 340 chars per turn.
- **Settings**: `memoryEnabled` (learn, inject, tools) and `memoryPerTurn` (typed
  turns only). `memoryEnabled` is in `sessionKey()`, so switching it restarts the
  session; `memoryPerTurn` is read per turn. The Memory screen lists facts with
  edit, delete and erase-all.
- **Failures are never fatal**: a read or lookup error logs under `MEMORY` and
  yields an empty block; learning is fire-and-forget on `Dispatchers.IO` so it can
  never delay a reply. Cancellation propagates.

## On-device voice gate

Android exposes no public VAD API, so `realtime/audio/SpeechGate` derives speech
from the WebSocket capture stream. `PcmRecorder` keeps reading the microphone but
only forwards audio that belongs to a spoken turn:

- 1 s pre-roll ring buffer, flushed on onset so the first syllable is never lost.
- Onset needs 100 ms of speech above the trigger. The ambient noise estimate
  calibrates at startup, then follows changes with a 15-second time constant in
  both directions. Speech prevents upward adaptation for the first 10 seconds;
  after that sustained noise can raise the floor so the gate eventually closes.
  A long continuous utterance is indistinguishable from sustained noise to this
  energy-only detector and may slowly raise the floor after that grace period.
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

**Speech speed**: the pace comes from the prompt, not from the client. The
vendor ignores every server-side hint (measured: `speech_rate` 1.0/1.5/2.0 and
nested variants like `audio.output.speech_rate` and `voice_settings.speed` all
produced ~9.7 s of audio for the same sentence), `AudioTrack.playbackParams` is
rejected by the Pixel 10 with `IllegalArgumentException: arguments out of
range` for every configuration, and a client-side WSOLA stretcher (the old
`TimeStretcher`) cannot work on a live AOQ feed because the SDK hands the
decoded stream over at exactly 1x — the player starved and the answer broke up
(measured frame delivery 0.89–0.98x against a 1.4x consumer). The slider, the
`speechRate` setting, `SessionConfig.speechRate`, `setPlaybackSpeed` and the
stretcher were therefore removed. Both the system prompt and the session
instructions ask for a brisk conversational delivery ("Speak at a faster
rate"), which is what the models actually follow.

## Transports

- **WebSocket** — full feature set: mic capture, streaming TTS playback,
  barge-in, images, tools, works on emulators. The on-device voice gate is
  WebSocket-only. Playback is plain `PcmPlayer` at the received rate; the pace
  comes from the prompt (see "Speech speed").
- **WebRTC** — lowest latency media path; `org.webrtc` owns mic, AEC, noise
  suppression and playback, so `PcmRecorder`/`PcmPlayer` are not used. VAD
  turns only. Typed image attachments use the multimodal reasoning API rather
  than the WebRTC video track.
- **AOQ** — implemented in `realtime/aoq/` with Alibaba's
  `AoqClientSdk-release.aar` (v1.4.0) and `libPluginOpus.so` (in
  `app/libs` / `app/src/main/jniLibs`). The SDK owns capture, Opus, AEC and the
  QUIC relay; realtime events travel as Data messages using the same payloads
  as WebSocket. The SDK renders the received audio itself and keeps its
  hardware AEC reference.
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
  - Typed image attachments use the multimodal reasoning API, not AOQ video.
- Typed questions need both the reasoning endpoint and the realtime session for
  shared conversation context. Show failures instead of silently falling back.

## Connection resilience

The link to DashScope drops often on mobile networks, so `RealtimeSession`
recovers on its own instead of ending the chat:

- **Opening a session** (`start`) tries `ReconnectPolicy.INITIAL_ATTEMPTS` times
  (250 ms, 600 ms backoff). `ensureSession` in `AppViewModel` reuses a connected
  transport, so a chat keeps one session across turns.
- **A failed open that a retry can fix keeps retrying.** After those three
  attempts the session no longer drops to a dead error state: a non-permanent
  failure (a DNS or interface blip — "Unable to resolve host" is the usual one,
  measured as a 36 s dead microphone while the user tapped voice mode and the
  app had already given up in 1 s) enters the same recovery loop a dropped
  session uses, so it retries for ~30 s and then waits for the network. Only
  `ReconnectPolicy.isPermanent` failures end the session immediately.
- **A dropped session** (`RealtimeEvent.Failure` with `recoverable = true`, or an
  unexpected `RealtimeEvent.Closed`) starts a recovery loop of
  `ReconnectPolicy.RECOVERY_ATTEMPTS` (10) attempts at 250 ms → 5 s backoff
  (~30 s in total) while `LiveStatus.RECONNECTING` is shown. Live mode survives
  the reconnect: the new transport is opened with the microphone enabled again.
- **A network outage does not end the session.** When the loop runs out of
  attempts (an unresolved host is the usual case) the session keeps
  `active = true`, `liveEnabled` and `config`, records `awaitingNetwork`, and
  shows the error banner. The next `onAvailable` calls `nudgeReconnect()`, which
  restarts the recovery loop, so a DNS or Wi-Fi outage that outlasts the backoff
  still resumes without the user tapping Retry. Only `stop()`/`start()` clear it.
  `nudgeReconnect` must not be gated on `closingTransport`: that flag is still
  true from the last failed open, and gating on it left a phone that regained
  Wi-Fi with an idle "Connection problem" banner for as long as the app stayed
  open (observed 11 minutes after the interface came back).
- **Every attempt is bounded** by `ReconnectPolicy.connectTimeoutMs` (20 s for a
  first attempt, 8 s for retries), so a dead network cannot make one attempt
  wait out the full TCP timeout and stall the loop.
- **Errors a retry cannot fix** are never retried: `ReconnectPolicy.isPermanent`
  matches a missing/rejected API key and the AOQ ABI limitation, and
  `RealtimeEvent.Failure(recoverable = false)` marks capture failures. Those go
  to `SessionUiState.error` straight away.
- **Context survives the reconnect.** The server conversation is new, so the
  turns `RealtimeSession` still remembers are folded into the instructions by
  `SessionPayloads.resumeInstructions` before the first `session.update`. The
  recap is capped (12 turns, 4 000 chars) and marked as already-said, so the
  model continues instead of restarting. `stop()` clears the transcript: an
  explicit stop, a new chat or a settings change starts a clean conversation.
- **Network changes are handled, not waited out.** `AppViewModel` registers a
  default network callback. `onAvailable` compares the new default network with
  the last one: on a switch (Wi-Fi → mobile, mobile → Wi-Fi, hotspot, VPN) it
  waits `NETWORK_SETTLE_MS` (600 ms) for the interface to validate and calls
  `RealtimeSession.onNetworkChanged()`, which redials on the new interface
  through the recovery loop; on the same network it only calls
  `nudgeReconnect()`. `onLost` logs the loss and keeps `lastNetwork` so the next
  `onAvailable` can still tell a switch from a retry. Redialling is
  intentional: the old socket was bound to the route that just disappeared and
  would otherwise sit dead until the 20 s keep-alive fired, while the recap
  keeps the conversation and live mode comes back with the new transport.
- **Keep-alive** is OkHttp's WebSocket ping (`pingInterval` 10 s on
  `realtimeClient`), so an idle socket is not reaped and a dead link fails into
  the recovery path within ~20 s. AOQ/WebRTC keep their link inside the vendor
  stack.
- **The screen stays on while voice mode is live.** `KeepScreenOn` in `AppRoot`
  holds `FLAG_KEEP_SCREEN_ON` while `liveEnabled || reconnecting`, so the display
  (and the radio with it) does not sleep mid-conversation and Doze cannot
  suspend the session. The flag is cleared as soon as voice mode ends.
- The error banner offers a manual **Retry** (`AppViewModel.reconnect`) that
  restores the previous mode, for when every automatic attempt failed.

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

### Debug logs and storage

- **In-app log viewer.** Open **Debug logs** from the chat drawer to inspect
  the last 1,500 enabled entries retained in process memory. **Copy all** copies
  that buffer, and **Clear** clears the buffer only. A process restart starts a
  new buffer. The logger also writes to Android Logcat under the `VoiceChat`
  tag.
- **Persistent app logs.** Enable **Settings → Debug → Write log file** to
  write new entries to `filesDir/logs/app.log`. It defaults to off. The previous
  file is `app.log.1`, and rotation occurs at about 1 MiB. On the primary Android
  user, the debug build's directory is
  `/data/user/0/com.voicechat.assistant.debug/files/logs/`. The package name for
  an install without the debug suffix is `com.voicechat.assistant`. These
  directories are app-private.
- **Filters and redaction.** **Settings → Debug** controls categories and
  **Log event payloads**. Protocol dumps redact binary audio and image
  payloads and credentials.
- **Issue reports** are captured on demand. Tap the bug button in chat or say
  “Save an issue report” followed by the description in one transcribed voice
  turn. The local transcript command works without client tools, skips
  speculative reasoning, and uses the same capture path as the button. Save
  confirmation comes from the app. The button works while disconnected.
  `IssueReportStore` saves one folder per report under
  `noBackupFilesDir/issue-reports/`. For the debug build on the primary Android
  user, this is `/data/user/0/com.voicechat.assistant.debug/no_backup/issue-reports/`.
  Each folder contains `issue.md`, `app.log`, `android-logcat.log`,
  `llm-interaction.log`, and `network.log`. The report's `app.log` snapshots
  the retained in-memory entries from the preceding five minutes, so capture
  works with **Write log file** off. **Debug logs → Issue reports** exports the
  selected folder as a ZIP to the destination chosen in the Android file picker.
  Device-wide Android, Wi-Fi and radio buffers require ADB or privileged access.

On the Windows host, use ADB to read logs from a connected debug build:

```powershell
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
& $adb shell run-as com.voicechat.assistant.debug ls -l files/logs
& $adb exec-out run-as com.voicechat.assistant.debug cat files/logs/app.log
& $adb logcat -d -s VoiceChat
```

## UI conventions

- Material 3, dark/light + dynamic colour on Android 12+, no XML layouts.
- Drawer: new chat, history (grouped by day), long-press → rename/delete,
  footer → Settings, Debug logs, Usage.
- Top bar: menu, chat title, connection dot, gear.
- Chat: `LazyColumn`, user right / assistant left. Interim voice recognition
  updates a user bubble in place. Its final transcript keeps the same bubble
  ID when written to Room. Audio answers show a small waveform badge.
- Composer: `+` (photo / camera / document), expanding text field, send,
  sound-wave button for live mode. Send and sound-wave are separate 42 dp
  targets with a gap; never let IconButton's 48 dp touch target overlap them.
  Typing and sending stops live mode; capture only starts from the sound wave.
- A standalone microphone waveform appears while voice mode is on. Tap it
  during a response to interrupt, or use the composer sound-wave button to
  exit voice mode. Below the waveform, the traffic strip shows media
  sent/received (B/KB/MB) and totals for realtime input/output tokens when the
  server has reported usage. The top-bar dot is green when audio is received,
  blue while VAD detects the user speaking, amber when connected but silent,
  and gray when disconnected.
- Settings: connection (transport, model, voice, workspace host, API key),
  conversation (system prompt + presets, preferred language, VAD, on-device
  voice gate + trigger, transcription, client tools + native search),
  memory (remember between chats, use in typed turns, manage/erase), deep
  reasoning (model, budget, timeout, context, speculative voice), metrics/cost
  rates, debug categories + log viewer.
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
argument handling, text-turn vs voice-turn modality policy, the handoff request
shape (`Authorization`, thinking mode per model), the speculative warm-call
lifecycle, the news section mapping and recency window, result-date parsing,
memory extraction/dedupe/secret rejection, memory ranking and block caps, and the
`SpeechGate` state machine (silence dropped, pre-roll flushed, tail silence,
adaptive floor, disabled pass-through). UI tests are not required; verify screens
on the emulator with `adb` screenshots.

## Known limitations / roadmap

- AOQ requires an arm64/armv7 device; the emulator is x86_64.
- Speech pace is prompt-only: the vendor ignores server-side speed hints and
  the device rejects AudioTrack rate changes, so there is no speed slider.
- Image turns pause live mode. Camera shots are still images; no video track is published.
- Cost rates must be entered manually; there is no billing API integration.
- Deep reasoning needs client tools; with server-side search in agent mode on
  `qwen3.8-omni-flash-realtime` the app falls back to the client `web_search` to
  keep the tool available. Speculative voice warm calls can spend tokens the
  realtime model never uses.
- No wake word or background/locked-screen service: voice mode keeps the screen
  awake instead (see "Connection resilience").
- The on-device voice gate only covers the WebSocket transport.
- Memory extraction is rule-based, so a fact stated in an unusual paraphrase is
  missed (it is never wrong, which matters more: a bad fact is injected into
  every later session). A batched text-model pass over a finished chat is the
  next step, not a per-turn one.
- Voice turns get the session block and `recall_memory`, not a per-turn memory
  block: the server transcribes them, so the app cannot extend the user item.
- No release signing, by design.
