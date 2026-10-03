package com.voicechat.agent.stt

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Optional on-device batch comparison for the offline Parakeet candidate.
 * This is deliberately separate from paced streaming STT: sherpa's selected
 * 110M model only returns a transcript after the full waveform is decoded.
 */
@RunWith(AndroidJUnit4::class)
class ParakeetOfflineReplayInstrumentedTest {
    @Test
    fun offlineAudioFilesProduceContentFreeTimingRows() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            val runId = arguments.getString(ARG_RUN_ID)
            assumeTrue("set $ARG_RUN_ID to opt in to the file replay", !runId.isNullOrBlank())
            requireNotNull(runId)
            require(RUN_ID.matches(runId)) { "invalid replay run id" }

            val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
            val replayDir = File(File(targetContext.cacheDir, REPLAY_DIRECTORY), runId)
            assertTrue("replay directory is missing", replayDir.isDirectory)
            val modelDir = File(targetContext.filesDir, MODEL_DIRECTORY)
            val modelFileName = arguments.getString(ARG_MODEL_FILE)?.takeIf { it.isNotBlank() } ?: MODEL_FILE
            val modelFile = File(modelDir, modelFileName)
            val tokensFile = File(modelDir, TOKENS_FILE)
            assertTrue("Parakeet model file is missing", modelFile.isFile)
            assertTrue("Parakeet token file is missing", tokensFile.isFile)

            val files =
                replayDir
                    .listFiles { file -> file.isFile && file.extension.lowercase(Locale.ROOT) in SUPPORTED_EXTENSIONS }
                    ?.sortedBy { it.name }
                    .orEmpty()
            assertTrue("no WAV or M4A files were staged for replay", files.isNotEmpty())

            val modelOpenStartedAt = SystemClock.elapsedRealtimeNanos()
            var modelOpenErrorCode: String? = null
            val recognizer =
                try {
                    withContext(Dispatchers.IO) {
                        OfflineRecognizer(
                            config =
                                OfflineRecognizerConfig(
                                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = 80),
                                    modelConfig =
                                        OfflineModelConfig(
                                            nemo = OfflineNemoEncDecCtcModelConfig(model = modelFile.absolutePath),
                                            tokens = tokensFile.absolutePath,
                                            numThreads = MODEL_THREAD_COUNT,
                                            provider = "cpu",
                                        ),
                                ),
                        )
                    }
                } catch (failure: Exception) {
                    modelOpenErrorCode = failure.javaClass.simpleName
                    null
                } catch (failure: LinkageError) {
                    modelOpenErrorCode = failure.javaClass.simpleName
                    null
                }
            val modelOpenMillis = elapsedMillis(modelOpenStartedAt, SystemClock.elapsedRealtimeNanos())
            val modelOpenFailure = if (recognizer == null) modelOpenErrorCode ?: "MODEL_OPEN_FAILED" else null
            val exportDraftLabels = arguments.getString(ARG_EXPORT_DRAFT_LABELS)?.toBoolean() == true
            val rows =
                try {
                    files.map { file -> replayOne(file, recognizer, modelOpenMillis, modelOpenFailure, exportDraftLabels) }
                } finally {
                    recognizer?.release()
                }

            File(replayDir, RESULTS_FILE).writeText(CSV_HEADER + "\n" + rows.joinToString("\n", postfix = "\n") { it.toCsv() })
            if (exportDraftLabels) {
                File(replayDir, DRAFT_LABELS_FILE).writeText(
                    DRAFT_LABELS_HEADER + "\n" + rows.joinToString("\n", postfix = "\n") { it.toDraftCsv() },
                )
            }
            rows.forEach { row -> Log.i(TAG, "PARAKEET_REPLAY,${row.fileId},${row.outcome}") }
        }

    private suspend fun replayOne(
        file: File,
        recognizer: OfflineRecognizer?,
        modelOpenMillis: Long,
        modelOpenFailure: String?,
        exportDraftLabels: Boolean,
    ): ReplayRow {
        val fileId = file.nameWithoutExtension.takeIf { SAFE_FILE_ID.matches(it) } ?: "invalid-file-id"
        val rowStartedAt = SystemClock.elapsedRealtimeNanos()
        var audioDecodeMillis: Long? = null
        var decodeMillis: Long? = null
        var inputDurationMillis: Long? = null
        var transcript: String? = null
        var outcome = if (modelOpenFailure == null) "DECODE_FAILED" else "MODEL_OPEN_FAILED"
        var errorCode: String? = modelOpenFailure

        if (recognizer != null) {
            val audioStartedAt = SystemClock.elapsedRealtimeNanos()
            val decoded =
                try {
                    withContext(Dispatchers.IO) {
                        when (file.extension.lowercase(Locale.ROOT)) {
                            "wav" -> readPcmWav(file)
                            "m4a" -> AndroidM4aReplayDecoder.decode(file)
                            else -> throw AndroidM4aReplayDecoder.DecodeException("UNSUPPORTED_AUDIO_CONTAINER")
                        }
                    }
                } catch (failure: AndroidM4aReplayDecoder.DecodeException) {
                    errorCode = failure.code
                    null
                } catch (_: Exception) {
                    errorCode = "AUDIO_DECODE_FAILED"
                    null
                }
            audioDecodeMillis = elapsedMillis(audioStartedAt, SystemClock.elapsedRealtimeNanos())

            if (decoded != null) {
                inputDurationMillis = decoded.durationMillis
                try {
                    val waveform = FloatArray(decoded.samples.size) { index -> decoded.samples[index] / PCM_NORMALIZATION }
                    decodeMillis =
                        withContext(Dispatchers.Default) {
                            val stream = recognizer.createStream()
                            try {
                                stream.acceptWaveform(waveform, SAMPLE_RATE_HZ)
                                val decodeStartedAt = SystemClock.elapsedRealtimeNanos()
                                recognizer.decode(stream)
                                val duration = elapsedMillis(decodeStartedAt, SystemClock.elapsedRealtimeNanos())
                                transcript = recognizer.getResult(stream).text
                                duration
                            } finally {
                                stream.release()
                            }
                        }
                    outcome = "FINAL"
                    errorCode = null
                } catch (failure: Exception) {
                    outcome = "FAILED"
                    errorCode = failure.javaClass.simpleName
                } catch (failure: LinkageError) {
                    outcome = "FAILED"
                    errorCode = failure.javaClass.simpleName
                }
            }
        }

        val wallTimeMillis = elapsedMillis(rowStartedAt, SystemClock.elapsedRealtimeNanos())
        return ReplayRow(
            fileId = fileId,
            inputDurationMillis = inputDurationMillis,
            modelOpenMillis = modelOpenMillis,
            audioDecodeMillis = audioDecodeMillis,
            decodeMillis = decodeMillis,
            wallTimeMillis = wallTimeMillis,
            realTimeFactor = inputDurationMillis?.takeIf { it > 0L }?.let { wallTimeMillis.toDouble() / it },
            decodeRealTimeFactor = inputDurationMillis?.takeIf { it > 0L }?.let { decodeMillis?.toDouble()?.div(it) },
            outcome = outcome,
            errorCode = errorCode,
            draftTranscript = transcript.takeIf { exportDraftLabels },
        )
    }

    private fun readPcmWav(file: File): AndroidM4aReplayDecoder.PcmAudio {
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= 12L)
            require(readFourCc(input) == "RIFF")
            readUInt32Le(input)
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
            return AndroidM4aReplayDecoder.PcmAudio(samples)
        }
    }

    private data class ReplayRow(
        val fileId: String,
        val inputDurationMillis: Long?,
        val modelOpenMillis: Long,
        val audioDecodeMillis: Long?,
        val decodeMillis: Long?,
        val wallTimeMillis: Long,
        val realTimeFactor: Double?,
        val decodeRealTimeFactor: Double?,
        val outcome: String,
        val errorCode: String?,
        val draftTranscript: String?,
    ) {
        fun toCsv(): String =
            listOf(
                csv(fileId),
                ENGINE_ID,
                MODEL_ID,
                "OFFLINE",
                inputDurationMillis.csvValue(),
                modelOpenMillis.toString(),
                audioDecodeMillis.csvValue(),
                decodeMillis.csvValue(),
                "", // Offline batch decoding has no first-partial event.
                "",
                "",
                wallTimeMillis.toString(),
                realTimeFactor.csvValue(),
                decodeRealTimeFactor.csvValue(),
                "false",
                outcome,
                errorCode.orEmpty(),
            ).joinToString(",")

        fun toDraftCsv(): String =
            listOf(
                csv(fileId),
                csv(ENGINE_ID),
                csv(MODEL_ID),
                csv("OFFLINE"),
                csv(draftTranscript),
                csv("machine-generated"),
                csv("unverified"),
                csv(outcome),
                csv(errorCode),
            ).joinToString(",")

        private fun Long?.csvValue(): String = this?.toString().orEmpty()

        private fun Double?.csvValue(): String = this?.let { String.format(Locale.US, "%.3f", it) }.orEmpty()

        private fun csv(value: String?): String = "\"${value.orEmpty().replace("\"", "\"\"").replace("\r\n", "\n").replace('\r', '\n')}\""
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

    private fun elapsedMillis(
        startedAtNanos: Long,
        endedAtNanos: Long,
    ): Long = ((endedAtNanos - startedAtNanos).coerceAtLeast(0L)) / NANOS_PER_MILLI

    private companion object {
        const val TAG = "VoiceChatSttReplay"
        const val ARG_RUN_ID = "sttReplayRunId"
        const val ARG_EXPORT_DRAFT_LABELS = "sttReplayExportDraftLabels"
        const val ARG_MODEL_FILE = "sttReplayParakeetModelFile"
        const val REPLAY_DIRECTORY = "voicechat-stt-replay"
        const val MODEL_DIRECTORY = "parakeet-tdt-ctc-110m-en-int8"
        const val MODEL_FILE = "model.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"
        const val RESULTS_FILE = "parakeet-results.csv"
        const val DRAFT_LABELS_FILE = "parakeet-draft-labels.csv"
        const val ENGINE_ID = "sherpa-onnx"
        const val MODEL_ID = "parakeet-tdt-ctc-110m-int8"
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16
        const val PCM_ENCODING = 1
        const val MODEL_THREAD_COUNT = 2
        const val PCM_NORMALIZATION = 32_768.0f
        const val NANOS_PER_MILLI = 1_000_000L
        val SUPPORTED_EXTENSIONS = setOf("wav", "m4a")
        val RUN_ID = Regex("^[0-9a-f]{32}$")
        val SAFE_FILE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        val CSV_HEADER =
            listOf(
                "file_id",
                "engine_id",
                "model_id",
                "mode",
                "input_duration_ms",
                "model_open_ms",
                "audio_decode_ms",
                "decode_ms",
                "first_partial_ms",
                "input_start_to_first_partial_ms",
                "input_end_to_final_ms",
                "wall_time_ms",
                "real_time_factor",
                "decode_real_time_factor",
                "streaming",
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
