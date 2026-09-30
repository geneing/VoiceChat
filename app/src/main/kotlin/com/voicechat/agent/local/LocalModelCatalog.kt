package com.voicechat.agent.local

import com.voicechat.agent.contracts.ModelDescriptor
import com.voicechat.agent.contracts.ModelRuntime
import com.voicechat.agent.contracts.ModelTask
import com.voicechat.agent.domain.ModelId

/**
 * One **app-managed** local model bundle candidate (M20).
 *
 * It is the full allow-list record `docs/model-runtime.md` requires: identity,
 * provenance, license, runtime, device fit, resources, and integrity. Every field
 * that gates admission is nullable so a candidate that is missing required
 * metadata can be represented and then rejected by [LocalModelCatalogValidator]
 * instead of silently entering the catalog.
 *
 * This type never holds an arbitrary model URL or file path as executable input:
 * the app chooses which allow-listed entries to expose, and the installed path is
 * derived from app-private storage by [LocalModelInstaller], not from this record.
 */
data class LocalModelArtifact(
    val id: ModelId,
    val displayName: String,
    val task: ModelTask = ModelTask.LANGUAGE_MODEL,
    val runtime: ModelRuntime = ModelRuntime.LITERT_LM,
    /** Publisher of the artifact. */
    val publisher: String? = null,
    /** Stable, official source for the artifact (not a download endpoint). */
    val sourceUrl: String? = null,
    /** License identifier/name and any redistribution restriction. */
    val license: String? = null,
    /** Where the license text can be verified, when one exists. */
    val licenseUrl: String? = null,
    /** The exact LiteRT-LM runtime version the bundle was validated against. */
    val runtimeVersion: String? = null,
    /** Expected download size in bytes. */
    val downloadBytes: Long? = null,
    /** On-device storage required after install, in bytes. */
    val storageBytes: Long? = null,
    /** Peak runtime memory the model needs, in bytes. */
    val peakMemoryBytes: Long? = null,
    /** Minimum Android API level the runtime supports. */
    val minAndroidApi: Int? = null,
    /** Lowercase hex SHA-256 of the artifact, the integrity identity. */
    val sha256: String? = null,
    /** Device the entry was measured on, when it has been tested. */
    val testedDevice: String? = null,
    /** Accelerator/ABI assumptions, or `null` when unverified. */
    val deviceNotes: String? = null,
    /** Free-form verification notes; never a place for secrets or model bytes. */
    val notes: String? = null,
)

/** An artifact that passed validation, with its app-facing descriptor. */
data class ValidatedLocalModel(
    val artifact: LocalModelArtifact,
    val descriptor: ModelDescriptor,
)

/** Outcome of admitting one [LocalModelArtifact]: admitted or rejected with why. */
sealed interface LocalCatalogValidation {
    /** Admitted to the catalog. */
    data class Accepted(
        val model: ValidatedLocalModel,
    ) : LocalCatalogValidation

    /**
     * Refused. [missing] names the metadata that failed, and [reason] is a short,
     * safe explanation. Rejection is final: the artifact is never exposed.
     */
    data class Rejected(
        val missing: List<String>,
        val reason: String,
    ) : LocalCatalogValidation
}

/**
 * Admits a candidate only when every required field is present and plausible.
 *
 * This is the "allow-listed" gate from `docs/model-runtime.md`: without a
 * publisher, a stable source, a license, a validated runtime version, resource
 * figures, and a well-formed SHA-256, an entry is refused. It deliberately does
 * not fetch or hash the file itself (that is the install-time integrity check).
 */
object LocalModelCatalogValidator {
    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

    fun validate(artifact: LocalModelArtifact): LocalCatalogValidation {
        val missing = mutableListOf<String>()

        if (artifact.displayName.isBlank()) missing += "displayName"
        if (artifact.task != ModelTask.LANGUAGE_MODEL) missing += "task (must be LANGUAGE_MODEL)"
        if (artifact.runtime != ModelRuntime.LITERT_LM) missing += "runtime (must be LITERT_LM)"
        if (artifact.publisher.isNullOrBlank()) missing += "publisher"
        if (artifact.sourceUrl.isNullOrBlank()) missing += "sourceUrl"
        if (artifact.license.isNullOrBlank()) missing += "license"
        if (artifact.licenseUrl.isNullOrBlank()) missing += "licenseUrl"
        if (artifact.runtimeVersion.isNullOrBlank()) missing += "runtimeVersion"
        if (artifact.downloadBytes == null || artifact.downloadBytes <= 0) missing += "downloadBytes"
        if (artifact.storageBytes == null || artifact.storageBytes <= 0) missing += "storageBytes"
        if (artifact.peakMemoryBytes == null || artifact.peakMemoryBytes <= 0) missing += "peakMemoryBytes"
        if (artifact.minAndroidApi == null || artifact.minAndroidApi !in 1..100) missing += "minAndroidApi"
        val checksum = artifact.sha256
        if (checksum == null || !checksum.matches(SHA256_PATTERN)) missing += "sha256"

        if (missing.isNotEmpty()) {
            return LocalCatalogValidation.Rejected(
                missing = missing,
                reason = "not allow-listed: missing or invalid " + missing.joinToString(", "),
            )
        }

        return LocalCatalogValidation.Accepted(
            ValidatedLocalModel(
                artifact = artifact,
                descriptor =
                    ModelDescriptor(
                        id = artifact.id,
                        task = artifact.task,
                        displayName = artifact.displayName,
                        runtime = artifact.runtime,
                        providerId = LocalProviders.LITERT_LM,
                    ),
            ),
        )
    }
}

/** Why no local model is offered, so the UI can say so instead of showing nothing. */
sealed interface LocalCatalogStatus {
    /** At least one allow-listed model exists. */
    data class Available(
        val models: List<ModelDescriptor>,
    ) : LocalCatalogStatus

    /** No candidate met the allow-list bar; [reason] explains why. */
    data class NoAllowListedModel(
        val reason: String,
    ) : LocalCatalogStatus
}

/**
 * The curated, allow-listed `.litertlm` catalog (M20).
 *
 * The catalog is **empty** today. It ships empty rather than pointing at an
 * unverified bundle because `docs/model-runtime.md` requires a validated
 * publisher, license, runtime version, resource figures, and checksum before an
 * artifact may be exposed, and no `.litertlm` bundle has been verified to that
 * bar for this toolchain. Adding a model is a deliberate, reviewed catalog entry
 * plus its `docs/local-models.md` record — never a user-supplied URL.
 *
 * An empty catalog is a valid, honest result: [status] reports
 * [LocalCatalogStatus.NoAllowListedModel] with the reason.
 */
object LocalModelCatalog {
    /** Raw candidates, before the allow-list gate. Currently none. */
    val allowListed: List<LocalModelArtifact> = emptyList()

    /** The reason the catalog is empty (used by [status] and the docs). */
    const val EMPTY_REASON: String =
        "No .litertlm bundle has a verified publisher, license, pinned runtime version, " +
            "resource figures, and SHA-256 for this toolchain."

    /** Every candidate that passed [LocalModelCatalogValidator], in declared order. */
    fun entries(): List<ValidatedLocalModel> =
        allowListed.mapNotNull { artifact ->
            (LocalModelCatalogValidator.validate(artifact) as? LocalCatalogValidation.Accepted)?.model
        }

    /** The validated entry for [id], or `null` when it is not allow-listed. */
    fun find(id: ModelId): ValidatedLocalModel? = entries().firstOrNull { it.descriptor.id == id }

    /** The overall catalog state: available models, or the honest empty reason. */
    val status: LocalCatalogStatus
        get() {
            val models = entries().map { it.descriptor }
            return if (models.isEmpty()) {
                LocalCatalogStatus.NoAllowListedModel(EMPTY_REASON)
            } else {
                LocalCatalogStatus.Available(models)
            }
        }
}
