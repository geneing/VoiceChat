package com.voicechat.agent.audio

import android.media.AudioAttributes
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicechat.agent.domain.AudioFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import android.media.AudioFormat as AndroidAudioFormat

/**
 * Diagnostic: captures real microphone audio while the device speaker plays a
 * staged WAV, then writes the capture to app-private cache for offline analysis.
 *
 * It exists to separate "the live microphone path delivers decodable speech" from
 * "the recognizer receives quiet/garbled audio" when the file-driven harness works
 * but live input does not. Opt-in: skipped unless `-e micLoopbackRunId <id>` is
 * passed and `files/mic-fixtures/probe.wav` is staged. Never runs in a normal
 * connected test run.
 */
@RunWith(AndroidJUnit4::class)
class MicLoopbackCaptureInstrumentedTest {
    @Test
    fun capturesSpeakerPlaybackThroughTheMicrophone() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            val runId = arguments.getString(ARG_RUN_ID)
            assumeTrue("set $ARG_RUN_ID to opt in to the microphone loopback", !runId.isNullOrBlank())
            requireNotNull(runId)
            require(RUN_ID.matches(runId)) { "invalid run id" }

            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val fixture = File(File(context.filesDir, FIXTURE_DIRECTORY), FIXTURE_FILE)
            assumeTrue("stage $FIXTURE_FILE first", fixture.isFile)
            val playback = readWav(fixture)
            val playbackMillis = playback.size.toLong() * 1000L / SAMPLE_RATE_HZ

            val capture =
                MicrophoneAudioCapture.create(
                    context = context,
                    audioSource = MediaRecorder.AudioSource.MIC,
                )
            val collected = ArrayList<Short>(SAMPLE_RATE_HZ * 12)
            val captureJob =
                launch(Dispatchers.IO) {
                    capture.frames().collect { frame: AudioFrame -> synchronized(collected) { collected.addAll(frame.samples.toList()) } }
                }

            try {
                delay(CAPTURE_LEAD_IN_MILLIS)
                playThroughSpeaker(context, playback, playbackMillis)
                delay(CAPTURE_TAIL_MILLIS)
            } finally {
                captureJob.cancel()
                withContext(Dispatchers.IO) { runCatching { capture.close() } }
                val captured = synchronized(collected) { collected.toShortArray() }
                val outFile = File(context.cacheDir, "mic-loopback-$runId.wav")
                writeWav(outFile, captured)
                val stats = rmsAndPeak(captured)
                val decoded =
                    withTimeoutOrNull(5_000L) {
                        withContext(Dispatchers.IO) { decodeWithParakeet(context, captured) }
                    }
                Log.i(
                    TAG,
                    "MIC_LOOPBACK file=${outFile.absolutePath} frames=${captured.size} " +
                        "seconds=${captured.size / SAMPLE_RATE_HZ} rms=${stats.rms} peak=${stats.peak} " +
                        "decodedChars=${decoded?.length ?: -1} decoded=${decoded.orEmpty()}",
                )
                assertTrue("capture produced no samples", captured.isNotEmpty())
            }
        }

    private suspend fun playThroughSpeaker(
        context: android.content.Context,
        samples: ShortArray,
        playbackMillis: Long,
    ) {
        // Never raise the device volume here: playback uses whatever level the
        // device is already at, so a diagnostic run cannot blast the speaker.
        val minBytes =
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AndroidAudioFormat.CHANNEL_OUT_MONO,
                AndroidAudioFormat.ENCODING_PCM_16BIT,
            )
        val track =
            AudioTrack
                .Builder()
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).setAudioFormat(
                    AndroidAudioFormat
                        .Builder()
                        .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(AndroidAudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                ).setBufferSizeInBytes(maxOf(minBytes, samples.size * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        try {
            track.write(samples, 0, samples.size)
            track.play()
            delay(playbackMillis + 400L)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun decodeWithParakeet(
        context: android.content.Context,
        samples: ShortArray,
    ): String {
        val modelDir = File(context.filesDir, "parakeet-eou")
        if (!modelDir.isDirectory) return ""
        val config =
            com.voicechat.agent.stt.parakeet.ParakeetEouConfig
                .pinned()
        val vocab =
            com.voicechat.agent.stt.parakeet.ParakeetVocabulary
                .fromJson(File(modelDir, "vocab.json").readText())
        val runtime =
            com.voicechat.agent.stt.parakeet.OnnxParakeetEouRuntime
                .load(modelDir, config)
        try {
            val decoder =
                com.voicechat.agent.stt.parakeet
                    .ParakeetStreamingDecoder(vocab, config, runtime)
            val waveform = FloatArray(samples.size) { samples[it] / 32_768f }
            var offset = 0
            while (offset < waveform.size) {
                val end = minOf(offset + config.shiftSamples, waveform.size)
                decoder.accept(waveform.copyOfRange(offset, end))
                offset = end
            }
            return decoder.finalize().text
        } finally {
            runtime.close()
        }
    }

    private data class Stats(
        val rms: Double,
        val peak: Double,
    )

    private fun rmsAndPeak(samples: ShortArray): Stats {
        if (samples.isEmpty()) return Stats(0.0, 0.0)
        var sum = 0.0
        var peak = 0.0
        for (sample in samples) {
            val value = sample / 32_768.0
            sum += value * value
            val abs = kotlin.math.abs(value)
            if (abs > peak) peak = abs
        }
        return Stats(sqrt(sum / samples.size), peak)
    }

    private fun readWav(file: File): ShortArray {
        val bytes = file.readBytes()
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "not RIFF" }
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "not WAVE" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var dataOffset = -1
        var dataLength = -1
        var cursor = 12
        while (cursor + 8 <= bytes.size) {
            val id = String(bytes, cursor, 4, Charsets.US_ASCII)
            val size = buffer.getInt(cursor + 4)
            if (id == "data") {
                dataOffset = cursor + 8
                dataLength = size
            }
            cursor = cursor + 8 + size + (size and 1)
        }
        require(dataOffset >= 0 && dataLength > 0) { "no PCM data" }
        val count = minOf(dataLength, bytes.size - dataOffset) / 2
        val data = ByteBuffer.wrap(bytes, dataOffset, count * 2).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(count) { data.short }
    }

    private fun writeWav(
        file: File,
        samples: ShortArray,
    ) {
        val dataBytes = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(SAMPLE_RATE_HZ)
        header.putInt(SAMPLE_RATE_HZ * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes)
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            out.write(header.array())
            val bytes = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) bytes.putShort(sample)
            out.write(bytes.array())
        }
    }

    private companion object {
        const val TAG = "MicLoopback"
        const val ARG_RUN_ID = "micLoopbackRunId"
        const val FIXTURE_DIRECTORY = "mic-fixtures"
        const val FIXTURE_FILE = "probe.wav"
        const val SAMPLE_RATE_HZ = 16_000
        const val CAPTURE_LEAD_IN_MILLIS = 1_000L
        const val CAPTURE_TAIL_MILLIS = 1_500L
        val RUN_ID = Regex("[0-9a-f]{32}")
    }
}
