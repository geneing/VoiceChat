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
import com.google.ai.edge.litert.CompiledModel
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

/**
 * Debug-only GPU inference spike using the pinned Google LiteRT ASR sample path.
 *
 * It runs one offline Moonshine Tiny window with a GPU-only accelerator
 * allowlist and no CPU entry, for both the FP16-weight and FP32 artifacts. The
 * LiteRT 2.1.5 Java surface exposes no per-op placement query, so a completed
 * run is recorded as placement-UNKNOWN, never as confirmed GPU execution. An
 * accelerator that is present but cannot compile the graph is recorded as a
 * failed lane, not as a fallback success. `availableAccelerators` is the
 * runtime's own advertisement, not proof of execution.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtSpeechGpuInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun attemptsFp16AndFp32GpuWindowsWhenInputsAreStaged() {
        val audio = File(context.filesDir, AUDIO_RELATIVE_PATH)
        val candidates =
            listOf(
                Candidate(
                    label = "moonshine-tiny-fp16-weight",
                    relativePath = FP16_RELATIVE_PATH,
                    expectedBytes = FP16_MODEL_BYTES,
                    expectedSha256 = FP16_MODEL_SHA256,
                    artifactPrecision = "FP16_FLOAT_CASTING_WEIGHTS_FP32_COMPUTE",
                    requestedGpuPrecision = CompiledModel.GpuOptions.Precision.FP16,
                ),
                Candidate(
                    label = "moonshine-tiny-fp32",
                    relativePath = FP32_RELATIVE_PATH,
                    expectedBytes = FP32_MODEL_BYTES,
                    expectedSha256 = FP32_MODEL_SHA256,
                    artifactPrecision = "FP32_QUANT_NONE",
                    requestedGpuPrecision = CompiledModel.GpuOptions.Precision.FP32,
                ),
            )

        val missing = candidates.filterNot { File(context.filesDir, it.relativePath).isFile }
        if (missing.isNotEmpty() || !audio.isFile) {
            emit(
                candidate = "none",
                outcome = "INPUTS_NOT_STAGED",
                observedBackend = "UNKNOWN",
                extra =
                    JSONObject()
                        .put("missingModels", missing.map { it.relativePath })
                        .put("audioPresent", audio.isFile)
                        .put("stageInstructions", "scripts/stage-litert-asr-inputs.sh"),
            )
            return
        }

        val thermalBefore = thermalStatus()
        val batteryBefore = batterySnapshot()
        for (candidate in candidates) {
            attempt(candidate, audio)
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
        audio: File,
    ) {
        val model = File(context.filesDir, candidate.relativePath)
        val modelSha256 = sha256(model)
        if (model.length() != candidate.expectedBytes || modelSha256 != candidate.expectedSha256) {
            emit(
                candidate = candidate.label,
                outcome = "ARTIFACT_IDENTITY_MISMATCH",
                observedBackend = "UNKNOWN",
                extra = JSONObject().put("modelBytes", model.length()).put("modelSha256", modelSha256),
            )
            return
        }

        var runner: SampleMoonshineRunner? = null
        try {
            val loadStart = SystemClock.elapsedRealtimeNanos()
            runner =
                SampleMoonshineRunner(
                    modelFile = model,
                    accelerators = setOf(Accelerator.GPU),
                    gpuOptions =
                        CompiledModel.GpuOptions(
                            precision = candidate.requestedGpuPrecision,
                        ),
                )
            val modelLoadMs = elapsedMs(loadStart)
            val available = runner.availableAccelerators.map { it.name }.sorted()

            val preprocessStart = SystemClock.elapsedRealtimeNanos()
            val window =
                FileInputStream(audio).use { input ->
                    SamplePcmFileAudioSource(
                        inputStream = input,
                        samplingRate = SAMPLE_RATE,
                        interval = WINDOW_SECONDS.seconds,
                        overlap = FILE_OVERLAP_SECONDS.seconds,
                    ).windows().firstOrNull()
                } ?: throw AssertionError("Staged WAV contained no PCM samples")
            val audioReadPreprocessMs = elapsedMs(preprocessStart)

            val encodeStart = SystemClock.elapsedRealtimeNanos()
            val encodedOutputs = runner.encode(window)
            val encodeMs = elapsedMs(encodeStart)

            val decodeStart = SystemClock.elapsedRealtimeNanos()
            val decoded = runner.decode(encodedOutputs).iterator()
            var firstTokenAfterDecodeStartMs: Long? = null
            var tokenCount = 0
            var sawEndOfSequence = false
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
            }
            val decoderTotalMs = elapsedMs(decodeStart)
            assertTrue("sample decoder did not terminate", sawEndOfSequence)

            emit(
                candidate = candidate.label,
                outcome = "INFERENCE_COMPLETED_PLACEMENT_UNCONFIRMED",
                observedBackend = "UNKNOWN",
                extra =
                    JSONObject()
                        .put("modelBytes", model.length())
                        .put("modelSha256", modelSha256)
                        .put("audioSha256", sha256(audio))
                        .put("windowSamples", window.size)
                        .put("windowDurationMs", WINDOW_MILLIS)
                        .put("audioReadPreprocessMs", audioReadPreprocessMs)
                        .put("coldModelLoadAndBufferSetupMs", modelLoadMs)
                        .put("encodeMs", encodeMs)
                        .put("decoderFirstTokenMs", firstTokenAfterDecodeStartMs ?: JSONObject.NULL)
                        .put("decoderTotalMs", decoderTotalMs)
                        .put("encodePlusDecodeMs", encodeMs + decoderTotalMs)
                        .put("decoderTokenCount", tokenCount)
                        .put("convertedArtifactPrecision", candidate.artifactPrecision)
                        .put("requestedGpuPrecision", candidate.requestedGpuPrecision.name)
                        .put("availableAccelerators", available)
                        .put("runtimeComputePrecision", "UNKNOWN_NOT_EXPOSED")
                        .put("accuracy", "NOT_MEASURED"),
            )
        } catch (failure: Throwable) {
            emit(
                candidate = candidate.label,
                outcome = "INFERENCE_FAILED",
                observedBackend = "UNKNOWN",
                extra =
                    JSONObject()
                        .put("convertedArtifactPrecision", candidate.artifactPrecision)
                        .put("requestedGpuPrecision", candidate.requestedGpuPrecision.name)
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
        observedBackend: String,
        extra: JSONObject,
    ) {
        Log.i(
            TAG,
            JSONObject()
                .put("record", "litert_asr_gpu_spike")
                .put("outcome", outcome)
                .put("candidate", candidate)
                .put("modelId", MODEL_ID)
                .put("runtime", LITERT_RUNTIME)
                .put("requestedBackend", "GPU")
                .put("observedBackend", observedBackend)
                .put("backendEvidence", "GPU_ONLY_ALLOWLIST_NO_PER_OP_PLACEMENT_API")
                .put("fallbackAllowed", false)
                .put("extra", extra)
                .toString(),
        )
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
        val artifactPrecision: String,
        val requestedGpuPrecision: CompiledModel.GpuOptions.Precision,
    )

    private companion object {
        const val TAG = "LiteRtSpeechGpuTest"
        const val MODEL_ID = "UsefulSensors/moonshine-tiny"
        const val LITERT_RUNTIME = "com.google.ai.edge.litert:litert:2.1.5"
        const val FP16_RELATIVE_PATH = "litert-asr/moonshine-tiny-fp16-5s.tflite"
        const val FP32_RELATIVE_PATH = "litert-asr/moonshine-tiny-fp32-5s.tflite"
        const val AUDIO_RELATIVE_PATH = "litert-asr/eng-000001-000081dd.wav"
        const val FP16_MODEL_BYTES = 55_271_072L
        const val FP16_MODEL_SHA256 = "59a3701c307eb0d6ec6d3dc40654b8de3a3c66d67647cd43c25f2aa941d39826"
        const val FP32_MODEL_BYTES = 109_330_616L
        const val FP32_MODEL_SHA256 = "c4f4735852d4a8de1003431c43a253376c4c91ee19713778a07bd0a446c7e930"
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SECONDS = 5
        const val WINDOW_MILLIS = WINDOW_SECONDS * 1_000
        const val FILE_OVERLAP_SECONDS = 2
        const val NANOS_PER_MILLI = 1_000_000L
        const val BUFFER_BYTES = 64 * 1024
        const val END_OF_SEQUENCE = -1
    }
}
