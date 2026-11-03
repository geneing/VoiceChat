package com.voicechat.assistant.data.model

/** Token → USD estimate. Prices are per one million tokens. */
object CostCalculator {

    fun cost(usage: TokenUsage, priceInPerMillion: Double, priceOutPerMillion: Double): Double {
        if (priceInPerMillion <= 0.0 && priceOutPerMillion <= 0.0) return 0.0
        return usage.inputTokens / 1_000_000.0 * priceInPerMillion +
            usage.outputTokens / 1_000_000.0 * priceOutPerMillion
    }
}
