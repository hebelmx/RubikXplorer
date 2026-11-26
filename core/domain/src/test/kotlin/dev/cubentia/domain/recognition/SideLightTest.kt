package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SideLightTest {
    private fun face(level: Double): List<Double?> = listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, level, level + 5, level + 10)

    private val labs =
        mapOf(
            Color.WHITE to Lab(90.0, 0.0, 0.0),
            Color.GREEN to Lab(55.0, -60.0, 40.0),
            Color.RED to Lab(45.0, 65.0, 45.0),
            Color.YELLOW to Lab(85.0, -5.0, 80.0),
            Color.ORANGE to Lab(65.0, 45.0, 65.0),
            Color.BLUE to Lab(40.0, 10.0, -50.0),
        )
    private val saved = CalibrationCenters(Color.entries.associateWith(labs::getValue))

    /** Nine stickers of [colors] (cycled), each at [brightness] times the saved lightness of its color. */
    private fun stickers(
        vararg colors: Color,
        brightness: Double = 1.0,
    ): List<Lab?> = List(9) { i -> labs.getValue(colors[i % colors.size]).let { it.copy(l = it.l * brightness) } }

    @Test
    fun `the nearest saved colour is matched on a-star b-star only, so shading never changes the match`() {
        // A deeply shaded white is still White (a dark L* would otherwise sit nearest Blue's).
        assertEquals(Color.WHITE, SideLight.nearestOnChroma(Lab(40.0, 0.0, 0.0), saved))
        assertEquals(Color.ORANGE, SideLight.nearestOnChroma(Lab(30.0, 45.0, 65.0), saved))
    }

    @Test
    fun `calibrated relative brightness is the median sticker L* over its colour's saved lightness`() {
        assertEquals(1.0, SideLight.relativeBrightness(stickers(Color.RED, Color.WHITE, Color.BLUE), saved)!!, 1e-9)
        assertEquals(0.6, SideLight.relativeBrightness(stickers(Color.GREEN, Color.YELLOW, brightness = 0.6), saved)!!, 1e-9)
        // Median: one glare sticker far above does not move it.
        val glare = stickers(Color.GREEN, brightness = 0.8).toMutableList().also { it[0] = Lab(99.0, -60.0, 40.0) }
        assertEquals(0.8, SideLight.relativeBrightness(glare, saved)!!, 1e-9)
        assertNull(SideLight.relativeBrightness(listOf(null, Lab(Double.NaN, 0.0, 0.0)), saved))
    }

    @Test
    fun `a solved cube in uniform front light passes in calibrated mode where the uncalibrated check would hold it`() {
        val top = stickers(Color.WHITE)
        val left = stickers(Color.GREEN)
        val right = stickers(Color.RED)
        val calibrated = SideLight.checkLab(top, left, right, saved)
        assertEquals(SideLight.Mode.CALIBRATED, calibrated.mode)
        assertTrue(calibrated.passed)
        assertEquals(1.0, calibrated.score!!, 1e-9)
        // Without a calibration the colour-dependent 75th-percentile check holds it (accepted by the PO, decision E).
        val uncalibrated = SideLight.checkLab(top, left, right, null)
        assertEquals(SideLight.Mode.UNCALIBRATED, uncalibrated.mode)
        assertFalse(uncalibrated.passed)
    }

    @Test
    fun `the calibrated check fails only when the top over the mean of both sides is above its cut`() {
        val cut = SideLight.MAX_TOP_OVER_SIDES_RATIO
        val top = stickers(Color.WHITE, Color.RED, brightness = 1.1)
        val atCut =
            SideLight.checkLab(
                top,
                stickers(Color.BLUE, brightness = 1.1 / cut),
                stickers(Color.ORANGE, brightness = 1.1 / cut),
                saved,
            )
        assertTrue(atCut.passed)
        assertEquals(cut, atCut.score!!, 1e-9)
        val sides = 1.1 / (cut + 0.01)
        val past = SideLight.checkLab(top, stickers(Color.BLUE, brightness = sides), stickers(Color.ORANGE, brightness = sides), saved)
        assertFalse(past.passed)
        assertEquals(cut + 0.01, past.score!!, 1e-9)
        // Light from the side: one side shaded, the other bright; the mean of both keeps the hold from being held.
        val sideLit =
            SideLight.checkLab(
                stickers(Color.WHITE),
                stickers(Color.GREEN, brightness = 0.6),
                stickers(Color.RED, brightness = 1.4),
                saved,
            )
        assertTrue(sideLit.passed)
        assertEquals(1.0, sideLit.score!!, 1e-9)
        // An unmeasured face never fails it.
        assertTrue(SideLight.checkLab(top, listOf(null), stickers(Color.ORANGE, brightness = 0.1), saved).passed)
        // An invalid saved calibration falls back to the uncalibrated mode.
        val partial = CalibrationCenters(saved.centers - Color.BLUE)
        assertEquals(SideLight.Mode.UNCALIBRATED, SideLight.checkLab(top, top, top, partial).mode)
    }

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
        assertTrue(SideLight.checkSamples(listOf(null, null, null)).passed)
    }

    @Test
    fun `samples are checked by their sticker lightness in hold order`() {
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
