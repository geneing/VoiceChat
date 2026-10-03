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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Debug-only Parakeet TDT vs CTC comparison on the Pixel 10 CPU.
 *
 * Both models are the official `litert-community` INT8 artifacts, run with a
 * CPU-only allowlist on one 5-second window. Input features are the pinned
 * Hugging Face processors' log-Mel, frozen to `.f32` on the host, so
 * preprocessing is excluded from device timing. Token IDs are emitted only; the
 * sample tokenizer JNI is not linked. This is latency, not WER.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtParakeetComparisonInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun comparesTdtAndCtcCpuLatencyWhenInputsAreStaged() {
        val cases =
            listOf(
                Case(
                    label = "parakeet-ctc-0.6b-5s-i8",
                    modelPath = "litert-asr/parakeet-ctc-0.6b-5s-i8.tflite",
                    modelBytes = 596_365_072L,
                    modelSha256 = "3d0799f625842fded4580c9b51d321667f0bd5f334cb325fb1b9051c6534e102",
                    featuresPath = "litert-asr/parakeet-ctc-features-5s.f32",
                    featuresSha256 = "647222dfb72e123aedbd8a4244079b779af94a4184d6aa3c63ab1ec506b8c460",
                    tokenizerPath = "litert-asr/parakeet-ctc-tokenizer.json",
                    mode = ParakeetRunner.Mode.CTC,
                ),
                Case(
                    label = "parakeet-tdt-0.6b-v3-5s-i8-stateful",
                    modelPath = "litert-asr/parakeet-tdt-0.6b-v3-5s-i8-stateful.tflite",
                    modelBytes = 614_261_072L,
                    modelSha256 = "334745b8bc7fd372b1c213516f0b6338bb827b1a2abb3e77ad35fe6fea5cd16b",
                    featuresPath = "litert-asr/parakeet-tdt-features-5s.f32",
                    featuresSha256 = "8434cd7f5cdfd562de7cb777b908ec07fa1307946b25fa7b095d13baeb1fd441",
                    tokenizerPath = "litert-asr/parakeet-tdt-tokenizer.json",
                    mode = ParakeetRunner.Mode.TDT,
                ),
            )

        val missing = cases.filterNot { File(context.filesDir, it.modelPath).isFile && File(context.filesDir, it.featuresPath).isFile }
        if (missing.isNotEmpty()) {
            Log.i(
                TAG,
                JSONObject()
                    .put("record", "litert_parakeet_comparison")
                    .put("outcome", "INPUTS_NOT_STAGED")
                    .put("missing", JSONArray(missing.map { it.label }))
                    .toString(),
            )
            return
        }

        val tokenizers = decodeTokenizers(cases)

        val thermalBefore = thermalStatus()
        val batteryBefore = batterySnapshot()
        for (case in cases) {
            runCase(case, tokenizers[case.label])
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
                .put("tokenizerJniLoaded", tokenizerJniLoaded)
                .toString(),
        )
    }

    /**
     * Loads the sample tokenizer JNI and builds one tokenizer per case family from
     * the staged `tokenizer.json`. A missing library or tokenizer is a logged
     * unavailability, not a failure: the timing rows still run, text is just
     * reported as unavailable.
     */
    private fun decodeTokenizers(cases: List<Case>): Map<String, LiteRtHuggingfaceTokenizer> {
        try {
            LiteRtHuggingfaceTokenizer.load()
            tokenizerJniLoaded = true
        } catch (failure: Throwable) {
            Log.i(
                TAG,
                JSONObject()
                    .put("record", "litert_parakeet_comparison")
                    .put("outcome", "TOKENIZER_JNI_UNAVAILABLE")
                    .put("failureType", failure.javaClass.name)
                    .put("failureMessage", failure.message ?: JSONObject.NULL)
                    .toString(),
            )
            return emptyMap()
        }
        val result = LinkedHashMap<String, LiteRtHuggingfaceTokenizer>()
        for (case in cases) {
            val tokenizerFile = File(context.filesDir, case.tokenizerPath)
            if (!tokenizerFile.isFile) {
                Log.i(
                    TAG,
                    JSONObject()
                        .put("record", "litert_parakeet_comparison")
                        .put("outcome", "TOKENIZER_INPUTS_NOT_STAGED")
                        .put("case", case.label)
                        .put("tokenizerPath", case.tokenizerPath)
                        .toString(),
                )
                continue
            }
            try {
                val tokenizer = LiteRtHuggingfaceTokenizer.fromJson(tokenizerFile.readText())
                result[case.label] = tokenizer
                tokenizerVocab[case.label] = tokenizer.vocabSize
            } catch (failure: Throwable) {
                Log.i(
                    TAG,
                    JSONObject()
                        .put("record", "litert_parakeet_comparison")
                        .put("outcome", "TOKENIZER_INIT_FAILED")
                        .put("case", case.label)
                        .put("failureType", failure.javaClass.name)
                        .put("failureMessage", failure.message ?: JSONObject.NULL)
                        .toString(),
                )
            }
        }
        return result
    }

    private fun runCase(
        case: Case,
        tokenizer: LiteRtHuggingfaceTokenizer?,
    ) {
        val model = File(context.filesDir, case.modelPath)
        val featuresFile = File(context.filesDir, case.featuresPath)
        val modelSha = sha256(model)
        if (model.length() != case.modelBytes || modelSha != case.modelSha256) {
            emit(case, "ARTIFACT_IDENTITY_MISMATCH", JSONObject().put("modelSha256", modelSha))
            return
        }
        val featuresSha = sha256(featuresFile)
        if (featuresSha != case.featuresSha256) {
            emit(case, "FEATURES_IDENTITY_MISMATCH", JSONObject().put("featuresSha256", featuresSha))
            return
        }

        var runner: ParakeetRunner? = null
        try {
            val loadStart = SystemClock.elapsedRealtimeNanos()
            runner = ParakeetRunner(model, case.mode)
            val constructMs = elapsedMs(loadStart)

            val features = readLittleEndianFloats(featuresFile)
            val result = runner.run(features)
            assertTrue("decoder produced no token", result.tokenIds.isNotEmpty())

            // Local, Git-ignored hypothesis sidecars: raw token IDs and, when the
            // tokenizer JNI is present, the decoded text. Neither is accuracy.
            val hypothesisFile = File(context.filesDir, "litert-asr/${case.label}-tokens.txt")
            hypothesisFile.writeText(result.tokenIds.joinToString(" "))
            val transcript =
                if (tokenizer != null) {
                    val decoded = tokenizer.decode(result.tokenIds)
                    File(context.filesDir, "litert-asr/${case.label}-transcript.txt").writeText(decoded)
                    decoded
                } else {
                    ""
                }

            emit(
                case,
                "INFERENCE_COMPLETED",
                JSONObject()
                    .put("modelSha256", modelSha)
                    .put("featuresSha256", featuresSha)
                    .put("modelConstructMs", constructMs)
                    .put("featureLoadMs", result.modelLoadMs)
                    .put("encodeMs", result.encodeMs)
                    .put("decodeMs", result.decodeMs)
                    .put("encodePlusDecodeMs", result.encodeMs + result.decodeMs)
                    .put("decodeIterations", result.decodeIterations)
                    .put("tokenCount", result.tokenIds.size)
                    .put("encodeOutputShape", JSONArray(result.encodeOutputShape))
                    .put("hypothesisFile", hypothesisFile.name)
                    .put("transcriptFile", if (transcript.isNotEmpty()) "${case.label}-transcript.txt" else JSONObject.NULL)
                    .put("tokenizerVocabSize", tokenizerVocab[case.label] ?: JSONObject.NULL)
                    // Decoded text is intentionally NOT logged: only its presence.
                    .put("hasTranscript", transcript.isNotEmpty())
                    .put("accuracy", "NOT_MEASURED"),
            )
        } catch (failure: Throwable) {
            emit(
                case,
                "INFERENCE_FAILED",
                JSONObject()
                    .put("failureType", failure.javaClass.name)
                    .put("failureMessage", failure.message ?: JSONObject.NULL),
            )
        } finally {
            runner?.close()
        }
    }

    private fun emit(
        case: Case,
        outcome: String,
        extra: JSONObject,
    ) {
        Log.i(
            TAG,
            JSONObject()
                .put("record", "litert_parakeet_comparison")
                .put("outcome", outcome)
                .put("case", case.label)
                .put("runtime", LITERT_RUNTIME)
                .put("requestedBackend", "CPU")
                .put("observedBackend", "CPU")
                .put("backendEvidence", "CPU_ONLY_COMPILED_MODEL_ALLOWLIST")
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
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperatureTenthsC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return JSONObject()
            .put("temperatureC", if (temperatureTenthsC >= 0) temperatureTenthsC / 10.0 else JSONObject.NULL)
            .put("plugged", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1)
            .put("powerSaveMode", context.getSystemService(PowerManager::class.java).isPowerSaveMode)
    }

    private data class Case(
        val label: String,
        val modelPath: String,
        val modelBytes: Long,
        val modelSha256: String,
        val featuresPath: String,
        val featuresSha256: String,
        val tokenizerPath: String,
        val mode: ParakeetRunner.Mode,
    )

    /** Set once per run; read back into the environment record. */
    private var tokenizerJniLoaded = false
    private val tokenizerVocab = LinkedHashMap<String, Int>()

    private companion object {
        const val TAG = "LiteRtParakeetTest"
        const val LITERT_RUNTIME = "com.google.ai.edge.litert:litert:2.1.5"
        const val NANOS_PER_MILLI = 1_000_000L
        const val BUFFER_BYTES = 64 * 1024
    }
}
