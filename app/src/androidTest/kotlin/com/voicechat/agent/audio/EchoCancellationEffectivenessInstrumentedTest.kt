package com.voicechat.agent.audio

import android.Manifest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import android.media.AudioFormat as AndroidAudioFormat

/**
 * Opt-in device probe (R-0254): how much of the assistant's own speech the
 * microphone still hears under different capture configurations.
 *
 * It speaks a fixed phrase through the platform TTS and measures the residual
 * frame RMS at the microphone for four configurations — the app's current one
 * (`VOICE_RECOGNITION`, `MODE_NORMAL`, no AEC), the same with AEC enabled, and
 * two `MODE_IN_COMMUNICATION` variants that AEC is documented to need. The
 * numbers decide whether the fix is a better capture configuration or a
 * playback-aware onset threshold; nothing here asserts, it only records.
 *
 * Requires a **quiet room** (no human speech) and plays audio. Opt in with
 * `-e echoProbe=1`, so routine connected runs stay silent.
 */
@RunWith(AndroidJUnit4::class)
class EchoCancellationEffectivenessInstrumentedTest {
    @get:Rule
    val grantMicrophone: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun measuresTheTtsResidualThroughTheMicrophone() =
        runBlocking {
            assumeTrue(
                "opt-in device probe: pass -e echoProbe=1 to run it",
                InstrumentationRegistry.getArguments().getString(ARG_ENABLE) == "1",
            )
            val audioManager = context.getSystemService(AudioManager::class.java)
            val originalMode = audioManager.mode
            val tts = createTextToSpeech()
            try {
                for (config in CONFIGS) {
                    val stats = measure(config, tts, audioManager)
                    Log.i(
                        TAG,
                        "ECHO ${config.label} mean=${fmt(stats.mean)} max=${fmt(stats.max)} p95=${fmt(stats.p95)} " +
                            "frames=${stats.frames}",
                    )
                    Thread.sleep(700)
                }
            } finally {
                audioManager.mode = originalMode
                runCatching { tts.shutdown() }
            }
        }

    private fun measure(
        config: Config,
        tts: TextToSpeech,
        audioManager: AudioManager,
    ): Stats {
        audioManager.mode = config.mode
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufferBytes = maxOf(minBuffer, FRAME_SAMPLES * 2 * 8)
        val record =
            try {
                AudioRecord(config.source, SAMPLE_RATE, CHANNEL, ENCODING, bufferBytes)
            } catch (failure: Throwable) {
                Log.w(TAG, "ECHO ${config.label} could not open: ${failure.javaClass.simpleName}")
                return Stats(0.0, 0.0, 0.0, 0)
            }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            Log.w(TAG, "ECHO ${config.label} did not initialize")
            return Stats(0.0, 0.0, 0.0, 0)
        }
        var canceller: AcousticEchoCanceler? = null
        return try {
            record.startRecording()
            if (config.aec && AcousticEchoCanceler.isAvailable()) {
                canceller = AcousticEchoCanceler.create(record.audioSessionId)?.apply { setEnabled(true) }
            }
            tts.speak(PHRASE, TextToSpeech.QUEUE_FLUSH, null, "echo-probe")
            val rmsValues = mutableListOf<Double>()
            val buffer = ShortArray(FRAME_SAMPLES)
            val deadline = System.nanoTime() + MEASURE_NANOS
            while (System.nanoTime() < deadline) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                var sum = 0.0
                for (index in 0 until read) {
                    val sample = buffer[index] / FULL_SCALE
                    sum += sample * sample
                }
                rmsValues += sqrt(sum / read)
            }
            Stats.of(rmsValues)
        } finally {
            runCatching { canceller?.release() }
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    private fun createTextToSpeech(): TextToSpeech {
        val latch = CountDownLatch(1)
        val tts = TextToSpeech(context) { latch.countDown() }
        latch.await(5, TimeUnit.SECONDS)
        tts.language = Locale.US
        return tts
    }

    private data class Config(
        val label: String,
        val source: Int,
        val mode: Int,
        val aec: Boolean,
    )

    private data class Stats(
        val mean: Double,
        val max: Double,
        val p95: Double,
        val frames: Int,
    ) {
        companion object {
            /** Mean/max/p95 of the frames that actually carry audio (above the noise floor). */
            fun of(values: List<Double>): Stats {
                val voiced = values.filter { it > NOISE_FLOOR }.sorted()
                if (voiced.isEmpty()) return Stats(0.0, 0.0, 0.0, values.size)
                val mean = voiced.average()
                val p95 = voiced[(voiced.size * 95 / 100).coerceIn(0, voiced.lastIndex)]
                return Stats(mean, voiced.last(), p95, values.size)
            }

            private const val NOISE_FLOOR = 0.002
        }
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.5f", value)

    private companion object {
        const val TAG = "EchoProbe"
        const val ARG_ENABLE = "echoProbe"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AndroidAudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AndroidAudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SAMPLES = 320
        const val FULL_SCALE = 32768.0
        const val MEASURE_NANOS = 3_000_000_000L
        const val PHRASE = "The quick brown fox jumps over the lazy dog, and then it runs away again."

        val CONFIGS =
            listOf(
                Config(
                    "VOICE_RECOGNITION/MODE_NORMAL/aec=off",
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    AudioManager.MODE_NORMAL,
                    false,
                ),
                Config("VOICE_RECOGNITION/MODE_NORMAL/aec=on", MediaRecorder.AudioSource.VOICE_RECOGNITION, AudioManager.MODE_NORMAL, true),
                Config(
                    "VOICE_RECOGNITION/MODE_IN_COMM/aec=on",
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    AudioManager.MODE_IN_COMMUNICATION,
                    true,
                ),
                Config(
                    "VOICE_COMMUNICATION/MODE_IN_COMM/aec=on",
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    AudioManager.MODE_IN_COMMUNICATION,
                    true,
                ),
            )
    }
}
