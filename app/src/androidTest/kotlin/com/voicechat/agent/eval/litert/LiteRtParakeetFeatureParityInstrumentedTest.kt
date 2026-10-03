package com.voicechat.agent.eval.litert

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs

/** Compares both current TDT front ends with the SHA-pinned frozen feature control. */
@RunWith(AndroidJUnit4::class)
class LiteRtParakeetFeatureParityInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun reportsOnDeviceFeatureParityAgainstFrozenTdtFeatures() {
        val modelDir = File(context.filesDir, "litert-asr")
        val clip = File(modelDir, CLIP_NAME)
        val referenceFile = File(modelDir, FEATURES_NAME)
        assertTrue("Stage $CLIP_NAME and $FEATURES_NAME with scripts/stage-litert-asr-inputs.sh", clip.isFile)
        assertTrue("Stage $FEATURES_NAME with scripts/stage-litert-asr-inputs.sh", referenceFile.isFile)
        assertEquals(FEATURES_SHA256, sha256(referenceFile))

        val reference = readLittleEndianFloats(referenceFile)
        assertEquals(FRAME_COUNT * MEL_COUNT, reference.size)
        assertTrue(
            "frozen TDT frame 499 must be zero-padded (499 valid frames)",
            (0 until MEL_COUNT).all { mel -> reference[mel * FRAME_COUNT + FRAME_COUNT - 1] == 0f },
        )
        val audio = readParakeetPcmWindow(clip)
        assertEquals(FRAME_COUNT * HOP_LENGTH, audio.size)

        // Reproduce the pre-fix settings as an explicit diagnostic control. The
        // frozen tensor is mel-major; the old corpus config was frame-major and
        // used librosa's 512-sample periodic-Hann defaults.
        val oldConfig = LogMelConfig(nFft = 512, nMels = MEL_COUNT, nFrames = FRAME_COUNT, transpose = true, preemphasis = 0.97f)
        val oldPureKotlin = LogMelSpectrogram(oldConfig).process(audio)
        val oldPureKotlinMelMajor = LogMelSpectrogram(oldConfig.copy(transpose = false)).process(audio)
        val oldJlibrosa = sampleMelProcessor(transpose = true).process(audio)
        val oldJlibrosaMelMajor = sampleMelProcessor(transpose = false).process(audio)
        val referenceFrameMajor = transposeMelMajor(reference)

        emitComparison("baseline-pure-kotlin-flat-layout", oldPureKotlin, reference, coordinateDetails = false)
        emitComparison("baseline-jlibrosa-flat-layout", oldJlibrosa, reference, coordinateDetails = false)
        emitComparison("baseline-pure-kotlin-frame-major-aligned", oldPureKotlin, referenceFrameMajor)
        emitComparison("baseline-jlibrosa-frame-major-aligned", oldJlibrosa, referenceFrameMajor)
        emitComparison("baseline-pure-kotlin-mel-major", oldPureKotlinMelMajor, reference, melMajor = true)
        emitComparison("baseline-jlibrosa-mel-major", oldJlibrosaMelMajor, reference, melMajor = true)

        val corrected = LogMelSpectrogram(LogMelConfig.parakeetTdt()).process(audio)
        val correctedMaxAbs = emitComparison("corrected-pure-kotlin", corrected, reference, melMajor = true)
        assertTrue("HF Parakeet TDT preprocessing maxAbs=$correctedMaxAbs must be < 1e-3", correctedMaxAbs < 1e-3f)
    }

    private fun sampleMelProcessor(transpose: Boolean) =
        SampleMelProcessor(
            samplingRate = SAMPLE_RATE,
            nFft = 512,
            nMels = MEL_COUNT,
            hopLength = HOP_LENGTH,
            nFrames = FRAME_COUNT,
            transpose = transpose,
            preemphasis = 0.97f,
            normType = "standard",
        )

    private fun emitComparison(
        source: String,
        actual: FloatArray,
        reference: FloatArray,
        melMajor: Boolean = false,
        coordinateDetails: Boolean = true,
    ): Float {
        assertEquals("$source output element count", reference.size, actual.size)
        assertTrue("$source produced non-finite values", actual.all(Float::isFinite))

        val maxByMel = FloatArray(MEL_COUNT)
        val sumByMel = DoubleArray(MEL_COUNT)
        val worstFrameByMel = IntArray(MEL_COUNT)
        val maxByFrame = FloatArray(FRAME_COUNT)
        val sumByFrame = DoubleArray(FRAME_COUNT)
        val worstMelByFrame = IntArray(FRAME_COUNT)
        var globalMax = 0f
        var globalSum = 0.0
        var globalWorstIndex = 0

        for (index in actual.indices) {
            val difference = abs(actual[index] - reference[index])
            val frame = if (melMajor) index % FRAME_COUNT else index / MEL_COUNT
            val mel = if (melMajor) index / FRAME_COUNT else index % MEL_COUNT
            if (difference > globalMax) {
                globalMax = difference
                globalWorstIndex = index
            }
            globalSum += difference
            sumByMel[mel] += difference
            if (difference > maxByMel[mel]) {
                maxByMel[mel] = difference
                worstFrameByMel[mel] = frame
            }
            sumByFrame[frame] += difference
            if (difference > maxByFrame[frame]) {
                maxByFrame[frame] = difference
                worstMelByFrame[frame] = mel
            }
        }

        var edgeSum = 0.0
        var edgeCount = 0
        var interiorSum = 0.0
        var interiorCount = 0
        for (frame in 0 until FRAME_COUNT) {
            val sum = sumByFrame[frame]
            if (frame < EDGE_FRAMES || frame >= FRAME_COUNT - EDGE_FRAMES) {
                edgeSum += sum
                edgeCount += MEL_COUNT
            } else {
                interiorSum += sum
                interiorCount += MEL_COUNT
            }
        }

        val worstFrame = globalWorstIndex / MEL_COUNT
        val worstMel = globalWorstIndex % MEL_COUNT
        Log.i(
            TAG,
            JSONObject()
                .put("record", "parakeet_feature_parity")
                .put("source", source)
                .put("clip", CLIP_NAME)
                .put("referenceSha256", FEATURES_SHA256)
                .put("frames", FRAME_COUNT)
                .put("validFramesInReference", FRAME_COUNT - 1)
                .put("mels", MEL_COUNT)
                .put("coordinateComparison", if (coordinateDetails) "aligned" else "flat-index-only")
                .put("maxAbs", globalMax.toDouble())
                .put("meanAbs", globalSum / actual.size)
                .put("worstFrame", worstFrame)
                .put("worstMel", worstMel)
                .put("worstActual", actual[globalWorstIndex].toDouble())
                .put("worstReference", reference[globalWorstIndex].toDouble())
                .put("edgeFrames", "0-${EDGE_FRAMES - 1},${FRAME_COUNT - EDGE_FRAMES}-${FRAME_COUNT - 1}")
                .put("edgeMeanAbs", edgeSum / edgeCount)
                .put("interiorMeanAbs", interiorSum / interiorCount)
                .toString(),
        )

        if (!coordinateDetails) return globalMax

        for (start in 0 until MEL_COUNT step LOG_CHUNK_SIZE) {
            val rows = JSONArray()
            for (mel in start until minOf(start + LOG_CHUNK_SIZE, MEL_COUNT)) {
                rows.put(
                    JSONObject()
                        .put("mel", mel)
                        .put("maxAbs", maxByMel[mel].toDouble())
                        .put("meanAbs", sumByMel[mel] / FRAME_COUNT)
                        .put("worstFrame", worstFrameByMel[mel]),
                )
            }
            Log.i(
                TAG,
                JSONObject()
                    .put("record", "parakeet_feature_parity_mels")
                    .put("source", source)
                    .put("bins", rows)
                    .toString(),
            )
        }

        for (start in 0 until FRAME_COUNT step FRAME_LOG_CHUNK_SIZE) {
            val rows = JSONArray()
            for (frame in start until minOf(start + FRAME_LOG_CHUNK_SIZE, FRAME_COUNT)) {
                rows.put(
                    JSONObject()
                        .put("frame", frame)
                        .put("maxAbs", maxByFrame[frame].toDouble())
                        .put("meanAbs", sumByFrame[frame] / MEL_COUNT)
                        .put("worstMel", worstMelByFrame[frame])
                        .put("region", if (frame < EDGE_FRAMES || frame >= FRAME_COUNT - EDGE_FRAMES) "edge" else "interior"),
                )
            }
            Log.i(
                TAG,
                JSONObject()
                    .put("record", "parakeet_feature_parity_frames")
                    .put("source", source)
                    .put("frames", rows)
                    .toString(),
            )
        }
        return globalMax
    }

    private fun readLittleEndianFloats(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also(buffer::get)
    }

    /** Converts the frozen `[mel, frame]` reference into `[frame, mel]` for semantic comparison. */
    private fun transposeMelMajor(features: FloatArray): FloatArray =
        FloatArray(features.size) { index ->
            val frame = index / MEL_COUNT
            val mel = index % MEL_COUNT
            features[mel * FRAME_COUNT + frame]
        }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private companion object {
        const val TAG = "ParakeetFeatureParity"
        const val SAMPLE_RATE = 16_000
        const val HOP_LENGTH = 160
        const val MEL_COUNT = 128
        const val FRAME_COUNT = 500
        const val EDGE_FRAMES = 10
        const val LOG_CHUNK_SIZE = 16
        const val FRAME_LOG_CHUNK_SIZE = 10
        const val CLIP_NAME = "eng-000001-000081dd.wav"
        const val FEATURES_NAME = "parakeet-tdt-features-5s.f32"
        const val FEATURES_SHA256 = "8434cd7f5cdfd562de7cb777b908ec07fa1307946b25fa7b095d13baeb1fd441"
    }
}
