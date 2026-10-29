package dev.rubikxplorer.application.port

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeatureFlagsTest {
    private fun flagsAt(level: FlagLevel) =
        object : FeatureFlags {
            override fun level(flag: FeatureFlag): FlagLevel = level
        }

    @Test
    fun `OFF disables every flag`() {
        val flags = flagsAt(FlagLevel.OFF)
        FeatureFlag.entries.forEach { assertFalse(flags.isEnabled(it), it.name) }
    }

    @Test
    fun `BETA and GA enable every flag`() {
        listOf(FlagLevel.BETA, FlagLevel.GA).forEach { level ->
            val flags = flagsAt(level)
            FeatureFlag.entries.forEach { assertTrue(flags.isEnabled(it), "$it at $level") }
        }
    }

    @Test
    fun `the flag and level sets are closed`() {
        assertEquals(
            listOf("QUICK_SCAN", "PLACEMENT", "CASE_RECOGNITION", "TEST_ME", "CAMERA_SCAN", "ADAPTIVE_ASSISTANCE"),
            FeatureFlag.entries.map { it.name },
        )
        assertEquals(listOf("OFF", "BETA", "GA"), FlagLevel.entries.map { it.name })
    }
}
