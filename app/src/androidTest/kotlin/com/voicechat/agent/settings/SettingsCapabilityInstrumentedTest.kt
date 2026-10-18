package com.voicechat.agent.settings

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicechat.agent.ui.AndroidSettingsCapabilityProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app-boundary capability snapshot **on the device** (M28).
 *
 * `AndroidSettingsCapabilityProvider` is the one settings file the JVM tests
 * cannot reach: it reads the real asset manager, the real app-private model
 * directory, and the platform's speech engines. Its engine-choice list is what the
 * picker renders, so it is worth proving on hardware that
 *
 * - the distilled engine is available with **nothing pushed**, because the graph
 *   ships in the APK, and the Kokoro model state says so;
 * - every engine the app knows appears in the list, so the picker can never
 *   silently omit an engine the user might need;
 * - an available engine carries no reason and an unavailable one always carries
 *   one, so the picker can explain itself.
 */
@RunWith(AndroidJUnit4::class)
class SettingsCapabilityInstrumentedTest {
    @Test
    fun theSnapshotListsEveryEngineAndReportsTheBundledVoiceAsAvailable() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val capabilities = AndroidSettingsCapabilityProvider(context).snapshot()

            assertEquals(
                "the picker must be able to offer every engine this build knows",
                TtsEngineChoice.entries.toSet(),
                capabilities.ttsEngineChoices.map { it.choice }.toSet(),
            )

            val distilled = requireNotNull(capabilities.ttsEngineChoices.firstOrNull { it.choice == TtsEngineChoice.DISTILLED_KOKORO })
            if (capabilities.kokoro is KokoroState.Installed) {
                // The graph is bundled, so this is the state a fresh install must
                // report — no push, no download.
                assertTrue("an installed graph must make the distilled engine available", distilled.available)
                assertNull(distilled.reason)
                assertTrue(
                    "the distilled voice must be offered as a voice too",
                    capabilities.ttsVoices.any { it.id == KokoroVoiceIds.AF_HEART },
                )
            } else {
                // A build without the asset directory (clean checkout) and without
                // an install: reported honestly, never as ready.
                assertTrue("an unavailable engine must explain itself", distilled.reason != null)
            }

            // An unavailable engine always says why; the platform engine's reason
            // is a device fact (no embedded voice for the locale), which a device
            // without one legitimately reports.
            capabilities.ttsEngineChoices.forEach { option ->
                if (option.available) {
                    assertNull("${option.choice} is available but carries a reason", option.reason)
                } else {
                    assertNotNull("${option.choice} is unavailable without a reason", option.reason)
                }
            }
        }

    @Test
    fun theBundledGraphIsReadFromTheApkNotFromAppPrivateStorage() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val capabilities = AndroidSettingsCapabilityProvider(context).snapshot()
            assumeTrue(
                "this build has no bundled or installed graph (device ${Build.MODEL})",
                capabilities.kokoro is KokoroState.Installed,
            )

            // The app-private copy is absent on a fresh install; the state comes
            // from the bundled asset, which is what makes the app speak offline.
            val privateFile = java.io.File(context.filesDir, "kokoro/af_heart/model_int8.onnx")
            assumeTrue("a graph was pushed to app-private storage; that path overrides the bundled one", !privateFile.exists())

            assertTrue(capabilities.ttsVoices.isNotEmpty())
        }

    private object KokoroVoiceIds {
        const val AF_HEART: String = "af_heart"
    }
}
