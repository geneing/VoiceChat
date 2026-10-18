package com.voicechat.agent.tts.kokoro

import android.content.Context
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.TextToSpeech
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.tts.EngineTextToSpeech
import com.voicechat.agent.tts.kokoro.phonemes.DefaultKokoroPhonemizer
import com.voicechat.agent.tts.normalization.TextNormalizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.Locale

/**
 * App-boundary factory for the distilled Kokoro path.
 *
 * The app-private store verifies the selected graph before the ONNX session is
 * opened. The seams are injectable so JVM callers can supply a fake state reader,
 * phonemizer, synthesizer loader, and sink without Android or native code.
 */
object KokoroTts {
    /**
     * Builds the user-approved model installer with injectable storage and source.
     *
     * Supplying both seams avoids reading Android context state, so JVM callers
     * can compose the installation path with fakes as well.
     */
    fun createModelInstaller(
        context: Context,
        store: KokoroModelStore? = null,
        source: KokoroModelSource? = null,
    ): KokoroModelInstaller {
        val resolvedStore =
            store ?: KokoroModelStore(
                File(context.applicationContext.filesDir, KokoroArtifact.DIRECTORY_NAME),
            )
        return DefaultKokoroModelInstaller(
            store = resolvedStore,
            source = source ?: OkHttpKokoroModelSource(KokoroArtifact.DEFAULT),
        )
    }

    /**
     * Builds the Kokoro engine and wraps it in the existing contract adapter.
     *
     * The default path uses only app-private model storage and an on-device sink;
     * it does not request a permission or transfer speech text/audio over the
     * network. [engineId] is retained for callers migrating from platform TTS.
     */
    fun create(
        context: Context,
        locale: Locale = Locale.getDefault(),
        voiceId: String? = null,
        engineId: String? = null,
        diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
        clock: MonotonicClock = SystemMonotonicClock,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        modelStateReader: KokoroModelStateReader? = null,
        phonemizer: KokoroPhonemizer? = null,
        synthesizerLoader: (suspend (KokoroGraphSource) -> KokoroSynthesizer)? = null,
        audioSink: KokoroAudioSink? = null,
        modelStoreFactory: ((File) -> KokoroModelStore)? = null,
    ): TextToSpeech {
        val appContext =
            if (modelStateReader == null || phonemizer == null || audioSink == null) {
                context.applicationContext
            } else {
                context
            }
        val modelStore =
            if (modelStateReader == null) {
                val root = File(appContext.filesDir, KokoroArtifact.DIRECTORY_NAME)
                // The asset opener is what lets a bundled graph be used at all: it
                // is the only Android touch point in the store.
                val assets = appContext.assets
                (
                    modelStoreFactory ?: { directory ->
                        KokoroModelStore(directory, openAsset = { path -> assets.open(path) })
                    }
                ).invoke(root)
            } else {
                null
            }
        val stateReader = modelStateReader ?: KokoroModelStateReader(requireNotNull(modelStore)::state)
        val resolvedPhonemizer =
            phonemizer
                ?: DefaultKokoroPhonemizer.fromAssets(
                    appContext.assets,
                    // The spoken path normalizes: dates, currency, units,
                    // abbreviations and initialisms become words before the G2P
                    // sees them (docs/text-normalization.md). The gate keeps a
                    // language this app cannot phonemize untouched.
                    TextNormalizer.forLanguageTag(locale.toLanguageTag()),
                )
        val resolvedSink = audioSink ?: AudioTrackKokoroSink()
        val store = modelStore
        val loadGraph: suspend (KokoroGraphSource) -> KokoroSynthesizer =
            synthesizerLoader ?: { source ->
                when (source) {
                    // An installed graph keeps its path so ONNX Runtime can map it.
                    is KokoroGraphSource.Installed -> {
                        OnnxKokoroEngine.load(source.file)
                    }

                    // A bundled asset has no path; hand its bytes to the runtime.
                    is KokoroGraphSource.Bundled -> {
                        OnnxKokoroEngine.load(requireNotNull(store).read(source))
                    }
                }
            }
        val engine =
            KokoroTtsEngine(
                locale = locale,
                voiceId = voiceId,
                preferredEngineId = engineId,
                modelStateReader = stateReader,
                phonemizer = resolvedPhonemizer,
                sink = resolvedSink,
                synthesizerLoader = loadGraph,
                diagnostics = diagnostics,
                clock = clock,
                dispatcher = dispatcher,
            )
        return EngineTextToSpeech(engine = engine, diagnostics = diagnostics, clock = clock)
    }
}
