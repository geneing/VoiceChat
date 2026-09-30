# Shared Remote LLM Transport (M14)

This document defines the provider-neutral remote transport that the M14 OpenAI
adapter introduces and that every later remote adapter (M15 OpenRouter, M16
DeepSeek, M17 OpenCode Go, M18 OpenCode Zen, M19 Hermes) reuses. It is the HTTP,
Server-Sent Events, and JSON layer beneath the M12
[`LanguageModel` contract](./llm-contract.md); the app-facing contract itself
never sees an HTTP or JSON type. Provider-specific facts live in
[openai-adapter.md](./openai-adapter.md) and the later per-provider documents.

## Why a shared transport

The planned providers differ in payload and error bodies but share one wire
shape: a TLS POST that returns a Server-Sent Events stream. Re-implementing
timeouts, cancellation, frame decoding, size bounds, and typed error mapping in
each adapter would duplicate the privacy- and correctness-sensitive parts. One
small transport fixes them once:

- cancellation actually cancels the in-flight socket call;
- a dropped connection mid-stream is a typed `NETWORK` failure, not a silent end;
- provider bodies and credentials never reach a log;
- a recorded-stream fixture harness is reused by every provider's tests.

## Dependency choices (verified 2026-09-29)

| Concern | Choice | Version | License | Why |
| --- | --- | --- | --- | --- |
| HTTP (streaming) | OkHttp | 5.1.0 | Apache-2.0 | Streaming-capable, coroutine-friendly via a blocking `Call.execute()` on `Dispatchers.IO`, cancellable through `Call.cancel()`, and already the de-facto Android HTTP client. Ktor was the alternative; OkHttp is smaller and adds no engine choice. |
| JSON | kotlinx-serialization-json | 1.10.0 | Apache-2.0 | Tree-based `Json.parseToJsonElement` needs **no compiler plugin**, so no Gradle serialization plugin or code generation is added. Requires kotlin-stdlib 2.3.0, below the pinned 2.4.20 toolchain. |

Both are pinned in `gradle/libs.versions.toml` and declared in
`app/build.gradle.kts`. They resolve (`.\gradlew.bat :app:dependencies
--configuration debugRuntimeClasspath`): `com.squareup.okhttp3:okhttp:5.1.0` →
`okhttp-android:5.1.0` → `com.squareup.okio:okio:3.15.0`, and
`org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0` →
`kotlinx-serialization-core:1.10.0`.

The new `android.permission.INTERNET` is declared in the manifest with the
first adapter that sends a request off-device (M14), not with M13.

## Package layout

```
app/src/main/kotlin/com/voicechat/agent/remote/
  RemoteJson.kt             JsonNode tree + RemoteJson facade (the only file that
                            imports kotlinx-serialization)
  SseDecoder.kt             SseFrame + a pure, bounded SSE decoder
  RemoteTransport.kt        RemoteHttpRequest/Header, HttpStreamEvent,
                            HttpStreamingEngine seam, RemoteTransport,
                            RemoteStatusMapper, RemoteHttpResponse
  OkHttpStreamingEngine.kt  the only file that imports okhttp3
```

`RemoteSourcePurityTest` enforces that OkHttp appears only in
`OkHttpStreamingEngine.kt` and kotlinx-serialization only in `RemoteJson.kt`,
and that `domain/`+`contracts/` do not import the transport at all. This is the
mechanical form of "keep the HTTP/JSON library types inside the transport".

## The seams

### `HttpStreamingEngine`

The single replaceable boundary. It returns `Flow<HttpStreamEvent>`: one
`Head(status, headers)` followed by `Chunk(bytes)` slices. OkHttp is one
implementation; the fixture engine (test sources) is another. An implementation
maps its own transport failures to typed `VoiceAgentException`s; nothing above
it depends on a socket.

### `RemoteTransport`

Turns an engine into a `Flow<SseFrame>` (`streamSse`) and offers a bounded
buffered call (`fetch`) for a non-streaming check such as OpenAI's `GET /models`.
It owns the status mapping:

| HTTP status | `ErrorCode` |
| --- | --- |
| 401, 403 | `LLM_AUTHENTICATION_FAILED` |
| 408, 504 | `LLM_TIMEOUT` |
| 429 | `LLM_RATE_LIMITED` |
| other 4xx | `LLM_INVALID_REQUEST` |
| 5xx | `LLM_UNAVAILABLE` |
| other | `LLM_REQUEST_FAILED` |

`RemoteStatusMapper` never inspects a provider body, and a non-2xx body is
drained but never surfaced, so a provider error message cannot leak into a
`VoiceAgentError.detail`, a trace, or a log.

### `SseDecoder`

A pure decoder with the SSE field rules that matter:
`data` lines join with `\n`, a leading space after the colon is stripped,
`:` comments (keep-alives) are ignored, `event`/`id`/`retry` are captured, and a
final unterminated frame is flushed. It does not parse JSON. Emission is
suspending, so a slow consumer backpressures the producer; a line or frame above
`DEFAULT_MAX_FRAME_BYTES` (4 MiB) ends the flow with a typed
`LLM_MALFORMED_RESPONSE` instead of growing memory.

### `JsonNode` / `RemoteJson`

A small library-free tree that adapters navigate. `RemoteJson.parse` raises a
typed `LLM_MALFORMED_RESPONSE` on invalid JSON, and `stringify` renders compact
JSON. Building a request is `RemoteJson.obj(...)`; reading a response is
`stringField`/`intField`/`objField`/`arrField`. No kotlinx type leaves the
package.

## Behavior an adapter can rely on

- **Cancellation.** Cancelling collection of `streamSse` cancels the engine's
  in-flight call (OkHttp `invokeOnClose` → `Call.cancel()`), closes the socket,
  and propagates `CancellationException`. It is never converted to a typed
  failure.
- **Timeouts.** The OkHttp engine sets a 10 s connect timeout, a 30 s read
  timeout (the gap between body bytes), a 15 s write timeout, and **disables**
  `callTimeout`, because a legitimate reasoning stream can run for minutes. A
  socket timeout maps to `LLM_TIMEOUT`.
- **Retries.** None are implicit. `retryOnConnectionFailure(false)` and no
  backoff loop: a streamed response must not be silently replayed, and the typed
  `RATE_LIMITED`/`UNAVAILABLE`/`TIMEOUT` reasons carry `retryable = true` so the
  caller decides.
- **Redirects.** None are followed. The OkHttp engine sets
  `followRedirects(false)`/`followSslRedirects(false)`, so a 3xx is surfaced as
  its own status and becomes a typed failure; a request can never be silently
  redirected to an unintended host (added with the M19 Hermes adapter, risk
  R-0074). DNS resolution and DNS-rebinding defense are still out of scope.
- **Bounds.** Buffered bodies are capped (1 MiB default); frames are capped
  (4 MiB default). No unbounded buffering anywhere.
- **Privacy.** No transport method logs a request, header, credential, prompt, or
  body. `RemoteHttpRequest.toString` redacts header values and reports the body
  as a length only.

## Fixture harness (reused by M15–M19)

Test sources provide the reusable pieces:

- `remote.FakeHttpStreamingEngine` records requests and serves a scripted
  `Flow<HttpStreamEvent>`;
- `scriptedResponse`, `failingResponse`, `stallingResponse` build the normal,
  network-loss, and cancellable cases;
- `RecordedFixtures.text/openAi` loads `.sse` files from
  `app/src/test/resources/`.

A provider test needs only to add its own recorded `.sse` files and call
`RemoteTransport.streamSse` / its adapter. There is no socket, clock, DNS, or
credential in the harness, so every provider fixture is deterministic.

## Limitations

- One HTTP implementation (OkHttp). A future provider needing a different client
  implements `HttpStreamingEngine`; nothing else changes.
- No automatic retry, `Retry-After` handling, DNS-rebinding defense, or
  certificate pinning (R-0090, R-0094, R-0099, R-0154); these are tracked for M26.
  Redirects are now disabled, but DNS resolution and rebinding defense remain
  open.
- The transport is a TLS POST + SSE client. WebSocket transports (for example a
  future OpenAI WebSocket mode) are out of scope.
