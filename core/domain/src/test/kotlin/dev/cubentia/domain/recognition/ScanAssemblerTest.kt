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
import dev.cubentia.domain.recognition.RecognitionFixtures.fullRead
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
                assertEquals(fullRead(facelets, List(54) { StickerConfidence.CONFIDENT }), result)
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
                    assertEquals(fullRead(facelets, allConfident), full)
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
                    assertEquals(fullRead(facelets, allConfident), ScanAssembler.assemble(posed, CALIBRATION))
                }
            }
            // Ambiguity is the exception: almost every deep scramble resolves with no re-prompt.
            assertTrue(resolved >= iterations * 95 / 100, "$resolved of $iterations resolved")
        }

    @Test
    fun `a solved cube in any turns is one distinct state`() {
        listOf(List(6) { 0 }, listOf(1, 2, 3, 0, 1, 2), List(6) { 3 }).forEach { turns ->
            val samples = unposedSamples(solved, turns)
            assertEquals(fullRead(solved, allConfident), ScanAssembler.assemble(samples, CALIBRATION))
            assertEquals(emptyList<Face>(), ScanAssembler.repromptFaces(samples, CALIBRATION))
        }
    }

    @Test
    fun `posed and unposed faces mix, each posed face at its prompted turn`() {
        val samples =
            Face.entries.map { face ->
                if (face.ordinal % 2 == 0) capture(scrambled, face, (face.ordinal + 1) % 4, 90) else unposed(scrambled, face, 3, 180)
            }
        assertEquals(fullRead(scrambled, allConfident), ScanAssembler.assemble(samples, CALIBRATION))
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
        assertEquals(fullRead(r2, allConfident), ScanAssembler.assemble(reprompted, CALIBRATION))
        assertEquals(emptyList<Face>(), ScanAssembler.repromptFaces(reprompted, CALIBRATION))
    }

    /** [scrambled] with edge UF (U7, F1) misread: U7 shows F1's color, a piece no real cubie shows. */
    private val misreadEdge = Facelets(scrambled.colors.toMutableList().also { it[7] = it[range(Face.F).first + 1] })

    @Test
    fun `no valid state with one face involved re-prompts it`() {
        // F is posed (re-prompted earlier), so only U is left to turn.
        val samples =
            Face.entries.map { face ->
                if (face ==
                    Face.F
                ) {
                    capture(misreadEdge, face, 0, 0)
                } else {
                    unposed(misreadEdge, face, face.ordinal % 4)
                }
            }
        assertEquals(listOf(Face.U), ScanAssembler.repromptFaces(samples, CALIBRATION))
    }

    @Test
    fun `no valid state with two faces involved re-prompts both, in plan order`() {
        val samples = unposedSamples(misreadEdge, listOf(1, 2, 3, 0, 1, 2))
        assertEquals(listOf(Face.F, Face.U), ScanAssembler.repromptFaces(samples, CALIBRATION))
        val full = ScanAssembler.assemble(samples, CALIBRATION) as ScanResult.Full
        assertEquals(misreadEdge, full.facelets)
    }

    @Test
    fun `no valid state with more than two faces involved goes to review with the best fit (PO 2026-11-16, session B2)`() {
        // U8 (corner URF: U8, R0, F2) misread as the corner's own R sticker color: no real corner shows that.
        val colors = scrambled.colors.toMutableList()
        colors[8] = colors[9]
        val misread = Facelets(colors)
        val samples = unposedSamples(misread, listOf(1, 2, 3, 0, 1, 2))
        assertTrue(ScanAssembler.MAX_REPROMPT_FACES < 3)
        assertEquals(emptyList<Face>(), ScanAssembler.repromptFaces(samples, CALIBRATION))
        // Review gets the best candidate as read: the true turns, with only the misread wrong.
        val full = ScanAssembler.assemble(samples, CALIBRATION) as ScanResult.Full
        assertEquals(misread, full.facelets)
        assertEquals(allConfident, full.confidence)
    }

    @Test
    fun `a center that reads another color is re-prompted first, unless it is a logo, and counts toward the limit`() {
        // The orange center reads RED; with the misread UF edge (F posed) only U is left to turn: two faces involved.
        val samples =
            Face.entries.map { face ->
                val sample = if (face == Face.F) capture(misreadEdge, face, 0, 0) else unposed(misreadEdge, face, 0)
                if (face ==
                    Face.L
                ) {
                    sample.copy(stickers = sample.stickers.toMutableList().also { it[4] = LABS.getValue(Color.RED) })
                } else {
                    sample
                }
            }
        assertEquals(listOf(Face.L, Face.U), ScanAssembler.repromptFaces(samples, CALIBRATION))
        val logo = samples.map { if (it.centerColor == Color.ORANGE) it.copy(centerPlain = false) else it }
        assertEquals(listOf(Face.U), ScanAssembler.repromptFaces(logo, CALIBRATION))
        // The red face shown again at the orange step, with every other face unposed: more than two faces involved.
        val wrong = scrambled.colors.toMutableList()
        range(Face.L).forEachIndexed { i, index -> wrong[index] = scrambled.colors[range(Face.R).first + i] }
        val shownTwice = Face.entries.map { face -> unposed(Facelets(wrong), face, 0).copy(face = face) }
        assertEquals(emptyList<Face>(), ScanAssembler.repromptFaces(shownTwice, CALIBRATION))
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
    fun `a posed face with a disagreeing center is eligible only when re-prompting posed faces`() {
        val samples =
            Face.entries.map { face ->
                val sample = capture(misreadEdge, face, 0, 0)
                if (face == Face.U) {
                    sample.copy(stickers = sample.stickers.toMutableList().also { it[4] = LABS.getValue(Color.RED) })
                } else {
                    sample
                }
            }
        assertEquals(emptyList<Face>(), ScanAssembler.repromptFaces(samples, CALIBRATION))
        assertEquals(listOf(Face.U, Face.F), ScanAssembler.repromptFaces(samples, CALIBRATION, repromptPosed = true))
    }

    @Test
    fun `a posed face with a disagreeing center does not count toward the re-prompt limit`() {
        // U and F unposed hold the illegal UF edge; posed L's center misreads, which would make three faces.
        val samples =
            Face.entries.map { face ->
                when (face) {
                    Face.U, Face.F -> {
                        unposed(misreadEdge, face, face.ordinal % 4)
                    }

                    Face.L -> {
                        val sample = capture(misreadEdge, face, 0, 0)
                        sample.copy(stickers = sample.stickers.toMutableList().also { it[4] = LABS.getValue(Color.RED) })
                    }

                    else -> {
                        capture(misreadEdge, face, 0, 0)
                    }
                }
            }
        assertEquals(listOf(Face.F, Face.U), ScanAssembler.repromptFaces(samples, CALIBRATION))
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
        assertEquals(fullRead(twisted, allConfident), ScanAssembler.assemble(samples, CALIBRATION))
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
        assertEquals(fullRead(scrambled, allConfident), ScanAssembler.assemble(samples, CALIBRATION))
    }

    @Test
    fun `an uncalibrated scan has nothing to re-prompt`() {
        assertEquals(emptyList<Face>(), ScanAssembler.repromptFaces(unposedSamples(r2), CalibrationCenters(emptyMap())))
    }

    @Test
    fun `each sticker's Lab follows its color into net order, posed or searched (#174)`() {
        // Every sticker gets its own Lab (its color's, nudged by its net index), so any misrotation shows.
        val netLabs = scrambled.colors.mapIndexed { index, color -> LABS.getValue(color).let { it.copy(l = it.l + index * 0.01) } }
        val turns = listOf(1, 2, 3, 0, 1, 2)
        val orientations = listOf(90, 0, 270, 180, 90, 0)
        val samples =
            Face.entries.map { slot ->
                val net = netLabs.subList(slot.ordinal * 9, slot.ordinal * 9 + 9)
                val viewfinder = clockwise(net, 4 - turns[slot.ordinal])
                val orientation = orientations[slot.ordinal]
                val pixels = clockwise(viewfinder, 4 - orientation / 90)
                // F is posed (its up neighbor at the held direction); the others are searched.
                val up = if (slot == Face.F) Color.home(neighbors.getValue(slot)[turns[slot.ordinal]]) else null
                sample(center = Color.home(slot), stickers = pixels, up = up, orientation = orientation, face = slot)
            }
        val full = ScanAssembler.assemble(samples, CALIBRATION) as ScanResult.Full
        assertEquals(scrambled, full.facelets)
        assertEquals(netLabs, full.labs)
    }

    // Device diagnostics (Story 10.7 device-fix round 1): the trace shows what the assembler decided and why.

    @Test
    fun `a trace reports the read, the valid count, the branch and the chosen turns`() {
        val turns = listOf(1, 2, 3, 0, 1, 2)
        val resolved = ScanAssembler.trace(unposedSamples(scrambled, turns), CALIBRATION)
        assertEquals(ScanBranch.RESOLVED, resolved.branch)
        assertEquals(1, resolved.valid)
        assertEquals(emptyList<Face>(), resolved.involved)
        assertEquals(Face.entries.associateWith { turns[it.ordinal] }, resolved.turns)
        val u = resolved.samples.first()
        assertEquals(Face.U, u.face)
        assertEquals(clockwise(scrambled.colors.subList(0, 9), 4 - turns[0]), u.read)

        val ambiguous = ScanAssembler.trace(unposedSamples(r2), CALIBRATION)
        assertEquals(ScanBranch.AMBIGUOUS, ambiguous.branch)
        assertTrue(ambiguous.valid!! >= 2)
        assertEquals(ScanAssembler.repromptFaces(unposedSamples(r2), CALIBRATION), ambiguous.involved)

        val oneTurnProblem = ScanAssembler.trace(unposedSamples(misreadEdge, turns), CALIBRATION)
        assertEquals(ScanBranch.NO_VALID_REPROMPT, oneTurnProblem.branch)
        assertEquals(0, oneTurnProblem.valid)
        assertEquals(listOf(Face.F, Face.U), oneTurnProblem.involved)

        val colors = scrambled.colors.toMutableList()
        colors[8] = colors[9]
        val misread = ScanAssembler.trace(unposedSamples(Facelets(colors), turns), CALIBRATION)
        assertEquals(ScanBranch.NO_VALID_REVIEW, misread.branch)
        assertEquals(0, misread.valid)
        assertEquals(emptyList<Face>(), misread.involved)
    }

    @Test
    fun `a trace of a partial or uncalibrated scan runs no search`() {
        val partial = ScanAssembler.trace(solvedSamples().filterNot { it.centerColor == Color.YELLOW }, CALIBRATION)
        assertEquals(ScanBranch.PARTIAL, partial.branch)
        assertNull(partial.valid)
        assertEquals((Face.entries - Face.D).associateWith { 0 }, partial.turns)
        val uncalibrated = ScanAssembler.trace(solvedSamples(), CalibrationCenters(emptyMap()))
        assertEquals(ScanBranch.UNCALIBRATED, uncalibrated.branch)
        assertTrue(uncalibrated.samples.all { it.read == null })
    }

    @Test
    fun `a trace describes itself as one line of colors and counts`() {
        val line = ScanAssembler.trace(unposedSamples(solved), CALIBRATION).describe()
        assertTrue(line.startsWith("branch=RESOLVED valid=1 involved=- turns=U0,R0,F0,D0,L0,B0 "), line)
        assertTrue("U:c=W/up=-/o=0/read=WWWWWWWWW" in line, line)
        assertTrue("F:c=G/up=-/o=0/read=GGGGGGGGG" in line, line)
        assertEquals("-", ScanTrace.letter(null))
    }

    // Story 10.16: retake from review, kept faces fixed.

    private fun keptOf(
        facelets: Facelets,
        retaken: Set<Face>,
    ): Map<Face, List<Color?>> = (Face.entries - retaken).associateWith { facelets.colors.subList(it.ordinal * 9, it.ordinal * 9 + 9) }

    private fun nonCenters(face: Face) = range(face).filter { it % 9 != 4 }

    @Test
    fun `retake with one valid fit fills the retaken face in its turn and keeps kept faces`() {
        (0..3).forEach { turn ->
            val result =
                ScanAssembler.assembleRetake(
                    keptOf(scrambled, setOf(Face.F)),
                    listOf(unposed(scrambled, Face.F, turn)),
                    CALIBRATION,
                )
            assertEquals(scrambled, result.facelets)
            assertEquals(allConfident, result.confidence)
            assertEquals(scrambled.colors.subList(18, 27).map(LABS::getValue), result.labs!!.subList(18, 27))
        }
    }

    @Test
    fun `retake of two faces searches both turns with the rest fixed`() {
        val result =
            ScanAssembler.assembleRetake(
                keptOf(scrambled, setOf(Face.U, Face.B)),
                listOf(unposed(scrambled, Face.B, 3), unposed(scrambled, Face.U, 1, orientation = 90)),
                CALIBRATION,
            )
        assertEquals(scrambled, result.facelets)
        assertEquals(allConfident, result.confidence)
    }

    @Test
    fun `ambiguous retake keeps the first valid state and blanks only retaken faces that differ`() {
        val retaken = setOf(Face.U, Face.F, Face.D, Face.B)
        val result = ScanAssembler.assembleRetake(keptOf(r2, retaken), retaken.map { unposed(r2, it, 0) }, CALIBRATION)
        assertEquals(r2, result.facelets)
        retaken.forEach { face -> nonCenters(face).forEach { assertEquals(StickerConfidence.UNCERTAIN, result.confidence[it]) } }
        (range(Face.R) + range(Face.L)).forEach { assertEquals(StickerConfidence.CONFIDENT, result.confidence[it]) }
    }

    @Test
    fun `no valid retake with a kept blank reviews the best fit and blanks differing retaken faces`() {
        val retaken = setOf(Face.U, Face.F, Face.D, Face.B)
        val blank = range(Face.R).first()
        val kept = keptOf(r2, retaken).mapValues { (face, colors) -> if (face == Face.R) listOf(null) + colors.drop(1) else colors }
        val result = ScanAssembler.assembleRetake(kept, retaken.map { unposed(r2, it, 0) }, CALIBRATION)
        assertEquals(StickerConfidence.UNCERTAIN, result.confidence[blank])
        (range(Face.R) - blank + range(Face.L)).forEach { assertEquals(StickerConfidence.CONFIDENT, result.confidence[it]) }
        assertEquals(r2.colors.subList(blank + 1, blank + 9), result.facelets.colors.subList(blank + 1, blank + 9))
        retaken.forEach { face -> nonCenters(face).forEach { assertEquals(StickerConfidence.UNCERTAIN, result.confidence[it]) } }
    }

    @Test
    fun `a kept blank leaves its piece illegal but a single best fit still fills the retaken face`() {
        val blank = range(Face.U).first()
        val kept =
            keptOf(scrambled, setOf(Face.F)).mapValues { (face, colors) ->
                if (face ==
                    Face.U
                ) {
                    listOf(null) + colors.drop(1)
                } else {
                    colors
                }
            }
        val result = ScanAssembler.assembleRetake(kept, listOf(unposed(scrambled, Face.F, 1)), CALIBRATION)
        assertEquals(StickerConfidence.UNCERTAIN, result.confidence[blank])
        assertEquals(scrambled.colors.subList(18, 27), result.facelets.colors.subList(18, 27))
        nonCenters(Face.F).forEach { assertEquals(StickerConfidence.CONFIDENT, result.confidence[it]) }
    }

    @Test
    fun `a retaken sample that cannot be placed is blank and kept faces are unchanged`() {
        val good = unposed(scrambled, Face.F, 0)
        listOf(
            good.copy(quality = CaptureQuality.BLURRED),
            good.copy(stickers = good.stickers.take(8)),
            good.copy(orientationDegrees = 45),
            good.copy(centerColor = Color.RED),
        ).forEach { sample ->
            val result = ScanAssembler.assembleRetake(keptOf(scrambled, setOf(Face.F)), listOf(sample), CALIBRATION)
            nonCenters(Face.F).forEach { assertEquals(StickerConfidence.UNCERTAIN, result.confidence[it]) }
            assertEquals(Color.GREEN, result.facelets.colors[22])
            (Face.entries - Face.F).flatMap(::range).forEach {
                assertEquals(scrambled.colors[it], result.facelets.colors[it])
                assertEquals(StickerConfidence.CONFIDENT, result.confidence[it])
            }
        }
    }

    @Test
    fun `an uncalibrated retake blanks retaken faces and keeps the rest`() {
        val result =
            ScanAssembler.assembleRetake(
                keptOf(scrambled, setOf(Face.F)),
                listOf(unposed(scrambled, Face.F, 0)),
                CalibrationCenters(emptyMap()),
            )
        nonCenters(Face.F).forEach { assertEquals(StickerConfidence.UNCERTAIN, result.confidence[it]) }
        (Face.entries - Face.F).flatMap(::range).forEach { assertEquals(scrambled.colors[it], result.facelets.colors[it]) }
        assertNull(result.labs)
    }

    /** [facelets] with each face in [turns] turned that many clockwise quarter turns (centers stay). */
    private fun turned(
        facelets: Facelets,
        turns: Map<Face, Int>,
    ): Facelets =
        Facelets(
            Face.entries.flatMap { face ->
                clockwise(facelets.colors.subList(face.ordinal * 9, face.ordinal * 9 + 9), turns[face] ?: 0)
            },
        )

    @Test
    fun `orient keeps valid input as entered with nothing turned`() {
        listOf(solved, scrambled).forEach { assertEquals(FaceTurnFit.Resolved(it, emptyMap()), ScanAssembler.orient(it)) }
    }

    @Test
    fun `orient turns one face entered at 90, 180 or 270 degrees back to the only valid cube`() {
        (1..3).forEach { turn ->
            val entered = turned(scrambled, mapOf(Face.U to turn))
            assertTrue(CubeValidator.validate(entered) !is ValidationResult.Valid)
            // Entered $turn clockwise, so the fit turns it back by the rest of a full turn.
            assertEquals(FaceTurnFit.Resolved(scrambled, mapOf(Face.U to 4 - turn)), ScanAssembler.orient(entered), "U turned $turn")
        }
    }

    @Test
    fun `orient turns two faces back and names both in URFDLB order`() {
        val entered = turned(scrambled, mapOf(Face.B to 1, Face.R to 2))
        val fit = ScanAssembler.orient(entered)
        assertEquals(FaceTurnFit.Resolved(scrambled, mapOf(Face.R to 2, Face.B to 3)), fit)
        fit as FaceTurnFit.Resolved
        assertEquals(listOf(Face.R, Face.B), fit.turned)
    }

    @Test
    fun `orient takes a half-turn-symmetric face's turn count from the assignment`() {
        // After F' B the Up face reads the same after a half turn, so entered 270 degrees it is fixed by one quarter turn.
        val cube = CubeState.SOLVED.apply(listOf(FaceTurn(Face.F, Turn.COUNTERCLOCKWISE), FaceTurn(Face.B, Turn.CLOCKWISE))).toFacelets()
        val up = cube.colors.subList(0, 9)
        assertEquals(up, clockwise(up, 2))
        assertTrue(up != clockwise(up, 1))
        val fit = ScanAssembler.orient(turned(cube, mapOf(Face.U to 3)))
        assertEquals(FaceTurnFit.Resolved(cube, mapOf(Face.U to 1)), fit)
    }

    @Test
    fun `orient keeps a valid entry even when another turning is also valid`() {
        // R2: turning U, F, D and B by a half turn gives L2, also valid; the entry as entered wins.
        val other = turned(r2, listOf(Face.U, Face.F, Face.D, Face.B).associateWith { 2 })
        assertTrue(CubeValidator.validate(other) is ValidationResult.Valid)
        assertEquals(FaceTurnFit.Resolved(r2, emptyMap()), ScanAssembler.orient(r2))
    }

    @Test
    fun `orient with several valid turnings is unresolved and names the faces that differ`() {
        // R2 with U entered a quarter turn off: U back gives R2; U on with F, D and B half-turned gives L2.
        val fit = ScanAssembler.orient(turned(r2, mapOf(Face.U to 1)))
        fit as FaceTurnFit.Unresolved
        assertTrue(fit.valid >= 2, "valid ${fit.valid}")
        assertEquals(listOf(Face.U, Face.F, Face.D, Face.B), fit.involved)
    }

    @Test
    fun `orient with no valid turning is unresolved with the faces to check`() {
        val colors = scrambled.colors.toMutableList()
        val b = (9 until 54).first { it % 9 != 4 && colors[it] != colors[0] }
        colors[0] = scrambled.colors[b]
        colors[b] = scrambled.colors[0]
        val fit = ScanAssembler.orient(Facelets(colors))
        fit as FaceTurnFit.Unresolved
        assertEquals(0, fit.valid)
        assertTrue(fit.involved.isNotEmpty())
        assertEquals(Face.entries.filter { it in fit.involved }, fit.involved)
    }

    @Test
    fun `orient with a twisted corner and every piece legal names no face`() {
        // URF corner twisted in place: right counts, every piece legal, so no face turn can fix it.
        val colors = solved.colors.toMutableList()
        val (u, r, f) = listOf(colors[8], colors[9], colors[20])
        colors[9] = u
        colors[20] = r
        colors[8] = f
        assertEquals(FaceTurnFit.Unresolved(0, emptyList()), ScanAssembler.orient(Facelets(colors)))
    }

    @Test
    fun `orient with wrong color counts never resolves`() {
        val colors = solved.colors.toMutableList().also { it[0] = Color.RED }
        val fit = ScanAssembler.orient(Facelets(colors))
        fit as FaceTurnFit.Unresolved
        assertEquals(0, fit.valid)
        assertTrue(Face.U in fit.involved)
    }

    @Test
    fun `net neighbors are top, right, bottom and left of each face`() {
        Face.entries.forEach { assertEquals(neighbors.getValue(it), ScanAssembler.netNeighbors(it)) }
    }

    @Test
    fun `a retaken face with a named top is placed at its turn`() {
        val result =
            ScanAssembler.assembleRetake(
                keptOf(scrambled, setOf(Face.R)),
                listOf(capture(scrambled, Face.R, 2, 180).copy(face = Face.R)),
                CALIBRATION,
            )
        assertEquals(scrambled, result.facelets)
    }
}
