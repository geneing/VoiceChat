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
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

/**
 * Debug-only CPU inference spike using the pinned Google LiteRT ASR sample path.
 * It measures one offline Moonshine Tiny window; it is not a streaming or
 * transcript-accuracy test. Large inputs are staged under app-private files by
 * scripts/stage-litert-asr-inputs.sh and are never read from APK assets.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtSpeechCpuInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun loadsAndTimesOneSampleCompatibleCpuWindowWhenInputsAreStaged() {
        val model = File(context.filesDir, MODEL_RELATIVE_PATH)
        val audio = File(context.filesDir, AUDIO_RELATIVE_PATH)
        if (!model.isFile || !audio.isFile) {
            emit(
                outcome = "INPUTS_NOT_STAGED",
                observedBackend = "UNKNOWN",
                extra =
                    JSONObject()
                        .put("modelPresent", model.isFile)
                        .put("audioPresent", audio.isFile)
                        .put("stageInstructions", "scripts/stage-litert-asr-inputs.sh"),
            )
            return
        }

        val modelSha256 = sha256(model)
        if (model.length() != EXPECTED_MODEL_BYTES || modelSha256 != EXPECTED_MODEL_SHA256) {
            emit(
                outcome = "ARTIFACT_IDENTITY_MISMATCH",
                observedBackend = "UNKNOWN",
                extra =
                    JSONObject()
                        .put("modelBytes", model.length())
                        .put("modelSha256", modelSha256),
            )
            throw AssertionError("Staged LiteRT model does not match the converted artifact")
        }

        var runner: SampleMoonshineRunner? = null
        var observedBackend = "UNKNOWN"
        var outcome = "INFERENCE_FAILED"
        val thermalBefore = thermalStatus()
        val batteryBefore = batterySnapshot()
        try {
            val loadStart = SystemClock.elapsedRealtimeNanos()
            runner = SampleMoonshineRunner(model)
            val modelLoadMs = elapsedMs(loadStart)

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
            // The sample's Moonshine preprocessor is DummyAudioProcessor: raw PCM16
            // samples normalized to float are the model input, without log-Mel.
            val features = window
            val audioReadPreprocessMs = elapsedMs(preprocessStart)

            val encodeStart = SystemClock.elapsedRealtimeNanos()
            val encodedOutputs = runner.encode(features)
            val encodeMs = elapsedMs(encodeStart)
            // The CompiledModel was constructed with only Accelerator.CPU, so no
            // delegate fallback is possible. A completed encode confirms CPU use.
            observedBackend = "CPU"

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
            outcome = "INFERENCE_COMPLETED"

            emit(
                outcome = outcome,
                observedBackend = observedBackend,
                extra =
                    JSONObject()
                        .put("modelBytes", model.length())
                        .put("modelSha256", modelSha256)
                        .put("audioFile", audio.name)
                        .put("audioBytes", audio.length())
                        .put("audioSha256", sha256(audio))
                        .put("sampleRateHz", SAMPLE_RATE)
                        .put("windowSamples", window.size)
                        .put("windowDurationMs", WINDOW_MILLIS)
                        .put("audioReadPreprocessMs", audioReadPreprocessMs)
                        .put("coldModelLoadAndBufferSetupMs", modelLoadMs)
                        .put("encodeMs", encodeMs)
                        .put("decoderFirstTokenMs", firstTokenAfterDecodeStartMs ?: JSONObject.NULL)
                        .put("decoderTotalMs", decoderTotalMs)
                        .put("encodePlusDecodeMs", encodeMs + decoderTotalMs)
                        .put("decoderTokenCount", tokenCount)
                        .put("warmupCount", 0)
                        .put("realTimePacing", false)
                        .put("windowMerge", "NOT_APPLICABLE_SINGLE_WINDOW")
                        .put("firstTextFromClipStartMs", "UNAVAILABLE_UNPACED_FILE")
                        .put("transcript", "UNAVAILABLE_TOKENIZER_NOT_LINKED")
                        .put("accuracy", "NOT_MEASURED")
                        .put("convertedArtifactPrecision", "FP32_QUANT_NONE")
                        .put("runtimeComputePrecision", "CPU_DEFAULT_NOT_EXPOSED"),
            )
        } catch (failure: Throwable) {
            emit(
                outcome = outcome,
                observedBackend = observedBackend,
                extra = JSONObject().put("failureType", failure.javaClass.name),
            )
            throw failure
        } finally {
            runner?.close()
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
    }

    private fun emit(
        outcome: String,
        observedBackend: String,
        extra: JSONObject,
    ) {
        Log.i(
            TAG,
            JSONObject()
                .put("record", "litert_asr_cpu_spike")
                .put("outcome", outcome)
                .put("modelId", MODEL_ID)
                .put("runtime", LITERT_RUNTIME)
                .put("requestedBackend", "CPU")
                .put("observedBackend", observedBackend)
                .put("backendEvidence", "CPU_ONLY_COMPILED_MODEL_ALLOWLIST")
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

    private companion object {
        const val TAG = "LiteRtSpeechCpuTest"
        const val MODEL_ID = "UsefulSensors/moonshine-tiny"
        const val LITERT_RUNTIME = "com.google.ai.edge.litert:litert:2.1.5"
        const val MODEL_RELATIVE_PATH = "litert-asr/moonshine-tiny-fp32-5s.tflite"
        const val AUDIO_RELATIVE_PATH = "litert-asr/eng-000001-000081dd.wav"
        const val EXPECTED_MODEL_BYTES = 109_330_616L
        const val EXPECTED_MODEL_SHA256 = "c4f4735852d4a8de1003431c43a253376c4c91ee19713778a07bd0a446c7e930"
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SECONDS = 5
        const val WINDOW_MILLIS = WINDOW_SECONDS * 1_000
        const val FILE_OVERLAP_SECONDS = 2
        const val NANOS_PER_MILLI = 1_000_000L
        const val BUFFER_BYTES = 64 * 1024
        const val END_OF_SEQUENCE = -1
    }
}
