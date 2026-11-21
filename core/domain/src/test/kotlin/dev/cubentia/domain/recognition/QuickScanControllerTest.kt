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
        val scan = QuickScanController()
        val first = hold(1)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 0))
        assertTrue(scan.onFrame(true, first, 599).isEmpty())
        assertTrue(scan.onFrame(true, first, 600).isEmpty())
        val moved = first.toMutableList().also { it[0] = it[0].withCell(0, Lab(79.0, 0.0, 0.0)) }
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
        val scan = QuickScanController()
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
        val scan = QuickScanController()
        val first = hold(1).toMutableList()
        val cells = first[1].cells.toMutableList()
        cells[FaceSample.CENTER_INDEX] = CellReading(Lab(55.0, 55.0, 55.0), false)
        first[1] = first[1].copy(cells = cells)
        val done = capture(scan, first, hold(2)) as QuickScanEffect.Done
        assertEquals(cube.toFacelets(), assemble(done.samples).facelets)
    }

    @Test
    fun `missing poor and uncalibrated faces enter the guided grid`() {
        val scan = QuickScanController()
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
        val scan = QuickScanController()
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
        val scan = QuickScanController()
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
        val scan = QuickScanController()
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
        val scan = QuickScanController()
        scan.onFrame(true, listOf(hold(1)[0], null, null), 0)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onShutter())
        val second = hold(2)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, listOf(null, second[1], second[2]), 100))
    }

    @Test
    fun `a kept corner face is never asked again once calibration becomes possible`() {
        val scan = QuickScanController()
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
        val scan = QuickScanController()
        val first = hold(1).toMutableList().also { it[1] = it[1].withCell(0, Lab(55.0, 55.0, 55.0)) }
        assertEquals(QuickScanEffect.Fallback(listOf(Face.F)), capture(scan, first, hold(2)))
        val grid = checkNotNull(scan.fallback)
        grid.onFrame(face(Face.U), 0, 0)
        assertTrue(grid.onShutter(0).none { it is GuidedScanEffect.Capture })
        assertEquals(Coaching.WRONG_FACE, grid.coaching)
    }

    @Test
    fun `face by face before the first capture asks all six faces in guided order with no named top`() {
        val scan = QuickScanController()
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
        val atOne = QuickScanController()
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
        val scan = QuickScanController()
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
        val switched = QuickScanController()
        val first = hold(1, solved).toMutableList()
        first[2] = listOf(0, 2, 8).fold(first[2]) { stats, index -> stats.withCell(index, pale) }
        switched.onFrame(true, first, 0)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), switched.onShutter())
        assertEquals(QuickScanEffect.Fallback(listOf(Face.R, Face.B, Face.L, Face.D)), switched.switchToFaceByFace())
        assertEquals(setOf(Face.U, Face.F), switched.keptFaces)

        // The automatic hand-over with no green center: a top read between yellow and orange is asked with the missing F.
        val automatic = QuickScanController()
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
        val switched = QuickScanController()
        switched.onFrame(true, hold(1, solved), 0)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), switched.onShutter())
        assertEquals(QuickScanEffect.Fallback(listOf(Face.B, Face.L, Face.D)), switched.switchToFaceByFace())
        assertEquals(setOf(Face.U, Face.F, Face.R), switched.keptFaces)

        val automatic = QuickScanController()
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
        val scan = QuickScanController()
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
        val scan = QuickScanController()
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
        val scan = QuickScanController()
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

    private val dark = SideLight.Verdict(passed = false, topLevel = 90.0, leftLevel = 55.0, rightLevel = 80.0)
    private val lit = SideLight.Verdict(passed = true, topLevel = 90.0, leftLevel = 75.0, rightLevel = 80.0)

    @Test
    fun `a backlit corner holds every capture and coaches sides-dark after one second of failing`() {
        val scan = QuickScanController()
        val first = hold(1)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 0, dark, settled = false))
        assertTrue(scan.lightHeld)
        assertEquals(0L, scan.lightHeldSince)
        assertEquals(null, scan.coaching)
        for (now in 100L..5000L step 100) {
            assertTrue(scan.onFrame(true, first, now, dark, settled = true).isEmpty(), "no auto capture at $now")
            assertEquals(if (now >= 1000) Coaching.SIDES_DARK else null, scan.coaching, "coaching at $now")
            assertTrue(scan.onShutter().isEmpty(), "the shutter is ignored while held at $now")
        }
        assertEquals(1, scan.hold)
    }

    @Test
    fun `correcting the light clears the line with the hold, re-meters and restarts the steady count`() {
        val scan = QuickScanController()
        val first = hold(1)
        scan.onFrame(true, first, 0, dark, settled = false)
        scan.onFrame(true, first, 1000, dark, settled = true)
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        assertTrue(scan.onFrame(true, first, 1100, lit, settled = true).isEmpty())
        assertTrue(scan.onFrame(true, first, 1599, lit, settled = true).isEmpty())
        assertTrue(scan.lightHeld)
        assertEquals(Coaching.SIDES_DARK, scan.coaching, "the line stays up through the pass window")
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 1600, lit, settled = true))
        assertFalse(scan.lightHeld)
        assertEquals(null, scan.coaching)
        assertTrue(scan.onFrame(true, first, 1700, lit, settled = false).isEmpty())
        assertTrue(scan.onFrame(true, first, 1800, lit, settled = true).isEmpty())
        assertTrue(scan.onFrame(true, first, 2799, lit, settled = true).isEmpty())
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, first, 2800, lit, settled = true))
    }

    @Test
    fun `a moving pass or a pass shorter than half a second keeps the hold and the line`() {
        val scan = QuickScanController()
        val first = hold(1)
        scan.onFrame(true, first, 0, dark, settled = true)
        scan.onFrame(true, first, 1000, dark, settled = true)
        scan.onFrame(true, first, 1100, lit, settled = true)
        scan.onFrame(true, first, 1400, lit, settled = true)
        assertTrue(scan.onFrame(true, first, 1500, dark, settled = true).isEmpty())
        assertTrue(scan.lightHeld)
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        // Passing but moving: each change restarts the pass-and-steady window.
        val moved = first.toMutableList().also { it[0] = it[0].withCell(0, Lab(70.0, 0.0, 0.0)) }
        scan.onFrame(true, first, 1600, lit, settled = true)
        scan.onFrame(true, moved, 1900, lit, settled = true)
        assertTrue(scan.onFrame(true, moved, 2399, lit, settled = true).isEmpty())
        assertTrue(scan.lightHeld)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, moved, 2400, lit, settled = true))
        assertFalse(scan.lightHeld)
    }

    @Test
    fun `losing the cube while held clears the line at once but keeps the hold`() {
        val scan = QuickScanController()
        val first = hold(1)
        scan.onFrame(true, first, 0, dark, settled = true)
        scan.onFrame(true, first, 1000, dark, settled = true)
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        assertTrue(scan.onFrame(false, List(3) { null }, 1100).isEmpty())
        assertEquals(null, scan.coaching)
        assertTrue(scan.lightHeld)
        assertTrue(scan.onShutter().isEmpty(), "the shutter stays blocked while the hold persists")
        scan.onFrame(false, List(3) { null }, 4099)
        assertEquals(null, scan.coaching)
        scan.onFrame(false, List(3) { null }, 4100)
        assertEquals(Coaching.PLAIN_LIGHT_SURFACE, scan.coaching)
        // Found again, still failing: the one-second timer restarts.
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 4200, dark, settled = false))
        assertEquals(null, scan.coaching)
        scan.onFrame(true, first, 5199, dark, settled = true)
        assertEquals(null, scan.coaching)
        scan.onFrame(true, first, 5200, dark, settled = true)
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        // Found again and passing: the hold clears only after a pass-and-steady window.
        scan.onFrame(false, List(3) { null }, 5300)
        scan.onFrame(true, first, 5400, lit, settled = true)
        scan.onFrame(true, first, 5500, lit, settled = true)
        assertTrue(scan.lightHeld)
        assertEquals(null, scan.coaching)
        scan.onFrame(true, first, 5900, lit, settled = true)
        assertFalse(scan.lightHeld)
    }

    @Test
    fun `sides-dark outranks low light, and low light glare and blur keep their order`() {
        val scan = QuickScanController()
        val first = hold(1)
        val darkFace = listOf(first[0].copy(meanLuma = 10.0), first[1].copy(glareFraction = 0.5), first[2].copy(sharpness = 0.0))
        scan.onFrame(true, darkFace, 0, dark, settled = true)
        assertEquals(Coaching.LOW_LIGHT, scan.coaching)
        scan.onFrame(true, darkFace, 1000, dark, settled = true)
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        val other = QuickScanController()
        other.onFrame(true, listOf(first[0], first[1].copy(glareFraction = 0.5), first[2].copy(sharpness = 0.0)), 0, lit, settled = true)
        assertEquals(Coaching.GLARE, other.coaching)
        other.onFrame(true, listOf(first[0], first[1], first[2].copy(sharpness = 0.0)), 100, lit, settled = true)
        assertEquals(Coaching.BLURRED, other.coaching)
    }

    @Test
    fun `glare with the torch on coaches torch glare, and a toggle re-meters`() {
        val scan = QuickScanController()
        val first = hold(1)
        val glare = listOf(first[0].copy(glareFraction = 0.5), first[1], first[2])
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, glare, 0, lit, settled = false))
        assertEquals(Coaching.GLARE, scan.coaching)
        scan.setTorch(true)
        assertTrue(scan.torchOn)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, glare, 100, lit, settled = false))
        assertEquals(Coaching.TORCH_GLARE, scan.coaching)
        assertTrue(scan.onFrame(true, glare, 200, lit, settled = true).isEmpty())
        scan.setTorch(true)
        assertTrue(scan.onFrame(true, glare, 300, lit, settled = true).isEmpty(), "no toggle, no re-meter")
        scan.setTorch(false)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, glare, 400, lit, settled = false))
        assertEquals(Coaching.GLARE, scan.coaching)
        scan.onFrame(true, first, 500, lit, settled = true)
        assertEquals(null, scan.coaching)
    }

    @Test
    fun `auto capture waits for the settle signal, or the cap`() {
        val signalled = QuickScanController()
        val first = hold(1)
        signalled.onFrame(true, first, 0, lit, settled = false)
        assertTrue(signalled.settling)
        assertTrue(signalled.onFrame(true, first, 1200, lit, settled = false).isEmpty())
        assertTrue(signalled.onFrame(true, first, 1300, lit, settled = true).isEmpty())
        assertFalse(signalled.settling)
        assertTrue(signalled.onFrame(true, first, 2299, lit, settled = true).isEmpty())
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), signalled.onFrame(true, first, 2300, lit, settled = true))

        val capped = QuickScanController()
        capped.onFrame(true, first, 0, lit, settled = false)
        assertTrue(capped.onFrame(true, first, 1499, lit, settled = false).isEmpty())
        assertTrue(capped.onFrame(true, first, 1500, lit, settled = false).isEmpty())
        assertFalse(capped.settling)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), capped.onFrame(true, first, 2500, lit, settled = false))
    }

    @Test
    fun `a manual press while settling fires once settled, unless the hold engages or the cube is lost first`() {
        val first = hold(1)
        val queued = QuickScanController()
        queued.onFrame(true, first, 0, lit, settled = false)
        assertTrue(queued.onShutter().isEmpty())
        assertTrue(queued.onFrame(true, first, 100, lit, settled = false).isEmpty())
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), queued.onFrame(true, first, 200, lit, settled = true))
        assertEquals(2, queued.hold)

        val lost = QuickScanController()
        lost.onFrame(true, first, 0, lit, settled = false)
        assertTrue(lost.onShutter().isEmpty())
        lost.onFrame(false, List(3) { null }, 100)
        assertEquals(listOf(QuickScanEffect.Remeter), lost.onFrame(true, first, 200, lit, settled = false))
        assertTrue(lost.onFrame(true, first, 300, lit, settled = true).isEmpty(), "the dropped press never fires")
        assertEquals(1, lost.hold)

        val held = QuickScanController()
        held.onFrame(true, first, 0, lit, settled = false)
        assertTrue(held.onShutter().isEmpty())
        assertTrue(held.onFrame(true, first, 100, dark, settled = true).isEmpty())
        held.onFrame(true, first, 200, lit, settled = true)
        assertEquals(listOf(QuickScanEffect.Remeter), held.onFrame(true, first, 700, lit, settled = true))
        assertTrue(held.onFrame(true, first, 800, lit, settled = true).isEmpty(), "the dropped press never fires")
        assertEquals(1, held.hold)

        // Settled already: the press is taken at once.
        val settledScan = QuickScanController()
        settledScan.onFrame(true, first, 0, lit, settled = false)
        settledScan.onFrame(true, first, 100, lit, settled = true)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), settledScan.onShutter())
    }

    @Test
    fun `a front-lit steady corner captures both holds and the light hold resets per hold`() {
        val scan = QuickScanController(saved)
        val first = hold(1)
        scan.onFrame(true, first, 0, lit, settled = false)
        scan.onFrame(true, first, 100, lit, settled = true)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, first, 1100, lit, settled = true))
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, hold(2), 1200, lit, settled = false))
        scan.onFrame(true, hold(2), 1300, lit, settled = true)
        val effects = scan.onFrame(true, hold(2), 2300, lit, settled = true)
        assertEquals(QuickScanEffect.CornerCaptured(2), effects.first())
        assertTrue(effects.last() is QuickScanEffect.Done)
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
