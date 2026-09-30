package com.voicechat.agent.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that the M22 DataStore-backed settings store persists a
 * validated selection and survives a restart proxy, and that the preferences file
 * holds no credential value.
 *
 * **Compiled only** on the host with `:app:assembleDebugAndroidTest`; device
 * testing is deferred, so it was not run for this milestone (see Tests.md).
 */
@RunWith(AndroidJUnit4::class)
class PreferencesSettingsStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aValidatedSelectionPersistsAndASecondStoreInstanceReadsIt() {
        val file = java.io.File(context.filesDir, "datastore/${PreferencesSettingsStore.PREFERENCES_NAME}.preferences_pb")
        val expected =
            VoiceSettings(
                llmProviderId = ProviderId("openai"),
                llmModelId = ModelId("gpt-instrumented"),
                ttsVoiceId = "voice-instrumented",
            )
        val store = PreferencesSettingsStore.create(context)

        runBlocking {
            store.save(expected)
            assertEquals(expected, store.observe().first())
        }

        // A second store instance over the same app-private file is a restart proxy.
        val restarted = PreferencesSettingsStore.create(context)
        val loaded = runBlocking { restarted.observe().first() }
        assertEquals(expected, loaded)

        assertFalse(
            "settings must never contain a credential",
            file.takeIf { it.isFile }?.readText()?.contains("sk-") ?: false,
        )
    }
}
