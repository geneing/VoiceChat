# Hermes Agent API Server Adapter (M19)

This is the Hermes Agent API Server provider behind the M12
[`LanguageModel` contract](./llm-contract.md). It uses the shared M14
[remote transport](./llm-transport.md); this document records the verified
Hermes facts, the adapter's mapping, its privacy and destination behavior, and
its explicit limitations.

All provider facts below were **verified against the official Hermes Agent
documentation on 2026-09-29** (the M00 matrix was read 2026-09-28; this is the
re-verification R-0072 requires before an adapter ships). Sources are listed at
the end.

Hermes is **not** a fixed public endpoint: it is a user/admin-deployed server
that exposes an OpenAI-compatible API. There is therefore no hard-coded base URL
and no default destination; the app validates the user-supplied address with the
M13 rules and shows it before text is sent.

## Verified facts (accessed 2026-09-29)

| Concern | Verified behavior |
| --- | --- |
| Deployment | A self-hosted gateway. Default bind is `127.0.0.1:8642`; the address is set by `API_SERVER_HOST`/`API_SERVER_PORT` (or `gateway.api_server.*`). It is enabled with `API_SERVER_ENABLED=true`. |
| Authentication | `Authorization: Bearer <API_SERVER_KEY>`, **required for every deployment, including the default loopback bind**. `GET /v1/capabilities` reports `auth: {"type":"bearer","required":true}`. A missing/wrong key is rejected before any model or completion work. |
| Endpoints | `POST /v1/chat/completions`, `POST /v1/responses`, `GET /v1/models`, `GET /v1/capabilities`, `GET /health` (also `/v1/health`), plus runs/jobs/sessions/browser-control surfaces this build does not use. |
| Model | `/v1/models` advertises a single stable alias: the profile name, or `hermes-agent` for the default profile. `/api/model/options` is the richer Hermes-native picker (not used here). |
| Streaming | `"stream": true` returns SSE. Chat Completions uses standard `chat.completion.chunk` events **plus** a custom `hermes.tool.progress` named event for tool-start UX, terminated by a `data: [DONE]` sentinel. All SSE streams emit a `: keepalive` comment every 10 s so long tool calls do not trip idle timeouts. |
| Reasoning channel | Reasoning deltas arrive as `choices[0].delta.reasoning_content` (the DeepSeek-style field); answer text stays in `delta.content`. Reasoning is emitted only when the model produces it and the resolved `reasoning` config allows it. The input-side opt-out is `model_options.reasoning.enabled: false`. |
| Per-request model | `model`, `provider`, and `model_options` (`reasoning_effort`, `service_tier`) may be sent. A **bare `model` without `provider` is ignored** unless the server enables `gateway.platforms.api_server.direct_model_requests: true`; requests with an explicit `provider` always honor it. |
| Tool execution | Hermes is an **agent runtime**, not a proxy: tool calls (`terminal`, file, browser, MCP) are executed **server-side** and replayed in the response with `"status": "completed"`; the model's final answer is the assistant text. |
| Server-side state | `/v1/responses` supports `previous_response_id`/`conversation` chaining and stores history; `POST /api/sessions/{id}/chat` and the runs API provide session control. `X-Hermes-Session-Id` (transcript-scoped) and `X-Hermes-Session-Key` (long-term-memory scope) are optional continuation headers. |
| Multi-profile | When `gateway.multiplex_profiles` is enabled, profiles are served under `/p/<profile>/…` and each prefix is bound to that profile's own `API_SERVER_KEY`. |
| Security posture | The server "gives full access to hermes-agent's toolset, including terminal commands"; `API_SERVER_KEY` is required for every deployment, and responses carry `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`. |

### What is inferred rather than printed

The official docs name the frame types but do not print a full Chat Completions
SSE example. Two details are therefore **inferred from OpenAI Chat Completions
compatibility** and pinned by the local mock contract (below), not quoted:

- the terminal frame is `data: [DONE]` (the docs' integration guide states the
  stream "finally [sends] a `[DONE]` sentinel"; the adapter also accepts a
  non-null `choices[].finish_reason` as a fallback terminal signal);
- the exact payload of the custom `hermes.tool.progress` named event. The
  adapter ignores **every** named SSE event (it is never assistant text), so the
  exact payload does not matter for correctness.

Because no live Hermes server was run for this milestone (R-0150), the streaming
shape is verified against the docs plus the in-process `FakeHermesServer` mock
that serves the documented bytes over loopback. If the official docs become
unavailable or the live shape differs, the mapping is adjusted; the mock is the
documented contract for the deterministic tests.

## Which surface is wired

Only the OpenAI-compatible **Chat Completions** surface is implemented
(`POST {base}/chat/completions`). The Responses API, the runs API
(`POST /v1/runs`, `GET /v1/runs/{id}/events`, `/stop`), the sessions API, jobs,
and browser-extension control are documented but **not** surfaced (R-0151).

## Request mapping

`HermesChatCompletions.encodeRequest` builds the Chat Completions payload:

```json
{
  "model": "hermes-agent",
  "messages": [ { "role": "user", "content": "..." } ],
  "stream": true
}
```

- **Roles.** `USER`/`ASSISTANT` map to `user`/`assistant`; a `SYSTEM` message maps
  to `system` (Hermes documents standard Chat Completions and layers the system
  message on top of its own core prompt). The adapter does not reuse OpenAI's
  Responses `developer` role.
- **Reasoning.** `reasoning == null` omits `model_options`, so the server's
  configured default applies. `ReasoningLevel.NONE` sends the documented opt-out
  `model_options.reasoning.enabled = false`. **A real level is never sent**: the
  accepted `reasoning_effort` vocabulary is not enumerated in the docs, so the
  adapter declares no reasoning level and `LlmRequestValidator` refuses one before
  a request is built (R-0152).
- **Provider/model identity** stays explicit. The adapter sends the selected
  model alias and echoes the `model` the response reports on
  `LlmStreamEvent.Completed.model`, so a mismatch is visible (R-0017/R-0023).
  Because a bare `model` may be ignored unless the server enables direct model
  requests, the request is served by the server's configured default, which is
  the same alias `/v1/models` advertises (R-0158).
- **Statelessness.** The adapter sends the already-bounded M12 message list and
  no `previous_response_id`/session continuation header (unless a caller supplies
  a session id), so Hermes' server-side history features are unused (R-0157).

## Response mapping

| Hermes frame | Contract |
| --- | --- |
| `choices[].delta.content` (non-empty) | `Delta(content)` |
| `choices[].delta.reasoning_content` | **excluded** from `Delta` (R-0066) |
| `event: hermes.tool.progress` (any named event) | ignored; never assistant text |
| `: keepalive` comment | ignored by the SSE decoder |
| `finish_reason: "stop"` (or a `[DONE]` with no reason) | `Completed(usage, model)` |
| `data: [DONE]` | the end-of-stream sentinel; a completion |
| `finish_reason: "length"` | `Failed(LLM_MALFORMED_RESPONSE)` — a truncated answer is not whole |
| `finish_reason: "content_filter"` / other | `Failed(LLM_REQUEST_FAILED)` |
| a JSON `error` body in a 200 response | `Failed(typed error)` from the stable `type`/`code` |
| a frame whose `data:` is not valid JSON | `Failed(LLM_MALFORMED_RESPONSE)` |
| the stream ends with no `[DONE]` and no `finish_reason` | `Failed(LLM_MALFORMED_RESPONSE)` (R-0067) |
| role-only / empty-delta chunks | ignored |

`usage` and the serving `model` may ride the same frame as the `finish_reason`;
the adapter accumulates them and resolves the terminal event after the stream
stops, so a `[DONE]` immediately after a metadata chunk does not lose usage.

### Reasoning channel (R-0066)

The reasoning channel is a distinct `HermesStreamFrame.Reasoning`, produced from
`delta.reasoning_content`, and is **never** concatenated into assistant text. The
contract has no typed reasoning side channel yet, so the content is dropped; only
the reasoning *token count* (when reported on
`usage.completion_tokens_details.reasoning_tokens`) is surfaced as usage detail.
Test `theReasoningChannelIsExcludedFromAssistantText` proves the reasoning text
never appears in `LlmStreamResult.text`.

### Typed error mapping

`RemoteStatusMapper` (shared) maps statuses: 401/403 → `LLM_AUTHENTICATION_FAILED`,
408/504 → `LLM_TIMEOUT`, 429 → `LLM_RATE_LIMITED`, other 4xx →
`LLM_INVALID_REQUEST`, 5xx → `LLM_UNAVAILABLE`. A streamed `error` object is
mapped from its stable `type`/`code` (`rate_limit_exceeded` →
`LLM_RATE_LIMITED`, `authentication_error`/`invalid_api_key`/`permission_error`
→ `LLM_AUTHENTICATION_FAILED`, `server_error`/`api_error`/`overloaded_error` →
`LLM_UNAVAILABLE`, `timeout` → `LLM_TIMEOUT`, an unrecognized type →
`LLM_REQUEST_FAILED`). A dropped connection maps to `LLM_NETWORK_FAILED` and a
socket timeout to `LLM_TIMEOUT`, both in the transport. No provider `message`
becomes `VoiceAgentError.detail`.

## Destination, TLS, and disclosure

`HermesServerAddress` reuses the M13
[`ServerDestination`/`ServerDestinationValidator`](./credentials.md#destination-validation-and-secure-transport)
rules through `ProviderEndpointPolicy`:

- only `http`/`https` are accepted;
- an embedded `user:pass@host` is refused;
- **TLS (`https`) is required for any non-local host**; plain `http` is accepted
  only for a loopback address (`localhost`, `127.0.0.0/8`, `::1`);
- a QR-sourced value (`EndpointSource.QR_PAYLOAD`) is never accepted as an
  endpoint.

Every accepted address produces a `HermesServerConfig` whose `disclosure` (for
example `https://hermes.example.com/v1`) is what the settings/UI shows **before**
text leaves the device. There is deliberately **no default address**.

### Tools run on the server host (R-0019)

Hermes is an agent runtime. A configured remote Hermes server executes `pwd`,
file, browser, and MCP tools on its own host, and the response reports those tool
calls as already executed. The adapter therefore:

- carries `toolExecutionOnServer = true` on the registry entry and
  `HermesServerConfig.toolsRunOnServerHost`/`notice` on the config model;
- requires an explicit destination and TLS for non-local hosts;
- never bundles a public endpoint or a server-side secret in the APK.

`HermesServerConfig.TOOLS_ON_SERVER_HOST_NOTICE` is the disclosure string the
settings UI surfaces alongside the destination.

## Redirects and DNS (R-0074)

The shared OkHttp engine now disables redirects
(`followRedirects(false)`/`followSslRedirects(false)`), so a 3xx is surfaced as
its own status and a request **cannot** be silently redirected to an unintended
host. `HermesMockServerTest.aRedirectIsNeverFollowed` proves it against the local
mock server (the redirect target is never requested). Resolving DNS or defending
against DNS rebinding (a hostname that resolves to a private address) is **not**
done and remains open (R-0074/R-0154).

## Credential integration (M13)

`HermesLanguageModel` loads the credential from the M13 `CredentialStore` at
request time and places it only in the `Authorization` header:

- no credential → `LLM_NOT_CONFIGURED`, and no request is sent;
- the secret never reaches a result, trace, `VoiceAgentError.detail`, or the
  developer log
  (`HermesLanguageModelTest.theRequestAndStreamNeverPutTheCredentialOrContentInLogs`);
- `RemoteHttpRequest.toString` redacts header values, so even an accidental
  interpolation cannot leak the key.

`HermesCredentialValidator` implements the documented minimal check
(`GET /v1/models`): 2xx is `Valid`, 401/403 is `authenticationRejected()`,
another status keeps its typed code, and a transport failure is a typed `Failed`.
It reads only the status and never echoes the key or the response body. It has
not validated a real key (R-0156).

## Files

```
app/src/main/kotlin/com/voicechat/agent/providers/hermes/
  HermesProtocol.kt              frame model + typed error mapping
  HermesChatCompletions.kt       request encoding, frame parsing, model-list parsing
  HermesModels.kt                /v1/models + /v1/capabilities parsing + model catalog
  HermesServerAddress.kt         destination config model, validation, disclosure
  HermesLanguageModel.kt         LanguageModel adapter (credential + transport + destination)
  HermesCredentialValidator.kt   minimal GET /v1/models check
app/src/test/kotlin/com/voicechat/agent/providers/hermes/
  FakeHermesServer.kt            in-process loopback mock server (JDK HttpServer, no new dependency)
  HermesProtocolTest.kt          protocol mapping (no HTTP)
  HermesLanguageModelTest.kt     fixture-driven acceptance (all milestone cases + destinations)
  HermesCredentialValidatorTest.kt
  HermesMockServerTest.kt        real HTTP/SSE path over the loopback mock server
  HermesSmokeTest.kt             opt-in real-server smoke test
app/src/test/resources/llm/hermes/*.sse   recorded frame fixtures
```

No new dependency was added: the mock server uses the JDK's
`com.sun.net.httpserver`, and the adapter reuses OkHttp/kotlinx-serialization via
the shared transport.

## Tests

Every acceptance case is a recorded fixture, a scripted engine, or the loopback
mock server (no external network, no real credential):

| Case | Test |
| --- | --- |
| normal stream | `HermesLanguageModelTest.aNormalStreamCompletesWithTextModelAndUsage` (`normal_stream.sse`) |
| endpoint + bearer + streaming | `theRequestGoesToTheConfiguredEndpointWithBearerAuthAndStreaming` |
| optional session header | `anExplicitSessionIdIsSentAsTheDocumentedHeader` |
| destination disclosure | `theDestinationIsDisclosedBeforeAnyRequest` |
| empty response | `anEmptyResponseCompletesWithNoDeltas` (`empty_response.sse`) |
| malformed frames | `aMalformedFrameFailsWithMalformedResponseAndKeepsThePrefix` (`malformed_frame.sse`) |
| terminal-less stream | `aTerminalLessStreamIsNotACompletion` (`terminal_less.sse`) |
| truncated response | `aTruncatedResponseIsAFailureNotACompletionButKeepsThePartialText` (`truncated.sse`) |
| auth / rate-limit / server status | `httpStatusesMapToTheirTypedReasons` |
| mid-stream error events | `midStreamErrorEventsMapToTheirTypedReasons` (`mid_stream_error.sse`, `auth_error_event.sse`, `rate_limit_event.sse`) |
| server error keeps partial text | `aMidStreamServerErrorKeepsThePartialText` |
| network loss | `aNetworkLossKeepsThePartialTextAndReportsNetwork` |
| cancellation | `cancellingMidStreamCancelsTheEngineAndEmitsNoTerminalEvent` |
| reasoning excluded | `theReasoningChannelIsExcludedFromAssistantText` (`reasoning_channel.sse`) |
| missing credential | `aMissingCredentialFailsNotConfiguredWithoutSending` |
| unsupported reasoning | `aReasoningLevelTheAdapterDoesNotSupportIsRefusedBeforeAnyRequest` |
| reasoning opt-out sent | `aNoneReasoningRequestSendsTheDocumentedOptOut` |
| capability reporting | `theAdapterDeclaresOnlyTheVerifiedCapabilities` |
| valid/invalid/TLS/QR destinations | `validDestinationsIncludeLoopbackHttpAndRemoteHttps`, `insecureRemoteNonHttpsAndCredentialBearingDestinationsAreRefused`, `aQrSourcedDestinationIsNeverAccepted` |
| credential not logged | `theRequestAndStreamNeverPutTheCredentialOrContentInLogs` |
| protocol mapping | `HermesProtocolTest` (request, roles, reasoning opt-out, frames, tool event, `[DONE]`, errors, `/models`, `/capabilities`) |
| credential check | `HermesCredentialValidatorTest` |
| real HTTP/SSE over loopback | `HermesMockServerTest` (stream, auth failure, server error, redirect not followed, cancellation) |

The opt-in `HermesSmokeTest` is documented and marked **not run** in
[Tests.md](../Tests.md) § M19.

## Capability registry update (R-0072)

Re-verification at M19 updated the Hermes entry in `ProviderCapabilityRegistry`:

- `transport.unverified` no longer contains `STREAMING` (the API server
  documents SSE streaming for `/v1/chat/completions`);
- `models.unverified` now contains `REASONING` **and** `USAGE` (the streamed
  usage report and the reasoning-effort vocabulary are not documented);
- `models.reasoningLevels` stays empty and `usageReporting` stays `false`;
- `toolExecutionOnServer` stays `true`.

`HermesLanguageModelTest.theHermesRowClaimsStreamingVerifiedAndKeepsReasoningAndUsageMarked`
asserts the row.

## Limitations

- Only the OpenAI-compatible **Chat Completions** surface is wired; the Responses
  API, runs API, sessions API, jobs, and browser control are documented but not
  surfaced (R-0151).
- The live server has never been contacted; fixtures, the local mock, and the
  docs prove the mapping, not live latency, auth, or streaming quirks (R-0150).
- The reasoning-effort vocabulary is not documented, so no level is claimed and
  only the `NONE` opt-out is sent (R-0152).
- Streamed usage reporting is not documented and is not claimed; usage is echoed
  only when the server sends it (R-0153).
- DNS rebinding is not defended; only redirects are constrained (R-0074/R-0154).
- The app's manifest has no network security config permitting cleartext to
  loopback, so the allowed `http` loopback destination may be blocked on-device
  by the Android cleartext policy; the JVM tests are unaffected (R-0155).
- `HermesCredentialValidator` is fixture/mock-tested only (R-0156).
- The adapter is stateless and does not use Hermes session continuity or its
  long-term-memory headers (R-0157).
- A bare `model` may be ignored unless the server enables direct model requests,
  so per-request model selection may not take effect (R-0158).
- The terminal signal (`[DONE]` / `finish_reason`) is inferred from OpenAI
  compatibility; a server that closes without either is reported malformed
  (R-0159).
- No device or real-server run was performed for this milestone.

## Sources (accessed 2026-09-29)

- API Server (endpoints, auth, streaming, tool progress, reasoning, model
  selection, sessions, security, configuration) —
  https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
- Programmatic Integration (protocols, endpoint list, terminal run status, model
  hot-swapping) —
  https://hermes-agent.nousresearch.com/docs/developer-guide/programmatic-integration
- Hermes Agent repository — https://github.com/NousResearch/hermes-agent
