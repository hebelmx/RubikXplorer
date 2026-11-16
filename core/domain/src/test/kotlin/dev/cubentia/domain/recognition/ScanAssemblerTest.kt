package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.recognition.RecognitionFixtures.CALIBRATION
import dev.cubentia.domain.recognition.RecognitionFixtures.LABS
import dev.cubentia.domain.recognition.RecognitionFixtures.sample
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScanAssemblerTest {
    private val neighbors =
        mapOf(
            Face.U to listOf(Face.B, Face.R, Face.F, Face.L),
            Face.D to listOf(Face.F, Face.R, Face.B, Face.L),
            Face.R to listOf(Face.U, Face.B, Face.D, Face.F),
            Face.F to listOf(Face.U, Face.R, Face.D, Face.L),
            Face.L to listOf(Face.U, Face.F, Face.D, Face.B),
            Face.B to listOf(Face.U, Face.L, Face.D, Face.R),
        )

    private fun <T> clockwise(
        cells: List<T>,
        turns: Int,
    ): List<T> = ScanAssembler.rotateClockwise(cells, turns)

    /**
     * What the camera would deliver for [slot] of [facelets] held with the up neighbor at net direction [direction] and a
     * sensor needing [orientation] degrees clockwise: the net turned [direction] counter-clockwise, then pixel order turned
     * back counter-clockwise by the orientation.
     */
    private fun capture(
        facelets: Facelets,
        slot: Face,
        direction: Int,
        orientation: Int,
    ): FaceSample {
        val net = facelets.colors.subList(slot.ordinal * 9, slot.ordinal * 9 + 9)
        val viewfinder = clockwise(net, 4 - direction)
        val pixels = clockwise(viewfinder, 4 - orientation / 90)
        return sample(
            center = Color.home(slot),
            stickers = pixels.map { LABS.getValue(it) },
            up = Color.home(neighbors.getValue(slot)[direction]),
            orientation = orientation,
        )
    }

    private val solved = CubeState.SOLVED.toFacelets()

    /** [capture] with no named top (Story 10.14): the face held at net direction [turn], then the top dropped. */
    private fun unposed(
        facelets: Facelets,
        slot: Face,
        turn: Int,
        orientation: Int = 0,
    ): FaceSample = capture(facelets, slot, turn, orientation).copy(face = slot, upNeighborColor = null)

    private fun unposedSamples(
        facelets: Facelets,
        turns: List<Int> = List(6) { 0 },
    ): List<FaceSample> = Face.entries.map { unposed(facelets, it, turns[it.ordinal]) }

    private val moves = Face.entries.flatMap { face -> Turn.entries.map { FaceTurn(face, it) as Move } }

    /** A deep scramble: one valid state whatever the faces' turns. */
    private val scrambled =
        CubeState.SOLVED
            .apply(
                listOf(
                    FaceTurn(Face.R, Turn.CLOCKWISE),
                    FaceTurn(Face.U, Turn.COUNTERCLOCKWISE),
                    FaceTurn(Face.F, Turn.HALF),
                    FaceTurn(Face.L, Turn.CLOCKWISE),
                    FaceTurn(Face.D, Turn.CLOCKWISE),
                    FaceTurn(Face.B, Turn.COUNTERCLOCKWISE),
                    FaceTurn(Face.R, Turn.HALF),
                    FaceTurn(Face.F, Turn.CLOCKWISE),
                    FaceTurn(Face.U, Turn.HALF),
                    FaceTurn(Face.L, Turn.COUNTERCLOCKWISE),
                ),
            ).toFacelets()

    /** R2 alone: turning U, F, D and B by a half turn gives L2, also valid. */
    private val r2 = CubeState.SOLVED.apply(listOf(FaceTurn(Face.R, Turn.HALF))).toFacelets()

    private val allConfident = List(54) { StickerConfidence.CONFIDENT }

    private fun range(face: Face) = face.ordinal * 9 until face.ordinal * 9 + 9

    private fun solvedSamples(): List<FaceSample> = Face.entries.map { capture(solved, it, 0, 0) }

    @Test
    fun `clockwise quarter turn follows the design note`() {
        assertEquals(listOf(6, 3, 0, 7, 4, 1, 8, 5, 2), clockwise((0..8).toList(), 1))
        assertEquals((0..8).toList(), clockwise((0..8).toList(), 4))
        assertEquals(clockwise((0..8).toList(), 3), clockwise((0..8).toList(), -1))
    }

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `scrambled cube with random per-face turns and orientations assembles to its facelets`() =
        runBlocking<Unit> {
            val moves = Face.entries.flatMap { face -> Turn.entries.map { FaceTurn(face, it) as Move } }
            checkAll(
                PropTestConfig(seed = 101, iterations = 300),
                Arb.list(Arb.element(moves), 0..30),
                Arb.list(Arb.int(0..3), 6..6),
                Arb.list(Arb.element(0, 90, 180, 270), 6..6),
                Arb.int(0..5),
            ) { scramble, directions, orientations, shift ->
                val facelets = CubeState.SOLVED.apply(scramble).toFacelets()
                val samples =
                    Face.entries.map { capture(facelets, it, directions[it.ordinal], orientations[it.ordinal]) }
                // Order and the prompted `face` field do not matter; placement follows the center color.
                val shuffled = (samples.drop(shift) + samples.take(shift)).map { it.copy(face = Face.D) }
                val result = ScanAssembler.assemble(shuffled, CALIBRATION)
                assertEquals(ScanResult.Full(facelets, List(54) { StickerConfidence.CONFIDENT }), result)
            }
        }

    @Test
    fun `colors and confidences rotate together`() {
        val stickers = List(9) { LABS.getValue(Color.GREEN) }.toMutableList()
        // Pixel cell 0 is a close call between WHITE (90, 0, 0) and YELLOW (85, -5, 80).
        stickers[0] = Lab(88.0, -2.0, 38.0)
        val samples =
            solvedSamples().map {
                if (it.centerColor ==
                    Color.GREEN
                ) {
                    it.copy(stickers = stickers, orientationDegrees = 90)
                } else {
                    it
                }
            }
        val full = ScanAssembler.assemble(samples, CALIBRATION) as ScanResult.Full
        val green = Face.F.ordinal * 9
        // Pixel cell 0 turned one quarter clockwise lands on cell 2.
        assertEquals(StickerConfidence.UNCERTAIN, full.confidence[green + 2])
        assertEquals(1, full.confidence.count { it == StickerConfidence.UNCERTAIN })
    }

    @Test
    fun `center takes the prompted color and is confident only when read so`() {
        val samples =
            solvedSamples().map {
                if (it.centerColor ==
                    Color.RED
                ) {
                    it.copy(
                        stickers =
                            it.stickers.mapIndexed { i, lab ->
                                if (i ==
                                    4
                                ) {
                                    LABS.getValue(Color.BLUE)
                                } else {
                                    lab
                                }
                            },
                    )
                } else {
                    it
                }
            }
        val full = ScanAssembler.assemble(samples, CALIBRATION) as ScanResult.Full
        assertEquals(Color.RED, full.facelets.colors[Face.R.ordinal * 9 + 4])
        assertEquals(StickerConfidence.UNCERTAIN, full.confidence[Face.R.ordinal * 9 + 4])
    }

    @Test
    fun `54 colors are Full without validation`() {
        val samples = solvedSamples().map { it.copy(stickers = List(9) { LABS.getValue(Color.WHITE) }) }
        val full = ScanAssembler.assemble(samples, CALIBRATION) as ScanResult.Full
        // 48 non-center stickers read WHITE plus the U center; validation is CubeValidator's job, not the assembler's.
        assertEquals(49, full.facelets.colors.count { it == Color.WHITE })
        assertTrue(CubeValidator.validate(full.facelets) !is ValidationResult.Valid)
    }

    @Test
    fun `missing face gives a partial with five faces`() {
        val result = ScanAssembler.assemble(solvedSamples().filterNot { it.centerColor == Color.YELLOW }, CALIBRATION) as ScanResult.Partial
        assertEquals(
            Face.entries - Face.D,
            result.observation.faces.keys
                .toList(),
        )
        assertEquals(mapOf(Face.D to ScanGap.MISSING), result.gaps)
        assertEquals(List(9) { Color.WHITE }, result.observation.faces.getValue(Face.U))
        assertEquals(List(9) { StickerConfidence.CONFIDENT }, result.confidence.getValue(Face.U))
        assertEquals(null, result.observation.orientationHint)
    }

    private fun gapFor(change: (FaceSample) -> FaceSample): Map<Face, ScanGap> {
        val samples = solvedSamples().map { if (it.centerColor == Color.GREEN) change(it) else it }
        val result = ScanAssembler.assemble(samples, CALIBRATION) as ScanResult.Partial
        assertTrue(Face.F !in result.observation.faces)
        return result.gaps
    }

    @Test
    fun `bad capture is absent with its gap`() {
        assertEquals(mapOf(Face.F to ScanGap.POOR_QUALITY), gapFor { it.copy(quality = CaptureQuality.GLARE) })
        assertEquals(mapOf(Face.F to ScanGap.MALFORMED), gapFor { it.copy(stickers = it.stickers.take(8)) })
        assertEquals(mapOf(Face.F to ScanGap.MALFORMED), gapFor { it.copy(stickers = it.stickers.map { Lab(120.0, 0.0, 0.0) }) })
    }

    @Test
    fun `bad pose is NO_POSE`() {
        assertEquals(mapOf(Face.F to ScanGap.NO_POSE), gapFor { it.copy(upNeighborColor = Color.GREEN) })
        assertEquals(mapOf(Face.F to ScanGap.NO_POSE), gapFor { it.copy(upNeighborColor = Color.BLUE) })
        assertEquals(mapOf(Face.F to ScanGap.NO_POSE), gapFor { it.copy(orientationDegrees = 45) })
        // No center color: the gap is reported on the face the prompt named.
        assertEquals(mapOf(Face.F to ScanGap.NO_POSE), gapFor { it.copy(centerColor = null, face = Face.F) })
    }

    @Test
    fun `a sample without a center color does not take a claimed face`() {
        // The stray names F, which the GREEN sample claims by its center color: F is still placed.
        val stray = sample(null, face = Face.F)
        assertTrue(ScanAssembler.assemble(solvedSamples() + stray, CALIBRATION) is ScanResult.Full)
        // With F unclaimed, the stray reports NO_POSE there; an unnamed face is MISSING.
        val partial =
            ScanAssembler.assemble(
                solvedSamples().filterNot { it.centerColor == Color.GREEN } + stray,
                CALIBRATION,
            ) as ScanResult.Partial
        assertEquals(mapOf(Face.F to ScanGap.NO_POSE), partial.gaps)
    }

    @Test
    fun `duplicate center places neither`() {
        val samples = solvedSamples() + solvedSamples().first { it.centerColor == Color.ORANGE }
        val result = ScanAssembler.assemble(samples, CALIBRATION) as ScanResult.Partial
        assertEquals(mapOf(Face.L to ScanGap.DUPLICATE), result.gaps)
        assertEquals(5, result.observation.faces.size)
    }

    @Test
    fun `bad calibration leaves every face uncalibrated`() {
        val expected =
            ScanResult.Partial(
                PhysicalObservation.Partial(emptyMap()),
                emptyMap(),
                Face.entries.associateWith { ScanGap.UNCALIBRATED },
            )
        assertEquals(expected, ScanAssembler.assemble(solvedSamples(), CalibrationCenters(CALIBRATION.centers - Color.RED)))
        assertEquals(
            expected,
            ScanAssembler.assemble(solvedSamples(), CalibrationCenters(CALIBRATION.centers + (Color.RED to Lab(50.0, Double.NaN, 0.0)))),
        )
    }

    // Story 10.14: any rotation

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `a deep scramble with every face in a random turn and no named top assembles to its facelets`() =
        runBlocking<Unit> {
            var resolved = 0
            var iterations = 0
            checkAll(
                PropTestConfig(seed = 1014, iterations = 200),
                Arb.list(Arb.element(moves), 25..30),
                Arb.list(Arb.int(0..3), 6..6),
                Arb.list(Arb.element(0, 90, 180, 270), 6..6),
            ) { scramble, turns, orientations ->
                iterations++
                val facelets = CubeState.SOLVED.apply(scramble).toFacelets()
                val samples = Face.entries.map { unposed(facelets, it, turns[it.ordinal], orientations[it.ordinal]) }
                assertTrue(samples.all { it.upNeighborColor == null })
                val full = ScanAssembler.assemble(samples, CALIBRATION) as ScanResult.Full
                val involved = ScanAssembler.repromptFaces(samples, CALIBRATION)
                if (involved.isEmpty()) {
                    resolved++
                    assertEquals(ScanResult.Full(facelets, allConfident), full)
                } else {
                    // A rare state where another turn of a face is also a valid cube: never a confidently wrong turn,
                    // and the faces involved, once posed, give the cube.
                    assertTrue(
                        full.facelets.colors.indices.none {
                            full.facelets.colors[it] != facelets.colors[it] &&
                                full.confidence[it] == StickerConfidence.CONFIDENT
                        },
                    )
                    val posed = samples.map { if (it.face in involved) capture(facelets, it.face, 0, 0) else it }
                    assertEquals(ScanResult.Full(facelets, allConfident), ScanAssembler.assemble(posed, CALIBRATION))
                }
            }
            // Ambiguity is the exception: almost every deep scramble resolves with no re-prompt.
            assertTrue(resolved >= iterations * 95 / 100, "$resolved of $iterations resolved")
        }

    @Test
    fun `a solved cube in any turns is one distinct state`() {
        listOf(List(6) { 0 }, listOf(1, 2, 3, 0, 1, 2), List(6) { 3 }).forEach { turns ->
            val samples = unposedSamples(solved, turns)
            assertEquals(ScanResult.Full(solved, allConfident), ScanAssembler.assemble(samples, CALIBRATION))
            assertEquals(emptyList<Face>(), ScanAssembler.repromptFaces(samples, CALIBRATION))
        }
    }

    @Test
    fun `posed and unposed faces mix, each posed face at its prompted turn`() {
        val samples =
            Face.entries.map { face ->
                if (face.ordinal % 2 == 0) capture(scrambled, face, (face.ordinal + 1) % 4, 90) else unposed(scrambled, face, 3, 180)
            }
        assertEquals(ScanResult.Full(scrambled, allConfident), ScanAssembler.assemble(samples, CALIBRATION))
        assertEquals(emptyList<Face>(), ScanAssembler.repromptFaces(samples, CALIBRATION))
    }

    @Test
    fun `several valid states re-prompt the faces that differ, in plan order, and blank them`() {
        val samples = unposedSamples(r2)
        // R2 and L2 differ on U, F, D and B; R and L are all one color either way.
        assertEquals(listOf(Face.F, Face.B, Face.U, Face.D), ScanAssembler.repromptFaces(samples, CALIBRATION))
        val full = ScanAssembler.assemble(samples, CALIBRATION) as ScanResult.Full
        assertTrue(CubeValidator.validate(full.facelets) is ValidationResult.Valid)
        listOf(Face.U, Face.F, Face.D, Face.B).forEach { face ->
            range(face).forEach { i ->
                val expected = if (i % 9 == 4) StickerConfidence.CONFIDENT else StickerConfidence.UNCERTAIN
                assertEquals(expected, full.confidence[i], "$face $i")
            }
        }
        listOf(Face.R, Face.L).forEach { face -> range(face).forEach { assertEquals(StickerConfidence.CONFIDENT, full.confidence[it]) } }
        // Posing the involved faces (their prompted turn) resolves it.
        val reprompted = samples.map { if (it.face in listOf(Face.F, Face.B, Face.U, Face.D)) capture(r2, it.face, 0, 0) else it }
        assertEquals(ScanResult.Full(r2, allConfident), ScanAssembler.assemble(reprompted, CALIBRATION))
        assertEquals(emptyList<Face>(), ScanAssembler.repromptFaces(reprompted, CALIBRATION))
    }

    @Test
    fun `a misread sticker re-prompts the faces holding the illegal piece`() {
        // U8 (corner URF: U8, R0, F2) misread as the corner's own R sticker color: no real corner shows that.
        val colors = scrambled.colors.toMutableList()
        colors[8] = colors[9]
        val misread = Facelets(colors)
        val samples = unposedSamples(misread, listOf(1, 2, 3, 0, 1, 2))
        assertEquals(listOf(Face.F, Face.R, Face.U), ScanAssembler.repromptFaces(samples, CALIBRATION))
        // Review gets the best candidate as read: the true turns, with only the misread wrong.
        val full = ScanAssembler.assemble(samples, CALIBRATION) as ScanResult.Full
        assertEquals(misread, full.facelets)
    }

    @Test
    fun `a center that reads another color is re-prompted first, unless it is a logo`() {
        // The red face shown again at the orange step: its center reads RED under the saved calibration.
        val wrong = scrambled.colors.toMutableList()
        range(Face.L).forEachIndexed { i, index -> wrong[index] = scrambled.colors[range(Face.R).first + i] }
        val samples = Face.entries.map { face -> unposed(Facelets(wrong), face, 0).copy(face = face) }
        val involved = ScanAssembler.repromptFaces(samples, CALIBRATION)
        assertEquals(Face.L, involved.first())
        val plan = GuidedScanPlan.steps.map { it.face }
        assertEquals(involved.drop(1), involved.drop(1).sortedBy { plan.indexOf(it) })
        val logo = samples.map { if (it.centerColor == Color.ORANGE) it.copy(centerPlain = false) else it }
        val withLogo = ScanAssembler.repromptFaces(logo, CALIBRATION)
        assertEquals(withLogo.sortedBy { plan.indexOf(it) }, withLogo)
        // A disagreeing center alone does not re-prompt a scan that resolves.
        val oneOff =
            unposedSamples(scrambled).map {
                if (it.centerColor ==
                    Color.ORANGE
                ) {
                    it.copy(stickers = it.stickers.toMutableList().also { s -> s[4] = LABS.getValue(Color.RED) })
                } else {
                    it
                }
            }
        assertEquals(emptyList<Face>(), ScanAssembler.repromptFaces(oneOff, CALIBRATION))
    }

    @Test
    fun `re-prompted faces still invalid keep their prompted read and are not asked again`() {
        val colors = r2.colors.toMutableList()
        colors[range(Face.F).first] = Color.YELLOW
        val misread = Facelets(colors)
        // F and B were re-prompted (posed); U and D are still searched.
        val samples =
            Face.entries.map { face -> if (face == Face.F || face == Face.B) capture(misread, face, 0, 0) else unposed(misread, face, 0) }
        val full = ScanAssembler.assemble(samples, CALIBRATION) as ScanResult.Full
        assertTrue(CubeValidator.validate(full.facelets) !is ValidationResult.Valid)
        // Posed faces keep their read.
        range(Face.B).forEach { assertEquals(misread.colors[it], full.facelets.colors[it]) }
        val involved = ScanAssembler.repromptFaces(samples, CALIBRATION)
        assertTrue(involved.none { it == Face.F || it == Face.B }, "$involved")
    }

    @Test
    fun `no valid state with tied best candidates blanks the faces that differ in review`() {
        // R2 read with one R sticker misread: no assignment is valid, and the R2/L2 tie on U, F, D and B remains.
        val colors = r2.colors.toMutableList()
        colors[10] = Color.ORANGE
        val misread = Facelets(colors)
        val full = ScanAssembler.assemble(unposedSamples(misread), CALIBRATION) as ScanResult.Full
        assertTrue(CubeValidator.validate(full.facelets) !is ValidationResult.Valid)
        val review = ScanReview.from(full)
        listOf(Face.U, Face.F, Face.D, Face.B).forEach { face ->
            range(face).filter { it % 9 != 4 }.forEach { i ->
                assertEquals(StickerConfidence.UNCERTAIN, full.confidence[i], "$face $i")
                assertNull(review.stickers[i], "$face $i")
            }
        }
        range(Face.L).forEach { assertNotNull(review.stickers[it], "L $it") }
    }

    @Test
    fun `a twisted corner with every piece legal resolves to its one best state with nothing to re-prompt`() {
        val colors = scrambled.colors.toMutableList()
        val (a, b, c) = listOf(8, 9, 20)
        val twistedCorner = listOf(colors[c], colors[a], colors[b])
        colors[a] = twistedCorner[0]
        colors[b] = twistedCorner[1]
        colors[c] = twistedCorner[2]
        val twisted = Facelets(colors)
        assertTrue(CubeValidator.validate(twisted) !is ValidationResult.Valid)
        val samples = unposedSamples(twisted, listOf(1, 2, 3, 0, 1, 2))
        assertEquals(ScanResult.Full(twisted, allConfident), ScanAssembler.assemble(samples, CALIBRATION))
        assertEquals(emptyList<Face>(), ScanAssembler.repromptFaces(samples, CALIBRATION))
    }

    @Test
    fun `a gap leaves unposed faces NO_POSE`() {
        val samples = unposedSamples(scrambled).map { if (it.centerColor == Color.GREEN) it.copy(quality = CaptureQuality.GLARE) else it }
        val partial = ScanAssembler.assemble(samples, CALIBRATION) as ScanResult.Partial
        assertEquals(Face.entries.associateWith { if (it == Face.F) ScanGap.POOR_QUALITY else ScanGap.NO_POSE }, partial.gaps)
        assertEquals(emptyList<Face>(), ScanAssembler.repromptFaces(samples, CALIBRATION))
        // A posed face is still placed next to the gap.
        val mixed = samples.map { if (it.centerColor == Color.RED) capture(scrambled, Face.R, 0, 0) else it }
        val withPosed = ScanAssembler.assemble(mixed, CALIBRATION) as ScanResult.Partial
        assertEquals(
            listOf(Face.R),
            withPosed.observation.faces.keys
                .toList(),
        )
        assertEquals(scrambled.colors.slice(range(Face.R)), withPosed.observation.faces.getValue(Face.R))
    }

    @Test
    fun `one unposed face among prompted ones is searched`() {
        val samples = Face.entries.map { if (it == Face.F) unposed(scrambled, it, 2) else capture(scrambled, it, 0, 0) }
        assertEquals(ScanResult.Full(scrambled, allConfident), ScanAssembler.assemble(samples, CALIBRATION))
    }

    @Test
    fun `an uncalibrated scan has nothing to re-prompt`() {
        assertEquals(emptyList<Face>(), ScanAssembler.repromptFaces(unposedSamples(r2), CalibrationCenters(emptyMap())))
    }
}
