# OpenCode Go LLM Comparison for the Voice Loop

This is a single run of one voice-shaped request against every OpenCode Go model
that was measured for [voice-llm-selection.md](./voice-llm-selection.md), recorded on
**2026-10-17** from a Windows host against `https://opencode.ai/zen/go/v1` with the `secrets/opencode-go.key`
credential. It is **not** a device result: no Pixel 10, microphone, or TTS
participates, and it is one run per model on one network path, so the numbers
are indicative and not a p50/p95 claim (R-0253).

## Method

- Request text, verbatim: "I'm going to Yosemite. Recommend best three hikes up to 10 miles long. One easy, one intermediate, and one challenging."
- Each model ran with the recommended voice system prompt (reproduced at the end) and with the thinking setting named below.
- Each call was streamed with `store:false` (Responses), `max_tokens: 2048` (Messages/Chat), one stable `x-opencode-session` id, and a client user agent of `voicechat-agent/0.1`.
- **TTFT** is the elapsed time from request start to the first assistant text token. **First sentence** is the elapsed time when the first sentence-completing character arrived, which is when the TTS chunker would speak. **Full response** is the elapsed time to the end of the stream. The Messages family needs `x-api-key` and `anthropic-version`; Chat Completions and Responses use `Authorization: Bearer`.
- Time is wall-clock on the host and includes the network path to the gateway and the upstream provider. Reasoning tokens are counted separately and are never assistant text.

## Summary

| Model | Endpoint | Thinking setting | TTFT (s) | First sentence (s) | Full response (s) | Characters | Reasoning chars | HTTP |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `claude-haiku-5-5` | messages | no thinking parameter (model default) | 1.00 | 1.28 | 2.35 | 768 | 0 | 200 |
| `gpt-6-luna` | responses | reasoning.effort = none | 0.90 | 1.40 | 2.36 | 659 | 0 | 200 |
| `gpt-5.6-luna` | responses | reasoning.effort = none | 0.85 | 1.11 | 1.98 | 574 | 0 | 200 |
| `space-bunny` | chat | none exposed or needed | 12.16 | 12.16 | 12.94 | 507 | 4033 | 200 |
| `deepseek-v4.1-flash` | chat | thinking.type = disabled | 2.13 | 2.34 | 3.37 | 502 | 0 | 200 |
| `minimax-m3` | messages | no reasoning parameter (effort leaks think tags into text) | 0.94 | 1.22 | 2.13 | 611 | 0 | 200 |
| `step-5-preview` | chat | none | 7.67 | 7.76 | 8.03 | 532 | 5715 | 200 |
| `grok-4.7` | responses | reasoning.effort = low | 10.15 | 10.34 | 11.99 | 574 | 384 | 200 |
| `glm-5.3-flash` | chat | reasoning_effort = low | 4.19 | 4.25 | 7.59 | 673 | 307 | 200 |
| `qwen3.8-flash` | messages | thinking.type = disabled | 1.19 | 1.78 | 4.43 | 703 | 0 | 200 |
| `glm-5.3` | chat | none (heavy reasoner) | 4.65 | 4.78 | 5.74 | 632 | 1783 | 200 |
| `kimi-k3` | chat | none (max variant only) | 4.70 | 4.70 | 5.10 | 617 | 0 | 200 |

Token usage reported per row: claude-haiku-5-5 468/277; gpt-6-luna 312/138; gpt-5.6-luna 312/116; space-bunny 450/1108; deepseek-v4.1-flash 339/109; minimax-m3 329/119; step-5-preview 350/1496; grok-4.7 1548/555; glm-5.3-flash 316/232; qwen3.8-flash 331/133; glm-5.3 316/536; kimi-k3 402/913.

## Answers, as received

### `claude-haiku-5-5` — no thinking parameter (model default)

- TTFT: 1.00 s; first sentence: 1.28 s; full response: 2.35 s; characters: 768; reasoning characters: 0; HTTP 200
- Provider usage: ``{"input_tokens":468,"output_tokens":277,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens_details":{"thinking_tokens":0}}``

```text
Good choices for Yosemite are Lower Yosemite Fall, Mist Trail to Vernal Fall, and Sentinel Dome. Here is how they split up.

The easy one is the Lower Yosemite Fall loop. It is about one mile round trip, nearly flat, and ends right at the base of the falls.

The intermediate one is the Mist Trail to Vernal Fall. The round trip to the top of Vernal Fall is about three miles with a steady climb of roughly one thousand feet, and the spray can soak you, so bring a rain layer.

The challenging one is Sentinel Dome. It is about four and a half miles round trip with roughly one thousand five hundred feet of climbing, and the views over Yosemite Valley are excellent.

Check trail conditions and any permit requirements on the National Park Service site before you go.
```

### `gpt-6-luna` — reasoning.effort = none

- TTFT: 0.90 s; first sentence: 1.40 s; full response: 2.36 s; characters: 659; reasoning characters: 0; HTTP 200
- Provider usage: ``{"input_tokens":312,"output_tokens":138,"total_tokens":450,"input_tokens_details":{"cached_tokens":0,"cache_write_tokens":0},"output_tokens_details":{"reasoning_tokens":0}}``

```text
For a great mix, choose Lower Yosemite Fall, the Mist Trail to Vernal Fall Footbridge, and the Upper Yosemite Fall Trail to Columbia Rock.

Lower Yosemite Fall is an easy, mostly flat loop of about one mile, with excellent views of the falls. The Mist Trail to Vernal Fall Footbridge is an intermediate round trip of about one point six miles, with steep stone steps and a close-up waterfall view. For a challenge, hike about six miles round trip to Columbia Rock on the Upper Yosemite Fall Trail; it climbs steeply and rewards you with sweeping valley views.

Check current trail conditions and shuttle access before you go, and bring water and sturdy shoes.
```

### `gpt-5.6-luna` — reasoning.effort = none

- TTFT: 0.85 s; first sentence: 1.11 s; full response: 1.98 s; characters: 574; reasoning characters: 0; HTTP 200
- Provider usage: ``{"input_tokens":312,"output_tokens":116,"total_tokens":428,"input_tokens_details":{"cached_tokens":0,"cache_write_tokens":0},"output_tokens_details":{"reasoning_tokens":0}}``

```text
Choose Lower Yosemite Fall, Mist Trail to Vernal Fall, and the Panorama Trail for a balanced Yosemite hiking trio.

Lower Yosemite Fall is easy, about one mile round trip, and offers excellent waterfall and valley views.

Mist Trail to Vernal Fall is intermediate, about four miles round trip, with steep steps and powerful spray in spring.

The Panorama Trail is challenging, about eight and a half miles one way, so use the shuttle from Glacier Point if operating, or plan a much longer return. Check current trail conditions, permits, and shuttle schedules before hiking.
```

### `space-bunny` — none exposed or needed

- TTFT: 12.16 s; first sentence: 12.16 s; full response: 12.94 s; characters: 507; reasoning characters: 4033; HTTP 200
- Provider usage: ``{"prompt_tokens":450,"completion_tokens":1108,"total_tokens":1558,"prompt_tokens_details":{"cached_tokens":128},"completion_tokens_details":{"reasoning_tokens":1003}}``

```text
For Yosemite, choose the Vernal Fall footbridge, Glacier Point, and Lembert Dome.

The easy option is the round trip from Happy Isles to the Vernal Fall footbridge, about two miles with minor stairs. The intermediate option is Glacier Point via Four Mile Trail, about eight-point-eight miles round trip with a steep final mile. The challenging option is the Lembert Dome loop, about five-point-two miles with roughly fifteen hundred feet of climbing. Check seasonal road and trail closures, and start early.
```

### `deepseek-v4.1-flash` — thinking.type = disabled

- TTFT: 2.13 s; first sentence: 2.34 s; full response: 3.37 s; characters: 502; reasoning characters: 0; HTTP 200
- Provider usage: ``{"prompt_tokens":339,"completion_tokens":109,"total_tokens":448,"prompt_tokens_details":{"cached_tokens":0},"completion_tokens_details":{"reasoning_tokens":0}}``

```text
For an easy day, walk the Mist Trail to Vernal Fall, about three miles round trip.

It climbs a thousand feet on wet granite steps, so bring traction and a rain shell. For intermediate, do the Panorama Trail from Glacier Point down to the valley, eight and a half miles, all downhill with huge views. For challenging, hike Half Dome via the Mist Trail, sixteen miles round trip with cables and a permit. If ten miles is a hard limit, swap Half Dome for Clouds Rest, fourteen miles but no permit needed.
```

### `minimax-m3` — no reasoning parameter (effort leaks think tags into text)

- TTFT: 0.94 s; first sentence: 1.22 s; full response: 2.13 s; characters: 611; reasoning characters: 0; HTTP 200
- Provider usage: ``{"input_tokens":329,"output_tokens":119,"cache_read_input_tokens":128}``

```text
For an easy hike, try the Mist Trail to Vernal Fall Footbridge, about one point seven miles round trip with little elevation gain and a thundering waterfall payoff. For intermediate, the Upper Yosemite Falls Trail to Columbia Rock gives you roughly three miles round trip and around a thousand feet of climb, with sweeping views of the valley and Half Dome. For challenging, the Four Mile Trail to Panorama Point climbs about three thousand two hundred feet in roughly four point six miles one way, then you can descend the steeper but swifter Yosemite Falls Trail for a classic valley rim to valley floor loop.
```

### `step-5-preview` — none

- TTFT: 7.67 s; first sentence: 7.76 s; full response: 8.03 s; characters: 532; reasoning characters: 5715; HTTP 200
- Provider usage: ``{"prompt_tokens":350,"completion_tokens":1496,"total_tokens":1846,"prompt_tokens_details":{"cached_tokens":0},"completion_tokens_details":{"reasoning_tokens":0}}``

```text
Your best three are the Valley Loop Trail, the Mist Trail to Nevada Fall, and the Four Mile Trail to Glacier Point. For an easy day, walk the Valley Loop, seven miles of paved path with big views of El Capitan and Bridalveil Fall. For intermediate, take the Mist Trail up past Vernal Fall to Nevada Fall and back, about seven miles round trip with granite steps and waterfall spray. For challenging, climb the Four Mile Trail to Glacier Point, nine and a half miles round trip with a steady steep climb and a huge payoff at the rim.
```

### `grok-4.7` — reasoning.effort = low

- TTFT: 10.15 s; first sentence: 10.34 s; full response: 11.99 s; characters: 574; reasoning characters: 384; HTTP 200
- Provider usage: ``{"input_tokens":1548,"output_tokens":555,"total_tokens":2103,"input_tokens_details":{"cached_tokens":1152},"output_tokens_details":{"reasoning_tokens":432}}``

```text
Mirror Lake, the Mist Trail to Vernal Fall, and Upper Yosemite Fall are the three I’d pick.
Mirror Lake is the easy one, about two miles round trip on mostly flat ground with Half Dome views. The Mist Trail to Vernal Fall is intermediate, roughly three miles round trip with steep wet stone steps and a big payoff at the top. Upper Yosemite Fall is the hard one, about seven miles round trip and nearly twenty seven hundred feet of climbing, so start early and bring plenty of water. Check current trail closures before you go, since rockfall and ice can shut sections down.
```

### `glm-5.3-flash` — reasoning_effort = low

- TTFT: 4.19 s; first sentence: 4.25 s; full response: 7.59 s; characters: 673; reasoning characters: 307; HTTP 200
- Provider usage: ``{"prompt_tokens":316,"completion_tokens":232,"total_tokens":548,"prompt_tokens_details":{"audio_tokens":0,"cached_tokens":0,"cache_write_tokens":0},"completion_tokens_details":{"accepted_prediction_tokens":0,"audio_tokens":0,"reasoning_tokens":90,"rejected_prediction_tokens":0}}``

```text
Here are three great picks.

Easy: Mirror Lake via the paved trail, about two miles round trip, with a nice view of Half Dome reflected in the water.

Intermediate: The Mist Trail to Vernal Fall, roughly two and a half miles round trip, or continue to Nevada Fall for about five and a half. Expect spray from the waterfalls and lots of stairs.

Challenging: Four Mile Trail up to Glacier Point, about nine and a half miles round trip, climbing nearly three thousand feet with sweeping valley views the whole way.

Go early for parking, bring more water than you think you need, and check trail conditions, since the Mist Trail stairs can close in high water or icy weather.
```

### `qwen3.8-flash` — thinking.type = disabled

- TTFT: 1.19 s; first sentence: 1.78 s; full response: 4.43 s; characters: 703; reasoning characters: 0; HTTP 200
- Provider usage: ``{"input_tokens":331,"output_tokens":133,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}``

```text
Pick Mirror Lake for easy, Yosemite Falls to Glacier Point for intermediate, and the Four Mile Trail for challenging.

Mirror Lake is flat and scenic at just under two miles round trip. You get great reflections of Half Dome without any steep climbs. For intermediate effort, hike up Yosemite Falls to Glacier Point via the Panorama Trail section or combine it with a shuttle ride if you want variety, but actually, let's stick to standard recommendations. The Mist Trail to Vernal Fall is a solid intermediate choice at about three miles round trip with some elevation gain. For challenging, the Four Mile Trail gains over three thousand feet in four miles, offering iconic views but demanding stamina.
```

### `glm-5.3` — none (heavy reasoner)

- TTFT: 4.65 s; first sentence: 4.78 s; full response: 5.74 s; characters: 632; reasoning characters: 1783; HTTP 200
- Provider usage: ``{"prompt_tokens":316,"completion_tokens":536,"total_tokens":852,"prompt_tokens_details":{"audio_tokens":null,"cached_tokens":12},"completion_tokens_details":{"accepted_prediction_tokens":null,"audio_tokens":null,"reasoning_tokens":407,"rejected_prediction_tokens":null}}``

```text
Go with Mirror Lake, the Mist Trail to Vernal and Nevada Falls, and the Upper Yosemite Falls trail. Mirror Lake is an easy five mile loop, mostly flat, with beautiful cliff reflections in spring and early summer. The Mist Trail runs about five and a half miles round trip, climbing stone steps right beside two big waterfalls, so expect spray on the steps and a solid workout. Upper Yosemite Falls is seven point two miles round trip with nearly three thousand feet of gain, and you finish at a railing lookout above the falls themselves. Start early since trailhead parking fills fast, and carry more water than you think you need.
```

### `kimi-k3` — none (max variant only)

- TTFT: 4.70 s; first sentence: 4.70 s; full response: 5.10 s; characters: 617; reasoning characters: 0; HTTP 200
- Provider usage: ``{"prompt_tokens":402,"completion_tokens":913,"total_tokens":1315,"prompt_tokens_details":{"audio_tokens":0,"cached_tokens":0,"cache_write_tokens":0},"completion_tokens_details":{"audio_tokens":0,"reasoning_tokens":777}}``

```text
Easy: Mirror Lake. Intermediate: the Mist Trail to Vernal and Nevada Falls. Challenging: Upper Yosemite Fall.

Mirror Lake is a mostly flat two mile stroll with a gorgeous view of Half Dome reflected in the water. The Mist Trail runs about six and a half miles round trip with close to two thousand feet of climbing up granite steps beside two huge waterfalls, so expect to get wet from the spray. Upper Yosemite Fall is about seven and a half miles round trip with roughly twenty seven hundred feet of gain, ending at the top of the tallest waterfall in North America. Start that one early and carry plenty of water.
```

## Follow-up: can the slow rows be made fast?

The request text above is much harder than a trivial question, and several rows
reasoned before answering. A second probe the same day, same host and same
request text but **without** the voice system prompt, tried the thinking
parameters that are documented for the other families:

| Model | Parameter tried | HTTP | TTFT (s) | Full response (s) | Text chars | Reasoning chars | Note |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `space-bunny` | `reasoning_effort = none` | 400 | - | 0.47 | 0 | 0 | parameter refused |
| `space-bunny` | `thinking.type = disabled` | 400 | - | 0.59 | 0 | 0 | parameter refused |
| `step-5-preview` | `reasoning_effort = none` | 200 | - | 6.30 | 0 | 3825 | accepted but reasoning continued and no text arrived before the cap |
| `step-5-preview` | `thinking.type = disabled` | 200 | - | 6.77 | 0 | 4140 | same |
| `grok-4.7` | `reasoning.effort = none` | 400 | - | 1.63 | 0 | 0 | refused; `low` is the lowest accepted level |
| `kimi-k3` | `reasoning_effort = none` | 200 | **1.25** | 6.02 | 1833 | 0 | reasoning off works |
| `glm-5.3-flash` | `reasoning_effort = none` | 200 | **0.84** | 11.09 | 1546 | 31 | better than the 4.19 s `low` row above |

What this means for the live tier:

- TTFT here is **not a model constant**. `space-bunny` first-tokened at 1.30 s on
  the easy question and at 12.16 s on this one; `step-5-preview` went 1.0 s to
  7.67 s; `glm-5.3-flash` went 1.04 s at `reasoning_effort=low` on an easy
  question to 4.19 s on this one. Only a model that does not reason, or whose
  reasoning can be turned off, keeps a stable first token.
- `space-bunny`, `step-5-preview`, and `grok-4.7` have no working off switch
  through this gateway, so they cannot lead a spoken turn (R-0252).
- `glm-5.3-flash` with `reasoning_effort=none` and `kimi-k3` with
  `reasoning_effort=none` are the two rows this probe rescued.
- These probe rows ran without the voice system prompt, so they are not directly
  comparable with the table above; the reasoning split is what dominates TTFT.

## Harness run (2026-10-17)

The `com.voicechat.agent.eval.llm.VoiceLlmHarnessTest` harness reproduces the
request below through the **real adapter** (`scripts/run-llm-harness.ps1`), three
attempts per configuration, and reports the median. It is the same method as the
single-run table above, but repeated, and it is what caught the upstream
`glm-5.3-flash` change (that model now refuses every reasoning-off form and was
dropped from the live tier).

| Model | Thinking | Attempts | TTFT median (s) | First sentence median (s) | Full response median (s) | Characters | Result |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `claude-haiku-5-5` | no thinking field | 3 | 0.73 | 1.17 | 2.21 | 560 | completed |
| `gpt-6-luna` | `reasoning.effort=none` | 3 | 0.98 | 1.30 | 2.53 | 539 | completed |
| `gpt-5.6-luna` | `reasoning.effort=none` | 3 | 0.90 | 1.09 | 2.05 | 606 | completed |
| `minimax-m3` | no reasoning field | 3 | 0.90 | 1.14 | 2.44 | 643 | completed |
| `qwen3.8-flash` | `thinking.type=disabled` | 3 | 0.96 | 1.33 | 2.66 | 320 | completed |
| `deepseek-v4.1-flash` | `reasoning_effort=none` | 3 | 1.79 | 2.10 | 3.00 | 320 | completed |
| `gpt-6-luna` | `reasoning.effort=low` (deep tier) | 3 | 3.55 | 3.80 | 4.55 | 600 | completed |

The first answer from one attempt, verbatim, as the record writes it:

```text
Here are three good Yosemite hikes, all under ten miles. For an easy one, try the Mirror Lake loop, about two miles round trip on a paved road with gentle slopes. For intermediate, take the Vernal Fall footbridge and Mist Trail, roughly three miles round trip, with steep granite steps and spray near the waterfall. For challenging, the Upper Yosemite Fall trail is about seven miles round trip with a steady climb to a spectacular view above the valley. Bring water, wear sturdy shoes, and check trail conditions at the park visitor center before you set out.
```

Two facts about the method matter for reading the numbers:

- The harness measures the **adapter's** first assistant text delta, so a
  provider's reasoning channel is excluded by construction (R-0066) — this is the
  delay a spoken turn waits for.
- The `none` field is sent explicitly wherever the model was measured to accept
  it. `gpt-6-luna` with the field omitted reasoned ~650 tokens and took a 7.70 s
  median instead of 0.98 s, which is why "omit `none`" is wrong for this
  provider.

The generated record (with every attempt and answer) is written to
`build/llm-harness/` and is not committed; the file is reproduced by running the
harness script.

## Device run (Pixel 10, 2026-10-17)

The same harness through the same adapter, run **on the Pixel 10** by
`scripts/run-llm-harness-device.ps1` (instrumented
`VoiceLlmHarnessInstrumentedTest`), so the numbers include the phone's own network
path and ART runtime rather than the host's.

- Device: Pixel 10 (`frankel`), Android 17 / API 37, build
  `google/frankel/frankel:17/CP3A.261005.005/16271449:user/release-keys`, debug
  variant, run id `9d241415d5a84f6b8e11174ae3a41a2e`, three attempts per model.
- Conditions **sampled after the run**: Wi-Fi connected (5 GHz band),
  USB-powered at 80% battery, battery temperature 29.0 °C. Radio state, screen
  state, and thermal throttling were not sampled during the run.
- The credential came from the app's AndroidKeyStore-backed store (populated by
  the debug import of the file `scripts/push-credentials.sh` stages); no key was
  passed on the command line or written to a report.

| Model | Thinking | Attempts | TTFT median (s) | First sentence median (s) | Full response median (s) | Characters | Result |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `claude-haiku-5-5` | no thinking field | 3 | 1.11 | 1.23 | 2.54 | 928 | completed |
| `gpt-6-luna` | `reasoning.effort=low` (deep tier) | 3 | 1.15 | 1.52 | 2.54 | 548 | completed |
| `gpt-6-luna` | `reasoning.effort=none` | 3 | 1.16 | 1.58 | 2.97 | 630 | completed |
| `minimax-m3` | no reasoning field | 3 | 1.22 | 1.34 | 1.78 | 705 | completed |
| `qwen3.8-flash` | `thinking.type=disabled` | 3 | 1.32 | 1.73 | 2.87 | 324 | completed |
| `gpt-5.6-luna` | `reasoning.effort=none` | 3 | 1.41 | 1.67 | 3.04 | 703 | completed |
| `glm-5.3-flash` | `reasoning_effort=low` (no off switch) | 3 | 1.51 | 1.72 | 5.88 | 426 | completed |
| `deepseek-v4.1-flash` | `reasoning_effort=none` | 3 | 2.08 | 2.16 | 3.10 | 434 | completed |

What the device adds to the host numbers:

- **Every case still completed**, including the ones the host measured as slow:
  the adapter's reasoning/auth path works from the device unchanged.
- **First token is ~0.25-0.55 s later than the same case on the host**
  (0.73-0.98 s host against 1.11-1.58 s device for the six live models), which is
  the phone's network path and scheduling. The device remains inside the
  "first sentence spoken by about 1.2-1.7 s" budget the voice loop was designed
  around.
- **`gpt-6-luna` `low` is not reliably slow**: 3.42 s on one attempt, 1.00 s and
  1.15 s on the others, so a single deep-tier call can look as fast as the live
  tier. The median hides that; the per-attempt rows are in the record.
- Characters differ from the host run because the answers are regenerated;
  the timing comparison is between medians, not between answers.

`scripts/run-llm-harness-device.ps1` copies `summary.md` (this table) and
`record.md` (the same table plus every attempt's timings and the verbatim answers)
into `build/llm-harness-device/<runId>/`. Neither is committed.

## Voice system prompt used for every row

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

## Not covered

- One run per model: no p50/p95, no repeat, no cache-warm measurement.
- No on-device measurement: Android TTS time-to-first-audio and whether the
  responses sound natural are unmeasured (R-0253).
- No answer-accuracy judgement: the hike recommendations were not fact-checked
  against Yosemite National Park Service guidance.

