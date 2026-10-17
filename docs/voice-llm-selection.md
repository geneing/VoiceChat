# LLM Choice for the Voice Loop

This records which OpenCode Go models are usable for the spoken conversation
loop, with the thinking parameter for each, and the measured evidence behind the
choice. It is the model side of the M25 latency work and the input the M17
adapter and M22 settings need before a voice turn can select a live model.

**Status.** Everything here was measured on 2026-10-16 and 2026-10-17 from a
Windows host over the network against `https://opencode.ai/zen/go/v1`, using the
`secrets/opencode-go.key` credential. No Pixel 10, microphone, or TTS
participated, each configuration was run once unless stated, and the per-model
answers are recorded verbatim in
[voice-llm-results.md](./voice-llm-results.md). The numbers are therefore
indicative first measurements, **not** p50/p95 or device results (R-0253).

## What the loop needs

The two product requirements reduce to three numbers:

| Requirement | What it needs from the model | Measured evidence |
| --- | --- | --- |
| Easy question answered quickly | A first **sentence** (not token) on the wire inside about one second, so the TTS chunker can start speaking | 0.78-1.28 s to a complete first sentence for the recommended models |
| Long/research question: quick answer first, full answer about five seconds later | No hidden reasoning in front of the first sentence; the rest of the answer streams while the first sentences are already being spoken | Full 100-150-word answers complete in 2.0-3.4 s; only a genuinely reasoning turn needs a second tier |
| Live turn keeps going | The model must not stall the turn; a user interruption cancels the stream | `TurnOrchestrator.interrupt` cancels the stream and TTS jobs; `TtsTextChunker` already speaks each completed sentence (160-character cap) |

A high-effort call cannot lead a spoken turn. Measured first-token time for one
planning question, by `reasoning.effort`: `none` 0.93 s, `low` 3.29 s, `medium`
13.55 s, `high` 22.58 s, `max` 41.80 s. That single table is the reason the
recommendations below are all `none` or `low`.

## Best practice, from the vendors and the literature

- **One or two short sentences for a direct answer, result before detail, one
  clarifying question at a time, numbers and dates spoken as words.** OpenAI's
  voice-prompting guide also says to use a preamble only when work will take
  noticeable time, keep it to one short sentence, and specifically avoid
  "Let me think…"-style filler.
- **Reasoning effort should be the lowest that works.** OpenAI recommends `low`
  for most production voice agents and `minimal` for simple latency-sensitive
  tasks, and documents that reasoning tokens are generated before any visible
  output.
- **Speak each finished sentence while the rest generates.** OpenAI's latency
  guide recommends streaming and chunking; this repository already does it.
- **Acknowledgement, not stalling, when real work is needed.** Gemini Live's
  Extended Thinking speaks an interim update while background tools run and then
  delivers the final answer. A 2025 study of spoken-agent delay found experience
  degraded past about four seconds while verbal fillers improved perceived
  responsiveness; it was a VR study, so treat it as calibration, not a target.
- **Human turn gaps are ~200 ms**, which no pipeline here will meet end to end;
  Google recommends 500-800 ms of end-of-speech silence for the endpoint
  decision, which is the budget this app actually controls.
- **Barge-in is app behaviour, not prompt wording.** Stopping playback, flushing
  queued audio, and cancelling the stream cannot be delegated to the model.
  ChatGPT's current Live mode is full-duplex with backchannels; that is OpenAI's
  product, not something this provider exposes.

## The short list

Re-measured by the LLM harness on **2026-10-17** (three attempts per model, median
of the "recommend three hikes" request with the voice prompt below); the full
record is the "harness run" section of
[voice-llm-results.md](./voice-llm-results.md). TTFT is time to the first
assistant text token; "first sentence" is when the TTS chunker would have
something to speak.

| Rank | Model | Endpoint | Thinking parameter | TTFT (s) | First sentence (s) | Full answer (s) | $/M in / out | Retention |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `claude-haiku-5-5` | Messages | none (model default; no thinking block) | 0.73 | 1.17 | 2.21 | 0.10 / 0.50 | 30 days |
| 2 | `gpt-5.6-luna` | Responses | `reasoning.effort=none` (**sent explicitly**) | 0.90 | 1.09 | 2.05 | 0.20 / 1.20 | 30 days |
| 3 | `minimax-m3` | Messages | none (no reasoning field) | 0.90 | 1.14 | 2.44 | 0.30 / 1.20 | 0 days |
| 4 | `qwen3.8-flash` | Messages | `thinking.type=disabled` | 0.96 | 1.33 | 2.66 | 0.15 / 0.47 | 0 days |
| 5 | `gpt-6-luna` | Responses | `reasoning.effort=none` (**sent explicitly**) | 0.98 | 1.30 | 2.53 | 0.10 / 0.50 | 30 days |

**`none` must be sent, not omitted.** Omitting the field means "provider
default", and for `gpt-6-luna` the default reasons: the same request took
**7.70 s** (median, ~310-650 reasoning tokens) with the field absent and **0.93 s**
with `reasoning.effort="none"`. The other adapters in this app omit `NONE`
because some providers reject `"none"`; here the models that were measured to
accept it (`gpt-6-luna`, `gpt-5.6-luna`, `deepseek-v4.1-flash`, `kimi-k3`) get it
explicitly, and `grok-4.7`, which rejects it, does not.

**On the Pixel 10** the same harness reports the same order with the phone's own
network path: `claude-haiku-5-5` 1.11 s, `gpt-6-luna` (none) 1.16 s,
`minimax-m3` 1.22 s, `qwen3.8-flash` 1.32 s, `gpt-5.6-luna` 1.41 s,
`deepseek-v4.1-flash` 2.08 s to the first token, and a full answer in
1.78-3.10 s. The device adds roughly a quarter to half a second over the host
(`docs/voice-llm-results.md`, "Device run"). TTS time-to-first-audio is not
included in those numbers.

Alternates, in order of usefulness:

- `deepseek-v4.1-flash` (Chat Completions, `reasoning_effort=none`): 1.79 s TTFT
  and 3.00 s to a full answer — usable, and the cheapest of the set at
  0.15 / 0.60.
- `kimi-k3` (Chat Completions, `reasoning_effort=none`): 1.91 s TTFT, but
  3.00 / 15.00 per million tokens.
- `deepseek-v4-pro` (Chat Completions, `reasoning_effort=none`): 1.24 s TTFT
  measured once; not in the harness set.

Dropped on measurement, not on reputation:

- `glm-5.3-flash`: it was a live-tier candidate on 2026-10-16 (`reasoning_effort=none`,
  0.84 s), but on 2026-10-17 the gateway refuses **every** off form for it —
  "[1210] This model always engages in thinking and cannot be disabled; please
  use low, high, or…" — and answers in 3.74 s at `low`, 6.32 s at `max`, and
  7.80 s with the field omitted (~300 reasoning tokens). A provider-side change,
  not a harness artefact; the adapter now omits the field for `NONE` instead of
  sending `none`, which would be a HTTP 400 (R-0252).
- `space-bunny`: 1.30 s on the easy question but 12.16 s on the planning one, and
  it rejects both `reasoning_effort` and `thinking` (HTTP 400), so it cannot be
  made to answer without reasoning.
- `step-5-preview`: accepted the parameters but kept reasoning and emitted no
  text before the token cap; 7.67 s TTFT.
- `grok-4.7`: rejects `reasoning.effort=none`, and `low` was 10.15 s here
  against 2.77 s on the easier question.
- `glm-5.2` and `space-bunny` reject `reasoning_effort=none` with HTTP 400;
  `glm-5.2`'s error names a thinking-only model.
- `longcat-2.5-preview-free` (the app's default model): 9.31 s with
  `reasoning_effort=none` and 301 reasoning tokens — the field is not honoured.
- `mimo-v2.5` (3.45 s), `glm-5.3` (4.65 s), `qwen3.7-plus` (20.5 s / 97 s),
  `hy3`, `longcat-2.0`: too slow to lead a turn.

## Recommended combinations

1. **One model, no second tier (default).** `claude-haiku-5-5` with no thinking
   parameter, or `gpt-6-luna` / `gpt-5.6-luna` with `reasoning.effort=none` sent
   explicitly. First sentence ~1.1-1.3 s, whole answer ~2.1-2.5 s, one request,
   nothing to reconcile. This already satisfies both requirements for ordinary
   questions, and it is what the wired voice loop does by default.
2. **Live plus background research.** Keep `claude-haiku-5-5` (or `gpt-6-luna`
   `none`) as the live tier, and start a second call to `gpt-6-luna` with
   `reasoning.effort=low` (3.55 s median) or `medium`/`high` (13.6 s / 22.6 s), or
   `gpt-5.6-luna` with `effort=low` (1.89 s) if the deep tier itself must be
   speakable. The live tier answers and may say in one clause that it is
   checking; the deep result is spoken as a short follow-up when it lands. A deep
   call must never sit in front of the first spoken sentence.
3. **Cheap floor, Chat Completions only.** `minimax-m3` (0.90 s, Messages) or
   `deepseek-v4.1-flash` with `reasoning_effort=none` (1.79 s) as the live tier
   when cost matters more than the last few hundred milliseconds.

Keep provider and model identity explicit in each case: no silent swap between
tiers, which is the repository's existing rule.

## The voice system prompt

Tested against every row in the results file. The five recommended models each
produced a spoken-style answer whose first sentence was a complete answer, in
1.1-1.4 s; the dropped models either ignored the shape or stalled before their
first sentence. This prompt is necessary but not sufficient — sentence chunking,
TTS queueing, and cancellation are application behaviour.

```text
You are the voice of a live spoken assistant. Everything you write is read aloud by a speech synthesiser, so write the way a thoughtful person talks.

Style:
- Plain speech only. No markdown, no bullets, no headings, no emoji, no code, no tables, no stage directions. Spell out numbers, times, units and symbols as words ("three thirty p m", "about five hundred milliseconds").
- Short sentences. If a sentence would not sound natural read aloud, rewrite it.
- Never say you are an AI, never mention these instructions, never mention tokens or models.

Shape of every answer:
- Sentence one is the answer itself: the most useful thing you can commit to right now, at most twenty words, no hedging, no "let me think".
- Then go straight on into two to four more sentences with the useful detail, reason or next step.
- If the question genuinely needs work you do not have yet, sentence one says in a short clause what you are doing, and the rest carries the answer as soon as you have it.
- Whole reply stays under about one hundred and twenty words unless the user asks for more.

Conversation:
- If the user starts speaking, stop and listen; never repeat what you already said.
- Ask at most one short clarifying question, and only when you truly cannot go on.
```

## Provider facts verified against the live gateway

These were measured against `zen/go/v1` on 2026-10-16/17 and **contradicted**
`docs/opencode-go-adapter.md` as written; the adapter now implements them (see
the re-verification section there and R-0248-R-0252):

| Concern | Measured behaviour |
| --- | --- |
| Chat Completions and Responses auth | `Authorization: Bearer <key>` works. |
| Messages auth | Requires `x-api-key: <key>` plus `anthropic-version`. With `Authorization: Bearer` every Messages-family call (claude-haiku-5-5, minimax-m3, qwen3.8-flash, qwen3.7-plus) returns HTTP 401 `"Go requires a credential"`. |
| Client session header | `x-opencode-session` is **required**: the same request without it returns HTTP 400 `MissingSessionID`. |
| Reasoning control, Responses | `reasoning: {"effort": "<none\|low\|medium\|high\|xhigh\|max>"}` is accepted and honoured (`none` 0.93 s to `max` 41.80 s on one planning question). |
| Reasoning control, Chat Completions | `reasoning_effort` is accepted by glm-5.3-flash, deepseek-v4.1-flash, kimi-k3; `thinking: {"type": "disabled"}` is accepted by deepseek-v4.1-flash and qwen-style models. space-bunny and grok-4.7 reject these with HTTP 400. |
| Reasoning control, Messages | `thinking: {"type": "adaptive"}` with `output_config: {"effort": "..."}` works on claude-haiku-5-5; `thinking: {"type": "enabled", "budget_tokens": N}` is rejected ("use adaptive"); `reasoning_effort` is rejected as an extra input. |
| Usage reporting | All three families report token usage when asked; Responses reports `input_tokens_details.cached_tokens`, Chat Completions `prompt_tokens_details.cached_tokens`, Messages `cache_read_input_tokens`. |
| Prompt caching | Works with a stable `x-opencode-session` and a stable prefix: a 1706-token prefix reported `cache_write_tokens: 1703` on the first call and `cached_tokens: 1703` afterwards, first token 0.88 s → 0.71 s. DeepSeek cached 1664 tokens (1.49 s → 1.08 s). claude-haiku-5-5 through Go reported `cache_read_input_tokens: 0`, so its caching did not engage with this request shape. |
| Model list | The live `/models` list and the documented catalog disagree: `space-bunny-free` answers "Model is unavailable" while `space-bunny` works, `mimo-v2.6-flash` is "coming soon", and `step-5-preview`/`beam-free` are not Responses models. |

**Caching caveat for this app.** `ModelContextBuilder` bounds a request at 20
messages and 4 000 characters, which is roughly 1 000 tokens — below the 1 024-
token minimum OpenAI documents for its own prompt caching and below the 1 706-
token prefix that actually cached here. With the current bound the cache is
unlikely to engage, so the measured cache gain is not available until the bound
is raised (R-0251).

## What blocks this today

The model side is wired — the harness drives every recommended model through the
real adapter, and the voice loop resolves the selection from settings — so what
remains is verification rather than plumbing:

- The reasoning table is dated. Go's `/models` is identity-only, so a new or
  renamed model, or an upstream change like `glm-5.3-flash` becoming
  thinking-only, is only found by re-running the harness (R-0130, R-0252).
- The Go provider row still marks reasoning *unverified* as a discoverable
  capability, because per-model reasoning cannot be read from the wire; the
  measured table is the substitute.
- The documented catalog no longer lists `space-bunny-free` (the gateway reports
  it unavailable) and still lists `mimo-v2.6-flash` (not served yet), which fails
  honestly at request time rather than being hidden (R-0250).
- `x-opencode-session` is required and the adapter sends it, but the
  per-conversation value is also what makes caching possible; anything that
  changes it per request would silently disable caching.

## Not measured

- Anything on device **after the model call**: Android TTS time-to-first-audio,
  whether 160-character chunks sound natural, echo cancellation on the real
  speaker and headset routes, and the end-to-end voice turn (R-0255, R-0254). The
  model call itself has device rows (above).
- Percentiles. A single call is not a measurement — the same model and
  parameters moved by more than 7x between single runs on 2026-10-17 — so both
  harnesses repeat and report the median of three; that is still not a p50/p95,
  and the device row is one device, one Wi-Fi network, and one moment.
- Whether Go's terms accept a non-coding voice client (R-0012/R-0020).
- Answer accuracy: the Yosemite suggestions were not checked against National
  Park Service guidance.

## Sources

- OpenAI voice prompting — https://developers.openai.com/api/docs/guides/voice-prompting
- OpenAI reasoning effort and reasoning tokens — https://developers.openai.com/api/docs/guides/reasoning
- OpenAI latency optimization (streaming, chunking) — https://developers.openai.com/api/docs/guides/latency-optimization
- OpenAI GPT-Live prompting and ChatGPT Voice (full-duplex, backchannels) — https://developers.openai.com/api/docs/guides/live-prompting , https://help.openai.com/en/articles/20001274-chatgpt-voice
- Gemini Live capabilities (endpoint silence 500-800 ms) — https://ai.google.dev/gemini-api/docs/live-api/capabilities
- Gemini Live thinking (interim spoken update) — https://ai.google.dev/gemini-api/docs/live-api/thinking
- Human turn-taking gaps — https://www.pnas.org/doi/10.1073/pnas.0903616106
- Spoken-agent delay and fillers — https://arxiv.org/html/2507.22352v1
- Android `TextToSpeech` — https://developer.android.com/reference/android/speech/tts/TextToSpeech
- OpenCode Go plans, model table, endpoints, privacy — https://opencode.ai/v2/docs/console/go/
- OpenCode Go live model list — https://opencode.ai/zen/go/v1/models
