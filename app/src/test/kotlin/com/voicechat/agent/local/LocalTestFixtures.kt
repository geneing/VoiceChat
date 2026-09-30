package com.voicechat.agent.local

import com.voicechat.agent.contracts.LlmCapabilities
import com.voicechat.agent.contracts.ModelRuntime
import com.voicechat.agent.contracts.ModelTask
import com.voicechat.agent.domain.ModelId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A fully-specified, allow-list-eligible artifact; override a field to break it. */
internal fun sampleArtifact(
    id: String = "test-model",
    displayName: String = "Test Model",
    task: ModelTask = ModelTask.LANGUAGE_MODEL,
    runtime: ModelRuntime = ModelRuntime.LITERT_LM,
    publisher: String? = "Example Publisher",
    sourceUrl: String? = "https://example.com/model",
    license: String? = "Apache-2.0",
    licenseUrl: String? = "https://example.com/LICENSE",
    runtimeVersion: String? = "0.17.1",
    downloadBytes: Long? = 1024L,
    storageBytes: Long? = 1024L,
    peakMemoryBytes: Long? = 2048L,
    minAndroidApi: Int? = 31,
    sha256: String? = "a".repeat(64),
    testedDevice: String? = null,
    deviceNotes: String? = null,
    notes: String? = null,
): LocalModelArtifact =
    LocalModelArtifact(
        id = ModelId(id),
        displayName = displayName,
        task = task,
        runtime = runtime,
        publisher = publisher,
        sourceUrl = sourceUrl,
        license = license,
        licenseUrl = licenseUrl,
        runtimeVersion = runtimeVersion,
        downloadBytes = downloadBytes,
        storageBytes = storageBytes,
        peakMemoryBytes = peakMemoryBytes,
        minAndroidApi = minAndroidApi,
        sha256 = sha256,
        testedDevice = testedDevice,
        deviceNotes = deviceNotes,
        notes = notes,
    )

/** A validated sample, or an assertion failure if the fixture is malformed. */
internal fun validatedSample(
    id: String = "test-model",
    sha256: String = "a".repeat(64),
    downloadBytes: Long = 1024L,
): ValidatedLocalModel {
    val validation = LocalModelCatalogValidator.validate(sampleArtifact(id = id, sha256 = sha256, downloadBytes = downloadBytes))
    return (validation as LocalCatalogValidation.Accepted).model
}

/** In-memory [LocalModelFileStore] so the install lifecycle needs no filesystem. */
internal class InMemoryLocalModelFileStore : LocalModelFileStore {
    private val files = linkedMapOf<String, ByteArray>()

    override fun names(): List<String> = files.keys.toList()

    override fun size(name: String): Long? = files[name]?.size?.toLong()

    override fun read(name: String): ByteArray? = files[name]?.copyOf()

    override fun pathFor(name: String): String? = name.takeIf { files.containsKey(it) }

    override fun write(
        name: String,
        bytes: ByteArray,
    ) {
        files[name] = bytes.copyOf()
    }

    override fun rename(
        from: String,
        to: String,
    ): Boolean {
        val bytes = files.remove(from) ?: return false
        files[to] = bytes
        return true
    }

    override fun delete(name: String): Boolean = files.remove(name) != null
}

/** A deterministic [LocalTextGenerator] for the AICore adapter tests. */
internal class FakeLocalTextGenerator(
    private val chunks: List<String> = emptyList(),
    private val failure: Throwable? = null,
    override val capabilities: LlmCapabilities = LlmCapabilities(streaming = true, usageReporting = false),
) : LocalTextGenerator {
    val prompts = mutableListOf<String>()

    override fun generate(prompt: String): Flow<String> =
        flow {
            prompts += prompt
            chunks.forEach { emit(it) }
            failure?.let { throw it }
        }
}

/** A session + factory pair for the LiteRT-LM adapter tests. */
internal class FakeLiteRtLmSessionFactory(
    private val chunks: List<String> = emptyList(),
    private val openFailure: Throwable? = null,
    private val generateFailure: Throwable? = null,
) : LiteRtLmSessionFactory {
    val openedPaths = mutableListOf<String>()
    val sessions = mutableListOf<FakeLiteRtLmSession>()

    override suspend fun open(modelPath: String): LiteRtLmSession {
        openedPaths += modelPath
        openFailure?.let { throw it }
        return FakeLiteRtLmSession(chunks, generateFailure).also { sessions += it }
    }
}

internal class FakeLiteRtLmSession(
    private val chunks: List<String>,
    private val generateFailure: Throwable?,
) : LiteRtLmSession {
    val prompts = mutableListOf<String>()
    var closed: Boolean = false

    override fun generate(prompt: String): Flow<String> =
        flow {
            prompts += prompt
            chunks.forEach { emit(it) }
            generateFailure?.let { throw it }
        }

    override fun close() {
        closed = true
    }
}
