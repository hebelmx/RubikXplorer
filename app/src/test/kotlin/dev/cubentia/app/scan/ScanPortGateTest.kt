package dev.cubentia.app.scan

import dev.cubentia.application.port.FeatureFlag
import dev.cubentia.application.port.FeatureFlags
import dev.cubentia.application.port.FlagLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ScanPortGateTest {
    private fun flags(level: FlagLevel) =
        object : FeatureFlags {
            override fun level(flag: FeatureFlag) = level
        }

    @Test
    fun `missing binding fails during debug construction naming enabled flags`() {
        val failure = assertThrows<IllegalStateException> { ScanPortGate(flags(FlagLevel.GA), false, true) }
        ScanPortGate.cameraFlags.forEach { assertTrue(it.name in failure.message.orEmpty()) }
    }

    @Test
    fun `missing binding masks only camera flags in production`() {
        val gate = ScanPortGate(flags(FlagLevel.BETA), false, false)
        FeatureFlag.entries.forEach { assertEquals(if (it in ScanPortGate.cameraFlags) FlagLevel.OFF else FlagLevel.BETA, gate.level(it)) }
    }

    @Test
    fun `binding preserves levels and OFF needs no binding`() {
        FeatureFlag.entries.forEach {
            assertEquals(FlagLevel.GA, ScanPortGate(flags(FlagLevel.GA), true, true).level(it))
            assertEquals(FlagLevel.OFF, ScanPortGate(flags(FlagLevel.OFF), false, true).level(it))
        }
    }

    @Test
    fun `device without camera masks camera flags even with binding`() {
        val gate = ScanPortGate(flags(FlagLevel.GA), true, true, false)
        ScanPortGate.cameraFlags.forEach { assertEquals(FlagLevel.OFF, gate.level(it)) }
    }
}
