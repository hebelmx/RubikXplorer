package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.cube.ValidationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests about capture, hand-over and classification build controllers with `lightCheck = false` when they have no saved
 * calibration: their synthetic scrambled cubes can trip the uncalibrated light check (its colour-dependent signal holds
 * some front-lit cubes by design, PO 2026-11-21 E). The light-hold tests further down run the check.
 */
class QuickScanControllerTest {
    private val labs =
        mapOf(
            Color.WHITE to Lab(90.0, 0.0, 0.0),
            Color.GREEN to Lab(55.0, -60.0, 40.0),
            Color.RED to Lab(45.0, 65.0, 45.0),
            Color.YELLOW to Lab(85.0, -5.0, 80.0),
            Color.ORANGE to Lab(65.0, 45.0, 65.0),
            Color.BLUE to Lab(40.0, 10.0, -50.0),
        )

    /** A saved calibration: with it every captured face is judged by the six centers, as before Story 10.17's fix. */
    private val saved = CalibrationCenters(Color.entries.associateWith(labs::getValue))

    private val cube = CubeState.SOLVED.apply(listOf(FaceTurn(Face.R, Turn.CLOCKWISE), FaceTurn(Face.U, Turn.COUNTERCLOCKWISE)))

    private val evidenceCube = CubeState.SOLVED.apply((Notation.parse("U R2 F' D L' B2") as NotationResult.Parsed).moves)

    private fun face(
        face: Face,
        cube: CubeState = this.cube,
    ): FrameStats {
        val colors =
            cube
                .toFacelets()
                .colors
                .drop(face.ordinal * 9)
                .take(9)
        return FrameStats(colors.map { CellReading(labs.getValue(it), true) }, 120.0, 0.0, 100.0, 12)
    }

    private fun hold(
        index: Int,
        cube: CubeState = this.cube,
    ): List<FrameStats> =
        QuickScanPlan.holds[index - 1].map { step ->
            val stats = face(step.face, cube)
            when (step.face) {
                Face.D -> stats.copy(cells = listOf(2, 5, 8, 1, 4, 7, 0, 3, 6).map(stats.cells::get))
                Face.L, Face.B -> stats.copy(cells = stats.cells.reversed())
                else -> stats
            }
        }

    @Test
    fun `auto capture settles and holds all 27 cells steady before taking each corner`() {
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 0))
        assertTrue(scan.onFrame(true, first, 599).isEmpty())
        assertTrue(scan.onFrame(true, first, 600).isEmpty())
        // Two cells of one face moving restart the steady window (PO 2026-11-21: one flickering cell alone does not).
        val moved = first.toMutableList().also { it[0] = it[0].withCell(0, Lab(79.0, 0.0, 0.0)).withCell(1, Lab(79.0, 0.0, 0.0)) }
        assertTrue(scan.onFrame(true, moved, 800).isEmpty())
        assertTrue(scan.onFrame(true, first, 899).isEmpty())
        assertTrue(scan.onFrame(true, first, 1898).isEmpty())
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, first, 1899))
        assertTrue(scan.onFrame(false, List(3) { null }, 2000).isEmpty())
        assertTrue(scan.onFrame(true, first, 3000).isEmpty())
        assertTrue(scan.onShutter().isEmpty())
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, hold(2), 4000))
        assertTrue(scan.onFrame(true, hold(2), 4600).isEmpty())
        val effects = scan.onFrame(true, hold(2), 5600)
        assertEquals(QuickScanEffect.CornerCaptured(2), effects.first())
        val done = effects.last() as QuickScanEffect.Done
        assertEquals(6, done.samples.size)
        assertTrue(done.samples.all { it.orientationDegrees == 0 && it.upNeighborColor != null })
        assertEquals(cube.toFacelets(), assemble(done.samples).facelets)
        assertFalse(scan.inCorner)
        assertTrue(scan.onShutter().isEmpty())
    }

    @Test
    fun `shutter captures immediately and an uncertain face alone is handed over, any rotation, with no second round`() {
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1).toMutableList().also { it[1] = it[1].withCell(0, Lab(55.0, 55.0, 55.0)) }
        assertEquals(QuickScanEffect.Fallback(listOf(Face.F)), capture(scan, first, hold(2)))
        val grid = checkNotNull(scan.fallback)
        assertEquals(ScanStep(Color.GREEN), grid.step)
        assertEquals(1, grid.planSize)
        assertEquals(Face.entries.toSet() - Face.F, scan.keptFaces)
        assertTrue(scan.added.isEmpty())
        val done = guidedCapture(scan, face(Face.F)) as QuickScanEffect.Done
        assertEquals(null, scan.fallback)
        assertEquals(Face.entries.toSet(), done.samples.map { it.face }.toSet())
        assertEquals(cube.toFacelets(), assemble(done.samples).facelets)
    }

    @Test
    fun `an undetected second corner coaches and the shutter sends its faces to the guided grid`() {
        val scan = QuickScanController(saved)
        scan.onFrame(true, hold(1), 0)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onShutter())
        val second = hold(2)
        assertTrue(scan.onFrame(false, List(3) { null }, 100).isEmpty())
        assertTrue(scan.onFrame(false, listOf(second[0].copy(meanLuma = 10.0), second[1], second[2]), 200).isEmpty())
        assertEquals(Coaching.LOW_LIGHT, scan.coaching)
        assertEquals(
            listOf(QuickScanEffect.CornerCaptured(2), QuickScanEffect.Fallback(listOf(Face.B, Face.L, Face.D))),
            scan.onShutter(),
        )
        assertEquals(QuickScanPlan.holds[0].map { it.face }, scan.captured.map { it.face })
    }

    @Test
    fun `an uncertain logo center does not send confident stickers to the guided grid`() {
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1).toMutableList()
        val cells = first[1].cells.toMutableList()
        cells[FaceSample.CENTER_INDEX] = CellReading(Lab(55.0, 55.0, 55.0), false)
        first[1] = first[1].copy(cells = cells)
        val done = capture(scan, first, hold(2)) as QuickScanEffect.Done
        assertEquals(cube.toFacelets(), assemble(done.samples).facelets)
    }

    @Test
    fun `missing poor and uncalibrated faces enter the guided grid`() {
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1)
        val input = listOf(null, first[1].copy(meanLuma = 10.0), first[2])
        // No white or green center was captured, so B and L (white and green stickers) cannot be read either.
        assertEquals(QuickScanEffect.Fallback(listOf(Face.F, Face.B, Face.L, Face.U)), capture(scan, input, hold(2)))
        assertEquals(Face.F, scan.fallback?.step?.face)
        assertEquals(null, guidedCapture(scan, face(Face.F)))
        assertEquals(null, guidedCapture(scan, face(Face.B)))
        assertEquals(null, guidedCapture(scan, face(Face.L)))
        val done = guidedCapture(scan, face(Face.U)) as QuickScanEffect.Done
        assertEquals(cube.toFacelets(), assemble(done.samples).facelets)
    }

    @Test
    fun `a missing corner face can be captured in the grid with any top at camera rotation 90`() {
        for (missing in Face.entries) {
            for (heldTurns in 0..3) {
                val scan = QuickScanController(saved)
                val holds =
                    QuickScanPlan.holds.mapIndexed { index, steps ->
                        steps.zip(hold(index + 1, evidenceCube)).map { (step, stats) ->
                            stats.takeUnless { step.face == missing }
                        }
                    }
                val scenario = "face $missing, held turns $heldTurns"
                assertEquals(QuickScanEffect.Fallback(listOf(missing)), capture(scan, holds[0], holds[1]), scenario)
                val done = guidedCapture(scan, evidenceGridFace(missing, heldTurns), rotation = 90)
                assertTrue(done is QuickScanEffect.Done, "$scenario: $done")
                val samples = (done as QuickScanEffect.Done).samples
                val merged = assemble(samples).facelets
                assertEquals(evidenceCube.toFacelets(), merged, scenario)
                if (missing == Face.U) assertEquals("OWORWYORB", merged.colors.take(9).joinToString("") { it.name.take(1) }, scenario)
                val reasked = samples.single { it.face == missing }
                // The hand-over names no top: the assembler searches the face's four quarter turns.
                assertEquals(null, reasked.upNeighborColor)
                assertTrue(samples.filter { it.face != missing }.all { it.upNeighborColor != null }, scenario)
            }
        }
    }

    @Test
    fun `an invalid merge hands over the involved faces once, and one can be held upside down`() {
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1, evidenceCube).toMutableList()
        // A U edge read from the corner hold implicates two faces, within the re-prompt limit.
        first[0] = first[0].withCell(1, labs.getValue(Color.BLUE))
        val effect = capture(scan, first, hold(2, evidenceCube))
        assertTrue(effect is QuickScanEffect.Fallback, "$effect")
        val fallback = effect as QuickScanEffect.Fallback
        assertEquals(scan.repromptFaces.toSet(), fallback.faces.toSet())
        assertTrue(Face.U in fallback.faces && fallback.faces.size <= 2, "${fallback.faces}")
        assertTrue(checkNotNull(scan.fallback).step?.upNeighborColor == null)
        val done =
            fallback.faces
                .map { face ->
                    guidedCapture(scan, evidenceGridFace(face, if (face == Face.U) 2 else 0), rotation = 90)
                }.last() as QuickScanEffect.Done
        val merged = assemble(done.samples).facelets
        assertEquals(evidenceCube.toFacelets(), merged)
        assertEquals("OWORWYORB", merged.colors.take(9).joinToString("") { it.name.take(1) })
    }

    private fun evidenceGridFace(
        face: Face,
        heldTurns: Int,
    ): FrameStats {
        val stats = face(face, evidenceCube)
        // At camera rotation 90 the pixels are one turn counterclockwise from the held face.
        return stats.copy(cells = ScanAssembler.rotateClockwise(stats.cells, heldTurns - 1))
    }

    @Test
    fun `an invalid merged state after the hand-over goes straight to review without a second round`() {
        val scan = QuickScanController(saved)
        val first = hold(1)
        val invalidF = first[1].withCell(1, labs.getValue(Color.WHITE))
        assertEquals(QuickScanEffect.Fallback(listOf(Face.U)), capture(scan, listOf(null, invalidF, first[2]), hold(2)))
        val done = guidedCapture(scan, face(Face.U)) as QuickScanEffect.Done
        assertFalse(CubeValidator.validate(assemble(done.samples).facelets) is ValidationResult.Valid)
        assertEquals(null, scan.fallback)
        assertEquals(null, scan.switchToFaceByFace())
    }

    @Test
    fun `quality coaching uses shutter quality and two unlocated shutters ask for all faces`() {
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1)
        listOf(
            first[0].copy(sharpness = 0.0) to Coaching.BLURRED,
            first[0].copy(glareFraction = 0.5) to Coaching.GLARE,
            first[0].copy(meanLuma = 10.0) to Coaching.LOW_LIGHT,
        ).forEach { (bad, coaching) ->
            scan.onFrame(true, listOf(bad, first[1], first[2]), 0)
            assertEquals(coaching, scan.coaching)
        }
        scan.onFrame(false, List(3) { null }, 1)
        assertEquals(null, scan.coaching)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onShutter())
        assertEquals(QuickScanEffect.Fallback(GuidedScanPlan.steps.map { it.face }), scan.onShutter().last())
    }

    @Test
    fun `the shutter keeps a partial corner even when automatic detection does not arm`() {
        val scan = QuickScanController(saved)
        assertTrue(scan.onFrame(false, listOf(hold(1)[0], null, null), 0).isEmpty())
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onShutter())
        assertEquals(listOf(Face.U), scan.captured.map { it.face })
        scan.onFrame(true, hold(2), 100)
        assertEquals(QuickScanEffect.Fallback(listOf(Face.F, Face.R)), scan.onShutter().last())
    }

    @Test
    fun `the second corner arms only on a detected view whose top center changed`() {
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1)
        scan.onFrame(true, first, 0)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onShutter())
        // An undetected frame cannot arm automatic capture or clear the turnover check.
        assertTrue(scan.onFrame(false, hold(2), 100).isEmpty())
        // The same corner shifted so a side sticker reads differently is still the first corner.
        val shifted = first.toMutableList().also { it[1] = it[1].withCell(0, labs.getValue(Color.BLUE)) }
        assertTrue(scan.onFrame(true, shifted, 200).isEmpty())
        assertTrue(scan.onShutter().isEmpty())
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, hold(2), 300))
    }

    @Test
    fun `a second corner with no cell comparable to a partial first corner still arms`() {
        val scan = QuickScanController(lightCheck = false)
        scan.onFrame(true, listOf(hold(1)[0], null, null), 0)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onShutter())
        val second = hold(2)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, listOf(null, second[1], second[2]), 100))
    }

    @Test
    fun `a kept corner face is never asked again once calibration becomes possible`() {
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1)
        // Between white and yellow: confident (both gated reads agree) while no white center is captured, uncertain once it is.
        val between = Lab(80.0, -6.0, 35.0)
        val laterUncertainF = first[1].withCell(0, between)
        // No white center yet: U is missing and B and L (white stickers) cannot be read; F is read against the others.
        assertEquals(
            QuickScanEffect.Fallback(listOf(Face.B, Face.L, Face.U)),
            capture(scan, listOf(null, laterUncertainF, first[2]), hold(2)),
        )
        val keptF = scan.kept.single { it.face == Face.F }
        assertEquals(null, guidedCapture(scan, face(Face.B)))
        assertEquals(null, guidedCapture(scan, face(Face.L)))
        val done = guidedCapture(scan, face(Face.U)) as QuickScanEffect.Done
        assertEquals(keptF, done.samples.single { it.face == Face.F })
        val calibration = (SelfCalibration.from(done.samples) as SelfCalibration.Calibrated).calibration
        assertEquals(StickerConfidence.UNCERTAIN, QuickScanReadout.read(keptF, calibration)[0]?.confidence)
    }

    @Test
    fun `the guided grid rejects a face already read from a corner`() {
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1).toMutableList().also { it[1] = it[1].withCell(0, Lab(55.0, 55.0, 55.0)) }
        assertEquals(QuickScanEffect.Fallback(listOf(Face.F)), capture(scan, first, hold(2)))
        val grid = checkNotNull(scan.fallback)
        grid.onFrame(face(Face.U), 0, 0)
        assertTrue(grid.onShutter(0).none { it is GuidedScanEffect.Capture })
        assertEquals(Coaching.WRONG_FACE, grid.coaching)
    }

    @Test
    fun `face by face before the first capture asks all six faces in guided order with no named top`() {
        val scan = QuickScanController(lightCheck = false)
        scan.onFrame(true, hold(1), 0)
        assertEquals(QuickScanEffect.Fallback(GuidedScanPlan.steps.map { it.face }), scan.switchToFaceByFace())
        assertFalse(scan.inCorner)
        assertTrue(scan.keptFaces.isEmpty())
        assertTrue(scan.kept.isEmpty())
        val grid = checkNotNull(scan.fallback)
        assertEquals(6, grid.planSize)
        assertEquals(ScanStep(Color.GREEN), grid.step)
        // The switch is a no-op once the hand-over runs, and corner frames are ignored.
        assertEquals(null, scan.switchToFaceByFace())
        assertTrue(scan.onShutter().isEmpty())
        val done =
            GuidedScanPlan.steps
                .map { guidedCapture(scan, face(it.face, evidenceCube)) }
                .last() as QuickScanEffect.Done
        assertEquals(evidenceCube.toFacelets(), assemble(done.samples).facelets)
        assertTrue(done.samples.all { it.upNeighborColor == null })
    }

    @Test
    fun `face by face between captures keeps the first corner and asks the other three`() {
        val scan = QuickScanController(saved)
        scan.onFrame(true, hold(1), 0)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onShutter())
        val corner = scan.captured
        assertEquals(QuickScanEffect.Fallback(listOf(Face.B, Face.L, Face.D)), scan.switchToFaceByFace())
        assertEquals(setOf(Face.U, Face.F, Face.R), scan.keptFaces)
        assertEquals(corner, scan.kept)
        val added = listOf(Face.B, Face.L, Face.D).map { it to face(it) }
        val effects = added.map { (_, stats) -> guidedCapture(scan, stats) }
        assertEquals(listOf(null, null), effects.dropLast(1))
        val done = effects.last() as QuickScanEffect.Done
        // Kept samples are exactly as captured (named top, orientation 0, same stickers); the new ones are searched.
        assertEquals(corner, done.samples.take(3))
        assertEquals(scan.added, done.samples.drop(3))
        assertTrue(done.samples.drop(3).all { it.upNeighborColor == null })
        assertEquals(cube.toFacelets(), assemble(done.samples).facelets)
    }

    @Test
    fun `face by face drops a doubtful corner face and asks it with the missing ones`() {
        val scan = QuickScanController(saved)
        val first = hold(1).toMutableList().also { it[1] = it[1].copy(meanLuma = 10.0) }
        scan.onFrame(true, first, 0)
        scan.onShutter()
        assertEquals(QuickScanEffect.Fallback(listOf(Face.F, Face.B, Face.L, Face.D)), scan.switchToFaceByFace())
        assertEquals(setOf(Face.U, Face.R), scan.keptFaces)
        assertEquals(listOf(Face.U, Face.R), scan.captured.map { it.face })
    }

    @Test
    fun `back from the hand-over resumes the hold it started from with kept faces intact`() {
        // Hold 1, nothing captured.
        val atOne = QuickScanController(lightCheck = false)
        assertFalse(atOne.backOut())
        atOne.switchToFaceByFace()
        guidedCapture(atOne, face(Face.F))
        assertTrue(atOne.backOut())
        assertEquals(1, atOne.hold)
        assertTrue(atOne.inCorner)
        assertTrue(atOne.captured.isEmpty())
        assertTrue(atOne.added.isEmpty())
        assertEquals(null, atOne.fallback)
        assertFalse(atOne.backOut())

        // Hold 2, the first corner kept.
        val atTwo = QuickScanController(saved)
        atTwo.onFrame(true, hold(1), 0)
        atTwo.onShutter()
        val corner = atTwo.captured
        atTwo.switchToFaceByFace()
        guidedCapture(atTwo, face(Face.B))
        assertTrue(atTwo.backOut())
        assertEquals(2, atTwo.hold)
        assertEquals(corner, atTwo.kept)
        assertTrue(atTwo.added.isEmpty())
        // The second corner then fills the rest and the scan finishes with the kept corner untouched.
        atTwo.onFrame(true, hold(2), 100)
        val done = atTwo.onShutter().last() as QuickScanEffect.Done
        assertEquals(corner, done.samples.filter { it.face in corner.map(FaceSample::face) })
        assertEquals(cube.toFacelets(), assemble(done.samples).facelets)
    }

    @Test
    fun `back after both captures resumes hold 2 and a re-capture fills only faces not kept`() {
        val scan = QuickScanController(lightCheck = false)
        val uncertain = hold(2).toMutableList().also { it[2] = it[2].withCell(0, Lab(55.0, 55.0, 55.0)) }
        assertEquals(QuickScanEffect.Fallback(listOf(Face.B)), capture(scan, hold(1), uncertain))
        val kept = scan.kept
        assertEquals(Face.entries.toSet() - Face.B, scan.keptFaces)
        // The last hand-over face is captured, then Back before review drops it.
        assertTrue(guidedCapture(scan, face(Face.B)) is QuickScanEffect.Done)
        assertTrue(scan.backOut())
        assertEquals(2, scan.hold)
        assertEquals(kept, scan.kept)
        assertTrue(scan.added.isEmpty())
        // A re-capture with D read differently keeps D as it was; B is read again and is now confident.
        val recapture = hold(2).toMutableList().also { it[0] = it[0].withCell(0, labs.getValue(Color.RED)) }
        scan.onFrame(true, recapture, 200)
        val done = scan.onShutter().last() as QuickScanEffect.Done
        assertEquals(kept, done.samples.filter { it.face != Face.B })
        assertEquals(cube.toFacelets(), assemble(done.samples).facelets)
        assertFalse(scan.backOut())
    }

    @Test
    fun `with no calibration a corner face with ambiguous stickers is not kept and is asked in the hand-over`() {
        val solved = CubeState.SOLVED
        // Pale red, halfway to white: neither center wins by the close-call margin.
        val pale = Lab(67.5, 32.5, 22.5)
        val switched = QuickScanController(lightCheck = false)
        val first = hold(1, solved).toMutableList()
        first[2] = listOf(0, 2, 8).fold(first[2]) { stats, index -> stats.withCell(index, pale) }
        switched.onFrame(true, first, 0)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), switched.onShutter())
        assertEquals(QuickScanEffect.Fallback(listOf(Face.R, Face.B, Face.L, Face.D)), switched.switchToFaceByFace())
        assertEquals(setOf(Face.U, Face.F), switched.keptFaces)

        // The automatic hand-over with no green center: a top read between yellow and orange is asked with the missing F.
        val automatic = QuickScanController(lightCheck = false)
        val second = hold(2, solved).toMutableList()
        second[0] = listOf(1, 3).fold(second[0]) { stats, index -> stats.withCell(index, Lab(75.0, 20.0, 72.5)) }
        val start = hold(1, solved)
        assertEquals(QuickScanEffect.Fallback(listOf(Face.F, Face.D)), capture(automatic, listOf(start[0], null, start[2]), second))
        assertEquals(setOf(Face.U, Face.R, Face.L, Face.B), automatic.keptFaces)
        assertEquals(null, guidedCapture(automatic, face(Face.F, solved)))
        val done = guidedCapture(automatic, face(Face.D, solved)) as QuickScanEffect.Done
        assertEquals(solved.toFacelets(), assemble(done.samples).facelets)
    }

    @Test
    fun `with no calibration corner faces whose stickers are all confident are kept as before`() {
        val solved = CubeState.SOLVED
        val switched = QuickScanController(lightCheck = false)
        switched.onFrame(true, hold(1, solved), 0)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), switched.onShutter())
        assertEquals(QuickScanEffect.Fallback(listOf(Face.B, Face.L, Face.D)), switched.switchToFaceByFace())
        assertEquals(setOf(Face.U, Face.F, Face.R), switched.keptFaces)

        val automatic = QuickScanController(lightCheck = false)
        val start = hold(1, solved)
        assertEquals(QuickScanEffect.Fallback(listOf(Face.F)), capture(automatic, listOf(start[0], null, start[2]), hold(2, solved)))
        assertEquals(Face.entries.toSet() - Face.F, automatic.keptFaces)
    }

    private fun capture(
        scan: QuickScanController,
        first: List<FrameStats?>,
        second: List<FrameStats?>,
    ): QuickScanEffect {
        scan.onFrame(true, first, 0)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onShutter())
        scan.onFrame(true, second, 100)
        return scan.onShutter().last()
    }

    @Test
    fun `undetected corner for three seconds coaches a plain light surface and clears on detection`() {
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1)
        scan.onFrame(false, List(3) { null }, 100)
        assertEquals(null, scan.coaching)
        scan.onFrame(false, List(3) { null }, 3099)
        assertEquals(null, scan.coaching)
        scan.onFrame(false, List(3) { null }, 3100)
        assertEquals(Coaching.PLAIN_LIGHT_SURFACE, scan.coaching)
        scan.onFrame(true, first, 3200)
        assertEquals(null, scan.coaching)
    }

    @Test
    fun `the hand-over delays auto capture so the learner can turn the cube`() {
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1).toMutableList().also { it[1] = it[1].withCell(0, Lab(55.0, 55.0, 55.0)) }
        assertEquals(QuickScanEffect.Fallback(listOf(Face.F)), capture(scan, first, hold(2)))
        val grid = checkNotNull(scan.fallback)
        val front = face(Face.F)
        assertEquals(listOf(GuidedScanEffect.Remeter), grid.onFrame(front, 0, 1000))
        // Steady for > 600 ms but still within the 2.5 s initial turn delay: must not auto-capture yet.
        assertTrue(grid.onFrame(front, 0, 1500).isEmpty())
        assertTrue(grid.onFrame(front, 0, 3499).isEmpty())
        // Once past 2.5 s from the first frame, steady auto-capture proceeds.
        val effects = grid.onFrame(front, 0, 3500)
        assertTrue(effects.any { it is GuidedScanEffect.Capture }, "$effects")
    }

    @Test
    fun `turnover without top center requires at least two other centers to change`() {
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1)
        scan.onFrame(true, first, 0)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onShutter())
        val noisySingleFace = listOf(null, first[1].withCell(FaceSample.CENTER_INDEX, labs.getValue(Color.ORANGE)), first[2])
        assertTrue(scan.onFrame(true, noisySingleFace, 100).isEmpty())
        val twoChanged =
            listOf(
                null,
                first[1].withCell(FaceSample.CENTER_INDEX, labs.getValue(Color.ORANGE)),
                first[2].withCell(FaceSample.CENTER_INDEX, labs.getValue(Color.BLUE)),
            )
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, twoChanged, 200))
    }

    /** [faces] with both side faces at [brightness] times their L* (the light behind the cube): fails the calibrated check against [saved]. */
    private fun darkSides(
        faces: List<FrameStats>,
        brightness: Double = 0.6,
    ): List<FrameStats> =
        listOf(faces[0]) +
            faces.drop(1).map { stats ->
                stats.copy(cells = stats.cells.map { it?.copy(value = it.value.copy(l = it.value.l * brightness)) })
            }

    /** A controller with the saved calibration, so the light check runs in its calibrated mode (exact labs read 1). */
    private fun lightScan() = QuickScanController(saved)

    @Test
    fun `the controller runs the light check itself, calibrated with a saved calibration and uncalibrated without`() {
        val calibrated = lightScan()
        calibrated.onFrame(true, hold(1), 0, settled = false)
        assertEquals(SideLight.Mode.CALIBRATED, calibrated.light?.mode)
        assertTrue(calibrated.light!!.passed)
        assertEquals(1.0, calibrated.light!!.score!!, 1e-9)
        calibrated.onFrame(true, darkSides(hold(1)), 100, settled = true)
        assertFalse(calibrated.light!!.passed)
        assertTrue(calibrated.lightHeld)
        calibrated.onFrame(false, List(3) { null }, 200)
        assertEquals(null, calibrated.light, "an undetected frame is not measured")

        val uncalibrated = QuickScanController()
        val bright = FrameStats(List(9) { CellReading(Lab(90.0, 0.0, 0.0), true) }, 120.0, 0.0, 100.0, 12)
        val dim = FrameStats(List(9) { CellReading(Lab(50.0, 0.0, 0.0), true) }, 120.0, 0.0, 100.0, 12)
        uncalibrated.onFrame(true, listOf(bright, dim, bright), 0, settled = false)
        uncalibrated.onFrame(true, listOf(bright, dim, bright), 100, settled = true)
        assertEquals(SideLight.Mode.UNCALIBRATED, uncalibrated.light?.mode)
        assertEquals(40.0, uncalibrated.light!!.darkerSideDifference!!, 1e-9)
        assertTrue(uncalibrated.lightHeld)
        assertFalse(
            QuickScanController(lightCheck = false)
                .also {
                    it.onFrame(true, listOf(bright, dim, bright), 0, settled = false)
                    it.onFrame(true, listOf(bright, dim, bright), 100, settled = true)
                }.lightHeld,
        )
    }

    @Test
    fun `a backlit corner holds every capture and coaches sides-dark after one second of failing`() {
        val scan = lightScan()
        val dark = darkSides(hold(1))
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, dark, 0, settled = false))
        assertFalse(scan.lightHeld, "the first frame re-meters onto the cube and holds nothing")
        assertEquals(null, scan.coaching)
        for (now in 100L..5000L step 100) {
            assertTrue(scan.onFrame(true, dark, now, settled = true).isEmpty(), "no auto capture at $now")
            assertTrue(scan.lightHeld, "held at $now")
            assertEquals(100L, scan.lightHeldSince, "the hold starts on the first settled failing frame")
            assertEquals(if (now >= 1100) Coaching.SIDES_DARK else null, scan.coaching, "coaching at $now")
            assertTrue(scan.onShutter().isEmpty(), "the shutter is ignored while held at $now")
        }
        assertEquals(1, scan.hold)
    }

    @Test
    fun `correcting the light clears the line with the hold, re-meters and restarts the steady count`() {
        val scan = lightScan()
        val first = hold(1)
        val dark = darkSides(first)
        scan.onFrame(true, dark, 0, settled = false)
        scan.onFrame(true, dark, 100, settled = true)
        scan.onFrame(true, dark, 1100, settled = true)
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        assertTrue(scan.onFrame(true, first, 1200, settled = true).isEmpty())
        assertTrue(scan.onFrame(true, first, 1699, settled = true).isEmpty())
        assertTrue(scan.lightHeld)
        assertEquals(Coaching.SIDES_DARK, scan.coaching, "the line stays up through the pass window")
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 1700, settled = true))
        assertFalse(scan.lightHeld)
        assertEquals(null, scan.coaching)
        assertTrue(scan.onFrame(true, first, 1800, settled = false).isEmpty())
        assertTrue(scan.onFrame(true, first, 1900, settled = true).isEmpty())
        assertTrue(scan.onFrame(true, first, 2899, settled = true).isEmpty())
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, first, 2900, settled = true))
    }

    @Test
    fun `one flickering right-face cell neither blocks the light clear nor auto capture after a light hold`() {
        val scan = lightScan()
        val first = hold(1)
        val dark = darkSides(first)
        scan.onFrame(true, dark, 0, settled = false)
        scan.onFrame(true, dark, 100, settled = true)
        assertTrue(scan.lightHeld)
        // The Lenovo session: one right-face cell alternating L* ~86/96 while everything else holds still.
        val low = first.toMutableList().also { it[2] = it[2].withCell(4, Lab(86.0, 0.0, 0.0)) }
        val high = first.toMutableList().also { it[2] = it[2].withCell(4, Lab(96.0, 0.0, 0.0)) }
        var clearedAt: Long? = null
        var capturedAt: Long? = null
        var now = 200L
        while (capturedAt == null && now < 10_000L) {
            val frame = if ((now / 33) % 2 == 0L) low else high
            val effects = scan.onFrame(true, frame, now, settled = if (clearedAt == null) true else null)
            if (QuickScanEffect.Remeter in effects && clearedAt == null) clearedAt = now
            if (QuickScanEffect.CornerCaptured(1) in effects) capturedAt = now
            assertEquals(null, scan.steadyRestart, "one moving cell never restarts the steady window at $now")
            now += 33
        }
        val cleared = checkNotNull(clearedAt) { "the light hold cleared despite the flickering cell" }
        assertTrue(cleared <= 200 + CornerHold.LIGHT_CLEAR_MS + 100, "light cleared at $cleared")
        val captured = checkNotNull(capturedAt) { "auto capture fired despite the flickering cell" }
        assertTrue(
            captured <= cleared + CornerHold.SETTLE_MS + CornerHold.STEADY_MS + 200,
            "captured at $captured, light cleared at $cleared",
        )
    }

    @Test
    fun `two cells moving on one face still restart the steady window and report it`() {
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 0))
        assertTrue(scan.onFrame(true, first, 600).isEmpty())
        // One moving cell on each of two faces is not a face change: the window started at 600 runs on.
        val spread =
            first.toMutableList().also {
                it[1] = it[1].withCell(0, Lab(30.0, 0.0, 0.0))
                it[2] = it[2].withCell(0, Lab(30.0, 0.0, 0.0))
            }
        assertTrue(scan.onFrame(true, spread, 700).isEmpty())
        assertEquals(null, scan.steadyRestart)
        val moved = first.toMutableList().also { it[2] = it[2].withCell(3, Lab(86.0, 0.0, 0.0)).withCell(5, Lab(96.0, 0.0, 0.0)) }
        assertTrue(scan.onFrame(true, moved, 800).isEmpty())
        val restart = checkNotNull(scan.steadyRestart)
        assertEquals(2, restart.face)
        assertEquals(listOf(3, 5), restart.cells)
        assertTrue(restart.maxDeltaE >= GuidedScanController.STEADY_DELTA_E)
        assertTrue(scan.onFrame(true, moved, 1600).isEmpty(), "the window restarted at 800, not 600")
        assertTrue(scan.onFrame(true, moved, 1799).isEmpty())
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, moved, 1800))
    }

    @Test
    fun `a failing verdict on an episode's first detected frame never starts the light hold`() {
        val scan = lightScan()
        val dark = darkSides(hold(1))
        // The first frame is still exposed on the whole scene and triggers the re-meter: even reported settled, no hold.
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, dark, 0, settled = true))
        assertFalse(scan.light!!.passed, "the failing verdict is still reported")
        assertFalse(scan.lightHeld)
        assertEquals(null, scan.lightHeldSince)
        // After losing the cube, the next episode's first frame holds nothing either.
        scan.onFrame(false, List(3) { null }, 100)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, dark, 200, settled = true))
        assertFalse(scan.lightHeld)
        // Once metering on the cube has settled, a failing frame starts the hold and its coaching timer.
        scan.onFrame(true, dark, 300, settled = true)
        assertTrue(scan.lightHeld)
        assertEquals(300L, scan.lightHeldSince)
        scan.onFrame(true, dark, 1299, settled = true)
        assertEquals(null, scan.coaching)
        scan.onFrame(true, dark, 1300, settled = true)
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
    }

    @Test
    fun `a failing verdict while a metering change settles never starts the light hold`() {
        val scan = lightScan()
        val first = hold(1)
        val dark = darkSides(first)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 0, settled = false))
        // An exposure transient right after metering: the verdict fails and is reported, but no hold starts.
        assertTrue(scan.onFrame(true, dark, 100, settled = false).isEmpty())
        assertFalse(scan.light!!.passed)
        assertFalse(scan.lightHeld)
        assertTrue(scan.onFrame(true, first, 200, settled = true).isEmpty())
        assertFalse(scan.lightHeld)
        // A torch toggle is a metering change: while it is pending or settling a failing verdict holds nothing.
        scan.syncTorch(requested = true, applied = false, nowMs = 300)
        scan.onFrame(true, dark, 350, settled = false)
        assertFalse(scan.lightHeld)
        scan.syncTorch(requested = true, applied = true, nowMs = 400)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 450, settled = false))
        scan.onFrame(true, dark, 500, settled = false)
        assertFalse(scan.lightHeld)
        // Once settled, a failing frame starts the hold.
        scan.onFrame(true, dark, 600, settled = true)
        assertTrue(scan.lightHeld)
        assertEquals(600L, scan.lightHeldSince)
    }

    @Test
    fun `a moving pass or a pass shorter than half a second keeps the hold and the line`() {
        val scan = lightScan()
        val first = hold(1)
        val dark = darkSides(first)
        scan.onFrame(true, dark, 0, settled = false)
        scan.onFrame(true, dark, 100, settled = true)
        scan.onFrame(true, dark, 1100, settled = true)
        scan.onFrame(true, first, 1200, settled = true)
        scan.onFrame(true, first, 1500, settled = true)
        assertTrue(scan.onFrame(true, dark, 1600, settled = true).isEmpty())
        assertTrue(scan.lightHeld)
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        // Passing but moving (two cells of one face): each change restarts the pass-and-steady window.
        val moved = first.toMutableList().also { it[0] = it[0].withCell(0, Lab(70.0, 0.0, 0.0)).withCell(1, Lab(70.0, 0.0, 0.0)) }
        scan.onFrame(true, first, 1700, settled = true)
        scan.onFrame(true, moved, 2000, settled = true)
        assertTrue(scan.onFrame(true, moved, 2499, settled = true).isEmpty())
        assertTrue(scan.lightHeld)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, moved, 2500, settled = true))
        assertFalse(scan.lightHeld)
    }

    @Test
    fun `an unreadable face while held neither starts nor advances the pass window`() {
        val scan = lightScan()
        val first = hold(1)
        val dark = darkSides(first)
        val unreadable = listOf(first[0], first[1].copy(cells = List(9) { null }), first[2])
        scan.onFrame(true, dark, 0, settled = false)
        scan.onFrame(true, dark, 100, settled = true)
        assertTrue(scan.lightHeld)
        scan.onFrame(true, first, 1200, settled = true)
        scan.onFrame(true, unreadable, 1400, settled = true)
        assertEquals(null, scan.light!!.score, "an unreadable face has no score")
        assertTrue(scan.light!!.passed, "a verdict without a score passes, but must not clear the hold")
        // Without the reset the window from 1200 would clear the hold at 1700.
        listOf(1500L, 1700L, 1999L).forEach { now ->
            assertTrue(scan.onFrame(true, first, now, settled = true).isEmpty())
            assertTrue(scan.lightHeld, "still held at $now")
        }
        // Unreadable frames alone never clear it.
        listOf(2100L, 3100L, 4100L).forEach { now -> scan.onFrame(true, unreadable, now, settled = true) }
        assertTrue(scan.lightHeld)
        scan.onFrame(true, first, 4200, settled = true)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 4700, settled = true))
        assertFalse(scan.lightHeld)
    }

    @Test
    fun `a flickering verdict while held still coaches sides-dark one second after the first failing frame`() {
        val scan = lightScan()
        val first = hold(1)
        val dark = darkSides(first)
        scan.onFrame(true, dark, 0, settled = false)
        scan.onFrame(true, dark, 100, settled = true)
        // Short passes (each under the 0.5 s clear window) never restart the one-second failing timer.
        listOf(300L to first, 500L to dark, 700L to first, 900L to dark, 1000L to first).forEach { (now, faces) ->
            scan.onFrame(true, faces, now, settled = true)
            assertEquals(null, scan.coaching, "no line yet at $now")
        }
        scan.onFrame(true, dark, 1100, settled = true)
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        assertTrue(scan.lightHeld)
    }

    @Test
    fun `losing the cube while held clears the line at once but keeps the hold`() {
        val scan = lightScan()
        val first = hold(1)
        val dark = darkSides(first)
        scan.onFrame(true, dark, 0, settled = false)
        scan.onFrame(true, dark, 100, settled = true)
        scan.onFrame(true, dark, 1100, settled = true)
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        assertTrue(scan.onFrame(false, List(3) { null }, 1200).isEmpty())
        assertEquals(null, scan.coaching)
        assertTrue(scan.lightHeld)
        assertTrue(scan.onShutter().isEmpty(), "the shutter stays blocked while the hold persists")
        scan.onFrame(false, List(3) { null }, 4199)
        assertEquals(null, scan.coaching)
        scan.onFrame(false, List(3) { null }, 4200)
        assertEquals(Coaching.PLAIN_LIGHT_SURFACE, scan.coaching)
        // Found again, still failing: the one-second timer restarts.
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, dark, 4300, settled = false))
        assertEquals(null, scan.coaching)
        scan.onFrame(true, dark, 5299, settled = true)
        assertEquals(null, scan.coaching)
        scan.onFrame(true, dark, 5300, settled = true)
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        // Found again and passing: the hold clears only after a pass-and-steady window.
        scan.onFrame(false, List(3) { null }, 5400)
        scan.onFrame(true, first, 5500, settled = true)
        scan.onFrame(true, first, 5600, settled = true)
        assertTrue(scan.lightHeld)
        assertEquals(null, scan.coaching)
        scan.onFrame(true, first, 6000, settled = true)
        assertFalse(scan.lightHeld)
    }

    @Test
    fun `sides-dark outranks low light, and low light glare and blur keep their order`() {
        val scan = lightScan()
        val first = hold(1)
        val dark = darkSides(first)
        val darkFace = listOf(dark[0].copy(meanLuma = 10.0), dark[1].copy(glareFraction = 0.5), dark[2].copy(sharpness = 0.0))
        scan.onFrame(true, darkFace, 0, settled = false)
        scan.onFrame(true, darkFace, 100, settled = true)
        assertEquals(Coaching.LOW_LIGHT, scan.coaching)
        scan.onFrame(true, darkFace, 1100, settled = true)
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        val other = lightScan()
        other.onFrame(true, listOf(first[0], first[1].copy(glareFraction = 0.5), first[2].copy(sharpness = 0.0)), 0, settled = true)
        assertEquals(Coaching.GLARE, other.coaching)
        other.syncTorch(requested = true, applied = true, nowMs = 50)
        other.onFrame(true, listOf(first[0], first[1].copy(glareFraction = 0.5), first[2].copy(sharpness = 0.0)), 60, settled = true)
        assertEquals(Coaching.TORCH_GLARE, other.coaching, "torch glare keeps glare's rank, above blur")
        other.onFrame(true, listOf(first[0], first[1], first[2].copy(sharpness = 0.0)), 100, settled = true)
        assertEquals(Coaching.BLURRED, other.coaching)
    }

    @Test
    fun `glare with the torch on coaches torch glare, and a toggle re-meters only once the torch is applied`() {
        val scan = lightScan()
        val first = hold(1)
        val glare = listOf(first[0].copy(glareFraction = 0.5), first[1], first[2])
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, glare, 0, settled = false))
        assertEquals(Coaching.GLARE, scan.coaching)
        scan.syncTorch(requested = true, applied = false, nowMs = 100)
        assertTrue(scan.torchOn)
        assertTrue(scan.torchPending)
        assertTrue(scan.settling)
        // Coaching follows the torch at once; metering waits for the physical change.
        assertTrue(scan.onFrame(true, glare, 150, settled = true).isEmpty())
        assertEquals(Coaching.TORCH_GLARE, scan.coaching)
        scan.syncTorch(requested = true, applied = false, nowMs = 160)
        assertTrue(scan.torchPending, "the old physical state is not this change")
        scan.syncTorch(requested = true, applied = true, nowMs = 170)
        assertFalse(scan.torchPending)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, glare, 200, settled = false))
        assertTrue(scan.onFrame(true, glare, 300, settled = true).isEmpty())
        scan.syncTorch(requested = true, applied = true, nowMs = 350)
        assertFalse(scan.torchPending, "no toggle, nothing pending")
        assertTrue(scan.onFrame(true, glare, 400, settled = true).isEmpty(), "no toggle, no re-meter")
        // A toggle the adapter has already applied by this frame re-meters at once.
        scan.syncTorch(requested = false, applied = false, nowMs = 450)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, glare, 500, settled = false))
        assertEquals(Coaching.GLARE, scan.coaching)
        scan.onFrame(true, first, 600, settled = true)
        assertEquals(null, scan.coaching)
    }

    @Test
    fun `a torch change the adapter never confirms re-meters at the settle cap`() {
        val scan = lightScan()
        val first = hold(1)
        scan.onFrame(true, first, 0, settled = false)
        scan.onFrame(true, first, 100, settled = true)
        scan.syncTorch(requested = true, applied = false, nowMs = 200)
        assertTrue(scan.onFrame(true, first, 200 + QuickScanController.SETTLE_CAP_MS - 1, settled = true).isEmpty())
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 200 + QuickScanController.SETTLE_CAP_MS, settled = false))
        assertFalse(scan.torchPending)
        // Still not applied: the same request does not wait again.
        scan.syncTorch(requested = true, applied = false, nowMs = 2000)
        assertFalse(scan.torchPending)
    }

    @Test
    fun `a physical torch that drifts off the wanted state waits for it again`() {
        val scan = lightScan()
        val first = hold(1)
        scan.syncTorch(requested = true, applied = true, nowMs = 0)
        scan.onFrame(true, first, 0, settled = false)
        scan.onFrame(true, first, 100, settled = true)
        assertFalse(scan.torchPending)
        // E.g. the camera reopened with the torch off: wait for it to come back on before metering.
        scan.syncTorch(requested = true, applied = false, nowMs = 200)
        assertTrue(scan.torchPending)
        scan.syncTorch(requested = true, applied = true, nowMs = 300)
        assertFalse(scan.torchPending)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 400, settled = false))
    }

    @Test
    fun `a press after a torch toggle and before its re-meter is queued and fires once settled`() {
        val scan = lightScan()
        val first = hold(1)
        scan.onFrame(true, first, 0, settled = false)
        scan.onFrame(true, first, 100, settled = true)
        assertFalse(scan.settling)
        scan.syncTorch(requested = true, applied = false, nowMs = 200)
        assertTrue(scan.onShutter().isEmpty(), "queued, not taken on the pre-torch exposure")
        assertTrue(scan.onFrame(true, first, 250, settled = true).isEmpty())
        scan.syncTorch(requested = true, applied = true, nowMs = 260)
        assertTrue(scan.onShutter().isEmpty(), "still queued until the re-meter")
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 300, settled = false))
        assertTrue(scan.onFrame(true, first, 400, settled = false).isEmpty())
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, first, 500, settled = true))
    }

    @Test
    fun `auto capture waits for the settle signal, or the cap`() {
        val signalled = lightScan()
        val first = hold(1)
        signalled.onFrame(true, first, 0, settled = false)
        assertTrue(signalled.settling)
        assertTrue(signalled.onFrame(true, first, 1200, settled = false).isEmpty())
        assertTrue(signalled.onFrame(true, first, 1300, settled = true).isEmpty())
        assertFalse(signalled.settling)
        assertTrue(signalled.onFrame(true, first, 2299, settled = true).isEmpty())
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), signalled.onFrame(true, first, 2300, settled = true))

        val capped = lightScan()
        capped.onFrame(true, first, 0, settled = false)
        assertTrue(capped.onFrame(true, first, 1499, settled = false).isEmpty())
        assertTrue(capped.onFrame(true, first, 1500, settled = false).isEmpty())
        assertFalse(capped.settling)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), capped.onFrame(true, first, 2500, settled = false))
    }

    @Test
    fun `a manual press while settling fires once settled, unless the hold engages or the cube is lost first`() {
        val first = hold(1)
        val queued = lightScan()
        queued.onFrame(true, first, 0, settled = false)
        assertTrue(queued.onShutter().isEmpty())
        assertTrue(queued.onFrame(true, first, 100, settled = false).isEmpty())
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), queued.onFrame(true, first, 200, settled = true))
        assertEquals(2, queued.hold)

        val lost = lightScan()
        lost.onFrame(true, first, 0, settled = false)
        assertTrue(lost.onShutter().isEmpty())
        lost.onFrame(false, List(3) { null }, 100)
        assertEquals(listOf(QuickScanEffect.Remeter), lost.onFrame(true, first, 200, settled = false))
        assertTrue(lost.onFrame(true, first, 300, settled = true).isEmpty(), "the dropped press never fires")
        assertEquals(1, lost.hold)

        val held = lightScan()
        held.onFrame(true, first, 0, settled = false)
        assertTrue(held.onShutter().isEmpty())
        assertTrue(held.onFrame(true, darkSides(first), 100, settled = true).isEmpty())
        held.onFrame(true, first, 200, settled = true)
        assertEquals(listOf(QuickScanEffect.Remeter), held.onFrame(true, first, 700, settled = true))
        assertTrue(held.onFrame(true, first, 800, settled = true).isEmpty(), "the dropped press never fires")
        assertEquals(1, held.hold)

        // Settled already: the press is taken at once.
        val settledScan = lightScan()
        settledScan.onFrame(true, first, 0, settled = false)
        settledScan.onFrame(true, first, 100, settled = true)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), settledScan.onShutter())
    }

    @Test
    fun `a front-lit steady corner captures both holds`() {
        val scan = lightScan()
        val first = hold(1)
        scan.onFrame(true, first, 0, settled = false)
        scan.onFrame(true, first, 100, settled = true)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, first, 1100, settled = true))
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, hold(2), 1200, settled = false))
        scan.onFrame(true, hold(2), 1300, settled = true)
        val effects = scan.onFrame(true, hold(2), 2300, settled = true)
        assertEquals(QuickScanEffect.CornerCaptured(2), effects.first())
        assertTrue(effects.last() is QuickScanEffect.Done)
    }

    @Test
    fun `the light hold starts afresh on hold 2 after a front-lit hold 1`() {
        val scan = lightScan()
        val first = hold(1)
        scan.onFrame(true, first, 0, settled = false)
        scan.onFrame(true, first, 100, settled = true)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, first, 1100, settled = true))
        assertFalse(scan.lightHeld)
        val dark = darkSides(hold(2))
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, dark, 1200, settled = false))
        assertFalse(scan.lightHeld, "hold 2's first frame re-meters onto the cube and holds nothing")
        scan.onFrame(true, dark, 1300, settled = true)
        assertTrue(scan.lightHeld)
        assertEquals(1300L, scan.lightHeldSince)
        scan.onFrame(true, dark, 2299, settled = true)
        assertEquals(null, scan.coaching, "the one-second timer starts with this hold")
        assertTrue(scan.onFrame(true, dark, 2300, settled = true).isEmpty())
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        assertEquals(2, scan.hold)
        // Scan face by face clears the hold with the hand-over.
        scan.switchToFaceByFace()
        assertFalse(scan.lightHeld)
    }

    @Test
    fun `with a saved calibration a sticker the saved and self reads disagree on is uncertain, never confidently wrong`() {
        val samples =
            QuickScanPlan.holds.flatMapIndexed { index, steps ->
                steps.zip(hold(index + 1, CubeState.SOLVED)).map { (step, stats) ->
                    FaceSample(
                        face = step.face,
                        orientationDegrees = 0,
                        stickers = stats.cells.map { it!!.value },
                        quality = CaptureQuality.ACCEPTABLE,
                        centerColor = step.centerColor,
                        upNeighborColor = step.upNeighborColor,
                        centerPlain = true,
                    )
                }
            }
        val swapped =
            CalibrationCenters(
                saved.centers + mapOf(Color.RED to labs.getValue(Color.ORANGE), Color.ORANGE to labs.getValue(Color.RED)),
            )
        val r = samples.single { it.face == Face.R }
        assertTrue(QuickScanClassification.read(r, samples, saved).all { it?.confidence == StickerConfidence.CONFIDENT })
        val readings = QuickScanClassification.read(r, samples, swapped)
        assertTrue(readings.none { it?.confidence == StickerConfidence.CONFIDENT && it.color != Color.RED }, "$readings")
        assertTrue(Face.R in QuickScanClassification.needGrid(samples, Face.entries, swapped))
        assertEquals(emptyList<Face>(), QuickScanClassification.needGrid(samples, Face.entries, saved))
        // The Quick Scan path reads with the shared self-calibration, unchanged.
        assertEquals(
            (SelfCalibration.from(samples, saved) as SelfCalibration.Calibrated).calibration,
            QuickScanClassification.calibration(samples, saved),
        )
    }

    @Test
    fun `with no saved calibration a uniformly darker face still agrees, and a light-dependent read is uncertain`() {
        val samples =
            QuickScanPlan.holds.flatMapIndexed { index, steps ->
                steps.zip(hold(index + 1, CubeState.SOLVED)).map { (step, stats) ->
                    FaceSample(
                        face = step.face,
                        orientationDegrees = 0,
                        stickers = stats.cells.map { it!!.value },
                        quality = CaptureQuality.ACCEPTABLE,
                        centerColor = step.centerColor,
                        upNeighborColor = step.upNeighborColor,
                        centerPlain = true,
                    )
                }
            }
        assertEquals(emptyList<Face>(), QuickScanClassification.needGrid(samples, Face.entries, null))
        // F (green) shaded: every sticker 20 L* darker, its own center too; still read green and confident.
        val shaded = samples.map { s -> if (s.face == Face.F) s.copy(stickers = s.stickers.map { it.copy(l = it.l - 20) }) else s }
        assertTrue(Face.F !in QuickScanClassification.needGrid(shaded, Face.entries, null))
        // A white sticker on a dark face: the self read says white, the light-normalized read disagrees.
        val mixed =
            samples.map { s ->
                if (s.face == Face.B) s.copy(stickers = s.stickers.mapIndexed { i, lab -> if (i == 0) Lab(64.0, 0.0, 0.0) else lab }) else s
            }
        val readings = QuickScanClassification.read(mixed.single { it.face == Face.B }, mixed, null)
        assertTrue(readings[0]?.confidence != StickerConfidence.CONFIDENT || readings[0]?.color == Color.WHITE, "$readings")
    }

    private fun guidedCapture(
        scan: QuickScanController,
        stats: FrameStats,
        rotation: Int = 0,
    ): QuickScanEffect? {
        val grid = checkNotNull(scan.fallback)
        grid.onFrame(stats, rotation, 0)
        return scan.onGuidedEffects(grid.onShutter(0))
    }

    private fun assemble(samples: List<FaceSample>): ScanResult.Full =
        ScanAssembler.assemble(samples, (SelfCalibration.from(samples) as SelfCalibration.Calibrated).calibration) as ScanResult.Full

    private fun FrameStats.withCell(
        index: Int,
        lab: Lab,
    ): FrameStats = copy(cells = cells.toMutableList().also { it[index] = CellReading(lab, true) })
}
