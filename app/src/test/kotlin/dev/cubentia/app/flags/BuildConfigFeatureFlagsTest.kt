package dev.cubentia.app.flags

import dev.cubentia.app.BuildConfig
import dev.cubentia.application.port.FeatureFlag
import dev.cubentia.application.port.FlagLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BuildConfigFeatureFlagsTest {
    @Test
    fun `levels come from build-logic per build type`() {
        val expected =
            when (BuildConfig.BUILD_TYPE) {
                "debug" -> FlagLevel.GA
                "release" -> FlagLevel.OFF
                else -> error("No expectation for build type ${BuildConfig.BUILD_TYPE}")
            }
        val flags = BuildConfigFeatureFlags()
        FeatureFlag.entries.forEach { assertEquals(expected, flags.level(it), it.name) }
    }

    @Test
    fun `parses an explicit levels string`() {
        val encoded = FeatureFlag.entries.joinToString(",") { "$it=${if (it == FeatureFlag.TEST_ME) "BETA" else "OFF"}" }
        val flags = BuildConfigFeatureFlags(encoded)
        assertEquals(FlagLevel.BETA, flags.level(FeatureFlag.TEST_ME))
        assertEquals(FlagLevel.OFF, flags.level(FeatureFlag.QUICK_SCAN))
    }

    @Test
    fun `missing flag throws`() {
        val encoded = FeatureFlag.entries.drop(1).joinToString(",") { "$it=GA" }
        assertThrows<IllegalStateException> { BuildConfigFeatureFlags(encoded) }
    }

    @Test
    fun `unknown level throws`() {
        val encoded = FeatureFlag.entries.joinToString(",") { "$it=ON" }
        assertThrows<IllegalStateException> { BuildConfigFeatureFlags(encoded) }
    }

    @Test
    fun `unknown flag, duplicate or malformed entry throws`() {
        val valid = FeatureFlag.entries.joinToString(",") { "$it=GA" }
        assertThrows<IllegalStateException> { BuildConfigFeatureFlags("$valid,NEW_FLAG=GA") }
        assertThrows<IllegalStateException> { BuildConfigFeatureFlags("$valid,TEST_ME=OFF") }
        assertThrows<IllegalStateException> { BuildConfigFeatureFlags("$valid,TEST_ME") }
        assertThrows<IllegalStateException> { BuildConfigFeatureFlags("") }
    }
}
