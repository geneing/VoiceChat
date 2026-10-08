package com.voicechat.agent.stt.parakeet

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicechat.agent.contracts.SttEvent
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device regression for the live-microphone level problem.
 *
 * The pinned Parakeet-EOU model feeds raw log-mel with no input normalization, so a
 * quiet capture decodes to nothing. Device measurement (2026-10-03) showed the model
 * produced **no tokens** for a clip attenuated to ~0.37 peak, while the same clip at
 * ~0.47+ peak decoded. This test takes a staged clip, attenuates it into that dead
 * zone, and proves the **adapter's** [ParakeetInputGain] recovers it while a
 * pass-through adapter does not.
 *
 * Opt-in: skipped unless `-e parakeetQuietRunId <id>` is passed and
 * `files/mic-fixtures/probe.wav` is staged. It reads no microphone.
 */
@RunWith(AndroidJUnit4::class)
class ParakeetEouQuietClipInstrumentedTest {
    @Test
    fun adapterNormalizationRecoversAQuietClipThatRawDecodeDrops() =
        runBlocking {
            val runId = InstrumentationRegistry.getArguments().getString(ARG_RUN_ID)
            assumeTrue("set $ARG_RUN_ID to opt in", !runId.isNullOrBlank())
            requireNotNull(runId)
            require(RUN_ID.matches(runId)) { "invalid run id" }

            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val fixture = File(File(context.filesDir, FIXTURE_DIRECTORY), FIXTURE_FILE)
            assumeTrue("stage $FIXTURE_FILE first", fixture.isFile)
            val samples = readWav(fixture)
            // Attenuate into the measured dead zone. The level is overridable so a
            // sweep can locate the raw-decode cliff and prove normalization recovers
            // it, rather than passing trivially at a level raw still decodes.
            val quietGain =
                InstrumentationRegistry
                    .getArguments()
                    .getString(ARG_QUIET_GAIN)
                    ?.toFloatOrNull()
                    ?: QUIET_GAIN
            val quiet = ShortArray(samples.size) { (samples[it] * quietGain).toInt().coerceIn(-32768, 32767).toShort() }

            val rawText = withContext(Dispatchers.IO) { decode(context, quiet, normalize = false) }
            val normalizedText = withContext(Dispatchers.IO) { decode(context, quiet, normalize = true) }
            Log.i(
                TAG,
                "QUIET_CLIP runId=$runId gain=$quietGain rawChars=${rawText.length} " +
                    "normalizedChars=${normalizedText.length} raw=${rawText.take(60)} " +
                    "normalized=${normalizedText.take(120)}",
            )

            // Precondition: at this attenuation the raw path decodes nothing, so the
            // assertion below proves the gain is what recovers the text.
            assertTrue(
                "the raw pass-through decode should drop this quiet clip (got ${rawText.length} chars)",
                rawText.isBlank(),
            )
            assertTrue("the normalized adapter should recover text from a quiet clip", normalizedText.isNotBlank())
        }

    private fun decode(
        context: android.content.Context,
        samples: ShortArray,
        normalize: Boolean,
    ): String {
        val modelDir = File(context.filesDir, "parakeet-eou")
        if (!modelDir.isDirectory) return ""
        val config = ParakeetEouConfig.pinned()
        val vocab = ParakeetVocabulary.fromJson(File(modelDir, "vocab.json").readText())
        val runtime = OnnxParakeetEouRuntime.load(modelDir, config)
        val inputGain = if (normalize) ParakeetInputGain() else ParakeetInputGain.PASSTHROUGH
        val adapter =
            ParakeetEouSpeechToText(
                decoderFactory =
                    ParakeetDecoderSessionFactory {
                        ParakeetDecoderSessions.ofDecoder(ParakeetStreamingDecoder(vocab, config, runtime))
                    },
                inputGain = inputGain,
            )
        try {
            val finalText =
                runBlocking {
                    adapter
                        .transcribe(pacedFrames(samples))
                        .filterIsInstance<SttEvent.Result>()
                        .toList()
                        .lastOrNull { it.transcript.isFinal }
                        ?.transcript
                        ?.text
                        .orEmpty()
                }
            return finalText
        } finally {
            runBlocking { runCatching { adapter.close() } }
            runtime.close()
        }
    }

    /** Delivers the clip as 20 ms capture frames, paced so the bounded chunk queue never drops. */
    private fun pacedFrames(samples: ShortArray): Flow<AudioFrame> =
        flow {
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + FRAME_SAMPLES, samples.size)
                emit(AudioFrame(AudioFormat.MONO_16_KHZ, samples.copyOfRange(offset, end), 0L))
                offset = end
                delay(FRAME_PACING_MILLIS)
            }
        }

    private fun readWav(file: File): ShortArray {
        val bytes = file.readBytes()
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "not RIFF" }
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

    private companion object {
        const val TAG = "ParakeetQuiet"
        const val ARG_RUN_ID = "parakeetQuietRunId"
        const val ARG_QUIET_GAIN = "parakeetQuietGain"
        const val FIXTURE_DIRECTORY = "mic-fixtures"
        const val FIXTURE_FILE = "probe.wav"
        const val FRAME_SAMPLES = 320
        const val FRAME_PACING_MILLIS = 6L

        // Stage a microphone-captured clip (files/mic-fixtures/probe.wav). At -26 dB
        // (peak ~0.06) the raw pass-through decode drops the clip entirely; the
        // adapter's normalization recovers it. A measured sweep on Pixel 10
        // (2026-10-07) put the raw cliff between 0.09 peak (decodes) and 0.06 peak
        // (no tokens), so this default sits safely below it.
        const val QUIET_GAIN = 0.05f
        val RUN_ID = Regex("[0-9a-f]{32}")
    }
}
