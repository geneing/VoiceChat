package com.voicechat.agent.stt.parakeet

import com.voicechat.agent.domain.ModelId

/**
 * The pinned Parakeet-EOU 120M INT8 ONNX artifact identity (stream 2).
 *
 * Every field is a **verified fact** from
 * [docs/parakeet-eou-streaming.md](../../../../../../../../../../docs/parakeet-eou-streaming.md)
 * §"Model facts (verified)", sourced from the Hugging Face repository
 * `soniqo/Parakeet-EOU-120M-ONNX-INT8` (license `cc-by-4.0`; base
 * `nvidia/parakeet_realtime_eou_120m-v1`, NVIDIA Open Model License).
 *
 * Unlike Smart Turn (one file), Parakeet-EOU is **five** files: the encoder
 * (INT8), decoder (FP32), joint (FP32), plus `config.json` and `vocab.json`.
 * All five are pinned by exact byte size and lowercase hex SHA-256 and are
 * downloaded app-privately; none is ever bundled in the APK or placed in
 * `assets/`.
 *
 * @param modelName app-facing model identity, used by the settings catalog.
 * @param files the five pinned files, in a stable (encoder → vocab) order.
 */
data class ParakeetEouArtifact(
    val modelName: String,
    val files: List<ParakeetEouFile>,
    /** The immutable repository revision the base URL is pinned to. */
    val revision: String,
    /** Public, revision-pinned base URL; per-file URLs are [baseUrl] + fileName. */
    val baseUrl: String,
    /** The artifact license. */
    val license: String,
    /** The date the artifact facts were last verified against the publisher. */
    val accessedOn: String,
) {
    init {
        require(modelName.isNotBlank()) { "modelName must not be blank" }
        require(files.isNotEmpty()) { "files must not be empty" }
        require(files.map { it.fileName }.toSet().size == files.size) { "file names must be unique" }
        require(baseUrl.startsWith("https://")) { "baseUrl must be https" }
        require(baseUrl.endsWith("/")) { "baseUrl must end with '/', was $baseUrl" }
        require(revision.isNotBlank()) { "revision must not be blank" }
    }

    /** The catalog id for this model. */
    val modelId: ModelId get() = ModelId(modelName)

    /** Total expected bytes across all five files, e.g. for a progress total. */
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    /** @return the pinned file named [fileName], or `null` when unknown. */
    fun file(fileName: String): ParakeetEouFile? = files.firstOrNull { it.fileName == fileName }

    /** The public, revision-pinned download URL for [file]. */
    fun downloadUrl(file: ParakeetEouFile): String = baseUrl + file.fileName

    companion object {
        /** Public, revision-pinned base URL for every file. */
        const val BASE_URL: String =
            "https://huggingface.co/soniqo/Parakeet-EOU-120M-ONNX-INT8/resolve/main/"

        /** The repository revision the URLs are pinned to (the `main` branch tip). */
        const val REVISION: String = "main"

        /** The single allow-listed Parakeet-EOU artifact. */
        val PINNED: ParakeetEouArtifact =
            ParakeetEouArtifact(
                modelName = "parakeet-eou-120m-onnx-int8",
                files =
                    listOf(
                        ParakeetEouFile(
                            fileName = "parakeet-eou-encoder.onnx",
                            sizeBytes = 131_741_896L,
                            sha256 = "fcd0452f27a49d266a7b13a048f603798c66afe453838cd1ac685b30c1bae55c",
                            role = ParakeetEouFileRole.ENCODER,
                        ),
                        ParakeetEouFile(
                            fileName = "parakeet-eou-decoder.onnx",
                            sizeBytes = 15_757_826L,
                            sha256 = "205cd7d44b5320f81d82dd6683e49e05ba4d72e0564151eb21a86e172f69f488",
                            role = ParakeetEouFileRole.DECODER,
                        ),
                        ParakeetEouFile(
                            fileName = "parakeet-eou-joint.onnx",
                            sizeBytes = 5_589_132L,
                            sha256 = "6fb4b7674c2988ff8a4aa9c9181c6f06990d318a828e9696c4619505d2c08457",
                            role = ParakeetEouFileRole.JOINT,
                        ),
                        ParakeetEouFile(
                            fileName = "config.json",
                            sizeBytes = 524L,
                            sha256 = "db0d95f8575e6610f4f524c87d647d5bcd5a868ed5102044e5ccdf4352d5da4b",
                            role = ParakeetEouFileRole.CONFIG,
                        ),
                        ParakeetEouFile(
                            fileName = "vocab.json",
                            sizeBytes = 17_437L,
                            sha256 = "83fd42ad33dae1bd3ceee6c0bb6c625f314cf0b2dc8430be441ac1e2643d5c36",
                            role = ParakeetEouFileRole.VOCAB,
                        ),
                    ),
                revision = REVISION,
                baseUrl = BASE_URL,
                license = "cc-by-4.0",
                accessedOn = "2026-10-03",
            )
    }
}

/** What a pinned Parakeet-EOU file is, so a caller can name the graph it needs. */
enum class ParakeetEouFileRole {
    ENCODER,
    DECODER,
    JOINT,
    CONFIG,
    VOCAB,
}

/**
 * One pinned file inside [ParakeetEouArtifact].
 *
 * @param fileName the exact file name inside the pinned repository.
 * @param sizeBytes the exact expected byte size.
 * @param sha256 the exact expected lowercase hex SHA-256 (64 characters).
 * @param role the file's purpose.
 */
data class ParakeetEouFile(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val role: ParakeetEouFileRole,
) {
    init {
        require(fileName.isNotBlank()) { "fileName must not be blank" }
        require(sizeBytes > 0L) { "sizeBytes must be positive, was $sizeBytes" }
        require(sha256.length == SHA256_HEX_LENGTH) { "sha256 must be $SHA256_HEX_LENGTH hex characters" }
    }

    companion object {
        const val SHA256_HEX_LENGTH: Int = 64
    }
}
