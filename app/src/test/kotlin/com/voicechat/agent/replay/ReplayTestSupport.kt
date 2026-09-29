package com.voicechat.agent.replay

import java.io.File

/** Path and resource helpers for the replay tests. */
internal object ReplayTestSupport {
    fun resourceBytes(path: String): ByteArray =
        ReplayTestSupport::class.java.getResourceAsStream(path)?.use { it.readBytes() }
            ?: error("missing test resource: $path")

    fun resourceText(path: String): String = String(resourceBytes(path), Charsets.UTF_8)

    /** Locates the repository root (the directory containing the `app` module). */
    fun repositoryRoot(): File {
        val working = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var directory: File? = working
        while (directory != null) {
            if (File(directory, "app/src/main/kotlin/com/voicechat/agent").isDirectory) return directory
            if (File(directory, "src/main/kotlin/com/voicechat/agent").isDirectory) {
                return directory.parentFile ?: error("module has no parent: $directory")
            }
            directory = directory.parentFile
        }
        error("could not locate the repository root from $working")
    }

    fun frozenResourceDirectory(): File = File(repositoryRoot(), "app/src/test/resources/replay/frozen")

    fun mainReplaySourceDirectory(): File = File(repositoryRoot(), "app/src/main/kotlin/com/voicechat/agent/replay")

    fun testReplaySourceDirectory(): File = File(repositoryRoot(), "app/src/test/kotlin/com/voicechat/agent/replay")
}
