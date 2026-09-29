package com.voicechat.agent.replay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Broad coverage is generated, not committed: every required condition has a
 * repeatable variant built from the same base fixture with fixed seeds.
 */
class FixtureVariantCoverageTest {
    private val base = ReplayFixtures.syntheticPauseResume()

    private val requiredConditions =
        listOf(
            FixtureCondition.STREET_NOISE,
            FixtureCondition.CAR_NOISE,
            FixtureCondition.COMPETING_SPEECH,
            FixtureCondition.ECHO,
            FixtureCondition.REVERBERATION,
            FixtureCondition.GAIN,
            FixtureCondition.CLIPPING,
            FixtureCondition.COMPRESSION,
            FixtureCondition.CODEC_ARTIFACTS,
        )

    @Test
    fun everyRequiredConditionHasAByteRepeatableVariant() {
        requiredConditions.forEach { condition ->
            val variant = FixtureVariants.variant(base, condition)
            val rebuilt = FixtureVariants.variant(base, condition)

            assertEquals(condition.name.lowercase(), variant.manifest.id.substringAfterLast(':'))
            assertTrue(
                "condition ${condition.name} must record its transformation",
                variant.manifest.transformations.isNotEmpty(),
            )
            assertTrue(
                "condition ${condition.name} must change the samples",
                !base.samples.contentEquals(variant.samples),
            )
            assertArrayEquals(variant.samples, rebuilt.samples)
            assertEquals(variant.manifest, rebuilt.manifest)
        }
    }

    @Test
    fun theCleanVariantPreservesTheBaseSamples() {
        val clean = FixtureVariants.variant(base, FixtureCondition.CLEAN)

        assertArrayEquals(base.samples, clean.samples)
        assertTrue(clean.manifest.transformations.isEmpty())
    }

    @Test
    fun standardVariantsCoverEveryConditionExactlyOnce() {
        val variants = FixtureVariants.standardVariants(base)

        assertEquals(FixtureCondition.entries.size, variants.size)
        assertEquals(
            FixtureCondition.entries.map { it.name.lowercase() },
            variants.map { it.manifest.id.substringAfterLast(':') },
        )
    }
}
