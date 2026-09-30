package com.voicechat.agent.credentials

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicechat.agent.domain.ProviderId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/**
 * On-device checks for the real AndroidKeyStore-backed [CredentialStore]
 * (Tests.md § M13). They run only with `:app:connectedDebugAndroidTest`.
 *
 * Every assertion uses the real KeyStore and real app-private preferences; a
 * "restart" is modeled by building a **new** store instance, which re-reads the
 * KeyStore key and the preferences file instead of any in-memory state. A full
 * `adb shell am force-stop` relaunch remains a manual check in Tests.md.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreCredentialStoreInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val providerId = ProviderId("m13-instrumented")
    private val secret = "sk-live-DO-NOT-LEAK-0123456789"

    @Test
    fun storeStatusLoadReplaceAndRemoveUseTheRealKeystore() =
        runBlocking {
            val store = AndroidKeystoreCredentialStore.create(context)
            store.remove(providerId)

            assertEquals(CredentialStatus.NotStored, store.status(providerId))

            assertEquals(
                CredentialStoreOutcome.Success,
                store.store(Credential(providerId, CredentialKind.API_KEY, secret)),
            )
            assertEquals(CredentialStatus.Stored(providerId, CredentialKind.API_KEY), store.status(providerId))
            assertEquals(secret, store.load(providerId)?.secret)

            val replacement = "sk-live-REPLACED-0987654321"
            store.store(Credential(providerId, CredentialKind.API_KEY, replacement))
            assertEquals(replacement, store.load(providerId)?.secret)

            assertEquals(CredentialStoreOutcome.Success, store.remove(providerId))
            assertEquals(CredentialStatus.NotStored, store.status(providerId))
            assertNull(store.load(providerId))
            Log.i(TAG, "M13 store/replace/remove against the real AndroidKeyStore completed")
        }

    @Test
    fun aStoredCredentialSurvivesANewStoreInstance() =
        runBlocking {
            AndroidKeystoreCredentialStore
                .create(context)
                .store(Credential(providerId, CredentialKind.API_KEY, secret))

            // A new instance re-reads the KeyStore key and the preferences file.
            val restarted = AndroidKeystoreCredentialStore.create(context)
            assertEquals(CredentialStatus.Stored(providerId, CredentialKind.API_KEY), restarted.status(providerId))
            assertEquals(secret, restarted.load(providerId)?.secret)

            assertTrue(
                "the KeyStore key must exist for the store to survive a restart",
                KeyStore
                    .getInstance(ANDROID_KEYSTORE)
                    .apply { load(null) }
                    .containsAlias(AndroidKeystoreCredentialStore.KEY_ALIAS),
            )
            Log.i(TAG, "M13 credential survived a new store instance via the real KeyStore")

            restarted.remove(providerId)
        }

    @Test
    fun thePreferencesFileNeverHoldsThePlaintextSecret() =
        runBlocking {
            AndroidKeystoreCredentialStore
                .create(context)
                .store(Credential(providerId, CredentialKind.API_KEY, secret))

            val stored =
                context
                    .getSharedPreferences(AndroidKeystoreCredentialStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .all
                    .values
                    .joinToString(separator = "\n") { it.toString() }

            assertTrue("the encrypted blob should be present", stored.isNotEmpty())
            assertFalse("the plaintext secret was written to preferences", stored.contains(secret))
            Log.i(TAG, "M13 preferences hold only ciphertext")

            AndroidKeystoreCredentialStore.create(context).remove(providerId)
        }

    private companion object {
        const val TAG = "M13CredentialsInstrumented"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
