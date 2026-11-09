package dev.cubentia.buildlogic.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FeatureFlagLevelsTest {
    @Test
    fun `debug enables every flag at GA`() {
        assertEquals(
            "QUICK_SCAN=GA,PLACEMENT=GA,CASE_RECOGNITION=GA,TEST_ME=GA,CAMERA_SCAN=GA,ADAPTIVE_ASSISTANCE=GA",
            FeatureFlagLevels.forBuildType("debug"),
        )
    }

    @Test
    fun `release turns every flag OFF`() {
        assertEquals(
            "QUICK_SCAN=OFF,PLACEMENT=OFF,CASE_RECOGNITION=OFF,TEST_ME=OFF,CAMERA_SCAN=OFF,ADAPTIVE_ASSISTANCE=OFF",
            FeatureFlagLevels.forBuildType("release"),
        )
    }

    @Test
    fun `benchmark carries the release levels`() {
        assertEquals(FeatureFlagLevels.forBuildType("release"), FeatureFlagLevels.forBuildType("benchmark"))
        assertEquals(FeatureFlagLevels.byBuildType["release"], FeatureFlagLevels.byBuildType["benchmark"])
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
