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
        assertEquals(mapOf(Face.F to ScanGap.NO_POSE), gapFor { it.copy(upNeighborColor = null) })
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
}
