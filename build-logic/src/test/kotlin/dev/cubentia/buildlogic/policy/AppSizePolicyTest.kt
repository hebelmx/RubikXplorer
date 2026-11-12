package dev.cubentia.buildlogic.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AppSizePolicyTest {
    @Test
    fun `estimate includes dex expansion and data rather than AAB size`() {
        val result = AppSizePolicy.evaluate(3_059_060, 2_737_352, 5_564_448)
        assertEquals(11_424_716, result.estimatedBytes)
        assertTrue(result.withinBudget)
        listOf("5564448", "30000000", "18575284", "estimate").forEach { assertTrue(result.report.contains(it)) }
    }

    @Test
    fun `budget boundary is strict and overage has negative headroom`() {
        val justUnder = AppSizePolicy.evaluate(29_846_399, 0, 1)
        assertEquals(29_999_999, justUnder.estimatedBytes)
        assertTrue(justUnder.withinBudget)
        val atBudget = AppSizePolicy.evaluate(29_846_400, 0, 1)
        assertEquals(30_000_000, atBudget.estimatedBytes)
        assertFalse(atBudget.withinBudget)
        assertTrue(atBudget.report.contains("Headroom: 0"))
        val over = AppSizePolicy.evaluate(29_846_401, 0, 1)
        assertFalse(over.withinBudget)
        assertTrue(over.report.contains("Headroom: -1"))
        assertThrows<IllegalArgumentException> { AppSizePolicy.evaluate(1, -1, 1) }
        assertThrows<ArithmeticException> { AppSizePolicy.evaluate(1, Long.MAX_VALUE, 1) }
    }
}
