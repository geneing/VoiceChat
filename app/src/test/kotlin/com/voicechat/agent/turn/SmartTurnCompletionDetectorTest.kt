package com.voicechat.agent.turn

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.TurnCompletion
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.fake.FakeSmartTurnInferenceEngine
import com.voicechat.agent.fake.RecordingDiagnosticsSink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M10 detector contract, proven with a fake [SmartTurnInferenceEngine] so no
 * native ONNX runtime or real model is needed: probability → verdict mapping,
 * format/failure handling, and idempotent close.
 */
class SmartTurnCompletionDetectorTest {
    private val config = SmartTurnConfig(windowSamples = 8, completionThreshold = 0.5f)
    private val monoFrame = AudioFrame(AudioFormat.MONO_16_KHZ, ShortArray(8) { (it + 1).toShort() })

    private fun detector(
        engine: FakeSmartTurnInferenceEngine,
        diagnostics: RecordingDiagnosticsSink = RecordingDiagnosticsSink(),
    ) = SmartTurnCompletionDetector(engine, config, diagnostics)

    @Test
    fun aProbabilityAboveTheThresholdIsComplete() =
        runTest {
            val engine = FakeSmartTurnInferenceEngine(probability = 0.5f + 0.0001f)

            assertEquals(TurnCompletion.COMPLETE, detector(engine).evaluate(monoFrame))
        }

    @Test
    fun aProbabilityAtOrBelowTheThresholdIsIncomplete() =
        runTest {
            assertEquals(TurnCompletion.INCOMPLETE, detector(FakeSmartTurnInferenceEngine(0.5f)).evaluate(monoFrame))
            assertEquals(TurnCompletion.INCOMPLETE, detector(FakeSmartTurnInferenceEngine(0.0f)).evaluate(monoFrame))
        }

    @Test
    fun theEngineReceivesTheConfiguredWindowLengthAndRawSamples() =
        runTest {
            val engine = FakeSmartTurnInferenceEngine()

            detector(engine).evaluate(monoFrame)

            val input = engine.inputs.single()
            assertEquals(config.windowSamples, input.size)
            assertEquals(3f, input[2], 0f)
        }

    @Test
    fun anUnexpectedSampleRateIsUnavailableAndNeverRunsTheModel() =
        runTest {
            val engine = FakeSmartTurnInferenceEngine()
            val frame = AudioFrame(AudioFormat(sampleRateHz = 8_000, channelCount = 1), ShortArray(8))

            assertEquals(TurnCompletion.UNAVAILABLE, detector(engine).evaluate(frame))
            assertEquals(0, engine.inferenceCount)
        }

    @Test
    fun anInferenceFailureIsUnavailableNotAGuess() =
        runTest {
            val engine = FakeSmartTurnInferenceEngine(failure = IllegalStateException("native failure"))

            assertEquals(TurnCompletion.UNAVAILABLE, detector(engine).evaluate(monoFrame))
            assertEquals(1, engine.inferenceCount)
        }

    @Test
    fun aNonFiniteProbabilityIsUnavailable() =
        runTest {
            val engine = FakeSmartTurnInferenceEngine(probability = Float.NaN)

            assertEquals(TurnCompletion.UNAVAILABLE, detector(engine).evaluate(monoFrame))
        }

    @Test
    fun closeIsIdempotentAndAPostCloseEvaluateIsUnavailable() =
        runTest {
            val engine = FakeSmartTurnInferenceEngine()
            val detector = detector(engine)

            detector.close()
            detector.close()

            assertEquals(TurnCompletion.UNAVAILABLE, detector.evaluate(monoFrame))
            assertTrue(engine.closed)
            assertEquals(0, engine.inferenceCount)
        }

    @Test
    fun anInferenceRecordsTheProbabilityAndVerdictWithoutAudioContent() =
        runTest {
            val sink = RecordingDiagnosticsSink()
            val engine = FakeSmartTurnInferenceEngine(probability = 0.83f)

            detector(engine, sink).evaluate(monoFrame)

            val inferenceEvent = sink.events.single { it.attributes[DiagnosticAttribute.VAD_EVENT] == "SMART_TURN_INFERENCE" }
            assertEquals("0.83", inferenceEvent.attributes[DiagnosticAttribute.TURN_COMPLETION_PROBABILITY])
            assertEquals(TurnCompletion.COMPLETE.name, inferenceEvent.attributes[DiagnosticAttribute.VAD_REASON])
            assertFalse(
                "no diagnostic value may contain a sample value",
                inferenceEvent.attributes.values.any { it.contains("[") },
            )
        }
}
