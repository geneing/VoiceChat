package com.voicechat.agent.local

import com.voicechat.agent.domain.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun aSizeMismatchIsReportedRatherThanInstalled() {
        val store = InMemoryLocalModelFileStore()
        val installer = LocalModelInstaller(store)
        val target = model()
        installer.install(target, bytes)

        // Simulate a truncated/partial file of the right name but the wrong size.
        store.write(LocalModelInstaller.fileNameFor(target.artifact), bytes.copyOf(3))

        val state = installer.state(target)
        assertTrue(state is LocalInstallState.SizeMismatch)
        assertEquals(bytes.size.toLong(), (state as LocalInstallState.SizeMismatch).expectedBytes)
        assertEquals(3L, state.actualBytes)
    }

    /**
     * `scripts/push-models.sh` must not install an app-managed bundle the catalog
     * does not know, and must install every catalog entry under the exact name
     * [LocalModelInstaller] resolves it by. The catalog is empty today, so the
     * script must declare no `local-models/` destination; this guards against an
     * orphaned bundle (such as a removed local LLM) lingering in the script.
     */
    @Test
    fun thePushScriptOnlyInstallsCatalogModels() {
        val script = File(locateRepositoryRoot(), "scripts/push-models.sh").readText()
        val declared = pushDestinations(script).values.filter { it.startsWith("local-models/") }.toSet()
        val expected =
            DocumentedLocalModels.all
                .map { "local-models/${LocalModelInstaller.fileNameFor(it)}" }
                .toSet()

        assertEquals(expected, declared)
    }

    /**
     * Destination (relative to app `files/`) by artifact id, from the script.
     *
     * The catalog is a `$'...'` string whose rows are separated by a literal
     * `\n` escape and whose fields by a literal `\t`, so the parser splits on
     * those escapes rather than real whitespace.
     */
    private fun pushDestinations(script: String): Map<String, String> =
        script
            .substringAfter("CATALOG=", missingDelimiterValue = "")
            .lineSequence()
            .flatMap { it.split("\\n") }
            .mapNotNull { row ->
                val fields = row.split("\\t")
                if (fields.size != 3) return@mapNotNull null
                val id = fields[0].substringAfter('\'').trim()
                val destination = fields[2].substringBefore('\'').trim()
                if (id.isEmpty() || !destination.contains('/')) return@mapNotNull null
                id to destination
            }.toMap()

    private fun locateRepositoryRoot(): File {
        val workingDirectory = System.getProperty("user.dir") ?: "."
        var directory: File? = File(workingDirectory).absoluteFile
        while (directory != null) {
            val current = directory
            if (File(current, "settings.gradle.kts").isFile && File(current, "app").isDirectory) return current
            directory = current.parentFile
        }
        throw AssertionError("Could not locate the repository root from $workingDirectory")
    }
}
