package com.voicechat.agent.providers.opencodego

import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ReasoningLevel

/**
 * How one OpenCode Go model expresses reasoning control on the wire.
 *
 * The Go gateway does not document a reasoning parameter and does not report
 * per-model capabilities, but it accepts and honours the field of the protocol
 * family the model is served through — measured live on 2026-10-16/17
 * (`docs/voice-llm-selection.md`). Which field works is therefore a property of
 * the **model**, not of the provider, so it is declared per model here and never
 * guessed:
 *
 * | style | request field |
 * | --- | --- |
 * | [NONE] | no reasoning field; only [ReasoningLevel.NONE] is accepted |
 * | [RESPONSES_EFFORT] | `reasoning: {"effort": "…"}` |
 * | [CHAT_REASONING_EFFORT] | `reasoning_effort: "…"` |
 * | [MESSAGES_ADAPTIVE_EFFORT] | `thinking: {"type":"adaptive"}` + `output_config: {"effort": "…"}` |
 *
 * A model that is not listed has **no verified** reasoning control, so the
 * adapter refuses any level except `NONE` for it rather than sending a field the
 * gateway may reject or, worse, honour with unexpected behaviour (for example
 * `minimax-m3`, whose `reasoning_effort` leaks raw `<think>` text into the
 * assistant stream, and `space-bunny`, which rejects the field with HTTP 400).
 */
enum class OpenCodeGoReasoningStyle {
    /** No reasoning field is sent; only [ReasoningLevel.NONE] is accepted. */
    NONE,

    /** OpenAI Responses: `reasoning: {"effort": "…"}`. */
    RESPONSES_EFFORT,

    /** OpenAI-compatible Chat Completions: `reasoning_effort: "…"`. */
    CHAT_REASONING_EFFORT,

    /** Anthropic Messages: adaptive thinking with an effort hint. */
    MESSAGES_ADAPTIVE_EFFORT,

    /**
     * Anthropic Messages: the model is thinking-only, and its single verified
     * control is `thinking: {"type": "disabled"}`. Any requested level beyond
     * `NONE` is refused, because nothing else was measured to work.
     */
    MESSAGES_THINKING_DISABLED,
}

/** One model's verified reasoning control: the wire [style] and the accepted levels. */
data class OpenCodeGoReasoningSupport(
    val style: OpenCodeGoReasoningStyle,
    /**
     * The levels beyond [ReasoningLevel.NONE] this model is verified to accept.
     * Only levels measured against the live gateway are listed; an unmeasured
     * level is absent, so the adapter refuses it instead of claiming it.
     */
    val levels: Set<ReasoningLevel> = emptySet(),
    /**
     * True when the model's **off** value must be sent explicitly to disable
     * reasoning, because omitting the field means "provider default" and the
     * default reasons.
     *
     * Measured on `gpt-6-luna`: the voice request with the field omitted reasoned
     * (~650 reasoning tokens, 7.70 s median to first token) while
     * `reasoning.effort = "none"` answered in 0.93 s with zero reasoning tokens.
     * The other adapters in this app omit `NONE` because some providers reject
     * `"none"`; here the omitting provider is the exception, and `false` is kept
     * for a model like `grok-4.7`, which does reject `"none"`.
     */
    val sendsExplicitOff: Boolean = false,
) {
    /** True when [level] may be requested for this model. */
    fun supports(level: ReasoningLevel): Boolean = level == ReasoningLevel.NONE || level in levels

    /**
     * True when a request for [level] should carry the model's reasoning field.
     * `NONE` is only sent when [sendsExplicitOff] says the field is what turns
     * reasoning off (or when the model's only verified control is the off value,
     * which [OpenCodeGoReasoningStyle.MESSAGES_THINKING_DISABLED] encodes).
     */
    fun sendsFieldFor(level: ReasoningLevel): Boolean =
        when (style) {
            OpenCodeGoReasoningStyle.NONE -> false
            OpenCodeGoReasoningStyle.MESSAGES_THINKING_DISABLED -> level == ReasoningLevel.NONE
            else -> level != ReasoningLevel.NONE || sendsExplicitOff
        }
}

/**
 * The per-model reasoning table for OpenCode Go.
 *
 * Only models measured against the live gateway are listed. Everything else
 * keeps the pre-measurement behaviour: `NONE` only, refused before any request is
 * sent (R-0135 stays open for the unmeasured ids).
 */
object OpenCodeGoReasoning {
    /** Levels the OpenAI Responses effort field accepts; measured on gpt-6-luna. */
    private val EFFORT_LEVELS: Set<ReasoningLevel> =
        setOf(
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
            ReasoningLevel.MAX,
        )

    private val SUPPORT: Map<String, OpenCodeGoReasoningSupport> =
        mapOf(
            // Responses family. `none` … `max` measured on a planning question:
            // first token 0.93 s at none, 3.29 s at low, 41.80 s at max. The field
            // must be sent for `none` too, or the provider default reasons
            // (~7.70 s median, ~650 reasoning tokens).
            "gpt-6-luna" to
                OpenCodeGoReasoningSupport(OpenCodeGoReasoningStyle.RESPONSES_EFFORT, EFFORT_LEVELS, sendsExplicitOff = true),
            "gpt-5.6-luna" to
                OpenCodeGoReasoningSupport(OpenCodeGoReasoningStyle.RESPONSES_EFFORT, EFFORT_LEVELS, sendsExplicitOff = true),
            // `reasoning.effort=none` is rejected for Grok ("Unsupported value"),
            // so `low` is its floor and the field is omitted for `none`.
            "grok-4.7" to
                OpenCodeGoReasoningSupport(
                    OpenCodeGoReasoningStyle.RESPONSES_EFFORT,
                    setOf(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH),
                ),
            // Messages family: `thinking.type=enabled` is refused ("use adaptive"),
            // and `reasoning_effort` is an unknown field. Adaptive + effort works;
            // with no field the model answers without extra thinking (measured
            // 0.75-1.30 s to first token), so `none` omits it.
            "claude-haiku-5-5" to
                OpenCodeGoReasoningSupport(
                    OpenCodeGoReasoningStyle.MESSAGES_ADAPTIVE_EFFORT,
                    setOf(ReasoningLevel.LOW, ReasoningLevel.HIGH),
                ),
            // Chat Completions family. glm-5.3-flash was a live-tier candidate on
            // 2026-10-16 (`reasoning_effort=none`, 0.84 s), but the gateway now
            // refuses every off form for it — "[1210] This model always engages in
            // thinking and cannot be disabled; please use low, high, or…" — so it
            // accepts only effort levels (`low` 3.74 s, `max` 6.32 s, and 7.80 s
            // with ~300 reasoning tokens when the field is omitted). Sending it
            // `none` is now a HTTP 400, so `NONE` omits the field, and the model
            // can no longer lead a spoken turn (R-0252).
            "glm-5.3-flash" to
                OpenCodeGoReasoningSupport(
                    OpenCodeGoReasoningStyle.CHAT_REASONING_EFFORT,
                    setOf(ReasoningLevel.LOW, ReasoningLevel.HIGH),
                ),
            "deepseek-v4.1-flash" to
                OpenCodeGoReasoningSupport(
                    OpenCodeGoReasoningStyle.CHAT_REASONING_EFFORT,
                    setOf(ReasoningLevel.LOW, ReasoningLevel.HIGH),
                    sendsExplicitOff = true,
                ),
            "deepseek-v4-pro" to
                OpenCodeGoReasoningSupport(
                    OpenCodeGoReasoningStyle.CHAT_REASONING_EFFORT,
                    sendsExplicitOff = true,
                ),
            // Only `reasoning_effort=none` was measured; the default already reasons
            // (4.70 s to first token), so `none` is sent explicitly.
            "kimi-k3" to
                OpenCodeGoReasoningSupport(
                    OpenCodeGoReasoningStyle.CHAT_REASONING_EFFORT,
                    sendsExplicitOff = true,
                ),
            // Measured to reject every reasoning field; the model still reasons on
            // its own, which is why it is not a live-tier candidate.
            "minimax-m3" to OpenCodeGoReasoningSupport(OpenCodeGoReasoningStyle.NONE),
            // Messages family, thinking-only: `thinking.type=disabled` is the one
            // verified control (1.07 s to first token, no thinking).
            "qwen3.8-flash" to OpenCodeGoReasoningSupport(OpenCodeGoReasoningStyle.MESSAGES_THINKING_DISABLED),
        )

    /** The verified support for [modelId], or `null` when the model was not measured. */
    fun support(modelId: ModelId): OpenCodeGoReasoningSupport? = SUPPORT[modelId.value]

    /**
     * The levels beyond `NONE` verified for [modelId]; empty when the model was
     * not measured or accepts none.
     */
    fun levels(modelId: ModelId): Set<ReasoningLevel> = support(modelId)?.levels.orEmpty()

    /**
     * True when [level] may be requested for [modelId].
     *
     * An unmeasured model accepts `NONE` only, because "we did not verify a
     * control" is not the same as "there is no control".
     */
    fun supports(
        modelId: ModelId,
        level: ReasoningLevel,
    ): Boolean =
        when (level) {
            ReasoningLevel.NONE -> true
            else -> support(modelId)?.supports(level) == true
        }

    /** The documented wire value for [level] in an `effort`-style field. */
    fun effortWire(level: ReasoningLevel): String =
        when (level) {
            ReasoningLevel.NONE -> "none"
            ReasoningLevel.MINIMAL -> "minimal"
            ReasoningLevel.LOW -> "low"
            ReasoningLevel.MEDIUM -> "medium"
            ReasoningLevel.HIGH -> "high"
            ReasoningLevel.XHIGH -> "xhigh"
            ReasoningLevel.MAX -> "max"
        }
}
