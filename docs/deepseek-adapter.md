# DeepSeek Adapter (M16)

This is the DeepSeek provider behind the M12
[`LanguageModel` contract](./llm-contract.md). It uses the shared M14
[remote transport](./llm-transport.md); this document records the verified
DeepSeek facts, the adapter's mapping, its privacy behavior, and its explicit
limitations.

All provider facts below were **verified against the official DeepSeek API
documentation on 2026-09-29** (the M00 matrix was read 2026-09-28; this is the
re-verification R-0072 requires before an adapter ships). Sources are listed at
the end.

The mapping is taken from DeepSeek's own API reference and thinking-mode guide.
It is **not** copied from the OpenAI adapter: DeepSeek's Chat Completions stream
is data-only SSE terminated by `data: [DONE]`, its reasoning content rides a
`delta.reasoning_content` field, and its request uses `thinking` /
`reasoning_effort` (not OpenAI's Responses `reasoning.effort`).

## Verified facts (accessed 2026-09-29)

| Concern | Verified behavior |
| --- | --- |
| Base URL (OpenAI format) | `https://api.deepseek.com` — `POST /chat/completions`, `POST /responses`, `GET /models`. |
| Base URL (Anthropic format) | `https://api.deepseek.com/anthropic` — a separate protocol, **not implemented** here. |
| Authentication | `Authorization: Bearer <API key>`, user-supplied. The M13 registry pins `api.deepseek.com`, so a substituted host is refused before a request is built. |
| Models | `deepseek-flash` (DeepSeek-V4.1-Flash) and `deepseek-v4-pro`. The legacy names `deepseek-v4-flash` and `deepseek-v4-flash-vision-exp` are accepted but retire to the Flash model. Thinking mode is enabled by default on both. |
| Model discovery | `GET /models` returns `{ "object": "list", "data": [ { "id", "object", "owned_by", "name", "context_window", "max_output_tokens", "input_modalities", "output_modalities", "effort": { "supported_levels", "default_level" }, "api_capabilities": { "anthropic_messages": { "system_prompt_update" } } } ] }`. |
| Streaming endpoint | `POST /chat/completions` with `"stream": true` returns data-only SSE, `data: <json>` frames, terminated by `data: [DONE]`. Keep-alive comments (`: keep-alive`) may be sent and must be ignored. |
| Streaming chunk | `{ "id", "object": "chat.completion.chunk", "created", "model", "system_fingerprint", "choices": [ { "index", "delta": { "content", "reasoning_content", "role", "tool_calls" }, "logprobs", "finish_reason" } ], "usage"? }`. |
| Reasoning channel | In thinking mode the chain of thought is returned on `choices[].delta.reasoning_content`, at the same level as `content`. It is **not** assistant text. |
| Completion | The last chunk before `[DONE]` carries a non-null `choices[].finish_reason` (`stop`/`length`/`content_filter`/`tool_calls`/`insufficient_system_resource`/`aborted`) and the request's `usage` (there is no separate usage-only chunk). |
| Usage | `usage.prompt_tokens`, `completion_tokens`, `total_tokens`, `prompt_cache_hit_tokens`, `prompt_cache_miss_tokens`, `prompt_tokens_details.cached_tokens`, `completion_tokens_details.reasoning_tokens`. |
| Thinking toggle | `thinking: { "type": "enabled" \| "disabled" }`; **enabled by default**. `reasoning_effort: "none"` also disables thinking. |
| Thinking effort | `reasoning_effort` ∈ `none`, `low`, `high`, `max`; default `high`. `minimal` is accepted and mapped to `low`; `medium` and `xhigh` are accepted and mapped to `high`. |
| Thinking-mode constraints | `temperature`, `presence_penalty`, and `frequency_penalty` have no effect in thinking mode (accepted, ignored). |
| Errors | HTTP `400` invalid format, `401` authentication fails, `402` insufficient balance, `422` invalid parameters, `429` rate limit reached, `500` server error, `503` server overloaded. A `429` is also returned when the account concurrency limit is exceeded. |
| Statelessness | Chat Completions documents no storage option and no `store` field; the adapter sends none. |

## Request mapping

`DeepSeekChat.encodeRequest` builds the Chat Completions payload:

```json
{
  "model": "<selected model>",
  "messages": [ { "role": "user", "content": "..." } ],
  "stream": true,
  "thinking": { "type": "enabled" },
  "reasoning_effort": "medium"
}
```

- **Roles.** `USER`/`ASSISTANT` map to `user`/`assistant`; a `SYSTEM` message maps
  to `system`, which is the role DeepSeek's Chat Completions API documents. (The
  adapter does **not** reuse OpenAI's Responses `developer` role.)
- **Reasoning.** `reasoning == null` omits both thinking fields, so DeepSeek's
  default applies (thinking enabled, effort `high`). `ReasoningLevel.NONE` sends
  `thinking.type = "disabled"` and omits `reasoning_effort` (the documented way to
  switch to non-thinking mode). A real level sends `thinking.type = "enabled"`
  plus `reasoning_effort` set to that level's documented spelling.
- **Provider/model identity** stays explicit; the adapter never routes to a
  different model. `Completed.model` echoes the model the provider reported
  (`"model"` is present on every chunk), so a mismatch is visible
  (R-0017/R-0023).
- **No `store`-style field** is sent: DeepSeek's Chat Completions API documents
  none and is stateless.

## Response mapping

| DeepSeek frame | Contract |
| --- | --- |
| `choices[].delta.content` (non-empty) | `Delta(content)` |
| `choices[].delta.reasoning_content` | **excluded** from `Delta` (R-0066) |
| chunk with a non-null `choices[].finish_reason` = `stop` | `Completed(usage, model)` |
| chunk with `finish_reason` = `length` | `Failed(LLM_MALFORMED_RESPONSE)` — a truncated response is not whole |
| chunk with `finish_reason` = `content_filter` | `Failed(LLM_REQUEST_FAILED)` |
| chunk with `finish_reason` = `insufficient_system_resource` | `Failed(LLM_UNAVAILABLE)` |
| chunk with `finish_reason` = `aborted` | `Failed(LLM_REQUEST_FAILED)` |
| `data: [DONE]` | the stream sentinel; a completion if it arrives with no finish-reason chunk |
| a JSON `error` body in a 200 response | `Failed(typed error)` from the stable `code`/`type` |
| a frame whose `data:` is not valid JSON | `Failed(LLM_MALFORMED_RESPONSE)` |
| the stream ends with no finish reason and no `[DONE]` | `Failed(LLM_MALFORMED_RESPONSE)` (R-0067) |
| role-only / empty-delta chunks and keep-alive comments | ignored |

`usage` and the serving `model` ride the finish-reason chunk, so `Completed`
carries them. DeepSeek does not report the served thinking effort, so
`Completed.reasoning` is always `null` — the adapter never fabricates it.

### Reasoning channel (R-0066)

The reasoning channel is a distinct `DeepSeekStreamFrame.Reasoning`, produced
from `delta.reasoning_content`, and is **never** concatenated into assistant
text. The contract has no typed reasoning side channel yet, so the content is
dropped; only the reasoning *token count* (if reported in
`usage.completion_tokens_details.reasoning_tokens`) is surfaced as usage detail.
Test `theReasoningChannelIsExcludedFromAssistantText` proves the reasoning text
never appears in `LlmStreamResult.text`.

### Typed error mapping

`RemoteStatusMapper` (shared) maps statuses: 401/403 → `LLM_AUTHENTICATION_FAILED`,
408/504 → `LLM_TIMEOUT`, 429 → `LLM_RATE_LIMITED`, other 4xx →
`LLM_INVALID_REQUEST`, 5xx → `LLM_UNAVAILABLE`. A streamed `error` object is
mapped from its stable `code`/`type` (`insufficient_balance` → `LLM_UNAVAILABLE`,
`rate_limit_exceeded` → `LLM_RATE_LIMITED`, `authentication_error`/`invalid_api_key`
→ `LLM_AUTHENTICATION_FAILED`, `invalid_request_error`/`invalid_parameters` →
`LLM_INVALID_REQUEST`, `server_error` → `LLM_UNAVAILABLE`). A dropped connection
maps to `LLM_NETWORK_FAILED` and a socket timeout to `LLM_TIMEOUT`, both in the
transport. No provider `message` becomes `VoiceAgentError.detail`.

**Known gap:** DeepSeek documents HTTP `402` for insufficient balance, but the
shared status mapper has no billing/account code and classifies every other 4xx
as `LLM_INVALID_REQUEST`, so `402` currently surfaces as `LLM_INVALID_REQUEST`
(R-0124).

## Credential integration (M13)

`DeepSeekLanguageModel` loads the credential from the M13 `CredentialStore` at
request time and places it only in the `Authorization` header:

- no credential → `LLM_NOT_CONFIGURED`, and no request is sent;
- the secret never reaches a result, trace, `VoiceAgentError.detail`, or the
  developer log (`DeepSeekLanguageModelTest.theRequestAndStreamNeverPutTheCredentialOrContentInLogs`);
- `RemoteHttpRequest.toString` redacts header values, so even an accidental
  interpolation cannot leak the key.

`DeepSeekCredentialValidator` implements the documented minimal check
(`GET /models`): 2xx is `Valid`, 401/403 is `authenticationRejected()`, another
status keeps its typed code, and a transport failure is a typed `Failed`. It
reads only the status and never echoes the key or the response body.

## Files

```
app/src/main/kotlin/com/voicechat/agent/providers/deepseek/
  DeepSeekChat.kt              request encoding, frame parsing, model-list parsing
  DeepSeekLanguageModel.kt     LanguageModel adapter (credential + transport)
  DeepSeekCredentialValidator.kt minimal GET /models check
app/src/test/kotlin/com/voicechat/agent/providers/deepseek/
  DeepSeekChatTest.kt          protocol mapping (no HTTP)
  DeepSeekLanguageModelTest.kt fixture-driven acceptance (all milestone cases)
  DeepSeekCredentialValidatorTest.kt
  DeepSeekSmokeTest.kt         opt-in real-provider smoke test
app/src/test/resources/llm/deepseek/*.sse   recorded frame fixtures
```

## Tests

Every acceptance case is a recorded fixture or a scripted engine (no network):

| Case | Test |
| --- | --- |
| normal stream | `DeepSeekLanguageModelTest.aNormalStreamCompletesWithTextModelAndUsage` (`normal_stream.sse`, includes a keep-alive comment) |
| empty response | `anEmptyResponseCompletesWithNoDeltas` (`empty_response.sse`) |
| malformed frames | `aMalformedFrameFailsWithMalformedResponseAndKeepsThePrefix` (`malformed_frame.sse`) |
| truncated response | `aTruncatedFinishReasonIsNotACompletionButKeepsThePartialText` (`truncated.sse`) |
| rate limit | `aRateLimitStatusFailsWithRateLimited` |
| timeout | `aTransportTimeoutFailsWithTimeoutAndKeepsThePartialText`, `aGatewayTimeoutStatusFailsWithTimeout` |
| auth failure | `anAuthenticationStatusFailsWithAuthentication` |
| insufficient balance | `a402InsufficientBalanceKeepsTheSharedStatusMapping` (documents R-0124) |
| server error | `aServerErrorStatusFailsWithUnavailable` |
| network loss | `aNetworkLossKeepsThePartialTextAndReportsNetwork` |
| cancellation mid-stream | `cancellingMidStreamCancelsTheEngineAndEmitsNoTerminalEvent` |
| terminal-less stream | `aTerminalLessStreamIsNotACompletion` (`terminal_less.sse`) |
| reasoning excluded | `theReasoningChannelIsExcludedFromAssistantText` (`reasoning_channel.sse`) |
| model selection sent | `theRequestGoesToTheDocumentedEndpointWithBearerAuthAndStreaming` |
| thinking/effort sent | `aRealReasoningRequestSendsTheDocumentedThinkingAndEffort`, `noneReasoningDisablesThinking` |
| unsupported reasoning | `aReasoningLevelTheAdapterDoesNotSupportIsRefusedBeforeAnyRequest` |
| missing credential | `aMissingCredentialFailsNotConfiguredWithoutSending` |
| capability reporting | `theAdapterDeclaresTheVerifiedProviderCapabilities` |
| credential not logged | `theRequestAndStreamNeverPutTheCredentialOrContentInLogs` |
| protocol mapping | `DeepSeekChatTest` (request, roles, thinking/effort, frame parsing, `/models`) |
| credential check | `DeepSeekCredentialValidatorTest` |

The opt-in smoke test is documented and marked **not run** in
[Tests.md](../Tests.md) § M16.

## Capability registry update (R-0072)

Re-verification at M16 updated the DeepSeek entry in
`ProviderCapabilityRegistry`:

- `models.reasoningLevels` now carries `NONE, MINIMAL, LOW, MEDIUM, HIGH, XHIGH,
  MAX`, because the API accepts every level the app exposes (`minimal`/`medium`/
  `xhigh` are documented aliases);
- `models.usageReporting` is now `true` (the Chat Completions response and the
  last streamed chunk carry usage);
- `UnverifiedCapability.REASONING` was removed from `models.unverified`.

`ProviderCapabilityRegistryTest.deepSeekExposesItsReverifiedDocumentedCapabilities`
asserts this. Per-model `effort.supported_levels` refinement from `/models` is
still an M13/M22 concern (R-0123).

## Limitations

- Only DeepSeek's OpenAI-format **Chat Completions** protocol is wired. The
  `/responses` (Responses) and `/anthropic` protocols are documented but not
  used (R-0122).
- The live `/models` catalog is parsed for identities but not consumed by the
  app/settings (M22 concern, R-0102/R-0123); per-model effort levels are
  therefore not surfaced.
- HTTP `402` (insufficient balance) keeps the shared 4xx mapping
  (`LLM_INVALID_REQUEST`) (R-0124).
- Tools/function calling, JSON output, and image input are not surfaced.
- No live smoke run was performed for this milestone (R-0121); fixtures prove
  the mapping, not live latency or a real credential.
- The app still runs `NotConfiguredLanguageModel` in the UI; wiring the adapter
  into the turn path is M23 (R-0012, R-0097).

## Sources (accessed 2026-09-29)

- Your First API Call (base URLs, models, auth) — https://api-docs.deepseek.com/
- Chat Completions API (request/response, streaming, thinking, usage, finish reasons) — https://api-docs.deepseek.com/api/create-chat-completion
- Lists Models (`/models` metadata) — https://api-docs.deepseek.com/api/list-models
- Thinking Mode (toggle, effort aliases, `reasoning_content`) — https://api-docs.deepseek.com/guides/thinking_mode
- Using the Responses API (event names; documents the separate protocol) — https://api-docs.deepseek.com/guides/responses_api
- Error Codes (400/401/402/422/429/500/503) — https://api-docs.deepseek.com/quick_start/error_codes
- Rate Limit & Isolation (429, keep-alive comments) — https://api-docs.deepseek.com/quick_start/rate_limit
- Models & Pricing (model names/versions) — https://api-docs.deepseek.com/quick_start/pricing
