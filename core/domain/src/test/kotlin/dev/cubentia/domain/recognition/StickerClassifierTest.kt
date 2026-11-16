package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class StickerClassifierTest {
    /** Centers along a: WHITE at a = 0, then 100, 200, ... so only the two nearest matter. */
    private fun centers(vararg overrides: Pair<Color, Lab>): CalibrationCenters =
        CalibrationCenters(Color.entries.associateWith { Lab(50.0, 100.0 * it.ordinal, 0.0) } + overrides)

    private fun classify(
        sticker: Lab,
        calibration: CalibrationCenters,
    ): StickerReading = (StickerClassifier.classify(sticker, calibration) as StickerClassification.Classified).reading

    @Test
    fun `clear read is confident`() {
        // Nearest (WHITE) 5 ΔE, second (YELLOW) 30 ΔE.
        val calibration = centers(Color.YELLOW to Lab(80.0, 0.0, 0.0))
        assertEquals(StickerReading(Color.WHITE, StickerConfidence.CONFIDENT), classify(Lab(50.0, 5.0, 0.0), calibration))
    }

    @Test
    fun `close call below 10 is uncertain and exactly 10 is confident`() {
        val sticker = Lab(50.0, 0.0, 0.0)
        val uncertain = centers(Color.WHITE to Lab(55.0, 0.0, 0.0), Color.YELLOW to Lab(50.0, 0.0, 14.99))
        assertEquals(StickerReading(Color.WHITE, StickerConfidence.UNCERTAIN), classify(sticker, uncertain))
        val exact = centers(Color.WHITE to Lab(55.0, 0.0, 0.0), Color.YELLOW to Lab(50.0, 0.0, 15.0))
        assertEquals(StickerReading(Color.WHITE, StickerConfidence.CONFIDENT), classify(sticker, exact))
        assertEquals(10.0, StickerClassifier.CLOSE_CALL_DELTA_E)
    }

    @Test
    fun `ties resolve in color order`() {
        val calibration = centers(Color.WHITE to Lab(50.0, 0.0, 10.0), Color.YELLOW to Lab(50.0, 0.0, -10.0))
        assertEquals(StickerReading(Color.WHITE, StickerConfidence.UNCERTAIN), classify(Lab(50.0, 0.0, 0.0), calibration))
    }

    @Test
    fun `bad calibration and bad samples are unavailable`() {
        val missing = CalibrationCenters(centers().centers - Color.RED)
        assertFalse(StickerClassifier.isValid(missing))
        assertEquals(
            StickerClassification.Unavailable(StickerClassificationFailure.INVALID_CALIBRATION),
            StickerClassifier.classify(Lab(50.0, 0.0, 0.0), missing),
        )
        val invalid = centers(Color.RED to Lab(150.0, 0.0, 0.0))
        assertFalse(StickerClassifier.isValid(invalid))
        assertTrue(StickerClassifier.isValid(centers()))
        assertEquals(
            StickerClassification.Unavailable(StickerClassificationFailure.INVALID_SAMPLE),
            StickerClassifier.classify(Lab(Double.NaN, 0.0, 0.0), centers()),
        )
    }

    @Test
    fun `synthetic labeled fixture classifies to its truth`() {
        val fixture = Json.decodeFromString<ScanFixtureSet>(File("../../Fixtures/labeled/face-samples.json").readText())
        fixture.samples.forEach { labeled ->
            assertEquals(labeled.truth, labeled.sample.stickers.map { classify(it, fixture.calibration).color })
        }
    }
}
