package dev.cubentia.application.port

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

    private fun flags(vararg levels: Pair<FeatureFlag, FlagLevel>): FeatureFlags {
        val map = levels.toMap()
        return object : FeatureFlags {
            override fun level(flag: FeatureFlag): FlagLevel = map[flag] ?: FlagLevel.GA
        }
    }

    @Test
    fun `showsBeta is true for a flag at BETA`() {
        FeatureFlag.entries.forEach { assertTrue(flags(it to FlagLevel.BETA).showsBeta(it), it.name) }
    }

    @Test
    fun `showsBeta is true for a GA camera feature while CAMERA_SCAN is BETA`() {
        listOf(FeatureFlag.QUICK_SCAN, FeatureFlag.PLACEMENT, FeatureFlag.CASE_RECOGNITION).forEach {
            assertTrue(flags(FeatureFlag.CAMERA_SCAN to FlagLevel.BETA).showsBeta(it), it.name)
        }
    }

    @Test
    fun `showsBeta is false for a GA camera feature while CAMERA_SCAN is GA`() {
        FeatureFlag.entries.forEach { assertFalse(flags().showsBeta(it), it.name) }
    }

    @Test
    fun `showsBeta is false for a GA non-camera flag while CAMERA_SCAN is BETA`() {
        listOf(FeatureFlag.TEST_ME, FeatureFlag.ADAPTIVE_ASSISTANCE).forEach {
            assertFalse(flags(FeatureFlag.CAMERA_SCAN to FlagLevel.BETA).showsBeta(it), it.name)
        }
    }

    @Test
    fun `showsBeta is false for a flag that is OFF`() {
        FeatureFlag.entries.filter { it != FeatureFlag.CAMERA_SCAN }.forEach {
            assertFalse(flags(it to FlagLevel.OFF, FeatureFlag.CAMERA_SCAN to FlagLevel.BETA).showsBeta(it), it.name)
        }
        assertFalse(flags(FeatureFlag.CAMERA_SCAN to FlagLevel.OFF).showsBeta(FeatureFlag.CAMERA_SCAN))
        assertFalse(flags(FeatureFlag.CAMERA_SCAN to FlagLevel.OFF).showsBeta(FeatureFlag.QUICK_SCAN))
    }
}
