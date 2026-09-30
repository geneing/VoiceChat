package com.voicechat.agent.local

/**
 * The documented, allow-listed local `.litertlm` catalog entries (M20 / R-0213).
 *
 * Each entry here must be verified against the publisher's API and model card,
 * with the exact size and SHA-256 recorded, before it can pass
 * [LocalModelCatalogValidator] and be exposed by the app. Adding an entry is a
 * deliberate, reviewed change: it requires a real publisher, a stable source, a
 * license with a verifiable text, a validated LiteRT-LM runtime version, resource
 * figures, and a checksum, plus a matching record in `docs/local-models.md`. It is
 * never a user-supplied URL or an arbitrary file.
 *
 * **The catalog is intentionally empty.** No `.litertlm` bundle has been verified
 * to that bar for this toolchain, and the project does not ship a local LLM
 * (the external OpenCode Go provider is the primary LLM path). No model file is
 * committed or downloaded for this runtime; the app's own install path
 * (download → verify → atomic install into app-private storage) remains the
 * production mechanism for any future allow-listed entry.
 *
 * `scripts/fetch-models.sh` and `scripts/push-models.sh` still carry the pinned
 * Smart Turn artifact (see [com.voicechat.agent.turn.SmartTurnArtifact]); no
 * `.litertlm` is fetched or pushed.
 */
object DocumentedLocalModels {
    /** Every documented, allow-listed entry, in declared order. */
    val all: List<LocalModelArtifact> = emptyList()
}
