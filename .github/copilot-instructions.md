# Copilot instructions

This repository is a private Android voice assistant. Before suggesting code,
read `AGENTS.md`.

Essential constraints:

- Kotlin + Jetpack Compose (Material 3), package `com.voicechat.assistant`.
- Qwen realtime API (DashScope) over WebSocket or WebRTC; one session shared by
  text and voice turns. Text input → text output, voice input → audio output.
- Never hard-code or print the API key. It is injected from the git-ignored
  `secrets/` CSV into `BuildConfig` at build time.
- Protocol helpers are in `realtime/`; keep transport code behind
  `RealtimeTransport` and keep `RealtimeSession` free of OkHttp/WebRTC types.
- Audio work must never block the main thread. Barge-in must stop playback
  before any network call.
- Debug logging goes through `AppLog` with a category and must redact secrets
  and binary payloads.
- Windows build: set `ANDROID_HOME` and `JAVA_HOME` (JDK 21), then run
  `.\gradlew.bat :app:assembleDebug`.
