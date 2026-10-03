package com.voicechat.agent.voice

import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.SpeechToText
import com.voicechat.agent.contracts.TurnCompletionDetector
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.log.AppLog
import com.voicechat.agent.log.LogLevel
import com.voicechat.agent.stt.SttEngine
import com.voicechat.agent.stt.parakeet.OnnxParakeetEouRuntime
import com.voicechat.agent.stt.parakeet.ParakeetDecoderSessionFactory
import com.voicechat.agent.stt.parakeet.ParakeetDecoderSessions
import com.voicechat.agent.stt.parakeet.ParakeetEouConfig
import com.voicechat.agent.stt.parakeet.ParakeetEouSpeechToText
import com.voicechat.agent.stt.parakeet.ParakeetEouSpeechToTextConfig
import com.voicechat.agent.stt.parakeet.ParakeetEouTurnDetectorFactory
import com.voicechat.agent.stt.parakeet.ParakeetStreamingDecoder
import com.voicechat.agent.stt.parakeet.ParakeetVocabulary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The production [ParakeetEouSessionFactory] (stream 3 ↔ stream 4).
 *
 * It turns a verified app-private model set into the adapter pair the voice loop
 * runs: the `OnnxParakeetEouRuntime`'s three graphs are opened once, wrapped in a
 * per-turn [ParakeetStreamingDecoder], and exposed through
 * [ParakeetEouSpeechToText] together with the model's own `<EOU>` corroborator
 * ([ParakeetEouTurnDetectorFactory]).
 *
 * **Lifecycle.** One session owns one runtime. `create` opens the graphs; a graph
 * that cannot be opened throws a typed failure (`UnexpectedParakeetTensorException`
 * from [OnnxParakeetEouRuntime.load]) rather than returning a half-initialized
 * session, so the assembly reports `MODEL_UNAVAILABLE` instead of fabricating a
 * session — the same honesty rule every other model path follows. The assembly
 * closes the [ParakeetEouSession]; [close] releases the graphs and is idempotent.
 *
 * **Threading.** The graph load is blocking and native, so the assembly must call
 * [create] off the main thread (it does: the session runs on its IO dispatcher).
 *
 * **Model facts.** Every dimension comes from the pinned `config.json`
 * ([ParakeetEouConfig.pinned]) and the vocabulary from `vocab.json`, so a changed
 * artifact is a loud failure, never a silently wrong transcript.
 *
 * @param diagnostics privacy-safe M04 sink, forwarded to the STT adapter.
 * @param clock monotonic clock for per-chunk timing and EOU ageing.
 * @param config pinned decoder geometry; defaults to [ParakeetEouConfig.pinned].
 * @param adapterConfig chunking/debounce tuning for [ParakeetEouSpeechToText].
 * @param decodeDispatcher dispatcher the CPU-bound decode loop runs on.
 */
class OnnxParakeetEouSessionFactory(
    private val diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val config: ParakeetEouConfig = ParakeetEouConfig.pinned(),
    private val adapterConfig: ParakeetEouSpeechToTextConfig = ParakeetEouSpeechToTextConfig.default(),
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ParakeetEouSessionFactory {
    override fun create(
        engine: SttEngine,
        directory: File,
    ): ParakeetEouSession {
        val vocab = loadVocabulary(directory)
        val runtime = OnnxParakeetEouRuntime.load(directory, config)
        AppLog.voice(LogLevel.INFO) {
            "voice: parakeet runtime loaded engineId=${engine.engineId.value} vocab=${vocab.count}"
        }
        val speechToText =
            ParakeetEouSpeechToText(
                engineId = engine.engineId,
                decoderFactory = decoderFactory(runtime, vocab),
                config = adapterConfig,
                diagnostics = diagnostics,
                clock = clock,
                decodeDispatcher = decodeDispatcher,
            )
        return OnnxParakeetEouSession(
            speechToText = speechToText,
            turnDetector = ParakeetEouTurnDetectorFactory.forEngine(speechToText, clock = clock),
            runtime = runtime,
        )
    }

    /** Reads the pinned `vocab.json`; a missing/corrupt file is a typed load failure. */
    private fun loadVocabulary(directory: File): ParakeetVocabulary {
        val file = File(directory, VOCAB_FILE_NAME)
        check(file.isFile) { "missing Parakeet-EOU $VOCAB_FILE_NAME" }
        val vocab = ParakeetVocabulary.fromJson(file.readText())
        val expected = ParakeetVocabulary.expectedCount(config)
        check(vocab.count == expected) {
            "Parakeet-EOU $VOCAB_FILE_NAME has ${vocab.count} entries, expected $expected"
        }
        return vocab
    }

    /** One fresh streaming decoder per recognition session, sharing the loaded runtime. */
    private fun decoderFactory(
        runtime: OnnxParakeetEouRuntime,
        vocab: ParakeetVocabulary,
    ): ParakeetDecoderSessionFactory =
        ParakeetDecoderSessionFactory {
            ParakeetDecoderSessions.ofDecoder(ParakeetStreamingDecoder(vocab, config, runtime))
        }

    private companion object {
        const val VOCAB_FILE_NAME = "vocab.json"
    }
}

/**
 * A loaded Parakeet-EOU session: the STT adapter, its EOU corroborator, and the
 * one shared ONNX runtime they both decode through.
 *
 * [close] releases the three ONNX graphs and marks the STT adapter closed, and is
 * idempotent. The adapter's own `close()` is a `suspend` call but its body is a
 * cheap atomic flag flip; it is bridged here with [runBlocking] so the session can
 * be an `AutoCloseable` the assembly closes in a non-suspending `finally`.
 */
internal class OnnxParakeetEouSession(
    override val speechToText: SpeechToText,
    override val turnDetector: TurnCompletionDetector,
    private val runtime: AutoCloseable,
) : ParakeetEouSession {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        runCatching { runBlocking { speechToText.close() } }
        runCatching { runtime.close() }
    }
}
