package com.voicechat.agent.stt.parakeet

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicechat.agent.stt.AndroidM4aReplayDecoder
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
 * On-device accuracy/latency measurement for the Parakeet-EOU streaming engine
 * (content-free timing rows; see `docs/parakeet-eou-streaming.md`).
 *
 * It exercises the **real** ONNX decoder through the same
 * [ParakeetStreamingDecoder] the app uses, chunking each staged clip into
 * `chunkSamples` windows and decoding them as fast as the device can, so the
 * measured real-time factor is a genuine device number. It is opt-in: without
 * `-e parakeetEouRunId <id>` the test is skipped, and it never runs on a clean
 * checkout.
 *
 * It writes content-free rows only (`file_id`, durations, chunk decode time,
 * RTF, partial/EOU counts, outcome). Transcripts are written to a **separate,
 * Git-ignored** draft-labels sidecar only when `-e parakeetEouExportDraftLabels
 * true` is passed, exactly like the other replay harnesses; they are never
 * ground truth.
 */
@RunWith(AndroidJUnit4::class)
class ParakeetEouReplayInstrumentedTest {
    @Test
    fun streamingAudioFilesProduceContentFreeTimingRows() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            val runId = arguments.getString(ARG_RUN_ID)
            assumeTrue("set $ARG_RUN_ID to opt in to the Parakeet-EOU replay", !runId.isNullOrBlank())
            requireNotNull(runId)
            require(RUN_ID.matches(runId)) { "invalid replay run id" }

            val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
            val replayDir = File(File(targetContext.cacheDir, REPLAY_DIRECTORY), runId)
            assertTrue("replay directory is missing", replayDir.isDirectory)

            val config = ParakeetEouConfig.pinned()
            val vocab = ParakeetVocabulary.fromJson(File(File(targetContext.filesDir, MODEL_DIRECTORY), VOCAB_FILE).readText())

            val files =
                replayDir
                    .listFiles { file -> file.isFile && file.extension.lowercase(Locale.ROOT) in SUPPORTED_EXTENSIONS }
                    ?.sortedBy { it.name }
                    .orEmpty()
            assertTrue("no WAV or M4A files were staged for replay", files.isNotEmpty())

            val exportLabels = arguments.getString(ARG_EXPORT_LABELS)?.toBoolean() == true

            // Model load once, measured; the runtime is reused across clips (a warm
            // engine), which is the steady-state the voice loop actually runs in.
            val loadStartedAt = SystemClock.elapsedRealtimeNanos()
            var loadFailure: String? = null
            val runtime =
                try {
                    withContext(Dispatchers.IO) {
                        OnnxParakeetEouRuntime.load(File(targetContext.filesDir, MODEL_DIRECTORY), config)
                    }
                } catch (failure: Throwable) {
                    loadFailure = failure.javaClass.simpleName
                    null
                }
            val loadMillis = elapsedMillis(loadStartedAt, SystemClock.elapsedRealtimeNanos())

            val rows =
                try {
                    files.map { file -> replayOne(file, config, vocab, runtime, loadMillis, loadFailure) }
                } finally {
                    runtime?.close()
                }

            File(replayDir, RESULTS_FILE).writeText(CSV_HEADER + "\n" + rows.joinToString("\n", postfix = "\n") { it.toCsv() })
            if (exportLabels) {
                File(replayDir, DRAFT_LABELS_FILE).writeText(
                    DRAFT_LABELS_HEADER + "\n" + rows.joinToString("\n", postfix = "\n") { it.toDraftCsv() },
                )
            }
            rows.forEach { row -> Log.i(TAG, "PARAKEET_EOU_REPLAY,${row.fileId},${row.outcome}") }
        }

    private suspend fun replayOne(
        file: File,
        config: ParakeetEouConfig,
        vocab: ParakeetVocabulary,
        runtime: OnnxParakeetEouRuntime?,
        loadMillis: Long,
        loadFailure: String?,
    ): ReplayRow {
        val fileId = file.nameWithoutExtension.takeIf { SAFE_FILE_ID.matches(it) } ?: "invalid-file-id"
        var audioDecodeMillis: Long? = null
        var inputDurationMillis: Long? = null
        var chunkDecodeMillis: Long? = null
        var chunks: Int? = null
        var chunksWithText: Int? = null
        var eouCommits: Int? = null
        var firstPartialChunk: Int? = null
        var transcript: String? = null
        var outcome = if (loadFailure == null) "DECODE_FAILED" else "MODEL_OPEN_FAILED"
        var errorCode: String? = loadFailure

        if (runtime != null) {
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
                val waveform = FloatArray(decoded.samples.size) { index -> decoded.samples[index] / PCM_NORMALIZATION }
                try {
                    val decoder = ParakeetStreamingDecoder(vocab, config, runtime)
                    val chunkSamples = config.chunkSamples
                    val chunkTimesNanos = mutableListOf<Long>()
                    var chunkCount = 0
                    var withText = 0
                    var eou = 0
                    var firstText: Int? = null
                    var offset = 0
                    // Feed only NEW samples per step, exactly as the live adapter
                    // (ParakeetEouSpeechToText) does: the decoder buffers them and
                    // owns the 5120-sample window advance internally. Feeding a full
                    // overlapping 10240-sample window every 5120 samples here would
                    // double-overlap the encoder input and duplicate every token.
                    while (offset < waveform.size) {
                        val end = minOf(offset + config.shiftSamples, waveform.size)
                        val window = waveform.copyOfRange(offset, end)
                        val startedAt = SystemClock.elapsedRealtimeNanos()
                        val result = decoder.accept(window)
                        chunkTimesNanos += SystemClock.elapsedRealtimeNanos() - startedAt
                        chunkCount++
                        if (result.text.isNotBlank()) {
                            withText++
                            if (firstText == null) firstText = chunkCount
                        }
                        if (result.eouDetected || result.isFinal) eou++
                        offset = end
                    }
                    val tail = decoder.finalize()
                    if (tail.text.isNotBlank() && firstText == null) firstText = chunkCount + 1
                    if (tail.isFinal) eou++
                    chunks = chunkCount
                    chunksWithText = withText
                    eouCommits = eou
                    firstPartialChunk = firstText
                    transcript = if (tail.text.isNotBlank()) tail.text else null
                    chunkDecodeMillis = chunkTimesNanos.sum() / 1_000_000L
                    outcome = "COMPLETED"
                    errorCode = null
                } catch (failure: Throwable) {
                    errorCode = failure.javaClass.simpleName
                    outcome = "DECODE_FAILED"
                }
            }
        }

        val realTimeFactor =
            if (inputDurationMillis != null && chunkDecodeMillis != null && inputDurationMillis > 0) {
                chunkDecodeMillis.toDouble() / inputDurationMillis.toDouble()
            } else {
                null
            }
        return ReplayRow(
            fileId = fileId,
            inputDurationMillis = inputDurationMillis,
            modelOpenMillis = loadMillis,
            audioDecodeMillis = audioDecodeMillis,
            chunkDecodeMillis = chunkDecodeMillis,
            chunks = chunks,
            chunksWithText = chunksWithText,
            eouCommits = eouCommits,
            firstPartialChunk = firstPartialChunk,
            realTimeFactor = realTimeFactor,
            outcome = outcome,
            errorCode = errorCode,
            transcript = transcript,
        )
    }

    private fun elapsedMillis(
        fromNanos: Long,
        toNanos: Long,
    ): Long = (toNanos - fromNanos) / 1_000_000L

    /**
     * Minimal 16-bit PCM WAV reader (mono or interleaved), mirroring the other
     * replay harnesses. The staged fixtures are 16 kHz mono 16-bit PCM.
     */
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
                        readUInt16Le(input) // audio format
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
            require(channels > 0 && sampleRate > 0 && dataOffset >= 0) { "WAV is missing fmt/data" }
            require(sampleRate == SAMPLE_RATE_HZ) { "WAV must be 16 kHz, was $sampleRate" }
            input.seek(dataOffset)
            val frameBytes = dataLength.toInt()
            val bytes = ByteArray(frameBytes)
            input.readFully(bytes)
            val shorts = ShortArray(frameBytes / 2)
            var index = 0
            var byteIndex = 0
            while (byteIndex + 1 < frameBytes) {
                shorts[index++] = ((bytes[byteIndex].toInt() and 0xFF) or (bytes[byteIndex + 1].toInt() shl 8)).toShort()
                byteIndex += 2
            }
            val monoSamples =
                if (channels == 1) {
                    shorts
                } else {
                    ShortArray(shorts.size / channels) { frame -> shorts[frame * channels] }
                }
            return AndroidM4aReplayDecoder.PcmAudio(monoSamples)
        }
    }

    private fun readFourCc(input: RandomAccessFile): String {
        val bytes = ByteArray(4)
        input.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readUInt32Le(input: RandomAccessFile): Long {
        val b0 = input.read()
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        require(b0 or b1 or b2 or b3 >= 0) { "WAV is truncated" }
        return (b0.toLong() and 0xFF) or ((b1.toLong() and 0xFF) shl 8) or
            ((b2.toLong() and 0xFF) shl 16) or ((b3.toLong() and 0xFF) shl 24)
    }

    private fun readUInt16Le(input: RandomAccessFile): Int {
        val b0 = input.read()
        val b1 = input.read()
        require(b0 or b1 >= 0) { "WAV is truncated" }
        return (b0 and 0xFF) or ((b1 and 0xFF) shl 8)
    }

    private data class ReplayRow(
        val fileId: String,
        val inputDurationMillis: Long?,
        val modelOpenMillis: Long,
        val audioDecodeMillis: Long?,
        val chunkDecodeMillis: Long?,
        val chunks: Int?,
        val chunksWithText: Int?,
        val eouCommits: Int?,
        val firstPartialChunk: Int?,
        val realTimeFactor: Double?,
        val outcome: String,
        val errorCode: String?,
        val transcript: String?,
    ) {
        fun toCsv(): String =
            listOf(
                fileId,
                ENGINE_ID,
                MODEL_ID,
                inputDurationMillis?.toString().orEmpty(),
                modelOpenMillis.toString(),
                audioDecodeMillis?.toString().orEmpty(),
                chunkDecodeMillis?.toString().orEmpty(),
                chunks?.toString().orEmpty(),
                chunksWithText?.toString().orEmpty(),
                eouCommits?.toString().orEmpty(),
                firstPartialChunk?.toString().orEmpty(),
                realTimeFactor?.let { String.format(Locale.US, "%.4f", it) }.orEmpty(),
                outcome,
                errorCode.orEmpty(),
            ).joinToString(",")

        fun toDraftCsv(): String =
            listOf(
                fileId,
                transcript.orEmpty(),
                LABEL_ORIGIN,
                VERIFICATION_STATUS,
                outcome,
                errorCode.orEmpty(),
            ).joinToString(",")
    }

    private companion object {
        const val TAG = "ParakeetEouReplay"
        const val ARG_RUN_ID = "parakeetEouRunId"
        const val ARG_EXPORT_LABELS = "parakeetEouExportDraftLabels"
        const val REPLAY_DIRECTORY = "voicechat-stt-replay"
        const val MODEL_DIRECTORY = "parakeet-eou"
        const val VOCAB_FILE = "vocab.json"
        const val RESULTS_FILE = "parakeet-eou-results.csv"
        const val DRAFT_LABELS_FILE = "parakeet-eou-draft-labels.csv"
        const val ENGINE_ID = "parakeet-eou-onnx"
        const val MODEL_ID = "parakeet-eou-120m-onnx-int8"
        const val PCM_NORMALIZATION = 32_768f
        const val SAMPLE_RATE_HZ = 16_000
        const val LABEL_ORIGIN = "on_device_model"
        const val VERIFICATION_STATUS = "unverified_draft"

        val RUN_ID = Regex("[0-9a-f]{32}")
        val SAFE_FILE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        val SUPPORTED_EXTENSIONS = setOf("wav", "m4a")

        const val CSV_HEADER =
            "file_id,engine_id,model_id,input_duration_ms,model_open_ms,audio_decode_ms," +
                "chunk_decode_ms,chunks,chunks_with_text,eou_commits,first_partial_chunk,real_time_factor,outcome,error_code"
        const val DRAFT_LABELS_HEADER = "file_id,draft_transcript,label_origin,verification_status,outcome,error_code"
    }
}
