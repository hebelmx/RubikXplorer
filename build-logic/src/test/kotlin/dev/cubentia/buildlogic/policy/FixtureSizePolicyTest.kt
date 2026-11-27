package dev.cubentia.buildlogic.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FixtureSizePolicyTest {
    @Test
    fun `limits are decimal bytes and inclusive`() {
        assertEquals(15_000_000L, FixtureSizePolicy.SESSION_LIMIT_BYTES)
        assertEquals(60_000_000L, FixtureSizePolicy.ROOT_LIMIT_BYTES)
        assertEquals(emptyList<String>(), FixtureSizePolicy.violations(mapOf("a" to 15_000_000L), 60_000_000L))
        assertEquals(emptyList<String>(), FixtureSizePolicy.violations(emptyMap(), 0L))
    }

    @Test
    fun `names each oversized session with its bytes and an oversized root`() {
        assertEquals(
            listOf(
                "Fixtures/continuous/a is 15000001 B (limit 15000000 B)",
                "Fixtures/continuous/c is 20000000 B (limit 15000000 B)",
                "Fixtures/continuous/ is 60000001 B (limit 60000000 B)",
            ),
            FixtureSizePolicy.violations(mapOf("c" to 20_000_000L, "b" to 1L, "a" to 15_000_001L), 60_000_001L),
        )
    }
}
