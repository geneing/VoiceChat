package com.voicechat.agent.eval.litert

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Debug-only Whisper graph-inference spike on the Pixel 10 CPU.
 *
 * Input features are extracted on the host with the pinned sample's own
 * `TransformersProcessor` (log-Mel `(1, 80, 3000)`) and frozen to
 * `whisper-features-30s.f32`; this is not the sample's on-device jlibrosa
 * front-end, so preprocessing time is excluded. The sample tokenizer JNI is not
 * linked, so no transcript or accuracy is produced. The autoregressive decode
 * graph reprocesses the whole 256-token sequence per step, so decoding is capped
 * and reported as a bounded step count, not a full decode.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtSpeechWhisperInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun timesWhisperConversionsOnCpuWhenInputsAreStaged() {
        val featuresFile = File(context.filesDir, FEATURES_RELATIVE_PATH)
        val candidates =
            listOf(
                Candidate(
                    label = "whisper-tiny-fp32-30s",
                    relativePath = "litert-asr/whisper-tiny-fp32-30s.tflite",
                    expectedBytes = TINY_BYTES,
                    expectedSha256 = TINY_SHA256,
                ),
                Candidate(
                    label = "whisper-small-fp32-30s",
                    relativePath = "litert-asr/whisper-small-fp32-30s.tflite",
                    expectedBytes = SMALL_BYTES,
                    expectedSha256 = SMALL_SHA256,
                ),
            )
        val missing = candidates.filterNot { File(context.filesDir, it.relativePath).isFile }
        if (missing.isNotEmpty() || !featuresFile.isFile) {
            emit(
                candidate = "none",
                outcome = "INPUTS_NOT_STAGED",
                extra =
                    JSONObject()
                        .put("missingModels", missing.map { it.relativePath })
                        .put("featuresPresent", featuresFile.isFile)
                        .put("stageInstructions", "STAGE_WHISPER=1 scripts/stage-litert-asr-inputs.sh"),
            )
            return
        }

        val features = readLittleEndianFloats(featuresFile)
        val thermalBefore = thermalStatus()
        val batteryBefore = batterySnapshot()
        for (candidate in candidates) {
            attempt(candidate, features, featuresFile)
        }
        Log.i(
            TAG,
            JSONObject()
                .put("record", "environment")
                .put("deviceManufacturer", Build.MANUFACTURER)
                .put("deviceModel", Build.MODEL)
                .put("device", Build.DEVICE)
                .put("socModel", Build.SOC_MODEL)
                .put("androidApi", Build.VERSION.SDK_INT)
                .put("buildId", Build.ID)
                .put("buildFingerprint", Build.FINGERPRINT)
                .put("thermalStatusBefore", thermalBefore)
                .put("thermalStatusAfter", thermalStatus())
                .put("batteryBefore", batteryBefore)
                .put("batteryAfter", batterySnapshot())
                .toString(),
        )
    }

    private fun attempt(
        candidate: Candidate,
        features: FloatArray,
        featuresFile: File,
    ) {
        val model = File(context.filesDir, candidate.relativePath)
        val modelSha256 = sha256(model)
        if (model.length() != candidate.expectedBytes || modelSha256 != candidate.expectedSha256) {
            emit(
                candidate = candidate.label,
                outcome = "ARTIFACT_IDENTITY_MISMATCH",
                extra = JSONObject().put("modelBytes", model.length()).put("modelSha256", modelSha256),
            )
            return
        }

        var runner: SampleAsrRunner? = null
        try {
            val loadStart = SystemClock.elapsedRealtimeNanos()
            runner =
                SampleAsrRunner(
                    modelFile = model,
                    accelerators = setOf(Accelerator.CPU),
                    decodeStartToken = WHISPER_START_TOKEN,
                    decodeStopToken = WHISPER_STOP_TOKEN,
                )
            val modelLoadMs = elapsedMs(loadStart)

            val encodeStart = SystemClock.elapsedRealtimeNanos()
            val encodedOutputs = runner.encode(features)
            val encodeMs = elapsedMs(encodeStart)

            val decodeStart = SystemClock.elapsedRealtimeNanos()
            val decoded = runner.decode(encodedOutputs).iterator()
            var firstTokenAfterDecodeStartMs: Long? = null
            var tokenCount = 0
            var sawEndOfSequence = false
            var capped = false
            while (decoded.hasNext()) {
                val token = decoded.next()
                if (token == END_OF_SEQUENCE) {
                    sawEndOfSequence = true
                    break
                }
                tokenCount += 1
                if (firstTokenAfterDecodeStartMs == null) {
                    firstTokenAfterDecodeStartMs = elapsedMs(decodeStart)
                }
                if (tokenCount >= MAX_DECODE_STEPS) {
                    capped = true
                    break
                }
            }
            val decoderTotalMs = elapsedMs(decodeStart)
            assertTrue("Whisper CPU decoder produced no step output", tokenCount > 0)

            emit(
                candidate = candidate.label,
                outcome = "INFERENCE_COMPLETED",
                extra =
                    JSONObject()
                        .put("modelBytes", model.length())
                        .put("modelSha256", modelSha256)
                        .put("featuresSha256", sha256(featuresFile))
                        .put("featuresSamples", features.size)
                        .put("coldModelLoadAndBufferSetupMs", modelLoadMs)
                        .put("encodeMs", encodeMs)
                        .put("decoderFirstTokenMs", firstTokenAfterDecodeStartMs ?: JSONObject.NULL)
                        .put("decoderTotalMs", decoderTotalMs)
                        .put("decoderSteps", tokenCount)
                        .put("decoderMsPerStep", if (tokenCount > 0) decoderTotalMs.toDouble() / tokenCount else JSONObject.NULL)
                        .put("decodeCappedAt", MAX_DECODE_STEPS)
                        .put("terminatedByStopToken", sawEndOfSequence)
                        .put("decodeCapped", capped)
                        .put("warmupCount", 0)
                        .put("realTimePacing", false)
                        .put("preprocessLocation", "HOST_SAMPLE_TRANSFORMERS_PROCESSOR_LOG_MEL")
                        .put("transcript", "UNAVAILABLE_TOKENIZER_NOT_LINKED")
                        .put("accuracy", "NOT_MEASURED")
                        .put("convertedArtifactPrecision", "FP32_QUANT_NONE"),
            )
        } catch (failure: Throwable) {
            emit(
                candidate = candidate.label,
                outcome = "INFERENCE_FAILED",
                extra =
                    JSONObject()
                        .put("failureType", failure.javaClass.name)
                        .put("failureMessage", failure.message ?: JSONObject.NULL),
            )
        } finally {
            runner?.close()
        }
    }

    private fun emit(
        candidate: String,
        outcome: String,
        extra: JSONObject,
    ) {
        Log.i(
            TAG,
            JSONObject()
                .put("record", "litert_asr_whisper_spike")
                .put("outcome", outcome)
                .put("candidate", candidate)
                .put("runtime", LITERT_RUNTIME)
                .put("requestedBackend", "CPU")
                .put("observedBackend", "CPU")
                .put("backendEvidence", "CPU_ONLY_COMPILED_MODEL_ALLOWLIST")
                .put("fallbackAllowed", false)
                .put("extra", extra)
                .toString(),
        )
    }

    private fun readLittleEndianFloats(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val result = FloatArray(buffer.remaining())
        buffer.get(result)
        return result
    }

    private fun elapsedMs(startNanos: Long) = (SystemClock.elapsedRealtimeNanos() - startNanos) / NANOS_PER_MILLI

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun thermalStatus(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(PowerManager::class.java).currentThermalStatus.toString()
        } else {
            "UNAVAILABLE"
        }

    private fun batterySnapshot(): JSONObject {
        val battery =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperatureTenthsC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return JSONObject()
            .put("temperatureC", if (temperatureTenthsC >= 0) temperatureTenthsC / 10.0 else JSONObject.NULL)
            .put("plugged", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1)
            .put("powerSaveMode", context.getSystemService(PowerManager::class.java).isPowerSaveMode)
    }

    private data class Candidate(
        val label: String,
        val relativePath: String,
        val expectedBytes: Long,
        val expectedSha256: String,
    )

    private companion object {
        const val TAG = "LiteRtSpeechWhisperTest"
        const val LITERT_RUNTIME = "com.google.ai.edge.litert:litert:2.1.5"
        const val FEATURES_RELATIVE_PATH = "litert-asr/whisper-features-30s.f32"
        const val TINY_BYTES = 151_149_520L
        const val TINY_SHA256 = "6e160e3b992a7af2adab32c0d4942aec417698b83d77e456339fe1c89fff50ed"
        const val SMALL_BYTES = 967_516_352L
        const val SMALL_SHA256 = "157f637fafbc6a2fef9e5c81c33d3a478d9100b7e176da10cdfac3a791ed1b4a"

        // From the sample's model_metadata.json for whisper-tiny.
        const val WHISPER_START_TOKEN = 50258
        const val WHISPER_STOP_TOKEN = 50257
        const val MAX_DECODE_STEPS = 16
        const val NANOS_PER_MILLI = 1_000_000L
        const val BUFFER_BYTES = 64 * 1024
        const val END_OF_SEQUENCE = -1
    }
}
