package com.voicechat.agent.tts.kokoro.phonemes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * The phonemizer's asset loading must survive the packager.
 *
 * The lexicon is committed gzipped (`us_gold.json.gz`) so the repository stays
 * small, but the Android packager stores a `.gz` asset **decompressed** under the
 * name without the suffix: the APK built for M28 contains `assets/kokoro/
 * us_gold.json`, not `us_gold.json.gz` (verified by listing the built APK). A
 * phonemizer that only knew how to read gzip would therefore construct on the
 * JVM — where the committed file really is gzipped — and fail on a device.
 *
 * These tests pin both forms through the same seam the app uses, so the failure
 * cannot come back silently. The end-to-end proof is still the device run
 * (R-0267).
 */
class KokoroPhonemeAssetsTest {
    @Test
    fun `lexicon parses whether the packaged asset is gzipped or plain`() {
        val sentence = "The weather in Yosemite will be clear this weekend."

        val fromCommittedGzip = phonemizer(plainLexiconAssets = false)
        val fromPackagedPlain = phonemizer(plainLexiconAssets = true)

        assertTrue("no phonemes from the gzipped form", fromCommittedGzip.phonemes(sentence).isNotEmpty())
        assertEquals(
            "the packaged (decompressed) lexicon changed the pronunciation",
            fromCommittedGzip.phonemes(sentence),
            fromPackagedPlain.phonemes(sentence),
        )
        assertEquals(
            "the packaged (decompressed) lexicon changed the chunk plan",
            fromCommittedGzip.chunks(sentence).map { it.phonemes },
            fromPackagedPlain.chunks(sentence).map { it.phonemes },
        )
    }

    private fun phonemizer(plainLexiconAssets: Boolean): DefaultKokoroPhonemizer =
        DefaultKokoroPhonemizer(
            KokoroPhonemeResources { path ->
                when {
                    // The packager renames `x.gz` to `x` and stores it decompressed,
                    // so an asset request for `x.gz` finds plain JSON. Simulate both
                    // the committed file and the packaged entry.
                    plainLexiconAssets && path.endsWith(".gz") -> ByteArrayInputStream(gunzip(File("src/main/assets/$path")))

                    else -> File("src/main/assets/$path").inputStream()
                }
            },
        )

    private fun gunzip(file: File): ByteArray = GZIPInputStream(file.inputStream()).use { it.readAllBytes() }
}
