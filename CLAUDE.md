# CLAUDE.md

Read `AGENTS.md` first; it is the single source of truth for this repository.

Quick reference:

- Private Android app (`com.voicechat.assistant`), Kotlin + Jetpack Compose +
  Material 3, single module `app`, Qwen realtime API over WebSocket/WebRTC.
- Build on Windows with JDK 21:
  `$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"; $env:JAVA_HOME="$env:USERPROFILE\.jdks\semeru-21.0.12.15"; .\gradlew.bat :app:assembleDebug`
- The API key comes from `secrets/qwen-apiKey-*.csv` via Gradle `BuildConfig`.
  Never commit it, never log it, never paste it into source or docs.
- One realtime session per conversation. Text turns request text-only output;
  voice turns request text+audio. Same context. Barge-in mutes playback and
  cancels the response immediately.
- Protocol details, module map, transports, logging/metrics rules and known
  limitations are in `AGENTS.md`. Update that file with code changes.
