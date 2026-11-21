package dev.cubentia.domain.recognition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SideLightTest {
    private fun face(level: Double): List<Double?> = listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, level, level + 5, level + 10)

    @Test
    fun `a face's light level is the 75th-percentile L* of its stickers`() {
        assertEquals(70.0, SideLight.level(face(70.0)))
        // Linear interpolation between neighbors when the position falls between two stickers.
        assertEquals(17.5, SideLight.level(listOf(10.0, 20.0, null))!!, 1e-9)
        assertEquals(42.0, SideLight.level(listOf(42.0)))
        assertNull(SideLight.level(listOf(null, null)))
        assertNull(SideLight.level(emptyList()))
    }

    @Test
    fun `the check fails only when the darker side face is more than the cut below the top face`() {
        val top = face(90.0)
        val atCut = SideLight.check(top, face(90.0 - SideLight.MAX_SIDE_DARKER_L), face(80.0))
        assertTrue(atCut.passed)
        assertEquals(SideLight.MAX_SIDE_DARKER_L, atCut.darkerSideDifference!!, 1e-9)
        assertEquals(10.0, atCut.rightDifference!!, 1e-9)
        val past = SideLight.check(top, face(80.0), face(90.0 - SideLight.MAX_SIDE_DARKER_L - 0.1))
        assertFalse(past.passed)
        // A side face brighter than the top never fails.
        assertTrue(SideLight.check(face(40.0), face(90.0), face(85.0)).passed)
    }

    @Test
    fun `an unmeasured face never fails the check`() {
        val verdict = SideLight.check(face(90.0), listOf(null), face(20.0))
        assertTrue(verdict.passed)
        assertNull(verdict.leftDifference)
        assertNull(verdict.darkerSideDifference)
        assertTrue(SideLight.check(listOf(null, null, null)).passed)
    }

    @Test
    fun `frame stats and samples are checked by their cell lightness in hold order`() {
        fun stats(level: Double) = FrameStats(face(level).map { CellReading(Lab(it!!, 0.0, 0.0), true) }, 120.0, 0.0, 100.0, 12)
        assertFalse(SideLight.check(listOf(stats(90.0), stats(50.0), stats(88.0))).passed)
        assertTrue(SideLight.check(listOf(stats(90.0), null, stats(50.0))).passed)

        fun sample(level: Double) =
            FaceSample(
                face = dev.cubentia.domain.cube.Face.U,
                orientationDegrees = 0,
                stickers = face(level).map { Lab(it!!, 0.0, 0.0) },
                quality = CaptureQuality.ACCEPTABLE,
            )
        assertFalse(SideLight.checkSamples(listOf(sample(90.0), sample(88.0), sample(60.0))).passed)
        assertTrue(SideLight.checkSamples(listOf(sample(90.0), sample(88.0), sample(70.0))).passed)
    }
}
