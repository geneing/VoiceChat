# OpenAI Adapter (M14)

This is the first complete remote provider behind the M12
[`LanguageModel` contract](./llm-contract.md). It uses the shared
[remote transport](./llm-transport.md); this document records the verified
OpenAI facts, the adapter's mapping, and its privacy behavior.

All provider facts below were **verified against the official OpenAI
documentation on 2026-09-29** (the M00 matrix was read 2026-09-28; this is the
re-verification R-0072 requires before an adapter ships). Sources are listed at
the end.

## Verified facts (accessed 2026-09-29)

| Concern | Verified behavior |
| --- | --- |
| Endpoint | `POST {base}/responses` — base `https://api.openai.com/v1`, so `https://api.openai.com/v1/responses`. The Responses API is the documented path for reasoning models and streaming typed events. |
| Authentication | `Authorization: Bearer <API key>`, user-supplied. The M13 registry already pins `api.openai.com`, so a substituted host is refused before a request is built. |
| Model discovery | `GET {base}/models` returns `{ "object": "list", "data": [ { "id", ... } ] }`. It lists **model identities only**; per-model reasoning levels are not in this response. |
| Streaming | `"stream": true` returns `text/event-stream`. Each frame is `event: <type>` plus `data: <json>`, and comments (`: ...`) are keep-alives that must be ignored. |
| Streaming event names | `response.created`, `response.output_text.delta` (incremental `delta` text), `response.output_text.done`, `response.output_item.*`, `response.content_part.*`, `response.completed`, `response.failed`, `response.incomplete`, `error`, and reasoning events `response.reasoning_summary_text.delta` / `response.reasoning_text.delta`. |
| Completion | `response.completed` carries the full `response` object: the serving `model`, `reasoning.effort`, and `usage`. |
| Usage | `response.usage` = `input_tokens`, `output_tokens`, `total_tokens`, `input_tokens_details.cached_tokens`, `output_tokens_details.reasoning_tokens`. |
| Reasoning control | `reasoning.effort` ∈ `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`, **model-dependent**; `reasoning.mode` = `standard`/`pro` and `reasoning.summary` are separate, model-gated controls. |
| Cancellation | A foreground stream is cancelled by closing the connection; `POST {base}/responses/{response_id}/cancel` only cancels `background: true` responses. |
| Storage (R-0018) | `store` **defaults to true when omitted**, and a stored response "will be stored for at least 30 days". The adapter always sends `"store": false`. |

### Reasoning union update (R-0072)

The current reasoning guide documents the effort union as including **`minimal`**,
which the M00 OpenAI row omitted. The M13 registry entry for OpenAI now carries
`NONE, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX` (`ProviderCapabilityRegistry.openAi`),
and `ProviderCapabilityRegistryTest` asserts it. `reasoning.mode` and
`reasoning.summary` are **not** surfaced yet — they have no `ReasoningLevel`
representation and are tracked in R-0093/R-0095.

## Request mapping

`OpenAiResponses.encodeRequest` builds the Responses payload:

```json
{
  "model": "<selected model>",
  "input": [ { "type": "message", "role": "user", "content": "..." } ],
  "store": false,
  "stream": true,
  "reasoning": { "effort": "medium" }
}
```

- **`store: false` is unconditional** (R-0018). The response body is never
  stored for later retrieval.
- **Roles.** `USER`/`ASSISTANT` map to `user`/`assistant`; a `SYSTEM` message
  maps to `developer`, the current instruction-priority role (the API also
  accepts `system`).
- **Reasoning.** `ReasoningLevel.NONE` **omits** the `reasoning` object: some
  models reject `"none"` with HTTP 400, and "do not reason" is expressed by the
  model default. Every other level maps to its documented effort spelling.
  `LlmRequestValidator` refuses a level outside the adapter's declared
  capability before any request is built.
- **Provider/model identity** stays explicit; the adapter never routes to a
  different model. `Completed.model` echoes the model the provider reported, so
  a mismatch is visible (R-0017/R-0023).

## Response mapping

| OpenAI frame | Contract |
| --- | --- |
| `response.output_text.delta` | `Delta(delta)` |
| `response.refusal.delta` | `Delta(delta)` — a refusal is assistant output |
| `response.reasoning_summary_text.delta`, `response.reasoning_text.delta` | **excluded** from `Delta` (R-0066) |
| `response.completed` | `Completed(usage, model, reasoning.effort)` |
| `response.failed`, `error` | `Failed(typed error)` |
| `response.incomplete` | `Failed(LLM_MALFORMED_RESPONSE)` — a terminated-but-partial response is not a completion |
| `response.created` / `in_progress` / item / content-part / `output_text.done` | ignored |
| a frame whose `data:` is not valid JSON | `Failed(LLM_MALFORMED_RESPONSE)` |
| the stream ends without any terminal event | `Failed(LLM_MALFORMED_RESPONSE)` (R-0067) |

`response.output_text.done` is ignored because the text already streamed as
deltas; emitting it again would duplicate the answer.

### Reasoning channel (R-0066)

The reasoning channel is a distinct `OpenAiStreamFrame.Reasoning`, never
concatenated into assistant text. The contract has no typed reasoning side
channel yet, so the content is dropped and only the reported `reasoning.effort`
(from `response.completed`) is surfaced as `Completed.reasoning`. A test
(`theReasoningChannelIsExcludedFromAssistantText`) proves a reasoning frame never
appears in `LlmStreamResult.text`.

### Typed error mapping

`RemoteStatusMapper` (shared) maps statuses: 401/403 → `LLM_AUTHENTICATION_FAILED`,
408/504 → `LLM_TIMEOUT`, 429 → `LLM_RATE_LIMITED`, other 4xx →
`LLM_INVALID_REQUEST`, 5xx → `LLM_UNAVAILABLE`. `OpenAiResponses` maps a streamed
`error`/`response.failed` code: `rate_limit_exceeded` → `LLM_RATE_LIMITED`,
`server_error` → `LLM_UNAVAILABLE`, `invalid_prompt`/`invalid_request_error` →
`LLM_INVALID_REQUEST`, `invalid_api_key`/`authentication_error` →
`LLM_AUTHENTICATION_FAILED`. A dropped connection maps to `LLM_NETWORK_FAILED`
and a socket timeout to `LLM_TIMEOUT`, both in the transport. No provider message
becomes `VoiceAgentError.detail`.

## Credential integration (M13)

`OpenAiLanguageModel` loads the credential from the M13 `CredentialStore` at
request time and places it only in the `Authorization` header:

- no credential → `LLM_NOT_CONFIGURED`, and no request is sent;
- the secret never reaches a result, trace, `VoiceAgentError.detail`, or the
  developer log (`LlmStreamLoggingTest`, `RemoteTransportTest`,
  `OpenAiLanguageModelTest.theRequestAndStreamNeverPutTheCredentialOrContentInLogs`);
- `RemoteHttpRequest.toString` redacts header values, so even an accidental
  interpolation cannot leak the key.

`OpenAiCredentialValidator` implements the documented minimal check
(`GET /v1/models`, resolving the OpenAI part of R-0073): 2xx is `Valid`, 401/403
is `authenticationRejected()`, another status keeps its typed code, and a
transport failure is a typed `Failed`. It reads only the status and never echoes
the key or the response body.

## Files

```
app/src/main/kotlin/com/voicechat/agent/providers/openai/
  OpenAiResponses.kt           request encoding, frame parsing, model-list parsing
  OpenAiLanguageModel.kt       LanguageModel adapter (credential + transport)
  OpenAiCredentialValidator.kt minimal GET /models check
app/src/test/kotlin/com/voicechat/agent/providers/openai/
  OpenAiResponsesTest.kt       protocol mapping (no HTTP)
  OpenAiLanguageModelTest.kt   fixture-driven acceptance (all milestone cases)
  OpenAiCredentialValidatorTest.kt
  OpenAiSmokeTest.kt           opt-in real-provider smoke test
app/src/test/resources/llm/openai/*.sse   recorded frame fixtures
```

## Tests

Every acceptance case is a recorded fixture or a scripted engine (no network):

| Case | Test |
| --- | --- |
| normal stream | `OpenAiLanguageModelTest.aNormalStreamCompletesWithTextModelUsageAndReasoning` (`normal_stream.sse`) |
| empty response | `anEmptyResponseCompletesWithNoDeltas` (`empty_response.sse`) |
| malformed frames | `aMalformedFrameFailsWithMalformedResponseAndKeepsThePrefix` (`malformed_frame.sse`) |
| rate limit | `aRateLimitStatusFailsWithRateLimited`, `providerErrorEventsMapToTheirTypedReasons` (`rate_limit_event.sse`) |
| auth failure | `anAuthenticationStatusFailsWithAuthentication` |
| server error | `aServerErrorStatusFailsWithUnavailable` (`server_error_event.sse`) |
| network loss | `aNetworkLossKeepsThePartialTextAndReportsNetwork` |
| cancellation mid-stream | `cancellingMidStreamCancelsTheEngineAndEmitsNoTerminalEvent` |
| terminal-less stream | `aTerminalLessStreamIsNotACompletion` (`terminal_less.sse`) |
| reasoning excluded | `theReasoningChannelIsExcludedFromAssistantText` (`reasoning_channel.sse`) |
| `store: false` sent | `theRequestGoesToTheDocumentedEndpointWithBearerAuthAndStoreFalse` |
| credential not logged | `theRequestAndStreamNeverPutTheCredentialOrContentInLogs` |
| capability reporting | `theAdapterDeclaresTheVerifiedProviderCapabilities` |

The opt-in smoke test is documented and marked **not run** in
[Tests.md](../Tests.md) § M14.

## Limitations

- Only the Responses API is wired. Chat Completions is documented but not used;
  `reasoning.mode`/`summary` are not surfaced (R-0093).
- Per-model reasoning levels are not discovered from `/models` (it lists ids
  only), so a model-specific rejection surfaces as `LLM_INVALID_REQUEST`
  (R-0092).
- No live smoke run was performed for this milestone (R-0091); fixtures prove
  the mapping, not live latency or a real credential.
- The app still runs `NotConfiguredLanguageModel` in the UI; wiring the adapter
  into the turn path is M23 (R-0012, R-0097).

## Sources (accessed 2026-09-29)

- Responses create reference — https://platform.openai.com/docs/api-reference/responses/create
- Responses streaming reference (event names, cancel) — https://platform.openai.com/docs/api-reference/responses-streaming
- Streaming responses guide — https://developers.openai.com/api/docs/guides/streaming-responses
- Reasoning guide (effort values, mode, usage, `store: false` stateless mode) — https://developers.openai.com/api/docs/guides/reasoning
- Models list — https://platform.openai.com/docs/api-reference/models/list
