package com.voicechat.agent.voice

import android.content.Context
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicechat.agent.contracts.AudioInput
import com.voicechat.agent.contracts.ConversationRepository
import com.voicechat.agent.contracts.DiagnosticEvent
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.TurnCompletionDetector
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.ConversationSummary
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.log.AndroidLogging
import com.voicechat.agent.log.LogLevel
import com.voicechat.agent.stt.AndroidM4aReplayDecoder
import com.voicechat.agent.stt.AndroidSpeechRecognizerSpeechToText
import com.voicechat.agent.stt.SttEngine
import com.voicechat.agent.stt.SttMode
import com.voicechat.agent.turn.OnnxSmartTurnEngine
import com.voicechat.agent.turn.SmartTurnDetectorFactory
import com.voicechat.agent.turn.SmartTurnEngineFactory
import com.voicechat.agent.turn.SmartTurnModelStore
import com.voicechat.agent.vad.EndpointHoldReason
import com.voicechat.agent.vad.TurnDetectionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * On-device harness that drives a staged audio file through the **real** voice
 * pipeline — capture → VAD/onset → platform `SpeechRecognizer` STT → bounded
 * endpointing (with optional Smart Turn) — and records the timing of every stage.
 *
 * It exists to measure the latency story the product treats as a primary
 * requirement (`docs/voice-quality-and-latency.md`) from a replayed recording
 * instead of a live microphone:
 *
 * - **time to first partial** — capture start (and VAD speech onset) to the first
 *   interim transcript revision;
 * - **end of speech → final** — the VAD endpoint (the end-of-speech authority)
 *   and the recognizer's own `onEndOfSpeech` to the final committed transcript;
 * - **input end → final** — the last real-audio frame to the final transcript;
 * - per-stage VAD, Smart Turn, and STT diagnostics.
 *
 * The clip is fed as 20 ms / 320-sample frames **paced to real time**, exactly
 * like [com.voicechat.agent.audio.MicrophoneAudioCapture], followed by a bounded
 * tail of silence so the bounded silence cap commits the turn in the ordinary
 * way instead of through the capture-ended path. The recognizer reads those
 * frames through `RecognizerIntent.EXTRA_AUDIO_SOURCE`, the same path the live
 * app uses. The recorder writes content-free rows: no transcript text, only
 * character counts and stable codes.
 *
 * With `-e sttHarnessLogTranscripts true` the harness additionally logs each
 * partial revision and the final committed text to logcat (`SttHarness` tag,
 * `STT_HARNESS_TRANSCRIPT`); the CSV reports stay content-free either way.
 *
 * Opt-in: skipped unless `-e sttHarnessRunId <id>` is passed, the clip is staged
 * under `cache/<STAGE_DIRECTORY>/<id>/`, and the device exposes an on-device
 * recognizer. `-e sttHarnessSmartTurn true` enables the Smart Turn corroborator
 * when its model is installed; otherwise the VAD-only bounded endpoint runs.
 */
@RunWith(AndroidJUnit4::class)
class SttPipelineHarnessInstrumentedTest {
    @Test
    fun replayedAudioReportsPerStageSttVadAndSmartTurnTiming() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            val runId = arguments.getString(ARG_RUN_ID)
            assumeTrue("set $ARG_RUN_ID to opt in to the STT pipeline harness", !runId.isNullOrBlank())
            requireNotNull(runId)
            require(RUN_ID.matches(runId)) { "invalid harness run id" }

            // Debug builds log the per-callback decoder trace; make it explicit so
            // the harness always emits stage lines even if the Application did not.
            AndroidLogging.install(debug = true, minLevel = LogLevel.VERBOSE)

            val context = InstrumentationRegistry.getInstrumentation().targetContext
            assumeTrue(
                "no on-device recognizer on this device",
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context),
            )
            val runDir = File(File(context.cacheDir, STAGE_DIRECTORY), runId)
            assertTrue("harness stage directory is missing", runDir.isDirectory)

            val files =
                runDir
                    .listFiles { file -> file.isFile && file.extension.lowercase(Locale.ROOT) in SUPPORTED_EXTENSIONS }
                    ?.sortedBy { it.name }
                    .orEmpty()
            assertTrue("no WAV or M4A files were staged for the harness", files.isNotEmpty())

            val smartTurnRequested = arguments.getString(ARG_SMART_TURN)?.toBoolean() == true
            // Recognized text is logged to logcat only when explicitly opted in; the
            // CSV reports stay content-free either way.
            val logTranscripts = arguments.getString(ARG_LOG_TRANSCRIPTS)?.toBoolean() == true

            val rows =
                files.map { file ->
                    replayOne(
                        file = file,
                        context = context,
                        smartTurnRequested = smartTurnRequested,
                        logTranscripts = logTranscripts,
                    )
                }

            File(runDir, STAGES_FILE).writeText(
                "file_id,stage,wall_offset_ms,audio_offset_ms,detail\n" +
                    rows.joinToString("") { row -> row.stages.joinToString("") { it.toCsv(row.fileId) } },
            )
            File(runDir, SUMMARY_FILE).writeText(
                SUMMARY_HEADER + "\n" + rows.joinToString("\n", postfix = "\n") { it.toCsv() },
            )
            rows.forEach { row -> Log.i(TAG, row.toLogLine()) }
        }

    private suspend fun replayOne(
        file: File,
        context: Context,
        smartTurnRequested: Boolean,
        logTranscripts: Boolean,
    ): HarnessRow {
        val fileId = file.nameWithoutExtension.takeIf { SAFE_FILE_ID.matches(it) } ?: "invalid-file-id"
        val decoded =
            withContext(Dispatchers.IO) {
                runCatching { decodeAudio(file) }
            }
        val pcm =
            decoded.getOrElse { failure ->
                val code = (failure as? AndroidM4aReplayDecoder.DecodeException)?.code ?: "AUDIO_DECODE_FAILED"
                Log.w(TAG, "HARNESS ${file.name} decode failed code=$code")
                return HarnessRow.failure(fileId, code)
            }

        val timeline = CaptureTimeline()
        val recorder = HarnessRecorder(fileId, timeline, logTranscripts)
        val audioInput = PacedFileAudioInput(samples = pcm.samples, timeline = timeline)
        val diagnostics = RecordingDiagnosticsSink(recorder)
        val engine = SttEngine(SttMode.ANDROID_SPEECH, Locale.US)
        val adapter =
            AndroidSpeechRecognizerSpeechToText(
                context = context,
                engine = engine,
                diagnostics = diagnostics,
                clock = SystemMonotonicClock,
            )
        val semanticDetector = if (smartTurnRequested) buildSmartTurn(context, diagnostics) else null
        val turnDetector =
            RecordingVoiceTurnDetector(
                delegate =
                    PolicyVoiceTurnDetector.forRoute(
                        diagnostics = diagnostics,
                        clock = SystemMonotonicClock,
                        semanticDetector = semanticDetector,
                    ),
                recorder = recorder,
            )
        val repository = HarnessConversationRepository()
        val conversation =
            Conversation(
                id = ConversationId("stt-harness-$fileId"),
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
            )
        val coordinator =
            VoiceSessionCoordinator(
                audioInput = audioInput,
                repository = repository,
                speechToText = adapter,
                turnDetector = turnDetector,
                listener = HarnessListener(recorder),
                diagnostics = diagnostics,
                clock = SystemMonotonicClock,
                dispatcher = Dispatchers.Default,
                wallClock = System::currentTimeMillis,
            )

        val timeoutMillis = pcm.durationMillis * 2L + TAIL_SILENCE_MILLIS * 2L + FINALIZE_GRACE_MILLIS
        val runStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        try {
            val completed =
                withTimeoutOrNull(timeoutMillis) {
                    coordinator.run(conversation)
                } != null
            if (!completed) {
                recorder.stage("HARNESS_TIMEOUT", detail = "timeout_ms=$timeoutMillis")
                coordinator.stop()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            recorder.stage("HARNESS_FAILED", detail = failure.javaClass.simpleName)
        } finally {
            runCatching { coordinator.stop() }
            runCatching { semanticDetector?.close() }
            runCatching { adapter.close() }
            runCatching { audioInput.close() }
        }
        val wallTimeMillis = elapsedMillis(runStartedAtNanos, SystemClock.elapsedRealtimeNanos())
        return recorder.toRow(
            audioDurationMillis = pcm.durationMillis,
            smartTurn = semanticDetector != null,
            wallTimeMillis = wallTimeMillis,
        )
    }

    /** Loads the optional Smart Turn detector through the production provider path. */
    private suspend fun buildSmartTurn(
        context: Context,
        diagnostics: DiagnosticsSink,
    ): TurnCompletionDetector? =
        SmartTurnDetectorFactory(
            store = SmartTurnModelStore(File(context.filesDir, SmartTurnModelStore.DIRECTORY_NAME)),
            enabled = { true },
            engineFactory = SmartTurnEngineFactory { file -> OnnxSmartTurnEngine.load(file) },
            diagnostics = diagnostics,
            clock = SystemMonotonicClock,
        ).detector()

    private fun decodeAudio(file: File): AndroidM4aReplayDecoder.PcmAudio =
        when (file.extension.lowercase(Locale.ROOT)) {
            "wav" -> readPcmWav(file)
            "m4a" -> AndroidM4aReplayDecoder.decode(file)
            else -> throw AndroidM4aReplayDecoder.DecodeException("UNSUPPORTED_AUDIO_CONTAINER")
        }

    /** Minimal 16 kHz mono 16-bit PCM WAV reader, mirroring the other replay harnesses. */
    private fun readPcmWav(file: File): AndroidM4aReplayDecoder.PcmAudio {
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= 12L)
            require(readFourCc(input) == "RIFF")
            readUInt32Le(input)
            require(readFourCc(input) == "WAVE")
            var channels = -1
            var sampleRate = -1
            var dataOffset = -1L
            var dataLength = -1L
            while (input.filePointer + 8L <= input.length()) {
                val chunkId = readFourCc(input)
                val chunkLength = readUInt32Le(input)
                val chunkStart = input.filePointer
                require(chunkLength <= input.length() - chunkStart) { "WAV chunk exceeds file" }
                when (chunkId) {
                    "fmt " -> {
                        require(chunkLength >= 16L) { "WAV fmt chunk is truncated" }
                        readUInt16Le(input)
                        channels = readUInt16Le(input)
                        sampleRate = readUInt32Le(input).toInt()
                        input.skipBytes(6)
                    }

                    "data" -> {
                        dataOffset = chunkStart
                        dataLength = chunkLength
                    }
                }
                input.seek(chunkStart + chunkLength + (chunkLength and 1L))
            }
            require(channels == 1) { "WAV must be mono, was $channels" }
            require(sampleRate == SAMPLE_RATE_HZ) { "WAV must be 16 kHz, was $sampleRate" }
            require(dataOffset >= 0L && dataLength > 0L) { "WAV is missing its PCM data" }
            input.seek(dataOffset)
            val bytes = ByteArray(dataLength.toInt())
            input.readFully(bytes)
            val shorts = ShortArray(bytes.size / 2)
            var index = 0
            var byteIndex = 0
            while (byteIndex + 1 < bytes.size) {
                shorts[index++] = ((bytes[byteIndex].toInt() and 0xFF) or (bytes[byteIndex + 1].toInt() shl 8)).toShort()
                byteIndex += 2
            }
            return AndroidM4aReplayDecoder.PcmAudio(shorts)
        }
    }

    private fun readFourCc(input: RandomAccessFile): String {
        val bytes = ByteArray(4)
        input.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readUInt32Le(input: RandomAccessFile): Long {
        val b0 = input.read().toLong() and 0xFF
        val b1 = input.read().toLong() and 0xFF
        val b2 = input.read().toLong() and 0xFF
        val b3 = input.read().toLong() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readUInt16Le(input: RandomAccessFile): Int = (input.read() and 0xFF) or ((input.read() and 0xFF) shl 8)

    private fun elapsedMillis(
        startedAtNanos: Long,
        endedAtNanos: Long,
    ): Long = ((endedAtNanos - startedAtNanos).coerceAtLeast(0L)) / NANOS_PER_MILLI

    private companion object {
        const val TAG = "SttHarness"
        const val ARG_RUN_ID = "sttHarnessRunId"
        const val ARG_SMART_TURN = "sttHarnessSmartTurn"
        const val ARG_LOG_TRANSCRIPTS = "sttHarnessLogTranscripts"
        const val STAGE_DIRECTORY = "voicechat-stt-harness"
        const val SUMMARY_FILE = "summary.csv"
        const val STAGES_FILE = "stages.csv"
        const val ENGINE_ID = "android-speech-recognizer"
        const val MODEL_ID = "android-speech-on-device"
        const val FRAME_SAMPLES = 320 // 20 ms at 16 kHz
        const val SAMPLE_RATE_HZ = 16_000
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val TAIL_SILENCE_MILLIS = 3_500L
        const val FINALIZE_GRACE_MILLIS = 60_000L

        val SUMMARY_HEADER =
            listOf(
                "file_id",
                "engine_id",
                "model_id",
                "audio_duration_ms",
                "smart_turn",
                "capture_start_to_first_partial_ms",
                "onset_to_first_partial_ms",
                "input_end_to_final_ms",
                "vad_endpoint_to_final_ms",
                "end_of_speech_to_final_ms",
                "vad_onset_count",
                "vad_endpoint_count",
                "partial_count",
                "last_endpoint_reason",
                "final_chars",
                "wall_time_ms",
                "outcome",
                "error_code",
            ).joinToString(",")
        val RUN_ID = Regex("[0-9a-f]{32}")
        val SAFE_FILE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        val SUPPORTED_EXTENSIONS = setOf("wav", "m4a")
    }

    /**
     * Shared capture timeline the recorder reads to convert event wall time into
     * an offset from the first frame. `0` means "not started yet".
     */
    private class CaptureTimeline {
        @Volatile
        var startedAtNanos: Long = 0L

        @Volatile
        var inputEndedAtNanos: Long = 0L
    }

    /** Real-time paced file capture, followed by bounded trailing silence. */
    private class PacedFileAudioInput(
        private val samples: ShortArray,
        private val timeline: CaptureTimeline,
    ) : AudioInput {
        override val format: AudioFormat = AudioFormat.MONO_16_KHZ

        @Volatile
        private var closed = false

        override fun frames(): Flow<AudioFrame> =
            flow {
                val startedAt = SystemClock.elapsedRealtimeNanos()
                timeline.startedAtNanos = startedAt
                var nextFrameAt = startedAt
                var offset = 0
                while (offset < samples.size && !closed) {
                    awaitDeadline(nextFrameAt)
                    val end = minOf(offset + FRAME_SAMPLES, samples.size)
                    val count = end - offset
                    emit(AudioFrame(format, samples.copyOfRange(offset, end), SystemClock.elapsedRealtimeNanos()))
                    offset = end
                    nextFrameAt += samplesToNanos(count)
                }
                timeline.inputEndedAtNanos = SystemClock.elapsedRealtimeNanos()

                // Trailing silence keeps capture alive like a real microphone, so the
                // bounded silence cap (not capture end) commits the turn.
                val silenceFrames = (TAIL_SILENCE_MILLIS * SAMPLE_RATE_HZ / 1_000L / FRAME_SAMPLES).toInt()
                val silence = ShortArray(FRAME_SAMPLES)
                var remaining = silenceFrames
                while (remaining > 0 && !closed) {
                    awaitDeadline(nextFrameAt)
                    emit(AudioFrame(format, silence, SystemClock.elapsedRealtimeNanos()))
                    remaining--
                    nextFrameAt += samplesToNanos(FRAME_SAMPLES)
                }
            }

        override suspend fun close() {
            closed = true
        }

        private suspend fun awaitDeadline(deadlineNanos: Long) {
            while (true) {
                val remaining = deadlineNanos - SystemClock.elapsedRealtimeNanos()
                if (remaining <= 0L) return
                val remainingMillis = remaining / NANOS_PER_MILLI
                if (remainingMillis > 0L) {
                    delay(remainingMillis)
                } else {
                    yield()
                }
            }
        }

        private fun samplesToNanos(sampleCount: Int): Long = sampleCount.toLong() * NANOS_PER_SECOND / SAMPLE_RATE_HZ
    }

    /** Wraps the production detector and records each VAD stage on the way through. */
    private class RecordingVoiceTurnDetector(
        private val delegate: VoiceTurnDetector,
        private val recorder: HarnessRecorder,
    ) : VoiceTurnDetector {
        override fun detect(audio: Flow<AudioFrame>): Flow<TurnDetectionEvent> =
            delegate
                .detect(audio)
                .onEach { event -> recorder.recordVad(event) }
    }

    /** Forwards every privacy-safe diagnostic event to the recorder. */
    private class RecordingDiagnosticsSink(
        private val recorder: HarnessRecorder,
    ) : DiagnosticsSink {
        override fun record(event: DiagnosticEvent) = recorder.recordDiagnostic(event)
    }

    /** Records session-level STT and endpoint progress. */
    private class HarnessListener(
        private val recorder: HarnessRecorder,
    ) : VoiceSessionListener {
        override fun onSessionState(state: VoiceSessionState) {
            recorder.stage("SESSION_STATE", detail = state.name)
        }

        override fun onListeningStarted(turnId: TurnId) {
            recorder.stage("STT_LISTENING_STARTED", detail = turnId.value)
        }

        override fun onProvisionalTranscript(
            turnId: TurnId,
            text: String,
        ) {
            recorder.recordTranscript(text.length)
            recorder.logTranscript("partial", text)
        }

        override fun onSttEndOfSpeech(turnId: TurnId) {
            recorder.recordEndOfSpeech()
        }

        override fun onUtteranceCommitted(
            turnId: TurnId,
            transcript: com.voicechat.agent.domain.Transcript,
        ) {
            recorder.recordCommitted(transcript.text, transcript.isFinal)
            recorder.logTranscript("final", transcript.text)
        }

        override fun onConversationChanged(conversation: Conversation) {
            recorder.stage("CONVERSATION_CHANGED", detail = "turns=${conversation.turns.size}")
        }

        override fun onNoSpeech() {
            recorder.stage("STT_NO_SPEECH")
        }

        override fun onError(error: com.voicechat.agent.domain.VoiceAgentError) {
            recorder.recordError(error.code.name)
        }
    }

    /** Accumulates stage events and derives the timing row. */
    private class HarnessRecorder(
        private val fileId: String,
        private val timeline: CaptureTimeline,
        private val logTranscripts: Boolean = false,
    ) {
        private val lock = Any()
        private val events = mutableListOf<Stage>()

        @Volatile
        private var firstOnsetWallMillis: Long? = null

        @Volatile
        private var firstTranscriptWallMillis: Long? = null

        @Volatile
        private var committedWallMillis: Long? = null

        @Volatile
        private var lastEndpointWallMillis: Long? = null

        @Volatile
        private var endOfSpeechWallMillis: Long? = null

        @Volatile
        private var lastEndpointReason: String? = null

        @Volatile
        private var committedChars: Int? = null

        @Volatile
        private var errorCode: String? = null

        @Volatile
        private var onsetCount: Int = 0

        @Volatile
        private var endpointCount: Int = 0

        @Volatile
        private var transcriptCount: Int = 0

        fun stage(
            name: String,
            wallNanos: Long = SystemClock.elapsedRealtimeNanos(),
            audioOffsetMillis: Long? = null,
            detail: String = "",
        ) {
            val wallOffset = wallOffsetMillis(wallNanos)
            val event = Stage(name, wallOffset, audioOffsetMillis, detail)
            synchronized(lock) { events += event }
            Log.i(
                TAG,
                "STT_HARNESS file=$fileId stage=$name wall_offset_ms=${wallOffset ?: -1} " +
                    "audio_offset_ms=${audioOffsetMillis ?: -1} $detail",
            )
        }

        fun recordVad(event: TurnDetectionEvent) {
            when (event) {
                is TurnDetectionEvent.Activity -> {
                    if (event.activity == com.voicechat.agent.contracts.SpeechActivity.SPEECH_STARTED) {
                        synchronized(lock) { onsetCount++ }
                        if (firstOnsetWallMillis == null) firstOnsetWallMillis = wallOffsetMillis()
                    }
                    stage(
                        name = "VAD_${event.activity.name}",
                        audioOffsetMillis = event.atOffsetMillis,
                        detail = "reason=${event.reason.name}",
                    )
                }

                is TurnDetectionEvent.Held -> {
                    val semantic = event.reason == EndpointHoldReason.SEMANTIC_INCOMPLETE
                    stage(
                        name = "VAD_HELD",
                        audioOffsetMillis = event.atOffsetMillis,
                        detail = "reason=${event.reason.name} smart_turn_incomplete=$semantic",
                    )
                }

                is TurnDetectionEvent.Endpointed -> {
                    synchronized(lock) { endpointCount++ }
                    lastEndpointWallMillis = wallOffsetMillis()
                    lastEndpointReason = event.reason.name
                    stage(
                        name = "VAD_ENDPOINT",
                        audioOffsetMillis = event.atOffsetMillis,
                        detail = "reason=${event.reason.name}",
                    )
                }

                is TurnDetectionEvent.Failed -> {
                    errorCode = event.error.code.name
                    stage(
                        name = "VAD_FAILED",
                        audioOffsetMillis = event.atOffsetMillis,
                        detail = "code=${event.error.code.name}",
                    )
                }
            }
        }

        fun recordDiagnostic(event: DiagnosticEvent) {
            when (event.stage) {
                DiagnosticStage.TURN_DETECTION, DiagnosticStage.SPEECH_TO_TEXT -> {
                    val detail =
                        event.attributes.entries.joinToString(" ") { (key, value) ->
                            "${key.name.lowercase(Locale.ROOT)}=$value"
                        }
                    stage(
                        name = "DIAG_${event.stage.name}_${event.outcome.name}",
                        detail = detail,
                    )
                    if (event.stage == DiagnosticStage.SPEECH_TO_TEXT && event.outcome.name == "FAILED") {
                        errorCode = event.attributes[com.voicechat.agent.contracts.DiagnosticAttribute.ERROR_CODE] ?: errorCode
                    }
                }

                else -> {
                    Unit
                }
            }
        }

        fun recordEndOfSpeech() {
            if (endOfSpeechWallMillis == null) endOfSpeechWallMillis = wallOffsetMillis()
            stage("STT_END_OF_SPEECH")
        }

        fun recordTranscript(chars: Int) {
            synchronized(lock) { transcriptCount++ }
            if (firstTranscriptWallMillis == null) firstTranscriptWallMillis = wallOffsetMillis()
            stage("STT_TRANSCRIPT", detail = "chars=$chars")
        }

        /**
         * Logs recognized text to logcat when transcript logging is opted in. It
         * bypasses [stage] so the CSV reports stay content-free: only logcat, and
         * only for a deliberate developer run, carries the transcript.
         */
        fun logTranscript(
            kind: String,
            text: String,
        ) {
            if (!logTranscripts) return
            Log.i(
                TAG,
                "STT_HARNESS_TRANSCRIPT file=$fileId kind=$kind chars=${text.length} text=$text",
            )
        }

        fun recordCommitted(
            text: String,
            isFinal: Boolean,
        ) {
            committedWallMillis = wallOffsetMillis()
            committedChars = text.length
            stage("STT_COMMITTED", detail = "chars=${text.length} final=$isFinal")
        }

        fun recordError(code: String) {
            errorCode = code
            stage("STT_ERROR", detail = "code=$code")
        }

        fun toRow(
            audioDurationMillis: Long,
            smartTurn: Boolean,
            wallTimeMillis: Long,
        ): HarnessRow {
            val inputEndedAt = timeline.inputEndedAtNanos
            val inputEndWall = if (inputEndedAt > 0L) wallOffsetMillis(inputEndedAt) else null
            val firstTranscript = firstTranscriptWallMillis
            val onset = firstOnsetWallMillis
            val committed = committedWallMillis
            val endOfSpeech = endOfSpeechWallMillis
            return HarnessRow(
                fileId = fileId,
                audioDurationMillis = audioDurationMillis,
                smartTurn = smartTurn,
                captureStartToFirstPartialMillis = firstTranscript,
                onsetToFirstPartialMillis =
                    if (onset != null && firstTranscript != null) (firstTranscript - onset).coerceAtLeast(0L) else null,
                inputEndToFinalMillis =
                    if (inputEndWall != null && committed != null) {
                        (committed - inputEndWall).coerceAtLeast(0L)
                    } else {
                        null
                    },
                vadEndpointToFinalMillis =
                    if (lastEndpointWallMillis != null && committed != null) {
                        (committed - lastEndpointWallMillis!!).coerceAtLeast(0L)
                    } else {
                        null
                    },
                endOfSpeechToFinalMillis =
                    if (endOfSpeech != null && committed != null) {
                        (committed - endOfSpeech).coerceAtLeast(0L)
                    } else {
                        null
                    },
                vadOnsetCount = onsetCount,
                vadEndpointCount = endpointCount,
                partialCount = transcriptCount,
                lastEndpointReason = lastEndpointReason,
                finalChars = committedChars,
                wallTimeMillis = wallTimeMillis,
                outcome = outcome(),
                errorCode = errorCode,
                stages = synchronized(lock) { events.toList() },
            )
        }

        private fun outcome(): String =
            when {
                errorCode != null && committedChars == null -> "FAILED"
                committedChars != null -> "FINAL"
                else -> "NO_TRANSCRIPT"
            }

        private fun wallOffsetMillis(wallNanos: Long = SystemClock.elapsedRealtimeNanos()): Long? {
            val startedAt = timeline.startedAtNanos
            if (startedAt <= 0L) return null
            return ((wallNanos - startedAt).coerceAtLeast(0L)) / NANOS_PER_MILLI
        }

        private companion object {
            const val NANOS_PER_MILLI = 1_000_000L
        }
    }

    /** One staged pipeline run's derived timing row. */
    private data class HarnessRow(
        val fileId: String,
        val audioDurationMillis: Long?,
        val smartTurn: Boolean = false,
        val captureStartToFirstPartialMillis: Long? = null,
        val onsetToFirstPartialMillis: Long? = null,
        val inputEndToFinalMillis: Long? = null,
        val vadEndpointToFinalMillis: Long? = null,
        val endOfSpeechToFinalMillis: Long? = null,
        val vadOnsetCount: Int = 0,
        val vadEndpointCount: Int = 0,
        val partialCount: Int = 0,
        val lastEndpointReason: String? = null,
        val finalChars: Int? = null,
        val wallTimeMillis: Long = 0L,
        val outcome: String = "FAILED",
        val errorCode: String? = null,
        val stages: List<Stage> = emptyList(),
    ) {
        fun toCsv(): String =
            listOf(
                fileId,
                ENGINE_ID,
                MODEL_ID,
                audioDurationMillis.csv(),
                smartTurn.toString(),
                captureStartToFirstPartialMillis.csv(),
                onsetToFirstPartialMillis.csv(),
                inputEndToFinalMillis.csv(),
                vadEndpointToFinalMillis.csv(),
                endOfSpeechToFinalMillis.csv(),
                vadOnsetCount.toString(),
                vadEndpointCount.toString(),
                partialCount.toString(),
                lastEndpointReason.orEmpty(),
                finalChars?.toString().orEmpty(),
                wallTimeMillis.toString(),
                outcome,
                errorCode.orEmpty(),
            ).joinToString(",")

        fun toLogLine(): String =
            "STT_HARNESS_RESULT,file=$fileId,smart_turn=$smartTurn,audio_ms=${audioDurationMillis.csv()}," +
                "first_partial_ms=${captureStartToFirstPartialMillis.csv()}," +
                "onset_to_first_ms=${onsetToFirstPartialMillis.csv()}," +
                "input_end_to_final_ms=${inputEndToFinalMillis.csv()}," +
                "vad_end_to_final_ms=${vadEndpointToFinalMillis.csv()}," +
                "eos_to_final_ms=${endOfSpeechToFinalMillis.csv()}," +
                "outcome=$outcome,error_code=${errorCode.orEmpty()}"

        private fun Long?.csv(): String = this?.toString().orEmpty()

        companion object {
            fun failure(
                fileId: String,
                code: String,
            ): HarnessRow = HarnessRow(fileId = fileId, audioDurationMillis = null, outcome = "DECODE_FAILED", errorCode = code)
        }
    }

    /** One recorded stage event. */
    private data class Stage(
        val name: String,
        val wallOffsetMillis: Long?,
        val audioOffsetMillis: Long?,
        val detail: String,
    ) {
        fun toCsv(fileId: String): String =
            listOf(
                fileId,
                name,
                wallOffsetMillis?.toString().orEmpty(),
                audioOffsetMillis?.toString().orEmpty(),
                detail.replace(",", ";"),
            ).joinToString(",") + "\n"
    }

    /** Minimal in-memory repository; the harness only needs the committed turns. */
    private class HarnessConversationRepository : ConversationRepository {
        private val conversations =
            kotlinx.coroutines.flow.MutableStateFlow<Map<ConversationId, Conversation>>(emptyMap())

        override fun observeConversations(): Flow<List<ConversationSummary>> =
            conversations.map { stored ->
                stored.values.map {
                    ConversationSummary(
                        id = it.id,
                        updatedAtEpochMillis = it.updatedAtEpochMillis,
                        title = it.title,
                        turnCount = it.turns.size,
                    )
                }
            }

        override suspend fun load(id: ConversationId): Conversation? = conversations.value[id]

        override suspend fun save(conversation: Conversation) {
            conversations.value = conversations.value + (conversation.id to conversation)
        }

        override suspend fun delete(id: ConversationId) {
            conversations.value = conversations.value - id
        }
    }
}
