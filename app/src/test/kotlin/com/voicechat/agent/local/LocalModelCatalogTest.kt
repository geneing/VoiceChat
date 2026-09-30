package com.voicechat.agent.local

import com.voicechat.agent.contracts.ModelRuntime
import com.voicechat.agent.contracts.ModelTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M20 acceptance for the allow-listed local-model catalog.
 *
 * The catalog is deliberately empty; these tests prove both that an honest empty
 * catalog is a valid result and that a candidate missing required metadata or
 * carrying a bad checksum is refused rather than admitted.
 */
class LocalModelCatalogTest {
    @Test
    fun theShippedCatalogIsHonestlyEmpty() {
        val status = LocalModelCatalog.status
        assertTrue(status is LocalCatalogStatus.NoAllowListedModel)
        assertTrue((status as LocalCatalogStatus.NoAllowListedModel).reason.isNotBlank())
        assertTrue(LocalModelCatalog.entries().isEmpty())
        assertTrue(LocalModelCatalog.allowListed.isEmpty())
    }

    @Test
    fun aFullySpecifiedArtifactIsAdmitted() {
        val validation = LocalModelCatalogValidator.validate(sampleArtifact())

        assertTrue(validation is LocalCatalogValidation.Accepted)
        val model = (validation as LocalCatalogValidation.Accepted).model
        assertEquals("test-model", model.descriptor.id.value)
        assertEquals(ModelTask.LANGUAGE_MODEL, model.descriptor.task)
        assertEquals(ModelRuntime.LITERT_LM, model.descriptor.runtime)
        assertEquals(LocalProviders.LITERT_LM, model.descriptor.providerId)
    }

    @Test
    fun missingRequiredMetadataIsRejectedWithTheFieldNames() {
        val validation =
            LocalModelCatalogValidator.validate(
                sampleArtifact(
                    publisher = null,
                    sourceUrl = "  ",
                    license = null,
                    runtimeVersion = null,
                    downloadBytes = 0,
                    storageBytes = null,
                    peakMemoryBytes = null,
                    minAndroidApi = null,
                ),
            )

        assertTrue(validation is LocalCatalogValidation.Rejected)
        val rejected = validation as LocalCatalogValidation.Rejected
        assertTrue("publisher" in rejected.missing)
        assertTrue("sourceUrl" in rejected.missing)
        assertTrue("license" in rejected.missing)
        assertTrue("runtimeVersion" in rejected.missing)
        assertTrue("downloadBytes" in rejected.missing)
        assertTrue("storageBytes" in rejected.missing)
        assertTrue("peakMemoryBytes" in rejected.missing)
        assertTrue("minAndroidApi" in rejected.missing)
        assertTrue(rejected.reason.contains("allow-list"))
    }

    @Test
    fun aWrongTaskOrRuntimeIsRejected() {
        val wrongTask = LocalModelCatalogValidator.validate(sampleArtifact(task = ModelTask.SPEECH_TO_TEXT))
        val wrongRuntime = LocalModelCatalogValidator.validate(sampleArtifact(runtime = ModelRuntime.REMOTE_API))

        assertTrue((wrongTask as LocalCatalogValidation.Rejected).missing.any { it.startsWith("task") })
        assertTrue((wrongRuntime as LocalCatalogValidation.Rejected).missing.any { it.startsWith("runtime") })
    }

    @Test
    fun aMalformedChecksumIsRejected() {
        listOf(
            "deadbeef", // too short
            "Z".repeat(64), // non-hex
            "a".repeat(63), // wrong length
            null,
        ).forEach { checksum ->
            val validation = LocalModelCatalogValidator.validate(sampleArtifact(sha256 = checksum))
            assertTrue("checksum=$checksum should be rejected", validation is LocalCatalogValidation.Rejected)
            assertTrue("sha256" in (validation as LocalCatalogValidation.Rejected).missing)
        }
    }

    @Test
    fun findReturnsNullForAnythingNotAllowListed() {
        assertNull(
            LocalModelCatalog.find(
                com.voicechat.agent.domain
                    .ModelId("not-a-model"),
            ),
        )
    }
}
