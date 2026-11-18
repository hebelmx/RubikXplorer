package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
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
    private val cube = CubeState.SOLVED.apply(listOf(FaceTurn(Face.R, Turn.CLOCKWISE), FaceTurn(Face.U, Turn.COUNTERCLOCKWISE)))

    private fun face(face: Face): FrameStats {
        val colors =
            cube
                .toFacelets()
                .colors
                .drop(face.ordinal * 9)
                .take(9)
        return FrameStats(colors.map { CellReading(labs.getValue(it), true) }, 120.0, 0.0, 100.0, 12)
    }

    private fun hold(index: Int): List<FrameStats> =
        QuickScanPlan.holds[index - 1].map { step ->
            val stats = face(step.face)
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
        assertTrue(scan.onFrame(true, first, 299).isEmpty())
        assertTrue(scan.onFrame(true, first, 300).isEmpty())
        val moved = first.toMutableList().also { it[0] = it[0].withCell(0, Lab(79.0, 0.0, 0.0)) }
        assertTrue(scan.onFrame(true, moved, 800).isEmpty())
        assertTrue(scan.onFrame(true, first, 899).isEmpty())
        assertTrue(scan.onFrame(true, first, 1498).isEmpty())
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onFrame(true, first, 1499))
        assertTrue(scan.onFrame(false, List(3) { null }, 2000).isEmpty())
        assertTrue(scan.onFrame(true, first, 3000).isEmpty())
        assertTrue(scan.onShutter().isEmpty())
        assertEquals(listOf(QuickScanEffect.Remeter), scan.onFrame(true, hold(2), 4000))
        assertTrue(scan.onFrame(true, hold(2), 4300).isEmpty())
        val effects = scan.onFrame(true, hold(2), 4900)
        assertEquals(QuickScanEffect.CornerCaptured(2), effects.first())
        val done = effects.last() as QuickScanEffect.Done
        assertEquals(6, done.samples.size)
        assertTrue(done.samples.all { it.orientationDegrees == 0 && it.upNeighborColor != null })
        assertEquals(cube.toFacelets(), assemble(done.samples).facelets)
        assertFalse(scan.inCorner)
        assertTrue(scan.onShutter().isEmpty())
    }

    @Test
    fun `shutter captures immediately and uncertain readings re-prompt only the affected face`() {
        val scan = QuickScanController()
        val first = hold(1).toMutableList().also { it[1] = it[1].withCell(0, Lab(55.0, 55.0, 55.0)) }
        assertEquals(QuickScanEffect.Fallback(listOf(Face.F), 1), capture(scan, first, hold(2)))
        val done = guidedCapture(scan, face(Face.F)) as QuickScanEffect.Done
        assertEquals(cube.toFacelets(), assemble(done.samples).facelets)
    }

    @Test
    fun `an undetected second corner coaches and the shutter sends its faces to the guided grid`() {
        val scan = QuickScanController()
        scan.onFrame(true, hold(1), 0)
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onShutter())
        val second = hold(2)
        assertTrue(scan.onFrame(false, List(3) { null }, 100).isEmpty())
        assertTrue(scan.onFrame(false, listOf(second[0].copy(meanLuma = 10.0), second[1], second[2]), 200).isEmpty())
        assertEquals(Coaching.LOW_LIGHT, scan.coaching)
        assertEquals(
            listOf(QuickScanEffect.CornerCaptured(2), QuickScanEffect.Fallback(listOf(Face.D, Face.L, Face.B), 1)),
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
        assertEquals(QuickScanEffect.Fallback(listOf(Face.U, Face.F), 1), capture(scan, input, hold(2)))
        assertEquals(Face.U, scan.fallback?.step?.face)
        assertEquals(null, guidedCapture(scan, face(Face.U)))
        val done = guidedCapture(scan, face(Face.F)) as QuickScanEffect.Done
        assertEquals(cube.toFacelets(), assemble(done.samples).facelets)
    }

    @Test
    fun `an invalid merged state asks involved faces once and then goes to review even if still invalid`() {
        val scan = QuickScanController()
        val first = hold(1)
        val invalidF = first[1].withCell(1, labs.getValue(Color.WHITE))
        assertEquals(QuickScanEffect.Fallback(listOf(Face.U), 1), capture(scan, listOf(null, invalidF, first[2]), hold(2)))
        val expectedSamples =
            scan.captured +
                FaceSample(
                    face = Face.U,
                    orientationDegrees = 0,
                    stickers = face(Face.U).cells.map { it!!.value },
                    quality = CaptureQuality.ACCEPTABLE,
                    centerColor = Color.WHITE,
                    upNeighborColor = Color.BLUE,
                )
        val calibration = (SelfCalibration.from(expectedSamples) as SelfCalibration.Calibrated).calibration
        val involved = ScanAssembler.repromptFaces(expectedSamples, calibration, repromptPosed = true)
        assertTrue(involved.isNotEmpty())
        assertEquals(QuickScanEffect.Fallback(involved, 2), guidedCapture(scan, face(Face.U)))
        val grid = checkNotNull(scan.fallback)
        assertTrue(grid.repeating)
        assertTrue(grid.onFrame(face(Face.U), 0, 1000).isEmpty())
        assertTrue(grid.onShutter(1000).isEmpty())
        val done = involved.map { guidedCapture(scan, if (it == Face.F) invalidF else face(it)) }.last() as QuickScanEffect.Done
        assertFalse(CubeValidator.validate(assemble(done.samples).facelets) is ValidationResult.Valid)
        assertEquals(null, scan.fallback)
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
        assertEquals(QuickScanEffect.Fallback(Face.entries.toList(), 1), scan.onShutter().last())
    }

    @Test
    fun `the shutter keeps a partial corner even when automatic detection does not arm`() {
        val scan = QuickScanController()
        assertTrue(scan.onFrame(false, listOf(hold(1)[0], null, null), 0).isEmpty())
        assertEquals(listOf(QuickScanEffect.CornerCaptured(1)), scan.onShutter())
        assertEquals(listOf(Face.U), scan.captured.map { it.face })
        scan.onFrame(true, hold(2), 100)
        assertEquals(QuickScanEffect.Fallback(listOf(Face.R, Face.F), 1), scan.onShutter().last())
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
    fun `a kept corner face with an uncertain sticker is asked once calibration becomes possible`() {
        val scan = QuickScanController()
        val first = hold(1)
        val uncertainF = first[1].withCell(0, Lab(55.0, 55.0, 55.0))
        assertEquals(QuickScanEffect.Fallback(listOf(Face.U), 1), capture(scan, listOf(null, uncertainF, first[2]), hold(2)))
        val next = guidedCapture(scan, face(Face.U)) as QuickScanEffect.Fallback
        assertEquals(2, next.round)
        assertTrue(Face.F in next.faces, "${next.faces}")
    }

    @Test
    fun `the guided grid rejects a face already read from a corner`() {
        val scan = QuickScanController()
        val first = hold(1).toMutableList().also { it[1] = it[1].withCell(0, Lab(55.0, 55.0, 55.0)) }
        assertEquals(QuickScanEffect.Fallback(listOf(Face.F), 1), capture(scan, first, hold(2)))
        val grid = checkNotNull(scan.fallback)
        grid.onFrame(face(Face.U), 0, 0)
        assertTrue(grid.onShutter(0).none { it is GuidedScanEffect.Capture })
        assertEquals(Coaching.WRONG_FACE, grid.coaching)
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

    private fun guidedCapture(
        scan: QuickScanController,
        stats: FrameStats,
    ): QuickScanEffect? {
        val grid = checkNotNull(scan.fallback)
        grid.onFrame(stats, 0, 0)
        return scan.onGuidedEffects(grid.onShutter(0))
    }

    private fun assemble(samples: List<FaceSample>): ScanResult.Full =
        ScanAssembler.assemble(samples, (SelfCalibration.from(samples) as SelfCalibration.Calibrated).calibration) as ScanResult.Full

    private fun FrameStats.withCell(
        index: Int,
        lab: Lab,
    ): FrameStats = copy(cells = cells.toMutableList().also { it[index] = CellReading(lab, true) })
}
