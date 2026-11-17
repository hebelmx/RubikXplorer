package dev.cubentia.app.camera

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CalibrationSession
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.Coaching
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.GuidedScanController
import dev.cubentia.domain.recognition.GuidedScanEffect
import dev.cubentia.domain.recognition.GuidedScanPlan
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.ScanReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Story 10.2: the Activity's pure permission and effect mappings, and the mini net's upright order. */
class GuidedScanFlowTest {
    @Test fun `corrections overwrite only painted indices and select the first blank`() {
        val review = ScanReview(List(54) { null }, null)
        val corrected = GuidedScanFlow.withCorrections(review, mapOf(0 to Color.RED, 8 to Color.BLUE))
        assertEquals(Color.RED, corrected.stickers[0])
        assertEquals(Color.BLUE, corrected.stickers[8])
        assertEquals(1, corrected.selected)
        assertEquals(46, corrected.blanks.size)
    }

    @Test fun `swatch hold captures steadily resets for retry and ignores pending`() {
        val hold = CalibrationHold()
        val frame = FrameStats(List(9) { CellReading(Lab(55.0, -60.0, 40.0), true) }, 120.0, 0.0, 100.0, 12)
        val session =
            dev.cubentia.domain.recognition
                .CalibrationSession()

        fun feed(
            s: CalibrationSession,
            start: Long,
            end: Long,
        ) = (start..end step 100).flatMap { hold.onFrame(s, frame, 0, it) }
        assertTrue(feed(session, 0, 2000).any { it is GuidedScanEffect.Capture })
        val pending = session.offer(CellReading(Lab(55.0, -60.0, 40.0), true))
        assertTrue(feed(pending, 2100, 4100).isEmpty())
        val retry = pending.retake()
        assertFalse(feed(retry, 4200, 4400).any { it is GuidedScanEffect.Capture })
        assertTrue(feed(retry, 4500, 6500).any { it is GuidedScanEffect.Capture })
    }

    private fun sample(
        stickers: List<Lab> = List(9) { Lab(it.toDouble(), 0.0, 0.0) },
        orientation: Int = 0,
    ) = FaceSample(
        face = Face.F,
        orientationDegrees = orientation,
        stickers = stickers,
        quality = CaptureQuality.ACCEPTABLE,
        centerColor = Color.GREEN,
    )

    @Test
    fun `the rationale comes first without the permission, the viewfinder with it`() {
        assertEquals(GuidedScanPhase.Rationale, GuidedScanFlow.initialPhase(hasPermission = false))
        assertEquals(GuidedScanPhase.Viewfinder(), GuidedScanFlow.initialPhase(hasPermission = true))
    }

    private fun denied(
        rationaleBefore: Boolean,
        rationaleAfter: Boolean,
        deniedBefore: Boolean,
    ) = GuidedScanFlow.afterPermission(false, rationaleBefore, rationaleAfter, deniedBefore)

    @Test
    fun `a grant opens the viewfinder`() {
        assertEquals(
            GuidedScanPhase.Viewfinder(),
            GuidedScanFlow.afterPermission(true, rationaleBefore = true, rationaleAfter = false, deniedBefore = true),
        )
    }

    @Test
    fun `a first-ever dismissal with no rationale before or after is not permanent (Android 11+)`() {
        assertEquals(GuidedScanPhase.Denied(false), denied(rationaleBefore = false, rationaleAfter = false, deniedBefore = false))
    }

    @Test
    fun `a denial after which the system still shows a rationale is not permanent`() {
        assertEquals(GuidedScanPhase.Denied(false), denied(rationaleBefore = false, rationaleAfter = true, deniedBefore = false))
        assertEquals(GuidedScanPhase.Denied(false), denied(rationaleBefore = true, rationaleAfter = true, deniedBefore = true))
    }

    @Test
    fun `a second denial that removes the rationale is permanent`() {
        assertEquals(GuidedScanPhase.Denied(true), denied(rationaleBefore = true, rationaleAfter = false, deniedBefore = false))
    }

    @Test
    fun `an already permanent denial in a later scan is permanent`() {
        assertEquals(GuidedScanPhase.Denied(true), denied(rationaleBefore = false, rationaleAfter = false, deniedBefore = true))
    }

    @Test
    fun `effects map to remetering and to the Captured result`() {
        val samples = List(6) { sample() }
        assertTrue(GuidedScanFlow.remeters(listOf(GuidedScanEffect.Capture(samples[0]), GuidedScanEffect.Remeter)))
        assertFalse(GuidedScanFlow.remeters(listOf(GuidedScanEffect.Capture(samples[0]), GuidedScanEffect.Done(samples))))
        assertNull(GuidedScanFlow.resultOf(listOf(GuidedScanEffect.Capture(samples[0]), GuidedScanEffect.Remeter)))
        assertEquals(
            ScanReview.fromSamples(samples),
            GuidedScanFlow.resultOf(listOf(GuidedScanEffect.Capture(samples[5]), GuidedScanEffect.Done(samples))),
        )
    }

    @Test
    fun `the viewfinder phase mirrors the controller`() {
        val controller = GuidedScanController()
        val cells = List(9) { CellReading(Lab(55.0, -60.0, 40.0), true) }
        controller.onFrame(FrameStats(cells, 120.0, 0.0, 100.0, 12), 0, 0)
        controller.onShutter(10)
        controller.onFrame(FrameStats(cells, 10.0, 0.0, 100.0, 12), 0, 20)
        val phase = GuidedScanFlow.phaseOf(controller)
        assertEquals(1, phase.stepIndex)
        assertEquals(controller.captured, phase.captured)
        assertEquals(Coaching.LOW_LIGHT, phase.coaching)
        assertFalse(phase.canUseAnyway)
        assertEquals(GuidedScanPlan.steps[1], phase.step)
        assertNull(phase.again)
    }

    @Test
    fun `the viewfinder phase mirrors the re-prompt step and its progress`() {
        val controller = GuidedScanController(reprompt = { listOf(Face.B, Face.U) })
        val colors =
            listOf(
                Lab(55.0, -60.0, 40.0),
                Lab(45.0, 65.0, 45.0),
                Lab(40.0, 10.0, -50.0),
                Lab(65.0, 45.0, 65.0),
                Lab(90.0, 0.0, 0.0),
                Lab(85.0, -5.0, 80.0),
            )
        colors.forEachIndexed { index, lab ->
            controller.onFrame(FrameStats(List(9) { CellReading(lab, true) }, 120.0, 0.0, 100.0, 12), 0, index * 10L)
            controller.onShutter(index * 10L + 1)
        }
        val phase = GuidedScanFlow.phaseOf(controller)
        assertEquals(GuidedScanPlan.reprompt(Face.B), phase.step)
        assertEquals(Color.WHITE, phase.step?.upNeighborColor)
        assertEquals(RepromptProgress(1, 2), phase.again)
        assertEquals(6, phase.captured.size)
    }

    @Test
    fun `uprightNet turns pixel order clockwise by the orientation`() {
        val pixels = List(9) { Lab(it.toDouble(), 0.0, 0.0) }

        fun upright(orientation: Int) = uprightNet(sample(pixels, orientation)).map { it.l.toInt() }
        assertEquals((0..8).toList(), upright(0))
        assertEquals(listOf(6, 3, 0, 7, 4, 1, 8, 5, 2), upright(90))
        assertEquals((8 downTo 0).toList(), upright(180))
        assertEquals(listOf(2, 5, 8, 1, 4, 7, 0, 3, 6), upright(270))
        assertEquals(upright(270), upright(-90))
    }

    @Test
    fun `a capture freezes every capture decision for 400 ms, with the face already captured`() {
        val controller = GuidedScanController()
        val freeze = CaptureFreeze()

        fun face(lab: Lab) = FrameStats(List(9) { CellReading(lab, true) }, 120.0, 0.0, 100.0, 12)
        val green = face(Lab(55.0, -60.0, 40.0))
        val red = face(Lab(50.0, 70.0, 50.0))
        assertFalse(freeze.isFrozen(0))
        freeze.decide(0) { controller.onFrame(green, 0, 0) }
        assertTrue(freeze.decide(10) { controller.onShutter(10) }.first() is GuidedScanEffect.Capture)
        // The face is marked captured at once, while its frame stays frozen.
        assertEquals(1, controller.stepIndex)
        assertEquals(listOf(Face.F), GuidedScanFlow.phaseOf(controller).captured.map { it.face })
        assertTrue(freeze.isFrozen(10))
        assertTrue(freeze.isFrozen(10 + CaptureFreeze.FREEZE_MS - 1))
        // A new face held steady, the shutter and the override during the freeze: no decision runs.
        var decisions = 0
        (100L..400L step 50).forEach { now ->
            assertEquals(emptyList<GuidedScanEffect>(), freeze.decide(now) { decisions++.let { controller.onFrame(red, 0, now) } })
            assertEquals(emptyList<GuidedScanEffect>(), freeze.decide(now + 5) { decisions++.let { controller.onShutter(now + 5) } })
            assertEquals(emptyList<GuidedScanEffect>(), freeze.decide(now + 5) { decisions++.let { controller.onUseAnyway(now + 5) } })
        }
        assertEquals(0, decisions)
        assertEquals(1, controller.stepIndex)
        // Live view resumes after the freeze.
        assertFalse(freeze.isFrozen(10 + CaptureFreeze.FREEZE_MS))
        assertEquals(listOf(GuidedScanEffect.Remeter), freeze.decide(500) { controller.onFrame(red, 0, 500) })
        val next = freeze.decide(510) { controller.onShutter(510) }.first() as GuidedScanEffect.Capture
        assertEquals(Face.R, next.sample.face)
        assertTrue(freeze.isFrozen(510))
    }

    @Test
    fun `the finished scan waits for the last face's freeze to end`() {
        val vm = GuidedScanViewModel()
        val done = ScanReview.fromSamples(List(6) { sample() })
        vm.phase = GuidedScanPhase.Viewfinder()
        vm.frozen = FrozenCapture(6, Face.B, null)
        vm.publish(done)
        assertEquals(GuidedScanPhase.Viewfinder(), vm.phase)
        // A stale freeze end (an earlier capture) neither ends this freeze nor publishes.
        vm.endFreeze(5)
        assertEquals(GuidedScanPhase.Viewfinder(), vm.phase)
        vm.endFreeze(6)
        assertNull(vm.frozen)
        assertEquals(GuidedScanPhase.Review(done), vm.phase)
    }

    @Test
    fun `a camera grant opens the Quick Check controller's current step`() {
        val vm = GuidedScanViewModel()
        vm.quickFaces = setOf(Face.R, Face.U)
        vm.controller = vm.newController()
        val phase = vm.capturePhase() as GuidedScanPhase.Viewfinder
        assertEquals(GuidedScanFlow.phaseOf(vm.controller), phase)
        assertEquals(Face.U, phase.step?.face)
        vm.calibrationMode = true
        assertTrue(vm.capturePhase() is GuidedScanPhase.Calibration)
    }

    @Test
    fun `the finished scan is published at once when no capture is frozen`() {
        val vm = GuidedScanViewModel()
        val done = ScanReview.fromSamples(List(6) { sample() })
        vm.publish(done)
        assertEquals(GuidedScanPhase.Review(done), vm.phase)
    }

    @Test
    fun `a decision without a capture does not freeze`() {
        val freeze = CaptureFreeze()
        freeze.decide(0) { listOf(GuidedScanEffect.Remeter) }
        assertFalse(freeze.isFrozen(1))
    }

    @Test fun `review flow passes saved centers into assembly and offer`() {
        val labs =
            mapOf(
                Color.GREEN to Lab(55.0, -60.0, 40.0),
                Color.RED to Lab(45.0, 65.0, 45.0),
                Color.BLUE to Lab(40.0, 10.0, -50.0),
                Color.ORANGE to Lab(65.0, 45.0, 65.0),
                Color.WHITE to Lab(90.0, 0.0, 0.0),
                Color.YELLOW to Lab(85.0, -5.0, 80.0),
            )
        val saved =
            dev.cubentia.domain.recognition
                .CalibrationCenters(labs)
        val samples =
            Face.entries.map { face ->
                val color = Color.home(face)
                FaceSample(
                    face = face,
                    orientationDegrees = 0,
                    stickers = List(9) { labs.getValue(color) },
                    quality = CaptureQuality.ACCEPTABLE,
                    centerColor = color,
                    centerPlain = color !in setOf(Color.WHITE, Color.RED),
                )
            }
        val effects = listOf(GuidedScanEffect.Done(samples))
        assertTrue(GuidedScanFlow.reviewOf(effects, null)!!.offerCalibration)
        val review = GuidedScanFlow.reviewOf(effects, saved)!!
        assertFalse(review.offerCalibration)
        assertTrue(review.review.blanks.isEmpty())
        assertEquals(review.review, GuidedScanFlow.resultOf(effects, saved))
    }

    @Test fun `calibration shutter coaches poor frames without offering a swatch`() {
        val phase = GuidedScanPhase.Calibration()
        val ring = CellReading(Lab(55.0, -60.0, 40.0), true)
        val good = FrameStats(List(9) { ring }, 120.0, 0.0, 100.0, 12)
        val dark = GuidedScanFlow.calibrationCapture(phase, good.copy(meanLuma = 1.0), ring)
        assertEquals(Coaching.LOW_LIGHT, dark.coaching)
        assertNull(dark.session.pending)
        assertFalse(dark.session.plainSticker)
        val accepted = GuidedScanFlow.calibrationCapture(dark, good, ring)
        assertEquals(ring.value, accepted.session.pending)
        assertNull(accepted.coaching)
        val unreadable = GuidedScanFlow.calibrationCapture(phase, good, null)
        assertTrue(unreadable.session.plainSticker)
        assertNull(unreadable.session.pending)
    }

    @Test fun quickCheckKeepsOnlyConfidentPosedFacesAndCanReturnNoEvidence() {
        val labs =
            mapOf(
                Color.GREEN to Lab(55.0, -60.0, 40.0),
                Color.RED to Lab(45.0, 65.0, 45.0),
                Color.BLUE to Lab(40.0, 10.0, -50.0),
                Color.ORANGE to Lab(65.0, 45.0, 65.0),
                Color.WHITE to Lab(90.0, 0.0, 0.0),
                Color.YELLOW to Lab(85.0, -5.0, 80.0),
            )
        val saved =
            dev.cubentia.domain.recognition
                .CalibrationCenters(labs)

        fun face(face: Face) =
            GuidedScanPlan.reprompt(face).let { step ->
                FaceSample(
                    face = face,
                    orientationDegrees = 0,
                    stickers = List(9) { labs.getValue(step.centerColor) },
                    quality = CaptureQuality.ACCEPTABLE,
                    centerColor = step.centerColor,
                    upNeighborColor = step.upNeighborColor,
                )
            }
        val good = face(Face.R)
        val uncertain = face(Face.U).copy(stickers = face(Face.U).stickers.toMutableList().also { it[0] = Lab(87.5, -2.5, 40.0) })
        val result = GuidedScanFlow.quickCheckOf(listOf(good, uncertain), saved)
        assertEquals(setOf(Face.R), result.faces.keys)
        assertEquals(List(9) { Color.RED }, result.faces.getValue(Face.R))
        assertTrue(GuidedScanFlow.quickCheckOf(listOf(uncertain), saved).faces.isEmpty())
        assertTrue(GuidedScanFlow.quickCheckOf(listOf(good.copy(upNeighborColor = null)), saved).faces.isEmpty())
    }

    /** Device 10.7 (Galaxy S24 FE, CubentiaScanDiag): the two Quick Check sample sets, read letters in pixel order. */
    private val deviceLabs =
        mapOf(
            Color.GREEN to Lab(55.0, -60.0, 40.0),
            Color.RED to Lab(45.0, 65.0, 45.0),
            Color.BLUE to Lab(40.0, 10.0, -50.0),
            Color.ORANGE to Lab(65.0, 45.0, 65.0),
            Color.WHITE to Lab(90.0, 0.0, 0.0),
            Color.YELLOW to Lab(85.0, -5.0, 80.0),
        )
    private val deviceExpected = "YYRWWBYWWGOBYRWORRBGOBGBOYWGGBRYBGRGRROOOWRYYWOBOBGYGW"

    private fun colorsOf(letters: String) = letters.map { letter -> Color.entries.first { it.name.first() == letter } }

    /** A sample as logged `<face>:c=<center>/up=<up>/o=<deg>/read=<letters>`, each sticker at its color's center. */
    private fun deviceSample(
        face: Face,
        up: Color,
        read: String,
        center: Lab? = null,
    ) = FaceSample(
        face = face,
        orientationDegrees = 90,
        stickers =
            colorsOf(read).mapIndexed { i, color ->
                if (i == FaceSample.CENTER_INDEX && center != null) center else deviceLabs.getValue(color)
            },
        quality = CaptureQuality.ACCEPTABLE,
        centerColor = Color.home(face),
        upNeighborColor = up,
    )

    private fun deviceCheck(
        partial: dev.cubentia.domain.recognition.PhysicalObservation.Partial,
        expected: String = deviceExpected,
        faces: Set<Face> = setOf(Face.U, Face.R),
    ) = dev.cubentia.domain.tutor.Assess.check(
        dev.cubentia.domain.tutor.CandidateEnumerator(
            (
                dev.cubentia.domain.cube.CubeValidator
                    .validate(
                        dev.cubentia.domain.cube
                            .Facelets(colorsOf(expected)),
                    )
                    as dev.cubentia.domain.cube.ValidationResult.Valid
            ).state,
            1,
            (
                dev.cubentia.domain.cube.Notation
                    .parse("U U' U2 R R' R2 F F' F2 D D' D2 L L' L2 B B' B2")
                    as dev.cubentia.domain.cube.NotationResult.Parsed
            ).moves,
        ),
        partial,
        faces,
    )

    @Test fun `device quick check of U and R on the expected cube keeps both faces and verifies`() {
        // Data point 1: R's red center read close to orange (UNCERTAIN, nearest still red); round 1 dropped R for it.
        val closeCall = Lab(53.0, 57.0, 53.0)
        val samples =
            listOf(
                deviceSample(Face.U, Color.BLUE, "RBWYWWYWY"),
                deviceSample(Face.R, Color.WHITE, "BWRORRGYO", center = closeCall),
            )
        val partial = GuidedScanFlow.quickCheckOf(samples, CalibrationCenters(deviceLabs))
        assertEquals(setOf(Face.U, Face.R), partial.faces.keys)
        assertEquals(colorsOf("YYRWWBYWW"), partial.faces.getValue(Face.U))
        assertEquals(colorsOf("GOBYRWORR"), partial.faces.getValue(Face.R))
        val check = deviceCheck(partial)
        assertEquals(dev.cubentia.domain.tutor.Certainty.VERIFIED, check.certainty)
        assertEquals(listOf(emptyList<dev.cubentia.domain.cube.Move>()), check.consistent.map { it.moves })
    }

    @Test fun `device quick check after a wrong R turn orients R by its prompted top and is likely, not verified`() {
        // Data point 2: the raw R read equals the expected R face only because R itself was turned a quarter.
        val samples =
            listOf(
                deviceSample(Face.U, Color.BLUE, "OBWYWWYWY"),
                deviceSample(Face.R, Color.WHITE, "GOBYRWORR"),
            )
        val partial = GuidedScanFlow.quickCheckOf(samples, CalibrationCenters(deviceLabs))
        assertEquals(colorsOf("YYOWWBYWW"), partial.faces.getValue(Face.U))
        assertEquals(colorsOf("OYGRRORWB"), partial.faces.getValue(Face.R))
        val check = deviceCheck(partial)
        assertEquals(dev.cubentia.domain.tutor.Certainty.LIKELY, check.certainty)
        assertFalse(check.matchesExpected)
        assertEquals(
            listOf(
                (
                    dev.cubentia.domain.cube.Notation
                        .parse("R") as dev.cubentia.domain.cube.NotationResult.Parsed
                ).moves,
            ),
            check.consistent.map { it.moves },
        )
    }

    @Test fun `a quick check face whose center reads another color is dropped`() {
        val wrongFace = deviceSample(Face.R, Color.WHITE, "BWRORRGYO", center = deviceLabs.getValue(Color.GREEN))
        val partial =
            GuidedScanFlow.quickCheckOf(
                listOf(deviceSample(Face.U, Color.BLUE, "RBWYWWYWY"), wrongFace),
                CalibrationCenters(deviceLabs),
            )
        assertEquals(setOf(Face.U), partial.faces.keys)
    }

    @Test fun `device quick check 3 orients U and R as the validity-searched full scans of the same cube do`() {
        // Data point 3: the Partial's R (raw OYGRRORGG, one quarter by its prompted white top) is what the two
        // 6-face scans of the same cube resolved from validity (13:43 raw OYGRRORGG at R1, 13:46 raw RROGRYGOG at R0).
        val partial =
            GuidedScanFlow.quickCheckOf(
                listOf(deviceSample(Face.U, Color.BLUE, "BBBYWWYWR"), deviceSample(Face.R, Color.WHITE, "OYGRRORGG")),
                CalibrationCenters(deviceLabs),
            )
        val full =
            dev.cubentia.domain.recognition.ScanAssembler.assemble(
                listOf(
                    Face.F to "YOWGGYBBO",
                    Face.R to "RROGRYGOG",
                    Face.B to "WOBBBGOGW",
                    Face.L to "RRGOOWRYY",
                    Face.U to "BBBYWWYWR",
                    Face.D to "YBWWYRORG",
                ).map { (face, read) -> deviceSample(face, Color.WHITE, read).copy(upNeighborColor = null) },
                CalibrationCenters(deviceLabs),
            ) as dev.cubentia.domain.recognition.ScanResult.Full
        val colors = full.facelets.colors
        assertEquals(colors.subList(0, 9), partial.faces.getValue(Face.U))
        assertEquals(colors.subList(9, 18), partial.faces.getValue(Face.R))
        assertEquals(colorsOf("RROGRYGOG"), partial.faces.getValue(Face.R))
        assertEquals(
            dev.cubentia.domain.recognition.PartialCheck.Consistent,
            dev.cubentia.domain.recognition.PartialConsistency
                .check(partial),
        )
    }

    /** A Quick Check of U then R, its re-prompts asked by [GuidedScanFlow.quickCheckRetakes] as the Activity does. */
    private fun quickCheckController() =
        GuidedScanController(
            plan = listOf(GuidedScanPlan.reprompt(Face.U), GuidedScanPlan.reprompt(Face.R)),
            reprompt = { GuidedScanFlow.quickCheckRetakes(it, CalibrationCenters(deviceLabs)) },
        )

    private var clock = 0L

    /** Shows [sample]'s stickers after a dark frame (the cube moved on) and presses Capture. */
    private fun GuidedScanController.shoot(sample: FaceSample): List<GuidedScanEffect> {
        clock += 100
        onFrame(FrameStats(List(9) { CellReading(Lab(1.0, 0.0, 0.0), true) }, 1.0, 0.0, 100.0, 12), 90, clock)
        clock += 100
        onFrame(FrameStats(sample.stickers.map { CellReading(it, true) }, 120.0, 0.0, 100.0, 12), 90, clock)
        return onShutter(clock)
    }

    private val quickU = deviceSample(Face.U, Color.BLUE, "RBWYWWYWY")
    private val quickR = deviceSample(Face.R, Color.WHITE, "BWRORRGYO")

    /** [quickR] with one non-center sticker between white and yellow (UNCERTAIN). */
    private val doubtfulR = quickR.copy(stickers = quickR.stickers.toMutableList().also { it[1] = Lab(87.5, -2.5, 40.0) })

    @Test fun `a quick check face with one uncertain sticker is asked once more, naming the face and its top`() {
        assertEquals(listOf(Face.R), GuidedScanFlow.quickCheckRetakes(listOf(quickU, doubtfulR), CalibrationCenters(deviceLabs)))
        val controller = quickCheckController()
        assertFalse(controller.shoot(quickU).any { it is GuidedScanEffect.Done })
        assertFalse(controller.shoot(doubtfulR).any { it is GuidedScanEffect.Done })
        assertEquals(1, controller.repromptCount)
        val phase = GuidedScanFlow.phaseOf(controller, quickCheck = true)
        assertTrue(phase.retake)
        assertEquals(
            dev.cubentia.domain.recognition
                .ScanStep(Color.RED, Color.WHITE),
            phase.step,
        )
        assertEquals(RepromptProgress(1, 1), phase.again)
        // A 6-face scan's re-prompt keeps its own prompt.
        assertFalse(GuidedScanFlow.phaseOf(controller).retake)
    }

    @Test fun `a confident re-capture is kept and the quick check verifies`() {
        val controller = quickCheckController()
        controller.shoot(quickU)
        controller.shoot(doubtfulR)
        val done = controller.shoot(quickR).filterIsInstance<GuidedScanEffect.Done>().single()
        assertEquals(2, done.samples.size)
        val partial = GuidedScanFlow.quickCheckOf(done.samples, CalibrationCenters(deviceLabs))
        assertEquals(setOf(Face.U, Face.R), partial.faces.keys)
        assertEquals(dev.cubentia.domain.tutor.Certainty.VERIFIED, deviceCheck(partial).certainty)
    }

    @Test fun `a second doubtful capture ends the quick check with no evidence for that face`() {
        val controller = quickCheckController()
        controller.shoot(quickU)
        controller.shoot(doubtfulR)
        val done = controller.shoot(doubtfulR).filterIsInstance<GuidedScanEffect.Done>().single()
        assertTrue(controller.done)
        assertNull(controller.step)
        assertEquals(setOf(Face.U), GuidedScanFlow.quickCheckOf(done.samples, CalibrationCenters(deviceLabs)).faces.keys)
    }

    @Test fun `a quick check face whose center reads another color is asked once more`() {
        val wrongFace = quickR.copy(stickers = quickR.stickers.toMutableList().also { it[4] = deviceLabs.getValue(Color.GREEN) })
        val controller = quickCheckController()
        controller.shoot(quickU)
        controller.shoot(wrongFace)
        val phase = GuidedScanFlow.phaseOf(controller, quickCheck = true)
        assertTrue(phase.retake)
        assertEquals(
            dev.cubentia.domain.recognition
                .ScanStep(Color.RED, Color.WHITE),
            phase.step,
        )
    }

    @Test fun `a quick check asks each doubtful face at most once, two at most in all`() {
        val doubtfulU = quickU.copy(stickers = quickU.stickers.toMutableList().also { it[0] = Lab(87.5, -2.5, 40.0) })
        val controller = quickCheckController()
        controller.shoot(doubtfulU)
        controller.shoot(doubtfulR)
        assertEquals(2, controller.repromptCount)
        controller.shoot(doubtfulU)
        val done = controller.shoot(doubtfulR).filterIsInstance<GuidedScanEffect.Done>().single()
        assertTrue(GuidedScanFlow.quickCheckOf(done.samples, CalibrationCenters(deviceLabs)).faces.isEmpty())
        assertTrue(controller.shoot(quickR).isEmpty())
    }

    @Test fun `confident quick check faces are not asked again`() {
        assertTrue(GuidedScanFlow.quickCheckRetakes(listOf(quickU, quickR), CalibrationCenters(deviceLabs)).isEmpty())
    }

    @Test fun `device quick check 4 re-captures L whose stickers 5 and 7 read uncertain in the scan's own light`() {
        // diag-s24-107-r2: F:c=G/up=W/o=90/read=BGYBGOGBO/unc=- L:c=O/up=W/o=90/read=YYGOORRRB/unc=5,7 (0-based pixel
        // order, 4 = the center) was kept and VERIFIED, as assembly read L against the saved colors only. L's orange
        // center reads nearer red in this light, so its red stickers 5 and 7 are close calls against it.
        val center = Lab(57.0, 53.0, 57.0)
        val redNearOrange = Lab(49.0, 61.0, 49.0)
        val f = deviceSample(Face.F, Color.WHITE, "BGYBGOGBO")
        val l = deviceSample(Face.L, Color.WHITE, "YYGOORRRB", center = center)
        val doubtfulL =
            l.copy(
                stickers =
                    l.stickers.toMutableList().also {
                        it[5] = redNearOrange
                        it[7] = redNearOrange
                    },
            )
        val saved = CalibrationCenters(deviceLabs)
        val trace =
            dev.cubentia.domain.recognition.ScanAssembler.trace(
                listOf(f, doubtfulL),
                (
                    dev.cubentia.domain.recognition.SelfCalibration
                        .from(listOf(f, doubtfulL), saved)
                        as dev.cubentia.domain.recognition.SelfCalibration.Calibrated
                ).calibration,
            )
        assertEquals(listOf(5, 7), trace.samples[1].uncertain)
        assertEquals(listOf(Face.L), GuidedScanFlow.quickCheckRetakes(listOf(f, doubtfulL), saved))
        assertEquals(setOf(Face.F), GuidedScanFlow.quickCheckOf(listOf(f, doubtfulL), saved).faces.keys)
        // The re-capture of L, its stickers clear of the center, is kept and the check verifies as on the device.
        val partial = GuidedScanFlow.quickCheckOf(listOf(f, l), saved)
        assertEquals(colorsOf("GBBBGGOOY"), partial.faces.getValue(Face.F))
        assertEquals(colorsOf("ROYROYBRG"), partial.faces.getValue(Face.L))
        val expected = "BRBYWGOWOYORWROGBGGBBBGGOOYWWRWYRWYRROYROYBRGYGWBBYWGO"
        assertEquals(
            dev.cubentia.domain.tutor.Certainty.VERIFIED,
            deviceCheck(partial, expected, setOf(Face.F, Face.L)).certainty,
        )
    }
}
