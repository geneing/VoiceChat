package com.voicechat.agent.tts.kokoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Keeps the Kotlin pin aligned with the fetch, manifest, and app-private push catalogs. */
class KokoroArtifactPinningTest {
    @Test
    fun theEnglishGraphPinMatchesEveryModelScriptAndManifest() {
        val repositoryRoot = locateRepositoryRoot()
        assumeTrue(
            "Repository files are unavailable from ${System.getProperty("user.dir")}; skipping artifact pin check",
            repositoryRoot != null,
        )
        val root = requireNotNull(repositoryRoot)
        val artifact = KokoroArtifact.AF_HEART
        val revision = KokoroArtifact.UPSTREAM_REVISION
        val expectedUrl =
            "https://huggingface.co/${KokoroArtifact.UPSTREAM_REPOSITORY}/resolve/" +
                "$revision/${artifact.upstreamPath}"
        val expectedSource = "${KokoroArtifact.UPSTREAM_REPOSITORY}@$revision"

        assertEquals("af_heart/model_int8.onnx", artifact.upstreamPath)
        assertEquals("kokoro/af_heart/model_int8.onnx", KokoroArtifact.bundledAssetPath(artifact))
        assertEquals(9_168_506L, artifact.sizeBytes)
        assertEquals(
            "f3daed94c8fe1f7a90f14cd7fc8ead55c99a8e57dd0aac669e376882483222c7",
            artifact.sha256,
        )

        val fetch = File(root, "scripts/fetch-models.sh").readText()
        val fetchCatalog = fetch.substringAfter("CATALOG=$(cat <<'EOF'\n").substringBefore("\nEOF")
        val fetchFields =
            fetchCatalog
                .lineSequence()
                .map { line -> line.split('\t').map(String::trim) }
                .firstOrNull { fields -> fields.firstOrNull() == "kokoro-af-heart" }
        assertEquals(
            listOf(
                "kokoro-af-heart",
                // The fetched file IS the bundled asset: it lands under the asset
                // source directory the app module registers.
                "kokoro-assets/kokoro/af_heart/model_int8.onnx",
                expectedUrl,
                "9168506",
                artifact.sha256,
                "Apache-2.0",
                expectedSource,
            ),
            fetchFields,
        )
        assertEquals(revision, requireNotNull(fetchFields)[2].substringAfter("/resolve/").substringBefore('/'))

        val manifest = File(root, "models/manifest.json").readText()
        val manifestArtifact =
            Regex("\\{\\s*\"id\": \"kokoro-af-heart\"([\\s\\S]*?)\\n\\s*}")
                .find(manifest)
                ?.value
        assertTrue("manifest must contain the Kokoro artifact", manifestArtifact != null)
        val block = requireNotNull(manifestArtifact)
        listOf(
            "\"filename\": \"kokoro-assets/kokoro/af_heart/model_int8.onnx\"",
            "\"destination\": \"kokoro/af_heart/model_int8.onnx\"",
            "\"bundledInApk\": true",
            "\"url\": \"$expectedUrl\"",
            "\"sizeBytes\": 9168506",
            "\"sha256\": \"${artifact.sha256}\"",
            "\"license\": \"Apache-2.0\"",
            "\"source\": \"$expectedSource\"",
            "\"runtime\": \"onnxruntime-android 1.29.0\"",
        ).forEach { field -> assertTrue("manifest must include $field", block.contains(field)) }

        val push = File(root, "scripts/push-models.sh").readText()
        assertTrue(
            "push catalog must install the voice graph under its per-voice path",
            push.contains(
                "kokoro-af-heart\\tkokoro-assets/kokoro/af_heart/model_int8.onnx\\tkokoro/af_heart/model_int8.onnx",
            ),
        )

        // The build is what actually puts the graph in the APK, so the wiring is
        // part of the pin: without it the fetched file is never packaged.
        val build = File(root, "app/build.gradle.kts").readText()
        assertTrue(
            "the app module must register the fetched graph directory as an asset source",
            build.contains("models/kokoro-assets") && build.contains("assets.directories.add"),
        )
    }

    private fun locateRepositoryRoot(): File? {
        val workingDirectory = System.getProperty("user.dir") ?: "."
        var directory: File? = File(workingDirectory).absoluteFile
        while (directory != null) {
            val current = directory
            if (File(current, "settings.gradle.kts").isFile && File(current, "app").isDirectory) {
                return current
            }
            directory = current.parentFile
        }
        return null
    }
}
