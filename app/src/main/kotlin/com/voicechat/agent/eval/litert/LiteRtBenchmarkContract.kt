package com.voicechat.agent.eval.litert

/** Models in the Google AI Edge speech-recognition sample and the requested extension. */
enum class LiteRtAsrModel(
    val id: String,
) {
    PARAKEET_TDT("parakeet-tdt"),
    PARAKEET_CTC("parakeet-ctc"),
    MOONSHINE("moonshine"),
    WHISPER_TINY("whisper-tiny"),
    WHISPER_SMALL("whisper-small"),
}

enum class LiteRtBackend(
    val id: String,
) {
    CPU("cpu"),
    GPU("gpu"),
    NPU("npu"),
}

enum class LiteRtDeviceTarget(
    val id: String,
) {
    PIXEL_10("pixel-10"),
    GALAXY_S23("galaxy-s23"),
    GALAXY_S24("galaxy-s24"),
    OTHER("other"),
}

enum class LiteRtLaneClassification(
    val id: String,
) {
    SAMPLE_LISTED("sample_listed"),
    EXTENSION_CANDIDATE("extension_candidate"),
    UNAVAILABLE("unavailable"),
}

/**
 * The sample's advertised device/backend compatibility, not a benchmark result.
 * CPU/GPU lanes listed by the sample are attempts; NPU targets are restricted to
 * the devices named by the upstream sample.
 */
data class LiteRtLane(
    val model: LiteRtAsrModel,
    val backend: LiteRtBackend,
    val device: LiteRtDeviceTarget,
) {
    fun classification(): LiteRtLaneClassification =
        when (backend) {
            LiteRtBackend.CPU, LiteRtBackend.GPU -> {
                if (model == LiteRtAsrModel.WHISPER_SMALL) {
                    LiteRtLaneClassification.EXTENSION_CANDIDATE
                } else {
                    LiteRtLaneClassification.SAMPLE_LISTED
                }
            }

            LiteRtBackend.NPU -> {
                when (model) {
                    LiteRtAsrModel.PARAKEET_TDT -> {
                        if (device == LiteRtDeviceTarget.PIXEL_10) {
                            LiteRtLaneClassification.SAMPLE_LISTED
                        } else {
                            LiteRtLaneClassification.UNAVAILABLE
                        }
                    }

                    LiteRtAsrModel.PARAKEET_CTC -> {
                        if (device == LiteRtDeviceTarget.GALAXY_S23 || device == LiteRtDeviceTarget.GALAXY_S24) {
                            LiteRtLaneClassification.SAMPLE_LISTED
                        } else {
                            LiteRtLaneClassification.UNAVAILABLE
                        }
                    }

                    LiteRtAsrModel.MOONSHINE,
                    LiteRtAsrModel.WHISPER_TINY,
                    LiteRtAsrModel.WHISPER_SMALL,
                    -> {
                        LiteRtLaneClassification.UNAVAILABLE
                    }
                }
            }
        }

    fun isListedBySample(): Boolean = classification() == LiteRtLaneClassification.SAMPLE_LISTED

    /** There is no LiteRT ASR runtime wired into this app yet. */
    fun initialOutcome(): LiteRtRunOutcome =
        if (classification() == LiteRtLaneClassification.UNAVAILABLE) {
            LiteRtRunOutcome.UNAVAILABLE
        } else {
            LiteRtRunOutcome.NOT_IMPLEMENTED
        }
}

enum class LiteRtRunOutcome(
    val id: String,
) {
    NOT_IMPLEMENTED("not_implemented"),
    UNAVAILABLE("unavailable"),
    LOAD_FAILED("load_failed"),
    RUNTIME_FALLBACK("runtime_fallback"),
    PARTIAL_DELEGATION("partial_delegation"),
    COMPLETED_UNCONFIRMED("completed_unconfirmed"),
    CONFIRMED("confirmed"),
}

enum class LiteRtArtifactPrecision(
    val id: String,
) {
    FLOAT32("fp32"),
    FLOAT16_CASTING("fp16_float_casting"),
    INT8_WEIGHTS_ACTIVATIONS("int8_wi8_ai8"),
}

/** Runtime compute precision is measured independently from the exported artifact format. */
enum class LiteRtComputePrecision(
    val id: String,
) {
    FP32("fp32"),
    FP16("fp16"),
    INT8("int8"),
    UNKNOWN("unknown"),
}

enum class LiteRtRunTemperature(
    val id: String,
) {
    COLD("cold"),
    WARM("warm"),
}

enum class LiteRtAccuracyReferenceSource(
    val id: String,
) {
    NONE("none"),
    OPENAI_GPT_4O_TRANSCRIBE("openai_gpt-4o-transcribe"),
    LOCAL_WHISPER("local_whisper"),
    HUMAN_REVIEWED("human_reviewed"),
}

enum class LiteRtAccuracyReferenceStatus(
    val id: String,
) {
    NOT_AVAILABLE("not_available"),
    UNREVIEWED_PSEUDO_REFERENCE("unreviewed_pseudo_reference"),
    HUMAN_REVIEWED("human_reviewed"),
}

enum class LiteRtPlacementEvidence(
    val id: String,
) {
    NONE("none"),
    INVOCATION_PROFILE("invocation_profile"),
}

/**
 * Requested backend and observed execution are separate fields by design. A
 * requested delegate alone is never evidence that the model ran on it.
 */
data class LiteRtExecutionDiagnostic(
    val requestedBackend: LiteRtBackend,
    val observedBackends: Set<LiteRtBackend> = emptySet(),
    val placementEvidence: LiteRtPlacementEvidence = LiteRtPlacementEvidence.NONE,
    val outcome: LiteRtRunOutcome,
) {
    init {
        when (outcome) {
            LiteRtRunOutcome.CONFIRMED -> {
                require(requestedBackend in observedBackends) {
                    "CONFIRMED requires the requested backend in observedBackends"
                }
                require(placementEvidence == LiteRtPlacementEvidence.INVOCATION_PROFILE) {
                    "CONFIRMED requires invocation-level placement evidence"
                }
            }

            LiteRtRunOutcome.RUNTIME_FALLBACK -> {
                require(requestedBackend != LiteRtBackend.CPU) { "CPU cannot fall back from itself" }
                require(LiteRtBackend.CPU in observedBackends) { "fallback requires observed CPU execution" }
                require(requestedBackend !in observedBackends) { "partial execution belongs in PARTIAL_DELEGATION" }
                require(placementEvidence != LiteRtPlacementEvidence.NONE) { "fallback requires placement evidence" }
            }

            LiteRtRunOutcome.PARTIAL_DELEGATION -> {
                require(requestedBackend in observedBackends) { "partial delegation requires the requested backend observed" }
                require(placementEvidence == LiteRtPlacementEvidence.INVOCATION_PROFILE) {
                    "partial delegation requires invocation-level placement evidence"
                }
            }

            else -> {
                Unit
            }
        }
    }
}

enum class LiteRtStage(
    val id: String,
) {
    MODEL_LOAD("model_load"),
    WARMUP("warmup"),
    AUDIO_DECODE("audio_decode"),
    PREPROCESS("preprocess"),
    ENCODER("encoder"),
    DECODER("decoder"),
    WINDOW_MERGE("window_merge"),
    FIRST_HYPOTHESIS("first_hypothesis"),
    END_TO_FINAL("end_to_final"),
    TOTAL("total"),
}

/** Content-free benchmark row. Transcript text and raw audio never belong here. */
data class LiteRtBenchmarkRecord(
    val gitCommit: String,
    val changeDescription: String,
    val deviceModel: String,
    val soc: String,
    val androidBuild: String,
    val model: LiteRtAsrModel,
    val artifactPrecision: LiteRtArtifactPrecision,
    val observedComputePrecision: LiteRtComputePrecision?,
    val modelSha256: String?,
    val conversionRevision: String?,
    val litertRuntimeVersion: String?,
    val delegateVersion: String?,
    val fixtureId: String,
    val audioDurationMillis: Long?,
    val runTemperature: LiteRtRunTemperature,
    val repeatIndex: Int,
    val thermalStatus: String?,
    val batteryTemperatureMilliC: Int?,
    val powerSaveEnabled: Boolean?,
    val accuracyReferenceSource: LiteRtAccuracyReferenceSource,
    val accuracyReferenceStatus: LiteRtAccuracyReferenceStatus,
    val accuracyReferenceSha256: String?,
    val wer: Double?,
    val cer: Double?,
    val execution: LiteRtExecutionDiagnostic,
    val stageDurationsNanos: Map<LiteRtStage, Long> = emptyMap(),
    val failureCode: String? = null,
) {
    init {
        require(gitCommit.matches(Regex("[0-9a-fA-F]{40}"))) { "gitCommit must be a full 40-character hash" }
        require(changeDescription.isNotBlank()) { "changeDescription must be recorded" }
        require(deviceModel.isNotBlank()) { "deviceModel must be recorded" }
        require(soc.isNotBlank()) { "soc must be recorded" }
        require(androidBuild.isNotBlank()) { "androidBuild must be recorded" }
        require(fixtureId.isNotBlank()) { "fixtureId must be recorded" }
        require(repeatIndex > 0) { "repeatIndex is one-based and must be positive" }
        require(modelSha256 == null || modelSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "modelSha256 must be a 64-character checksum"
        }
        require(accuracyReferenceSha256 == null || accuracyReferenceSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "accuracyReferenceSha256 must be a 64-character checksum"
        }
        require(batteryTemperatureMilliC == null || batteryTemperatureMilliC in -40_000..125_000) {
            "batteryTemperatureMilliC must be in millidegrees Celsius"
        }
        require(audioDurationMillis == null || audioDurationMillis > 0L) { "audioDurationMillis must be positive" }
        require(stageDurationsNanos.values.all { it >= 0L }) { "stage durations cannot be negative" }
        require((wer == null) == (cer == null)) { "WER and CER must be present together" }
        require(wer == null || (wer.isFinite() && wer >= 0.0)) { "WER must be finite and non-negative" }
        require(cer == null || (cer.isFinite() && cer >= 0.0)) { "CER must be finite and non-negative" }
        when (accuracyReferenceStatus) {
            LiteRtAccuracyReferenceStatus.NOT_AVAILABLE -> {
                require(accuracyReferenceSource == LiteRtAccuracyReferenceSource.NONE) {
                    "NOT_AVAILABLE requires the NONE reference source"
                }
                require(wer == null && cer == null) { "accuracy scores require a reference" }
            }

            LiteRtAccuracyReferenceStatus.UNREVIEWED_PSEUDO_REFERENCE -> {
                require(
                    accuracyReferenceSource == LiteRtAccuracyReferenceSource.OPENAI_GPT_4O_TRANSCRIBE ||
                        accuracyReferenceSource == LiteRtAccuracyReferenceSource.LOCAL_WHISPER,
                ) {
                    "pseudo-reference results must identify the OpenAI or local Whisper transcription source"
                }
            }

            LiteRtAccuracyReferenceStatus.HUMAN_REVIEWED -> {
                require(accuracyReferenceSource == LiteRtAccuracyReferenceSource.HUMAN_REVIEWED) {
                    "human-reviewed results must identify the reviewed reference source"
                }
            }
        }
        require(failureCode == null || failureCode.matches(Regex("[a-zA-Z0-9_.-]+"))) {
            "failureCode must be a stable content-free code"
        }
    }

    /** Stable, content-free CSV with empty cells for unmeasured values. */
    fun toCsvRow(): String =
        buildList {
            add(gitCommit)
            add(changeDescription)
            add(deviceModel)
            add(soc)
            add(androidBuild)
            add(model.id)
            add(artifactPrecision.id)
            add(observedComputePrecision?.id.orEmpty())
            add(modelSha256.orEmpty())
            add(conversionRevision.orEmpty())
            add(litertRuntimeVersion.orEmpty())
            add(delegateVersion.orEmpty())
            add(fixtureId)
            add(runTemperature.id)
            add(repeatIndex.toString())
            add(thermalStatus.orEmpty())
            add(batteryTemperatureMilliC?.toString().orEmpty())
            add(powerSaveEnabled?.toString().orEmpty())
            add(accuracyReferenceSource.id)
            add(accuracyReferenceStatus.id)
            add(accuracyReferenceSha256.orEmpty())
            add(wer?.toString().orEmpty())
            add(cer?.toString().orEmpty())
            add(execution.requestedBackend.id)
            add(execution.observedBackends.sortedBy { it.id }.joinToString("+") { it.id })
            add(execution.placementEvidence.id)
            add(execution.outcome.id)
            add(audioDurationMillis?.toString().orEmpty())
            for (stage in LiteRtStage.entries) add(stageDurationsNanos[stage]?.toString().orEmpty())
            add(failureCode.orEmpty())
        }.joinToString(",") { csvCell(it) }

    companion object {
        val CSV_HEADER: String =
            listOf(
                "git_commit",
                "change_description",
                "device_model",
                "soc",
                "android_build",
                "model",
                "artifact_precision",
                "observed_compute_precision",
                "model_sha256",
                "conversion_revision",
                "litert_runtime_version",
                "delegate_version",
                "fixture_id",
                "run_temperature",
                "repeat_index",
                "thermal_status",
                "battery_temperature_millic",
                "power_save_enabled",
                "accuracy_reference_source",
                "accuracy_reference_status",
                "accuracy_reference_sha256",
                "wer",
                "cer",
                "requested_backend",
                "observed_backends",
                "placement_evidence",
                "outcome",
                "audio_duration_ms",
                *LiteRtStage.entries.map { "${it.id}_ns" }.toTypedArray(),
                "failure_code",
            ).joinToString(",")
    }
}

private fun csvCell(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${value.replace("\"", "\"\"")}\""
    } else {
        value
    }
