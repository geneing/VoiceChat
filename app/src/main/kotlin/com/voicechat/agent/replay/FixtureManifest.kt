package com.voicechat.agent.replay

import com.voicechat.agent.contracts.SpeechActivity
import com.voicechat.agent.contracts.TurnCompletion
import com.voicechat.agent.domain.AudioFormat

/** Whether a fixture is harness-generated or a consenting human recording. */
enum class FixtureOrigin {
    /** Generated in-repo; used for broad deterministic coverage. */
    SYNTHETIC,

    /** A permissioned human recording admitted by the intake process. */
    HUMAN,
}

/**
 * Source identity for a fixture.
 *
 * [license] and [provenance] are mandatory so an unreviewed recording cannot be
 * quietly added: a human fixture must name its consent/license and intake
 * reference, and an unverified file has no valid manifest. See
 * `docs/audio-replay-harness.md`.
 */
data class FixtureSource(
    val origin: FixtureOrigin,
    val name: String,
    val description: String,
    val license: String,
    val provenance: String,
)

/**
 * Engine/model identity the fixture labels were produced against.
 *
 * For synthetic fixtures this names the harness generator; for human fixtures
 * it must name the actual STT engine and model that produced the labels.
 */
data class FixtureEngine(
    val engineId: String,
    val modelId: String,
    val version: String,
)

/** One expected transcript hypothesis at a replay frame offset. */
data class TranscriptLabel(
    val frameIndex: Int,
    val text: String,
    val isFinal: Boolean,
)

/** One expected speech-activity transition at a replay frame offset. */
data class VadLabel(
    val frameIndex: Int,
    val activity: SpeechActivity,
)

/** Expected labels for a fixture. */
data class FixtureLabels(
    val finalTranscript: String,
    val transcriptRevisions: List<TranscriptLabel>,
    val vadEvents: List<VadLabel>,
    val turnCompletions: List<TurnCompletion>,
)

/**
 * Complete, replayable fixture manifest.
 *
 * It carries everything needed to identify and reproduce a clip: source
 * identity, sample rate/format, generator engine/model, expected transcript and
 * boundary labels, the transformation chain with parameters, and the random
 * seed. [pcmSha256] pins the exact committed bytes.
 */
data class FixtureManifest(
    val id: String,
    val source: FixtureSource,
    val format: AudioFormat,
    val engine: FixtureEngine,
    val frameSizeSamples: Int,
    val sampleCount: Int,
    val pcmSha256: String,
    val seed: Long,
    val labels: FixtureLabels,
    val transformations: List<AudioTransformation>,
) {
    init {
        require(id.isNotBlank()) { "fixture id must not be blank" }
        require(frameSizeSamples > 0) { "frameSizeSamples must be positive" }
        require(sampleCount >= 0) { "sampleCount must not be negative" }
        require(pcmSha256.isNotBlank()) { "pcmSha256 must not be blank" }
    }
}

/**
 * Deterministic text codec for [FixtureManifest].
 *
 * The format is a single `key=value` per line (the value is everything after
 * the first `=`, so transcripts may contain `=`). Labels, transformations, and
 * their parameters are recorded in a fixed order so encoding the same manifest
 * twice always produces identical text. Comments start with `#`.
 *
 * No JSON dependency is used: the manifest must round-trip on the JVM with no
 * added runtime library, and a frozen fixture's sidecar file must stay stable.
 */
object FixtureManifestCodec {
    const val FORMAT_VERSION: Int = 1

    fun encode(manifest: FixtureManifest): String {
        val lines = mutableListOf<String>()
        lines += "# VoiceChat deterministic replay fixture manifest (v$FORMAT_VERSION)."
        lines += "manifest.version=$FORMAT_VERSION"
        lines += "fixture.id=${singleLine(manifest.id)}"
        lines += "source.origin=${manifest.source.origin.name}"
        lines += "source.name=${singleLine(manifest.source.name)}"
        lines += "source.description=${singleLine(manifest.source.description)}"
        lines += "source.license=${singleLine(manifest.source.license)}"
        lines += "source.provenance=${singleLine(manifest.source.provenance)}"
        lines += "format.sampleRateHz=${manifest.format.sampleRateHz}"
        lines += "format.channelCount=${manifest.format.channelCount}"
        lines += "engine.engineId=${singleLine(manifest.engine.engineId)}"
        lines += "engine.modelId=${singleLine(manifest.engine.modelId)}"
        lines += "engine.version=${singleLine(manifest.engine.version)}"
        lines += "frame.sizeSamples=${manifest.frameSizeSamples}"
        lines += "pcm.sampleCount=${manifest.sampleCount}"
        lines += "pcm.sha256=${manifest.pcmSha256}"
        lines += "seed=${manifest.seed}"
        lines += "label.finalTranscript=${singleLine(manifest.labels.finalTranscript)}"
        lines += "labels.transcript.count=${manifest.labels.transcriptRevisions.size}"
        manifest.labels.transcriptRevisions.forEachIndexed { index, label ->
            lines += "labels.transcript.$index.frameIndex=${label.frameIndex}"
            lines += "labels.transcript.$index.text=${singleLine(label.text)}"
            lines += "labels.transcript.$index.isFinal=${label.isFinal}"
        }
        lines += "labels.vad.count=${manifest.labels.vadEvents.size}"
        manifest.labels.vadEvents.forEachIndexed { index, label ->
            lines += "labels.vad.$index.frameIndex=${label.frameIndex}"
            lines += "labels.vad.$index.activity=${label.activity.name}"
        }
        lines += "labels.turnCompletion.count=${manifest.labels.turnCompletions.size}"
        manifest.labels.turnCompletions.forEachIndexed { index, completion ->
            lines += "labels.turnCompletion.$index=${completion.name}"
        }
        lines += "transformations.count=${manifest.transformations.size}"
        manifest.transformations.forEachIndexed { index, transformation ->
            lines += "transformations.$index.kind=${transformation.kind}"
            lines += "transformations.$index.seed=${transformation.seed}"
            transformation.parameters().forEach { (key, value) ->
                lines += "transformations.$index.param.${singleLine(key)}=${singleLine(value)}"
            }
        }
        return lines.joinToString(separator = "\n", postfix = "\n")
    }

    fun decode(text: String): FixtureManifest {
        val entries = LinkedHashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val separator = line.indexOf('=')
            require(separator > 0) { "malformed manifest line: $line" }
            entries[line.substring(0, separator).trim()] = line.substring(separator + 1)
        }

        val version = entries.required("manifest.version").toInt()
        require(version == FORMAT_VERSION) { "unsupported manifest version $version" }

        val transcriptCount = entries.required("labels.transcript.count").toInt()
        val transcriptRevisions =
            List(transcriptCount) { index ->
                TranscriptLabel(
                    frameIndex = entries.required("labels.transcript.$index.frameIndex").toInt(),
                    text = entries.required("labels.transcript.$index.text"),
                    isFinal = entries.required("labels.transcript.$index.isFinal").toBooleanStrict(),
                )
            }

        val vadCount = entries.required("labels.vad.count").toInt()
        val vadEvents =
            List(vadCount) { index ->
                VadLabel(
                    frameIndex = entries.required("labels.vad.$index.frameIndex").toInt(),
                    activity = SpeechActivity.valueOf(entries.required("labels.vad.$index.activity")),
                )
            }

        val completionCount = entries.required("labels.turnCompletion.count").toInt()
        val turnCompletions =
            List(completionCount) { index ->
                TurnCompletion.valueOf(entries.required("labels.turnCompletion.$index"))
            }

        val transformationCount = entries.required("transformations.count").toInt()
        val transformations =
            List(transformationCount) { index ->
                val prefix = "transformations.$index."
                val kind = entries.required("${prefix}kind")
                val seed = entries.required("${prefix}seed").toLong()
                val params =
                    entries
                        .filterKeys { it.startsWith("${prefix}param.") }
                        .mapKeys { (key, _) -> key.removePrefix("${prefix}param.") }
                TransformationFactory.create(kind, seed, params)
            }

        return FixtureManifest(
            id = entries.required("fixture.id"),
            source =
                FixtureSource(
                    origin = FixtureOrigin.valueOf(entries.required("source.origin")),
                    name = entries.required("source.name"),
                    description = entries.required("source.description"),
                    license = entries.required("source.license"),
                    provenance = entries.required("source.provenance"),
                ),
            format =
                AudioFormat(
                    sampleRateHz = entries.required("format.sampleRateHz").toInt(),
                    channelCount = entries.required("format.channelCount").toInt(),
                ),
            engine =
                FixtureEngine(
                    engineId = entries.required("engine.engineId"),
                    modelId = entries.required("engine.modelId"),
                    version = entries.required("engine.version"),
                ),
            frameSizeSamples = entries.required("frame.sizeSamples").toInt(),
            sampleCount = entries.required("pcm.sampleCount").toInt(),
            pcmSha256 = entries.required("pcm.sha256"),
            seed = entries.required("seed").toLong(),
            labels =
                FixtureLabels(
                    finalTranscript = entries.required("label.finalTranscript"),
                    transcriptRevisions = transcriptRevisions,
                    vadEvents = vadEvents,
                    turnCompletions = turnCompletions,
                ),
            transformations = transformations,
        )
    }

    private fun singleLine(value: String): String {
        require('\n' !in value && '\r' !in value) { "manifest values must be single-line: $value" }
        return value
    }

    private fun Map<String, String>.required(key: String): String = this[key] ?: error("missing manifest key: $key")
}

/** Rebuilds a typed transformation from its manifest form. */
internal object TransformationFactory {
    fun create(
        kind: String,
        seed: Long,
        params: Map<String, String>,
    ): AudioTransformation =
        when (kind) {
            GainTransformation.KIND -> {
                GainTransformation(gainDb = params.double("gainDb"), seed = seed)
            }

            ClippingTransformation.KIND -> {
                ClippingTransformation(ceiling = params.double("ceiling"), seed = seed)
            }

            NoiseTransformation.KIND -> {
                NoiseTransformation(
                    profile = NoiseProfile.valueOf(params.required("profile")),
                    snrDb = params.double("snrDb"),
                    seed = seed,
                )
            }

            CompetingSpeechTransformation.KIND -> {
                CompetingSpeechTransformation(
                    phrase = params.required("phrase"),
                    levelDb = params.double("levelDb"),
                    seed = seed,
                )
            }

            EchoTransformation.KIND -> {
                EchoTransformation(
                    delayMillis = params.int("delayMillis"),
                    decay = params.double("decay"),
                    reflections = params.int("reflections"),
                    seed = seed,
                )
            }

            ReverberationTransformation.KIND -> {
                ReverberationTransformation(
                    decayMillis = params.int("decayMillis"),
                    wetLevel = params.double("wetLevel"),
                    seed = seed,
                )
            }

            CompressionTransformation.KIND -> {
                CompressionTransformation(
                    thresholdDb = params.double("thresholdDb"),
                    ratio = params.double("ratio"),
                    makeupDb = params.double("makeupDb"),
                    seed = seed,
                )
            }

            CodecArtifactTransformation.KIND -> {
                CodecArtifactTransformation(profile = CodecProfile.valueOf(params.required("profile")), seed = seed)
            }

            else -> {
                error("unknown transformation kind: $kind")
            }
        }

    private fun Map<String, String>.required(key: String): String = this[key] ?: error("missing transformation parameter: $key")

    private fun Map<String, String>.double(key: String): Double = required(key).toDouble()

    private fun Map<String, String>.int(key: String): Int = required(key).toInt()
}
