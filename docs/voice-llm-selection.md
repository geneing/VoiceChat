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

Measured on both an easy question and the harder "recommend three Yosemite
hikes" request (the latter with the recommended voice prompt). TTFT is time to
the first assistant text token; "first sentence" is when the TTS chunker would
have something to speak; "characters" is assistant text only.

| Rank | Model | Endpoint | Thinking parameter | TTFT easy / hard (s) | First sentence, hard (s) | Full answer, hard (s) | $/M in / out | Retention |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `claude-haiku-5-5` | Messages | none (model default), or `thinking.type=adaptive` with `output_config.effort=low` | 0.75-1.00 / 1.00 | 1.28 | 2.35 | 0.10 / 0.50 | 30 days |
| 2 | `gpt-6-luna` | Responses | `reasoning.effort=none` (easy) / `low` (harder) | 0.68-1.18 / 0.90 | 1.40 | 2.36 | 0.10 / 0.50 | 30 days |
| 3 | `gpt-5.6-luna` | Responses | `reasoning.effort=none` | 0.78-1.01 / 0.85 | 1.11 | 1.98 | 0.20 / 1.20 | 30 days |
| 4 | `minimax-m3` | Messages | none | 0.89-1.04 / 0.94 | 1.22 | 2.13 | 0.30 / 1.20 | 0 days |
| 5 | `glm-5.3-flash` | Chat Completions | `reasoning_effort=none` | 1.04 at `low` / 0.84 at `none` | - | - | 0.15 / 0.50 | 0 days |

Alternates, in order of usefulness:

- `deepseek-v4.1-flash` (Chat Completions, `thinking.type=disabled`): the cheap
  floor, but 2.13 s TTFT on the hard question. Acceptable when an answer is not
  the first thing spoken.
- `qwen3.8-flash` (Messages, `thinking.type=disabled`): 1.19 s TTFT, but a
  4.43 s full answer and the least focused answer of the set in this run.
- `kimi-k3` (Chat Completions, `reasoning_effort=none`): 1.25 s TTFT once
  reasoning is off, but 3.00 / 15.00 per million tokens.

Dropped on measurement, not on reputation:

- `space-bunny`: 1.30 s on the easy question but 12.16 s here with 4033
  reasoning characters, and it rejects both `reasoning_effort` and `thinking`
  (HTTP 400), so it cannot be made to answer without reasoning.
- `step-5-preview`: accepted the parameters but kept reasoning and emitted no
  text before the token cap; 7.67 s TTFT.
- `grok-4.7`: rejects `reasoning.effort=none`, and `low` was 10.15 s here
  (384 reasoning characters) against 2.77 s on the easier question.
- `glm-5.3` (4.65 s, 1783 reasoning characters), `qwen3.7-plus` (20.5 s on an
  easy question, 97 s on a hard one), `hy3`, `longcat-2.0` (first byte alone
  4-6 s): too slow to lead a turn. `qwen3.8-flash` without
  `thinking.type=disabled` reasoned 6674 characters and emitted no text at all.
- `mimo-v2.6-flash` (in this repo's documented catalog) answers HTTP 400
  "model_coming_soon".

## Recommended combinations

1. **One model, no second tier (default).** `claude-haiku-5-5` with no thinking
   parameter, or `gpt-6-luna` with `reasoning.effort=none`. First sentence
   ~1.0-1.4 s, whole answer ~2.4 s, one request, nothing to reconcile. This
   already satisfies both requirements for ordinary questions.
2. **Live plus background research.** Keep `claude-haiku-5-5` (or `gpt-6-luna`
   `none`) as the live tier, and start a second call to `gpt-6-luna` with
   `reasoning.effort=medium` (13.6 s) or `high` (22.6 s), or `gpt-5.6-luna`
   with `effort=low` (1.9 s) if the deep tier itself must be speakable. The live
   tier answers and may say in one clause that it is checking; the deep result is
   spoken as a short follow-up when it lands. A deep call must never sit in front
   of the first spoken sentence.
3. **Cheap floor, no Messages family.** `glm-5.3-flash` with
   `reasoning_effort=none` (0.84 s) as live, `deepseek-v4.1-flash` with
   `thinking.type=disabled` as the fallback/detail tier. Both are Chat
   Completions, so they avoid the Messages-family auth gap below entirely.

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

These are measured against `zen/go/v1` on 2026-10-16/17 and **contradict parts of
`docs/opencode-go-adapter.md` as written** (see the corrections section there and
R-0248-R-0252):

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

The short list cannot be selected or driven by the app as it stands:

- The M17 Messages family sends `Authorization: Bearer` for every family, so
  every Messages-family model fails with 401 (R-0248).
- The Go registry row declares `reasoningLevels = emptySet()` and
  `usageReporting = false`, and `LlmRequestValidator` refuses any level a
  provider does not declare, so the parameters this document recommends cannot be
  requested (R-0249). `ReasoningLevel` also cannot express `thinking.type=disabled`
  or `adaptive` + effort, so the mapping needs a decision, not just a registry
  edit.
- The picker's documented catalog is `OpenCodeGoModels.FAMILIES`, which has
  drifted: it omits `claude-haiku-5-5` (the recommended live model) and lists
  entries the gateway no longer serves (R-0250).
- No live tier is stable without a working reasoning-off switch, and three
  models have none (R-0252).
- `x-opencode-session` is required, and the adapter does send it, but the
  per-conversation value is the only thing making caching possible; anything that
  changes it per request would silently disable caching.

## Not measured

- Anything on device: Android TTS time-to-first-audio, whether 160-character
  chunks sound natural, audio-route behaviour, and the end-to-end voice turn.
- Percentiles. Every row here is one run; the same model moved by 2x between the
  easy and the hard question, and `minimax-m3` once took 4.98 s on a single
  greeting.
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
