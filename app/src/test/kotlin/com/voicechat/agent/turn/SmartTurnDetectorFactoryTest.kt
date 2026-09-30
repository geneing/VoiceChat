package com.voicechat.agent.turn

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.fake.FakeContentHasher
import com.voicechat.agent.fake.FakeSmartTurnInferenceEngine
import com.voicechat.agent.fake.RecordingDiagnosticsSink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * M10 opt-in wiring: disabled means the detector is **never constructed** (the
 * engine is never opened and the VAD-only policy applies); enabled-but-missing or
 * enabled-but-corrupt is a typed unavailable, never a success-shaped fallback.
 */
class SmartTurnDetectorFactoryTest {
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

    private class CountingEngineFactory(
        private val engine: FakeSmartTurnInferenceEngine = FakeSmartTurnInferenceEngine(),
    ) : SmartTurnEngineFactory {
        var openCount: Int = 0
            private set

        override fun open(modelFile: File): SmartTurnInferenceEngine {
            openCount++
            return engine
        }
    }

    private fun store(
        directory: File,
        hash: String? = pinnedHash,
    ) = SmartTurnModelStore(directory, artifact, FakeContentHasher(hash))

    @Test
    fun whenDisabledTheDetectorIsNeverConstructed() =
        runTest {
            val directory = temporaryFolder.newFolder("installed")
            File(directory, artifact.fileName).writeBytes(ByteArray(4))
            val engineFactory = CountingEngineFactory()
            val factory =
                SmartTurnDetectorFactory(
                    store = store(directory),
                    enabled = { false },
                    engineFactory = engineFactory,
                )

            assertNull(factory.detector())
            assertEquals(0, engineFactory.openCount)
        }

    @Test
    fun whenEnabledButMissingTheDetectorIsNullAndTheModelIsNotOpened() =
        runTest {
            val engineFactory = CountingEngineFactory()
            val factory =
                SmartTurnDetectorFactory(
                    store = store(temporaryFolder.newFolder("missing")),
                    enabled = { true },
                    engineFactory = engineFactory,
                )

            assertNull(factory.detector())
            assertEquals(0, engineFactory.openCount)
        }

    @Test
    fun whenEnabledButCorruptTheDetectorIsNullAndAFailureIsRecorded() =
        runTest {
            val directory = temporaryFolder.newFolder("corrupt")
            File(directory, artifact.fileName).writeBytes(ByteArray(3))
            val engineFactory = CountingEngineFactory()
            val sink = RecordingDiagnosticsSink()
            val factory =
                SmartTurnDetectorFactory(
                    store = store(directory),
                    enabled = { true },
                    engineFactory = engineFactory,
                    diagnostics = sink,
                )

            assertNull(factory.detector())
            assertEquals(0, engineFactory.openCount)
            assertTrue(sink.events.any { it.outcome == DiagnosticOutcome.FAILED })
        }

    @Test
    fun whenEnabledAndVerifiedTheDetectorIsBuiltOnce() =
        runTest {
            val directory = temporaryFolder.newFolder("verified")
            File(directory, artifact.fileName).writeBytes(ByteArray(4))
            val engine = FakeSmartTurnInferenceEngine()
            val engineFactory = CountingEngineFactory(engine)
            val sink = RecordingDiagnosticsSink()
            val factory =
                SmartTurnDetectorFactory(
                    store = store(directory),
                    enabled = { true },
                    engineFactory = engineFactory,
                    diagnostics = sink,
                )

            val detector = factory.detector()

            assertNotNull(detector)
            assertEquals(1, engineFactory.openCount)
            assertTrue(sink.events.any { it.attributes[DiagnosticAttribute.VAD_EVENT] == "SMART_TURN_LOADED" })
        }

    @Test
    fun aModelThatFailsToLoadIsATypedUnavailable() =
        runTest {
            val directory = temporaryFolder.newFolder("load-failure")
            File(directory, artifact.fileName).writeBytes(ByteArray(4))
            val failing: SmartTurnEngineFactory = SmartTurnEngineFactory { error("cannot load graph") }
            val sink = RecordingDiagnosticsSink()
            val factory =
                SmartTurnDetectorFactory(
                    store = store(directory),
                    enabled = { true },
                    engineFactory = failing,
                    diagnostics = sink,
                )

            assertNull(factory.detector())
            assertTrue(sink.events.any { it.outcome == DiagnosticOutcome.FAILED })
        }
}
