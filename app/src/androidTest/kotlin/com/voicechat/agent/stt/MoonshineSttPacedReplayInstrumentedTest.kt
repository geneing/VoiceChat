package com.voicechat.agent.stt

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticEvent
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.SttEvent
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
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
import java.util.concurrent.CopyOnWriteArrayList

/** Opt-in, real-time-paced Pixel benchmark for the debug-only Moonshine candidates. */
@RunWith(AndroidJUnit4::class)
class MoonshineSttPacedReplayInstrumentedTest {
    @Test
    fun tinyAndSmallTranscribeTheSharedLocalReplayDirectory() =
        runBlocking {
            val runId = InstrumentationRegistry.getArguments().getString(ARG_RUN_ID)
            assumeTrue("set $ARG_RUN_ID to opt in to local audio replay", !runId.isNullOrBlank())
            requireNotNull(runId)
            require(RUN_ID.matches(runId)) { "invalid replay run id" }
            val exportDraftLabels =
                InstrumentationRegistry
                    .getArguments()
                    .getString(ARG_EXPORT_DRAFT_LABELS)
                    ?.toBoolean() == true

            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val replayDir = File(File(context.cacheDir, REPLAY_DIRECTORY), runId)
            assertTrue("replay directory is missing", replayDir.isDirectory)
            val files =
                replayDir
                    .listFiles { file ->
                        file.isFile && file.extension.lowercase(Locale.ROOT) in SUPPORTED_EXTENSIONS
                    }?.sortedBy { it.name }
                    .orEmpty()
            assertTrue("no WAV or M4A files were pushed for replay", files.isNotEmpty())

            val rows =
                buildList {
                    for (model in MoonshineSttModel.entries) {
                        val diagnostics = BenchmarkDiagnosticsSink()
                        val adapter = MoonshineSpeechToText(context, model, diagnostics = diagnostics)
                        try {
                            for (file in files) add(replayOne(adapter, model, file, diagnostics, exportDraftLabels))
                        } finally {
                            adapter.close()
                        }
                    }
                }
            File(replayDir, RESULTS_FILE).writeText(
                CSV_HEADER + "\n" + rows.joinToString("\n", postfix = "\n") { it.toCsv() },
            )
            val draftLabelsFile = File(replayDir, DRAFT_LABELS_FILE)
            if (exportDraftLabels) {
                draftLabelsFile.writeText(
                    DRAFT_LABELS_HEADER + "\n" + rows.joinToString("\n", postfix = "\n") { it.toDraftCsv() },
                )
            } else {
                // A reused run id must not make labels from an earlier opt-in run look current.
                draftLabelsFile.delete()
            }
            rows.forEach { Log.i(TAG, it.toLogLine()) }
        }

    private suspend fun replayOne(
        adapter: MoonshineSpeechToText,
        model: MoonshineSttModel,
        file: File,
        diagnostics: BenchmarkDiagnosticsSink,
        exportDraftLabels: Boolean,
    ): ReplayRow {
        val fileId = file.nameWithoutExtension.takeIf { SAFE_FILE_ID.matches(it) } ?: "invalid-file-id"
        val sessionStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        var firstPartialAtNanos: Long? = null
        var inputStartedAtNanos: Long? = null
        var inputEndedAtNanos: Long? = null
        var finalAtNanos: Long? = null
        var failureAtNanos: Long? = null
        var outcome = "DECODE_FAILED"
        var errorCode: String? = null
        var inputDurationMillis: Long? = null
        var draftTranscript: String? = null
        val diagnosticStartIndex = diagnostics.events.size

        val decoded =
            try {
                withContext(Dispatchers.IO) {
                    when (file.extension.lowercase(Locale.ROOT)) {
                        "wav" -> readPcmWav(file)
                        "m4a" -> AndroidM4aReplayDecoder.decode(file)
                        else -> throw ReplayDecodeException("UNSUPPORTED_AUDIO_CONTAINER")
                    }
                }
            } catch (failure: AndroidM4aReplayDecoder.DecodeException) {
                errorCode = failure.code
                null
            } catch (failure: ReplayDecodeException) {
                errorCode = failure.code
                null
            } catch (_: Exception) {
                errorCode = "AUDIO_DECODE_FAILED"
                null
            }

        if (decoded != null) {
            inputDurationMillis = decoded.durationMillis
            val input =
                pacedFrames(decoded.samples) { startedAt, endedAt ->
                    inputStartedAtNanos = startedAt
                    inputEndedAtNanos = endedAt
                }
            val timeoutMillis = decoded.durationMillis * 3L + RECOGNITION_TIMEOUT_GRACE_MILLIS
            try {
                val completed =
                    withTimeoutOrNull(timeoutMillis) {
                        adapter.transcribe(input).collect { event ->
                            val eventAt = SystemClock.elapsedRealtimeNanos()
                            when (event) {
                                is SttEvent.Result -> {
                                    if (exportDraftLabels) draftTranscript = event.transcript.text
                                    if (event.transcript.isFinal) {
                                        finalAtNanos = eventAt
                                        outcome = "FINAL"
                                    } else if (firstPartialAtNanos == null) {
                                        firstPartialAtNanos = eventAt
                                    }
                                }

                                is SttEvent.Failed -> {
                                    failureAtNanos = eventAt
                                    errorCode = event.error.code.name
                                    outcome = "FAILED"
                                }

                                SttEvent.EndOfSpeech -> {
                                    Unit
                                }
                            }
                        }
                        true
                    } != null
                if (!completed) outcome = "TIMEOUT"
                if (completed && outcome != "FINAL" && outcome != "FAILED") outcome = "NO_TERMINAL_EVENT"
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                outcome = "ADAPTER_EXCEPTION"
                errorCode = "STT_RECOGNITION_FAILED"
                failureAtNanos = SystemClock.elapsedRealtimeNanos()
            }
        }

        val completedAtNanos = SystemClock.elapsedRealtimeNanos()
        val modelOpenMillis =
            diagnostics.events
                .drop(diagnosticStartIndex)
                .lastOrNull { it.attributes[DiagnosticAttribute.STREAM_STATE] == "model_loaded" }
                ?.durationNanos
                ?.div(NANOS_PER_MILLI)
        return ReplayRow(
            fileId = fileId,
            engineId = MoonshineSttModel.ENGINE_ID.value,
            modelId = model.modelId.value,
            mode = REPLAY_MODE,
            inputDurationMillis = inputDurationMillis,
            modelOpenMillis = modelOpenMillis,
            inputStartMillis = inputStartedAtNanos?.let { elapsedMillis(sessionStartedAtNanos, it) },
            firstPartialMillis = firstPartialAtNanos?.let { elapsedMillis(sessionStartedAtNanos, it) },
            inputEndMillis = inputEndedAtNanos?.let { elapsedMillis(sessionStartedAtNanos, it) },
            finalMillis = finalAtNanos?.let { elapsedMillis(sessionStartedAtNanos, it) },
            failureMillis = failureAtNanos?.let { elapsedMillis(sessionStartedAtNanos, it) },
            inputStartToFirstPartialMillis = elapsedMillisBetween(inputStartedAtNanos, firstPartialAtNanos),
            inputEndToFinalMillis = elapsedMillisBetween(inputEndedAtNanos, finalAtNanos),
            wallTimeMillis = elapsedMillis(sessionStartedAtNanos, completedAtNanos),
            realTimeFactor =
                inputDurationMillis
                    ?.takeIf { it > 0L }
                    ?.let { elapsedMillis(sessionStartedAtNanos, completedAtNanos).toDouble() / it },
            outcome = outcome,
            errorCode = errorCode,
            draftTranscript = draftTranscript,
        )
    }

    private fun pacedFrames(
        samples: ShortArray,
        onInputTimes: (startedAtNanos: Long, endedAtNanos: Long?) -> Unit,
    ): Flow<AudioFrame> =
        flow {
            val inputStartedAt = SystemClock.elapsedRealtimeNanos()
            onInputTimes(inputStartedAt, null)
            var nextFrameAt = inputStartedAt
            var sampleOffset = 0
            while (sampleOffset < samples.size) {
                awaitDeadline(nextFrameAt)
                val end = minOf(sampleOffset + FRAME_SIZE_SAMPLES, samples.size)
                val frameSampleCount = end - sampleOffset
                emit(
                    AudioFrame(
                        format = AudioFormat.MONO_16_KHZ,
                        samples = samples.copyOfRange(sampleOffset, end),
                        capturedAtNanos = SystemClock.elapsedRealtimeNanos(),
                    ),
                )
                val emittedAt = SystemClock.elapsedRealtimeNanos()
                sampleOffset = end
                nextFrameAt = emittedAt + samplesToNanos(frameSampleCount)
            }
            awaitDeadline(nextFrameAt)
            onInputTimes(inputStartedAt, SystemClock.elapsedRealtimeNanos())
        }

    private suspend fun awaitDeadline(deadlineNanos: Long) {
        while (true) {
            val remaining = deadlineNanos - SystemClock.elapsedRealtimeNanos()
            if (remaining <= 0L) return
            val remainingMillis = remaining / NANOS_PER_MILLI
            if (remainingMillis > 0L) delay(remainingMillis) else yield()
        }
    }

    private fun readPcmWav(file: File): AndroidM4aReplayDecoder.PcmAudio {
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= 12L)
            require(readFourCc(input) == "RIFF")
            readUInt32Le(input)
            require(readFourCc(input) == "WAVE")
            var encoding = -1
            var channels = -1
            var sampleRate = -1
            var bitsPerSample = -1
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
                        encoding = readUInt16Le(input)
                        channels = readUInt16Le(input)
                        sampleRate = readUInt32Le(input).toInt()
                        input.skipBytes(6)
                        bitsPerSample = readUInt16Le(input)
                    }

                    "data" -> {
                        dataOffset = chunkStart
                        dataLength = chunkLength
                    }
                }
                input.seek(chunkStart + chunkLength + (chunkLength and 1L))
            }
            require(
                encoding == PCM_ENCODING && channels == CHANNEL_COUNT &&
                    sampleRate == SAMPLE_RATE_HZ && bitsPerSample == BITS_PER_SAMPLE,
            ) {
                "WAV must be mono 16 kHz PCM16"
            }
            require(dataOffset >= 0L && dataLength >= 0L && dataLength % 2L == 0L && dataLength <= Int.MAX_VALUE) {
                "WAV has no valid PCM data"
            }
            input.seek(dataOffset)
            val bytes = ByteArray(dataLength.toInt())
            input.readFully(bytes)
            val samples = ShortArray(bytes.size / 2)
            for (index in samples.indices) {
                val low = bytes[index * 2].toInt() and 0xff
                val high = bytes[index * 2 + 1].toInt()
                samples[index] = ((high shl 8) or low).toShort()
            }
            return AndroidM4aReplayDecoder.PcmAudio(samples)
        }
    }

    private fun readFourCc(input: RandomAccessFile): String {
        val bytes = ByteArray(4)
        input.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readUInt16Le(input: RandomAccessFile): Int {
        val low = input.readUnsignedByte()
        val high = input.readUnsignedByte()
        return low or (high shl 8)
    }

    private fun readUInt32Le(input: RandomAccessFile): Long {
        val b0 = input.readUnsignedByte().toLong()
        val b1 = input.readUnsignedByte().toLong()
        val b2 = input.readUnsignedByte().toLong()
        val b3 = input.readUnsignedByte().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun samplesToNanos(sampleCount: Int): Long = sampleCount.toLong() * NANOS_PER_SECOND / SAMPLE_RATE_HZ

    private fun elapsedMillis(
        startedAtNanos: Long,
        endedAtNanos: Long,
    ): Long = ((endedAtNanos - startedAtNanos).coerceAtLeast(0L)) / NANOS_PER_MILLI

    private fun elapsedMillisBetween(
        startedAtNanos: Long?,
        endedAtNanos: Long?,
    ): Long? = if (startedAtNanos == null || endedAtNanos == null) null else elapsedMillis(startedAtNanos, endedAtNanos)

    private data class ReplayRow(
        val fileId: String,
        val engineId: String,
        val modelId: String,
        val mode: String,
        val inputDurationMillis: Long?,
        val modelOpenMillis: Long?,
        val inputStartMillis: Long?,
        val firstPartialMillis: Long?,
        val inputEndMillis: Long?,
        val finalMillis: Long?,
        val failureMillis: Long?,
        val inputStartToFirstPartialMillis: Long?,
        val inputEndToFinalMillis: Long?,
        val wallTimeMillis: Long,
        val realTimeFactor: Double?,
        val outcome: String,
        val errorCode: String?,
        val draftTranscript: String?,
    ) {
        fun toCsv(): String =
            listOf(
                fileId,
                engineId,
                modelId,
                mode,
                inputDurationMillis.csvValue(),
                modelOpenMillis.csvValue(),
                inputStartMillis.csvValue(),
                firstPartialMillis.csvValue(),
                inputEndMillis.csvValue(),
                finalMillis.csvValue(),
                failureMillis.csvValue(),
                inputStartToFirstPartialMillis.csvValue(),
                inputEndToFinalMillis.csvValue(),
                wallTimeMillis.toString(),
                realTimeFactor?.let { String.format(Locale.US, "%.3f", it) }.orEmpty(),
                outcome,
                errorCode.orEmpty(),
            ).joinToString(",")

        fun toDraftCsv(): String =
            listOf(
                csvCell(fileId),
                csvCell(engineId),
                csvCell(modelId),
                csvCell(mode),
                csvCell(draftTranscript),
                csvCell(DRAFT_LABEL_ORIGIN),
                csvCell(DRAFT_VERIFICATION_STATUS),
                csvCell(outcome),
                csvCell(errorCode),
            ).joinToString(",")

        fun toLogLine(): String = "MOONSHINE_STT_REPLAY,$fileId,$modelId,$outcome"

        private fun Long?.csvValue(): String = this?.toString().orEmpty()

        private fun csvCell(value: String?): String =
            "\"${value.orEmpty().replace("\"", "\"\"").replace("\r\n", "\n").replace('\r', '\n')}\""

        private companion object {
            const val DRAFT_LABEL_ORIGIN = "machine-generated"
            const val DRAFT_VERIFICATION_STATUS = "unverified"
        }
    }

    private class BenchmarkDiagnosticsSink : DiagnosticsSink {
        val events = CopyOnWriteArrayList<DiagnosticEvent>()

        override fun record(event: DiagnosticEvent) {
            events += event
        }
    }

    private class ReplayDecodeException(
        val code: String,
    ) : Exception()

    private companion object {
        const val TAG = "MoonshineSttReplay"
        const val ARG_RUN_ID = "sttReplayRunId"
        const val ARG_EXPORT_DRAFT_LABELS = "sttReplayExportDraftLabels"
        const val REPLAY_DIRECTORY = "voicechat-stt-replay"
        const val RESULTS_FILE = "moonshine-results.csv"
        const val DRAFT_LABELS_FILE = "moonshine-draft-labels.csv"
        const val REPLAY_MODE = "STREAMING"
        const val FRAME_SIZE_SAMPLES = 320
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16
        const val PCM_ENCODING = 1
        const val RECOGNITION_TIMEOUT_GRACE_MILLIS = 30_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        val SUPPORTED_EXTENSIONS = setOf("wav", "m4a")
        val RUN_ID = Regex("^[a-f0-9-]{8,64}$")
        val SAFE_FILE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        val CSV_HEADER =
            listOf(
                "file_id",
                "engine_id",
                "model_id",
                "mode",
                "input_duration_ms",
                "model_open_ms",
                "input_start_ms",
                "first_partial_ms",
                "input_end_ms",
                "final_ms",
                "failure_ms",
                "input_start_to_first_partial_ms",
                "input_end_to_final_ms",
                "wall_time_ms",
                "real_time_factor",
                "outcome",
                "error_code",
            ).joinToString(",")
        val DRAFT_LABELS_HEADER =
            listOf(
                "file_id",
                "engine_id",
                "model_id",
                "mode",
                "draft_transcript",
                "label_origin",
                "verification_status",
                "outcome",
                "error_code",
            ).joinToString(",")
    }
}
