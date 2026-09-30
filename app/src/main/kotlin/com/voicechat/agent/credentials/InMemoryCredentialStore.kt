package com.voicechat.agent.credentials

import com.voicechat.agent.domain.ProviderId
import java.util.concurrent.ConcurrentHashMap

/**
 * A process-local [CredentialStore] used by tests and JVM tooling.
 *
 * It implements the full contract (store/replace/remove/load) without any
 * platform or KeyStore dependency, so the credential/UI seams can be exercised
 * on the JVM. It is deliberately **not** secure at rest: it holds the value in
 * memory only and must never be used in a shipped Android build — the Android
 * app uses `AndroidKeystoreCredentialStore`.
 */
class InMemoryCredentialStore : CredentialStore {
    private val records = ConcurrentHashMap<String, Credential>()

    override suspend fun status(providerId: ProviderId): CredentialStatus {
        val stored = records[providerId.value] ?: return CredentialStatus.NotStored
        return CredentialStatus.Stored(providerId, stored.kind)
    }

    override suspend fun store(credential: Credential): CredentialStoreOutcome {
        records[credential.providerId.value] = credential
        return CredentialStoreOutcome.Success
    }

    override suspend fun remove(providerId: ProviderId): CredentialStoreOutcome =
        if (records.remove(providerId.value) != null) {
            CredentialStoreOutcome.Success
        } else {
            CredentialStoreOutcome.NotStored
        }

    override suspend fun load(providerId: ProviderId): Credential? = records[providerId.value]
}
