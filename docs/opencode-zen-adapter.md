# OpenCode Zen Adapter (M18)

This is a remote provider behind the M12 [`LanguageModel`
contract](./llm-contract.md), after OpenAI (M14), OpenRouter (M15), DeepSeek
(M16), and OpenCode Go (M17). It uses the shared
[remote transport](./llm-transport.md) and is deliberately **not** treated as
OpenAI-compatible — and **not** as OpenCode Go: Zen is a separate product with
its own endpoint table, its own per-model protocol assignment, and its own
`/systemone` decision surface.

All provider facts below were **verified against the official OpenCode Zen /
Console documentation on 2026-09-29** (the M00 matrix was read 2026-09-28; this
is the re-verification R-0072 requires before an adapter ships). Sources are
listed at the end. Facts that could not be verified are listed explicitly under
[Unverified / unknown](#unverified--unknown-explicit) and are omitted rather
than invented.

## Verified facts (accessed 2026-09-29)

| Concern | Verified behavior |
| --- | --- |
| Base URL | `https://opencode.ai/zen/v1` (fixed provider; `expectedHost` `opencode.ai`, so a substituted host is refused before a request is built). |
| Endpoints | Three per-model chat endpoints: `…/chat/completions`, `…/responses`, `…/messages`. The Zen model table names the endpoint **and** the AI SDK package per model. |
| Non-chat surfaces | `…/systemone` (Jev, a System One structured-decision model) and `…/models/<model>` (the Gemini models, `@ai-sdk/google`). Neither is a chat completion; both are excluded from the adapter's chat model surface. |
| Model discovery | `GET …/models` returns the OpenAI-shaped `{"object":"list","data":[{"id":…,"object":"model","created":…,"owned_by":"opencode"}]}`. It lists **identities only** — no family, reasoning, or usage metadata. |
| Model discovery is public | The `…/models` endpoint answers **without** an `Authorization` header (read directly 2026-09-29), so it can list models but can never validate a credential. The adapter therefore offers **no** credential validator (`CredentialValidationSupport.NONE`). |
| Authentication | User-supplied API key, from OpenCode Zen / Console after signing in and adding credits. Sent as `Authorization: Bearer <key>` with `Content-Type: application/json`. No password, OAuth, device, or QR flow is documented. |
| Account requirements | Sign in to OpenCode Zen, add billing details and credits, copy the API key; usage is pay-as-you-go per model. Free models exist, auto-reload and monthly limits are workspace settings. |
| Protocol families | `@ai-sdk/openai-compatible` → `/chat/completions` (Qwen3.8 Max, DeepSeek V4, MiniMax, GLM, Kimi, and the free models); `@ai-sdk/openai` → `/responses` (GPT, Grok, Muse Spark); `@ai-sdk/anthropic` → `/messages` (Claude, Qwen3.8 Flash, Qwen3.7/3.6/3.5 Plus). |
| Reasoning control | **Not documented.** The Zen page describes no reasoning/thinking parameter, so the adapter declares no reasoning level and refuses any requested level except `none`. |
| Usage reporting | **Not promised.** The adapter parses usage only when a family's own protocol actually reports it; the provider capability claims no usage reporting. |
| Client identity | Zen documents no client-session header. Unlike OpenCode Go's `x-opencode-session`, none is sent; only the documented `Authorization`, `Content-Type`, and the standard SSE `Accept` header are. |
| Privacy (per-model) | All models are hosted in the US. Providers follow a zero-retention policy with listed exceptions (free models may use data to improve the model; OpenAI/Anthropic APIs retain requests for 30 days; Muse Spark 1.3 Contributor Free trains Meta models on prompts/completions). Not yet surfaced in the app (R-0149). |

### Families and the model→endpoint map

The Zen page presents a dated endpoint table. The adapter ships a **static,
dated** map (`OpenCodeZenModels.FAMILIES`) covering only the ids the page places
on a chat endpoint. A model present in `GET /models` but not placed by the page
(for example `claude-sonnet-5-5`, `deepseek-v4-flash-free`) has **no verified
chat family**, so the adapter refuses it with a typed `LLM_INVALID_REQUEST`
rather than guessing a protocol (R-0140, R-0147).

Two documented surfaces are deliberately **never** chat models:

- **`/systemone` (Jev).** `jev-1.13` and `jev-1.13-free` are structured-decision
  models: a request carries a `state` and typed `questions` (`noul`, `choice`,
  `score`) and returns values/probabilities, not assistant text. They are not in
  the chat family map, so they can never be selected or sent as a chat
  completion.
- **The Google family.** The Gemini ids (`gemini-3.8-flash`, …, `gemini-3.1-pro`)
  are served through `@ai-sdk/google` at `…/models/<model>`. The Zen page does
  not print that request/stream shape, so it is not implemented and the ids are
  refused (R-0142).

## Request mapping

`OpenCodeZenLanguageModel` classifies `request.model.modelId` and calls the
family encoder; the payloads are distinct and are not interchangeable:

| Family | Endpoint | Body (excerpt) |
| --- | --- | --- |
| Chat Completions | `/chat/completions` | `{"model":…,"messages":[{"role":"system\|user\|assistant","content":…}],"stream":true}` |
| Responses | `/responses` | `{"model":…,"input":[{"type":"message","role":"developer\|user\|assistant","content":…}],"store":false,"stream":true}` |
| Messages | `/messages` | `{"model":…,"max_tokens":4096,"system":…,"messages":[{"role":"user\|assistant","content":…}],"stream":true}` |

- **Responses always sends `store: false`** (R-0018), exactly as the OpenAI and
  OpenCode Go adapters do, so the request cannot silently opt into stored
  responses.
- **A system prompt** maps to `system` (Chat Completions), `developer`
  (Responses), or the top-level `system` string (Messages).
- **Messages `max_tokens`** is required by the protocol but is not part of the
  M12 contract, so a bounded 4096 default is sent; the gap is tracked (R-0143).
- Provider/model identity stays explicit; nothing is silently rerouted.

## Response mapping

Each family normalizes to `OpenCodeZenStreamFrame`, and the adapter folds it into
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
mapped by `OpenCodeZenErrors` from the two documented vocabularies (OpenAI-style
`code` and Anthropic-style `type`): rate/quota → `LLM_RATE_LIMITED`,
invalid/not-found → `LLM_INVALID_REQUEST`, auth/permission →
`LLM_AUTHENTICATION_FAILED`, server/overload → `LLM_UNAVAILABLE`. No provider
`message` is copied into `VoiceAgentError.detail`.

## Credential integration (M13)

`OpenCodeZenLanguageModel` loads the credential from the M13 `CredentialStore` at
request time and places it only in the `Authorization` header:

- no credential → `LLM_NOT_CONFIGURED`, and no request is sent;
- the secret never reaches a result, trace, `VoiceAgentError.detail`, or the
  developer log; `RemoteHttpRequest.toString` redacts header values;
- **no credential validator** is offered, because Zen's public `GET /models`
  cannot validate a key.

## Files

```
app/src/main/kotlin/com/voicechat/agent/providers/opencodezen/
  OpenCodeZenProtocol.kt        normalized stream frame + typed error mapping
  OpenCodeZenModels.kt          model list + per-model family map + endpoints
  OpenCodeZenChatCompletions.kt OpenAI-compatible Chat Completions encode/parse
  OpenCodeZenResponses.kt       OpenAI Responses encode/parse (store: false)
  OpenCodeZenMessages.kt        Anthropic Messages encode/parse
  OpenCodeZenLanguageModel.kt   LanguageModel adapter (family dispatch + credential)
app/src/test/kotlin/com/voicechat/agent/providers/opencodezen/
  OpenCodeZenFixtures.kt        local fixture loader (llm/opencodezen/)
  OpenCodeZenProtocolTest.kt    model surface, dispatch, payloads, frame/error parsing
  OpenCodeZenLanguageModelTest.kt fixture acceptance for all three families
  OpenCodeZenSmokeTest.kt       opt-in real-provider smoke test
app/src/test/resources/llm/opencodezen/*.sse   recorded frame fixtures
```

## Tests

Every acceptance case is a recorded fixture or a scripted engine. No network.

| Case | Test |
| --- | --- |
| Chat Completions normal stream | `aChatCompletionsStreamCompletesWithTextModelAndUsage` (`chat_normal.sse`) |
| Responses normal stream | `aResponsesStreamCompletesWithTextModelAndUsage` (`responses_normal.sse`) |
| Messages normal stream + merged usage | `aMessagesStreamCompletesWithMergedInputAndOutputUsage` (`messages_normal.sse`) |
| documented endpoint per family + bearer + no Go session header | `eachFamilyGoesToItsDocumentedEndpointWithBearerAuthAndNoSessionHeader` |
| provider identity is Zen | `theProviderIdentityIsOpenCodeZen` |
| reasoning channel excluded | `theReasoningChannelIsExcludedFromAssistantText` (`responses_reasoning.sse`) |
| provider error (rate limit / overload) | `aChatProviderErrorMapsToRateLimited`, `aMessagesProviderErrorMapsToUnavailableAndKeepsThePrefix` |
| HTTP status → typed reason | `httpStatusesMapToTypedFailures` |
| network loss | `aNetworkLossKeepsThePartialTextAndReportsNetwork` |
| terminal-less stream | `aTerminalLessStreamIsNotACompletion` (`terminal_less.sse`) |
| `/systemone` never a chat model | `theSystemOneDecisionModelIsRefusedBeforeAnyRequest`, `theSystemOneDecisionModelsAreNeverChatModels` |
| Google family omitted | `theGoogleFamilyModelIsRefusedBeforeAnyRequest`, `theGoogleFamilyModelsAreRefusedBecauseThatFamilyIsNotImplemented` |
| unplaced model refused before send | `aModelWithNoVerifiedProtocolFamilyIsRefusedBeforeAnyRequest` |
| unsupported reasoning refused before send | `aReasoningLevelTheAdapterDoesNotSupportIsRefusedBeforeAnyRequest` |
| missing credential | `aMissingCredentialFailsNotConfiguredWithoutSending` |
| cancellation mid-stream | `cancellingMidStreamCancelsTheEngineAndEmitsNoTerminalEvent` |
| capability omission | `theAdapterDeclaresOnlyTheVerifiedCapabilities`, `theRegistryZenRowClaimsNoReasoningAndMarksWhatIsUnverified`, `noCredentialValidatorIsOfferedBecauseTheModelListNeedsNoKey` |
| credential/content never logged | `theRequestAndStreamNeverPutTheCredentialOrContentInLogs` |

The opt-in smoke test is documented and marked **not run** in
[Tests.md](../Tests.md) § M18.

## Unverified / unknown (explicit)

- **Zen-published SSE framing.** The Zen page names the protocol family (via its
  AI SDK package) but does not print the SSE event names. The mappings follow the
  named protocols' own documentation; the Zen-specific framing is confirmed only
  by an opt-in live smoke run (R-0141).
- **Reasoning control.** No reasoning parameter is documented; the adapter
  claims none (R-0145).
- **Usage reporting.** Not promised; surfaced opportunistically only (R-0144).
- **Google `/models/<model>` family.** Documented as an endpoint but with no
  request/stream example; not implemented, and the Gemini ids are refused
  (R-0142).
- **Model list drift.** The family map is dated; new/renamed models need
  re-verification (R-0140), and `GET /models` ids the page does not place are
  refused (R-0147).
- **Messages `max_tokens`.** A fixed 4096 default is required by the protocol but
  is not a Zen-verified figure (R-0143).
- **Per-model retention/training.** Zen's privacy page lists exceptions the app
  must disclose before a request; not surfaced yet (R-0149).
- **Live run.** No real-provider smoke run was performed (R-0148).
- **Terms for a voice client.** Zen markets "no lock-in" and use "with any other
  coding agent"; that is not a reviewed grant for a non-coding voice client, so
  the open product question stays with the Go row (R-0020) and is not treated as
  settled for Zen.

## Limitations

- Zen's model list is public and identity-only; per-model reasoning/capability
  metadata is unavailable, so a model-specific rejection surfaces as a typed
  error rather than being hidden up front.
- Only chat completions are wired; `/systemone` and the Google family are
  intentionally omitted, and no tool, vision, or file API is used.
- The app still runs `NotConfiguredLanguageModel` in the UI; wiring this adapter
  into the turn path is M23 (R-0097).

## Sources (accessed 2026-09-29)

- OpenCode Zen (account, API key, model table, `/systemone` Jev, pricing,
  privacy, teams) — https://opencode.ai/docs/zen/
- OpenCode Console models (endpoint table, AI SDK package per model, model list,
  Jev) — https://opencode.ai/v2/docs/console/models/
- Console inference API (bearer auth, per-family paths) —
  https://opencode.ai/v2/docs/console/inference/
- Zen model list, read directly without a credential —
  https://opencode.ai/zen/v1/models
- OpenAI-compatible Chat Completions streaming (delta chunks, `[DONE]`) —
  https://platform.openai.com/docs/api-reference/chat/streaming
- OpenAI Responses streaming (typed events, `response.completed`, `store`) —
  https://platform.openai.com/docs/api-reference/responses-streaming
- Anthropic Messages streaming (`message_start`, `content_block_delta`,
  `message_delta`, `message_stop`, `error`) —
  https://docs.anthropic.com/en/api/messages-streaming
