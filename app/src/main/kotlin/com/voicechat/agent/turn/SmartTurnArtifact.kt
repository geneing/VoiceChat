package com.voicechat.agent.turn

import com.voicechat.agent.domain.ModelId

/**
 * The pinned Smart Turn v3.2 ONNX artifact identity (M10).
 *
 * Every field here is a **verified fact**, not a guess, and is recorded with its
 * source in [docs/smart-turn.md] and
 * [docs/decisions.md](../docs/decisions.md) §3.3:
 *
 * - Repository `soniqo/Smart-Turn-v3.2-ONNX`, revision
 *   `b48fdbe20772bcec1fef02f4a1a355236ef6359e` (published 2026-09-02).
 * - File `smart-turn-v3.2-int8.onnx`, exactly [sizeBytes] bytes, SHA-256
 *   [sha256]. Re-verified against the Hugging Face model API on
 *   [accessedOn].
 * - License BSD-2-Clause (the publisher's `LICENSE`, 1,406 bytes).
 * - Input `audio` float32 `[1, 128000]`, 16 kHz mono, most recent audio last with
 *   zeros at the front; output `probability` float32 `[1, 1]`. The graph embeds
 *   the Whisper log-mel + normalization front-end, so raw PCM is fed and one
 *   probability is read.
 *
 * It is a **third-party re-export** of `pipecat-ai/smart-turn-v3`, not the
 * upstream publisher; the project's contract is the `TurnCompletionDetector`
 * interface, so swapping the artifact must not change orchestration
 * ([docs/decisions.md](../docs/decisions.md) §3.3). It is a turn-detection model,
 * not speech, so it is **never** bundled in the APK or placed in `assets/`.
 */
data class SmartTurnArtifact(
    /** App-facing model identity, used by the settings catalog. */
    val modelName: String,
    /** The pinned file name inside [downloadUrl]. */
    val fileName: String,
    /** Public, revision-pinned download URL (never a secret, never app-private). */
    val downloadUrl: String,
    /** The immutable repository revision the URL is pinned to. */
    val revision: String,
    /** The exact expected byte size. */
    val sizeBytes: Long,
    /** The exact expected lowercase hex SHA-256. */
    val sha256: String,
    /** The artifact license. */
    val license: String,
    /** The date the artifact facts were last verified against the publisher. */
    val accessedOn: String,
    /** The model graph's input tensor name. */
    val inputName: String = INPUT_NAME,
    /** The model graph's output tensor name. */
    val outputName: String = OUTPUT_NAME,
) {
    init {
        require(modelName.isNotBlank()) { "modelName must not be blank" }
        require(fileName.isNotBlank()) { "fileName must not be blank" }
        require(downloadUrl.startsWith("https://")) { "downloadUrl must be https" }
        require(sizeBytes > 0L) { "sizeBytes must be positive, was $sizeBytes" }
        require(sha256.length == SHA256_HEX_LENGTH) { "sha256 must be 64 hex characters" }
    }

    /** The catalog id for this model. */
    val modelId: ModelId get() = ModelId(modelName)

    companion object {
        const val SHA256_HEX_LENGTH: Int = 64

        /** Public graph input name (`config.json`). */
        const val INPUT_NAME: String = "audio"

        /** Public graph output name (`config.json`). */
        const val OUTPUT_NAME: String = "probability"

        /**
         * The single allow-listed Smart Turn artifact.
         *
         * Verified \(2026-09-29\) against
         * `https://huggingface.co/api/models/soniqo/Smart-Turn-v3.2-ONNX?blobs=true`:
         * revision `b48fdbe20772bcec1fef02f4a1a355236ef6359e`, size 11,123,370,
         * SHA-256 `00cd…ea31`, license `bsd-2-clause`.
         */
        val PINNED: SmartTurnArtifact =
            SmartTurnArtifact(
                modelName = "smart-turn-v3.2-int8",
                fileName = "smart-turn-v3.2-int8.onnx",
                downloadUrl =
                    "https://huggingface.co/soniqo/Smart-Turn-v3.2-ONNX/" +
                        "resolve/b48fdbe20772bcec1fef02f4a1a355236ef6359e/smart-turn-v3.2-int8.onnx",
                revision = "b48fdbe20772bcec1fef02f4a1a355236ef6359e",
                sizeBytes = 11_123_370L,
                sha256 = "00cd131551e8d1e9011f31345edb632a26116dd83e159782c4ddff610718ea31",
                license = "BSD-2-Clause",
                accessedOn = "2026-09-29",
            )
    }
}
