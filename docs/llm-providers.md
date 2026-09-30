# LLM Providers and Connections

This is the planned provider and account-connection scope. No provider client,
login flow, or QR pairing is implemented yet; API-key storage and the provider
capability registry are implemented (see
[credentials.md](./credentials.md)).

The verified per-provider endpoint, authentication, model-discovery, streaming,
and reasoning capabilities—and the providers where a capability remains
unknown—are in the [decision record's provider capability matrix](./decisions.md#4-provider-capability-matrix).
Keep that matrix current when a provider's official documentation changes.

## Planned external providers

| Provider | Configuration direction |
| --- | --- |
| OpenAI | Provider-specific API endpoint, model selection, and supported authentication |
| OpenRouter | Provider-specific endpoint, model catalog, and supported authentication |
| OpenCode Go | Separate provider entry and account/connection flow |
| OpenCode Zen | Separate provider entry and account/connection flow |
| DeepSeek | Provider-specific endpoint, model catalog, and supported authentication |
| Hermes Agent API Server | Configurable server address and that server's configured authentication |

Keep provider identity separate from model identity. The user should select a
provider and then a model offered or documented for that provider. Do not
assume that matching OpenAI-compatible request formats imply matching model
names, tool/function calling, streaming semantics, context limits, quotas, or
authentication. Validate these details against current official provider
documentation during implementation.

For Hermes Agent API Server, treat the server address as user/admin
configuration rather than hard-coding a public endpoint. Require secure
transport for non-local connections and make the destination visible before
sending prompts.

## Connection methods

Offer connection methods supported by the selected provider:

1. **API key:** let the user enter or paste their own key, validate it with a
   minimal provider-supported request when practical, and provide replace and
   remove actions.
2. **Provider sign-in:** where officially supported, use the provider's
   documented OAuth/device authorization or browser-based flow. Use mobile-safe
   authorization patterns (for example, authorization code with PKCE) when the
   provider supports them; never collect the provider account password in the
   app.
3. **QR pairing:** where officially supported, display or scan the
   provider-generated pairing QR code and complete the provider's challenge
   flow. A QR code must not contain a reusable API key, password, or long-lived
   access token. Pairing challenges should be short-lived and single-use.

Not every provider will support all three methods. Query or configure auth
capabilities per provider and show only valid options. Do not invent a QR login
or treat a QR containing an arbitrary URL/token as a trusted provider flow.

## API and credential boundary

Keep authentication separate from chat transport and model selection. A
provider adapter should declare which authentication methods and API features
it supports; orchestration should depend on a common app-facing LLM contract,
not on provider SDK types.

- Use the provider's documented TLS endpoint and auth mechanism. Permit a
  custom server URL only for providers that support it, and validate the
  scheme/host before use.
- Store user-provided API keys and refresh/access tokens using an
  Android-Keystore-backed design. Keep secrets out of logs, crash reports,
  analytics, clipboard history where controllable, and backups where
  appropriate.
- Never ship an app-owned long-lived provider secret in the APK. If production
  access requires a confidential shared credential, put it behind a trusted
  backend rather than relying on obfuscation.
- Provide clear connection status, credential replacement/removal, and
  actionable error reporting without exposing secret values.
- Do not silently move a request to another provider when credentials expire
  or a provider is unavailable; provider changes affect privacy, pricing, and
  model behavior.

## User-visible provider selection

Show the selected provider and model before a conversation request. Explain
that transcript text and included conversation context are sent to the selected
external service. Show connection/setup errors in context and allow retry,
credential replacement, provider change, and cancellation.

For the complete voice loop and data disclosure requirements, see
[architecture](./architecture.md) and
[privacy and security](./privacy-and-security.md).
