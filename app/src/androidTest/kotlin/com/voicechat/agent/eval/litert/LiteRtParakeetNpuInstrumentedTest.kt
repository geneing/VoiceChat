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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Debug-only Parakeet TDT Tensor G5 NPU attempt on the Pixel 10.
 *
 * Uses the upstream AOT-compiled `_f32_stateful_Google_Tensor_G5.tflite`
 * artifact. It is F32, not the project's INT8 candidate, so a successful run
 * would be an F32 NPU result, never an INT8 one. The accelerator allowlist is
 * NPU-only with no CPU entry. LiteRT 2.1.5 exposes no per-op placement query, so
 * a completed run is recorded as placement-UNKNOWN. The dispatch library is only
 * present when `scripts/fetch-litert-npu-runtime.sh` has been run.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtParakeetNpuInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun attemptsTensorG5NpuWhenInputsAreStaged() {
        val model = File(context.filesDir, MODEL_PATH)
        val featuresFile = File(context.filesDir, FEATURES_PATH)
        val dispatch = File(context.applicationInfo.nativeLibraryDir, DISPATCH_LIBRARY)

        if (!dispatch.isFile) {
            emit(
                "DISPATCH_LIBRARY_MISSING",
                JSONObject()
                    .put("dispatchPath", dispatch.absolutePath)
                    .put("stageInstructions", "scripts/fetch-litert-npu-runtime.sh"),
            )
            return
        }
        if (!model.isFile || !featuresFile.isFile) {
            emit(
                "INPUTS_NOT_STAGED",
                JSONObject()
                    .put("modelPresent", model.isFile)
                    .put("featuresPresent", featuresFile.isFile),
            )
            return
        }

        val modelSha = sha256(model)
        if (model.length() != MODEL_BYTES || modelSha != MODEL_SHA256) {
            emit("ARTIFACT_IDENTITY_MISMATCH", JSONObject().put("modelBytes", model.length()).put("modelSha256", modelSha))
            return
        }
        val featuresSha = sha256(featuresFile)
        if (featuresSha != FEATURES_SHA256) {
            emit("FEATURES_IDENTITY_MISMATCH", JSONObject().put("featuresSha256", featuresSha))
            return
        }

        val thermalBefore = thermalStatus()
        val batteryBefore = batterySnapshot()
        var runner: ParakeetRunner? = null
        try {
            val loadStart = SystemClock.elapsedRealtimeNanos()
            runner =
                ParakeetRunner(
                    modelFile = model,
                    mode = ParakeetRunner.Mode.TDT,
                    accelerators = setOf(Accelerator.NPU),
                )
            val constructMs = elapsedMs(loadStart)
            val available = runner.availableAccelerators.map { it.name }.sorted()

            val features = readLittleEndianFloats(featuresFile)
            val result = runner.run(features)
            emit(
                "INFERENCE_COMPLETED_PLACEMENT_UNCONFIRMED",
                JSONObject()
                    .put("modelSha256", modelSha)
                    .put("featuresSha256", featuresSha)
                    .put("modelConstructMs", constructMs)
                    .put("availableAccelerators", available)
                    .put("encodeMs", result.encodeMs)
                    .put("decodeMs", result.decodeMs)
                    .put("encodePlusDecodeMs", result.encodeMs + result.decodeMs)
                    .put("decodeIterations", result.decodeIterations)
                    .put("tokenCount", result.tokenIds.size)
                    .put("encodeOutputShape", JSONArray(result.encodeOutputShape))
                    .put("convertedArtifactPrecision", "FP32_AOT_GOOGLE_TENSOR_G5_NOT_INT8")
                    .put("accuracy", "NOT_MEASURED"),
            )
        } catch (failure: Throwable) {
            emit(
                "INFERENCE_FAILED",
                JSONObject()
                    .put("convertedArtifactPrecision", "FP32_AOT_GOOGLE_TENSOR_G5_NOT_INT8")
                    .put("failureType", failure.javaClass.name)
                    .put("failureMessage", failure.message ?: JSONObject.NULL),
            )
        } finally {
            runner?.close()
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

    private fun emit(
        outcome: String,
        extra: JSONObject,
    ) {
        Log.i(
            TAG,
            JSONObject()
                .put("record", "litert_parakeet_npu")
                .put("outcome", outcome)
                .put("case", "parakeet-tdt-0.6b-v3-5s-f32-stateful-Google_Tensor_G5")
                .put("runtime", LITERT_RUNTIME)
                .put("dispatchLibrary", DISPATCH_LIBRARY)
                .put("requestedBackend", "NPU")
                .put("observedBackend", "UNKNOWN")
                .put("backendEvidence", "NPU_ONLY_ALLOWLIST_NO_PER_OP_PLACEMENT_API")
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
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperatureTenthsC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return JSONObject()
            .put("temperatureC", if (temperatureTenthsC >= 0) temperatureTenthsC / 10.0 else JSONObject.NULL)
            .put("plugged", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1)
            .put("powerSaveMode", context.getSystemService(PowerManager::class.java).isPowerSaveMode)
    }

    private companion object {
        const val TAG = "LiteRtParakeetNpuTest"
        const val LITERT_RUNTIME = "com.google.ai.edge.litert:litert:2.1.5"
        const val DISPATCH_LIBRARY = "libLiteRtDispatch_GoogleTensor.so"
        const val MODEL_PATH = "litert-asr/parakeet-tdt-0.6b-v3-5s-f32-stateful-Google_Tensor_G5.tflite"
        const val FEATURES_PATH = "litert-asr/parakeet-tdt-features-5s.f32"
        const val MODEL_BYTES = 1_292_830_512L
        const val MODEL_SHA256 = "5d720a785a90991eec74a898049c4a8e86f78c6fe5b6bba02aea0e59a2c10184"
        const val FEATURES_SHA256 = "8434cd7f5cdfd562de7cb777b908ec07fa1307946b25fa7b095d13baeb1fd441"
        const val NANOS_PER_MILLI = 1_000_000L
        const val BUFFER_BYTES = 64 * 1024
    }
}
