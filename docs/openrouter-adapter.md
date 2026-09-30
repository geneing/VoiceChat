# OpenRouter Adapter (M15)

This is the second complete remote provider behind the M12
[`LanguageModel` contract](./llm-contract.md). It uses the shared
[remote transport](./llm-transport.md); this document records the verified
OpenRouter facts, the adapter's mapping, its routing constraint, and its privacy
behavior.

All provider facts below were **verified against the official OpenRouter
documentation and the live public `/api/v1/models` endpoint on 2026-09-29** (the
M00 matrix was read 2026-09-28; this is the re-verification R-0072 requires
before an adapter ships). Sources are listed at the end.

## Verified facts (accessed 2026-09-29)

| Concern | Verified behavior |
| --- | --- |
| Endpoint | `POST {base}/chat/completions` — base `https://openrouter.ai/api/v1`, so `https://openrouter.ai/api/v1/chat/completions`. OpenRouter normalizes its OpenAI-compatible Chat Completions shape across provider models. |
| Authentication | `Authorization: Bearer <OPENROUTER_API_KEY>`, user-supplied. The M13 registry pins `openrouter.ai`, so a substituted host is refused before a request is built. OpenRouter also documents an OAuth authorization-code + PKCE browser flow (see limitations). |
| Model discovery | `GET {base}/models` returns `{ "data": [ { "id", "name", "context_length", "reasoning": { … }, … } ], "total_count", "links" }`. Unlike OpenAI, a model may carry per-model `reasoning.supported_efforts` / `default_effort` / `default_enabled` / `mandatory` / `supports_max_tokens`. |
| Streaming | `"stream": true` returns `text/event-stream`. Frames are `data: {json}` lines; comments such as `: OPENROUTER PROCESSING` are keep-alives and are ignored; the stream ends with `data: [DONE]`. |
| Response shape | OpenAI-style `choices[].delta.content`; each chunk carries the serving `model` (and often `provider`). Usage is returned **exactly once**, in a final content-free chunk just before `[DONE]`; that chunk repeats the stream's `finish_reason` and is an accounting frame, not a second terminal event. |
| Usage | `prompt_tokens`, `completion_tokens`, `total_tokens`, optional `prompt_tokens_details.cached_tokens`, optional `completion_tokens_details.reasoning_tokens`, and an optional `cost` in credits. |
| Reasoning control | The unified `reasoning` map: `effort` ∈ `max`/`xhigh`/`high`/`medium`/`low`/`minimal`/`none`, plus `max_tokens`, `exclude`, and `enabled`. Per-model `reasoning.supported_efforts` is returned by `/models`; a null `supported_efforts` means all gateway efforts are accepted; `mandatory: true` rejects `effort: "none"`. The legacy `reasoning_effort` enum and the deprecated `include_reasoning` alias are not used. |
| Routing / fallback | `provider.allow_fallbacks` **defaults to `true`** ("whether to allow backup providers when the primary is unavailable"); `models[]` plus `route: "fallback"` route across *different models*; `:nitro`/`:floor` are routing variants. The adapter disables fallbacks and never sends a model list (see [Routing constraint](#routing-constraint-r-0017)). |
| Errors | Before the response is committed: an HTTP status with `{ "error": { "code", "message", "metadata" } }`. After the `200 OK` is committed: a mid-stream SSE chunk whose top level carries `error.metadata.error_type` (the stable, typed vocabulary) and `choices[].finish_reason: "error"`. Documented statuses include 400, 401, 402 (insufficient credits), 403, 408, 429, 502, 503. |
| Cancellation | Aborting the connection cancels a streaming request; OpenRouter notes this immediately stops processing/billing only for some upstream providers. |

### Typed provider error vocabulary

OpenRouter normalizes provider failures into a stable `error.metadata.error_type`
string. The adapter maps the categories relevant to a chat completion: 
`rate_limit_exceeded` → `LLM_RATE_LIMITED`; `provider_overloaded`,
`provider_unavailable`, `server`, `unmapped` → `LLM_UNAVAILABLE`; `timeout` →
`LLM_TIMEOUT`; `authentication`/`permission_denied` →
`LLM_AUTHENTICATION_FAILED`; `payment_required` and the request-validation/
content-policy types (`invalid_request`, `invalid_prompt`, `not_found`,
`context_length_exceeded`, `content_policy_violation`, `refusal`, …) →
`LLM_INVALID_REQUEST`. An unrecognized type falls back to the numeric `code`
with the same status mapping the shared transport uses. The provider `message`
is never copied into `VoiceAgentError.detail`.

## Request mapping

`OpenRouterChatCompletions.encodeRequest` builds the payload:

```json
{
  "model": "<selected model>",
  "messages": [ { "role": "user", "content": "..." } ],
  "stream": true,
  "provider": { "allow_fallbacks": false }
}
```

- **Roles.** `SYSTEM`/`USER`/`ASSISTANT` map to `system`/`user`/`assistant` —
  Chat Completions uses `system`, unlike the Responses API's `developer`.
- **Reasoning.** `ReasoningLevel.NONE` **omits** the `reasoning` object: some
  models reject `"none"` (OpenRouter marks them `reasoning.mandatory`) and "do
  not reason" is expressed by the model default. Every other level maps to its
  documented `reasoning.effort` spelling. `LlmRequestValidator` refuses a level
  outside the adapter's declared capability before any request is built.
- **Provider/model identity** stays explicit; the adapter never routes to a
  different model.

## Response mapping

| OpenRouter frame | Contract |
| --- | --- |
| `choices[0].delta.content` (non-empty) | `Delta(content)` |
| `choices[0].delta.reasoning` / `delta.reasoning_details` | **excluded** from `Delta` (R-0066) |
| content-free chunk carrying `usage` | recorded as the accounting frame (usage + reported `model`) |
| `data: [DONE]` | `Completed(usage, model, reasoning=null)` |
| top-level `error` object | `Failed(typed error)` |
| a frame whose `data:` is not valid JSON | `Failed(LLM_MALFORMED_RESPONSE)` |
| the stream ends without `[DONE]` | `Failed(LLM_MALFORMED_RESPONSE)` (R-0067) |

The `[DONE]` sentinel is the only terminal event. A usage chunk that arrives
without a following `[DONE]` is **not** treated as a completion, because
OpenRouter documents it as an accounting frame rather than a terminal event.

### Reasoning channel (R-0066)

The reasoning channel arrives as `delta.reasoning` or the structured
`delta.reasoning_details` array. It is a distinct `OpenRouterStreamFrame.Reasoning`
and is never concatenated into assistant text. The contract has no typed reasoning
side channel yet, so the content is dropped; `Completed.reasoning` is `null`
because OpenRouter does not echo the effort that served a Chat Completions
request (only `completion_tokens_details.reasoning_tokens`). A test proves a
reasoning frame never appears in `LlmStreamResult.text`.

## Routing constraint (R-0017)

OpenRouter can silently fall back to another provider or GPU on a 5xx or a rate
limit, and it can route across different *models* when given `models[]` /
`route: "fallback"`. `AGENTS.md` forbids silently switching a provider or model,
so the adapter constrains routing as far as the documented API allows:

- `provider.allow_fallbacks: false` disables backup providers for the request;
- `models[]` and `route` are never emitted, so the selected model is the only
  candidate;
- no routing variant (`:nitro`, `:floor`) is appended to the model slug.

Even so, OpenRouter owns the routing decision, so the adapter records the model
OpenRouter reports in each response on `LlmStreamEvent.Completed.model`. A
response that reports a model differing from the selection is surfaced, not
rewritten to the selection: the M21 orchestrator records it as
`DiagnosticAttribute.REPORTED_MODEL_ID` (R-0023). A regression test
(`aReportedModelThatDiffersFromTheSelectionIsDetectableNotSilentlyAccepted`) and a
request-shape test (`theRequestGoesToTheDocumentedEndpointWithBearerAuthAndNoFallbackRouting`)
prove both halves.

## Credential integration (M13)

`OpenRouterLanguageModel` loads the credential from the M13 `CredentialStore` at
request time and places it only in the `Authorization` header:

- no credential → `LLM_NOT_CONFIGURED`, and no request is sent;
- the secret never reaches a result, trace, `VoiceAgentError.detail`, or the
  developer log; `RemoteHttpRequest.toString` redacts header values.

`OpenRouterCredentialValidator` implements the documented minimal check
(`GET /api/v1/models`, resolving the OpenRouter part of R-0073): 2xx is `Valid`,
401/403 is `authenticationRejected()`, another status keeps its typed code, and a
transport failure is a typed `Failed`. It reads only the status and never echoes
the key or the response body.

## Model catalog (R-0102)

`OpenRouterModels.parse` reads the `/models` response and `OpenRouterModelCatalog`
exposes it as an M13 `ModelCapabilityCatalog`: per-model reasoning levels from
`reasoning.supported_efforts` (all gateway efforts when the field is null, none
for a model with no `reasoning` object, and `NONE` removed when the model is
`mandatory`). This is the live per-model capability source R-0065/R-0102
describe; wiring it into the settings runtime provider remains M22/M23.

## Files

```
app/src/main/kotlin/com/voicechat/agent/providers/openrouter/
  OpenRouterChatCompletions.kt   request encoding, frame parsing, model-list parsing
  OpenRouterModels.kt            /models parsing + ModelCapabilityCatalog
  OpenRouterLanguageModel.kt     LanguageModel adapter (credential + transport)
  OpenRouterCredentialValidator.kt minimal GET /models check
app/src/test/kotlin/com/voicechat/agent/providers/openrouter/
  OpenRouterChatCompletionsTest.kt  protocol mapping (no HTTP)
  OpenRouterLanguageModelTest.kt    fixture-driven acceptance (all milestone cases)
  OpenRouterCredentialValidatorTest.kt
  OpenRouterSmokeTest.kt            opt-in real-provider smoke test
app/src/test/resources/llm/openrouter/*.sse   recorded frame fixtures
app/src/test/resources/llm/openrouter/models_list.json  a redacted /models sample
```

## Tests

Every acceptance case is a recorded fixture or a scripted engine (no network):

| Case | Test |
| --- | --- |
| normal stream | `OpenRouterLanguageModelTest.aNormalStreamCompletesWithTextModelUsageAndCost` (`normal_stream.sse`) |
| empty response | `anEmptyResponseCompletesWithNoDeltas` (`empty_response.sse`) |
| malformed frames | `aMalformedFrameFailsWithMalformedResponseAndKeepsThePrefix` (`malformed_frame.sse`) |
| rate limit | `httpStatusesMapToTheirTypedReasons`, `midStreamErrorEventsMapToTheirTypedReasons` (`rate_limit_event.sse`) |
| auth failure | `httpStatusesMapToTheirTypedReasons` (`auth_error_event.sse`) |
| payment required | `httpStatusesMapToTheirTypedReasons` (`payment_required_event.sse`) |
| server error | `httpStatusesMapToTheirTypedReasons` (`server_error_event.sse`) |
| mid-stream provider error | `aMidStreamProviderErrorKeepsThePartialTextAndReportsUnavailable` (`mid_stream_error.sse`) |
| network loss | `aNetworkLossKeepsThePartialTextAndReportsNetwork` |
| cancellation mid-stream | `cancellingMidStreamCancelsTheEngineAndEmitsNoTerminalEvent` |
| terminal-less stream | `aTerminalLessStreamIsNotACompletion` (`terminal_less.sse`); `theDoneSentinelIsTerminal` |
| reasoning excluded | `theReasoningChannelIsExcludedFromAssistantText` (`reasoning_channel.sse`) |
| model identity | `theModelOpenRouterReportsIsSurfacedInsteadOfTheSelection` |
| no silent routing | `aReportedModelThatDiffersFromTheSelectionIsDetectableNotSilentlyAccepted`, `theRequestGoesToTheDocumentedEndpointWithBearerAuthAndNoFallbackRouting` |
| unsupported parameters | `aReasoningLevelTheAdapterDoesNotSupportIsRefusedBeforeAnyRequest`, `reasoningNoneOmitsTheReasoningObject` |
| model catalog | `theModelCatalogCarriesPerModelReasoningEfforts`, `theCatalogRefinesReasoningPerModelAndClaimsNothingForAnUnknownModel` |
| credential not logged | `theRequestAndStreamNeverPutTheCredentialOrContentInLogs` |
| capability reporting | `theAdapterDeclaresTheVerifiedProviderCapabilities` |

The opt-in smoke test is documented and marked **not run** in
[Tests.md](../Tests.md) § M15.

## Limitations

- Only the Chat Completions API is wired. The Responses and Anthropic Messages
  skins OpenRouter also exposes are not used.
- `Completed.reasoning` is `null`: OpenRouter does not echo the serving effort
  for Chat Completions, so only `reasoning_tokens` is available (R-0114).
- `provider.data_collection` defaults to `"allow"` (a routed provider may store
  or train on data) and `zdr` is not enforced — a privacy decision deferred to
  M22/M26 (R-0110).
- No live smoke run was performed for this milestone (R-0111); fixtures prove the
  mapping, not live latency or a real credential. The credential check is
  fixture-tested only (R-0112).
- OpenRouter's PKCE browser flow is not implemented in this build; the adapter
  accepts an API key from the M13 store and the M22 settings seam owns the UI
  (R-0101/R-0113).
- The model catalog is not yet wired into the settings runtime provider (R-0102).

## Sources (accessed 2026-09-29)

- API overview, request/response schema, streaming notes, provider/model routing
  fallback — https://openrouter.ai/docs/api-reference/overview
- Streaming (SSE comments, `[DONE]`, final usage chunk, mid-stream errors,
  cancellation) — https://openrouter.ai/docs/api-reference/streaming
- Parameters (reasoning, `reasoning_effort`, `include_reasoning`) —
  https://openrouter.ai/docs/api-reference/parameters
- Errors and debugging (statuses, `error.metadata.error_type`, mid-stream error
  shape, skin formats) — https://openrouter.ai/docs/api-reference/errors
- Reasoning tokens (`reasoning` map, per-model `supported_efforts`) —
  https://openrouter.ai/docs/use-cases/reasoning-tokens
- Provider routing (`allow_fallbacks`, `data_collection`, `zdr`, order) —
  https://openrouter.ai/docs/features/provider-routing
- Models list reference (response fields, `reasoning`) —
  https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties
- Live `/models` response (464 models; `reasoning` metadata shape) —
  https://openrouter.ai/api/v1/models
- OAuth PKCE — https://openrouter.ai/docs/use-cases/oauth-pkce
