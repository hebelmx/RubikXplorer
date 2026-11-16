package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.RecognitionFixtures.CALIBRATION
import dev.cubentia.domain.recognition.RecognitionFixtures.LABS
import dev.cubentia.domain.recognition.RecognitionFixtures.LOGO
import dev.cubentia.domain.recognition.RecognitionFixtures.sample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SelfCalibrationTest {
    /** A solved cube's six faces; [logos] get a logo center that is not plain. */
    private fun solved(vararg logos: Color): List<FaceSample> =
        Color.entries.map { color ->
            if (color in logos) {
                sample(color, List(9) { if (it == 4) LOGO else LABS.getValue(color) }, plain = false)
            } else {
                sample(color, plain = if (color == Color.RED) null else true)
            }
        }

    private val saved = CalibrationCenters(Color.entries.associateWith { Lab(70.0, it.ordinal.toDouble(), 0.0) })

    @Test
    fun `plain centers calibrate in color order with nothing guarded`() {
        val result = SelfCalibration.from(solved().reversed())
        assertEquals(SelfCalibration.Calibrated(CALIBRATION, emptySet()), result)
        assertEquals(
            Color.entries,
            (result as SelfCalibration.Calibrated)
                .calibration.centers.keys
                .toList(),
        )
    }

    @Test
    fun `one logo center is derived from the 8 farthest stickers`() {
        assertEquals(SelfCalibration.Calibrated(CALIBRATION, setOf(Color.WHITE)), SelfCalibration.from(solved(Color.WHITE)))
    }

    @Test
    fun `derivation ignores samples without a center color`() {
        val stray = sample(null, List(9) { Lab(0.0, 120.0, -120.0) }, plain = true)
        assertEquals(SelfCalibration.Calibrated(CALIBRATION, setOf(Color.WHITE)), SelfCalibration.from(solved(Color.WHITE) + stray))
    }

    @Test
    fun `derivation excludes every center, including a far-off logo`() {
        val farLogo =
            solved(Color.WHITE).map {
                if (it.centerColor ==
                    Color.WHITE
                ) {
                    it.copy(
                        stickers =
                            it.stickers.mapIndexed { i, lab ->
                                if (i ==
                                    4
                                ) {
                                    Lab(100.0, -128.0, -128.0)
                                } else {
                                    lab
                                }
                            },
                    )
                } else {
                    it
                }
            }
        assertEquals(SelfCalibration.Calibrated(CALIBRATION, setOf(Color.WHITE)), SelfCalibration.from(farLogo))
    }

    @Test
    fun `a logo center uses the saved value when one is given`() {
        val expected = CalibrationCenters(CALIBRATION.centers + (Color.WHITE to saved.centers.getValue(Color.WHITE)))
        assertEquals(SelfCalibration.Calibrated(expected, setOf(Color.WHITE)), SelfCalibration.from(solved(Color.WHITE), saved))
    }

    @Test
    fun `two logo centers need calibration unless saved`() {
        assertEquals(
            SelfCalibration.NeedsCalibration(setOf(Color.WHITE, Color.BLUE)),
            SelfCalibration.from(solved(Color.WHITE, Color.BLUE)),
        )
        val calibrated = SelfCalibration.from(solved(Color.WHITE, Color.BLUE), saved) as SelfCalibration.Calibrated
        assertEquals(setOf(Color.WHITE, Color.BLUE), calibrated.guarded)
        assertEquals(saved.centers.getValue(Color.BLUE), calibrated.calibration.centers.getValue(Color.BLUE))
        assertEquals(LABS.getValue(Color.GREEN), calibrated.calibration.centers.getValue(Color.GREEN))
    }

    @Test
    fun `missing, duplicated, poor or malformed centers need calibration or come from saved`() {
        val faces = solved()
        val cases =
            listOf(
                faces.filterNot { it.centerColor == Color.GREEN },
                faces + sample(Color.GREEN),
                faces.map { if (it.centerColor == Color.GREEN) it.copy(quality = CaptureQuality.BLURRED) else it },
                faces.map { if (it.centerColor == Color.GREEN) it.copy(stickers = it.stickers.drop(1)) else it },
                faces.map { if (it.centerColor == Color.GREEN) it.copy(stickers = List(9) { Lab(Double.NaN, 0.0, 0.0) }) else it },
                faces.map { if (it.centerColor == Color.GREEN) it.copy(centerColor = null) else it },
            )
        cases.forEach { samples ->
            assertEquals(SelfCalibration.NeedsCalibration(setOf(Color.GREEN)), SelfCalibration.from(samples))
            val filled = SelfCalibration.from(samples, saved) as SelfCalibration.Calibrated
            assertEquals(emptySet<Color>(), filled.guarded)
            assertEquals(saved.centers.getValue(Color.GREEN), filled.calibration.centers.getValue(Color.GREEN))
        }
        // A missing color plus a logo, with no saved calibration, names both.
        assertEquals(
            SelfCalibration.NeedsCalibration(setOf(Color.WHITE, Color.GREEN)),
            SelfCalibration.from(solved(Color.WHITE).filterNot { it.centerColor == Color.GREEN }),
        )
    }

    @Test
    fun `a saved calibration without that color still needs calibration`() {
        val partial = CalibrationCenters(saved.centers - Color.GREEN + (Color.WHITE to Lab(Double.NaN, 0.0, 0.0)))
        assertEquals(
            SelfCalibration.NeedsCalibration(setOf(Color.WHITE, Color.GREEN)),
            SelfCalibration.from(solved(Color.WHITE).filterNot { it.centerColor == Color.GREEN }, partial),
        )
    }
}
