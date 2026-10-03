package com.voicechat.agent.eval.litert

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Debug-only LiteRT ASR corpus run for WER.
 *
 * It preprocesses each staged 16 kHz mono WAV clip on-device with the sample's
 * log-Mel front end ([LogMelSpectrogram]), runs Parakeet CTC and TDT with a
 * CPU-only allowlist, then decodes the token IDs to text with the sample
 * tokenizer JNI. Per-clip transcript sidecars are written to app-private storage
 * and pulled locally for scoring; decoded text is never logged.
 *
 * Clips and models are staged by `scripts/stage-litert-asr-inputs.sh`. A missing
 * model or tokenizer is a logged unavailability, not a failed run.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtParakeetCorpusInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun transcribesStagedClipsWhenModelsAreAvailable() {
        val modelDir = File(context.filesDir, "litert-asr")
        val clips = modelDir.listFiles { file -> file.name.endsWith(".wav") }.orEmpty().sortedBy { it.name }
        if (clips.isEmpty()) {
            Log.i(TAG, JSONObject().put("record", "litert_parakeet_corpus").put("outcome", "CLIPS_NOT_STAGED").toString())
            return
        }

        val tokenizer = loadTokenizer(modelDir)
        val families =
            listOf(
                Family(
                    "parakeet-ctc-0.6b-5s-i8",
                    "litert-asr/parakeet-ctc-0.6b-5s-i8.tflite",
                    ParakeetRunner.Mode.CTC,
                    "parakeet-ctc-tokenizer.json",
                ),
                Family(
                    "parakeet-tdt-0.6b-v3-5s-i8-stateful",
                    "litert-asr/parakeet-tdt-0.6b-v3-5s-i8-stateful.tflite",
                    ParakeetRunner.Mode.TDT,
                    "parakeet-tdt-tokenizer.json",
                ),
            )

        for (family in families) {
            val model = File(context.filesDir, family.modelPath)
            if (!model.isFile) {
                Log.i(
                    TAG,
                    JSONObject()
                        .put("record", "litert_parakeet_corpus")
                        .put("outcome", "MODEL_NOT_STAGED")
                        .put("family", family.label)
                        .toString(),
                )
                continue
            }
            val familyTokenizer = loadFamilyTokenizer(modelDir, family, tokenizer)
            var runner: ParakeetRunner? = null
            try {
                runner = ParakeetRunner(model, family.mode, accelerators = setOf(com.google.ai.edge.litert.Accelerator.CPU))
                for (clip in clips) {
                    runClip(family, clip, runner, familyTokenizer)
                }
            } catch (failure: Throwable) {
                Log.i(
                    TAG,
                    JSONObject()
                        .put("record", "litert_parakeet_corpus")
                        .put("outcome", "FAMILY_FAILED")
                        .put("family", family.label)
                        .put("failureType", failure.javaClass.name)
                        .put("failureMessage", failure.message ?: JSONObject.NULL)
                        .toString(),
                )
            } finally {
                runner?.close()
            }
        }
    }

    private fun runClip(
        family: Family,
        clip: File,
        runner: ParakeetRunner,
        tokenizer: LiteRtHuggingfaceTokenizer?,
    ) {
        try {
            val window = readParakeetPcmWindowWithLength(clip)
            val audio =
                if (family.mode == ParakeetRunner.Mode.TDT) {
                    window.samples.copyOf(minOf(window.validSamples, TDT_INPUT_SAMPLE_LIMIT))
                } else {
                    window.samples
                }
            val features = featureProcessor(family).process(audio)
            val start = SystemClock.elapsedRealtimeNanos()
            val result = runner.run(features)
            val totalMs = elapsedMs(start)
            val transcript = tokenizer?.decode(result.tokenIds).orEmpty()
            if (transcript.isNotEmpty()) {
                File(clip.parentFile, "${family.label}--${clip.name.removeSuffix(".wav")}.transcript.txt").writeText(transcript)
            }
            Log.i(
                TAG,
                JSONObject()
                    .put("record", "litert_parakeet_corpus")
                    .put("outcome", "CLIP_COMPLETED")
                    .put("family", family.label)
                    .put("clip", clip.name)
                    .put("inputSamples", audio.size)
                    .put("encodeMs", result.encodeMs)
                    .put("decodeMs", result.decodeMs)
                    .put("totalMs", totalMs)
                    .put("tokenCount", result.tokenIds.size)
                    .put("hasTranscript", transcript.isNotEmpty())
                    .toString(),
            )
        } catch (failure: Throwable) {
            Log.i(
                TAG,
                JSONObject()
                    .put("record", "litert_parakeet_corpus")
                    .put("outcome", "CLIP_FAILED")
                    .put("family", family.label)
                    .put("clip", clip.name)
                    .put("failureType", failure.javaClass.name)
                    .put("failureMessage", failure.message ?: JSONObject.NULL)
                    .toString(),
            )
        }
    }

    private fun loadTokenizer(modelDir: File): Boolean =
        try {
            LiteRtHuggingfaceTokenizer.load()
            true
        } catch (failure: Throwable) {
            Log.i(
                TAG,
                JSONObject()
                    .put("record", "litert_parakeet_corpus")
                    .put("outcome", "TOKENIZER_JNI_UNAVAILABLE")
                    .put("failureMessage", failure.message ?: JSONObject.NULL)
                    .toString(),
            )
            false
        }

    private fun loadFamilyTokenizer(
        modelDir: File,
        family: Family,
        jniLoaded: Boolean,
    ): LiteRtHuggingfaceTokenizer? {
        if (!jniLoaded) return null
        val file = File(modelDir, family.tokenizerName)
        if (!file.isFile) return null
        return runCatching { LiteRtHuggingfaceTokenizer.fromJson(file.readText()) }.getOrNull()
    }

    private fun elapsedMs(startNanos: Long) = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000L

    private data class Family(
        val label: String,
        val modelPath: String,
        val mode: ParakeetRunner.Mode,
        val tokenizerName: String,
    )

    private companion object {
        const val TAG = "LiteRtParakeetCorpus"
        val TDT_INPUT_SAMPLE_LIMIT = requireNotNull(LogMelConfig.parakeetTdt().inputSampleLimit)

        // CTC retains the established 80-mel front end. TDT matches HF Parakeet's
        // 128-bin, 400-sample symmetric-Hann window and NeMo mel-major layout.
        val FEATURE_EXTRACTORS =
            mapOf(
                ParakeetRunner.Mode.CTC to
                    LogMelSpectrogram(
                        LogMelConfig(nFft = 512, nMels = 80, nFrames = 500, transpose = true, preemphasis = 0.97f),
                    ),
                ParakeetRunner.Mode.TDT to
                    LogMelSpectrogram(LogMelConfig.parakeetTdt()),
            )

        // The sample's jlibrosa front end remains the CTC default when staged.
        // It cannot match the TDT 400-sample window/layout, so TDT always uses
        // the pure-Kotlin implementation above.
        val SAMPLE_EXTRACTORS: Map<ParakeetRunner.Mode, SampleMelProcessor>? =
            runCatching {
                mapOf(
                    ParakeetRunner.Mode.CTC to
                        SampleMelProcessor(
                            samplingRate = 16_000,
                            nFft = 512,
                            nMels = 80,
                            hopLength = 160,
                            nFrames = 500,
                            transpose = true,
                            preemphasis = 0.97f,
                            normType = "standard",
                        ),
                    ParakeetRunner.Mode.TDT to
                        SampleMelProcessor(
                            samplingRate = 16_000,
                            nFft = 512,
                            nMels = 128,
                            hopLength = 160,
                            nFrames = 500,
                            transpose = true,
                            preemphasis = 0.97f,
                            normType = "standard",
                        ),
                )
            }.getOrNull()
    }

    private fun featureProcessor(family: Family): FeatureSource =
        if (family.mode == ParakeetRunner.Mode.TDT) {
            FEATURE_EXTRACTORS.getValue(family.mode)
        } else {
            SAMPLE_EXTRACTORS?.get(family.mode) ?: FEATURE_EXTRACTORS.getValue(family.mode)
        }
}
