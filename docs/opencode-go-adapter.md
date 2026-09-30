# OpenCode Go Adapter (M17)

This is the third remote provider behind the M12 [`LanguageModel`
contract](./llm-contract.md), after OpenAI (M14). It uses the shared
[remote transport](./llm-transport.md) and is deliberately **not** treated as
OpenAI-compatible: OpenCode Go's own model table places each model on one of
three different protocols, and this adapter dispatches on the model.

All provider facts below were **verified against the official OpenCode Go page on
2026-09-29** (the M00 matrix was read 2026-09-28; this is the re-verification
R-0072 requires before an adapter ships). Sources are listed at the end.

## Verified facts (accessed 2026-09-29)

| Concern | Verified behavior |
| --- | --- |
| Base URL | `https://opencode.ai/zen/go/v1` (fixed provider; `expectedHost` `opencode.ai`, so a substituted host is refused before a request is built). |
| Endpoints | Three per-model completion endpoints: `…/chat/completions`, `…/responses`, `…/messages`. The Go model table names the endpoint **and** the AI SDK package per model. |
| Model discovery | `GET …/models` returns the OpenAI-shaped `{"object":"list","data":[{"id":…,"object":"model","owned_by":"opencode"}]}`. It lists **identities only** — no family, reasoning, or usage metadata. |
| Model discovery is public | The `…/models` endpoint answers **without** an `Authorization` header (read directly 2026-09-29), so it can list models but can never validate a credential. The adapter therefore offers **no** credential validator (`CredentialValidationSupport.NONE`). |
| Authentication | User-supplied API key, from the OpenCode Console after subscribing to Go/Go Plus. Sent as `Authorization: Bearer <key>` (the Console/Zen surface documents that bearer form; Go's page says to copy the Console API key). No password, OAuth, device, or QR flow is documented. |
| Client identity | The Go page asks a client to identify itself with its own user agent (`my-coding-agent/1.0`) and to send a stable `x-opencode-session` id per conversation. Both are sent; neither carries a credential. |
| Protocol families | `@ai-sdk/openai-compatible` → `/chat/completions` (GLM, Kimi, LongCat, DeepSeek, MiMo, Hy, Space Bunny); `@ai-sdk/openai` → `/responses` (Grok, GPT Luna, Muse Spark); `@ai-sdk/anthropic` → `/messages` (MiniMax, Qwen). |
| Reasoning control | **Not documented.** The Go page describes no reasoning/thinking parameter, so the adapter declares no reasoning level and refuses any requested level except `none`. |
| Usage reporting | **Not promised.** The adapter parses usage only when a family's own protocol actually reports it; the provider capability claims no usage reporting. |
| Privacy (per-model) | The Go page lists per-model retention/training. Grok/GPT: 30-day retention; most others: 0 days; Muse Spark Contributor models **use prompts/completions to train Meta models**; DeepSeek uses a monthly ZDR agreement. (Not yet surfaced in the app; R-0139.) |
| Terms | Go is "designed for OpenCode and other coding agents"; traffic is monitored for abuse. A non-coding voice client is not explicitly addressed (R-0020 stays open). |

### Families and the model→endpoint map

The Go page states the model list "may change as we test and add new ones", so
the adapter ships a **static, dated** map (`OpenCodeGoModels.FAMILIES`) covering
only the ids the page itself places. A model present in `GET /models` but not
placed by the page (for example `omen-alpha`, `hy3-preview`, `glm-5.1`,
`grok-4.5`, `qwen3.7-max`) has **no verified family**, so the adapter refuses it
with a typed `LLM_INVALID_REQUEST` rather than guessing a protocol (R-0137).

## Request mapping

`OpenCodeGoLanguageModel` classifies `request.model.modelId` and calls the family
encoder; the payloads are distinct and are not interchangeable:

| Family | Endpoint | Body (excerpt) |
| --- | --- | --- |
| Chat Completions | `/chat/completions` | `{"model":…,"messages":[{"role":"system\|user\|assistant","content":…}],"stream":true}` |
| Responses | `/responses` | `{"model":…,"input":[{"type":"message","role":"developer\|user\|assistant","content":…}],"store":false,"stream":true}` |
| Messages | `/messages` | `{"model":…,"max_tokens":4096,"system":…,"messages":[{"role":"user\|assistant","content":…}],"stream":true}` |

- **Responses always sends `store: false`** (R-0018), exactly as the OpenAI
  adapter does, so the request cannot silently opt into stored responses.
- **A system prompt** maps to `system` (Chat Completions), `developer`
  (Responses), or the top-level `system` string (Messages).
- **Messages `max_tokens`** is required by the protocol but is not part of the
  M12 contract, so a bounded 4096 default is sent; the gap is tracked (R-0133).
- Provider/model identity stays explicit; nothing is silently rerouted.

## Response mapping

Each family normalizes to `OpenCodeGoStreamFrame`, and the adapter folds it into
the M12 events. A frame's serving model and usage may arrive on a different frame
than the terminal one, so `Reported` observations are merged into the final
`Completed`.

| Family | Delta | Terminal | Metadata |
| --- | --- | --- | --- |
| Chat Completions | `choices[0].delta.content` | `data: [DONE]` | `model` per chunk, `usage` if present |
| Responses | `response.output_text.delta` / `response.refusal.delta` | `response.completed` | `response.model`, `response.usage` |
| Messages | `content_block_delta` `text_delta` | `message_stop` | `message_start.message.model`, input/output tokens merged |

- A **reasoning channel** is excluded from assistant text: Responses
  `response.reasoning_*.delta` and Messages `thinking_delta` are ignored (R-0066).
- The Messages output-token count on `message_delta` is **merged** with the
  input-token count from `message_start`; a missing count is never written as `0`.
- A malformed frame is `LLM_MALFORMED_RESPONSE`; `response.incomplete` is a
  failure, not a completion; and a stream that ends without its family's terminal
  marker is `LLM_MALFORMED_RESPONSE` (R-0067).

### Typed error mapping

`RemoteStatusMapper` (shared) maps HTTP statuses: 401/403 →
`LLM_AUTHENTICATION_FAILED`, 408/504 → `LLM_TIMEOUT`, 429 → `LLM_RATE_LIMITED`,
other 4xx → `LLM_INVALID_REQUEST`, 5xx → `LLM_UNAVAILABLE`. In-stream errors are
mapped by `OpenCodeGoErrors` from the two documented vocabularies
(OpenAI-style `code` and Anthropic-style `type`): rate/quota → `LLM_RATE_LIMITED`,
invalid/not-found → `LLM_INVALID_REQUEST`, auth/permission →
`LLM_AUTHENTICATION_FAILED`, server/overload → `LLM_UNAVAILABLE`. No provider
`message` is copied into `VoiceAgentError.detail`.

## Credential integration (M13)

`OpenCodeGoLanguageModel` loads the credential from the M13 `CredentialStore` at
request time and places it only in the `Authorization` header:

- no credential → `LLM_NOT_CONFIGURED`, and no request is sent;
- the secret never reaches a result, trace, `VoiceAgentError.detail`, or the
  developer log; `RemoteHttpRequest.toString` redacts header values;
- **no credential validator** is offered, because Go's public `GET /models`
  cannot validate a key.

## Files

```
app/src/main/kotlin/com/voicechat/agent/providers/opencodego/
  OpenCodeGoProtocol.kt        normalized stream frame + typed error mapping
  OpenCodeGoModels.kt          model list + per-model family map + endpoints
  OpenCodeGoChatCompletions.kt OpenAI-compatible Chat Completions encode/parse
  OpenCodeGoResponses.kt       OpenAI Responses encode/parse (store: false)
  OpenCodeGoMessages.kt        Anthropic Messages encode/parse
  OpenCodeGoLanguageModel.kt   LanguageModel adapter (family dispatch + credential)
app/src/test/kotlin/com/voicechat/agent/providers/opencodego/
  OpenCodeGoFixtures.kt        local fixture loader (llm/opencodego/)
  OpenCodeGoProtocolTest.kt    model surface, dispatch, payloads, frame/error parsing
  OpenCodeGoLanguageModelTest.kt fixture acceptance for all three families
  OpenCodeGoSmokeTest.kt       opt-in real-provider smoke test
app/src/test/resources/llm/opencodego/*.sse   recorded frame fixtures
```

## Tests

Every acceptance case is a recorded fixture or a scripted engine. No network.

| Case | Test |
| --- | --- |
| Chat Completions normal stream | `aChatCompletionsStreamCompletesWithTextModelAndUsage` (`chat_normal.sse`) |
| Responses normal stream | `aResponsesStreamCompletesWithTextModelAndUsage` (`responses_normal.sse`) |
| Messages normal stream + merged usage | `aMessagesStreamCompletesWithMergedInputAndOutputUsage` (`messages_normal.sse`) |
| documented endpoint per family + bearer + headers | `eachFamilyGoesToItsDocumentedEndpointWithBearerAuthAndClientHeaders` |
| reasoning channel excluded | `theReasoningChannelIsExcludedFromAssistantText` (`responses_reasoning.sse`) |
| provider error (rate limit / overload) | `aChatProviderErrorMapsToRateLimited`, `aMessagesProviderErrorMapsToUnavailableAndKeepsThePrefix` |
| HTTP status → typed reason | `httpStatusesMapToTypedFailures` |
| network loss | `aNetworkLossKeepsThePartialTextAndReportsNetwork` |
| terminal-less stream | `aTerminalLessStreamIsNotACompletion` (`terminal_less.sse`) |
| unplaced model refused before send | `aModelWithNoVerifiedProtocolFamilyIsRefusedBeforeAnyRequest` |
| unsupported reasoning refused before send | `aReasoningLevelTheAdapterDoesNotSupportIsRefusedBeforeAnyRequest` |
| missing credential | `aMissingCredentialFailsNotConfiguredWithoutSending` |
| cancellation mid-stream | `cancellingMidStreamCancelsTheEngineAndEmitsNoTerminalEvent` |
| capability omission | `theAdapterDeclaresOnlyTheVerifiedCapabilities`, `theRegistryGoRowClaimsNoReasoningAndMarksWhatIsUnverified`, `noCredentialValidatorIsOfferedBecauseTheModelListNeedsNoKey` |
| credential/content never logged | `theRequestAndStreamNeverPutTheCredentialOrContentInLogs` |

The opt-in smoke test is documented and marked **not run** in
[Tests.md](../Tests.md) § M17.

## Unverified / unknown (explicit)

- **Go-published SSE framing.** The Go page names the protocol family (via its
  AI SDK package) but does not print the SSE event names. The mappings follow the
  named protocols' own documentation; the Go-specific framing is confirmed only
  by an opt-in live smoke run (R-0131).
- **Reasoning control.** No reasoning parameter is documented; the adapter
  claims none (R-0135).
- **Usage reporting.** Not promised; surfaced opportunistically only (R-0134).
- **Model list drift.** The family map is dated; new/renamed models need
  re-verification (R-0130), and `GET /models` ids the page does not place are
  refused (R-0137).
- **Per-conversation session id.** A single per-adapter id is sent; wiring the
  real conversation id is M23 (R-0132).
- **Messages `max_tokens`.** A fixed 4096 default is required by the protocol but
  is not a Go-verified figure (R-0133).
- **Terms for a non-coding voice client.** Open (R-0020).
- **Live run.** No real-provider smoke run was performed (R-0138).

## Limitations

- Go's model list is public and identity-only; per-model reasoning/capability
  metadata is unavailable, so a model-specific rejection surfaces as a typed
  error rather than being hidden up front.
- Only completion (chat/responses/messages) is wired; no tool, vision, or
  files API is used. `deepseek-v4-flash-vision-exp` is placed on Chat Completions
  but vision input is out of scope.
- The app still runs `NotConfiguredLanguageModel` in the UI; wiring this adapter
  into the turn path is M23 (R-0097).

## Sources (accessed 2026-09-29)

- OpenCode Go (plans, model table, endpoints per model, client requirements,
  privacy) — https://opencode.ai/v2/docs/console/go/
- OpenCode Zen (Console API-key + `Authorization: Bearer` example, shared
  console surface) — https://opencode.ai/docs/zen/
- Go model list, read directly without a credential —
  https://opencode.ai/zen/go/v1/models
- OpenAI-compatible Chat Completions streaming (delta chunks, `[DONE]`) —
  https://platform.openai.com/docs/api-reference/chat/streaming
- OpenAI Responses streaming (typed events, `response.completed`, `store`) —
  https://platform.openai.com/docs/api-reference/responses-streaming
- Anthropic Messages streaming (`message_start`, `content_block_delta`,
  `message_delta`, `message_stop`, `error`) —
  https://docs.anthropic.com/en/api/messages-streaming
