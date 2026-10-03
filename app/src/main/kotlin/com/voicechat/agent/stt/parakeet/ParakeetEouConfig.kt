package com.voicechat.agent.stt.parakeet

/**
 * Validated configuration for the Parakeet-EOU-120M streaming decoder (stream 1).
 *
 * Every dimension is an exact model fact taken from the published `config.json`
 * (SHA-256 `db0d95…da4b`, 524 B) and reproduced by
 * `scripts/parakeet-eou-host.py`. The value type is the single place the
 * dimensions are declared **and validated**, mirroring
 * [com.voicechat.agent.turn.SmartTurnConfig]: validation happens in the
 * constructor so a mis-sized chunk or an out-of-range special token can never
 * silently reach the encoder.
 *
 * Derived streaming geometry (see the frozen contract):
 *
 * - [shiftSamples] = [outputMelFrames] × [subsamplingFactor] × [hopLength] —
 *   how many input samples the 32 decoded encoder frames advance per call.
 * - [chunkSamples] = [chunkMelFrames] × [hopLength] — the encoder's receptive
 *   window (64 mel frames = 10,240 samples = 640 ms).
 */
data class ParakeetEouConfig(
    /** Input sample rate; the pinned bundle requires 16 kHz mono float32. */
    val sampleRate: Int,
    /** Mel bins produced by the front end and consumed by the encoder. */
    val numMelBins: Int,
    /** STFT size for the mel front end. */
    val nFft: Int,
    /** STFT hop in samples. */
    val hopLength: Int,
    /** Analysis window length in samples (centered in an [nFft] frame). */
    val winLength: Int,
    /** Pre-emphasis coefficient applied before the STFT. */
    val preEmphasis: Float,
    /** Encoder hidden size. */
    val encoderHidden: Int,
    /** Number of conformer encoder layers. */
    val encoderLayers: Int,
    /** Time subsampling factor applied by the encoder. */
    val subsamplingFactor: Int,
    /** Attention context (frames of channel cache history). */
    val attentionContext: Int,
    /** Conformer convolution cache length (time frames). */
    val convCacheSize: Int,
    /** RNNT predictor (decoder) hidden size. */
    val decoderHidden: Int,
    /** Number of decoder LSTM layers. */
    val decoderLayers: Int,
    /** Vocabulary size excluding the blank/EOU/EOB extension. */
    val vocabSize: Int,
    /** The RNNT blank token id. */
    val blankTokenId: Int,
    /** The model-internal end-of-utterance token id. */
    val eouTokenId: Int,
    /** The model-internal end-of-block token id (never emitted as text). */
    val eobTokenId: Int,
    /** Mel frames per encoder window (64). */
    val chunkMelFrames: Int,
    /** Encoder frames decoded per call (4). */
    val outputMelFrames: Int,
    /** Frames of mel pre-cache carried by the encoder (9). */
    val preCacheSize: Int,
) {
    /** Input samples the 32 decoded encoder frames advance per call (5,120). */
    val shiftSamples: Int = outputMelFrames * subsamplingFactor * hopLength

    /** Input samples per encoder window (10,240). */
    val chunkSamples: Int = chunkMelFrames * hopLength

    /** The joint logit width: [vocabSize] word pieces plus the blank token. */
    val logits: Int = vocabSize + 1

    init {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        require(numMelBins > 0) { "numMelBins must be positive, was $numMelBins" }
        require(nFft > 0 && nFft % 2 == 0) { "nFft must be a positive even number, was $nFft" }
        require(hopLength > 0) { "hopLength must be positive, was $hopLength" }
        require(winLength in 2..nFft) { "winLength must be in 2..nFft, was $winLength" }
        require(encoderHidden > 0) { "encoderHidden must be positive, was $encoderHidden" }
        require(encoderLayers > 0) { "encoderLayers must be positive, was $encoderLayers" }
        require(subsamplingFactor > 0) { "subsamplingFactor must be positive, was $subsamplingFactor" }
        require(attentionContext > 0) { "attentionContext must be positive, was $attentionContext" }
        require(convCacheSize > 0) { "convCacheSize must be positive, was $convCacheSize" }
        require(decoderHidden > 0) { "decoderHidden must be positive, was $decoderHidden" }
        require(decoderLayers > 0) { "decoderLayers must be positive, was $decoderLayers" }
        require(vocabSize > 0) { "vocabSize must be positive, was $vocabSize" }
        require(chunkMelFrames > 0) { "chunkMelFrames must be positive, was $chunkMelFrames" }
        require(outputMelFrames > 0) { "outputMelFrames must be positive, was $outputMelFrames" }
        require(preCacheSize > 0) { "preCacheSize must be positive, was $preCacheSize" }
        require(blankTokenId in 0 until logits) {
            "blankTokenId must be in 0..<$logits, was $blankTokenId"
        }
        require(eouTokenId in 0 until logits) {
            "eouTokenId must be in 0..<$logits, was $eouTokenId"
        }
        require(eobTokenId in 0 until logits) {
            "eobTokenId must be in 0..<$logits, was $eobTokenId"
        }
        require(eouTokenId != eobTokenId && eouTokenId != blankTokenId && eobTokenId != blankTokenId) {
            "EOU, EOB, and blank must be distinct ids, was eou=$eouTokenId eob=$eobTokenId blank=$blankTokenId"
        }
    }

    companion object {
        /** The pinned bundle's exact `config.json`, byte-for-byte. */
        const val PINNED_CONFIG_JSON: String =
            """{
  "modelName": "parakeet-eou-120m",
  "numMelBins": 128,
  "sampleRate": 16000,
  "nFFT": 512,
  "hopLength": 160,
  "winLength": 400,
  "preEmphasis": 0.97,
  "encoderHidden": 512,
  "encoderLayers": 17,
  "subsamplingFactor": 8,
  "attentionContext": 70,
  "convCacheSize": 8,
  "decoderHidden": 640,
  "decoderLayers": 1,
  "vocabSize": 1026,
  "blankTokenId": 1026,
  "eouTokenId": 1024,
  "eobTokenId": 1025,
  "streaming": {
    "chunkMs": 320,
    "melFrames": 64,
    "preCacheSize": 9,
    "outputFrames": 4
  }
}
"""

        /** The pinned configuration parsed from [PINNED_CONFIG_JSON]. */
        fun pinned(): ParakeetEouConfig = fromConfigJson(PINNED_CONFIG_JSON)

        /**
         * Parses the publisher's `config.json` into a validated [ParakeetEouConfig].
         *
         * Only the fields the decoder uses are read; unknown keys (for example
         * `modelName` and `streaming.chunkMs`) are ignored so a future bundle can
         * add metadata without breaking this parser. A missing or mis-typed
         * required field throws [IllegalArgumentException].
         */
        fun fromConfigJson(text: String): ParakeetEouConfig {
            val root = parseParakeetJsonObject(text)
            val streaming = root.obj("streaming")
            return ParakeetEouConfig(
                sampleRate = root.int("sampleRate"),
                numMelBins = root.int("numMelBins"),
                nFft = root.int("nFFT"),
                hopLength = root.int("hopLength"),
                winLength = root.int("winLength"),
                preEmphasis = root.float("preEmphasis"),
                encoderHidden = root.int("encoderHidden"),
                encoderLayers = root.int("encoderLayers"),
                subsamplingFactor = root.int("subsamplingFactor"),
                attentionContext = root.int("attentionContext"),
                convCacheSize = root.int("convCacheSize"),
                decoderHidden = root.int("decoderHidden"),
                decoderLayers = root.int("decoderLayers"),
                vocabSize = root.int("vocabSize"),
                blankTokenId = root.int("blankTokenId"),
                eouTokenId = root.int("eouTokenId"),
                eobTokenId = root.int("eobTokenId"),
                chunkMelFrames = streaming.int("melFrames"),
                outputMelFrames = streaming.int("outputFrames"),
                preCacheSize = streaming.int("preCacheSize"),
            )
        }

        private fun Map<String, ParakeetJson>.obj(key: String): Map<String, ParakeetJson> {
            val value = this[key] ?: throw IllegalArgumentException("config.json is missing '$key'")
            return (value as? ParakeetJson.Obj)?.fields
                ?: throw IllegalArgumentException("config.json key '$key' is not an object")
        }

        private fun Map<String, ParakeetJson>.int(key: String): Int {
            val value = this[key] ?: throw IllegalArgumentException("config.json is missing '$key'")
            val number =
                (value as? ParakeetJson.Num)?.value
                    ?: throw IllegalArgumentException("config.json key '$key' is not a number")
            require(number == kotlin.math.floor(number) && number.isFinite()) {
                "config.json key '$key' must be an integer, was $number"
            }
            return number.toInt()
        }

        private fun Map<String, ParakeetJson>.float(key: String): Float {
            val value = this[key] ?: throw IllegalArgumentException("config.json is missing '$key'")
            val number =
                (value as? ParakeetJson.Num)?.value
                    ?: throw IllegalArgumentException("config.json key '$key' is not a number")
            return number.toFloat()
        }
    }
}
