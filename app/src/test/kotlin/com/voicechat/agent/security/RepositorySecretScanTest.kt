package com.voicechat.agent.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M13 acceptance for "static and packaged-resource checks find no test/live
 * secrets": the shipped source, resources, build files, docs, and CI config must
 * contain no credential-shaped string. Test sources are excluded because tests
 * legitimately use obviously-fake keys (`sk-live-DO-NOT-LEAK-...`), and the scan
 * is proven non-trivial by [theScannerCatchesAKnownSecretShape].
 *
 * This resolves risk R-0049 for the credentials milestone: the app bundles no
 * provider secret and the only stored credentials live in the AndroidKeyStore.
 */
class RepositorySecretScanTest {
    @Test
    fun theScannerCatchesAKnownSecretShape() {
        assertTrue(SECRET_PATTERNS.any { it.containsMatchIn("api_key = \"sk-live-0123456789abcdef\"") })
        assertTrue(
            SECRET_PATTERNS.any {
                it.containsMatchIn("Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIn0.signature")
            },
        )
        assertTrue(SECRET_PATTERNS.any { it.containsMatchIn("-----BEGIN RSA PRIVATE KEY-----") })
    }

    @Test
    fun noSecretShapeAppearsInShippedSourcesResourcesBuildFilesOrDocs() {
        val root = locateRepositoryRoot()
        val violations = mutableListOf<String>()

        scanTargets(root).forEach { target ->
            if (target.isDirectory) {
                target
                    .walkTopDown()
                    .filter { it.isFile && it.extension.lowercase() in SCANNED_EXTENSIONS }
                    .forEach { violations += scanFile(root, it) }
            } else if (target.isFile) {
                violations += scanFile(root, target)
            }
        }

        assertTrue(
            "a credential-shaped string was found in shipped sources/resources/build files/docs:\n" +
                violations.joinToString(separator = "\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun noPackagedKeystoreOrFirebaseConfigIsPresent() {
        val root = locateRepositoryRoot()
        val forbidden = listOf(".jks", ".keystore", "google-services.json")

        forbidden.forEach { name ->
            val matches =
                File(root, "app/src/main")
                    .walkTopDown()
                    .filter { it.isFile && it.name == name }
                    .toList()
            assertTrue("packaged secret artifact found: $name ($matches)", matches.isEmpty())
        }
    }

    private fun scanTargets(root: File): List<File> =
        listOf(
            File(root, "app/src/main"),
            File(root, "app/src/debug"),
            File(root, "app/build.gradle.kts"),
            File(root, "build.gradle.kts"),
            File(root, "settings.gradle.kts"),
            File(root, "gradle.properties"),
            File(root, "gradle/libs.versions.toml"),
            File(root, "gradle/wrapper/gradle-wrapper.properties"),
            File(root, ".github/workflows"),
            File(root, "docs"),
        )

    private fun scanFile(
        root: File,
        file: File,
    ): List<String> {
        val text = file.readText()
        return SECRET_PATTERNS
            .filter { it.containsMatchIn(text) }
            .map { pattern -> "${file.relativeTo(root)}: matched ${pattern.pattern}" }
    }

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

    private companion object {
        val SCANNED_EXTENSIONS =
            setOf("kt", "kts", "xml", "toml", "properties", "md", "json", "pro", "yml", "yaml", "txt", "cfg")

        val SECRET_PATTERNS =
            listOf(
                Regex("\\b(sk|rk)-[A-Za-z0-9_-]{16,}"),
                Regex("\\bAKIA[0-9A-Z]{16}\\b"),
                Regex("\\bAIza[0-9A-Za-z_-]{35}\\b"),
                Regex("\\bghp_[A-Za-z0-9]{36}\\b"),
                Regex("\\bxox[baprs]-[A-Za-z0-9-]{10,}"),
                Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
                Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
                Regex(
                    "(?i)(api[_-]?key|api[_-]?secret|client[_-]?secret|access[_-]?token|refresh[_-]?token|password)" +
                        "\\s*[:=]\\s*[\"'][A-Za-z0-9_\\-.]{16,}[\"']",
                ),
            )
    }
}
