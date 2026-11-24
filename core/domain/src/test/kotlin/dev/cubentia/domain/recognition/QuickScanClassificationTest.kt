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
        // Story 10.21: a swapped saved calibration fails the centre check, so it is ignored: as with none.
        val swapped =
            CalibrationCenters(saved.centers + mapOf(Color.RED to labs.getValue(Color.ORANGE), Color.ORANGE to labs.getValue(Color.RED)))
        assertEquals(CalibrationMatch.Mismatch(CalibrationMatch.Reason.CENTRE), QuickScanClassification.matchAll(samples, swapped))
        assertEquals(QuickScanClassification.involvedFaces(samples, null), QuickScanClassification.involvedFaces(samples, swapped))
        // A sticker the normalized read contradicts leaves F uncertain after the gate, so F is involved.
        val shaded = shadedFront()
        assertTrue(Face.F in QuickScanClassification.involvedFaces(shaded, null))
    }

    /** F (Green) shaded to half with its first sticker a dark bluish grey: the gate leaves it uncertain (see below). */
    private fun shadedFront(): List<FaceSample> =
        solved { if (it == Face.F) 0.5 else 1.0 }.map { sample ->
            if (sample.face ==
                Face.F
            ) {
                sample.copy(stickers = sample.stickers.toMutableList().also { it[0] = Lab(30.0, 3.0, -15.0) })
            } else {
                sample
            }
        }

    @Test
    fun `kept faces are never asked again, and the assembly's reprompt faces are the fallback when only kept faces are uncertain`() {
        // A broken cube (one U edge sticker reads Green) with a green-yellow close call on F: F is uncertain after the gate.
        val samples =
            solved()
                .map { sample ->
                    if (sample.face ==
                        Face.F
                    ) {
                        sample.copy(stickers = sample.stickers.toMutableList().also { it[0] = Lab(69.0, -35.0, 58.0) })
                    } else {
                        sample
                    }
                }.map { sample ->
                    if (sample.face ==
                        Face.U
                    ) {
                        sample.copy(stickers = sample.stickers.toMutableList().also { it[1] = labs.getValue(Color.GREEN) })
                    } else {
                        sample
                    }
                }
        val uncertain = QuickScanClassification.needGrid(samples, samples.map { it.face }, null)
        assertTrue(Face.F in uncertain)
        val kept = uncertain.toSet()
        val calibration = QuickScanClassification.calibration(samples, null)!!
        val expected = ScanAssembler.repromptFaces(samples, calibration, repromptPosed = true).filter { it !in kept }
        assertTrue(expected.isNotEmpty(), "the broken cube needs a reprompt outside the kept faces")
        assertEquals(expected, QuickScanClassification.involvedFaces(samples, null, kept))
        // With nothing kept, the gated uncertain faces are asked.
        assertEquals(uncertain, QuickScanClassification.involvedFaces(samples, null))
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

    // Story 10.21: the calibration match and the keep rule (spec I/O matrix).

    private fun withSticker(
        samples: List<FaceSample>,
        face: Face,
        vararg cells: Pair<Int, Lab>,
    ): List<FaceSample> =
        samples.map { sample ->
            if (sample.face ==
                face
            ) {
                sample.copy(stickers = sample.stickers.toMutableList().also { s -> cells.forEach { (i, lab) -> s[i] = lab } })
            } else {
                sample
            }
        }

    /** Red and Orange equidistant: a close call whatever the centres. */
    private val redOrange = Lab(55.0, 55.0, 55.0)

    @Test
    fun `a saved calibration matches only with the torch it was saved under and centres read as their own colours`() {
        val samples = solved()
        val lit = samples.associate { it.face to true }
        assertNull(QuickScanClassification.matchAll(samples, null))
        assertEquals(CalibrationMatch.Match, QuickScanClassification.matchAll(samples, saved.copy(torchOn = false)))
        assertEquals(CalibrationMatch.Match, QuickScanClassification.matchAll(samples, saved.copy(torchOn = true), lit))
        // Torch mismatch either way; a legacy row (no tag) counts as torch-off, as does a camera with no flash.
        val torch = CalibrationMatch.Mismatch(CalibrationMatch.Reason.TORCH)
        assertEquals(torch, QuickScanClassification.matchAll(samples, saved.copy(torchOn = true)))
        assertEquals(torch, QuickScanClassification.matchAll(samples, saved.copy(torchOn = false), lit))
        assertEquals(CalibrationMatch.Match, QuickScanClassification.matchAll(samples, saved))
        assertEquals(torch, QuickScanClassification.matchAll(samples, saved, lit))
        assertEquals("mismatch torch", torch.label)
        // One captured centre not CONFIDENT as its own colour: a centre mismatch.
        val centre = withSticker(samples, Face.R, FaceSample.CENTER_INDEX to redOrange)
        assertEquals(CalibrationMatch.Mismatch(CalibrationMatch.Reason.CENTRE), QuickScanClassification.matchAll(centre, saved))
        assertEquals("mismatch centre", CalibrationMatch.Mismatch(CalibrationMatch.Reason.CENTRE).label)
        // A guarded (logo) centre is not judged.
        val logo = centre.map { if (it.face == Face.R) it.copy(centerPlain = false) else it }
        assertEquals(CalibrationMatch.Match, QuickScanClassification.matchAll(logo, saved))
        // No plain centre to check (no face at all, or every centre guarded) is never a match.
        val centreMismatch = CalibrationMatch.Mismatch(CalibrationMatch.Reason.CENTRE)
        assertEquals(centreMismatch, QuickScanClassification.matchAll(emptyList(), saved))
        assertEquals(centreMismatch, QuickScanClassification.matchAll(samples.map { it.copy(centerPlain = false) }, saved))
    }

    @Test
    fun `an uncertain plain centre is never the allowed blank, so the matched face goes to the grid`() {
        // L's orange centre reads CONFIDENT against the saved centres (so it matches) but below the Orange margin after the gate.
        val samples = withSticker(solved(), Face.L, FaceSample.CENTER_INDEX to Lab(60.0, 52.0, 58.0))
        assertEquals(CalibrationMatch.Match, QuickScanClassification.matchAll(samples, saved))
        val decision = QuickScanClassification.decide(samples.single { it.face == Face.L }, samples, saved)
        assertEquals(StickerConfidence.UNCERTAIN, decision.readings[FaceSample.CENTER_INDEX]?.confidence)
        assertTrue(!decision.keep, "$decision")
        assertTrue(FaceSample.CENTER_INDEX !in decision.blanks)
        assertTrue(Face.L in QuickScanClassification.needGrid(samples, Face.entries, saved))
    }

    @Test
    fun `with a matching calibration a face with one uncertain sticker is kept with that sticker blank`() {
        val samples = withSticker(solved(), Face.R, 0 to redOrange)
        val r = samples.single { it.face == Face.R }
        val decision = QuickScanClassification.decide(r, samples, saved)
        assertTrue(decision.keep && decision.matched)
        assertEquals(setOf(0), decision.blanks)
        assertEquals(StickerConfidence.UNCERTAIN, decision.readings[0]?.confidence)
        assertTrue(Face.R !in QuickScanClassification.needGrid(samples, Face.entries, saved))
        assertEquals(1, QuickScanClassification.report(samples, listOf(Face.R), saved).single().blanks)
        // Without a match (none, or a torch mismatch) the all-9 rule holds.
        assertTrue(Face.R in QuickScanClassification.needGrid(samples, Face.entries, null))
        assertTrue(Face.R in QuickScanClassification.needGrid(samples, Face.entries, saved.copy(torchOn = true)))
    }

    @Test
    fun `with a matching calibration two uncertain stickers send the face to the grid`() {
        val samples = withSticker(solved(), Face.R, 0 to redOrange, 2 to redOrange)
        val decision = QuickScanClassification.decide(samples.single { it.face == Face.R }, samples, saved)
        assertEquals(setOf(0, 2), decision.blanks)
        assertTrue(!decision.keep)
        assertTrue(Face.R in QuickScanClassification.needGrid(samples, Face.entries, saved))
    }

    @Test
    fun `a hard disagreement, both reads confident on different colours, sends a matched face to the grid`() {
        // R shaded to half: the self read (R's own shaded centre) says Red, the saved read (L* gain 2) says Orange.
        val samples = withSticker(solved { if (it == Face.R) 0.5 else 1.0 }, Face.R, 0 to Lab(25.0, 45.0, 65.0))
        assertEquals(CalibrationMatch.Match, QuickScanClassification.matchAll(samples, saved))
        val reads = QuickScanClassification.stickerReads(samples.single { it.face == Face.R }, samples, saved)[0]
        assertEquals(StickerReading(Color.RED, StickerConfidence.CONFIDENT), reads.first)
        assertEquals(StickerReading(Color.ORANGE, StickerConfidence.CONFIDENT), reads.second)
        val decision = QuickScanClassification.decide(samples.single { it.face == Face.R }, samples, saved)
        assertTrue(decision.hardDisagreement && !decision.keep, "$decision")
        assertEquals(setOf(0), decision.blanks, "one blank alone would be kept: the hard disagreement decides")
    }

    @Test
    fun `the per-colour gate needs each colour's margin from both reads`() {
        val orange = QuickScanClassification.AGREEMENT_MARGIN.getValue(Color.ORANGE)
        assertTrue(orange > StickerClassifier.CLOSE_CALL_DELTA_E)
        assertEquals(
            StickerConfidence.UNCERTAIN,
            QuickScanClassification.gate(Color.ORANGE to 30.0, Color.ORANGE to orange - 0.1)?.confidence,
        )
        assertEquals(StickerConfidence.CONFIDENT, QuickScanClassification.gate(Color.ORANGE to 30.0, Color.ORANGE to orange)?.confidence)
        assertEquals(StickerConfidence.CONFIDENT, QuickScanClassification.gate(Color.BLUE to 10.0, Color.BLUE to 10.0)?.confidence)
        // Different colours never agree, and the first read's colour is kept.
        assertEquals(
            StickerReading(Color.RED, StickerConfidence.UNCERTAIN),
            QuickScanClassification.gate(
                Color.RED to 50.0,
                Color.ORANGE to 50.0,
            ),
        )
        assertNull(QuickScanClassification.gate(null, Color.RED to 50.0))
        // The 10.19 rule is the gate with every margin at the close-call delta.
        assertEquals(
            StickerConfidence.CONFIDENT,
            QuickScanClassification.gate(Color.ORANGE to 15.0, Color.ORANGE to 15.0, QuickScanClassification.STRICT_MARGIN)?.confidence,
        )
    }

    @Test
    fun `quick scan review leaves the kept faces' blanks blank and pre-selects the first`() {
        val samples = withSticker(solved(), Face.R, 0 to labs.getValue(Color.RED))
        val plain = ScanReview.fromSamples(samples, saved)
        val blanked = ScanReview.fromSamples(samples, saved, mapOf(Face.R to setOf(0)))
        assertTrue(plain.stickers.none { it == null })
        val blank = blanked.stickers.indices.filter { blanked.stickers[it] == null }
        assertEquals(1, blank.size, "${blanked.stickers}")
        assertEquals(Face.R, Face.entries[blank.single() / 9])
        assertEquals(blank.single(), blanked.selected)
    }
}
