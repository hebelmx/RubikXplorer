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
import dev.cubentia.domain.recognition.Suspect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test fun `publishing without corrections keeps the scan evidence, suspects and the top-suspect selection`() {
        val labs =
            mapOf(
                Color.GREEN to Lab(55.0, -60.0, 40.0),
                Color.RED to Lab(45.0, 65.0, 45.0),
                Color.BLUE to Lab(40.0, 10.0, -50.0),
                Color.ORANGE to Lab(65.0, 45.0, 65.0),
                Color.WHITE to Lab(90.0, 0.0, 0.0),
                Color.YELLOW to Lab(85.0, -5.0, 80.0),
            )
        // Solved with L's sticker 38 read yellow (10 yellow, 8 orange); it measures between yellow and orange.
        val colors =
            dev.cubentia.domain.cube.CubeState.SOLVED
                .toFacelets()
                .colors
                .toMutableList()
                .also { it[38] = Color.YELLOW }
        val review =
            ScanReview.from(
                dev.cubentia.domain.recognition.ScanResult.Full(
                    dev.cubentia.domain.cube
                        .Facelets(colors),
                    List(54) { dev.cubentia.domain.recognition.StickerConfidence.CONFIDENT },
                    colors.mapIndexed { i, color -> if (i == 38) Lab(77.0, 15.0, 74.0) else labs.getValue(color) },
                ),
                CalibrationCenters(labs),
            )
        assertEquals(listOf(Suspect(38, Color.ORANGE)), review.suspects)
        assertEquals(38, review.selected)
        val published = GuidedScanFlow.withCorrections(review, emptyMap())
        assertEquals(review.evidence, published.evidence)
        assertEquals(review.suspects, published.suspects)
        assertEquals(38, published.selected)
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
    fun `a last capture after the camera became Unavailable neither freezes nor holds the review`() {
        val vm = GuidedScanViewModel()
        val done = ScanReview.fromSamples(List(6) { sample() })
        vm.phase = GuidedScanPhase.Unavailable
        var snapshots = 0
        assertFalse(vm.onCaptured(Face.B) { null.also { snapshots++ } })
        assertNull(vm.frozen)
        assertEquals(0, snapshots)
        vm.publish(done)
        assertEquals(GuidedScanPhase.Review(done), vm.phase)
    }

    @Test
    fun `a capture in the viewfinder freezes and holds the review until its freeze ends`() {
        val vm = GuidedScanViewModel()
        val done = ScanReview.fromSamples(List(6) { sample() })
        vm.phase = GuidedScanPhase.Viewfinder()
        assertTrue(vm.onCaptured(Face.B) { null })
        val frozen = checkNotNull(vm.frozen)
        assertEquals(Face.B, frozen.face)
        vm.publish(done)
        assertEquals(GuidedScanPhase.Viewfinder(), vm.phase)
        vm.endFreeze(frozen.id)
        assertNull(vm.frozen)
        assertEquals(GuidedScanPhase.Review(done), vm.phase)
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

    // Story 10.16: retake faces from the scan review.

    private val retakeLabs =
        mapOf(
            Color.WHITE to Lab(90.0, 0.0, 0.0),
            Color.YELLOW to Lab(85.0, -5.0, 80.0),
            Color.GREEN to Lab(55.0, -60.0, 40.0),
            Color.BLUE to Lab(40.0, 10.0, -50.0),
            Color.RED to Lab(45.0, 65.0, 45.0),
            Color.ORANGE to Lab(65.0, 45.0, 65.0),
        )

    private val scrambled =
        dev.cubentia.domain.cube.CubeState.SOLVED
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
                ),
            ).toFacelets()

    private fun upright(
        slot: Face,
        colors: List<Color> = scrambled.colors.subList(slot.ordinal * 9, slot.ordinal * 9 + 9),
    ) = FaceSample(
        face = slot,
        orientationDegrees = 0,
        stickers = colors.map(retakeLabs::getValue),
        quality = CaptureQuality.ACCEPTABLE,
        centerColor = Color.home(slot),
    )

    /** A review of [scrambled] whose F face was misread all green, with one edit on U (0) and one on F (18). */
    private fun misreadReview(): GuidedScanPhase.Review {
        val samples = Face.entries.map { if (it == Face.F) upright(it, List(9) { Color.GREEN }) else upright(it) }
        val first = checkNotNull(GuidedScanFlow.reviewOf(listOf(GuidedScanEffect.Done(samples)), null))
        val corrections = mapOf(0 to Color.YELLOW, 18 to Color.BLUE)
        return first.copy(review = first.review.corrected(corrections), corrections = corrections, edited = true)
    }

    @Test fun `a retake asks only the chosen faces in Guided Scan order, each naming only its center`() {
        val plan = GuidedScanFlow.retakePlan(setOf(Face.D, Face.U, Face.L, Face.F))
        assertEquals(listOf(Color.GREEN, Color.ORANGE, Color.WHITE, Color.YELLOW), plan.map { it.centerColor })
        assertTrue(plan.all { it.upNeighborColor == null })
    }

    @Test fun `a retake run counts its own faces, shows kept faces as captured and never re-prompts`() {
        val from = misreadReview()
        val run = GuidedScanFlow.retakeRun(setOf(Face.R, Face.F), from)
        val phase = GuidedScanFlow.retakePhase(run)
        assertEquals(2, phase.total)
        assertEquals(0, phase.stepIndex)
        assertEquals(Color.GREEN, phase.step?.centerColor)
        assertNull(phase.step?.upNeighborColor)
        assertEquals(setOf(Face.U, Face.D, Face.L, Face.B), phase.kept.map { it.face }.toSet())
        assertTrue(phase.fromReview === from)
        // A misread pair of faces still finishes after two captures, with no named-top round.
        assertFalse(run.controller.shoot(upright(Face.F, List(9) { Color.RED })).any { it is GuidedScanEffect.Done })
        assertEquals(1, GuidedScanFlow.retakePhase(run).stepIndex)
        val done =
            run.controller
                .shoot(upright(Face.R, List(9) { Color.YELLOW }))
                .filterIsInstance<GuidedScanEffect.Done>()
                .single()
        assertEquals(listOf(Face.F, Face.R), done.samples.map { it.face })
        assertEquals(0, run.controller.repromptCount)
    }

    @Test fun `the review after a retake keeps kept faces and edits, replaces retaken ones and counts as edited`() {
        val before = misreadReview()
        val after = GuidedScanFlow.reviewAfterRetake(before, listOf(upright(Face.F).copy(orientationDegrees = 270)), null)
        (Face.entries - Face.F).flatMap { face -> face.ordinal * 9 until face.ordinal * 9 + 9 }.forEach {
            assertEquals(before.review.stickers[it], after.review.stickers[it])
        }
        assertEquals(Color.YELLOW, after.review.stickers[0])
        assertEquals(scrambled.colors.subList(18, 27), after.review.stickers.subList(18, 27))
        assertEquals(mapOf(0 to Color.YELLOW), after.corrections)
        assertTrue(after.edited)
        assertEquals(Face.entries.toList(), after.samples.map { it.face })
        assertEquals(270, after.samples[Face.F.ordinal].orientationDegrees)
        assertEquals(before.initialStickers, after.initialStickers)
    }

    @Test fun `a fresh review is never edited, so only a completed retake makes Close ask`() {
        val samples = Face.entries.map { upright(it) }
        val fresh = checkNotNull(GuidedScanFlow.reviewOf(listOf(GuidedScanEffect.Done(samples)), null))
        assertFalse(fresh.edited)
        assertEquals(samples, fresh.samples)
        assertTrue(GuidedScanFlow.reviewAfterRetake(fresh, listOf(upright(Face.B)), null).edited)
    }

    @Test fun `a fallback carries the review's stickers with its fixes, also from a retake or calibration opened from it`() {
        val review = misreadReview()
        val stickers = review.review.stickers
        assertEquals(Color.YELLOW, stickers[0])
        assertEquals(stickers, GuidedScanFlow.fallbackStickers(review, null))
        assertEquals(stickers, GuidedScanFlow.fallbackStickers(GuidedScanPhase.Calibration(fromReview = review), null))
        val retake = GuidedScanFlow.retakePhase(GuidedScanFlow.retakeRun(setOf(Face.F), review))
        assertEquals(stickers, GuidedScanFlow.fallbackStickers(retake, null))
    }

    @Test fun `a fallback before review reads the captured and kept faces, never filling the rest`() {
        val captured = listOf(upright(Face.U), upright(Face.R))
        val read = GuidedScanFlow.fallbackStickers(GuidedScanPhase.Viewfinder(captured = captured), null)
        assertEquals(ScanReview.fromSamples(captured, null).stickers, read)
        assertEquals(
            ScanReview.fromSamples(captured, null).stickers,
            GuidedScanFlow.fallbackStickers(GuidedScanPhase.Viewfinder(kept = captured.take(1), captured = captured.drop(1)), null),
        )
        assertEquals(
            ScanReview.fromSamples(captured, null).stickers,
            GuidedScanFlow.fallbackStickers(GuidedScanPhase.CornerViewfinder(hold = 1, captured = captured), null),
        )
        // A partial scan cannot pose faces: every non-center sticker is unset, centers stay home colors.
        checkNotNull(read).forEachIndexed { i, color ->
            assertEquals(if (i % 9 == 4) Color.home(Face.entries[i / 9]) else null, color)
        }
    }

    @Test fun `a calibration opened from Quick Scan's corner view reads the corner faces it returns to`() {
        val captured = listOf(upright(Face.U), upright(Face.R))
        val corner = GuidedScanPhase.CornerViewfinder(hold = 2, captured = captured, faceByFace = true)
        val calibration = GuidedScanPhase.Calibration(fromQuickScan = true)
        assertEquals(ScanReview.fromSamples(captured, null).stickers, GuidedScanFlow.fallbackStickers(calibration, null, corner))
        // Its hand-over reads the kept and added faces the same way.
        val handOver = GuidedScanPhase.Viewfinder(kept = captured.take(1), captured = captured.drop(1), handOver = true)
        assertEquals(ScanReview.fromSamples(captured, null).stickers, GuidedScanFlow.fallbackStickers(calibration, null, handOver))
        // No corner face yet, or a calibration not opened from Quick Scan: no stickers.
        assertNull(GuidedScanFlow.fallbackStickers(calibration, null, GuidedScanPhase.CornerViewfinder(hold = 1)))
        assertNull(GuidedScanFlow.fallbackStickers(GuidedScanPhase.Calibration(), null, corner))
    }

    @Test fun `no captured face, a denial or the rationale carries no stickers`() {
        listOf(
            GuidedScanPhase.Viewfinder(),
            GuidedScanPhase.CornerViewfinder(hold = 0),
            GuidedScanPhase.Viewfinder(handOver = true),
            GuidedScanPhase.Rationale,
            GuidedScanPhase.Denied(permanent = false),
            GuidedScanPhase.Denied(permanent = true),
            GuidedScanPhase.Unavailable,
            GuidedScanPhase.Calibration(),
            GuidedScanPhase.Calibration(fromQuickScan = true),
        ).forEach { assertNull(it.toString(), GuidedScanFlow.fallbackStickers(it, null)) }
    }

    @Test fun `backing out of a retake restores the review it started from exactly`() {
        val vm = GuidedScanViewModel()
        val from = misreadReview()
        val run = GuidedScanFlow.retakeRun(setOf(Face.F), from)
        vm.retake = run
        vm.phase = GuidedScanFlow.retakePhase(run)
        run.controller.shoot(upright(Face.F))
        // The retake finished during its last capture's freeze: its review is held and its centers were measured.
        vm.frozen = FrozenCapture(9, Face.F, null)
        vm.publishRetake(GuidedScanFlow.reviewAfterRetake(from, listOf(upright(Face.F)), null))
        vm.measuredCenters = null
        vm.backOutOfRetake(from)
        assertTrue(vm.phase === from)
        assertNull(vm.frozen)
        vm.endFreeze(9)
        assertTrue(vm.phase === from)
        val expected = GuidedScanFlow.runScanColorsOf(from.samples, null)
        assertNotNull(expected)
        assertEquals(expected, vm.measuredCenters)
        vm.analyzerExecutor.submit {}.get()
        assertNull(vm.retake)
        assertTrue(vm.guidedController() === vm.controller)
    }

    @Test fun `a finished retake is held until the last capture's freeze ends`() {
        val vm = GuidedScanViewModel()
        val from = misreadReview()
        vm.phase = GuidedScanFlow.retakePhase(GuidedScanFlow.retakeRun(setOf(Face.F), from))
        val after = GuidedScanFlow.reviewAfterRetake(from, listOf(upright(Face.F)), null)
        vm.frozen = FrozenCapture(7, Face.F, null)
        vm.publishRetake(after)
        assertTrue(vm.phase is GuidedScanPhase.Viewfinder)
        vm.endFreeze(7)
        // Published as built: earlier corrections are not re-applied.
        assertTrue(vm.phase === after)
    }

    // Story 10.17: Quick Scan's corner view offers *Scan face by face*; its hand-over viewfinder counts only the asked faces.

    private fun cornerHold(index: Int): List<FrameStats> {
        val labs =
            mapOf(
                Color.WHITE to Lab(90.0, 0.0, 0.0),
                Color.GREEN to Lab(55.0, -60.0, 40.0),
                Color.RED to Lab(45.0, 65.0, 45.0),
                Color.YELLOW to Lab(85.0, -5.0, 80.0),
                Color.ORANGE to Lab(65.0, 45.0, 65.0),
                Color.BLUE to Lab(40.0, 10.0, -50.0),
            )
        return dev.cubentia.domain.recognition.QuickScanPlan.holds[index - 1].map { step ->
            FrameStats(List(9) { CellReading(labs.getValue(step.centerColor), true) }, 120.0, 0.0, 100.0, 12)
        }
    }

    @Test fun `quick scan's corner view offers face by face and its hand-over shows kept faces and Face n of N`() {
        val quick =
            dev.cubentia.domain.recognition
                .QuickScanController(lightCheck = false)
        val corner = GuidedScanFlow.phaseOf(quick) as GuidedScanPhase.CornerViewfinder
        assertTrue(corner.faceByFace)
        quick.onFrame(true, cornerHold(1), 0)
        quick.onShutter()
        val second = GuidedScanFlow.phaseOf(quick) as GuidedScanPhase.CornerViewfinder
        assertEquals(2, second.hold)
        assertTrue(second.faceByFace)
        assertNotNull(quick.switchToFaceByFace())
        val handOver = GuidedScanFlow.phaseOf(quick) as GuidedScanPhase.Viewfinder
        assertTrue(handOver.handOver)
        assertNull(handOver.again)
        assertFalse(handOver.retake)
        assertEquals(3, handOver.total)
        assertEquals(0, handOver.stepIndex)
        assertEquals(Color.BLUE, handOver.step?.centerColor)
        assertNull(handOver.step?.upNeighborColor)
        assertEquals(listOf(Face.U, Face.F, Face.R), handOver.kept.map { it.face })
        assertTrue(handOver.captured.isEmpty())
        // Back returns to the corner view at hold 2 with the kept faces.
        assertTrue(quick.backOut())
        val back = GuidedScanFlow.phaseOf(quick) as GuidedScanPhase.CornerViewfinder
        assertEquals(2, back.hold)
        assertEquals(listOf(Face.U, Face.F, Face.R), back.captured.map { it.face })
    }

    @Test fun `case capture's corner view never offers face by face`() {
        val case =
            dev.cubentia.domain.recognition
                .CaseCaptureController(CalibrationCenters(emptyMap()), 2)
        assertFalse(GuidedScanFlow.phaseOf(case).faceByFace)
    }

    // Story 10.19: the activity's torch wiring: the wanted torch reaches the controller, which re-meters once it applied.
    @Test fun `torch sync wants the choice only with a flash unit and re-meters once the camera applied it`() {
        val quick =
            dev.cubentia.domain.recognition
                .QuickScanController(lightCheck = false)
        GuidedScanFlow.syncTorch(quick, hasFlash = null, torchOn = true, applied = null, nowMs = 0)
        assertFalse("no flash known: no torch", quick.torchOn)
        GuidedScanFlow.syncTorch(quick, hasFlash = false, torchOn = true, applied = false, nowMs = 10)
        assertFalse("no flash unit: never a torch", quick.torchOn)
        assertFalse(quick.torchPending)
        GuidedScanFlow.syncTorch(quick, hasFlash = true, torchOn = true, applied = false, nowMs = 20)
        assertTrue(quick.torchOn)
        assertTrue("waits for the camera to light it", quick.torchPending)
        assertTrue(quick.onFrame(true, cornerHold(1), 30).isEmpty())
        GuidedScanFlow.syncTorch(quick, hasFlash = true, torchOn = true, applied = true, nowMs = 40)
        assertFalse(quick.torchPending)
        assertEquals(
            listOf(dev.cubentia.domain.recognition.QuickScanEffect.Remeter),
            quick.onFrame(true, cornerHold(1), 50),
        )
        GuidedScanFlow.syncTorch(quick, hasFlash = true, torchOn = false, applied = true, nowMs = 60)
        assertFalse(quick.torchOn)
        assertTrue(quick.torchPending)
    }

    @Test fun `the corner outline crosses to the domain as twelve plain points, none when no cube is detected`() {
        val quads = List(3) { quad -> List(4) { point -> CornerPoint(10.0 * quad + point, 100.0 + point) } }
        val frame = CornerFrame(true, List(3) { null }, frameQuads = quads)
        val outline = checkNotNull(GuidedScanFlow.outlineOf(frame))
        assertEquals(12, outline.size)
        assertEquals(
            dev.cubentia.domain.recognition
                .OutlinePoint(0.0, 100.0),
            outline.first(),
        )
        assertEquals(
            dev.cubentia.domain.recognition
                .OutlinePoint(23.0, 103.0),
            outline.last(),
        )
        assertEquals(null, GuidedScanFlow.outlineOf(frame.copy(detected = false)))
        assertEquals(null, GuidedScanFlow.outlineOf(CornerFrame(true, List(3) { null })))
    }

    @Test fun `a light fail, and the torch turning on during it, reach the hold's timeline`() {
        val quick =
            dev.cubentia.domain.recognition
                .QuickScanController()
        val dark = List(9) { CellReading(Lab(40.0, 0.0, 0.0), true) }
        val bright = List(9) { CellReading(Lab(90.0, 0.0, 0.0), true) }
        val stats = { cells: List<CellReading> -> FrameStats(cells, 120.0, 0.0, 100.0, 12) }
        // The first detected frame re-meters onto the cube and coaches nothing; the settled frame after it starts the fail.
        quick.onFrame(true, listOf(stats(bright), stats(dark), stats(bright)), 50, settled = false)
        assertFalse(quick.lightHeld)
        quick.onFrame(true, listOf(stats(bright), stats(dark), stats(bright)), 100, settled = true)
        assertTrue(quick.lightHeld)
        // PO 2026-11-21: the light check is skipped while the torch is lit, so lighting it clears the fail at once.
        GuidedScanFlow.syncTorch(quick, hasFlash = true, torchOn = true, applied = true, nowMs = 300)
        assertFalse(quick.lightHeld)
        val light =
            quick.drainEvents().filter {
                it.kind == dev.cubentia.domain.recognition.CornerHoldEventKind.LIGHT_FAIL ||
                    it.kind == dev.cubentia.domain.recognition.CornerHoldEventKind.LIGHT_CLEAR
            }
        assertEquals(
            listOf(
                dev.cubentia.domain.recognition.CornerHoldEventKind.LIGHT_FAIL,
                dev.cubentia.domain.recognition.CornerHoldEventKind.LIGHT_CLEAR,
            ),
            light.map {
                it.kind
            },
        )
        assertEquals(listOf(50L, 250L), light.map { it.elapsedMs })
        assertEquals("torch", light.last().reason)
        assertEquals(200L, light.last().durationMs)
    }

    // Story 10.19: the torch follows the Learner's choice in Quick Scan's corner view only, on a camera with a flash unit.
    @Test fun `the torch is lit only in quick scan's corner view with a flash and is kept through the hand-over and back`() {
        val quick =
            dev.cubentia.domain.recognition
                .QuickScanController(lightCheck = false)
        val first = GuidedScanFlow.phaseOf(quick)!!
        assertEquals(true, GuidedScanFlow.torchControl(first, hasFlash = true, torchOn = true))
        assertTrue(GuidedScanFlow.torchLit(first, hasFlash = true, torchOn = true))
        // The Learner's choice off: the control shows "Torch off" and the torch is dark.
        assertEquals(false, GuidedScanFlow.torchControl(first, hasFlash = true, torchOn = false))
        assertFalse(GuidedScanFlow.torchLit(first, hasFlash = true, torchOn = false))
        // No flash unit (or not known yet): no control and no torch.
        assertNull(GuidedScanFlow.torchControl(first, hasFlash = false, torchOn = true))
        assertNull(GuidedScanFlow.torchControl(first, hasFlash = null, torchOn = true))
        quick.onFrame(true, cornerHold(1), 0)
        quick.onShutter()
        val second = GuidedScanFlow.phaseOf(quick)!!
        assertTrue("carries from hold 1 to hold 2", GuidedScanFlow.torchLit(second, hasFlash = true, torchOn = true))
        quick.switchToFaceByFace()
        val handOver = GuidedScanFlow.phaseOf(quick)!!
        assertNull(GuidedScanFlow.torchControl(handOver, hasFlash = true, torchOn = true))
        assertFalse("physically off in the hand-over", GuidedScanFlow.torchLit(handOver, hasFlash = true, torchOn = true))
        assertTrue(quick.backOut())
        assertTrue(
            "back to the corner view: on again",
            GuidedScanFlow.torchLit(GuidedScanFlow.phaseOf(quick)!!, hasFlash = true, torchOn = true),
        )
        // Case capture's corner view and every other phase never light it.
        val case =
            dev.cubentia.domain.recognition
                .CaseCaptureController(CalibrationCenters(emptyMap()), 2)
        assertFalse(GuidedScanFlow.torchLit(GuidedScanFlow.phaseOf(case), hasFlash = true, torchOn = true))
        listOf(
            GuidedScanPhase.Viewfinder(),
            GuidedScanPhase.Rationale,
            GuidedScanPhase.Unavailable,
        ).forEach {
            assertFalse("$it", GuidedScanFlow.torchLit(it, hasFlash = true, torchOn = true))
        }
    }

    // Story 10.21: the calibration phase's torch toggle, its lock, its defaults and the saved torch tag.
    @Test fun `the calibration phase shows the shared torch toggle on a flash device, locked once a swatch is kept`() {
        val fresh = GuidedScanPhase.Calibration()
        assertEquals(true, GuidedScanFlow.torchControl(fresh, hasFlash = true, torchOn = true))
        assertTrue(GuidedScanFlow.torchLit(fresh, hasFlash = true, torchOn = true))
        assertEquals(false, GuidedScanFlow.torchControl(fresh, hasFlash = true, torchOn = false))
        assertFalse(GuidedScanFlow.torchLit(fresh, hasFlash = true, torchOn = false))
        // No flash unit: no control, the torch is off.
        assertNull(GuidedScanFlow.torchControl(fresh, hasFlash = false, torchOn = true))
        assertFalse(GuidedScanFlow.torchLit(fresh, hasFlash = false, torchOn = true))
        // Unlocked until the first Keep; locked from then on (until saved or left: a new phase starts unlocked).
        assertFalse(GuidedScanFlow.torchLocked(fresh))
        val offered = fresh.copy(session = fresh.session.offer(CellReading(Lab(55.0, -60.0, 40.0), true)))
        assertFalse("a swatch shown but not kept", GuidedScanFlow.torchLocked(offered))
        val kept = offered.copy(session = offered.session.keep())
        assertTrue(GuidedScanFlow.torchLocked(kept))
        assertTrue("still shown while locked", GuidedScanFlow.torchControl(kept, hasFlash = true, torchOn = true) == true)
        assertFalse(GuidedScanFlow.torchLocked(GuidedScanPhase.Viewfinder()))
    }

    @Test fun `a swatch is kept only once the camera applied the torch the calibration wants`() {
        val offered =
            GuidedScanPhase.Calibration().let { it.copy(session = it.session.offer(CellReading(Lab(55.0, -60.0, 40.0), true))) }
        assertTrue(GuidedScanFlow.keepAllowed(offered, hasFlash = true, torchOn = true, applied = true))
        assertFalse("enableTorch(true) pending", GuidedScanFlow.keepAllowed(offered, hasFlash = true, torchOn = true, applied = false))
        assertFalse("nothing applied yet", GuidedScanFlow.keepAllowed(offered, hasFlash = true, torchOn = true, applied = null))
        assertFalse("enableTorch(false) pending", GuidedScanFlow.keepAllowed(offered, hasFlash = true, torchOn = false, applied = true))
        assertTrue(GuidedScanFlow.keepAllowed(offered, hasFlash = true, torchOn = false, applied = false))
        assertTrue("no flash: the torch is off", GuidedScanFlow.keepAllowed(offered, hasFlash = false, torchOn = true, applied = null))
        assertTrue(GuidedScanFlow.keepAllowed(GuidedScanPhase.Viewfinder(), hasFlash = true, torchOn = true, applied = false))
    }

    @Test fun `a calibration opens with the corner view's torch choice from its offer and on otherwise`() {
        assertFalse(GuidedScanFlow.calibrationTorch(fromQuickScan = true, current = false))
        assertTrue(GuidedScanFlow.calibrationTorch(fromQuickScan = true, current = true))
        assertTrue(GuidedScanFlow.calibrationTorch(fromQuickScan = false, current = false))
    }

    @Test fun `a calibration is tagged with the torch actually lit, off without a flash or an applied torch`() {
        assertTrue(GuidedScanFlow.savedTorchTag(hasFlash = true, torchOn = true, applied = true))
        assertFalse("requested, not applied", GuidedScanFlow.savedTorchTag(hasFlash = true, torchOn = true, applied = false))
        assertFalse("pending", GuidedScanFlow.savedTorchTag(hasFlash = true, torchOn = true, applied = null))
        assertFalse(GuidedScanFlow.savedTorchTag(hasFlash = true, torchOn = false, applied = false))
        assertFalse("no flash", GuidedScanFlow.savedTorchTag(hasFlash = false, torchOn = true, applied = true))
        assertFalse(GuidedScanFlow.savedTorchTag(hasFlash = null, torchOn = true, applied = true))
    }

    @Test fun `quick scan review leaves a kept face's carried blank blank and pre-selects it`() {
        val labs =
            mapOf(
                Color.GREEN to Lab(55.0, -60.0, 40.0),
                Color.RED to Lab(45.0, 65.0, 45.0),
                Color.BLUE to Lab(40.0, 10.0, -50.0),
                Color.ORANGE to Lab(65.0, 45.0, 65.0),
                Color.WHITE to Lab(90.0, 0.0, 0.0),
                Color.YELLOW to Lab(85.0, -5.0, 80.0),
            )
        val samples =
            dev.cubentia.domain.recognition.QuickScanPlan.holds.flatten().map { step ->
                FaceSample(
                    face = step.face,
                    orientationDegrees = 0,
                    stickers = List(9) { labs.getValue(step.centerColor) },
                    quality = CaptureQuality.ACCEPTABLE,
                    centerColor = step.centerColor,
                    upNeighborColor = step.upNeighborColor,
                    centerPlain = true,
                )
            }
        val saved = CalibrationCenters(labs)
        val done = listOf(GuidedScanEffect.Done(samples))
        val plain = GuidedScanFlow.reviewOf(done, saved)!!.review
        val review = GuidedScanFlow.reviewOf(done, saved, mapOf(Face.R to setOf(0)))!!.review
        assertTrue(plain.stickers.none { it == null })
        val blank = review.stickers.indices.single { review.stickers[it] == null }
        assertEquals(Face.R, Face.entries[blank / 9])
        assertEquals(blank, review.selected)
        // Quick Scan's review reads with the calibration its Done carries: the matched one, or none after a mismatch.
        val matched =
            dev.cubentia.domain.recognition.QuickScanEffect
                .Done(samples, mapOf(Face.R to setOf(0)), saved)
        assertEquals(review, GuidedScanFlow.quickReviewOf(matched)!!.review)
        val mismatched =
            dev.cubentia.domain.recognition.QuickScanEffect
                .Done(samples, mapOf(Face.R to setOf(0)), calibration = null)
        assertEquals(
            GuidedScanFlow.reviewOf(done, null, mapOf(Face.R to setOf(0)))!!.review,
            GuidedScanFlow.quickReviewOf(mismatched)!!.review,
        )
    }

    @Test fun `a retake from a quick scan review read after a calibration mismatch reads as with no calibration`() {
        val labs =
            mapOf(
                Color.GREEN to Lab(55.0, -60.0, 40.0),
                Color.RED to Lab(45.0, 65.0, 45.0),
                Color.BLUE to Lab(40.0, 10.0, -50.0),
                Color.ORANGE to Lab(65.0, 45.0, 65.0),
                Color.WHITE to Lab(90.0, 0.0, 0.0),
                Color.YELLOW to Lab(85.0, -5.0, 80.0),
            )
        val samples =
            dev.cubentia.domain.recognition.QuickScanPlan.holds.flatten().map { step ->
                FaceSample(
                    face = step.face,
                    orientationDegrees = 0,
                    stickers = List(9) { labs.getValue(step.centerColor) },
                    quality = CaptureQuality.ACCEPTABLE,
                    centerColor = step.centerColor,
                    upNeighborColor = step.upNeighborColor,
                    centerPlain = true,
                )
            }
        // The saved calibration the scan rejected (its torch tag differs); the Guided review below reads with it.
        val saved =
            CalibrationCenters(
                labs + mapOf(Color.RED to labs.getValue(Color.ORANGE), Color.ORANGE to labs.getValue(Color.RED)),
                torchOn = true,
            )
        val review =
            checkNotNull(
                GuidedScanFlow.quickReviewOf(
                    dev.cubentia.domain.recognition.QuickScanEffect
                        .Done(samples, calibration = null),
                ),
            )
        assertNull(review.calibration)
        val retaken = listOf(samples.first { it.face == Face.F })
        val after = GuidedScanFlow.reviewAfterRetake(review, retaken)
        assertEquals(GuidedScanFlow.reviewAfterRetake(review, retaken, null), after)
        assertNull(after.calibration)
        // A Guided review keeps the saved calibration it was read with.
        assertEquals(saved, GuidedScanFlow.reviewOf(listOf(GuidedScanEffect.Done(samples)), saved)!!.calibration)
    }
}
