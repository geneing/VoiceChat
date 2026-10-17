package com.voicechat.agent.tts.kokoro

/**
 * One pinned Teacher-in-the-Loop voice graph (M28).
 *
 * The graph is a single-voice Kokoro-82M student (8.07M parameters, int8 weights)
 * published by `MithrilMan/teacher-in-the-loop`; it takes Kokoro phoneme ids and
 * returns a 24 kHz waveform, so the identity that matters is the voice id, the
 * file, and the exact bytes. [sizeBytes] and [sha256] are the pinned identity:
 * a file that does not match both is never loaded.
 */
data class KokoroVoiceArtifact(
    /** Stable id, also the settings value (e.g. `af_heart`). */
    val id: String,
    /** Human-readable name for settings. */
    val displayName: String,
    /** The BCP-47 language the graph speaks (e.g. `en-US`). */
    val languageTag: String,
    /** The graph's file name inside the voice's app-private directory. */
    val fileName: String,
    /** The upstream repository path, for provenance. */
    val upstreamPath: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    init {
        require(id.isNotBlank()) { "voice id must not be blank" }
        require(fileName.isNotBlank()) { "voice file name must not be blank" }
        require(sizeBytes > 0) { "voice artifact size must be positive" }
    }
}

/**
 * The pinned Teacher-in-the-Loop Kokoro student artifacts and graph contract
 * (M28).
 *
 * This is the single place the app records **what** the distilled Kokoro model
 * is and **how** it is called, mirroring [com.voicechat.agent.turn.SmartTurnArtifact]
 * for the M10 model. Two things are pinned:
 *
 * - the **graphs**: `<voice>/model_int8.onnx`, 9,168,506 bytes each, downloaded
 *   app-privately and verified against the SHA-256 below
 *   (`docs/model-runtime.md`; never bundled in the APK);
 * - the **phoneme vocabulary**: Kokoro-82M's `vocab.json`, 1,147 bytes, which is
 *   shared by every voice and shipped as the app asset [VOCAB_ASSET_PATH]
 *   because the phonemizer needs it synchronously and offline.
 *
 * **Graph contract** (from the publisher's model card; verified by
 * `scripts/kokoro-reference.py` in WSL before the port was written):
 *
 * | direction | name | type | shape | content |
 * | --- | --- | --- | --- | --- |
 * | input | `input_ids` | int64 | `[1, T]` | phoneme ids, padding id `0` at both ends, `T <= 512` |
 * | input | `speed` | float32 | `[1]` | `1.0` is the teacher's pace |
 * | output | `waveform` | float32 | `[1, N]` | mono audio at [SAMPLE_RATE_HZ] |
 *
 * The excitation is random, so two runs on the same input differ slightly; that
 * is a property of the model, not a bug in the port, and it is why the engine
 * tests assert shape/range/silence-freedom rather than exact samples.
 */
object KokoroArtifact {
    /** The graph's output sample rate. Fixed by the architecture, not configurable. */
    const val SAMPLE_RATE_HZ: Int = 24_000

    /**
     * The most phonemes one graph call accepts.
     *
     * The text side has 512 positions, two of which hold the padding token, so a
     * call carries at most 510 phonemes — exactly `MAX_PHONEMES` in the
     * publisher's `titl/phonemes.py`, which this app's chunker ports.
     */
    const val MAX_PHONEMES: Int = 510

    /** The `input_ids` graph input name. */
    const val INPUT_IDS: String = "input_ids"

    /** The `speed` graph input name. */
    const val INPUT_SPEED: String = "speed"

    /** The `waveform` graph output name. */
    const val OUTPUT_WAVEFORM: String = "waveform"

    /** The pace used when the user has not chosen one: the teacher's own. */
    const val DEFAULT_SPEED: Float = 1.0f

    /** The app asset holding Kokoro-82M's phoneme vocabulary. */
    const val VOCAB_ASSET_PATH: String = "kokoro/vocab.json"

    /** The pinned vocabulary's byte size, for provenance checks. */
    const val VOCAB_SIZE_BYTES: Long = 1147L

    /** The pinned vocabulary's SHA-256 (`SHA256SUMS` of the model repository). */
    const val VOCAB_SHA256: String = "c985ed32b1007e0d52c8e86dd3f54e3ec62b74d14212bb5718348f7e9d734acf"

    /** The repository revision every artifact below is pinned to. */
    const val UPSTREAM_REVISION: String = "115a6c0469632a84fbcc6c8ed0ad7b1e06154b92"

    /** The repository the artifacts come from. */
    const val UPSTREAM_REPOSITORY: String = "MithrilMan/teacher-in-the-loop"

    /**
     * The English `af_heart` student, distilled from Kokoro-82M's `af_heart`
     * voice.
     *
     * It is the only wired voice: the app's speech surface offers the locales in
     * [com.voicechat.agent.tts.OnDeviceVoiceSelector.SUPPORTED_LANGUAGE_TAGS],
     * which are English and Spanish, and the other two published students are
     * Italian (`if_sara`, `im_nicola`) with an eSpeak-NG-derived phonemization
     * this app does not have (R-0261). Adding one is a new entry here plus a
     * language phonemizer — no engine change.
     */
    val AF_HEART: KokoroVoiceArtifact =
        KokoroVoiceArtifact(
            id = "af_heart",
            displayName = "Kokoro af_heart (English, distilled)",
            languageTag = "en-US",
            fileName = "model_int8.onnx",
            upstreamPath = "af_heart/model_int8.onnx",
            sizeBytes = 9_168_506L,
            sha256 = "f3daed94c8fe1f7a90f14cd7fc8ead55c99a8e57dd0aac669e376882483222c7",
        )

    /** Every voice this build knows how to install and load. */
    val VOICES: List<KokoroVoiceArtifact> = listOf(AF_HEART)

    /** The voice used when the user has not chosen one. */
    val DEFAULT: KokoroVoiceArtifact = AF_HEART

    /** The pinned voice with [id], or `null` when this build does not know it. */
    fun voice(id: String): KokoroVoiceArtifact? = VOICES.firstOrNull { it.id == id }

    /**
     * The app-private directory name that holds the Kokoro voices.
     *
     * Layout: `files/kokoro/<voice>/model_int8.onnx`, so a second voice is a new
     * subdirectory and never a rename of the first.
     */
    const val DIRECTORY_NAME: String = "kokoro"
}
