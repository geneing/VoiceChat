package com.voicechat.agent.eval.litert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtBenchmarkContractTest {
    @Test
    fun sampleNpuMatrixIsDeviceAndModelSpecific() {
        assertTrue(lane(LiteRtAsrModel.PARAKEET_TDT, LiteRtDeviceTarget.PIXEL_10).isListedBySample())
        assertTrue(lane(LiteRtAsrModel.PARAKEET_CTC, LiteRtDeviceTarget.GALAXY_S23).isListedBySample())
        assertTrue(lane(LiteRtAsrModel.PARAKEET_CTC, LiteRtDeviceTarget.GALAXY_S24).isListedBySample())
        assertFalse(lane(LiteRtAsrModel.PARAKEET_CTC, LiteRtDeviceTarget.PIXEL_10).isListedBySample())
        assertFalse(lane(LiteRtAsrModel.MOONSHINE, LiteRtDeviceTarget.PIXEL_10).isListedBySample())
        assertFalse(lane(LiteRtAsrModel.WHISPER_SMALL, LiteRtDeviceTarget.PIXEL_10).isListedBySample())
        assertEquals(
            LiteRtLaneClassification.EXTENSION_CANDIDATE,
            LiteRtLane(LiteRtAsrModel.WHISPER_SMALL, LiteRtBackend.CPU, LiteRtDeviceTarget.PIXEL_10)
                .classification(),
        )
        assertEquals(
            LiteRtLaneClassification.EXTENSION_CANDIDATE,
            LiteRtLane(LiteRtAsrModel.WHISPER_SMALL, LiteRtBackend.GPU, LiteRtDeviceTarget.PIXEL_10)
                .classification(),
        )
    }

    @Test
    fun noLaneCanBeReportedAsRunBeforeRuntimeIntegration() {
        assertEquals(
            LiteRtRunOutcome.NOT_IMPLEMENTED,
            lane(LiteRtAsrModel.PARAKEET_TDT, LiteRtDeviceTarget.PIXEL_10).initialOutcome(),
        )
        assertEquals(
            LiteRtRunOutcome.UNAVAILABLE,
            lane(LiteRtAsrModel.PARAKEET_CTC, LiteRtDeviceTarget.PIXEL_10).initialOutcome(),
        )
    }

    @Test
    fun confirmedPlacementRequiresRequestedBackendAndInvocationProfile() {
        val diagnostic =
            LiteRtExecutionDiagnostic(
                requestedBackend = LiteRtBackend.GPU,
                observedBackends = setOf(LiteRtBackend.GPU),
                placementEvidence = LiteRtPlacementEvidence.INVOCATION_PROFILE,
                outcome = LiteRtRunOutcome.CONFIRMED,
            )

        assertEquals(setOf(LiteRtBackend.GPU), diagnostic.observedBackends)
        assertInvalidDiagnostic(
            requestedBackend = LiteRtBackend.GPU,
            observedBackends = setOf(LiteRtBackend.CPU),
            evidence = LiteRtPlacementEvidence.INVOCATION_PROFILE,
            outcome = LiteRtRunOutcome.CONFIRMED,
        )
        assertInvalidDiagnostic(
            requestedBackend = LiteRtBackend.GPU,
            observedBackends = setOf(LiteRtBackend.GPU),
            evidence = LiteRtPlacementEvidence.NONE,
            outcome = LiteRtRunOutcome.CONFIRMED,
        )
    }

    @Test
    fun fallbackAndPartialDelegationHaveDistinctEvidenceRequirements() {
        LiteRtExecutionDiagnostic(
            requestedBackend = LiteRtBackend.GPU,
            observedBackends = setOf(LiteRtBackend.CPU),
            placementEvidence = LiteRtPlacementEvidence.INVOCATION_PROFILE,
            outcome = LiteRtRunOutcome.RUNTIME_FALLBACK,
        )
        LiteRtExecutionDiagnostic(
            requestedBackend = LiteRtBackend.GPU,
            observedBackends = setOf(LiteRtBackend.CPU, LiteRtBackend.GPU),
            placementEvidence = LiteRtPlacementEvidence.INVOCATION_PROFILE,
            outcome = LiteRtRunOutcome.PARTIAL_DELEGATION,
        )
        assertInvalidDiagnostic(
            requestedBackend = LiteRtBackend.GPU,
            observedBackends = setOf(LiteRtBackend.CPU, LiteRtBackend.GPU),
            evidence = LiteRtPlacementEvidence.INVOCATION_PROFILE,
            outcome = LiteRtRunOutcome.RUNTIME_FALLBACK,
        )
    }

    @Test
    fun csvKeepsMissingMeasurementsEmptyAndEscapesMetadata() {
        val record =
            LiteRtBenchmarkRecord(
                gitCommit = "a".repeat(40),
                changeDescription = "initial harness, no inference",
                deviceModel = "Pixel 10",
                soc = "Tensor G5",
                androidBuild = "build, with detail",
                model = LiteRtAsrModel.PARAKEET_TDT,
                artifactPrecision = LiteRtArtifactPrecision.FLOAT32,
                observedComputePrecision = null,
                modelSha256 = null,
                conversionRevision = null,
                litertRuntimeVersion = null,
                delegateVersion = null,
                fixtureId = "fixture-01",
                audioDurationMillis = 3_200L,
                runTemperature = LiteRtRunTemperature.COLD,
                repeatIndex = 1,
                thermalStatus = null,
                batteryTemperatureMilliC = null,
                powerSaveEnabled = null,
                accuracyReferenceSource = LiteRtAccuracyReferenceSource.NONE,
                accuracyReferenceStatus = LiteRtAccuracyReferenceStatus.NOT_AVAILABLE,
                accuracyReferenceSha256 = null,
                wer = null,
                cer = null,
                execution =
                    LiteRtExecutionDiagnostic(
                        requestedBackend = LiteRtBackend.NPU,
                        outcome = LiteRtRunOutcome.NOT_IMPLEMENTED,
                    ),
                stageDurationsNanos = mapOf(LiteRtStage.MODEL_LOAD to 150_000_000L),
            )

        val headerCells = LiteRtBenchmarkRecord.CSV_HEADER.split(',')
        val row = record.toCsvRow()

        assertTrue(
            row.startsWith(
                "${"a".repeat(40)},\"initial harness, no inference\",Pixel 10,Tensor G5,\"build, with detail\"",
            ),
        )
        assertEquals(headerCells.size, parseCsvCellCount(row))
        assertTrue(row.contains(",not_implemented,3200,150000000,"))
        assertFalse(row.contains("transcript"))

        val pseudoReferenceAgreement =
            record
                .copy(
                    accuracyReferenceSource = LiteRtAccuracyReferenceSource.OPENAI_GPT_4O_TRANSCRIBE,
                    accuracyReferenceStatus = LiteRtAccuracyReferenceStatus.UNREVIEWED_PSEUDO_REFERENCE,
                    accuracyReferenceSha256 = "b".repeat(64),
                    wer = 0.25,
                    cer = 0.125,
                ).toCsvRow()
        assertTrue(pseudoReferenceAgreement.contains("openai_gpt-4o-transcribe,unreviewed_pseudo_reference"))
        assertTrue(pseudoReferenceAgreement.contains(",0.25,0.125,"))
    }

    private fun lane(
        model: LiteRtAsrModel,
        device: LiteRtDeviceTarget,
    ) = LiteRtLane(model, LiteRtBackend.NPU, device)

    private fun assertInvalidDiagnostic(
        requestedBackend: LiteRtBackend,
        observedBackends: Set<LiteRtBackend>,
        evidence: LiteRtPlacementEvidence,
        outcome: LiteRtRunOutcome,
    ) {
        try {
            LiteRtExecutionDiagnostic(requestedBackend, observedBackends, evidence, outcome)
            throw AssertionError("Expected an invalid placement diagnostic")
        } catch (expected: IllegalArgumentException) {
            // Expected: the record must not overstate placement.
        }
    }

    /** Counts RFC 4180 cells, including commas inside quoted values. */
    private fun parseCsvCellCount(line: String): Int {
        var quoted = false
        var cells = 1
        var index = 0
        while (index < line.length) {
            when (line[index]) {
                '"' -> {
                    if (quoted && index + 1 < line.length && line[index + 1] == '"') index++ else quoted = !quoted
                }

                ',' -> {
                    if (!quoted) cells++
                }
            }
            index++
        }
        return cells
    }
}
