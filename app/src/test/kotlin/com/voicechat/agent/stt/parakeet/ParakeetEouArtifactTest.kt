package com.voicechat.agent.stt.parakeet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinned identity checks for the five-file Parakeet-EOU artifact: exact sizes,
 * SHA-256, URL construction, and init-time validation. Pure, no network.
 */
class ParakeetEouArtifactTest {
    private val pinned = ParakeetEouArtifact.PINNED

    @Test
    fun thePinnedSetHasTheFiveExpectedFiles() {
        assertEquals(
            listOf(
                "parakeet-eou-encoder.onnx",
                "parakeet-eou-decoder.onnx",
                "parakeet-eou-joint.onnx",
                "config.json",
                "vocab.json",
            ),
            pinned.files.map { it.fileName },
        )
        assertEquals("parakeet-eou-120m-onnx-int8", pinned.modelId.value)
        assertEquals("cc-by-4.0", pinned.license)
    }

    @Test
    fun thePinnedSizesAndHashesMatchTheContract() {
        assertEquals(131_741_896L, pinned.file("parakeet-eou-encoder.onnx")!!.sizeBytes)
        assertEquals(15_757_826L, pinned.file("parakeet-eou-decoder.onnx")!!.sizeBytes)
        assertEquals(5_589_132L, pinned.file("parakeet-eou-joint.onnx")!!.sizeBytes)
        assertEquals(524L, pinned.file("config.json")!!.sizeBytes)
        assertEquals(17_437L, pinned.file("vocab.json")!!.sizeBytes)

        assertEquals(
            "fcd0452f27a49d266a7b13a048f603798c66afe453838cd1ac685b30c1bae55c",
            pinned.file("parakeet-eou-encoder.onnx")!!.sha256,
        )
        assertEquals(
            "205cd7d44b5320f81d82dd6683e49e05ba4d72e0564151eb21a86e172f69f488",
            pinned.file("parakeet-eou-decoder.onnx")!!.sha256,
        )
        assertEquals(
            "6fb4b7674c2988ff8a4aa9c9181c6f06990d318a828e9696c4619505d2c08457",
            pinned.file("parakeet-eou-joint.onnx")!!.sha256,
        )
        assertEquals(
            "db0d95f8575e6610f4f524c87d647d5bcd5a868ed5102044e5ccdf4352d5da4b",
            pinned.file("config.json")!!.sha256,
        )
        assertEquals(
            "83fd42ad33dae1bd3ceee6c0bb6c625f314cf0b2dc8430be441ac1e2643d5c36",
            pinned.file("vocab.json")!!.sha256,
        )
    }

    @Test
    fun totalBytesIsTheSumOfTheFiveFiles() {
        assertEquals(131_741_896L + 15_757_826L + 5_589_132L + 524L + 17_437L, pinned.totalBytes)
    }

    @Test
    fun downloadUrlsUseTheRevisionPinnedBase() {
        assertEquals(
            "https://huggingface.co/soniqo/Parakeet-EOU-120M-ONNX-INT8/resolve/main/parakeet-eou-encoder.onnx",
            pinned.downloadUrl(pinned.file("parakeet-eou-encoder.onnx")!!),
        )
        assertEquals(
            "https://huggingface.co/soniqo/Parakeet-EOU-120M-ONNX-INT8/resolve/main/vocab.json",
            pinned.downloadUrl(pinned.file("vocab.json")!!),
        )
        assertTrue(pinned.baseUrl.startsWith("https://"))
        assertTrue(pinned.baseUrl.endsWith("/"))
    }

    @Test
    fun unknownFileNameIsNull() {
        assertNull(pinned.file("not-a-file.onnx"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun anUnknownFileCannotBeConstructedWithABadHash() {
        ParakeetEouFile(
            fileName = "x.onnx",
            sizeBytes = 4,
            sha256 = "short",
            role = ParakeetEouFileRole.ENCODER,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun anArtifactWithDuplicateFileNamesIsRejected() {
        val encoder = pinned.file("parakeet-eou-encoder.onnx")!!
        ParakeetEouArtifact(
            modelName = "dup",
            files = listOf(encoder, encoder),
            revision = "main",
            baseUrl = ParakeetEouArtifact.BASE_URL,
            license = "cc-by-4.0",
            accessedOn = "2026-10-03",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun anArtifactWithANonHttpsBaseUrlIsRejected() {
        ParakeetEouArtifact(
            modelName = "bad",
            files = listOf(pinned.file("config.json")!!),
            revision = "main",
            baseUrl = "http://example.invalid/",
            license = "cc-by-4.0",
            accessedOn = "2026-10-03",
        )
    }

    @Test
    fun thePinnedArtifactIsDistinctFromOtherFactories() {
        val copy =
            ParakeetEouArtifact(
                modelName = "copy",
                files = pinned.files,
                revision = pinned.revision,
                baseUrl = pinned.baseUrl,
                license = pinned.license,
                accessedOn = pinned.accessedOn,
            )
        assertNotEquals(pinned.modelId, copy.modelId)
        assertEquals(pinned.files, copy.files)
    }
}
