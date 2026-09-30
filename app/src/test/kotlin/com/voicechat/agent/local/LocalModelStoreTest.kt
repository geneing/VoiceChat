package com.voicechat.agent.local

import com.voicechat.agent.domain.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M20 acceptance for the app-managed model lifecycle: bounded size, integrity
 * verification, atomic install, partial cleanup, and removal. No filesystem or
 * real model is involved.
 */
class LocalModelStoreTest {
    private val bytes = "a-small-model-bundle".toByteArray()
    private val digest = LocalModelIntegrity.sha256Hex(bytes)

    private fun installer() = LocalModelInstaller(InMemoryLocalModelFileStore())

    private fun model(
        sha256: String = digest,
        downloadBytes: Long = bytes.size.toLong(),
    ) = validatedSample(sha256 = sha256, downloadBytes = downloadBytes)

    @Test
    fun aVerifiedArtifactInstallsAndReadsBackAsInstalled() {
        val installer = installer()
        val target = model()

        val result = installer.install(target, bytes)

        assertTrue(result is LocalInstallState.Installed)
        assertEquals(bytes.size.toLong(), (result as LocalInstallState.Installed).sizeBytes)
        assertTrue(installer.state(target) is LocalInstallState.Installed)
    }

    @Test
    fun aBadChecksumIsRefusedAndNoPartialFileRemains() {
        val installer = installer()
        val target = model(sha256 = "b".repeat(64))

        val result = installer.install(target, bytes)

        assertTrue(result is LocalInstallState.IntegrityFailed)
        assertFalse(installer.state(target) is LocalInstallState.Installed)
        assertEquals(LocalInstallState.NotInstalled, installer.state(target))
    }

    @Test
    fun anOversizedArtifactIsRefusedWithoutWriting() {
        val installer = installer()
        val target = model(downloadBytes = 4L)

        val result = installer.install(target, bytes)

        assertTrue(result is LocalInstallState.Failed)
        assertEquals(ErrorCode.MODEL_DOWNLOAD_FAILED, (result as LocalInstallState.Failed).error.code)
        assertEquals(LocalInstallState.NotInstalled, installer.state(target))
    }

    @Test
    fun removeDeletesTheInstalledBundle() {
        val installer = installer()
        val target = model()
        installer.install(target, bytes)

        assertTrue(installer.remove(target))
        assertEquals(LocalInstallState.NotInstalled, installer.state(target))
    }

    @Test
    fun cleanupRemovesLeftoverPartialFiles() {
        val store = InMemoryLocalModelFileStore()
        store.write("orphan.litertlm.partial", byteArrayOf(1, 2, 3))
        val installer = LocalModelInstaller(store)

        assertEquals(1, installer.cleanupPartial())
        assertTrue(store.names().isEmpty())
    }

    @Test
    fun theIntegrityHelperMatchesKnownSha256VectorsAndIsCaseInsensitive() {
        // SHA-256 of the empty string, a standard vector.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            LocalModelIntegrity.sha256Hex(ByteArray(0)),
        )
        assertTrue(LocalModelIntegrity.matches(digest.uppercase(), digest))
        assertFalse(LocalModelIntegrity.matches(digest, "b".repeat(64)))
    }
}
