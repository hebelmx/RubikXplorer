package dev.cubentia.buildlogic.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FeatureFlagLevelsTest {
    @Test
    fun `debug levels follow wired entry points`() {
        assertEquals(
            "QUICK_SCAN=BETA,PLACEMENT=GA,CASE_RECOGNITION=GA,TEST_ME=GA,CAMERA_SCAN=GA,ADAPTIVE_ASSISTANCE=GA",
            FeatureFlagLevels.forBuildType("debug"),
        )
    }

    @Test
    fun `release ships Test me as BETA, adaptive assistance as GA, and keeps camera flags OFF`() {
        assertEquals(
            "QUICK_SCAN=OFF,PLACEMENT=OFF,CASE_RECOGNITION=OFF,TEST_ME=BETA,CAMERA_SCAN=OFF,ADAPTIVE_ASSISTANCE=GA",
            FeatureFlagLevels.forBuildType("release"),
        )
    }

    @Test
    fun `benchmark carries the release levels`() {
        assertEquals(FeatureFlagLevels.forBuildType("release"), FeatureFlagLevels.forBuildType("benchmark"))
        assertEquals(FeatureFlagLevels.byBuildType["release"], FeatureFlagLevels.byBuildType["benchmark"])
    }

    @Test
    fun `the opt-in cornerR8 test build type raises both LiteRT flags over the release levels`() {
        assertEquals(
            "QUICK_SCAN=BETA,PLACEMENT=OFF,CASE_RECOGNITION=BETA,TEST_ME=BETA,CAMERA_SCAN=BETA,ADAPTIVE_ASSISTANCE=GA",
            FeatureFlagLevels.forBuildType("cornerR8"),
        )
        val levels = FeatureFlagLevels.byBuildType.getValue("cornerR8")
        assertEquals(true, FeatureFlagLevels.isContinuousModelEnabled(levels))
        assertEquals(true, FeatureFlagLevels.isCornerModelEnabled(levels))
        assertEquals(true, FeatureFlagLevels.isLiteRtEnabled(levels))
    }

    @Test
    fun `the opt-in release011 build type enables continuous Quick Scan and keeps CASE_RECOGNITION OFF`() {
        assertEquals(
            "QUICK_SCAN=BETA,PLACEMENT=BETA,CASE_RECOGNITION=OFF,TEST_ME=BETA,CAMERA_SCAN=GA,ADAPTIVE_ASSISTANCE=GA",
            FeatureFlagLevels.forBuildType("release011"),
        )
        val levels = FeatureFlagLevels.byBuildType.getValue("release011")
        assertEquals(true, FeatureFlagLevels.isCameraEnabled(levels))
        assertEquals(true, FeatureFlagLevels.isContinuousModelEnabled(levels))
        assertEquals(false, FeatureFlagLevels.isCornerModelEnabled(levels))
        assertEquals(true, FeatureFlagLevels.isLiteRtEnabled(levels))
    }

    @Test
    fun `continuous and corner model flags split cleanly while LiteRT covers either`() {
        val base = FeatureFlagLevels.byBuildType.getValue("release") + ("CAMERA_SCAN" to "GA")
        assertEquals(false, FeatureFlagLevels.isContinuousModelEnabled(base))
        assertEquals(false, FeatureFlagLevels.isCornerModelEnabled(base))
        assertEquals(false, FeatureFlagLevels.isLiteRtEnabled(base))

        val continuousOnly = base + ("QUICK_SCAN" to "BETA")
        assertEquals(true, FeatureFlagLevels.isContinuousModelEnabled(continuousOnly))
        assertEquals(false, FeatureFlagLevels.isCornerModelEnabled(continuousOnly))
        assertEquals(true, FeatureFlagLevels.isLiteRtEnabled(continuousOnly))

        val cornerOnly = base + ("CASE_RECOGNITION" to "BETA")
        assertEquals(false, FeatureFlagLevels.isContinuousModelEnabled(cornerOnly))
        assertEquals(true, FeatureFlagLevels.isCornerModelEnabled(cornerOnly))
        assertEquals(true, FeatureFlagLevels.isLiteRtEnabled(cornerOnly))
    }

    @Test
    fun `unknown build type is rejected`() {
        assertThrows<IllegalArgumentException> { FeatureFlagLevels.forBuildType("staging") }
    }

    @Test
    fun `missing flag, unknown flag or unknown level is rejected`() {
        val all = FeatureFlagLevels.flags.associateWith { "GA" }
        assertThrows<IllegalArgumentException> { FeatureFlagLevels.encode(all - "TEST_ME") }
        assertThrows<IllegalArgumentException> { FeatureFlagLevels.encode(all + ("NEW_FLAG" to "GA")) }
        assertThrows<IllegalArgumentException> { FeatureFlagLevels.encode(all + ("TEST_ME" to "ON")) }
    }
}
