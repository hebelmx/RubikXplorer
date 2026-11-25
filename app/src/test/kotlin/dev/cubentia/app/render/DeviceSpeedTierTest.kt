package dev.cubentia.app.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSpeedTierTest {
    @Test
    fun theMinimumReferenceDeviceGetsTheMinimumTier() {
        val tier = DeviceSpeedTier.fromTotalMemoryBytes(3_700_000_000L)
        assertEquals(DeviceSpeedTier.MINIMUM, tier)
        assertEquals(900_000L, tier.renderSyncTimeoutMillis)
    }

    @Test
    fun theMidRangeReferenceDeviceGetsTheMidTier() {
        val tier = DeviceSpeedTier.fromTotalMemoryBytes(7_300_000_000L)
        assertEquals(DeviceSpeedTier.MID, tier)
        assertEquals(600_000L, tier.renderSyncTimeoutMillis)
    }

    @Test
    fun aDeviceWithTenGibOrMoreGetsTheHighTier() {
        val tier = DeviceSpeedTier.fromTotalMemoryBytes(16L * GIB)
        assertEquals(DeviceSpeedTier.HIGH, tier)
        assertEquals(300_000L, tier.renderSyncTimeoutMillis)
    }

    @Test
    fun unknownOrNonPositiveMemoryFallsToTheMinimumTier() {
        assertEquals(DeviceSpeedTier.MINIMUM, DeviceSpeedTier.fromTotalMemoryBytes(0))
        assertEquals(DeviceSpeedTier.MINIMUM, DeviceSpeedTier.fromTotalMemoryBytes(-1))
        assertEquals(DeviceSpeedTier.MINIMUM, DeviceSpeedTier.fromTotalMemoryBytes(Long.MIN_VALUE))
    }

    @Test
    fun theBoundariesBelongToTheFasterTier() {
        assertEquals(DeviceSpeedTier.MINIMUM, DeviceSpeedTier.fromTotalMemoryBytes(6L * GIB - 1))
        assertEquals(DeviceSpeedTier.MID, DeviceSpeedTier.fromTotalMemoryBytes(6L * GIB))
        assertEquals(DeviceSpeedTier.MID, DeviceSpeedTier.fromTotalMemoryBytes(10L * GIB - 1))
        assertEquals(DeviceSpeedTier.HIGH, DeviceSpeedTier.fromTotalMemoryBytes(10L * GIB))
    }

    @Test
    fun theMinimumTierGivesTheLenovoRunAtLeastOnePointNineTimesMargin() {
        assertTrue(DeviceSpeedTier.MINIMUM.renderSyncTimeoutMillis >= (LENOVO_RUN_MILLIS * 1.9).toLong())
    }

    @Test
    fun thereAreAtMostThreeTiers() {
        assertTrue(DeviceSpeedTier.entries.size <= 3)
    }

    private companion object {
        const val GIB = 1024L * 1024 * 1024
        const val LENOVO_RUN_MILLIS = 464_600L
    }
}
