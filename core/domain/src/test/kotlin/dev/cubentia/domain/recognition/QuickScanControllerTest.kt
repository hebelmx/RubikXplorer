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

    /** A cube outline of twelve points whose bounding box is 300 × 400 px (diagonal 500), shifted [dx] px to the right. */
    private fun outline(dx: Double = 0.0): List<OutlinePoint> =
        listOf(150.0 to 0.0, 300.0 to 100.0, 150.0 to 200.0, 0.0 to 100.0, 0.0 to 300.0, 150.0 to 400.0, 300.0 to 300.0)
            .let { p -> listOf(p[0], p[1], p[2], p[3], p[3], p[2], p[5], p[4], p[2], p[1], p[6], p[5]) }
            .map { (x, y) -> OutlinePoint(x + dx, y) }

    private var jitter = 0

    /** An outline that moves 100 px (0.2 of its diagonal) on every call: a hand that never holds still. */
    private fun shaking(): List<OutlinePoint> = outline(dx = (jitter++ % 2) * 100.0)

    /** One settled, detected frame whose outline keeps moving: the steady window restarts, so nothing auto-captures. */
    private fun QuickScanController.shake(
        faces: List<FrameStats?>,
        nowMs: Long,
    ) = onFrame(true, faces, nowMs, settled = true, outline = shaking())

    /**
     * The detected frames that confirm the cube was turned to another corner (Story 10.22), the last one at [nowMs]:
     * the earlier ones change nothing, the last one's effects are returned.
     */
    private fun confirmTurn(
        scan: QuickScanController,
        faces: List<FrameStats?>,
        nowMs: Long,
        settled: Boolean? = null,
    ): List<QuickScanEffect> {
        val frames = QuickScanController.TURN_CONFIRM_FRAMES
        for (earlier in frames - 1 downTo 1) assertTrue(scan.onFrame(true, faces, nowMs - earlier, settled).isEmpty())
        return scan.onFrame(true, faces, nowMs, settled)
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
    fun `auto capture settles and holds the outline steady before taking each corner`() {
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 0, outline = outline()))
        assertTrue(scan.onFrame(true, first, 599, outline = outline()).isEmpty())
        assertTrue(scan.onFrame(true, first, 600, outline = outline()).isEmpty())
        // Story 10.22: the outline moving 0.12 of its diagonal restarts the steady window.
        assertTrue(scan.onFrame(true, first, 800, outline = outline(dx = 60.0)).isEmpty())
        assertTrue(scan.onFrame(true, first, 899, outline = outline(dx = 60.0)).isEmpty())
        assertTrue(scan.onFrame(true, first, 1799, outline = outline(dx = 60.0)).isEmpty())
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, first, 1800, outline = outline(dx = 60.0)))
        assertTrue(scan.onFrame(false, List(3) { null }, 2000).isEmpty())
        assertTrue(scan.onFrame(true, first, 3000).isEmpty())
        assertTrue(scan.onShutter().isEmpty())
        assertEquals(listOf(QuickScanEffect.Remeter), confirmTurn(scan, hold(2), 4000))
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
        // Story 10.21: with a matching calibration, so the Orange stickers' second read keeps the fitted Orange margin.
        val scan = QuickScanController(saved, lightCheck = false)
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
    fun `the second corner arms only once detected frames in a row show other centres`() {
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
        // Story 10.22: a single frame showing another corner never clears the previous hold, and the count starts over
        // when the first corner shows again.
        assertTrue(scan.onFrame(true, hold(2), 300).isEmpty())
        assertTrue(scan.onFrame(true, hold(2), 400).isEmpty())
        assertTrue(scan.onFrame(true, first, 500).isEmpty())
        assertTrue(scan.onFrame(true, hold(2), 600).isEmpty())
        assertTrue(scan.onFrame(true, hold(2), 700).isEmpty())
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, hold(2), 800))
    }

    @Test
    fun `a second corner with no cell comparable to a partial first corner still arms`() {
        val scan = QuickScanController(lightCheck = false)
        scan.onFrame(true, listOf(hold(1)[0], null, null), 0)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onShutter())
        val second = hold(2)
        assertEquals(listOf(QuickScanEffect.Remeter), confirmTurn(scan, listOf(null, second[1], second[2]), 100))
    }

    @Test
    fun `a kept corner face is never asked again once calibration becomes possible`() {
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1)
        // Between white and yellow: confident (both gated reads agree) while no white center is captured, uncertain once it is.
        val between = Lab(80.0, -6.0, 35.0)
        // Story 10.21: F's two Orange stickers are made Green, as the normalized read leaves them below the Orange margin.
        val laterUncertainF = first[1].withCell(0, between).withCell(1, labs.getValue(Color.GREEN)).withCell(2, labs.getValue(Color.GREEN))
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
        assertEquals(listOf(QuickScanEffect.Remeter), confirmTurn(scan, twoChanged, 200))
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
    fun `an uncalibrated light fail offers calibration after five seconds, never calibrated nor torch-lit`() {
        val bright = FrameStats(List(9) { CellReading(Lab(90.0, 0.0, 0.0), true) }, 120.0, 0.0, 100.0, 12)
        val dim = FrameStats(List(9) { CellReading(Lab(50.0, 0.0, 0.0), true) }, 120.0, 0.0, 100.0, 12)
        val dark = listOf(bright, dim, bright)
        val lit = listOf(bright, bright, bright)
        val none = List<FrameStats?>(3) { null }
        val offerAt = 100 + QuickScanController.CALIBRATION_OFFER_MS

        // The outline keeps moving in this test: the light check no longer holds a capture back (Story 10.22), so only a
        // hold that does not capture can fail for five seconds.
        val scan = QuickScanController()
        scan.onFrame(true, dark, 0, settled = false)
        scan.shake(dark, 100)
        assertTrue(scan.lightHeld)
        scan.shake(dark, offerAt - 1)
        assertFalse(scan.calibrationOffered, "not before five seconds of failing")
        assertTrue(scan.shake(dark, offerAt).isEmpty())
        assertTrue(scan.calibrationOffered)
        // The offer only adds: the sides-dark line stays.
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        assertEquals(1, scan.hold)
        // A detection gap inside the grace keeps it; a lost cube hides it, and it comes back while the fail runs on.
        scan.onFrame(false, none, offerAt + 100)
        assertTrue(scan.calibrationOffered)
        scan.onFrame(false, none, offerAt + 100 + CornerHold.LOST_MS)
        assertFalse(scan.calibrationOffered, "hidden once the cube is lost")
        scan.onFrame(true, dark, offerAt + 600, settled = false)
        assertTrue(scan.lightHeld)
        assertTrue(scan.calibrationOffered)
        // The check passing again hides it.
        scan.shake(lit, offerAt + 700)
        scan.shake(lit, offerAt + 700 + CornerHold.LIGHT_CLEAR_MS)
        assertFalse(scan.lightHeld)
        assertFalse(scan.calibrationOffered)

        // The torch turning on clears the fail and with it the offer; while lit, no fail and no offer.
        val torch = QuickScanController()
        torch.onFrame(true, dark, 0, settled = false)
        torch.shake(dark, 100)
        torch.shake(dark, offerAt)
        assertTrue(torch.calibrationOffered)
        torch.syncTorch(requested = true, applied = true, nowMs = offerAt + 50)
        assertFalse(torch.calibrationOffered)
        for (now in offerAt + 100..offerAt + 7000 step 100) torch.shake(dark, now)
        assertFalse(torch.lightHeld)
        assertFalse(torch.calibrationOffered, "never offered with the torch lit")

        // Scan face by face hides it.
        val switched = QuickScanController()
        switched.onFrame(true, dark, 0, settled = false)
        switched.shake(dark, 100)
        switched.shake(dark, offerAt)
        assertTrue(switched.calibrationOffered)
        switched.switchToFaceByFace()
        assertFalse(switched.calibrationOffered)

        // Calibrated mode: the fail runs as long, but calibration is never offered.
        val calibrated = lightScan()
        // Dark enough to fail the calibrated check, the side centres still within the centre closeness of the calibration.
        val calibratedDark = darkSides(hold(1), brightness = 0.73)
        calibrated.onFrame(true, calibratedDark, 0, settled = false)
        for (now in 100L..offerAt + 2000 step 100) calibrated.shake(calibratedDark, now)
        assertTrue(calibrated.lightHeld)
        assertFalse(calibrated.calibrationOffered, "never offered with a saved calibration")
    }

    @Test
    fun `a backlit corner coaches sides-dark after one second of failing and still captures, auto and manual`() {
        val scan = lightScan()
        val dark = darkSides(hold(1))
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, dark, 0, settled = false))
        assertFalse(scan.lightHeld, "the first frame re-meters onto the cube and coaches nothing")
        assertEquals(null, scan.coaching)
        // Hand-held and moving, nothing captures: the coaching keeps its 10.19 timing.
        for (now in 100L..3000L step 100) {
            assertTrue(scan.shake(dark, now).isEmpty(), "no auto capture while moving at $now")
            assertTrue(scan.lightHeld, "failing at $now")
            assertEquals(100L, scan.lightHeldSince, "the fail starts on the first settled failing frame")
            assertEquals(if (now >= 1100) Coaching.SIDES_DARK else null, scan.coaching, "coaching at $now")
        }
        // Story 10.22: the check is advisory. Held still, the corner auto-captures in the failing light after one steady
        // second, with the line still showing.
        val still = outline(dx = 50.0)
        assertTrue(scan.onFrame(true, dark, 3100, settled = true, outline = still).isEmpty())
        assertTrue(scan.onFrame(true, dark, 4099, settled = true, outline = still).isEmpty())
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, dark, 4100, settled = true, outline = still))
        val auto = checkNotNull(scan.lastCapture)
        assertFalse(auto.manual)
        assertEquals(false, auto.lightPassed)
        assertTrue(auto.lightScore!! > SideLight.MAX_TOP_OVER_SIDES_RATIO)
        assertEquals(4100L, auto.totalMs)
        assertEquals(0L, auto.undetectedMs)

        // The manual shutter works in the failing light too.
        val manual = lightScan()
        manual.onFrame(true, dark, 0, settled = false)
        manual.shake(dark, 100)
        manual.shake(dark, 1100)
        assertEquals(Coaching.SIDES_DARK, manual.coaching)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), manual.onShutter(1150))
        assertTrue(manual.lastCapture!!.manual)
        assertEquals(false, manual.lastCapture!!.lightPassed)
        assertEquals(1150L, manual.lastCapture!!.totalMs)
    }

    @Test
    fun `correcting the light hides the line without a re-meter and without restarting the steady window`() {
        val scan = lightScan()
        val first = hold(1)
        val dark = darkSides(first)
        scan.onFrame(true, dark, 0, settled = false)
        scan.shake(dark, 100)
        scan.shake(dark, 600)
        scan.shake(dark, 1100)
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        // The Learner turns to the light and holds still: the steady window starts at 1200.
        val still = outline(dx = 50.0)
        assertTrue(scan.onFrame(true, first, 1200, settled = true, outline = still).isEmpty())
        assertTrue(scan.onFrame(true, first, 1699, settled = true, outline = still).isEmpty())
        assertTrue(scan.lightHeld)
        assertEquals(Coaching.SIDES_DARK, scan.coaching, "the line stays up through the pass window")
        // Story 10.22: the clear is no metering change (F4)...
        assertTrue(scan.onFrame(true, first, 1700, settled = true, outline = still).isEmpty())
        assertFalse(scan.lightHeld)
        assertEquals(null, scan.coaching)
        assertFalse(scan.settling)
        // ...and the steady window that began at 1200 runs on.
        assertTrue(scan.onFrame(true, first, 2199, settled = true, outline = still).isEmpty())
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, first, 2200, settled = true, outline = still))
        assertEquals(true, scan.lastCapture!!.lightPassed)
    }

    @Test
    fun `one flickering right-face cell neither restarts the steady window nor delays auto capture`() {
        val scan = lightScan()
        val first = hold(1)
        scan.onFrame(true, first, 0, settled = false, outline = outline())
        scan.onFrame(true, first, 100, settled = true, outline = outline())
        // The Lenovo session: one right-face cell alternating L* ~86/96 while everything else holds still.
        val low = first.toMutableList().also { it[2] = it[2].withCell(4, Lab(86.0, 0.0, 0.0)) }
        val high = first.toMutableList().also { it[2] = it[2].withCell(4, Lab(96.0, 0.0, 0.0)) }
        var capturedAt: Long? = null
        var now = 133L
        while (capturedAt == null && now < 10_000L) {
            val frame = if ((now / 33) % 2 == 0L) low else high
            val effects = scan.onFrame(true, frame, now, settled = true, outline = outline())
            assertFalse(QuickScanEffect.Remeter in effects, "no re-meter at $now")
            if (QuickScanEffect.CornerCaptured(1) in effects) capturedAt = now
            assertEquals(null, scan.steadyRestart, "one moving cell never restarts the steady window at $now")
            now += 33
        }
        val captured = checkNotNull(capturedAt) { "auto capture fired despite the flickering cell" }
        assertTrue(captured <= 100 + CornerHold.STEADY_MS + 33, "captured at $captured")
    }

    @Test
    fun `an exposure shift on a still outline does not restart the steady window, an outline move does and reports it`() {
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 0, outline = outline()))
        assertTrue(scan.onFrame(true, first, 600, outline = outline()).isEmpty())
        // Story 10.22: an exposure change moves every sticker by ΔE 5–8 on a resting cube; the window started at 600 runs on.
        val shifted = first.map { stats -> stats.copy(cells = stats.cells.map { it?.copy(value = it.value.copy(l = it.value.l - 8.0)) }) }
        assertTrue(scan.onFrame(true, shifted, 700, outline = outline()).isEmpty())
        assertEquals(null, scan.steadyRestart)
        // The outline moving less than the steady fraction of its diagonal (500 px) is a hand tremor, not a move.
        assertTrue(scan.onFrame(true, first, 800, outline = outline(dx = 500 * CornerHold.OUTLINE_STEADY_FRACTION - 1)).isEmpty())
        assertEquals(null, scan.steadyRestart)
        assertTrue(scan.onFrame(true, first, 900, outline = outline(dx = 500 * CornerHold.OUTLINE_STEADY_FRACTION + 1)).isEmpty())
        val restart = checkNotNull(scan.steadyRestart)
        assertEquals(CornerSteadyRestart.Reason.OUTLINE, restart.reason)
        assertEquals(CornerHold.OUTLINE_STEADY_FRACTION + 1.0 / 500, restart.amount, 1e-9)
        val moved = outline(dx = 500 * CornerHold.OUTLINE_STEADY_FRACTION + 1)
        assertTrue(scan.onFrame(true, first, 1700, outline = moved).isEmpty(), "the window restarted at 900, not 600")
        assertTrue(scan.onFrame(true, first, 1899, outline = moved).isEmpty())
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, first, 1900, outline = moved))
    }

    @Test
    fun `a face losing its third stable sampled cell restarts the steady window, two cells changing does not`() {
        // PO 2026-11-22, after device session 1: the outline rule alone captured while the cube was being positioned.
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1)
        val other = Lab(20.0, 0.0, 0.0)

        fun changed(vararg cells: Int) = first.toMutableList().also { faces -> cells.forEach { faces[1] = faces[1].withCell(it, other) } }
        scan.onFrame(true, first, 0, outline = outline())
        assertTrue(scan.onFrame(true, first, 600, outline = outline()).isEmpty())
        // Two of the five sampled cells (edges 1, 3, 5, 7 and the plain centre) changed: three are still stable.
        assertTrue(scan.onFrame(true, changed(1, 3), 700, outline = outline()).isEmpty())
        assertEquals(null, scan.steadyRestart)
        // Corner cells are not sampled: any number of them changing restarts nothing.
        assertTrue(scan.onFrame(true, changed(0, 2, 6, 8), 800, outline = outline()).isEmpty())
        assertEquals(null, scan.steadyRestart)
        // A third sampled cell changed: the window restarts, and the timeline names the face.
        assertTrue(scan.onFrame(true, changed(1, 3, 5), 900, outline = outline()).isEmpty())
        val restart = checkNotNull(scan.steadyRestart)
        assertEquals(CornerSteadyRestart.Reason.COLOR, restart.reason)
        assertEquals(1, restart.face)
        assertEquals(2.0, restart.amount)
        val event = scan.drainEvents().last { it.kind == CornerHoldEventKind.STEADY_RESTART }
        assertEquals("color face=1", event.reason)
        assertTrue(scan.onFrame(true, changed(1, 3, 5), 1899, outline = outline()).isEmpty(), "the window restarted at 900")
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, changed(1, 3, 5), 1900, outline = outline()))

        // A logo centre (not plain) is not sampled, and a missing cell is not stable: two edges gone leave two of four.
        val logo = QuickScanController(lightCheck = false)
        val marked =
            first.toMutableList().also { faces ->
                faces[1] =
                    faces[1].copy(
                        cells =
                            faces[1].cells.mapIndexed { i, c ->
                                if (i ==
                                    FaceSample.CENTER_INDEX
                                ) {
                                    c?.copy(plain = false)
                                } else {
                                    c
                                }
                            },
                    )
            }
        logo.onFrame(true, marked, 0, outline = outline())
        logo.onFrame(true, marked, 600, outline = outline())
        val one = marked.toMutableList().also { it[1] = it[1].withCell(1, other) }
        assertTrue(logo.onFrame(true, one, 700, outline = outline()).isEmpty())
        assertEquals(null, logo.steadyRestart)
        val two = one.toMutableList().also { it[1] = it[1].withCell(3, other) }
        logo.onFrame(true, two, 800, outline = outline())
        assertEquals(CornerSteadyRestart.Reason.COLOR, logo.steadyRestart?.reason)
    }

    @Test
    fun `two plain centres changing colour restart the steady window as a different face, one does not`() {
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1)
        scan.onFrame(true, first, 0, outline = outline())
        assertTrue(scan.onFrame(true, first, 600, outline = outline()).isEmpty())
        val one = first.toMutableList().also { it[1] = it[1].withCell(FaceSample.CENTER_INDEX, labs.getValue(Color.ORANGE)) }
        assertTrue(scan.onFrame(true, one, 700, outline = outline()).isEmpty())
        assertEquals(null, scan.steadyRestart, "one centre alone (a logo, a glint) is not another face")
        // A centre that is not plain decides nothing either.
        val logo =
            one.toMutableList().also {
                it[2] =
                    it[2].copy(
                        cells =
                            it[2].cells.mapIndexed { i, c ->
                                if (i ==
                                    FaceSample.CENTER_INDEX
                                ) {
                                    CellReading(labs.getValue(Color.BLUE), false)
                                } else {
                                    c
                                }
                            },
                    )
            }
        assertTrue(scan.onFrame(true, logo, 800, outline = outline()).isEmpty())
        assertEquals(null, scan.steadyRestart)
        val two = one.toMutableList().also { it[2] = it[2].withCell(FaceSample.CENTER_INDEX, labs.getValue(Color.BLUE)) }
        assertTrue(scan.onFrame(true, two, 900, outline = outline()).isEmpty())
        assertEquals(CornerSteadyRestart.Reason.FACE, scan.steadyRestart?.reason)
        assertTrue(scan.onFrame(true, two, 1899, outline = outline()).isEmpty())
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, two, 1900, outline = outline()))
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
        // After losing the cube (undetected for LOST_MS), the next episode's first frame holds nothing either.
        scan.onFrame(false, List(3) { null }, 100)
        scan.onFrame(false, List(3) { null }, 100 + CornerHold.LOST_MS)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, dark, 500, settled = true))
        assertFalse(scan.lightHeld)
        // Once metering on the cube has settled, a failing frame starts the hold and its coaching timer.
        scan.shake(dark, 600)
        assertTrue(scan.lightHeld)
        assertEquals(600L, scan.lightHeldSince)
        scan.shake(dark, 1599)
        assertEquals(null, scan.coaching)
        scan.shake(dark, 1600)
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
        // A torch toggle is a metering change: while it is pending or settling a failing verdict holds nothing. The
        // toggle is to off: with the torch lit the check is skipped altogether (PO 2026-11-21).
        scan.syncTorch(requested = true, applied = true, nowMs = 250)
        scan.syncTorch(requested = false, applied = true, nowMs = 300)
        scan.onFrame(true, dark, 350, settled = false)
        assertFalse(scan.lightHeld)
        scan.syncTorch(requested = false, applied = false, nowMs = 400)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 450, settled = false))
        scan.onFrame(true, dark, 500, settled = false)
        assertFalse(scan.lightHeld)
        // Once settled, a failing frame starts the hold.
        scan.onFrame(true, dark, 600, settled = true)
        assertTrue(scan.lightHeld)
        assertEquals(600L, scan.lightHeldSince)
    }

    @Test
    fun `a pass shorter than half a second keeps the line, and a moving pass clears it without a re-meter`() {
        val scan = lightScan()
        val first = hold(1)
        val dark = darkSides(first)
        scan.onFrame(true, dark, 0, settled = false)
        scan.shake(dark, 100)
        scan.shake(dark, 1100)
        scan.shake(first, 1200)
        scan.shake(first, 1500)
        assertTrue(scan.shake(dark, 1600).isEmpty())
        assertTrue(scan.lightHeld)
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        // Passing but moving: the outline moving never restarts the pass window (device session 2026-11-21); a failing
        // verdict does, and since Story 10.21 two cells of one face changing colour too. Story 10.22: the clear re-meters nothing.
        scan.shake(first, 1700)
        scan.shake(first, 2000)
        assertTrue(scan.shake(first, 2199).isEmpty())
        assertTrue(scan.lightHeld)
        assertTrue(scan.shake(first, 2200).isEmpty())
        assertFalse(scan.lightHeld)
        assertEquals(null, scan.coaching)
    }

    @Test
    fun `an unreadable face while failing neither advances nor resets the pass window`() {
        val scan = lightScan()
        val first = hold(1)
        val dark = darkSides(first)
        val unreadable = listOf(first[0], first[1].copy(cells = List(9) { null }), first[2])
        scan.onFrame(true, dark, 0, settled = false)
        scan.shake(dark, 100)
        assertTrue(scan.lightHeld)
        scan.shake(first, 1200)
        scan.shake(first, 1400)
        scan.shake(unreadable, 1500)
        assertEquals(null, scan.light!!.score, "an unreadable face has no score")
        assertTrue(scan.light!!.passed, "a verdict without a score passes, but must not clear the fail")
        // Unreadable frames alone never clear it, however long they last.
        listOf(2100L, 3100L, 4100L).forEach { now -> scan.shake(unreadable, now) }
        assertTrue(scan.lightHeld)
        // 200 ms passed before the unreadable frames (kept, not reset); the time across them does not count.
        listOf(4200L, 4400L, 4499L).forEach { now ->
            assertTrue(scan.shake(first, now).isEmpty())
            assertTrue(scan.lightHeld, "still failing at $now")
        }
        assertTrue(scan.shake(first, 4500).isEmpty(), "the clear re-meters nothing")
        assertFalse(scan.lightHeld)
    }

    @Test
    fun `one-frame detection flicker while failing neither resets nor advances the pass window`() {
        // The S24 FE session: the cube was lost for one frame every 100-800 ms and a light hold lasted 256 s.
        val scan = lightScan()
        val first = hold(1)
        val dark = darkSides(first)
        scan.onFrame(true, dark, 0, settled = false)
        scan.shake(dark, 100)
        assertTrue(scan.lightHeld)
        var clearedAt: Long? = null
        var now = 200L
        while (clearedAt == null && now < 5_000L) {
            val detected = (now / 33) % 5 != 0L
            val effects = if (detected) scan.shake(first, now) else scan.onFrame(false, List(3) { null }, now, settled = true)
            assertTrue(effects.isEmpty(), "neither a re-meter nor a capture at $now")
            if (!scan.lightHeld) clearedAt = now
            now += 33
        }
        val cleared = checkNotNull(clearedAt) { "the light fail cleared despite the detection flicker" }
        assertTrue(cleared <= 200 + 2 * CornerHold.LIGHT_CLEAR_MS, "light cleared at $cleared")
    }

    @Test
    fun `a detection gap shorter than LOST_MS neither re-meters nor restarts the steady window`() {
        val scan = lightScan()
        val first = hold(1)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 0, settled = false))
        assertTrue(scan.onFrame(true, first, 100, settled = true).isEmpty())
        // A gap of under LOST_MS: no re-meter when found again, and the steady window started at 100 runs on.
        assertTrue(scan.onFrame(false, List(3) { null }, 500).isEmpty())
        assertTrue(scan.onFrame(false, List(3) { null }, 500 + CornerHold.LOST_MS - 1).isEmpty())
        assertTrue(scan.onFrame(true, first, 900, settled = true).isEmpty(), "no re-meter after a short gap")
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, first, 1100, settled = true))

        // Undetected for LOST_MS: the cube is lost, the next detected frame re-meters.
        val other = lightScan()
        other.onFrame(true, first, 0, settled = false)
        other.onFrame(true, first, 100, settled = true)
        other.onFrame(false, List(3) { null }, 500)
        other.onFrame(false, List(3) { null }, 500 + CornerHold.LOST_MS)
        assertEquals(listOf(QuickScanEffect.Remeter), other.onFrame(true, first, 900, settled = false))
        assertTrue(other.onFrame(true, first, 1100, settled = true).isEmpty(), "the steady window restarted")
    }

    @Test
    fun `a backlit corner with the torch lit captures with no light hold and no sides-dark line`() {
        val scan = lightScan()
        val dark = darkSides(hold(1))
        scan.syncTorch(requested = true, applied = true, nowMs = 0)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, dark, 0, settled = false))
        assertTrue(scan.onFrame(true, dark, 100, settled = true).isEmpty())
        assertFalse(scan.light!!.passed, "the verdict is still measured for the diagnostics")
        assertFalse(scan.lightHeld)
        scan.onFrame(true, dark, 1099, settled = true)
        assertEquals(null, scan.coaching)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, dark, 1100, settled = true))
        assertEquals(null, scan.coaching, "no sides-dark line with the torch lit")
    }

    @Test
    fun `turning the torch on during a light hold clears it at once, and turning it off restores the check`() {
        val scan = lightScan()
        val first = hold(1)
        val dark = darkSides(first)
        scan.onFrame(true, dark, 0, settled = false)
        scan.shake(dark, 100)
        scan.shake(dark, 1100)
        assertTrue(scan.lightHeld)
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        scan.syncTorch(requested = true, applied = true, nowMs = 1200)
        assertFalse(scan.lightHeld, "the torch turning on clears the hold at once")
        assertEquals(null, scan.lightHeldSince)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, dark, 1250, settled = false))
        assertEquals(null, scan.coaching)
        scan.shake(dark, 1300)
        assertFalse(scan.lightHeld)
        // Torch off: a metering change, then the check holds again once settled.
        scan.syncTorch(requested = false, applied = false, nowMs = 1400)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, dark, 1450, settled = false))
        assertFalse(scan.lightHeld, "no hold while the torch-off metering settles")
        scan.shake(dark, 1500)
        assertTrue(scan.lightHeld)
        assertEquals(1500L, scan.lightHeldSince)
        scan.shake(dark, 2500)
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
    }

    @Test
    fun `a flickering verdict while held still coaches sides-dark one second after the first failing frame`() {
        val scan = lightScan()
        val first = hold(1)
        val dark = darkSides(first)
        scan.onFrame(true, dark, 0, settled = false)
        scan.shake(dark, 100)
        // Short passes (each under the 0.5 s clear window) never restart the one-second failing timer.
        listOf(300L to first, 500L to dark, 700L to first, 900L to dark, 1000L to first).forEach { (now, faces) ->
            scan.shake(faces, now)
            assertEquals(null, scan.coaching, "no line yet at $now")
        }
        scan.shake(dark, 1100)
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        assertTrue(scan.lightHeld)
    }

    @Test
    fun `losing the cube while failing clears the line at once but keeps the fail running`() {
        val scan = lightScan()
        val first = hold(1)
        val dark = darkSides(first)
        scan.onFrame(true, dark, 0, settled = false)
        scan.shake(dark, 100)
        scan.shake(dark, 1100)
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        assertTrue(scan.onFrame(false, List(3) { null }, 1200).isEmpty())
        assertEquals(null, scan.coaching)
        assertTrue(scan.lightHeld)
        scan.onFrame(false, List(3) { null }, 4199)
        assertEquals(null, scan.coaching)
        scan.onFrame(false, List(3) { null }, 4200)
        assertEquals(Coaching.PLAIN_LIGHT_SURFACE, scan.coaching)
        // Found again, still failing: the one-second timer restarts.
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, dark, 4300, settled = false))
        assertEquals(null, scan.coaching)
        scan.shake(dark, 5299)
        assertEquals(null, scan.coaching)
        scan.shake(dark, 5300)
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        // Found again and passing: the fail clears only after a pass window.
        scan.onFrame(false, List(3) { null }, 5400)
        scan.shake(first, 5500)
        scan.shake(first, 5600)
        assertTrue(scan.lightHeld)
        assertEquals(null, scan.coaching)
        scan.shake(first, 6000)
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
    fun `a manual press while settling fires once settled, in failing light too, unless the cube is lost first`() {
        val first = hold(1)
        val queued = lightScan()
        queued.onFrame(true, first, 0, settled = false)
        assertTrue(queued.onShutter().isEmpty())
        assertTrue(queued.onFrame(true, first, 100, settled = false).isEmpty())
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), queued.onFrame(true, first, 200, settled = true))
        assertEquals(2, queued.hold)
        assertTrue(queued.lastCapture!!.manual, "a queued press is a manual capture")

        val lost = lightScan()
        lost.onFrame(true, first, 0, settled = false)
        assertTrue(lost.onShutter().isEmpty())
        // Lost only once undetected for LOST_MS (a shorter gap keeps the press).
        lost.onFrame(false, List(3) { null }, 100)
        lost.onFrame(false, List(3) { null }, 100 + CornerHold.LOST_MS)
        assertEquals(listOf(QuickScanEffect.Remeter), lost.onFrame(true, first, 500, settled = false))
        assertTrue(lost.onFrame(true, first, 600, settled = true).isEmpty(), "the dropped press never fires")
        assertEquals(1, lost.hold)

        // Story 10.22: the light check failing on the settle frame no longer drops the queued press.
        val failing = lightScan()
        failing.onFrame(true, first, 0, settled = false)
        assertTrue(failing.onShutter().isEmpty())
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), failing.onFrame(true, darkSides(first), 100, settled = true))
        assertTrue(failing.lastCapture!!.manual)
        assertEquals(false, failing.lastCapture!!.lightPassed)

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
        assertEquals(listOf(QuickScanEffect.Remeter), confirmTurn(scan, hold(2), 1200, settled = false))
        scan.onFrame(true, hold(2), 1300, settled = true)
        val effects = scan.onFrame(true, hold(2), 2300, settled = true)
        assertEquals(QuickScanEffect.CornerCaptured(2), effects.first())
        assertTrue(effects.last() is QuickScanEffect.Done)
    }

    @Test
    fun `the light fail starts afresh on hold 2 after a front-lit hold 1`() {
        val scan = lightScan()
        val first = hold(1)
        scan.onFrame(true, first, 0, settled = false)
        scan.onFrame(true, first, 100, settled = true)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, first, 1100, settled = true))
        assertFalse(scan.lightHeld)
        val dark = darkSides(hold(2))
        assertEquals(listOf(QuickScanEffect.Remeter), confirmTurn(scan, dark, 1200, settled = false))
        assertFalse(scan.lightHeld, "hold 2's first frame re-meters onto the cube and holds nothing")
        scan.shake(dark, 1300)
        assertTrue(scan.lightHeld)
        assertEquals(1300L, scan.lightHeldSince)
        scan.shake(dark, 2299)
        assertEquals(null, scan.coaching, "the one-second timer starts with this hold")
        assertTrue(scan.shake(dark, 2300).isEmpty())
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        assertEquals(2, scan.hold)
        // Scan face by face clears the hold with the hand-over.
        scan.switchToFaceByFace()
        assertFalse(scan.lightHeld)
    }

    @Test
    fun `a saved calibration whose centres disagree is ignored, never confidently wrong`() {
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
        // Story 10.21: the swapped Red and Orange centres fail the centre check, so R is read as with no calibration.
        assertEquals(CalibrationMatch.Mismatch(CalibrationMatch.Reason.CENTRE), QuickScanClassification.matchFor(Face.R, samples, swapped))
        assertEquals(QuickScanClassification.read(r, samples, null), readings)
        assertEquals(
            QuickScanClassification.needGrid(samples, Face.entries, null),
            QuickScanClassification.needGrid(samples, Face.entries, swapped),
        )
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

    @Test
    fun `hold 2 does not capture while the hold-1 centres are shown and captures once they change`() {
        val scan = QuickScanController(saved, lightCheck = false)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, hold(1), 0))
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onShutter())
        assertEquals(1, scan.lastCapture?.hold)
        assertEquals(QuickScanPlan.holds[0].map { it.face }, scan.lastCapture?.faces?.map { it.face })
        assertTrue(scan.lastCapture!!.faces.none { it.grid }, "${scan.lastCapture}")
        // Still the first corner, its top a little darker (PO 2026-11-21): the pose did not change.
        val still = hold(1).toMutableList().also { it[0] = it[0].withCell(FaceSample.CENTER_INDEX, Lab(86.0, 0.0, 0.0)) }
        for (now in 100L..4000L step 100L) {
            assertTrue(scan.onFrame(true, still, now).none { it is QuickScanEffect.CornerCaptured }, "at $now")
        }
        val block = checkNotNull(scan.centerBlock)
        assertEquals(2, block.hold)
        assertEquals(listOf(4.0, 0.0, 0.0), block.deltaE)
        assertTrue(scan.onShutter().isEmpty())
        assertEquals(2, scan.hold)
        assertEquals(listOf(QuickScanEffect.Remeter), confirmTurn(scan, hold(2), 5000))
        assertEquals(null, scan.centerBlock)
        assertTrue(scan.onFrame(true, hold(2), 5600).isEmpty())
        assertEquals(QuickScanEffect.CornerCaptured(2), scan.onFrame(true, hold(2), 6600).first())
        assertEquals(2, scan.lastCapture?.hold)
    }

    @Test
    fun `hold 2 without a saved calibration is blocked by the hold-1 centres it already captured`() {
        val scan = QuickScanController(lightCheck = false)
        scan.onFrame(true, hold(1), 0)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onShutter())
        val still = hold(1).toMutableList().also { it[0] = it[0].withCell(FaceSample.CENTER_INDEX, Lab(86.0, 0.0, 0.0)) }
        for (now in 100L..3000L step 100L) assertTrue(scan.onFrame(true, still, now).isEmpty(), "at $now")
        assertEquals(listOf(4.0, 0.0, 0.0), scan.centerBlock?.deltaE)
        assertEquals(listOf(QuickScanEffect.Remeter), confirmTurn(scan, hold(2), 4000))
        assertTrue(scan.onFrame(true, hold(2), 4600).isEmpty())
        assertEquals(QuickScanEffect.CornerCaptured(2), scan.onFrame(true, hold(2), 5600).first())
    }

    @Test
    fun `a calibration from another cube does not block hold 2 once the cube was turned`() {
        // The Lenovo session (PO 2026-11-21): a pastel cube against a GAN calibration, hold 2's pale blue centre reads WHITE.
        val paleBlue = Lab(85.0, -2.0, -8.0)
        val scan = QuickScanController(saved, lightCheck = false)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, hold(1), 0))
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onShutter())
        val turned = hold(2).toMutableList().also { it[2] = it[2].withCell(FaceSample.CENTER_INDEX, paleBlue) }
        assertEquals(Color.WHITE, StickerClassifier.read(paleBlue, saved.centers).color)
        assertEquals(listOf(QuickScanEffect.Remeter), confirmTurn(scan, turned, 1000))
        assertEquals(null, scan.centerBlock)
        assertTrue(scan.onFrame(true, turned, 1600).isEmpty())
        assertEquals(QuickScanEffect.CornerCaptured(2), scan.onFrame(true, turned, 2600).first())
    }

    @Test
    fun `hold 1 is never blocked by the centre check`() {
        val scan = QuickScanController(saved, lightCheck = false)
        val wrong = hold(1).toMutableList().also { it[0] = it[0].withCell(FaceSample.CENTER_INDEX, labs.getValue(Color.YELLOW)) }
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, wrong, 0))
        assertTrue(scan.onFrame(true, wrong, 600).isEmpty())
        assertEquals(null, scan.centerBlock)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, wrong, 1600))
    }

    @Test
    fun `the outline leaving the metered region re-meters once, and tremor does not`() {
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1)
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 0, settled = false, outline = outline()))
        assertTrue(scan.onFrame(true, first, 100, settled = true, outline = outline()).isEmpty())
        // Below the bound (0.30 of the 500 px diagonal): the steady window restarts, the exposure lock stays.
        assertTrue(scan.onFrame(true, first, 200, settled = true, outline = outline(dx = 140.0)).isEmpty())
        assertEquals(CornerSteadyRestart.Reason.OUTLINE, scan.steadyRestart?.reason)
        // Beyond it the cube left the region the exposure was metered on: one re-meter.
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 300, settled = true, outline = outline(dx = 160.0)))
        assertTrue(scan.settling)
        assertTrue(scan.onFrame(true, first, 400, settled = false, outline = outline(dx = 160.0)).isEmpty())
        assertTrue(scan.onFrame(true, first, 500, settled = true, outline = outline(dx = 160.0)).isEmpty())
        assertTrue(scan.onFrame(true, first, 1499, settled = true, outline = outline(dx = 160.0)).isEmpty())
        assertEquals(
            listOf(QuickScanEffect.CornerCaptured(1)),
            scan.onFrame(true, first, 1500, settled = true, outline = outline(dx = 160.0)),
        )
        val meters = scan.drainEvents().filter { it.kind == CornerHoldEventKind.METERING_REQUESTED }
        assertEquals(listOf("first", "outline"), meters.map { it.reason })
        assertEquals(0.32, meters.last().amount!!, 1e-9)
    }

    @Test
    fun `the faces' brightness changing beyond the bound since metering re-meters once`() {
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1)

        fun lit(luma: Double) = first.map { it.copy(meanLuma = luma) }
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, lit(120.0), 0, settled = false, outline = outline()))
        // The baseline is the frame the metering settled on, not the frame that asked for it.
        assertTrue(scan.onFrame(true, lit(100.0), 100, settled = true, outline = outline()).isEmpty())
        assertTrue(scan.onFrame(true, lit(120.0), 200, settled = true, outline = outline()).isEmpty(), "20% is within the bound")
        assertTrue(scan.onFrame(true, lit(80.0), 300, settled = true, outline = outline()).isEmpty())
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, lit(126.0), 400, settled = true, outline = outline()))
        assertTrue(scan.onFrame(true, lit(126.0), 500, settled = true, outline = outline()).isEmpty())
        assertTrue(scan.onFrame(true, lit(126.0), 1499, settled = true, outline = outline()).isEmpty(), "no second re-meter")
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, lit(126.0), 1500, settled = true, outline = outline()))
        val meters = scan.drainEvents().filter { it.kind == CornerHoldEventKind.METERING_REQUESTED }
        assertEquals(listOf("first", "brightness"), meters.map { it.reason })
        assertEquals(0.26, meters.last().amount!!, 1e-9)
    }

    @Test
    fun `a lost cube re-meters on the next detection and its undetected time is counted in the capture report`() {
        val scan = QuickScanController(lightCheck = false)
        val first = hold(1)
        val none = List<FrameStats?>(3) { null }
        scan.viewEntered(1000)
        assertTrue(scan.onFrame(false, none, 1200).isEmpty())
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 1500, settled = false, outline = outline()))
        assertTrue(scan.onFrame(true, first, 1600, settled = true, outline = outline()).isEmpty())
        assertTrue(scan.onFrame(false, none, 1700).isEmpty())
        assertTrue(scan.onFrame(false, none, 1700 + CornerHold.LOST_MS).isEmpty())
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, first, 2100, settled = false, outline = outline()))
        assertTrue(scan.onFrame(true, first, 2200, settled = true, outline = outline()).isEmpty(), "settle and steady restart")
        assertTrue(scan.onFrame(true, first, 3199, settled = true, outline = outline()).isEmpty())
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, first, 3200, settled = true, outline = outline()))
        val report = checkNotNull(scan.lastCapture)
        assertEquals(2200L, report.totalMs, "since the viewfinder opened")
        // Undetected: 1000–1200 before the first frame, then 1600–2000 around the loss.
        assertEquals(200L + 400L, report.undetectedMs)
        assertEquals(1700L, report.sinceDetectedMs)
        assertFalse(report.manual)
        assertEquals(null, report.lightPassed, "the light check is off in this controller")
    }

    @Test
    fun `a press with no time before any frame is taken at the hold's start, never before it`() {
        val scan = QuickScanController(lightCheck = false)
        scan.viewEntered(1000)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onShutter())
        assertEquals(0L, scan.lastCapture!!.totalMs)
        assertEquals(listOf(0L, 0L), scan.drainEvents().filter { it.hold == 1 }.map { it.elapsedMs })
        // Hold 2 began with that capture; the last frame's time is older and must not be used.
        assertTrue(scan.onShutter().isNotEmpty())
        assertEquals(0L, scan.lastCapture!!.totalMs)
    }

    @Test
    fun `every state change of a hold is recorded once with its time since the hold began`() {
        val scan = lightScan()
        val first = hold(1)
        val dark = darkSides(first)
        val none = List<FrameStats?>(3) { null }
        scan.viewEntered(1000)
        scan.onFrame(false, none, 1100)
        scan.onFrame(true, first, 1200, settled = false, outline = outline())
        scan.onFrame(true, first, 1300, settled = true, outline = outline())
        scan.onFrame(true, first, 1400, settled = true, outline = outline(dx = 60.0))
        scan.onFrame(false, none, 1500)
        scan.onFrame(false, none, 1500 + CornerHold.LOST_MS)
        scan.onFrame(true, first, 1900, settled = false, outline = outline())
        scan.onFrame(true, first, 2000, settled = false, outline = outline())
        // The settle signal never comes: the cap releases the wait.
        scan.onFrame(true, dark, 1900 + CornerHold.SETTLE_CAP_MS, settled = false, outline = outline())
        scan.onFrame(true, first.map { it.copy(sharpness = 0.0) }, 3500, settled = false, outline = outline())
        scan.onFrame(true, first, 3600, settled = false, outline = outline())
        scan.onFrame(true, first, 4100, settled = false, outline = outline())
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, first, 4600, settled = false, outline = outline()))
        // Hold 2 begins with the capture: the first corner still in view is rejected, then the turn is confirmed.
        scan.onFrame(true, first, 5000, settled = false, outline = outline())
        scan.onFrame(true, first, 5100, settled = false, outline = outline())
        scan.onFrame(true, hold(2), 5200, settled = false, outline = outline())
        scan.onFrame(true, hold(2), 5300, settled = false, outline = outline())
        scan.onFrame(true, hold(2), 5400, settled = false, outline = outline())
        scan.onFrame(true, hold(2), 5500, settled = true, outline = outline())
        assertEquals(QuickScanEffect.CornerCaptured(2), scan.onShutter(5600).first())
        // Hold 2's "since detected" starts at the first frame that passed the pose and turn checks (5400), not at the
        // first corner still in view (5000).
        assertEquals(200L, scan.lastCapture!!.sinceDetectedMs)
        val lines =
            scan.drainEvents().map { event ->
                listOfNotNull(
                    "h${event.hold}",
                    "t${event.elapsedMs}",
                    event.kind.label,
                    event.reason,
                    event.timeout?.let { "timeout=$it" },
                    event.durationMs?.let { "ms=$it" },
                ).joinToString(" ")
            }
        assertEquals(
            listOf(
                "h1 t0 viewfinder",
                "h1 t100 firstFrame",
                "h1 t200 cubeFound",
                "h1 t200 meteringRequested first",
                "h1 t300 meteringSettled ms=100",
                "h1 t300 steadyStart",
                "h1 t400 steadyRestart outline",
                "h1 t800 cubeLost",
                "h1 t900 cubeFound",
                "h1 t900 meteringRequested lost",
                "h1 t2400 meteringSettled timeout=true ms=1500",
                "h1 t2400 lightFail",
                "h1 t2400 steadyStart",
                "h1 t2500 steadyRestart quality",
                "h1 t2600 steadyStart",
                "h1 t3100 lightClear ms=700",
                "h1 t3600 capture auto",
                "h2 t0 viewfinder",
                "h2 t400 firstFrame",
                "h2 t400 cubeFound",
                "h2 t400 poseRejected centres",
                "h2 t600 poseRejected turn",
                "h2 t800 poseAccepted",
                "h2 t800 meteringRequested first",
                "h2 t900 meteringSettled ms=100",
                "h2 t900 steadyStart",
                "h2 t1000 capture manual",
            ),
            lines,
        )
        assertTrue(scan.drainEvents().isEmpty(), "each change is reported once")
    }

    @Test
    fun `after a back-out the resumed hold starts clean and the corner in view captures again`() {
        // Hold 1 captured, the Learner switches to face by face before turning the cube, then backs out.
        val scan = QuickScanController(saved, lightCheck = false)
        scan.onFrame(true, hold(1), 0)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onShutter(50))
        scan.onFrame(true, hold(1), 100)
        checkNotNull(scan.switchToFaceByFace())
        assertTrue(scan.backOut())
        assertEquals(2, scan.hold)
        // Story 10.22: no stale turn-over state: the second corner re-meters on its first frame and captures on its own.
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, hold(2), 1000, settled = false, outline = outline()))
        assertTrue(scan.onFrame(true, hold(2), 1100, settled = true, outline = outline()).isEmpty())
        val effects = scan.onFrame(true, hold(2), 2100, settled = true, outline = outline())
        assertEquals(QuickScanEffect.CornerCaptured(2), effects.first())
        assertEquals(1100L, scan.lastCapture!!.totalMs, "the resumed hold's time starts with its first frame")

        // Both corners captured, back from the hand-over: hold 2 resumes and the same corner captures again, although
        // it shows the centres hold 2 captured before.
        val again = QuickScanController(lightCheck = false)
        val uncertain = hold(2).toMutableList().also { it[2] = it[2].withCell(0, Lab(55.0, 55.0, 55.0)) }
        assertEquals(QuickScanEffect.Fallback(listOf(Face.B)), capture(again, hold(1), uncertain))
        assertTrue(again.backOut())
        assertEquals(listOf(QuickScanEffect.Remeter), again.onFrame(true, hold(2), 1000, settled = false, outline = outline()))
        again.onFrame(true, hold(2), 1100, settled = true, outline = outline())
        assertEquals(QuickScanEffect.CornerCaptured(2), again.onFrame(true, hold(2), 2100, settled = true, outline = outline()).first())
        // The first corner shown again after a back-out is still not taken as the second.
        val same = QuickScanController(lightCheck = false)
        same.onFrame(true, hold(1), 0)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), same.onShutter(50))
        checkNotNull(same.switchToFaceByFace())
        assertTrue(same.backOut())
        for (now in 1000L..3000L step 100) assertTrue(same.onFrame(true, hold(1), now, settled = true, outline = outline()).isEmpty())
        assertTrue(same.onShutter(3050).isEmpty())
    }

    // Story 10.21: the calibration match, the 1-blank keep rule, the confident-count wait, lit-vs-requested torch,
    // the light hysteresis and the return from calibration.

    @Test
    fun `a matching calibration keeps a corner face with one uncertain sticker and Done carries it as a blank`() {
        val solved = CubeState.SOLVED
        val first = hold(1, solved).toMutableList().also { it[2] = it[2].withCell(0, Lab(55.0, 55.0, 55.0)) }
        val scan = QuickScanController(saved, lightCheck = false)
        val done = capture(scan, first, hold(2, solved)) as QuickScanEffect.Done
        assertEquals(mapOf(Face.R to setOf(0)), done.blanks)
        assertEquals(saved, done.calibration, "review reads with the matched calibration")
        assertEquals(CalibrationMatch.Match, scan.lastCapture?.calibration)
        // The review leaves it blank and pre-selects it.
        val review = ScanReview.fromSamples(done.samples, saved, done.blanks)
        assertEquals(1, review.stickers.count { it == null })
        assertEquals(review.stickers.indexOf(null), review.selected)

        // Saved with the torch lit but captured with it off: the all-9 rule, so R goes to the grid.
        val torch = QuickScanController(saved.copy(torchOn = true), lightCheck = false)
        assertEquals(QuickScanEffect.Fallback(listOf(Face.R)), capture(torch, first, hold(2, solved)))
        assertEquals(CalibrationMatch.Mismatch(CalibrationMatch.Reason.TORCH), torch.lastCapture?.calibration)
        // No calibration at all: the all-9 rule too.
        assertEquals(QuickScanEffect.Fallback(listOf(Face.R)), capture(QuickScanController(lightCheck = false), first, hold(2, solved)))
        // A mismatched calibration is not what review reads with: as when none exists.
        val clean = capture(QuickScanController(saved.copy(torchOn = true), lightCheck = false), hold(1, solved), hold(2, solved))
        assertEquals(null, (clean as QuickScanEffect.Done).calibration)
    }

    @Test
    fun `a kept face's blank survives the hand-over and is carried by Done after the grid`() {
        val solved = CubeState.SOLVED
        val uncertain = Lab(55.0, 55.0, 55.0)
        // R keeps one uncertain sticker (kept, blank 0); F has two (to the grid).
        val first =
            hold(1, solved).toMutableList().also {
                it[2] = it[2].withCell(0, uncertain)
                it[1] = it[1].withCell(0, uncertain).withCell(2, uncertain)
            }
        val scan = QuickScanController(saved, lightCheck = false)
        assertEquals(QuickScanEffect.Fallback(listOf(Face.F)), capture(scan, first, hold(2, solved)))
        assertTrue(Face.R in scan.keptFaces)
        val done = guidedCapture(scan, face(Face.F, solved)) as QuickScanEffect.Done
        assertEquals(mapOf(Face.R to setOf(0)), done.blanks)
        assertEquals(saved, done.calibration)
    }

    /** Hold 1 with every non-centre sticker a close call between its centre colour and a neighbour: 3 of 27 CONFIDENT. */
    private fun fewConfident(): List<FrameStats> {
        val between = listOf(Lab(87.5, -2.5, 40.0), Lab(70.0, -32.5, 60.0), Lab(55.0, 55.0, 55.0))
        return hold(1).mapIndexed { face, stats ->
            stats.copy(
                cells =
                    stats.cells.mapIndexed { i, cell ->
                        if (i ==
                            FaceSample.CENTER_INDEX
                        ) {
                            cell
                        } else {
                            CellReading(between[face], true)
                        }
                    },
            )
        }
    }

    @Test
    fun `too few confident stickers hold auto capture with the prompt shown, log the count, and the shutter still captures`() {
        val scan = QuickScanController(saved, lightCheck = false)
        val few = fewConfident()
        for (now in 0L..3000L step 100) assertTrue(scan.onFrame(true, few, now).none { it is QuickScanEffect.CornerCaptured }, "at $now")
        assertTrue(scan.fewConfident)
        assertEquals(null, scan.coaching, "no coaching line of its own: the hold's prompt shows")
        val rejected = scan.drainEvents().filter { it.kind == CornerHoldEventKind.POSE_REJECTED }
        assertEquals(listOf(QuickScanController.POSE_FEW_CONFIDENT), rejected.map { it.reason })
        assertEquals(3.0, rejected.single().amount)
        // Device-session diagnostics: the per-face counts sum to exactly the amount the wait compares with the need.
        assertEquals(mapOf(Face.U to 1, Face.F to 1, Face.R to 1), rejected.single().confident)
        assertEquals(
            QuickScanDiagnosis.ConfidentCount(1, mapOf(Face.U to 1, Face.F to 1, Face.R to 1), QuickScanController.MIN_CONFIDENT),
            scan.confidentCount,
        )
        assertEquals(3, scan.confidentCount!!.amount)
        assertEquals(listOf(1, 1, 1), scan.confidentDiagnoses().map { it.counted })
        assertTrue(QuickScanController.MIN_CONFIDENT > 3)
        // Enough stickers read: the steady window starts and the capture counts the wait in its time since detected.
        var captured: Long? = null
        for (now in 3100L..5000L step 100) {
            if (scan.onFrame(true, hold(1), now).any { it is QuickScanEffect.CornerCaptured }) {
                captured = now
                break
            }
        }
        assertEquals(3100L + CornerHold.STEADY_MS, captured)
        assertFalse(scan.fewConfident)
        assertTrue(scan.drainEvents().any { it.kind == CornerHoldEventKind.POSE_ACCEPTED })
        assertEquals(captured, scan.lastCapture?.sinceDetectedMs)
        assertFalse(scan.lastCapture!!.manual)

        // The manual shutter never waits for it.
        val manual = QuickScanController(saved, lightCheck = false)
        manual.onFrame(true, few, 0)
        manual.onFrame(true, few, 600)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), manual.onShutter(700))
        assertTrue(manual.lastCapture!!.manual)
    }

    @Test
    fun `with no calibration a logo top and a second unplain centre leave hold 1 nothing to read against`() {
        // Story 10.21 device session (S24 FE, Rubik's logo top, torch lit): the logo White centre is guarded and the
        // Green centre is not plain either, so only Red is a trusted centre: fewer than two, every read is null.
        val first =
            hold(1, CubeState.SOLVED).mapIndexed { index, stats ->
                if (index == 2) {
                    stats
                } else {
                    stats.copy(
                        cells =
                            stats.cells.mapIndexed { i, cell ->
                                if (i ==
                                    FaceSample.CENTER_INDEX
                                ) {
                                    cell!!.copy(plain = false)
                                } else {
                                    cell
                                }
                            },
                    )
                }
            }
        val scan = QuickScanController(lightCheck = false)
        for (now in 0L..2000L step 100) scan.onFrame(true, first, now)
        assertTrue(scan.fewConfident)
        assertEquals(0, scan.confidentCount!!.amount)
        val faces = scan.confidentDiagnoses()
        assertEquals(listOf(Face.U, Face.F, Face.R), faces.map { it.face })
        assertTrue(faces.all { it.reference == QuickScanDiagnosis.Reference.NONE && it.referenceColors == setOf(Color.RED) })
        assertEquals(
            listOf(
                QuickScanDiagnosis.CentreIssue.GUARDED,
                QuickScanDiagnosis.CentreIssue.GUARDED,
                QuickScanDiagnosis.CentreIssue.NO_REFERENCE,
            ),
            faces.map { it.centreIssue },
        )
        assertEquals(setOf(Color.WHITE, Color.GREEN), faces.first().guarded)
        assertTrue(faces.all { face -> face.stickers.all { it.color == null } && face.counted == 0 })
        assertEquals(
            listOf(
                QuickScanDiagnosis.GridReason.UNCALIBRATED,
                QuickScanDiagnosis.GridReason.UNCALIBRATED,
                QuickScanDiagnosis.GridReason.CENTRE_UNCERTAIN,
            ),
            faces.map { it.gridReason },
        )
        assertEquals(listOf(false, false, true), faces.map { it.stickers[FaceSample.CENTER_INDEX].judged })
    }

    @Test
    fun `a capture's diagnoses name the reference, every sticker's reads and the decision`() {
        val solved = CubeState.SOLVED
        val scan = QuickScanController(lightCheck = false)
        assertTrue(scan.captureDiagnoses().isEmpty())
        scan.onFrame(true, hold(1, solved), 0)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onShutter())
        val faces = scan.captureDiagnoses()
        assertEquals(listOf(Face.U, Face.F, Face.R), faces.map { it.face })
        faces.forEach { face ->
            assertEquals(QuickScanDiagnosis.Reference.PARTIAL, face.reference)
            assertEquals(setOf(Color.WHITE, Color.GREEN, Color.RED), face.referenceColors)
            assertEquals(setOf(Color.YELLOW, Color.ORANGE, Color.BLUE), face.unresolved)
            assertEquals(null, face.calibration)
            assertEquals(null, face.centreIssue)
            assertEquals(9, face.counted)
            assertEquals(9, face.confident)
            assertEquals(null, face.gridReason)
            assertTrue(face.stickers.all { it.confident && it.judged && it.color == it.secondColor && it.firstMargin!! >= 10.0 })
        }
        // A matching saved calibration is the reference.
        val matched = QuickScanController(saved, lightCheck = false)
        matched.onFrame(true, hold(1, solved), 0)
        matched.onShutter()
        assertTrue(
            matched.captureDiagnoses().all {
                it.reference == QuickScanDiagnosis.Reference.SAVED &&
                    it.calibration == CalibrationMatch.Match
            },
        )
        // A blurred face goes to the grid for its quality.
        val blurred = hold(1, solved).toMutableList().also { it[1] = it[1].copy(sharpness = 0.0) }
        val poor = QuickScanController(lightCheck = false)
        poor.onFrame(true, blurred, 0)
        poor.onShutter()
        assertEquals(QuickScanDiagnosis.GridReason.QUALITY, poor.captureDiagnoses()[1].gridReason)
    }

    @Test
    fun `the hand-over report names the kept faces, the grid faces with why and the blanks`() {
        val solved = CubeState.SOLVED
        val uncertain = Lab(55.0, 55.0, 55.0)
        val first =
            hold(1, solved).toMutableList().also {
                it[2] = it[2].withCell(0, uncertain)
                it[1] = it[1].withCell(0, uncertain).withCell(2, uncertain)
            }
        val scan = QuickScanController(saved, lightCheck = false)
        assertEquals(null, scan.lastHandOver)
        assertEquals(QuickScanEffect.Fallback(listOf(Face.F)), capture(scan, first, hold(2, solved)))
        assertEquals(
            QuickScanDiagnosis.HandOverReport(
                QuickScanDiagnosis.HandOverTrigger.AUTO,
                kept = listOf(Face.U, Face.R, Face.D, Face.L, Face.B),
                grid = mapOf(Face.F to QuickScanDiagnosis.GridReason.UNCERTAIN),
                blanks = mapOf(Face.R to setOf(0)),
                done = false,
                calibration = CalibrationMatch.Match,
            ),
            scan.lastHandOver,
        )
        // The face the hand-over captures reads against the matched calibration.
        val read =
            scan.handOverRead(
                FaceSample(
                    face = Face.F,
                    orientationDegrees = 0,
                    stickers =
                        List(9) {
                            labs.getValue(Color.GREEN)
                        },
                    quality = CaptureQuality.ACCEPTABLE,
                ),
            )
        assertEquals(QuickScanDiagnosis.Reference.SAVED, read.reference)
        assertEquals(List(9) { StickerReading(Color.GREEN, StickerConfidence.CONFIDENT) }, read.readings)

        // Face by face after hold 1: the faces never captured are missing.
        val switched = QuickScanController(lightCheck = false)
        val pale = hold(1, solved).toMutableList().also { it[2] = it[2].withCell(0, Lab(67.5, 32.5, 22.5)) }
        switched.onFrame(true, pale, 0)
        switched.onShutter()
        switched.switchToFaceByFace()
        val report = checkNotNull(switched.lastHandOver)
        assertEquals(QuickScanDiagnosis.HandOverTrigger.FACE_BY_FACE, report.trigger)
        assertEquals(listOf(Face.U, Face.F), report.kept)
        assertEquals(QuickScanDiagnosis.GridReason.UNCERTAIN, report.grid[Face.R])
        assertEquals(listOf(Face.D, Face.L, Face.B).associateWith { QuickScanDiagnosis.GridReason.MISSING }, report.grid - Face.R)
        assertEquals(null, report.calibration)
        // Without a saved calibration, a hand-over face reads against the kept faces' own centres.
        val alone =
            switched.handOverRead(
                FaceSample(
                    face = Face.D,
                    orientationDegrees = 0,
                    stickers =
                        List(9) {
                            labs.getValue(Color.YELLOW)
                        },
                    quality = CaptureQuality.ACCEPTABLE,
                ),
            )
        assertEquals(QuickScanDiagnosis.Reference.PARTIAL, alone.reference)
        // A clean scan goes straight to review.
        val clean = QuickScanController(lightCheck = false)
        assertTrue(capture(clean, hold(1, solved), hold(2, solved)) is QuickScanEffect.Done)
        assertTrue(clean.lastHandOver!!.done)
        assertEquals(Face.entries.toList(), clean.lastHandOver!!.kept)
        assertTrue(clean.lastHandOver!!.grid.isEmpty())
    }

    @Test
    fun `a guided read with fewer than two centres reads nothing`() {
        val sample =
            FaceSample(
                face = Face.U,
                orientationDegrees = 90,
                stickers =
                    List(9) {
                        labs.getValue(Color.WHITE)
                    },
                quality = CaptureQuality.ACCEPTABLE,
                centerColor = Color.WHITE,
            )
        val read = QuickScanDiagnosis.guidedRead(sample, listOf(sample), null)
        assertEquals(QuickScanDiagnosis.Reference.NONE, read.reference)
        assertEquals(90, read.orientationDegrees)
        assertTrue(read.readings.all { it == null })
        val all =
            Face.entries.map { face ->
                FaceSample(
                    face = face,
                    orientationDegrees = 0,
                    stickers =
                        List(9) {
                            labs.getValue(Color.home(face))
                        },
                    quality = CaptureQuality.ACCEPTABLE,
                    centerColor = Color.home(face),
                )
            }
        assertEquals(QuickScanDiagnosis.Reference.SELF, QuickScanDiagnosis.guidedRead(all[0], all, null).reference)
    }

    @Test
    fun `a torch asked for but not lit runs the light check and matches only torch-off calibrations`() {
        // Dark enough to fail the calibrated check, the side centres still within the centre closeness of the calibration.
        val dark = darkSides(hold(1), brightness = 0.73)
        val scan = QuickScanController(saved.copy(torchOn = true))
        scan.syncTorch(requested = true, applied = false, nowMs = 0)
        assertTrue(scan.torchOn)
        assertFalse(scan.torchLit)
        scan.onFrame(true, dark, 0, settled = false)
        // The torch-on calibration does not match the unlit torch: the check runs uncalibrated, as with none.
        assertEquals(SideLight.Mode.UNCALIBRATED, scan.light?.mode)
        // Once the camera applied it, the torch is lit: the calibration matches and the check is skipped.
        scan.syncTorch(requested = true, applied = true, nowMs = 50)
        assertTrue(scan.torchLit)
        scan.onFrame(true, dark, 100, settled = false)
        assertEquals(SideLight.Mode.CALIBRATED, scan.light?.mode)
        for (now in 200L..2000L step 100) scan.shake(dark, now)
        assertFalse(scan.lightHeld)
    }

    @Test
    fun `only a physically lit torch clears a running light fail`() {
        val scan = lightScan()
        val dark = darkSides(hold(1))
        scan.onFrame(true, dark, 0, settled = false)
        scan.shake(dark, 100)
        scan.shake(dark, 1100)
        assertTrue(scan.lightHeld)
        scan.syncTorch(requested = true, applied = false, nowMs = 1200)
        assertTrue(scan.lightHeld, "asked for, not lit: the fail runs on")
        scan.syncTorch(requested = true, applied = true, nowMs = 1300)
        assertFalse(scan.lightHeld)
    }

    @Test
    fun `a light score in the hysteresis band neither starts nor clears a light fail`() {
        val scan = lightScan()
        val first = hold(1)
        // Calibrated mode: the score is the top over the sides' brightness, so sides at 1 / 1.34 score 1.34.
        val band = darkSides(first, brightness = 1 / 1.34)
        scan.onFrame(true, band, 0, settled = false)
        for (now in 100L..2000L step 100) scan.shake(band, now)
        assertTrue(scan.light!!.inBand)
        assertFalse(scan.lightHeld, "below the hold cut: no fail starts")
        val dark = darkSides(first)
        scan.shake(dark, 2100)
        scan.shake(dark, 3100)
        assertTrue(scan.lightHeld)
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        for (now in 3200L..5000L step 100) scan.shake(band, now)
        assertTrue(scan.lightHeld, "above the clear cut: the fail never clears")
        assertEquals(Coaching.SIDES_DARK, scan.coaching)
        scan.shake(first, 5100)
        scan.shake(first, 5100 + CornerHold.LIGHT_CLEAR_MS)
        assertFalse(scan.lightHeld)
    }

    @Test
    fun `the light-clear window restarts when two cells of one face move, not for one`() {
        val scan = lightScan()
        val first = hold(1)
        val dark = darkSides(first)
        scan.onFrame(true, dark, 0, settled = false)
        scan.shake(dark, 100)
        scan.shake(dark, 1100)
        assertTrue(scan.lightHeld)
        val other = Lab(70.0, 0.0, 0.0)
        val oneMoved = first.toMutableList().also { it[1] = it[1].withCell(0, other) }
        val twoMoved = first.toMutableList().also { it[1] = it[1].withCell(0, other).withCell(1, other) }
        scan.shake(first, 1200)
        scan.shake(oneMoved, 1400)
        scan.shake(twoMoved, 1600)
        scan.shake(twoMoved, 1699)
        assertTrue(scan.lightHeld, "two cells moved at 1600: the window restarted there")
        scan.shake(twoMoved, 1600 + CornerHold.LIGHT_CLEAR_MS)
        assertFalse(scan.lightHeld)
    }

    @Test
    fun `returning from calibration clears the light hold and the offer needs a fresh five seconds`() {
        val bright = FrameStats(List(9) { CellReading(Lab(90.0, 0.0, 0.0), true) }, 120.0, 0.0, 100.0, 12)
        val dim = FrameStats(List(9) { CellReading(Lab(50.0, 0.0, 0.0), true) }, 120.0, 0.0, 100.0, 12)
        val dark = listOf(bright, dim, bright)
        // A saved calibration that does not match (saved with the torch lit) behaves as none: the offer comes.
        val scan = QuickScanController(saved.copy(torchOn = true))
        val offerAt = 100 + QuickScanController.CALIBRATION_OFFER_MS
        scan.onFrame(true, dark, 0, settled = false)
        for (now in 100L..offerAt step 100) scan.shake(dark, now)
        assertTrue(scan.calibrationOffered)
        scan.drainEvents()
        scan.returnFromCalibration(offerAt + 100)
        assertFalse(scan.lightHeld)
        assertFalse(scan.calibrationOffered)
        assertEquals(null, scan.coaching)
        assertEquals(
            listOf(QuickScanController.REASON_CALIBRATION),
            scan.drainEvents().filter { it.kind == CornerHoldEventKind.LIGHT_CLEAR }.map { it.reason },
        )
        scan.shake(dark, offerAt + 200)
        assertTrue(scan.lightHeld)
        scan.shake(dark, offerAt + 200 + QuickScanController.CALIBRATION_OFFER_MS - 1)
        assertFalse(scan.calibrationOffered)
        scan.shake(dark, offerAt + 200 + QuickScanController.CALIBRATION_OFFER_MS)
        assertTrue(scan.calibrationOffered)
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
