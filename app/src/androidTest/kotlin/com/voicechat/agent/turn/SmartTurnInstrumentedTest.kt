package com.voicechat.agent.turn

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicechat.agent.contracts.ModelAvailability
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.settings.SmartTurnState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device scaffold for M10 (see Tests.md).
 *
 * It (a) reports Smart Turn availability, (b) if the pinned model is installed in
 * the app-private directory, loads the real ONNX graph and asserts one inference
 * returns a finite probability in `[0, 1]`, and (c) proves a wrong-size file is a
 * typed unavailable, never a success.
 *
 * **No model is committed to the repository.** When the model is not installed the
 * test returns early (not `Assume`, which Gradle reports as an assumption
 * violation) and passes vacuously; it never fabricates availability. Real
 * load/inference/memory numbers are M25 work and are not claimed here.
 */
@RunWith(AndroidJUnit4::class)
class SmartTurnInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun store() = SmartTurnModelStore(File(context.filesDir, SmartTurnModelStore.DIRECTORY_NAME))

    @Test
    fun reportsAvailabilityAndInfersWhenTheModelIsInstalled() {
        runBlocking {
            val state = store().state()
            Log.i(TAG, "Smart Turn availability: $state")
            Log.i(TAG, "Smart Turn artifact: ${SmartTurnArtifact.PINNED.modelName} ${SmartTurnArtifact.PINNED.revision}")

            when (state) {
                is SmartTurnModelState.Installed -> {
                    val engine =
                        try {
                            OnnxSmartTurnEngine.load(state.file)
                        } catch (failure: Throwable) {
                            throw AssertionError("the installed Smart Turn model failed to load", failure)
                        }
                    try {
                        val silence = FloatArray(SmartTurnConfig.WINDOW_SAMPLES)
                        val probability = engine.probability(silence)
                        assertTrue("probability must be finite, was $probability", probability.isFinite())
                        assertTrue("probability must be in [0, 1], was $probability", probability in 0f..1f)
                        Log.i(TAG, "Smart Turn inference probability on silence: $probability")
                    } finally {
                        engine.close()
                    }
                }

                SmartTurnModelState.Missing -> {
                    assertTrue(
                        SmartTurnCatalog.availability(state) is ModelAvailability.DownloadRequired,
                    )
                    Log.i(TAG, "Smart Turn model not installed; device inference not exercised")
                }

                is SmartTurnModelState.Corrupt -> {
                    assertTrue(SmartTurnCatalog.availability(state) is ModelAvailability.Unavailable)
                    Log.i(TAG, "Smart Turn model is corrupt: ${state.reason}")
                }
            }
        }
    }

    @Test
    fun aWrongSizeFileIsATypedUnavailable() {
        runBlocking {
            val directory = File(context.cacheDir, "smart-turn-corrupt-test")
            directory.mkdirs()
            try {
                File(directory, SmartTurnArtifact.PINNED.fileName).writeBytes(ByteArray(16))
                val store = SmartTurnModelStore(directory, SmartTurnArtifact.PINNED)

                val state = store.state()

                assertTrue("a wrong-size file must be corrupt", state is SmartTurnModelState.Corrupt)
                assertEquals(ErrorCode.MODEL_CORRUPT, (state as SmartTurnModelState.Corrupt).error.code)
                assertTrue(SmartTurnCatalog.settingsState(state) is SmartTurnState.Unavailable)
                assertTrue(SmartTurnCatalog.availability(state) is ModelAvailability.Unavailable)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private companion object {
        const val TAG = "SmartTurnTest"
    }
}
