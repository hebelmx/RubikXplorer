package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.RecognitionFixtures.CALIBRATION
import dev.cubentia.domain.recognition.RecognitionFixtures.LABS
import dev.cubentia.domain.recognition.RecognitionFixtures.sample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ColorCalibrationTest {
    @Test fun `swatches require explicit keep in plan order and retake preserves plain prompt`() {
        var session = CalibrationSession()
        assertSame(session, session.keep())
        assertNull(session.calibration)
        GuidedScanPlan.steps.forEach { step ->
            assertEquals(step.centerColor, session.current)
            val rejected = session.offer(null)
            assertTrue(rejected.plainSticker)
            assertNull(rejected.pending)
            val uneven = rejected.offer(CellReading(LABS.getValue(step.centerColor), false))
            assertTrue(uneven.plainSticker)
            assertNull(uneven.pending)
            val offered = uneven.offer(CellReading(LABS.getValue(step.centerColor), true))
            assertNotNull(offered.pending)
            assertTrue(offered.plainSticker)
            assertEquals(session.kept, offered.kept)
            val retaken = offered.retake()
            assertNull(retaken.pending)
            assertTrue(retaken.plainSticker)
            session = retaken.offer(CellReading(LABS.getValue(step.centerColor), true)).keep()
            assertFalse(session.plainSticker)
        }
        assertNull(session.current)
        assertEquals(CALIBRATION, session.calibration)
        assertSame(session, session.keep())
        assertSame(session, session.offer(null))
        assertTrue(ColorPlausibility.valid(session.calibration!!))
    }

    @Test fun `bad white logo and nearby kept red are refused`() {
        var session = CalibrationSession()
        repeat(4) { session = session.offer(CellReading(LABS.getValue(session.current!!), true)).keep() }
        assertEquals(Color.WHITE, session.current)
        listOf(Lab(76.0, 20.0, 71.0), RecognitionFixtures.LOGO, Lab(Double.NaN, 0.0, 0.0)).forEach {
            assertNull(session.offer(CellReading(it, true)).pending)
        }
        assertNotNull(session.offer(CellReading(Lab(88.0, -6.0, -16.0), true)).pending)
        var duplicate = CalibrationSession()
        duplicate = duplicate.offer(CellReading(LABS.getValue(Color.GREEN), true)).keep()
        val red = Lab(50.0, 40.0, 40.0)
        duplicate = duplicate.offer(CellReading(red, true)).keep()
        duplicate = duplicate.offer(CellReading(LABS.getValue(Color.BLUE), true)).keep()
        assertTrue(ColorPlausibility.plausible(Color.ORANGE, red))
        assertNull(duplicate.offer(CellReading(red, true)).pending)
    }

    @Test fun `plausibility boundaries and invalid centers`() {
        LABS.forEach { (color, lab) -> assertTrue(ColorPlausibility.plausible(color, lab)) }
        assertTrue(ColorPlausibility.plausible(Color.RED, Lab(50.0, 40.0, -5.0)))
        Color.entries.forEach { assertFalse(ColorPlausibility.plausible(it, Lab(-1.0, 0.0, 0.0))) }
        assertFalse(ColorPlausibility.valid(CalibrationCenters(emptyMap())))
        assertFalse(ColorPlausibility.valid(CalibrationCenters(LABS + (Color.WHITE to Lab(20.0, 0.0, 0.0)))))
        assertFalse(ColorPlausibility.valid(CalibrationCenters(LABS + (Color.ORANGE to LABS.getValue(Color.RED)))))
    }

    private fun solved(logos: Set<Color> = emptySet()) =
        Face.entries.map { face ->
            val color = Color.home(face)
            sample(color, plain = color !in logos, face = face)
        }

    @Test fun `offer covers missing many blanks and first guarded scan and saved colors fill guarded centers`() {
        val plain = solved()
        assertFalse(CalibrationOffer.offered(plain, null, ScanReview.fromSamples(plain)))
        val guarded = solved(setOf(Color.WHITE))
        assertTrue(CalibrationOffer.offered(guarded, null, ScanReview.fromSamples(guarded)))
        assertFalse(CalibrationOffer.offered(guarded, CALIBRATION, ScanReview.fromSamples(guarded, CALIBRATION)))
        val two = solved(setOf(Color.WHITE, Color.RED))
        assertTrue(CalibrationOffer.offered(two, null, ScanReview.fromSamples(two)))
        assertTrue(ScanReview.fromSamples(two).blanks.isNotEmpty())
        assertTrue(ScanReview.fromSamples(two, CALIBRATION).blanks.isEmpty())
        assertEquals(emptyList<Face>(), GuidedScanController.defaultReprompt(two, CALIBRATION))
        val five = ScanReview(List(54) { if (it in listOf(0, 1, 2, 3, 5)) null else Color.home(Face.entries[it / 9]) }, 0)
        val six = ScanReview(five.stickers.toMutableList().also { it[6] = null }, 0)
        assertFalse(CalibrationOffer.offered(plain, CALIBRATION, five))
        assertTrue(CalibrationOffer.offered(plain, CALIBRATION, six))
    }

    @Test fun `every live non-center sticker is plausible for its truth color`() {
        val files = File("../../Fixtures/live").walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        assertTrue(files.isNotEmpty())
        files.forEach { file ->
            val labeled = LiveFixtureJson.decode(file.readText()).labeledSample
            labeled.truth.forEachIndexed { index, color ->
                if (index !=
                    4
                ) {
                    assertTrue(
                        ColorPlausibility.plausible(color, labeled.sample.stickers[index]),
                        "${file.path}: $index $color ${labeled.sample.stickers[index]}",
                    )
                }
            }
        }
    }
}
