package com.voicechat.agent.stt.parakeet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Config parsing/validation for stream 1. The published `config.json` is
 * reproduced byte-for-byte as a constant so the parser is exercised against the
 * real 524-byte document (SHA-256 `db0d95f8575e6610f4f524c87d647d5bcd5a868ed5102044e5ccdf4352d5da4b`),
 * not a hand-written stand-in.
 */
class ParakeetEouConfigTest {
    @Test
    fun pinnedMatchesThePublishedConfigFields() {
        val config = ParakeetEouConfig.pinned()

        assertEquals(16_000, config.sampleRate)
        assertEquals(128, config.numMelBins)
        assertEquals(512, config.nFft)
        assertEquals(160, config.hopLength)
        assertEquals(400, config.winLength)
        assertEquals(0.97f, config.preEmphasis, 0f)
        assertEquals(512, config.encoderHidden)
        assertEquals(17, config.encoderLayers)
        assertEquals(8, config.subsamplingFactor)
        assertEquals(70, config.attentionContext)
        assertEquals(8, config.convCacheSize)
        assertEquals(640, config.decoderHidden)
        assertEquals(1, config.decoderLayers)
        assertEquals(1026, config.vocabSize)
        assertEquals(1026, config.blankTokenId)
        assertEquals(1024, config.eouTokenId)
        assertEquals(1025, config.eobTokenId)
        assertEquals(64, config.chunkMelFrames)
        assertEquals(4, config.outputMelFrames)
        assertEquals(9, config.preCacheSize)
    }

    @Test
    fun pinnedDerivesTheStreamingGeometry() {
        val config = ParakeetEouConfig.pinned()

        assertEquals(4 * 8 * 160, config.shiftSamples)
        assertEquals(5_120, config.shiftSamples)
        assertEquals(64 * 160, config.chunkSamples)
        assertEquals(10_240, config.chunkSamples)
        assertEquals(1027, config.logits)
    }

    @Test
    fun fromConfigJsonRoundTripsThePinnedDocument() {
        val parsed = ParakeetEouConfig.fromConfigJson(ParakeetEouConfig.PINNED_CONFIG_JSON)

        assertEquals(ParakeetEouConfig.pinned(), parsed)
    }

    @Test
    fun fromConfigJsonIgnoresUnknownMetadataKeys() {
        val withExtra =
            """
            {
              "modelName": "parakeet-eou-120m",
              "futureField": {"nested": [1, 2, 3], "flag": true, "nothing": null},
              "numMelBins": 128,
              "sampleRate": 16000,
              "nFFT": 512,
              "hopLength": 160,
              "winLength": 400,
              "preEmphasis": 0.97,
              "encoderHidden": 512,
              "encoderLayers": 17,
              "subsamplingFactor": 8,
              "attentionContext": 70,
              "convCacheSize": 8,
              "decoderHidden": 640,
              "decoderLayers": 1,
              "vocabSize": 1026,
              "blankTokenId": 1026,
              "eouTokenId": 1024,
              "eobTokenId": 1025,
              "streaming": {"chunkMs": 320, "melFrames": 64, "preCacheSize": 9, "outputFrames": 4}
            }
            """.trimIndent()

        assertEquals(ParakeetEouConfig.pinned(), ParakeetEouConfig.fromConfigJson(withExtra))
    }

    @Test
    fun validationRejectsNonPositiveDimensions() {
        assertFails { ParakeetEouConfig.pinned().copy(sampleRate = 0) }
        assertFails { ParakeetEouConfig.pinned().copy(numMelBins = 0) }
        assertFails { ParakeetEouConfig.pinned().copy(nFft = 0) }
        assertFails { ParakeetEouConfig.pinned().copy(hopLength = -1) }
        assertFails { ParakeetEouConfig.pinned().copy(winLength = 0) }
        assertFails { ParakeetEouConfig.pinned().copy(encoderLayers = 0) }
        assertFails { ParakeetEouConfig.pinned().copy(subsamplingFactor = 0) }
        assertFails { ParakeetEouConfig.pinned().copy(decoderHidden = 0) }
        assertFails { ParakeetEouConfig.pinned().copy(vocabSize = 0) }
        assertFails { ParakeetEouConfig.pinned().copy(chunkMelFrames = 0) }
        assertFails { ParakeetEouConfig.pinned().copy(outputMelFrames = 0) }
        assertFails { ParakeetEouConfig.pinned().copy(preCacheSize = 0) }
    }

    @Test
    fun validationRejectsWindowLongerThanTheFftFrame() {
        assertFails { ParakeetEouConfig.pinned().copy(winLength = 513) }
    }

    @Test
    fun validationRejectsSpecialTokensOutsideTheLogitWidth() {
        // logits = vocabSize + 1 = 1027, so id 1027 is out of range.
        assertFails { ParakeetEouConfig.pinned().copy(blankTokenId = 1027) }
        assertFails { ParakeetEouConfig.pinned().copy(eouTokenId = 1027) }
        assertFails { ParakeetEouConfig.pinned().copy(eobTokenId = 1027) }
        assertFails { ParakeetEouConfig.pinned().copy(eouTokenId = -1) }
    }

    @Test
    fun validationRejectsDuplicateSpecialTokenIds() {
        assertFails { ParakeetEouConfig.pinned().copy(eouTokenId = 1025) }
        assertFails { ParakeetEouConfig.pinned().copy(eouTokenId = 1026) }
        assertFails { ParakeetEouConfig.pinned().copy(eobTokenId = 1026) }
    }

    @Test
    fun fromConfigJsonRejectsMissingAndMisTypedFields() {
        val missing = ParakeetEouConfig.PINNED_CONFIG_JSON.replace("\"hopLength\": 160,", "")
        assertFails { ParakeetEouConfig.fromConfigJson(missing) }

        val misTyped = ParakeetEouConfig.PINNED_CONFIG_JSON.replace("\"hopLength\": 160,", "\"hopLength\": \"160\",")
        assertFails { ParakeetEouConfig.fromConfigJson(misTyped) }

        val truncated = ParakeetEouConfig.PINNED_CONFIG_JSON.replace("\"outputFrames\": 4", "\"outputFrames\": ")
        assertFails { ParakeetEouConfig.fromConfigJson(truncated) }
    }

    private fun assertFails(block: () -> Unit) {
        val threw =
            try {
                block()
                false
            } catch (expected: IllegalArgumentException) {
                true
            }
        assertTrue("expected IllegalArgumentException", threw)
    }
}
