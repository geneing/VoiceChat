package com.voicechat.agent.stt.parakeet

/**
 * The narrow decode seam [ParakeetEouSpeechToText] drives.
 *
 * Stream 1 owns the pure [ParakeetStreamingDecoder] (mel + RNNT greedy loop).
 * This interface is **not** a third decode implementation and does not fork that
 * seam: it is the adapter-facing view of one decode session, so the app-facing
 * STT adapter can be JVM-tested with a deterministic fake without running ONNX,
 * while the real decoder is wired through [ParakeetDecoderSessions.ofDecoder].
 *
 * Every method maps 1:1 to a [ParakeetStreamingDecoder] method with the same
 * contract:
 *
 * | This seam | [ParakeetStreamingDecoder] |
 * | --- | --- |
 * | [reset] | `reset()` |
 * | [accept] | `accept(chunkSamples: FloatArray): DecodeResult` |
 * | [finalize] | `finalize(): DecodeResult` |
 * | [forceEndOfUtterance] | `forceEndOfUtterance(): DecodeResult` |
 *
 * The adapter buffers captured audio and calls [accept] as data arrives. One
 * decoder session is retained for the capture; VAD uses [forceEndOfUtterance]
 * between turns so caches continue into the next utterance. A session is confined
 * to one worker thread by the adapter, so implementations need not be thread-safe.
 */
interface ParakeetDecodeSession : AutoCloseable {
    /** Clears all decode state so the next chunk starts a fresh utterance. */
    fun reset()

    /** Decodes one chunk of 16 kHz mono float32 PCM and returns what it produced. */
    fun accept(chunkSamples: FloatArray): DecodeResult

    /** Flushes any buffered right context at end of input. */
    fun finalize(): DecodeResult

    /** VAD-driven force-finalize that preserves the streaming caches. */
    fun forceEndOfUtterance(): DecodeResult
}

/**
 * Builds one [ParakeetDecodeSession] per recognition session.
 *
 * The adapter creates one decoder per
 * [com.voicechat.agent.contracts.SpeechToText] instance (per capture), so this
 * factory is the injection point at the app boundary. Production uses
 * [ParakeetDecoderSessions.ofDecoder]; tests inject a deterministic fake.
 */
fun interface ParakeetDecoderSessionFactory {
    /** Creates a fresh, unprimed session. */
    fun create(): ParakeetDecodeSession
}

/** Bridges stream 1's concrete decoder (and test fakes) to [ParakeetDecodeSession]. */
object ParakeetDecoderSessions {
    /**
     * Wraps a [ParakeetStreamingDecoder] so the adapter drives the real engine.
     *
     * [ParakeetStreamingDecoder] is `Closeable`-free (it holds no native resource;
     * the runtime does), so [ParakeetDecodeSession.close] is a no-op here.
     */
    fun ofDecoder(decoder: ParakeetStreamingDecoder): ParakeetDecodeSession =
        object : ParakeetDecodeSession {
            override fun reset() = decoder.reset()

            override fun accept(chunkSamples: FloatArray): DecodeResult = decoder.accept(chunkSamples)

            override fun finalize(): DecodeResult = decoder.finalize()

            override fun forceEndOfUtterance(): DecodeResult = decoder.forceEndOfUtterance()

            override fun close() = Unit
        }

    /**
     * Adapts a decode lambda triple to a factory, for tests and thin production
     * wiring: [accept]/[finalize]/[forceEndOfUtterance] are the decoder calls.
     */
    fun of(
        accept: (FloatArray) -> DecodeResult,
        finalize: () -> DecodeResult,
        forceEndOfUtterance: () -> DecodeResult,
        reset: () -> Unit = {},
        onClose: () -> Unit = {},
    ): ParakeetDecoderSessionFactory =
        ParakeetDecoderSessionFactory {
            object : ParakeetDecodeSession {
                override fun reset() = reset()

                override fun accept(chunkSamples: FloatArray): DecodeResult = accept(chunkSamples)

                override fun finalize(): DecodeResult = finalize()

                override fun forceEndOfUtterance(): DecodeResult = forceEndOfUtterance()

                override fun close() = onClose()
            }
        }
}
