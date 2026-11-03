package com.voicechat.assistant.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CostCalculatorTest {

    @Test
    fun `zero prices produce zero cost`() {
        val usage = TokenUsage(inputTokens = 1_000, outputTokens = 500, totalTokens = 1_500)
        assertEquals(0.0, CostCalculator.cost(usage, 0.0, 0.0), 0.000001)
    }

    @Test
    fun `cost uses per million pricing`() {
        val usage = TokenUsage(inputTokens = 500_000, outputTokens = 250_000, totalTokens = 750_000)
        val cost = CostCalculator.cost(usage, priceInPerMillion = 2.0, priceOutPerMillion = 8.0)
        assertEquals(1.0 + 2.0, cost, 0.000001)
    }

    @Test
    fun `empty usage costs nothing`() {
        assertEquals(0.0, CostCalculator.cost(TokenUsage(), 10.0, 20.0), 0.000001)
    }
}
