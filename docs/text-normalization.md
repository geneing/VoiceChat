# Text normalization for speech

How the app rewrites text into the form the distilled Kokoro voice pronounces
correctly, and the roadmap for extending it. It complements
[tts.md](./tts.md) (the engine) and [decisions.md](./decisions.md) §2.4/§2.5.

> Status: ported from the neighboring **EpubReader** project's
> `playback/normalization` package, which is the source of the rule catalogue,
> the tests, and most of the wording below. This app's copy lives in
> `app/src/main/kotlin/com/voicechat/agent/tts/normalization/`. It is pure Kotlin
> and JVM-testable; nothing in the package imports Android or a provider type.

## Where it runs

```
LLM reply text (streamed)
  └─ TurnOrchestrator chunker              sentence-sized chunks, persisted delivery
       └─ KokoroTtsEngine.speak(text)      one utterance
            └─ KokoroPhonemeChunker        sentence split, 510-phoneme cut
                 └─ TextNormalizer         <-- this document (per sentence)
                      └─ Misaki G2P port   phonemes
                           └─ Kokoro ONNX graph -> 24 kHz PCM -> AudioTrack
```

Normalization runs **after** sentence segmentation and **before** phonemization,
on the spoken string only. That placement is the whole reason the layer is a
plain `String -> String` pipeline:

- the text the app accounts for as **delivered** is unchanged, because the
  chunker carries the *original* sentence as
  [KokoroPhonemeChunk.sourceText](../app/src/main/kotlin/com/voicechat/agent/tts/kokoro/KokoroPhonemizer.kt)
  and only the phonemizer's input is rewritten. An interruption therefore still
  reports a true prefix of what the assistant said, even though `$1500` became
  "one thousand five hundred dollars" on the way to the graph;
- the phonemizer's **parity fixtures stay valid**, because they are generated
  from raw text. The port's fidelity to Misaki is asserted with
  `TextNormalizer.Identity`, and the spoken path is asserted separately
  ([kokoro-phonemes.md](./tts.md#kokoro-phonemization)).

## Principles

1. **Do no harm.** These are high-precision rules. An abbreviation is expanded
   only when it is effectively unambiguous; ambiguous short forms (`no.`, `in.`,
   `ft.`, `vol.`, `rev.` as "revolution") stay untouched until a safe context
   rule exists. A missed expansion sounds like "etc" (acceptable); a wrong one
   sounds like "Street John" (not acceptable).
2. **Segmentation first.** Normalize per sentence, never before sentence
   splitting. Expansion changes string length and would otherwise corrupt the
   delivered-text accounting.
3. **Pure and ordered.** Each rule is a small, deterministic
   [TextNormalizationRule](../app/src/main/kotlin/com/voicechat/agent/tts/normalization/TextNormalizationRule.kt)
   (`String -> String`). Order is explicit and significant. A rule claims
   source-shaped input and emits final spoken words; only a named downstream
   consumer may re-scan its intermediate output (`DigitGroupNormalizer` hands
   dotted versions to `DottedNumberNormalizer`). All other later rules must not
   treat emitted words as fresh source tokens or expand them again. The combined
   contract is idempotence: `normalize(normalize(text)) == normalize(text)`.
4. **Language-gated.** `TextNormalizer.forLanguageTag` returns the English
   pipeline for `en*` and for an absent tag, and a pass-through for any other
   language, so a voice this app cannot phonemize is never mis-pronounced by
   English rules.
5. **Provider-agnostic.** Nothing in the package imports the Kokoro engine, ONNX
   Runtime, or Android. It is unit-testable on the host JVM.

## Architecture

```
tts/normalization/
  TextNormalizationRule.kt   fun interface String -> String
  TextNormalizer.kt          ordered pipeline + forLanguageTag()
  NonProseNormalizer.kt      code, URLs, e-mail claimed first
  HygieneNormalizer.kt       entities, Unicode, quotes, dashes, hyphenation
  AbbreviationNormalizer.kt  abbreviation + St.-context rules
  NumberSpeller.kt           cardinal / year / decimal spelling
  NumberNormalizer.kt        numeral detection + dispatch
  ... one file per remaining rule (dates, currency, ordinals, ranges, units,
      fractions, digit groups, Roman numerals, symbols, lexicon)
```

To add a rule: implement `TextNormalizationRule`, append it to
`TextNormalizer.English` in the intended order, and add a focused test.

## Implemented now

### Hygiene

Decodes HTML entities, normalizes Unicode whitespace and zero-width/control
characters, converts smart quotes/dashes/ellipsis to ASCII, repairs line-break
hyphenation, and collapses repeated sentence punctuation. Runs first so the
numeric and symbol rules see clean ASCII.

### Abbreviations

Word-boundary, case-insensitive expansion of a curated, near-unambiguous list
distilled from authoritative TTS text-normalization sources (see
[References](#references)): honorifics and titles (`Mr.`, `Dr.`, `Prof.`,
`Rev.`, `Gen.`, `Sgt.`, `Jr.`, …), Latin and common forms (`etc.`, `vs.`,
`e.g.`, `i.e.`, `et al.`, `a.m.`, `p.m.`), business forms (`Inc.`, `Corp.`,
`Ltd.`, `Co.`, `Bros.`), months and days. Longer forms win (`Mrs.` over `Mr.`).
`St.` is the one context-sensitive entry: a following capitalised word means
Saint (`St. John`), a preceding word ending the token means Street (`Main St.`).

### Numbers

Comma-grouped thousands, decimals, four-digit years read in year style
(`1905` → "nineteen oh five"), negatives, phone numbers and digit runs read
digit by digit, and dotted versions. The caller selects a `YEAR` or `QUANTITY`
reading explicitly, so `1999` is "nineteen ninety-nine" but `$1500` and `1500 kg`
are "one thousand five hundred". Spelling follows CLDR
`spellout-numbering` conventions (hyphenated tens, no "and").

### Roman numerals

Expanded only with an explicit context signal — a title label immediately before
the numeral (`Chapter IV` → "Chapter four", `Vol. XII` → "Vol. twelve") or an
allowlisted regnal name (`Henry VIII` → "Henry the Eighth"). Isolated
Roman-shaped words stay untouched, so `I think`, `CD player`, `Vitamin C` and
`V for Vendetta` are unchanged.

### Dates, times and quantities

Month dates, numeric dates (day-before-month, with range checks), ordinals
(`21st` → "twenty-first"), decades (`1990s` → "nineteen nineties"), currency
(`$12.34` → "… and thirty-four cents"), percentages, ranges (`3-5` → "three to
five"), times (`09:05` → "nine oh five"), fractions (`3/4` → "three quarters"),
and units (`5 km`, `70 mph`, `20 °C`, `2 x 3`).

### Symbols, non-prose and lexicon

`&` → "and", `+` → "plus", `=` → "equals", `©`/`®`/`™`, `%`, `°`. Inline code,
URLs and e-mail addresses are spoken as letter sequences
(`user@example.com` → "u s e r at e x a m p l e dot c o m"); the scanner runs
first and claims host-shaped spans, while numeric lookalikes (`1,234.50`,
`1.2.3`, `02/03/2007`) and abbreviations (`a.m.`) stay available to their prose
rules. Known acronyms use a pronunciation (`NASA` → "Nasa"); unlisted vowel-less
all-caps words are spelled out.

## Relationship to the phonemizer's own handling

The ported Misaki lexicon already expands numbers, currency and ordinals, and
handles `%`/`&`/`+`/`@`. Normalizing first means the phonemizer usually receives
words rather than digits. Measured on the host (2026-10-17), with the pass-through
normalizer against the English pipeline:

| input | pass-through | English pipeline |
| --- | --- | --- |
| `$1500`, `1990s`, `50%`, `NASA`, `WHO` | already correct | **same** reading — the phonemizer's own expansion agrees |
| `1,234` | one malformed token | "one thousand two hundred thirty-four" |
| `3/4`, `3-5`, `9:30 a.m.`, `Chapter IV`, `Henry VIII` | letters or digits | "three quarters", "three to five", "nine thirty in the morning", "Chapter four", "Henry the Eighth" |
| `Dr. Smith`, `etc.`, `5 km`, `Main St.` | spoken as written | "Doctor Smith", "et cetera", "five kilometers", "Main Street" |
| `user@example.com` | one run-on token | "u s e r at e x a m p l e dot c o m" |
| `Feb. 13, 2007` | "feb thirteen two thousand seven" | "February thirteenth, two thousand seven" |

So the value is concentrated where the phonemizer has no rule at all — structured
numbers, units, abbreviations, dates, and non-prose spans — and the pipeline is
idempotent over all of them, so a form both layers understand is not expanded
twice. This is also why the parity fixtures must stay on raw text; see
[Where it runs](#where-it-runs).

One cosmetic artifact to know about: when an abbreviation ends a clause
(`3 in., and`), the expansion can leave the source period next to the existing
punctuation (`inches.,`). It has no audible effect — the phonemizer drops
punctuation outside the vocabulary — and the source project behaves the same way,
so the port keeps it rather than diverging.

## Planned rules

Remaining work, roughly by value. This is the source project's backlog, minus
what this app does not need.

- **Numbers and quantities:** scientific/large forms (`1.5e9`, `10^6`).
- **Symbols and punctuation:** `×`/`÷` between words, `§`, `¶`, footnote markers
  and stray brackets.
- **Text hygiene:** all-caps words beyond the small acronym list, possessives
  and trailing apostrophes.
- **User control:** a user lexicon / pronunciation substitutions
  (`LexiconNormalizer` is the host), and "silence this text" rules. A Settings
  editor is the intended extension point and does not exist yet.
- **Language:** per-language rule sets behind `forLanguageTag` (currently `en`).

## Cost

Measured on the host JVM (2026-10-17) by
`KokoroPhonemizerTimingTest`, on sentences built to exercise the rules rather than
prose that every rule skips: **1-3 ms per sentence**, against 3-5 ms for the
phonemizer's lexicon and tagger work on the same machine. It is a host
measurement, not a device claim; the device number is recorded with the rest of
the M28 latency run in [Tests.md](../Tests.md).

## Testing strategy

- Pure host JVM JUnit tests per rule and for the combined pipeline
  (`app/src/test/kotlin/com/voicechat/agent/tts/normalization/`); no Android or
  ICU dependency.
- Table-driven cases for number spellout (cardinals, years, decimals, negatives)
  and abbreviations, including near-misses that must **not** change.
- Contract tests: idempotence over a mixed corpus, the non-English pass-through,
  and overlap checks for structured-number, abbreviation, symbol, measurement
  and non-prose rule pairs.
- The engine seam is covered separately: the phonemizer's parity test runs with
  `TextNormalizer.Identity` (reference fidelity) and the spoken path is asserted
  with the English pipeline.

## Known limits

- **English only.** The pipeline is the source project's English rule set;
  `forLanguageTag` passes any other language through untouched. Only the English
  Kokoro voice is wired, so nothing is currently passed through in practice.
- **User lexicon.** `LexiconNormalizer` takes a user pronunciation map; there is
  no Settings editor for it yet. That is the intended extension point.
- **Book prose, not chat.** The rules were written for EPUB prose. An assistant
  reply can contain the same forms, but a false expansion on LLM output (a
  sentence that looks like an abbreviation or a Roman numeral) is the risk to
  watch, and it is recorded as R-0269.

## Provenance

Ported from the EpubReader project's `playback/normalization` package
(`com.geneing.epubreader.playback.normalization`), which documents the same
contract in its own `TEXT_NORMALIZATION.md`. The only API change is that
Readium's `Language` type is replaced by a BCP-47 tag
(`forLanguageTag(String?)`), so the package carries no book-reader dependency.
The rule tables and their citations are the source project's work; the
adaptation here is the seam described under [Where it runs](#where-it-runs).

## References

- Ossian text-normalisation abbreviation map — CSTR Edinburgh.
- MaryTTS `preprocess/abbrev.dat`.
- MUSE English abbreviations (Oxford-derived).
- Google Kestrel / WeTextProcessing whitespace whitelist.
- Tortoise-TTS `_abbreviations`, `expand_numbers`, `expand_abbreviations`.
- Unicode CLDR RBNF (`common/rbnf/en.xml`) for `spellout-numbering` and
  `spellout-numbering-year`.
- Sproat et al., "Hippocratic Abbreviation Expansion" — the high-precision,
  do-no-harm rationale for TTS abbreviation expansion.
