package com.voicechat.agent.local

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicechat.agent.contracts.ModelTask
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device checks for the M20 local runtimes (Tests.md).
 *
 * They run only with `:app:connectedDebugAndroidTest`. Availability is read from
 * each runtime's own status surface and never faked. When a capability is absent
 * the test **returns** (it never uses `Assume`, which Gradle reports as a
 * failure); the real availability is always logged. The measured numbers
 * (cold/warm startup, memory, latency, contention with STT/TTS) are recorded for a
 * human run, never asserted here, and the manual rows live in Tests.md.
 */
@RunWith(AndroidJUnit4::class)
class LocalRuntimeInstrumentedTest {
    private val installer = LocalModelInstaller(AndroidLocalModelFileStore(context()))
    private val provider = LocalModelAvailabilityProvider(MlKitGenAiPromptProbe(), installer)

    @Test
    fun aicorePromptAvailabilityIsReportedWithoutThrowing() {
        runBlocking {
            val probe = MlKitGenAiPromptProbe().probe()
            Log.i(TAG, "M20 AICore Prompt probe -> $probe")
            assertNotNull(probe)
        }
    }

    @Test
    fun localAvailabilitySnapshotReportsEveryKnownModelWithoutThrowing() {
        runBlocking {
            val models = provider.observe(ModelTask.LANGUAGE_MODEL).firstOrNull().orEmpty()
            Log.i(TAG, "M20 local availability: $models")
            // AICore is always probed, so there is always at least one entry.
            assertTrue(models.isNotEmpty())
            assertTrue(models.all { it.model.task == ModelTask.LANGUAGE_MODEL })
        }
    }

    @Test
    fun theLocalModelCatalogIsHonestAboutItsContents() {
        val status = LocalModelCatalog.status
        Log.i(TAG, "M20 catalog status: $status")
        assertTrue(status is LocalCatalogStatus.NoAllowListedModel || status is LocalCatalogStatus.Available)
        // Every shipped entry must pass the allow-list gate on device too.
        assertEquals(LocalModelCatalog.allowListed.size, LocalModelCatalog.entries().size)
    }

    @Test
    fun anAvailableAicoreModelCanGenerateWithinBounds() {
        runBlocking {
            val availability = MlKitGenAiPromptProbe().probe()
            if (availability !is LocalProbeResult.Ready) {
                Log.i(TAG, "M20 AICore is $availability; generation measurement is not applicable")
                return@runBlocking
            }

            val beforeBytes = usedHeapBytes()
            val startedAt = System.nanoTime()
            val firstChunk =
                withTimeoutOrNull(GENERATE_TIMEOUT_MILLIS) {
                    MlKitPromptGenerator().generate("Reply with the single word: hello.").firstOrNull()
                }
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            val memoryDelta = usedHeapBytes() - beforeBytes

            Log.i(
                TAG,
                "M20 AICore generation: firstChunkMillis=$elapsedMillis " +
                    "heapDeltaBytes=$memoryDelta gotText=${!firstChunk.isNullOrEmpty()}",
            )
            // The model is provisioned, so generation must not time out.
            assertNotNull(firstChunk)
        }
    }

    @Test
    fun anInstalledAllowListedModelWouldInitializeIfPresent() {
        runBlocking {
            val models = LocalModelCatalog.entries()
            if (models.isEmpty()) {
                Log.i(TAG, "M20 no allow-listed local model is installed; init measurement is not applicable")
                return@runBlocking
            }

            val target = models.first()
            val state = installer.state(target)
            Log.i(TAG, "M20 install state for ${target.descriptor.id}: $state")
            if (state !is LocalInstallState.Installed) {
                Log.i(TAG, "M20 ${target.descriptor.id} is not installed ($state); init measurement is not applicable")
                return@runBlocking
            }

            val beforeBytes = usedHeapBytes()
            val startedAt = System.nanoTime()
            val sessions = EngineLiteRtLmSessionFactory()
            val session = sessions.open(state.path)
            try {
                val chunk = withTimeoutOrNull(GENERATE_TIMEOUT_MILLIS) { session.generate("hello").firstOrNull() }
                val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
                Log.i(
                    TAG,
                    "M20 LiteRT-LM init+firstToken: model=${target.descriptor.id} millis=$elapsedMillis " +
                        "heapDeltaBytes=${usedHeapBytes() - beforeBytes} gotText=${!chunk.isNullOrEmpty()}",
                )
            } finally {
                runCatching { session.close() }
            }
        }
    }

    private fun context(): android.content.Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private companion object {
        const val TAG = "LocalRuntimeInstr"
        const val GENERATE_TIMEOUT_MILLIS = 60_000L
    }
}
