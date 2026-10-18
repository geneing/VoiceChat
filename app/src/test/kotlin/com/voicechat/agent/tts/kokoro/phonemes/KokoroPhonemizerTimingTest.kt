package com.voicechat.agent.tts.kokoro.phonemes

import com.voicechat.agent.tts.normalization.TextNormalizer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Host measurement of the ported phonemizer's own cost.
 *
 * The M28 latency question is "text start to first sound", and on the device that
 * is phonemize + one graph call + the audio device. This test measures the first
 * of those three on the JVM with the **real** lexicon, POS model, and chunker, so
 * a device number has something to be compared against and a phonemizer
 * regression is visible without a device.
 *
 * It is deliberately a measurement, not a gate: the only assertion is that the
 * work produced phonemes, because a wall-clock threshold on shared CI hardware
 * would be a flaky test rather than a measurement. The device number, not this
 * one, is what the product is judged on (`docs/evaluation.md`).
 */
class KokoroPhonemizerTimingTest {
    @Test
    fun `phonemizes the harness sentences and reports the per-sentence cost`() {
        val phonemizer =
            DefaultKokoroPhonemizer.fromFiles(
                File("src/main/assets/kokoro/vocab.json"),
                File("src/main/assets/kokoro/us_gold.json.gz"),
                File("src/main/assets/kokoro/us_silver.json.gz"),
                File("src/main/assets/kokoro/en-pos-perceptron.bin"),
            )
        // Warm the lexicon and the POS model so the first sentence is not paying
        // for their one-time load; the engine does the same before it speaks.
        phonemizer.chunks("Warm the lexicon.")

        val rows =
            SENTENCES.map { text ->
                val started = System.nanoTime()
                val chunks = phonemizer.chunks(text)
                val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
                Row(text, chunks.size, chunks.sumOf { it.phonemes.length }, millis)
            }

        // The normalization stage on its own, so a rule that is slow to match is
        // visible separately from the lexicon and tagger work above.
        val normalizationRows =
            NORMALIZATION_SAMPLES.map { text ->
                val started = System.nanoTime()
                val normalized = TextNormalizer.English.normalize(text)
                val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
                normalized to millis
            }

        System.err.println("Host phonemizer cost (JVM, real lexicon + POS model):")
        rows.forEach { row ->
            System.err.println(
                "  chunks=${row.chunks} phonemes=${row.phonemes} chars=${row.text.length} " +
                    "phonemize=${row.millis}ms",
            )
        }
        val median = rows.map { it.millis }.sorted()[rows.size / 2]
        System.err.println("  median phonemize=${median}ms over ${rows.size} sentences")

        System.err.println("Host normalization cost (JVM, real rule pipeline):")
        normalizationRows.forEach { (normalized, millis) ->
            System.err.println("  normalize=${millis}ms -> $normalized")
        }

        assertTrue("the phonemizer produced no phonemes", rows.all { it.phonemes > 0 })
        assertTrue("normalization produced no text", normalizationRows.all { it.first.isNotBlank() })
    }

    private class Row(
        val text: String,
        val chunks: Int,
        val phonemes: Int,
        val millis: Long,
    )

    private companion object {
        const val NANOS_PER_MILLI: Long = 1_000_000L

        /** The same assistant-shaped texts the M25 TTS harness speaks. */
        val SENTENCES =
            listOf(
                "The weather in Yosemite will be clear this weekend.",
                "Take the Mist Trail to the top of Vernal Fall, then continue to Nevada Fall.",
                "Start early, because the parking lot fills by seven thirty in the morning on a summer weekend.",
            )

        /**
         * Forms whose rules do the most work, so the normalization stage is
         * measured on text that actually exercises it rather than on prose that
         * every rule skips.
         */
        val NORMALIZATION_SAMPLES =
            listOf(
                "Dr. Smith paid \$1,234.56 on 02/03/2007 at 9:30 a.m.",
                "1500% is 5 m and 3 in., and Chapter IV covers 1990-2000.",
                "Visit example.com/path or user@example.com, then 3/4 of NASA's 10 kg.",
            )
    }
}
