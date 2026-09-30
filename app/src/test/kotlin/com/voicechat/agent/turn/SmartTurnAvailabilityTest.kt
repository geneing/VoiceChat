package com.voicechat.agent.turn

import com.voicechat.agent.contracts.ModelAvailability
import com.voicechat.agent.contracts.ModelTask
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.fake.FakeContentHasher
import com.voicechat.agent.settings.SmartTurnState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * M10 availability mapping: the model-availability contract and the settings
 * Smart Turn state both reflect the real on-disk integrity state, so a missing or
 * corrupt model is never reported as ready.
 */
class SmartTurnAvailabilityTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val pinnedHash = "a".repeat(64)
    private val artifact =
        SmartTurnArtifact(
            modelName = "test-smart-turn",
            fileName = "test.onnx",
            downloadUrl = "https://example.invalid/test.onnx",
            revision = "rev-test",
            sizeBytes = 4,
            sha256 = pinnedHash,
            license = "BSD-2-Clause",
            accessedOn = "2026-09-29",
        )

    @Test
    fun theDescriptorIsATurnCompletionOnnxModel() {
        assertEquals(ModelTask.TURN_COMPLETION, SmartTurnCatalog.descriptor.task)
        assertEquals(com.voicechat.agent.contracts.ModelRuntime.ONNX_RUNTIME, SmartTurnCatalog.descriptor.runtime)
    }

    @Test
    fun anInstalledModelIsReadyAndAvailable() {
        val state = SmartTurnModelState.Installed(File("model.onnx"), 4)

        assertTrue(SmartTurnCatalog.availability(state) is ModelAvailability.Ready)
        assertEquals(SmartTurnState.Available, SmartTurnCatalog.settingsState(state))
    }

    @Test
    fun aMissingModelRequiresADownload() {
        assertTrue(SmartTurnCatalog.availability(SmartTurnModelState.Missing) is ModelAvailability.DownloadRequired)
        assertEquals(SmartTurnState.DownloadRequired, SmartTurnCatalog.settingsState(SmartTurnModelState.Missing))
    }

    @Test
    fun aCorruptModelIsUnavailableWithItsReason() {
        val state =
            SmartTurnModelState.Corrupt(
                reason = "checksum mismatch",
                error = VoiceAgentError(ErrorCode.MODEL_CORRUPT, "Smart Turn model checksum mismatch"),
            )

        assertTrue(SmartTurnCatalog.availability(state) is ModelAvailability.Unavailable)
        assertEquals(SmartTurnState.Unavailable("checksum mismatch"), SmartTurnCatalog.settingsState(state))
    }

    @Test
    fun theProviderOnlyReportsTurnCompletionAvailability() =
        runTest {
            val directory = temporaryFolder.newFolder("smart-turn")
            File(directory, artifact.fileName).writeBytes(ByteArray(4))
            val provider =
                SmartTurnAvailabilityProvider(
                    SmartTurnModelStore(directory, artifact, FakeContentHasher(pinnedHash)),
                )

            val completion = provider.observe(ModelTask.TURN_COMPLETION).first()
            assertEquals(1, completion.size)
            assertTrue(completion.single() is ModelAvailability.Ready)
            assertTrue(provider.observe(ModelTask.LANGUAGE_MODEL).first().isEmpty())
        }
}
