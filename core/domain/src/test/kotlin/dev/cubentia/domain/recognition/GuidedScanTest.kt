package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.recognition.RecognitionFixtures.CALIBRATION
import dev.cubentia.domain.recognition.RecognitionFixtures.LABS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class GuidedScanTest {
    private fun cells(
        center: Color,
        plain: Int = 9,
    ): List<CellReading?> = List(9) { CellReading(LABS.getValue(center), it < plain) }

    private fun stats(
        cells: List<CellReading?> = cells(Color.GREEN),
        luma: Double = 120.0,
        glare: Double = 0.0,
        sharpness: Double = 100.0,
        seams: Int = 12,
    ) = FrameStats(cells, luma, glare, sharpness, seams)

    private val good = stats()

    /** Feeds [frame] every 100 ms from [from] through [to] inclusive and returns every effect. */
    private fun GuidedScanController.feed(
        frame: FrameStats,
        from: Long,
        to: Long,
        orientation: Int = 0,
    ): List<GuidedScanEffect> = (from..to step 100).flatMap { onFrame(frame, orientation, it) }

    // FrameQuality

    @Test
    fun `quality precedence is low light, glare, blur, misaligned, acceptable`() {
        val bad = stats(cells = List(9) { null }, luma = 10.0, glare = 0.5, sharpness = 1.0)
        assertEquals(CaptureQuality.LOW_LIGHT, FrameQuality.assess(bad))
        assertEquals(CaptureQuality.GLARE, FrameQuality.assess(bad.copy(meanLuma = 40.0)))
        assertEquals(CaptureQuality.BLURRED, FrameQuality.assess(bad.copy(meanLuma = 40.0, glareFraction = 0.149)))
        assertEquals(CaptureQuality.MISALIGNED, FrameQuality.assess(bad.copy(meanLuma = 40.0, glareFraction = 0.0, sharpness = 60.0)))
        assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(good))
    }

    @Test
    fun `thresholds sit at their named boundaries`() {
        assertEquals(CaptureQuality.LOW_LIGHT, FrameQuality.assess(stats(luma = 39.9)))
        assertEquals(CaptureQuality.GLARE, FrameQuality.assess(stats(glare = 0.15)))
        assertEquals(CaptureQuality.BLURRED, FrameQuality.assess(stats(sharpness = 59.9)))
    }

    @Test
    fun `a missing cell, fewer than seven plain cells or not nine cells is misaligned`() {
        assertEquals(
            CaptureQuality.MISALIGNED,
            FrameQuality.assess(stats(cells = cells(Color.GREEN).toMutableList().also { it[3] = null })),
        )
        assertEquals(CaptureQuality.MISALIGNED, FrameQuality.assess(stats(cells = cells(Color.GREEN, plain = 6))))
        assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(stats(cells = cells(Color.GREEN, plain = 7))))
        assertEquals(CaptureQuality.MISALIGNED, FrameQuality.assess(stats(cells = cells(Color.GREEN).take(8))))
    }

    @Test
    fun `seams on fewer than a majority of edges is misaligned`() {
        assertEquals(CaptureQuality.MISALIGNED, FrameQuality.assess(stats(seams = 6)))
        assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(stats(seams = 7)))
    }

    @Test
    fun `a logo center that is not plain still auto-captures when the outer cells are plain (PO 2026-11-16)`() {
        val logoCenter = cells(Color.WHITE).toMutableList().also { it[4] = CellReading(LABS.getValue(Color.WHITE), false) }
        assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(stats(cells = logoCenter)))
        val controller = GuidedScanController()
        val effects = controller.feed(stats(cells = logoCenter), 0, 2000)
        val sample = (effects.first { it is GuidedScanEffect.Capture } as GuidedScanEffect.Capture).sample
        assertEquals(LABS.getValue(Color.WHITE), sample.center)
        assertEquals(false, sample.centerPlain)
        // The outer cells are still judged: three of them not plain is misaligned.
        val outerUnplain = logoCenter.mapIndexed { index, cell -> if (index < 3) cell!!.copy(plain = false) else cell }
        assertEquals(CaptureQuality.MISALIGNED, FrameQuality.assess(stats(cells = outerUnplain)))
    }

    @Test
    fun `a dull gray scene with faint seams never auto-captures, solved white and yellow faces do`() {
        val gray =
            stats(
                cells = List(9) { CellReading(Lab(if (it % 2 == 0) 58.0 else 52.0, 1.0, 4.0), true) },
                sharpness = 200.0,
                seams = 12,
            )
        assertEquals(CaptureQuality.MISALIGNED, FrameQuality.assess(gray))
        val controller = GuidedScanController()
        assertEquals(emptyList<GuidedScanEffect>(), controller.feed(gray, 0, 5000))
        assertEquals(Coaching.MISALIGNED, controller.coaching)
        // The manual shutter still captures it (PO 2026-11-16).
        assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assessShutter(gray))
        listOf(Color.WHITE, Color.YELLOW).forEach { color ->
            val solved = stats(cells = cells(color))
            assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(solved), "$color")
            assertTrue(GuidedScanController().feed(solved, 0, 2000).any { it is GuidedScanEffect.Capture }, "$color")
        }
    }

    /**
     * Device session B2, round 2b (Lenovo, `diag-lenovo-r2b.log`): the pale gapless stickerless cube's solved yellow face
     * passed every check but seams (4–6 of 7): nine plain cells C*ab ≈ 84–89, L* ≈ 75–82, sharpness 265–730, luma ≈ 158.
     * Outlined by its surroundings on all four sides it is aligned (PO 2026-11-16); without that outline (a plain wall)
     * or without sticker evidence (a gray scene) it is not.
     */
    @Test
    fun `the pinned solved gapless yellow face is aligned when outlined, a plain wall or gray scene is not`() {
        val yellow =
            listOf(
                78.0 to 86.0,
                75.0 to 84.0,
                82.0 to 89.0,
                79.0 to 85.0,
                80.0 to 87.0,
                77.0 to 88.0,
                76.0 to 84.5,
                81.0 to 86.5,
                78.5 to 85.5,
            ).map { (l, c) -> CellReading(Lab(l, -6.0, sqrt(c * c - 36.0)), true) }
        val face = FrameStats(yellow, 158.0, 0.0, 400.0, 5, faceEdges = 4)
        assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(face))
        assertTrue(GuidedScanController().feed(face, 0, 2000).any { it is GuidedScanEffect.Capture })
        listOf(4, 6).forEach { seams -> assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(face.copy(seams = seams))) }
        // A plain yellow wall: the same cells, no outline.
        listOf(0, 3).forEach { edges ->
            val wall = face.copy(faceEdges = edges)
            assertEquals(FrameCheck.SEAMS, FrameQuality.inspect(wall).failed, "$edges edges")
            assertTrue(GuidedScanController().feed(wall, 0, 5000).none { it is GuidedScanEffect.Capture })
        }
        // A gray scene, even outlined: no sticker evidence.
        val gray = face.copy(cells = List(9) { CellReading(Lab(60.0, 1.0, 3.0), true) })
        assertEquals(CaptureQuality.MISALIGNED, FrameQuality.assess(gray))
        // An outlined face with a cell that is not plain, or two neighbors that differ without a seam, is not.
        assertEquals(
            CaptureQuality.MISALIGNED,
            FrameQuality.assess(
                face.copy(
                    cells =
                        yellow.mapIndexed { i, c ->
                            if (i ==
                                1
                            ) {
                                c.copy(plain = false)
                            } else {
                                c
                            }
                        },
                ),
            ),
        )
        val oneOrange = yellow.mapIndexed { i, c -> if (i == 0) CellReading(LABS.getValue(Color.ORANGE), true) else c }
        assertEquals(CaptureQuality.MISALIGNED, FrameQuality.assess(face.copy(cells = oneOrange, seams = 0)))
        assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(face.copy(cells = oneOrange, seams = 2)))
    }

    @Test
    fun `a dark background or black body cell is misaligned (device session B2)`() {
        val dark = cells(Color.GREEN).toMutableList().also { it[6] = CellReading(Lab(12.0, 1.0, -2.0), true) }
        assertEquals(FrameCheck.DARK_CELLS, FrameQuality.inspect(stats(cells = dark)).failed)
        // A dark but saturated sticker (blue in dim light) is not.
        val blue = cells(Color.GREEN).toMutableList().also { it[6] = CellReading(Lab(22.0, 20.0, -45.0), true) }
        assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(stats(cells = blue)))
    }

    @Test
    fun `a misaligned face outside the tolerance coaches its move, other qualities keep their coaching`() {
        val misaligned = stats(seams = 3)
        mapOf(
            Framing.CLOSER to Coaching.MOVE_CLOSER,
            Framing.LEFT to Coaching.MOVE_LEFT,
            Framing.RIGHT to Coaching.MOVE_RIGHT,
            Framing.UP to Coaching.MOVE_UP,
            Framing.DOWN to Coaching.MOVE_DOWN,
        ).forEach { (framing, coaching) ->
            val controller = GuidedScanController()
            assertTrue(controller.feed(misaligned.copy(framing = framing), 0, 3000).none { it is GuidedScanEffect.Capture })
            assertEquals(coaching, controller.coaching, "$framing")
        }
        val generic = GuidedScanController()
        generic.onFrame(misaligned, 0, 0)
        assertEquals(Coaching.MISALIGNED, generic.coaching)
        val blurred = GuidedScanController()
        blurred.onFrame(misaligned.copy(sharpness = 1.0, framing = Framing.LEFT), 0, 0)
        assertEquals(Coaching.BLURRED, blurred.coaching)
    }

    @Test
    fun `inspect names the first failing check and its measured values (device session B2 diagnostics)`() {
        val ok = FrameQuality.inspect(good)
        assertNull(ok.failed)
        assertEquals(CaptureQuality.ACCEPTABLE, ok.quality)
        assertEquals(listOf(9, 8, 12, 9), listOf(ok.readableCells, ok.plainOuterCells, ok.seams, ok.stickerCells))
        val green = LABS.getValue(Color.GREEN)
        assertEquals(Math.hypot(green.a, green.b), ok.chroma[0]!!, 1e-9)
        assertEquals(green.l, ok.lightness[8]!!, 1e-9)
        assertEquals(listOf(120.0, 0.0, 100.0), listOf(ok.meanLuma, ok.glareFraction, ok.sharpness))
        val pale = List(9) { CellReading(Lab(70.0, 5.0, 10.0), true) }
        val expected =
            mapOf(
                stats(luma = 10.0) to FrameCheck.LIGHT,
                stats(glare = 0.5) to FrameCheck.GLARE,
                stats(sharpness = 1.0) to FrameCheck.SHARPNESS,
                stats(cells = cells(Color.GREEN).toMutableList().also { it[3] = null }) to FrameCheck.READABLE_CELLS,
                stats(cells = cells(Color.GREEN, plain = 6)) to FrameCheck.PLAIN_OUTER_CELLS,
                stats(seams = 6) to FrameCheck.SEAMS,
                stats(cells = pale) to FrameCheck.STICKER_CELLS,
                stats(cells = cells(Color.GREEN).toMutableList().also { it[0] = CellReading(Lab(8.0, 0.0, 1.0), true) }) to
                    FrameCheck.DARK_CELLS,
            )
        expected.forEach { (frame, check) ->
            val inspection = FrameQuality.inspect(frame)
            assertEquals(check, inspection.failed)
            assertEquals(FrameQuality.assess(frame), inspection.quality)
        }
        val missing = FrameQuality.inspect(stats(cells = cells(Color.GREEN).toMutableList().also { it[3] = null }))
        assertEquals(8, missing.readableCells)
        assertNull(missing.chroma[3])
        assertEquals(0, FrameQuality.inspect(stats(cells = pale)).stickerCells)
    }

    /** Background the device check captured: steady, sharp and bright, nine plain cells, but no cube in the grid. */
    private val backgrounds =
        listOf(
            // Uniform gray floor or wall.
            stats(cells = List(9) { CellReading(Lab(55.0, 0.0, 1.0), true) }, seams = 0),
            // Textured floor (terrazzo): mixed light grays, a grout line crossing two edges.
            stats(
                cells = List(9) { CellReading(Lab(if (it % 2 == 0) 72.0 else 64.0, 0.5, 3.0), true) },
                sharpness = 400.0,
                seams = 2,
            ),
            // Dark clothing with folds.
            stats(cells = List(9) { CellReading(Lab(30.0, 4.0, 6.0), true) }, luma = 60.0, seams = 4),
        )

    @Test
    fun `background with no cube in the grid never auto-captures`() {
        backgrounds.forEach { frame ->
            val controller = GuidedScanController()
            assertEquals(emptyList<GuidedScanEffect>(), controller.feed(frame, 0, 5000))
            assertEquals(Coaching.MISALIGNED, controller.coaching)
            assertEquals(0, controller.stepIndex)
        }
    }

    @Test
    fun `the manual shutter skips the cube-face check but not light, glare or sharpness (PO 2026-11-16)`() {
        backgrounds.forEach { frame ->
            val controller = GuidedScanController()
            controller.feed(frame, 0, 1000)
            assertTrue(controller.onShutter(1000).first() is GuidedScanEffect.Capture)
            assertEquals(1, controller.stepIndex)
        }
        // A cube face the check does not recognize (too few seams, cells not plain) is still captured by hand.
        val unrecognized = stats(cells = cells(Color.GREEN, plain = 0), seams = 0)
        assertEquals(CaptureQuality.MISALIGNED, FrameQuality.assess(unrecognized))
        assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assessShutter(unrecognized))
        val controller = GuidedScanController()
        assertEquals(emptyList<GuidedScanEffect>(), controller.feed(unrecognized, 0, 3000))
        assertEquals(Coaching.MISALIGNED, controller.coaching)
        val sample = (controller.onShutter(3000).first() as GuidedScanEffect.Capture).sample
        assertEquals(false, sample.centerPlain)
        // Unreadable cells still only coach.
        val unreadable = GuidedScanController()
        unreadable.onFrame(stats(cells = List(9) { null }), 0, 0)
        assertEquals(emptyList<GuidedScanEffect>(), unreadable.onShutter(10))
        assertEquals(Coaching.MISALIGNED, unreadable.coaching)
    }

    @Test
    fun `a cube only partly in the grid does not capture until it is centered`() {
        val controller = GuidedScanController()
        // Shifted half a cell: most seams fall between the grid lines.
        assertEquals(emptyList<GuidedScanEffect>(), controller.feed(stats(seams = 6), 0, 3000))
        assertEquals(0, controller.stepIndex)
        assertEquals(listOf(GuidedScanEffect.Remeter), controller.onFrame(good, 0, 3100))
        assertTrue(controller.feed(good, 3200, 4000).any { it is GuidedScanEffect.Capture })
    }

    // Plan

    @Test
    fun `the plan is six steps in the documented order, each on its home face, naming no top`() {
        assertEquals(
            listOf(Color.GREEN, Color.RED, Color.BLUE, Color.ORANGE, Color.WHITE, Color.YELLOW),
            GuidedScanPlan.steps.map { it.centerColor },
        )
        assertTrue(GuidedScanPlan.steps.all { it.upNeighborColor == null })
        assertEquals(listOf(Face.F, Face.R, Face.B, Face.L, Face.U, Face.D), GuidedScanPlan.steps.map { it.face })
    }

    @Test
    fun `a re-prompt names the 10_2 top`() {
        assertEquals(
            listOf(
                Color.GREEN to Color.WHITE,
                Color.RED to Color.WHITE,
                Color.BLUE to Color.WHITE,
                Color.ORANGE to Color.WHITE,
                Color.WHITE to Color.BLUE,
                Color.YELLOW to Color.GREEN,
            ),
            GuidedScanPlan.steps.map { GuidedScanPlan.reprompt(it.face).let { step -> step.centerColor to step.upNeighborColor } },
        )
    }

    @Test
    fun `six faces of a scrambled cube shown per the plan assemble to that cube`() {
        val moves =
            listOf(
                FaceTurn(Face.R, Turn.CLOCKWISE),
                FaceTurn(Face.U, Turn.COUNTERCLOCKWISE),
                FaceTurn(Face.F, Turn.HALF),
                FaceTurn(Face.L, Turn.CLOCKWISE),
            )
        val cube = CubeState.SOLVED.apply(moves).toFacelets()
        val controller = GuidedScanController()
        var now = 0L
        var done: List<FaceSample>? = null
        GuidedScanPlan.steps.forEach { step ->
            val net = cube.colors.subList(step.face.ordinal * 9, step.face.ordinal * 9 + 9)
            // A sensor needing 90° clockwise delivers the upright view turned a quarter counter-clockwise.
            val pixels = ScanAssembler.rotateClockwise(net, 3)
            val frame = stats(cells = pixels.map { CellReading(LABS.getValue(it), true) })
            controller.onFrame(stats(cells = List(9) { null }), 90, now)
            val effects = controller.feed(frame, now + 100, now + 1100, orientation = 90)
            done = effects.filterIsInstance<GuidedScanEffect.Done>().singleOrNull()?.samples ?: done
            now += 2000
        }
        val samples = requireNotNull(done)
        assertEquals(GuidedScanPlan.steps.map { it.centerColor }, samples.map { it.centerColor })
        assertTrue(samples.all { it.orientationDegrees == 90 && it.quality == CaptureQuality.ACCEPTABLE && it.centerPlain == true })
        assertEquals(ScanResult.Full(cube, List(54) { StickerConfidence.CONFIDENT }), ScanAssembler.assemble(samples, CALIBRATION))
        val selfCalibrated = SelfCalibration.from(samples) as SelfCalibration.Calibrated
        assertEquals(
            ScanResult.Full(cube, List(54) { StickerConfidence.CONFIDENT }),
            ScanAssembler.assemble(samples, selfCalibrated.calibration),
        )
    }

    // Controller

    @Test
    fun `alignment found remeters once and auto-captures after settle plus 600 ms steady`() {
        val controller = GuidedScanController()
        assertEquals(listOf(GuidedScanEffect.Remeter), controller.onFrame(good, 0, 0))
        // Settle until 300 ms; the reference is taken at 300 ms, so the capture lands at 900 ms.
        assertEquals(emptyList<GuidedScanEffect>(), controller.feed(good, 100, 800))
        val effects = controller.onFrame(good, 0, 900)
        assertEquals(2, effects.size)
        val capture = effects[0] as GuidedScanEffect.Capture
        assertEquals(GuidedScanEffect.Remeter, effects[1])
        assertEquals(Face.F, capture.sample.face)
        assertEquals(Color.GREEN, capture.sample.centerColor)
        assertNull(capture.sample.upNeighborColor)
        assertEquals(1, controller.stepIndex)
        assertEquals(GuidedScanPlan.steps[1], controller.step)
    }

    @Test
    fun `a cell moving 5 dE at 500 ms restarts the steady timer`() {
        val controller = GuidedScanController()
        controller.onFrame(good, 0, 0)
        controller.feed(good, 100, 400)
        val moved = cells(Color.GREEN).toMutableList().also { it[8] = CellReading(Lab(55.0, -60.0, 45.0), true) }
        assertEquals(emptyList<GuidedScanEffect>(), controller.onFrame(stats(cells = moved), 0, 500))
        // Steady from 500 ms against the moved reference: nothing until 1100 ms.
        assertEquals(emptyList<GuidedScanEffect>(), controller.feed(stats(cells = moved), 600, 1000))
        assertEquals(0, controller.stepIndex)
        assertTrue(controller.onFrame(stats(cells = moved), 0, 1100).first() is GuidedScanEffect.Capture)
    }

    /** A seamless solid yellow face, accepted only as an outlined face (no seams, every inner edge joins one color). */
    private val seamless = stats(cells = cells(Color.YELLOW), seams = 0).copy(faceEdges = 4)

    @Test
    fun `a seamless solid face auto-captures only after a 1200 ms steady hold (PO 2026-11-16)`() {
        assertTrue(FrameQuality.inspect(seamless).outlinedOnly)
        assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(seamless))
        val controller = GuidedScanController()
        assertEquals(listOf(GuidedScanEffect.Remeter), controller.onFrame(seamless, 0, 0))
        // The reference is taken at 300 ms: the normal hold would capture at 900 ms, the longer one waits until 1500 ms.
        assertEquals(emptyList<GuidedScanEffect>(), controller.feed(seamless, 100, 1400))
        assertEquals(0, controller.stepIndex)
        assertTrue(controller.onFrame(seamless, 0, 1500).first() is GuidedScanEffect.Capture)
    }

    @Test
    fun `a seamed face keeps the normal 600 ms hold`() {
        val seamed = seamless.copy(seams = FrameQuality.MIN_SEAMS)
        assertFalse(FrameQuality.inspect(seamed).outlinedOnly)
        assertFalse(FrameQuality.inspect(good).outlinedOnly)
        val controller = GuidedScanController()
        controller.onFrame(seamed, 0, 0)
        assertEquals(emptyList<GuidedScanEffect>(), controller.feed(seamed, 100, 800))
        assertTrue(controller.onFrame(seamed, 0, 900).first() is GuidedScanEffect.Capture)
    }

    @Test
    fun `a movement restarts the longer hold of a seamless face`() {
        val controller = GuidedScanController()
        controller.onFrame(seamless, 0, 0)
        controller.feed(seamless, 100, 1000)
        val moved = seamless.copy(cells = seamless.cells.map { it!!.copy(value = it.value.copy(l = it.value.l - 6.0)) })
        assertTrue(FrameQuality.inspect(moved).outlinedOnly)
        assertEquals(emptyList<GuidedScanEffect>(), controller.onFrame(moved, 0, 1100))
        // Steady from 1100 ms against the moved reference: nothing until 2300 ms.
        assertEquals(emptyList<GuidedScanEffect>(), controller.feed(moved, 1200, 2200))
        assertEquals(0, controller.stepIndex)
        assertTrue(controller.onFrame(moved, 0, 2300).first() is GuidedScanEffect.Capture)
    }

    @Test
    fun `losing alignment remeters again when it is found`() {
        val controller = GuidedScanController()
        assertEquals(listOf(GuidedScanEffect.Remeter), controller.onFrame(good, 0, 0))
        assertEquals(emptyList<GuidedScanEffect>(), controller.onFrame(good, 0, 100))
        assertEquals(emptyList<GuidedScanEffect>(), controller.onFrame(stats(cells = cells(Color.GREEN, plain = 3)), 0, 200))
        assertEquals(Coaching.MISALIGNED, controller.coaching)
        assertEquals(listOf(GuidedScanEffect.Remeter), controller.onFrame(good, 0, 300))
        assertNull(controller.coaching)
    }

    @Test
    fun `glare, blur and low light coach and never capture`() {
        val cases =
            mapOf(
                stats(glare = 0.3) to Coaching.GLARE,
                stats(sharpness = 5.0) to Coaching.BLURRED,
                stats(luma = 5.0) to Coaching.LOW_LIGHT,
            )
        cases.forEach { (frame, coaching) ->
            val controller = GuidedScanController()
            // A face stuck blurred or dark re-meters (see below) but never captures.
            assertTrue(controller.feed(frame, 0, 3000).none { it !is GuidedScanEffect.Remeter })
            assertEquals(coaching, controller.coaching)
            assertEquals(emptyList<GuidedScanEffect>(), controller.onShutter(3000))
            assertEquals(coaching, controller.coaching)
            assertEquals(0, controller.stepIndex)
        }
    }

    @Test
    fun `a face that stays blurred or dark for 1 s remeters, at most once a second (PO 2026-11-16)`() {
        listOf(stats(sharpness = 5.0), stats(luma = 5.0)).forEach { stuck ->
            val controller = GuidedScanController()
            assertEquals(listOf(GuidedScanEffect.Remeter), controller.onFrame(good, 0, 0))
            controller.feed(good, 100, 200)
            // The cube moves nearer: blurred from 300 ms; nothing before 1 s of it.
            assertEquals(emptyList<GuidedScanEffect>(), controller.feed(stuck, 300, 1200))
            assertEquals(listOf(GuidedScanEffect.Remeter), controller.onFrame(stuck, 0, 1300))
            // Rate limited: the next one a second later, not on every frame.
            assertEquals(emptyList<GuidedScanEffect>(), controller.feed(stuck, 1400, 2200))
            assertEquals(listOf(GuidedScanEffect.Remeter), controller.onFrame(stuck, 0, 2300))
            assertEquals(0, controller.stepIndex)
        }
    }

    @Test
    fun `no face in the grid or a short blur does not remeter`() {
        val controller = GuidedScanController()
        val blurredBackground = stats(cells = List(9) { null }, sharpness = 5.0)
        assertEquals(emptyList<GuidedScanEffect>(), controller.feed(blurredBackground, 0, 3000))
        // A blur shorter than 1 s, broken by a misaligned frame, restarts the count.
        assertEquals(emptyList<GuidedScanEffect>(), controller.feed(stats(sharpness = 5.0), 3100, 3900))
        assertEquals(emptyList<GuidedScanEffect>(), controller.onFrame(stats(cells = cells(Color.GREEN, plain = 3)), 0, 4000))
        assertEquals(emptyList<GuidedScanEffect>(), controller.feed(stats(sharpness = 5.0), 4100, 5000))
    }

    @Test
    fun `after a blur remeter the face sharpens and auto-captures as before`() {
        val controller = GuidedScanController()
        controller.feed(stats(sharpness = 5.0), 0, 1000)
        // Sharp again at 1100: alignment found remeters; settle to 1400, steady to 2000.
        assertEquals(listOf(GuidedScanEffect.Remeter), controller.onFrame(good, 0, 1100))
        assertEquals(emptyList<GuidedScanEffect>(), controller.feed(good, 1200, 1900))
        assertTrue(controller.onFrame(good, 0, 2000).first() is GuidedScanEffect.Capture)
    }

    @Test
    fun `the shutter captures the latest acceptable frame at once`() {
        val controller = GuidedScanController()
        assertEquals(Coaching.MISALIGNED, controller.onShutter(0).let { controller.coaching })
        controller.onFrame(good, 180, 10)
        val effects = controller.onShutter(20)
        assertEquals(180, (effects[0] as GuidedScanEffect.Capture).sample.orientationDegrees)
        assertEquals(GuidedScanEffect.Remeter, effects[1])
    }

    /** A controller that has captured green at step 1 and then saw the cube move on (a misaligned frame). */
    private fun afterGreen(): GuidedScanController =
        GuidedScanController().apply {
            onFrame(good, 0, 0)
            onShutter(10)
            onFrame(stats(cells = cells(Color.GREEN, plain = 0)), 0, 20)
        }

    // Still green: within 10 dE of the captured green center, but not the identical frame.
    private val nearGreen = stats(cells = List(9) { CellReading(Lab(57.0, -58.0, 42.0), true) })

    @Test
    fun `the same face again is rejected with WRONG_FACE and not stored`() {
        val controller = afterGreen()
        assertEquals(1, controller.stepIndex)
        assertEquals(listOf(GuidedScanEffect.Remeter), controller.onFrame(nearGreen, 0, 100))
        assertEquals(emptyList<GuidedScanEffect>(), controller.onShutter(110))
        assertEquals(Coaching.WRONG_FACE, controller.coaching)
        assertEquals(1, controller.stepIndex)
        assertEquals(1, controller.captured.size)
        assertFalse(controller.canUseAnyway)
    }

    @Test
    fun `auto-capture rejects a repeated face too and keeps waiting on the same step`() {
        val controller = afterGreen()
        // Remeter at 100, reference at 400, rejection at 1000.
        assertEquals(listOf(GuidedScanEffect.Remeter), controller.feed(nearGreen, 100, 1000))
        assertEquals(Coaching.WRONG_FACE, controller.coaching)
        assertEquals(1, controller.stepIndex)
        assertFalse(controller.canUseAnyway)
    }

    @Test
    fun `a second rejection at one step offers Use this face anyway, which stores the rejected frame`() {
        val controller = afterGreen()
        assertEquals(emptyList<GuidedScanEffect>(), controller.onUseAnyway(50))
        controller.feed(nearGreen, 100, 1000)
        assertFalse(controller.canUseAnyway)
        // The steady timer restarted at the first rejection (1000 ms); the second lands at 1600 ms.
        controller.feed(nearGreen, 1100, 1600)
        assertTrue(controller.canUseAnyway)
        assertEquals(1, controller.stepIndex)
        val effects = controller.onUseAnyway(1700)
        val sample = (effects[0] as GuidedScanEffect.Capture).sample
        assertEquals(GuidedScanEffect.Remeter, effects[1])
        assertEquals(Color.RED, sample.centerColor)
        assertEquals(Lab(57.0, -58.0, 42.0), sample.center)
        assertEquals(2, controller.stepIndex)
        assertFalse(controller.canUseAnyway)
        assertNull(controller.coaching)
    }

    @Test
    fun `the face just captured is ignored until the frame changes`() {
        val controller = GuidedScanController()
        controller.onFrame(good, 0, 0)
        assertTrue(controller.onShutter(10).first() is GuidedScanEffect.Capture)
        // The same green, or green within 15 dE in every cell: no capture, no coaching, for as long as it is held.
        assertEquals(emptyList<GuidedScanEffect>(), controller.feed(good, 100, 3000))
        assertEquals(emptyList<GuidedScanEffect>(), controller.feed(nearGreen, 3100, 6000))
        assertNull(controller.coaching)
        assertEquals(1, controller.stepIndex)
        // One cell 15 dE or more away is a change: alignment found again.
        val moved = cells(Color.GREEN).toMutableList().also { it[0] = CellReading(LABS.getValue(Color.RED), true) }
        assertEquals(listOf(GuidedScanEffect.Remeter), controller.onFrame(stats(cells = moved), 0, 6100))
    }

    @Test
    fun `the shutter does nothing while the face just captured is held`() {
        val controller = GuidedScanController()
        controller.onFrame(good, 0, 0)
        controller.onShutter(10)
        controller.onFrame(good, 0, 100)
        // A double tap while green is still held: no capture, no WRONG_FACE coaching, no rejection counted.
        assertEquals(emptyList<GuidedScanEffect>(), controller.onShutter(110))
        assertEquals(emptyList<GuidedScanEffect>(), controller.onShutter(120))
        assertNull(controller.coaching)
        assertFalse(controller.canUseAnyway)
        assertEquals(1, controller.stepIndex)
        // Once the cube moves on, the shutter works again.
        controller.onFrame(stats(cells = cells(Color.RED)), 0, 200)
        val sample = (controller.onShutter(210)[0] as GuidedScanEffect.Capture).sample
        assertEquals(LABS.getValue(Color.RED), sample.center)
    }

    /** [afterGreen] with two repeated-green rejections at the red step, so *Use this face anyway* is offered. */
    private fun overrideOffered(): GuidedScanController =
        afterGreen().apply {
            onFrame(nearGreen, 0, 100)
            onShutter(110)
            onShutter(120)
            assertTrue(canUseAnyway)
            assertEquals(Coaching.WRONG_FACE, coaching)
        }

    @Test
    fun `showing a different face clears the override and the WRONG_FACE coaching`() {
        val controller = overrideOffered()
        controller.onFrame(stats(cells = cells(Color.RED)), 0, 200)
        assertFalse(controller.canUseAnyway)
        assertNull(controller.coaching)
        assertEquals(emptyList<GuidedScanEffect>(), controller.onUseAnyway(210))
        // Green again needs two fresh rejections before the override returns.
        controller.onFrame(nearGreen, 0, 300)
        controller.onShutter(310)
        assertFalse(controller.canUseAnyway)
    }

    @Test
    fun `a misaligned blip keeps the override`() {
        val controller = overrideOffered()
        controller.onFrame(stats(cells = cells(Color.GREEN, plain = 0)), 0, 200)
        assertEquals(Coaching.MISALIGNED, controller.coaching)
        assertTrue(controller.canUseAnyway)
        controller.onFrame(nearGreen, 0, 300)
        assertTrue(controller.canUseAnyway)
        val sample = (controller.onUseAnyway(310)[0] as GuidedScanEffect.Capture).sample
        assertEquals(Color.RED, sample.centerColor)
    }

    @Test
    fun `a misaligned frame after a capture ends the hold and the next alignment remeters`() {
        val controller = GuidedScanController()
        controller.onFrame(good, 0, 0)
        controller.onShutter(10)
        assertEquals(emptyList<GuidedScanEffect>(), controller.onFrame(stats(luma = 5.0), 0, 100))
        assertEquals(Coaching.LOW_LIGHT, controller.coaching)
        assertEquals(listOf(GuidedScanEffect.Remeter), controller.onFrame(stats(cells = cells(Color.RED)), 0, 200))
    }

    @Test
    fun `a face not yet captured is stored under the step's labels`() {
        val controller = GuidedScanController()
        controller.onFrame(good, 0, 0)
        controller.onShutter(10)
        // Blue shown at the red step: stored as the red step, left to Story 10.3 validation.
        controller.onFrame(stats(cells = cells(Color.BLUE)), 0, 100)
        val sample = (controller.onShutter(110)[0] as GuidedScanEffect.Capture).sample
        assertEquals(Color.RED, sample.centerColor)
        assertEquals(Face.R, sample.face)
        assertEquals(LABS.getValue(Color.BLUE), sample.center)
    }

    @Test
    fun `the sixth capture emits Done with six samples in plan order, then ignores input`() {
        val controller = GuidedScanController()
        val colors = GuidedScanPlan.steps.map { it.centerColor }
        val effects =
            colors.flatMapIndexed { index, color ->
                controller.onFrame(stats(cells = cells(color)), 0, index * 10L)
                controller.onShutter(index * 10L + 1)
            }
        val done = effects.last() as GuidedScanEffect.Done
        assertEquals(colors, done.samples.map { it.centerColor })
        assertEquals(6, effects.count { it is GuidedScanEffect.Capture })
        assertTrue(controller.done)
        assertNull(controller.step)
        assertEquals(emptyList<GuidedScanEffect>(), controller.onFrame(good, 0, 1000))
        assertEquals(emptyList<GuidedScanEffect>(), controller.onShutter(1000))
    }

    @Test
    fun `coaching maps every capture quality`() {
        assertEquals(
            mapOf(
                CaptureQuality.ACCEPTABLE to null,
                CaptureQuality.GLARE to Coaching.GLARE,
                CaptureQuality.BLURRED to Coaching.BLURRED,
                CaptureQuality.LOW_LIGHT to Coaching.LOW_LIGHT,
                CaptureQuality.MISALIGNED to Coaching.MISALIGNED,
                CaptureQuality.INCOMPLETE to Coaching.MISALIGNED,
            ),
            CaptureQuality.entries.associateWith { GuidedScanController.coachingFor(it) },
        )
    }

    // Story 10.14: re-prompt round

    /** R2 alone: L2 is also valid with U, F, D and B half-turned, so those four faces are re-prompted. */
    private val r2 = CubeState.SOLVED.apply(listOf(FaceTurn(Face.R, Turn.HALF))).toFacelets()

    private fun faceOf(face: Face): FrameStats =
        stats(cells = r2.colors.subList(face.ordinal * 9, face.ordinal * 9 + 9).map { CellReading(LABS.getValue(it), true) })

    /** Shows [frame] and presses the shutter at [now]. */
    private fun GuidedScanController.shoot(
        frame: FrameStats,
        now: Long,
    ): List<GuidedScanEffect> {
        onFrame(frame, 0, now)
        return onShutter(now + 1)
    }

    /** A controller after the six first-round captures of [r2], each face as the net draws it. */
    private fun afterFirstRound(
        controller: GuidedScanController = GuidedScanController(),
    ): Pair<GuidedScanController, List<GuidedScanEffect>> {
        val effects = GuidedScanPlan.steps.flatMapIndexed { index, step -> controller.shoot(faceOf(step.face), index * 10L) }
        return controller to effects
    }

    @Test
    fun `first-round samples carry no named top`() {
        val (controller, _) = afterFirstRound()
        assertTrue(controller.captured.take(6).all { it.upNeighborColor == null }, "${controller.captured}")
    }

    @Test
    fun `zero or several valid states re-prompt only the faces involved, in order, with a named top`() {
        val (controller, first) = afterFirstRound()
        // The sixth capture asks for re-prompts instead of finishing.
        assertEquals(6, first.count { it is GuidedScanEffect.Capture })
        assertTrue(first.none { it is GuidedScanEffect.Done })
        assertEquals(GuidedScanEffect.Remeter, first.last())
        assertFalse(controller.done)
        assertTrue(controller.repeating)
        assertEquals(4, controller.repromptCount)
        assertEquals(6, controller.stepIndex)
        val asked = mutableListOf<ScanStep>()
        var done: List<FaceSample>? = null
        var now = 1000L
        while (!controller.done) {
            val step = requireNotNull(controller.step)
            assertEquals(asked.size, controller.repromptIndex)
            asked += step
            val effects = controller.shoot(faceOf(step.face), now)
            val sample = (effects.first() as GuidedScanEffect.Capture).sample
            assertEquals(step.upNeighborColor, sample.upNeighborColor)
            assertEquals(step.face, sample.face)
            done = effects.filterIsInstance<GuidedScanEffect.Done>().singleOrNull()?.samples
            now += 1000
        }
        assertEquals(listOf(Face.F, Face.B, Face.U, Face.D).map(GuidedScanPlan::reprompt), asked)
        val samples = requireNotNull(done)
        assertEquals(GuidedScanPlan.steps.map { it.centerColor }, samples.map { it.centerColor })
        // Each re-prompt capture replaced its face's earlier sample.
        assertEquals(listOf(Color.WHITE, null, Color.WHITE, null, Color.BLUE, Color.GREEN), samples.map { it.upNeighborColor })
        assertFalse(controller.repeating)
        assertNull(controller.step)
        val calibration = (SelfCalibration.from(samples) as SelfCalibration.Calibrated).calibration
        assertEquals(ScanResult.Full(r2, List(54) { StickerConfidence.CONFIDENT }), ScanAssembler.assemble(samples, calibration))
        assertEquals(emptyList<GuidedScanEffect>(), controller.shoot(faceOf(Face.F), now))
    }

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
                ),
            ).toFacelets()

    /** The six first-round captures of [cube], each face as the net draws it, through the default re-prompt. */
    private fun firstRoundOf(cube: Facelets): Pair<GuidedScanController, List<GuidedScanEffect>> {
        val controller = GuidedScanController()
        val effects =
            GuidedScanPlan.steps.flatMapIndexed { index, step ->
                val cells =
                    cube.colors
                        .subList(
                            step.face.ordinal * 9,
                            step.face.ordinal * 9 + 9,
                        ).map { CellReading(LABS.getValue(it), true) }
                controller.shoot(stats(cells = cells), index * 10L)
            }
        return controller to effects
    }

    @Test
    fun `no valid state with two faces involved re-prompts them`() {
        // Edge UF misread: U7 shows F1's color. Only U and F hold the illegal piece.
        val misread = Facelets(scrambled.colors.toMutableList().also { it[7] = it[19] })
        val (controller, effects) = firstRoundOf(misread)
        assertTrue(effects.none { it is GuidedScanEffect.Done })
        assertTrue(controller.repeating)
        assertEquals(2, controller.repromptCount)
        assertEquals(GuidedScanPlan.reprompt(Face.F), controller.step)
        val green = misread.colors.subList(Face.F.ordinal * 9, Face.F.ordinal * 9 + 9).map { CellReading(LABS.getValue(it), true) }
        controller.shoot(stats(cells = green), 1000)
        assertEquals(GuidedScanPlan.reprompt(Face.U), controller.step)
    }

    @Test
    fun `no valid state with more than two faces involved goes straight to review with the best fit (PO 2026-11-16, session B2)`() {
        // Corner URF misread: U8 shows R0's color. U, R and F hold the illegal piece: a color problem, not a turn.
        val misread = Facelets(scrambled.colors.toMutableList().also { it[8] = it[9] })
        val (controller, effects) = firstRoundOf(misread)
        val done = effects.last() as GuidedScanEffect.Done
        assertTrue(controller.done)
        assertFalse(controller.repeating)
        assertEquals(0, controller.repromptCount)
        assertTrue(done.samples.all { it.upNeighborColor == null })
        val calibration = (SelfCalibration.from(done.samples) as SelfCalibration.Calibrated).calibration
        val full = ScanAssembler.assemble(done.samples, calibration) as ScanResult.Full
        assertEquals(misread, full.facelets)
        // Review names the illegal corner through its own validation.
        assertTrue(CubeValidator.validate(full.facelets) !is ValidationResult.Valid)
    }

    @Test
    fun `there is no second re-prompt round`() {
        var calls = 0
        val controller = GuidedScanController(reprompt = { calls++.let { listOf(Face.R) } })
        val (_, first) = afterFirstRound(controller)
        assertTrue(first.none { it is GuidedScanEffect.Done })
        assertEquals(GuidedScanPlan.reprompt(Face.R), controller.step)
        val effects = controller.shoot(faceOf(Face.R), 1000)
        val done = effects.last() as GuidedScanEffect.Done
        assertEquals(1, calls)
        assertTrue(controller.done)
        assertEquals(Color.WHITE, done.samples[1].upNeighborColor)
        assertEquals(6, done.samples.size)
    }

    @Test
    fun `no faces involved finishes at the sixth capture`() {
        val controller = GuidedScanController(reprompt = { emptyList() })
        val (_, effects) = afterFirstRound(controller)
        assertTrue(effects.last() is GuidedScanEffect.Done)
        assertFalse(controller.repeating)
        assertEquals(0, controller.repromptCount)
    }

    @Test
    fun `in the re-prompt round a repeated face is judged against the other faces only`() {
        val controller = GuidedScanController(reprompt = { listOf(Face.F, Face.B) })
        afterFirstRound(controller)
        // Green again at the green re-prompt: its own earlier sample does not count.
        assertTrue(controller.shoot(faceOf(Face.F), 1000).first() is GuidedScanEffect.Capture)
        assertEquals(GuidedScanPlan.reprompt(Face.B), controller.step)
        // Green again at the blue re-prompt is a repeated face.
        controller.onFrame(stats(luma = 5.0), 0, 1500)
        assertEquals(emptyList<GuidedScanEffect>(), controller.shoot(faceOf(Face.F), 2000))
        assertEquals(Coaching.WRONG_FACE, controller.coaching)
        assertEquals(GuidedScanPlan.reprompt(Face.B), controller.step)
        assertTrue(controller.shoot(faceOf(Face.B), 3000).last() is GuidedScanEffect.Done)
    }

    @Test
    fun `the default re-prompt asks nothing when the scan needs calibration`() {
        assertEquals(emptyList<Face>(), GuidedScanController.defaultReprompt(emptyList()))
    }
}
