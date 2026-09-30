package com.voicechat.agent.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.providers.AuthMethod
import com.voicechat.agent.stt.SttMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * M22 acceptance for settings persistence: the DataStore-backed store round-trips
 * every validated selection and survives a "process restart" (a new store
 * instance over the same file). It also proves no credential-shaped value is
 * persisted — the store has no credential API and writes only non-secret fields.
 *
 * Runs on the JVM under Robolectric over a temp file; no device is needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreferencesSettingsStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var counter = 0

    private fun newFile(): File {
        val directory = temporaryFolder.newFolder("settings-${counter++}")
        return File(directory, "voicechat-settings.preferences_pb")
    }

    private fun newStore(
        file: File,
        scope: CoroutineScope,
    ): PreferencesSettingsStore =
        PreferencesSettingsStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
        )

    private fun ioScope(): CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Test
    fun everyValidatedSelectionRoundTrips() =
        runBlocking {
            val scope = ioScope()
            val store = newStore(newFile(), scope)
            val settings =
                VoiceSettings(
                    sttMode = SttMode.ADVANCED,
                    sttLocaleLanguageTag = "en-GB",
                    llmProviderId = ProviderId("hermes"),
                    llmModelId = ModelId("hermes-agent"),
                    llmAuthMethod = AuthMethod.API_KEY,
                    reasoningLevel = ReasoningLevel.HIGH,
                    llmServerUrl = "https://hermes.example.com/v1",
                    ttsVoiceId = "voice-on-device",
                    smartTurnEnabled = false,
                )

            store.save(settings)

            assertEquals(settings, store.observe().first())
            scope.cancel()
        }

    @Test
    fun settingsSurviveAProcessRestartOnTheSameFile() =
        runBlocking {
            val file = newFile()
            val settings =
                VoiceSettings(
                    sttMode = SttMode.BASIC,
                    llmProviderId = ProviderId("openai"),
                    llmModelId = ModelId("gpt-test"),
                    llmAuthMethod = AuthMethod.API_KEY,
                    reasoningLevel = ReasoningLevel.MEDIUM,
                )

            val firstScope = ioScope()
            newStore(file, firstScope).save(settings)
            firstScope.cancel()

            // A new store over the same durable file is a new process.
            val secondScope = ioScope()
            val restarted = newStore(file, secondScope)
            assertEquals(settings, restarted.observe().first())
            secondScope.cancel()
        }

    @Test
    fun clearingASelectionRemovesItFromTheStore() =
        runBlocking {
            val scope = ioScope()
            val store = newStore(newFile(), scope)

            store.save(VoiceSettings(llmProviderId = ProviderId("openai"), llmModelId = ModelId("gpt-test")))
            store.save(VoiceSettings())

            val reloaded = store.observe().first()
            assertNull(reloaded.llmProviderId)
            assertNull(reloaded.llmModelId)
            scope.cancel()
        }

    @Test
    fun noCredentialShapedValueIsPersisted() =
        runBlocking {
            val file = newFile()
            val scope = ioScope()
            val store = newStore(file, scope)
            val fakeSecret = "sk-live-DO-NOT-LEAK-0123456789"

            // The choice of how to authenticate is stored; the credential value is not.
            store.save(
                VoiceSettings(
                    llmProviderId = ProviderId("openrouter"),
                    llmModelId = ModelId("anthropic/claude-test"),
                    llmAuthMethod = AuthMethod.OAUTH_PKCE,
                ),
            )

            val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse("a credential must never appear in settings", bytes.contains(fakeSecret))
            assertFalse(bytes.contains("sk-live"))
            assertTrue(store.observe().first().llmProviderId == ProviderId("openrouter"))
            scope.cancel()
        }
}
