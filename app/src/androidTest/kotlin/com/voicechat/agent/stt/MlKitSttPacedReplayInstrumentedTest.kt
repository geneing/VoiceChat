package com.voicechat.agent.stt

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicechat.agent.contracts.SttEvent
import com.voicechat.agent.debug.DebugM4aDecoder
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.ErrorCode
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
import kotlin.math.sqrt

/**
 * Opt-in, file-backed smoke benchmark for the real ML Kit STT adapter.
 *
 * The PowerShell driver copies WAV/M4A files into app-private cache and passes
 * a unique run id. Without that argument the test is skipped, so ordinary
 * connected test runs never replay personal audio. WAV samples, and M4A decoded
 * to PCM, are fed in 20 ms frames no faster than real time, as required by ML
 * Kit's custom-audio descriptor path. Transcript text is intentionally neither
 * logged nor saved.
 */
@RunWith(AndroidJUnit4::class)
class MlKitSttPacedReplayInstrumentedTest {
    @Test
    fun pacedAudioFilesProduceContentFreeTimingRows() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            val runId = arguments.getString(ARG_RUN_ID)
            assumeTrue("set $ARG_RUN_ID to opt in to the file replay", !runId.isNullOrBlank())

            requireNotNull(runId)
            require(RUN_ID.matches(runId)) { "invalid replay run id" }
            val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
            val replayDir = File(File(targetContext.cacheDir, REPLAY_DIRECTORY), runId)
            assertTrue("replay directory is missing", replayDir.isDirectory)

            val modeArgument = arguments.getString(ARG_MODE, "ADVANCED").uppercase(Locale.ROOT)
            val exportDraftLabels = arguments.getString(ARG_EXPORT_DRAFT_LABELS)?.toBoolean() == true
            val trimLeadingSilence = arguments.getString(ARG_TRIM_LEADING_SILENCE)?.toBoolean() == true
            val modes =
                when (modeArgument) {
                    "BASIC" -> listOf(SttMode.BASIC)
                    "ADVANCED" -> listOf(SttMode.ADVANCED)
                    "BOTH" -> listOf(SttMode.ADVANCED, SttMode.BASIC)
                    else -> error("unsupported STT mode '$modeArgument'")
                }
            val files =
                replayDir
                    .listFiles { file ->
                        file.isFile && file.extension.lowercase(Locale.ROOT) in SUPPORTED_EXTENSIONS
                    }?.sortedBy { it.name }
                    .orEmpty()
            assertTrue("no WAV or M4A files were pushed for replay", files.isNotEmpty())

            val rows =
                buildList {
                    for (mode in modes) {
                        for (file in files) {
                            add(replayOne(file, mode, trimLeadingSilence))
                        }
                    }
                }
            val resultFile = File(replayDir, RESULTS_FILE)
            resultFile.writeText(
                CSV_HEADER + "\n" + rows.joinToString(separator = "\n", postfix = "\n") { it.toCsv() },
            )
            if (exportDraftLabels) {
                val draftLabelsFile = File(replayDir, DRAFT_LABELS_FILE)
                draftLabelsFile.writeText(
                    DRAFT_LABELS_HEADER + "\n" +
                        rows.joinToString(separator = "\n", postfix = "\n") { it.toDraftCsv() },
                )
            }
            rows.forEach { row -> Log.i(TAG, row.toLogLine()) }
        }

    private suspend fun replayOne(
        file: File,
        mode: SttMode,
        trimLeadingSilence: Boolean,
    ): ReplayRow {
        val engine = SttEngine(mode, Locale.US)
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
        var sourceAudioDurationMillis: Long? = null
        var leadingTrimMillis: Long = 0L
        var draftTranscript: String? = null
        val exportDraftLabels =
            InstrumentationRegistry
                .getArguments()
                .getString(ARG_EXPORT_DRAFT_LABELS)
                ?.toBoolean() == true
        val decoded =
            try {
                withContext(Dispatchers.IO) {
                    when (file.extension.lowercase(Locale.ROOT)) {
                        "wav" -> readPcmWav(file)
                        "m4a" -> DebugM4aDecoder.decode(file)
                        else -> throw DebugM4aDecoder.DecodeException("UNSUPPORTED_AUDIO_CONTAINER")
                    }
                }
            } catch (failure: DebugM4aDecoder.DecodeException) {
                errorCode = failure.code
                null
            } catch (_: Exception) {
                errorCode = "AUDIO_DECODE_FAILED"
                null
            }
        if (decoded != null) {
            sourceAudioDurationMillis = decoded.durationMillis
            val fedAudio =
                if (trimLeadingSilence) {
                    trimLeadingSilence(decoded.samples)
                } else {
                    TrimmedAudio(decoded.samples, leadingSamples = 0)
                }
            inputDurationMillis = samplesToMillis(fedAudio.samples.size)
            leadingTrimMillis = samplesToMillis(fedAudio.leadingSamples)
            val input =
                pacedFrames(fedAudio.samples) { startedAt, endedAt ->
                    inputStartedAtNanos = startedAt
                    inputEndedAtNanos = endedAt
                }
            val adapter = MlKitSpeechToText(engine)
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
                                    errorCode = event.error.detail ?: event.error.code.name
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                outcome = "ADAPTER_EXCEPTION"
                errorCode = ErrorCode.STT_RECOGNITION_FAILED.name
                failureAtNanos = SystemClock.elapsedRealtimeNanos()
            } finally {
                adapter.close()
            }
        }

        val completedAtNanos = SystemClock.elapsedRealtimeNanos()
        return ReplayRow(
            fileId = fileId,
            mode = mode.name,
            engineId = engine.engineId.value,
            modelId = engine.modelId.value,
            inputDurationMillis = inputDurationMillis,
            sourceAudioDurationMillis = sourceAudioDurationMillis,
            leadingTrimMillis = leadingTrimMillis,
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

            // Keep the final pipe open through the last frame's audio interval.
            // Scheduling from each completed emit ensures a slow pipe never
            // causes later frames to burst faster than real time.
            awaitDeadline(nextFrameAt)
            onInputTimes(inputStartedAt, SystemClock.elapsedRealtimeNanos())
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

    private fun trimLeadingSilence(samples: ShortArray): TrimmedAudio {
        var consecutiveSpeechFrames = 0
        var candidateOnset = -1
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + FRAME_SIZE_SAMPLES, samples.size)
            var sumSquares = 0.0
            for (index in offset until end) {
                val sample = samples[index].toDouble()
                sumSquares += sample * sample
            }
            val rms = sqrt(sumSquares / (end - offset))
            if (rms >= LEADING_TRIM_SPEECH_RMS_THRESHOLD) {
                if (consecutiveSpeechFrames == 0) candidateOnset = offset
                consecutiveSpeechFrames++
                if (consecutiveSpeechFrames >= REQUIRED_SPEECH_FRAMES) {
                    val trimSamples = (candidateOnset - LEADING_TRIM_PREROLL_SAMPLES).coerceAtLeast(0)
                    return TrimmedAudio(samples.copyOfRange(trimSamples, samples.size), trimSamples)
                }
            } else {
                consecutiveSpeechFrames = 0
                candidateOnset = -1
            }
            offset = end
        }
        return TrimmedAudio(samples, leadingSamples = 0)
    }

    private fun samplesToMillis(sampleCount: Int): Long = sampleCount.toLong() * 1_000L / SAMPLE_RATE_HZ

    private fun readPcmWav(file: File): DebugM4aDecoder.PcmAudio {
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= 12L)
            require(readFourCc(input) == "RIFF")
            readUInt32Le(input) // RIFF container size
            require(readFourCc(input) == "WAVE")

            var encoding = -1
            var channelCount = -1
            var sampleRateHz = -1
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
                        require(chunkLength >= 16L) { "WAV format chunk is truncated" }
                        encoding = readUInt16Le(input)
                        channelCount = readUInt16Le(input)
                        sampleRateHz = readUInt32Le(input).toInt()
                        input.skipBytes(6) // byte rate and block alignment
                        bitsPerSample = readUInt16Le(input)
                    }

                    "data" -> {
                        dataOffset = chunkStart
                        dataLength = chunkLength
                    }
                }
                input.seek(chunkStart + chunkLength + (chunkLength and 1L))
            }

            require(encoding == PCM_ENCODING) { "WAV is not uncompressed PCM" }
            require(channelCount == CHANNEL_COUNT) { "WAV must be mono" }
            require(sampleRateHz == SAMPLE_RATE_HZ) { "WAV must be 16 kHz" }
            require(bitsPerSample == BITS_PER_SAMPLE) { "WAV must use 16-bit samples" }
            require(dataOffset >= 0L && dataLength >= 0L && dataLength % 2L == 0L) { "WAV has no valid PCM data" }
            require(dataLength <= Int.MAX_VALUE) { "WAV is too large for this replay test" }

            input.seek(dataOffset)
            val pcmBytes = ByteArray(dataLength.toInt())
            input.readFully(pcmBytes)
            val samples = ShortArray(pcmBytes.size / 2)
            for (index in samples.indices) {
                val low = pcmBytes[index * 2].toInt() and 0xff
                val high = pcmBytes[index * 2 + 1].toInt()
                samples[index] = ((high shl 8) or low).toShort()
            }
            return DebugM4aDecoder.PcmAudio(samples)
        }
    }

    private data class ReplayRow(
        val fileId: String,
        val mode: String,
        val engineId: String,
        val modelId: String,
        val inputDurationMillis: Long?,
        val sourceAudioDurationMillis: Long?,
        val leadingTrimMillis: Long,
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
                mode,
                engineId,
                modelId,
                inputDurationMillis.csvValue(),
                sourceAudioDurationMillis.csvValue(),
                leadingTrimMillis.toString(),
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
                csvCell(mode),
                csvCell(engineId),
                csvCell(modelId),
                csvCell(draftTranscript),
                csvCell(DRAFT_LABEL_ORIGIN),
                csvCell(DRAFT_VERIFICATION_STATUS),
                csvCell(outcome),
                csvCell(errorCode),
            ).joinToString(",")

        fun toLogLine(): String = "STT_REPLAY,$fileId,$mode,$outcome,leading_trim_ms=$leadingTrimMillis,error_code=${errorCode.orEmpty()}"

        private fun Long?.csvValue(): String = this?.toString().orEmpty()

        private fun csvCell(value: String?): String =
            "\"${value.orEmpty().replace("\"", "\"\"").replace("\r\n", "\n").replace('\r', '\n')}\""

        private companion object {
            const val DRAFT_LABEL_ORIGIN = "machine-generated"
            const val DRAFT_VERIFICATION_STATUS = "unverified"
        }
    }

    private data class TrimmedAudio(
        val samples: ShortArray,
        val leadingSamples: Int,
    )

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
    ): Long? =
        if (startedAtNanos == null || endedAtNanos == null) {
            null
        } else {
            elapsedMillis(startedAtNanos, endedAtNanos)
        }

    private companion object {
        const val TAG = "VoiceChatSttReplay"
        val CSV_HEADER =
            listOf(
                "file_id",
                "mode",
                "engine_id",
                "model_id",
                "input_duration_ms",
                "source_audio_duration_ms",
                "leading_trim_ms",
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
                "mode",
                "engine_id",
                "model_id",
                "draft_transcript",
                "label_origin",
                "verification_status",
                "outcome",
                "error_code",
            ).joinToString(",")
        const val ARG_RUN_ID = "sttReplayRunId"
        const val ARG_MODE = "sttReplayMode"
        const val ARG_EXPORT_DRAFT_LABELS = "sttReplayExportDraftLabels"
        const val ARG_TRIM_LEADING_SILENCE = "sttReplayTrimLeadingSilence"
        const val REPLAY_DIRECTORY = "voicechat-stt-replay"
        const val RESULTS_FILE = "results.csv"
        const val DRAFT_LABELS_FILE = "draft-labels.csv"
        const val FRAME_SIZE_SAMPLES = 320 // 20 ms at 16 kHz
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16
        const val PCM_ENCODING = 1
        const val RECOGNITION_TIMEOUT_GRACE_MILLIS = 30_000L
        const val LEADING_TRIM_SPEECH_RMS_THRESHOLD = 655.36
        const val REQUIRED_SPEECH_FRAMES = 3
        const val LEADING_TRIM_PREROLL_SAMPLES = 4_000 // 250 ms at 16 kHz
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        val SUPPORTED_EXTENSIONS = setOf("wav", "m4a")
        val RUN_ID = Regex("^[a-f0-9-]{8,64}$")
        val SAFE_FILE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
