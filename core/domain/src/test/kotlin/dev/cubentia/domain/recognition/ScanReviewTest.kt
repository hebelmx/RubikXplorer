package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Facelets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScanReviewTest {
    private val solved = CubeState.SOLVED.toFacelets()

    private fun full(uncertain: Set<Int> = emptySet()) =
        ScanResult.Full(
            solved,
            List(54) {
                if (it in
                    uncertain
                ) {
                    StickerConfidence.UNCERTAIN
                } else {
                    StickerConfidence.CONFIDENT
                }
            },
        )

    @Test fun `confident solved scan accepts with no selection or problems`() {
        val review = ScanReview.from(full())
        assertTrue(review.blanks.isEmpty())
        assertNull(review.selected)
        assertEquals(solved, review.accepted?.toFacelets())
        assertTrue(review.problems.isEmpty())
        assertSame(review, review.paint(Color.RED))
    }

    @Test fun `uncertain guesses stay blank and painting advances then stays`() {
        val review = ScanReview.from(full(setOf(7, 20)))
        assertEquals(listOf(7, 20), review.blanks)
        assertEquals(7, review.selected)
        assertNull(review.accepted)
        val first = review.paint(Color.WHITE)
        assertEquals(20, first.selected)
        val done = first.paint(Color.GREEN)
        assertEquals(20, done.selected)
        assertEquals(solved, done.accepted?.toFacelets())
        assertEquals(listOf(7, 20), review.blanks)
    }

    @Test fun `advance wraps and centers cannot be selected or painted`() {
        val review = ScanReview.from(full(setOf(0, 53, 4))).select(53).paint(Color.BLUE)
        assertEquals(0, review.selected)
        assertSame(review, review.select(4))
        assertSame(review, review.select(-1))
        assertSame(review, review.select(54))
        assertEquals(Color.WHITE, ScanReview(List(54) { null }, 4).paint(Color.RED).stickers[4])
    }

    @Test fun `partial gaps blank all eight non centers even with supplied guesses`() {
        val faces = Face.entries.associateWith { face -> List(9) { Color.home(face) } }
        val partial =
            ScanResult.Partial(
                PhysicalObservation.Partial(faces),
                Face.entries.associateWith { List(9) { StickerConfidence.CONFIDENT } },
                mapOf(Face.B to ScanGap.NO_POSE),
            )
        val review = ScanReview.from(partial)
        assertEquals((45..53).filter { it != 49 }, review.blanks)
        assertEquals(Color.BLUE, review.stickers[49])
        assertNull(review.accepted)
        val absent = ScanReview.from(partial.copy(observation = PhysicalObservation.Partial(faces - Face.B)))
        assertEquals(review, absent)
    }

    @Test fun `needs calibration blanks all non centers`() {
        val review = ScanReview.fromSamples(emptyList())
        assertEquals(48, review.blanks.size)
        assertEquals(0, review.selected)
        assertNull(review.accepted)
        assertTrue(review.problems.isEmpty())
    }

    @Test fun `sample pipeline calibrates then assembles confident solved faces`() {
        val samples =
            GuidedScanPlan.steps.map { step ->
                RecognitionFixtures.sample(
                    center = step.centerColor,
                    up = step.upNeighborColor,
                    stickers =
                        List(9) {
                            RecognitionFixtures.LABS.getValue(step.centerColor)
                        },
                )
            }
        assertEquals(solved, ScanReview.fromSamples(samples).accepted?.toFacelets())
    }

    @Test fun `sample pipeline leaves a close yellow white reading blank until painted`() {
        val index = 0
        val samples =
            GuidedScanPlan.steps.map { step ->
                RecognitionFixtures.sample(
                    center = step.centerColor,
                    up = step.upNeighborColor,
                    stickers =
                        List(9) { cell ->
                            if (step.face.ordinal * 9 + cell == index) {
                                Lab(88.0, -2.0, 38.0)
                            } else {
                                RecognitionFixtures.LABS.getValue(step.centerColor)
                            }
                        },
                )
            }

        val review = ScanReview.fromSamples(samples)
        assertEquals(listOf(index), review.blanks)
        assertEquals(index, review.selected)
        assertNull(review.accepted)
        assertEquals(solved, review.paint(solved.colors[index]).accepted?.toFacelets())
    }

    @Test fun `piece lines precede validator lines and only filled pieces are checked`() {
        val colors = solved.colors.toMutableList().also { it[9] = Color.WHITE }
        val review = ScanReview(colors, null)
        assertEquals(
            "msg.validation.piece-repeats.white",
            review.problems
                .first()
                .reason.key,
        )
        assertEquals(listOf(8, 9), review.problems.first().positions)
        assertEquals(
            "msg.validation.wrong-color-counts",
            review.problems
                .last()
                .reason.key,
        )
        assertEquals(listOf(8, 9, 20), review.problems.first().piece)
        assertTrue(
            review.problems
                .last()
                .piece
                .isEmpty(),
        )
        assertTrue(
            review.problems
                .last()
                .positions
                .isEmpty(),
        )
        assertEquals(setOf(8, 9, 20), review.outlined)
        assertNull(review.accepted)
        colors[20] = Color.RED
        val incomplete = ScanReview(colors.mapIndexed { i, c -> if (i == 8) null else c }, null)
        assertTrue(incomplete.problems.none { 9 in it.positions })
    }

    @Test fun `validator problems link all stickers of impossible corners and outlines include every piece`() {
        val colors =
            solved.colors.toMutableList().also {
                val red = it[9]
                it[9] = it[36]
                it[36] = red
                val whiteEdge = it[5]
                it[5] = it[37]
                it[37] = whiteEdge
            }
        val review = ScanReview(colors, null)
        val corners =
            review.problems.single {
                it.reason.key == "msg.validation.impossible-piece" && it.reason.args["piece"] == "corner"
            }
        assertEquals(listOf(0, 8, 9, 20, 36, 47), corners.positions)
        assertEquals(corners.positions, corners.piece)
        assertEquals(setOf(0, 3, 5, 8, 9, 10, 20, 36, 37, 47), review.outlined)
    }

    @Test fun `with right counts but impossible pieces the review opens on the first conflicting sticker (#174)`() {
        val colors =
            solved.colors.toMutableList().also {
                it[9] = Color.ORANGE
                it[36] = Color.RED
            }
        val confident = List(54) { StickerConfidence.CONFIDENT }
        val review = ScanReview.from(ScanResult.Full(Facelets(colors), confident))
        assertEquals(listOf(0, 8, 9, 20, 36, 47), review.problems.first().positions)
        assertEquals(0, review.selected)
        // Blanks still come first.
        val blank =
            ScanReview.from(
                ScanResult.Full(Facelets(colors), confident.toMutableList().also { it[30] = StickerConfidence.UNCERTAIN }),
            )
        assertEquals(30, blank.selected)
        // A twisted corner names no sticker: nothing is pre-selected.
        val twisted =
            solved.colors.toMutableList().also {
                val a = it[8]
                it[8] = it[9]
                it[9] = it[20]
                it[20] = a
            }
        assertNull(ScanReview.from(ScanResult.Full(Facelets(twisted), confident)).selected)
    }

    @Test fun `complete twist flip and parity failures never accept`() {
        val variants =
            listOf(
                solved.colors.toMutableList().also {
                    val a = it[8]
                    it[8] = it[9]
                    it[9] = it[20]
                    it[20] = a
                },
                solved.colors.toMutableList().also {
                    val a = it[7]
                    it[7] = it[19]
                    it[19] = a
                },
                solved.colors.toMutableList().also {
                    val a = it[19]
                    it[19] = it[10]
                    it[10] = a
                },
            )
        variants.forEach { colors ->
            val review = ScanReview(colors, null)
            assertNull(review.accepted)
            assertTrue(review.problems.all { it.piece.isEmpty() && it.positions.isEmpty() })
            assertTrue(review.outlined.isEmpty())
            assertEquals(CubeValidator.problems(Facelets(colors)).map { it.reason }, review.problems.map { it.reason })
        }
    }

    @Test fun `input list is defensively copied and equality reflects values`() {
        val input = solved.colors.toMutableList()
        val review = ScanReview(input, null)
        input[0] = Color.RED
        assertEquals(solved.colors, review.stickers)
        assertEquals(review, ScanReview(solved.colors, null))
        assertEquals(review.hashCode(), ScanReview(solved.colors, null).hashCode())
        assertNotEquals(review, review.select(0))
    }

    // Story 10.16: the review after a retake.

    private val scrambled =
        CubeState.SOLVED
            .apply(
                listOf(
                    dev.cubentia.domain.cube
                        .FaceTurn(Face.R, dev.cubentia.domain.cube.Turn.CLOCKWISE),
                    dev.cubentia.domain.cube
                        .FaceTurn(Face.U, dev.cubentia.domain.cube.Turn.COUNTERCLOCKWISE),
                    dev.cubentia.domain.cube
                        .FaceTurn(Face.F, dev.cubentia.domain.cube.Turn.HALF),
                    dev.cubentia.domain.cube
                        .FaceTurn(Face.L, dev.cubentia.domain.cube.Turn.CLOCKWISE),
                    dev.cubentia.domain.cube
                        .FaceTurn(Face.D, dev.cubentia.domain.cube.Turn.CLOCKWISE),
                    dev.cubentia.domain.cube
                        .FaceTurn(Face.B, dev.cubentia.domain.cube.Turn.COUNTERCLOCKWISE),
                    dev.cubentia.domain.cube
                        .FaceTurn(Face.R, dev.cubentia.domain.cube.Turn.HALF),
                    dev.cubentia.domain.cube
                        .FaceTurn(Face.F, dev.cubentia.domain.cube.Turn.CLOCKWISE),
                ),
            ).toFacelets()

    /** The face [slot] of [facelets] shown upright with no named top, read as the [colors] Labs. */
    private fun shown(
        facelets: Facelets,
        slot: Face,
        colors: List<Color> = facelets.colors.subList(slot.ordinal * 9, slot.ordinal * 9 + 9),
    ) = RecognitionFixtures.sample(center = Color.home(slot), stickers = colors.map(RecognitionFixtures.LABS::getValue), face = slot)

    private fun faceRange(face: Face) = face.ordinal * 9 until face.ordinal * 9 + 9

    @Test fun `a retake keeps kept faces with their edits and replaces retaken faces and their edits`() {
        // F was misread as all green; the Learner fixed one sticker on U and one on F, then retakes F.
        val misread = Face.entries.map { if (it == Face.F) shown(scrambled, it, List(9) { Color.GREEN }) else shown(scrambled, it) }
        val first = ScanReview.fromSamples(misread)
        val uEdit = 0
        val fEdit = 18
        val before = first.corrected(mapOf(uEdit to Color.YELLOW, fEdit to Color.BLUE))
        val retake = listOf(shown(scrambled, Face.F).copy(orientationDegrees = 90))
        val after = ScanReview.retaken(before, retake, misread)
        (Face.entries - Face.F).flatMap(::faceRange).forEach { assertEquals(before.stickers[it], after.stickers[it]) }
        assertEquals(Color.YELLOW, after.stickers[uEdit])
        assertEquals(scrambled.colors.subList(18, 27), after.stickers.subList(18, 27))
        // Kept evidence is unchanged; the edited sticker still has no Lab.
        assertNull(after.evidence!!.labs[uEdit])
        assertEquals(before.evidence!!.labs[1], after.evidence!!.labs[1])
        assertEquals(scrambled.colors.subList(18, 27).map(RecognitionFixtures.LABS::getValue), after.evidence!!.labs.subList(18, 27))
    }

    @Test fun `a retake that resolves the cube accepts it and selects nothing`() {
        val misread = Face.entries.map { if (it == Face.R) shown(scrambled, it, List(9) { Color.RED }) else shown(scrambled, it) }
        val before = ScanReview(scrambled.colors.mapIndexed { i, color -> if (i in faceRange(Face.R)) Color.RED else color }, null)
        val after = ScanReview.retaken(before, listOf(shown(scrambled, Face.R)), misread)
        assertEquals(scrambled, after.accepted?.toFacelets())
        assertNull(after.selected)
    }

    @Test fun `a retaken face that cannot be placed is blank and pre-selected`() {
        val samples = Face.entries.map { shown(scrambled, it) }
        val before = ScanReview.fromSamples(samples)
        val after =
            ScanReview.retaken(
                before,
                listOf(
                    shown(scrambled, Face.F).copy(
                        stickers =
                            List(8) {
                                RecognitionFixtures.LABS.getValue(Color.GREEN)
                            },
                    ),
                ),
                samples,
            )
        assertEquals(faceRange(Face.F).filter { it != 22 }, after.blanks)
        assertEquals(18, after.selected)
        (Face.entries - Face.F).flatMap(::faceRange).forEach { assertEquals(before.stickers[it], after.stickers[it]) }
    }

    @Test fun `an uncalibrated retake blanks retaken faces and keeps the rest`() {
        val samples = Face.entries.map { shown(scrambled, it) }
        val before = ScanReview.fromSamples(samples)
        // The retaken red face is unusable for self-calibration and no calibration is saved.
        val after = ScanReview.retaken(before, listOf(shown(scrambled, Face.R).copy(quality = CaptureQuality.BLURRED)), samples)
        assertEquals(faceRange(Face.R).filter { it != 13 }, after.blanks)
        (Face.entries - Face.R).flatMap(::faceRange).forEach { assertEquals(before.stickers[it], after.stickers[it]) }
        assertEquals(9, after.selected)
    }

    @Test fun `a two-face uncalibrated Guided retake blanks both retaken faces and clears their evidence Labs`() {
        val samples = Face.entries.map { shown(scrambled, it) }
        val before = ScanReview.fromSamples(samples)
        val blurredR = shown(scrambled, Face.R).copy(quality = CaptureQuality.BLURRED)
        val okF = shown(scrambled, Face.F)
        val after = ScanReview.retaken(before, listOf(blurredR, okF), samples, saved = null)
        val expectedBlanks = (faceRange(Face.R).filter { it != 13 } + faceRange(Face.F).filter { it != 22 }).sorted()
        assertEquals(expectedBlanks, after.blanks)
        val evidence = requireNotNull(after.evidence)
        (faceRange(Face.R) + faceRange(Face.F)).forEach { idx ->
            assertNull(evidence.labs[idx], "Retaken slot $idx must clear Lab on NeedsCalibration")
        }
    }

    @Test fun `merged samples replace retaken faces in place`() {
        val samples = Face.entries.map { shown(scrambled, it) }
        val again = shown(scrambled, Face.F).copy(orientationDegrees = 180)
        val merged = ScanReview.mergedSamples(samples, listOf(again))
        assertEquals(samples.map { it.face }, merged.map { it.face })
        assertSame(again, merged[Face.F.ordinal])
    }
}
