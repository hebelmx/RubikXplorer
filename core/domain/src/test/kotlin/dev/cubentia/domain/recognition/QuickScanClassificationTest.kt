package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 10.19: the two light compensations of [QuickScanClassification]'s second read, and the gated reprompt. */
class QuickScanClassificationTest {
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

    /** The six solved faces as corner samples (named poses), each sticker its home color's Lab scaled in L* by [shade]. */
    private fun solved(shade: (Face) -> Double = { 1.0 }): List<FaceSample> =
        QuickScanPlan.holds.flatten().map { step ->
            val lab = labs.getValue(step.centerColor)
            FaceSample(
                face = step.face,
                orientationDegrees = 0,
                stickers = List(9) { lab.copy(l = lab.l * shade(step.face)) },
                quality = CaptureQuality.ACCEPTABLE,
                centerColor = step.centerColor,
                upNeighborColor = step.upNeighborColor,
                centerPlain = true,
            )
        }

    @Test
    fun `the saved-path correction applies the gain from the face's own center to its saved center to every sticker`() {
        val red = solved { if (it == Face.R) 2.0 / 3.0 else 1.0 }.single { it.face == Face.R }
        val shaded = red.stickers.toMutableList().also { it[0] = Lab(20.0, 1.0, 2.0) }
        val corrected = QuickScanClassification.corrected(red.copy(stickers = shaded), saved)
        // Center L* 30 onto saved Red's 45: gain 1.5 on every sticker's L*, a*b* unchanged.
        assertEquals(45.0, corrected[FaceSample.CENTER_INDEX].l, 1e-9)
        assertEquals(Lab(30.0, 1.0, 2.0), corrected[0])
        // A guarded (logo) center gives no gain: the stickers are read as measured.
        val guarded = red.copy(stickers = shaded, centerPlain = false)
        assertEquals(shaded, QuickScanClassification.corrected(guarded, saved))
    }

    @Test
    fun `the no-saved normalization scales each center by this face's level over its captured source face's level`() {
        // F (Green) shaded to half; every other face lit.
        val samples = solved { if (it == Face.F) 0.5 else 1.0 }
        val front = samples.single { it.face == Face.F }
        val centers = SelfCalibration.trustedCenters(samples)
        val normalized = QuickScanClassification.normalized(front, samples, centers)
        // Each solved face's level is its own color's L*, so every center lands on F's level (27.5).
        Color.entries.forEach { color -> assertEquals(27.5, normalized.getValue(color).l, 1e-9, "$color") }
        assertEquals(centers.getValue(Color.RED).a, normalized.getValue(Color.RED).a)
        assertSame(samples.single { it.face == Face.R }, QuickScanClassification.sourceOf(Color.RED, samples))
    }

    @Test
    fun `a center with no captured source is left unscaled`() {
        val samples = solved { if (it == Face.F) 0.5 else 1.0 }
        val front = samples.single { it.face == Face.F }
        // Blue's face is guarded (a logo center) and Orange's face was never captured: no source for either.
        val merged = samples.map { if (it.face == Face.B) it.copy(centerPlain = false) else it }.filter { it.face != Face.L }
        assertNull(QuickScanClassification.sourceOf(Color.BLUE, merged))
        assertNull(QuickScanClassification.sourceOf(Color.ORANGE, merged))
        val centers =
            mapOf(
                Color.BLUE to Lab(41.0, 10.0, -50.0),
                Color.ORANGE to Lab(66.0, 45.0, 65.0),
                Color.RED to labs.getValue(Color.RED),
            )
        val normalized = QuickScanClassification.normalized(front, merged, centers)
        assertEquals(Lab(41.0, 10.0, -50.0), normalized.getValue(Color.BLUE))
        assertEquals(Lab(66.0, 45.0, 65.0), normalized.getValue(Color.ORANGE))
        assertEquals(27.5, normalized.getValue(Color.RED).l, 1e-9, "a captured source still scales")
    }

    @Test
    fun `involved faces come from the gated read`() {
        val samples = solved()
        val calibration = QuickScanClassification.calibration(samples, null)!!
        // All confident: the gated colors are the self-calibration's, so the assembler decides as before.
        assertEquals(
            ScanAssembler.repromptFaces(samples, calibration, repromptPosed = true),
            QuickScanClassification.involvedFaces(samples, null),
        )
        // Against a swapped saved calibration the gate leaves R uncertain, so R is involved.
        val swapped =
            CalibrationCenters(saved.centers + mapOf(Color.RED to labs.getValue(Color.ORANGE), Color.ORANGE to labs.getValue(Color.RED)))
        assertTrue(Face.R in QuickScanClassification.involvedFaces(samples, swapped))
    }

    @Test
    fun `kept faces are never asked again, and the assembly's reprompt faces are the fallback when only kept faces are uncertain`() {
        // A broken cube (one U edge sticker reads Green) against a swapped saved calibration: R is uncertain after the gate.
        val samples =
            solved().map { sample ->
                if (sample.face ==
                    Face.U
                ) {
                    sample.copy(stickers = sample.stickers.toMutableList().also { it[1] = labs.getValue(Color.GREEN) })
                } else {
                    sample
                }
            }
        val swapped =
            CalibrationCenters(saved.centers + mapOf(Color.RED to labs.getValue(Color.ORANGE), Color.ORANGE to labs.getValue(Color.RED)))
        val uncertain = QuickScanClassification.needGrid(samples, samples.map { it.face }, swapped)
        assertTrue(Face.R in uncertain)
        val kept = uncertain.toSet()
        val calibration = QuickScanClassification.calibration(samples, swapped)!!
        val expected = ScanAssembler.repromptFaces(samples, calibration, repromptPosed = true).filter { it !in kept }
        assertTrue(expected.isNotEmpty(), "the broken cube needs a reprompt outside the kept faces")
        assertEquals(expected, QuickScanClassification.involvedFaces(samples, swapped, kept))
        // With nothing kept, the gated uncertain faces are asked.
        assertEquals(uncertain, QuickScanClassification.involvedFaces(samples, swapped))
    }

    @Test
    fun `without a saved calibration a confident self read that the normalized read contradicts becomes uncertain`() {
        // F (Green) is shaded to half; its first sticker is a dark bluish grey. Against the lit centers it reads
        // confidently Blue; against the centers normalized to F's level only a*b* decides, and it reads confidently White.
        val sticker = Lab(30.0, 3.0, -15.0)
        val samples =
            solved { if (it == Face.F) 0.5 else 1.0 }.map { sample ->
                if (sample.face == Face.F) sample.copy(stickers = sample.stickers.toMutableList().also { it[0] = sticker }) else sample
            }
        val front = samples.single { it.face == Face.F }
        val centers = QuickScanClassification.calibration(samples, null)!!.centers
        val self = StickerClassifier.read(sticker, centers)
        assertEquals(Color.BLUE, self.color)
        assertEquals(StickerConfidence.CONFIDENT, self.confidence)
        val normalized = StickerClassifier.read(sticker, QuickScanClassification.normalized(front, samples, centers))
        assertEquals(Color.WHITE, normalized.color)
        assertEquals(StickerConfidence.CONFIDENT, normalized.confidence)
        val gated = QuickScanClassification.read(front, samples, null)[0]!!
        assertEquals(StickerConfidence.UNCERTAIN, gated.confidence)
        assertTrue(Face.F in QuickScanClassification.needGrid(samples, listOf(Face.F), null))
    }

    @Test
    fun `the Quick Scan calibration is the shared self-calibration, unchanged`() {
        val samples = solved()
        assertEquals(
            (SelfCalibration.from(samples, saved) as SelfCalibration.Calibrated).calibration,
            QuickScanClassification.calibration(samples, saved),
        )
        assertEquals(
            (SelfCalibration.from(samples) as SelfCalibration.Calibrated).calibration,
            QuickScanClassification.calibration(samples, null),
        )
    }

    /** Story 10.23: the report exposes both pre-gate reads for logging; gating them gives exactly what [read] decides. */
    @Test
    fun `the report carries the self and second reads the gate combines, and which second read it was`() {
        val samples = solved { if (it == Face.R) 0.6 else 1.0 }
        listOf(saved to QuickScanClassification.SecondRead.CALIBRATION, null to QuickScanClassification.SecondRead.NORMALIZED)
            .forEach { (calibration, source) ->
                val reports = QuickScanClassification.report(samples, Face.entries, calibration)
                reports.forEach { report ->
                    val sample = samples.single { it.face == report.face }
                    assertEquals(source, report.secondRead)
                    assertEquals(9, report.self.size)
                    assertEquals(9, report.second.size)
                    val gated =
                        report.self.zip(report.second).map { (first, second) ->
                            val agree =
                                first != null && second != null && first.confidence == StickerConfidence.CONFIDENT &&
                                    second.confidence == StickerConfidence.CONFIDENT && first.color == second.color
                            if (agree || first == null) first else first.copy(confidence = StickerConfidence.UNCERTAIN)
                        }
                    assertEquals(QuickScanClassification.read(sample, samples, calibration), gated, "${report.face} $source")
                }
            }
        // A face not captured has no reads.
        val missing = QuickScanClassification.report(samples.filter { it.face != Face.B }, listOf(Face.B), saved).single()
        assertTrue(missing.self.isEmpty() && missing.second.isEmpty() && missing.secondRead == null)
    }
}
