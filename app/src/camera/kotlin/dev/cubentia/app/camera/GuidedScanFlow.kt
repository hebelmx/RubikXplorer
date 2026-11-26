package dev.cubentia.app.camera

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CalibrationOffer
import dev.cubentia.domain.recognition.CalibrationSession
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.FrameQuality
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.GuidedScanController
import dev.cubentia.domain.recognition.GuidedScanEffect
import dev.cubentia.domain.recognition.GuidedScanPlan
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.PhysicalObservation
import dev.cubentia.domain.recognition.ScanAssembler
import dev.cubentia.domain.recognition.ScanResult
import dev.cubentia.domain.recognition.ScanReview
import dev.cubentia.domain.recognition.ScanStep
import dev.cubentia.domain.recognition.SelfCalibration
import dev.cubentia.domain.recognition.StickerClassification
import dev.cubentia.domain.recognition.StickerClassifier
import dev.cubentia.domain.recognition.StickerConfidence
import kotlin.math.sqrt

/** The Guided Scan Activity's decisions, kept pure so they are unit-tested without a camera or a permission dialog. */
internal object GuidedScanFlow {
    /**
     * Only whole posed faces with every non-center sticker CONFIDENT are evidence. No Expected state participates. The
     * stickers are read as every scan and its diagnostics read them ([calibrationFor]); the center is judged against
     * [saved] ([centerAgrees]). With [runScan] (GH #172), [saved] is the run's start-scan centers, not a saved
     * calibration, and no face is evidence unless the light is unchanged ([lightUnchanged]).
     */
    fun quickCheckOf(
        samples: List<FaceSample>,
        saved: CalibrationCenters,
        runScan: Boolean = false,
    ): PhysicalObservation.Partial {
        if (runScan && !lightUnchanged(samples, saved)) return PhysicalObservation.Partial(emptyMap())
        val result =
            ScanAssembler
                .assemble(samples, calibrationFor(samples, saved))
        if (result !is ScanResult.Partial) {
            return PhysicalObservation
                .Partial(
                    emptyMap(),
                )
        }
        val centerAgrees = centerAgrees(samples, saved)
        return result.observation.copy(
            faces =
                result.observation.faces.filterKeys { face ->
                    centerAgrees[Color.home(face)] == true && confident(result, face)
                },
        )
    }

    /**
     * The Quick Check faces to capture once more (PO 2026-11-17), in sample order: a face with an UNCERTAIN non-center
     * sticker, or whose center reads a color other than the face asked for (the wrong face was held). The controller
     * asks for each once, with its named top; a re-capture still doubtful is then no evidence ([quickCheckOf]). With
     * [runScan] and changed light ([lightUnchanged]), every captured face is doubtful.
     */
    fun quickCheckRetakes(
        samples: List<FaceSample>,
        saved: CalibrationCenters,
        runScan: Boolean = false,
    ): List<Face> {
        if (runScan && !lightUnchanged(samples, saved)) return samples.map { it.face }.distinct()
        val result = ScanAssembler.assemble(samples, calibrationFor(samples, saved)) as? ScanResult.Partial ?: return emptyList()
        val centerAgrees = centerAgrees(samples, saved)
        return samples
            .map { it.face }
            .distinct()
            .filter { face ->
                centerAgrees[Color.home(face)] == false ||
                    (face in result.observation.faces && !confident(result, face))
            }
    }

    /**
     * Self-calibration over [saved], as the 6-face scan and the debug `assembly` line use (device 10.7 round 2: the line
     * showed L's stickers 5 and 7 UNCERTAIN against the captured orange center, while assembly against [saved] alone read
     * them CONFIDENT and kept L). A captured center replaces its saved color, so the stickers are read in this light.
     */
    private fun calibrationFor(
        samples: List<FaceSample>,
        saved: CalibrationCenters,
    ): CalibrationCenters = (SelfCalibration.from(samples, saved) as? SelfCalibration.Calibrated)?.calibration ?: saved

    /**
     * The run-scan colors a finished Guided 6-face Scan returns (GH #172): its own six centers from [SelfCalibration]
     * with no saved calibration, or null when a calibration is [saved] or the scan cannot calibrate itself (6 faces then).
     */
    fun runScanColorsOf(
        samples: List<FaceSample>,
        saved: CalibrationCenters?,
    ): CalibrationCenters? = if (saved == null) (SelfCalibration.from(samples) as? SelfCalibration.Calibrated)?.calibration else null

    /**
     * Lighting guard for a run-scan reference (GH #172): a captured center self-calibrates its own color, but the other
     * colors still come from the start scan, so the light counts as unchanged only when at least one captured plain
     * center exists and every one is within [StickerClassifier.CLOSE_CALL_DELTA_E] (ΔE76) of [reference]'s center of
     * the color it claims. A logo-only capture has no probe, so it is doubtful.
     */
    private fun lightUnchanged(
        samples: List<FaceSample>,
        reference: CalibrationCenters,
    ): Boolean {
        val probes =
            samples
                .filter { it.centerPlain != false }
                .map { sample -> Triple(sample.centerColor, sample.center, sample.centerColor?.let { reference.centers[it] }) }
        return probes.isNotEmpty() &&
            probes.all { (color, center, expected) ->
                color != null && center != null && expected != null && deltaE76(center, expected) < StickerClassifier.CLOSE_CALL_DELTA_E
            }
    }

    private fun deltaE76(
        left: Lab,
        right: Lab,
    ): Double {
        val dl = left.l - right.l
        val da = left.a - right.a
        val db = left.b - right.b
        return sqrt(dl * dl + da * da + db * db)
    }

    /** Every non-center sticker of [face] is CONFIDENT; the center is judged by [centerAgrees] instead. */
    private fun confident(
        result: ScanResult.Partial,
        face: Face,
    ): Boolean =
        result.confidence[face]?.let {
            it.size == 9 &&
                it.withIndex().all { (index, confidence) ->
                    index == FaceSample.CENTER_INDEX || confidence == StickerConfidence.CONFIDENT
                }
        } == true

    // The center's color is fixed by the slot the sample claims, so only its nearest color matters (it must not read
    // another color, which would mean the wrong face was held; a logo center is exempt, as in assembly): a close call
    // on the center (device 10.7: a red center near orange under other light) must not drop the face.
    private fun centerAgrees(
        samples: List<FaceSample>,
        saved: CalibrationCenters,
    ): Map<Color, Boolean> =
        samples
            .mapNotNull { sample -> sample.centerColor?.let { it to sample } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size == 1 }
            .mapValues { (color, claims) ->
                val sample = claims.single()
                sample.centerPlain == false ||
                    sample.center?.let { center ->
                        (StickerClassifier.classify(center, saved) as? StickerClassification.Classified)?.reading?.color == color
                    } == true
            }

    fun withCorrections(
        review: ScanReview,
        corrections: Map<Int, dev.cubentia.domain.cube.Color>,
    ): ScanReview = review.corrected(corrections)

    /**
     * The stickers a fallback to the Editor carries from [phase] (GH #321): the review's (the Learner's fixes included),
     * also from a retake or calibration opened from review; else the stickers [ScanReview.fromSamples] reads from the
     * viewfinder's captured and kept faces or Case capture's corner view's captured ones, with [saved]. Null with no captured face,
     * and for the rationale, denial and unavailable cards and the continuous Quick Scan (whose stickers come from its
     * exported hand-off instead). Never filled from an expected state (AD-5).
     */
    fun fallbackStickers(
        phase: GuidedScanPhase,
        saved: CalibrationCenters?,
    ): List<Color?>? {
        fun read(samples: List<FaceSample>) = samples.takeIf { it.isNotEmpty() }?.let { ScanReview.fromSamples(it, saved).stickers }
        return when (phase) {
            is GuidedScanPhase.Review -> {
                phase.review.stickers
            }

            is GuidedScanPhase.Calibration -> {
                phase.fromReview?.review?.stickers
            }

            is GuidedScanPhase.Viewfinder -> {
                phase.fromReview?.review?.stickers ?: read(phase.kept + phase.captured)
            }

            is GuidedScanPhase.CornerViewfinder -> {
                read(phase.captured)
            }

            GuidedScanPhase.Continuous, GuidedScanPhase.Rationale, is GuidedScanPhase.Denied, GuidedScanPhase.Unavailable -> {
                null
            }
        }
    }

    /** Granted → the viewfinder; otherwise the rationale card comes before any system dialog. */
    fun initialPhase(hasPermission: Boolean): GuidedScanPhase =
        if (hasPermission) GuidedScanPhase.Viewfinder() else GuidedScanPhase.Rationale

    /**
     * The phase after the system permission dialog. A denial is permanent (offer *Open settings*) only when the system
     * will no longer show a rationale ([rationaleAfter] false) AND either it would have shown one just before the dialog
     * ([rationaleBefore]: this denial is what made it permanent) or a denial was recorded in an earlier scan
     * ([deniedBefore]: the dialog was already blocked). On Android 11+ dismissing the first-ever dialog also reads as
     * "no rationale" with neither set, and that must not offer *Open settings*.
     */
    fun afterPermission(
        granted: Boolean,
        rationaleBefore: Boolean,
        rationaleAfter: Boolean,
        deniedBefore: Boolean,
    ): GuidedScanPhase =
        if (granted) {
            GuidedScanPhase.Viewfinder()
        } else {
            GuidedScanPhase.Denied(permanent = !rationaleAfter && (rationaleBefore || deniedBefore))
        }

    /** What the viewfinder shows for the controller's current state; a [quickCheck]'s re-prompt round is a retake. */
    fun phaseOf(
        controller: GuidedScanController,
        quickCheck: Boolean = false,
    ): GuidedScanPhase.Viewfinder =
        GuidedScanPhase.Viewfinder(
            stepIndex = controller.stepIndex,
            captured = controller.captured,
            coaching = controller.coaching,
            canUseAnyway = controller.canUseAnyway,
            step = controller.step,
            again =
                if (controller.repeating) {
                    RepromptProgress(controller.repromptIndex + 1, controller.repromptCount)
                } else {
                    null
                },
            total = controller.planSize,
            retake = quickCheck && controller.repeating,
        )

    fun phaseOf(case: dev.cubentia.domain.recognition.CaseCaptureController): GuidedScanPhase.CornerViewfinder =
        GuidedScanPhase.CornerViewfinder(
            hold = case.hold.coerceAtMost(case.corners),
            coaching = case.coaching,
            prompt = case.prompt,
            totalHolds = case.corners,
        )

    /**
     * The torch control [phase] shows: the Learner's choice [torchOn] in the continuous Quick Scan (Story 10.32) and in
     * the calibration phase (Story 10.21) on a camera with a flash unit; null (no control, no torch copy) without a flash
     * unit, before the camera is known, or in any other phase (Case capture, the Guided grid and the hand-over included).
     */
    fun torchControl(
        phase: GuidedScanPhase,
        hasFlash: Boolean?,
        torchOn: Boolean,
    ): Boolean? =
        torchOn.takeIf {
            hasFlash == true &&
                (phase == GuidedScanPhase.Continuous || phase is GuidedScanPhase.Calibration)
        }

    /**
     * Story 10.21 (PO clarification 2): the calibration phase's torch toggle is locked (disabled, still shown) from the
     * first kept centre swatch until the calibration is saved or left, so every swatch is sampled under one torch state.
     */
    fun torchLocked(phase: GuidedScanPhase): Boolean = phase is GuidedScanPhase.Calibration && phase.session.kept.isNotEmpty()

    /**
     * Story 10.21: whether the calibration phase's *Keep* may keep the shown swatch: not while the torch it wants
     * ([torchLit] of [phase], [hasFlash], [torchOn]) differs from the one the camera last [applied] (an `enableTorch` still
     * pending or failed), so no swatch is kept, and the toggle locked, under a torch tag that does not match its light.
     * Always true outside the calibration phase.
     */
    fun keepAllowed(
        phase: GuidedScanPhase,
        hasFlash: Boolean?,
        torchOn: Boolean,
        applied: Boolean?,
    ): Boolean = phase !is GuidedScanPhase.Calibration || torchLit(phase, hasFlash, torchOn) == (applied == true)

    /**
     * Story 10.21: the torch tag a calibration is saved with: the torch actually lit, i.e. wanted ([torchOn]) on a camera
     * with a flash unit ([hasFlash]) and [applied] by the camera; no flash or a pending or failed torch is off.
     */
    fun savedTorchTag(
        hasFlash: Boolean?,
        torchOn: Boolean,
        applied: Boolean?,
    ): Boolean = hasFlash == true && torchOn && applied == true

    /**
     * Whether the torch is wanted on in [phase]: only in the continuous Quick Scan or the calibration phase (Story 10.21),
     * with a flash unit, when chosen.
     */
    fun torchLit(
        phase: GuidedScanPhase,
        hasFlash: Boolean?,
        torchOn: Boolean,
    ): Boolean = torchControl(phase, hasFlash, torchOn) == true

    /**
     * The review of a finished scan. [blanks] (Story 10.21, Quick Scan) are the kept corner faces' gated-uncertain
     * stickers, left blank and pre-selected.
     */
    fun reviewOf(
        effects: List<GuidedScanEffect>,
        saved: CalibrationCenters?,
        blanks: Map<Face, Set<Int>> = emptyMap(),
    ): GuidedScanPhase.Review? =
        effects.filterIsInstance<GuidedScanEffect.Done>().firstOrNull()?.let {
            val review = ScanReview.fromSamples(it.samples, saved, blanks)
            GuidedScanPhase.Review(
                review,
                CalibrationOffer
                    .offered(it.samples, saved, review),
                samples = it.samples,
                calibration = saved,
            )
        }

    /** A retake's plan (Story 10.16): the fixed Guided Scan order filtered to [faces], each prompt naming only its center. */
    fun retakePlan(faces: Set<Face>): List<ScanStep> = GuidedScanPlan.steps.filter { it.face in faces }

    /**
     * A retake of [faces] from [from]: a controller for only those faces, any rotation, never a named-top re-prompt.
     * Its controller is touched on the analyzer thread only.
     */
    fun retakeRun(
        faces: Set<Face>,
        from: GuidedScanPhase.Review,
    ): RetakeRun = RetakeRun(faces, from, GuidedScanController(plan = retakePlan(faces), reprompt = { emptyList() }))

    /**
     * The viewfinder of a retake [run]: its controller's step and "Face n of N" over the faces retaken, with the kept
     * faces' samples shown as captured, and the review to restore on back-out. The kept faces also carry the review's
     * stickers (Story 10.32): a Quick Scan review has no sample for a face the continuous scan mapped.
     */
    fun retakePhase(run: RetakeRun): GuidedScanPhase.Viewfinder =
        phaseOf(run.controller).copy(
            kept = run.from.samples.filter { it.face !in run.faces },
            fromReview = run.from,
            keptColors =
                (Face.entries - run.faces).associateWith { face ->
                    run.from.review.stickers
                        .subList(face.ordinal * 9, face.ordinal * 9 + 9)
                },
        )

    /**
     * The review after a retake from [before] whose [retaken] samples are all captured: kept faces exactly as reviewed
     * (edits included), retaken faces from the new capture only ([ScanReview.retaken]); corrections on retaken faces are
     * dropped, the review counts as edited (so *Close* asks), the calibration offer is recomputed over the merged samples,
     * and those merged samples are what a later retake starts from. By default it reads with the calibration [before] was
     * read with (Story 10.21: none after a Quick Scan mismatch, not the saved one the scan rejected).
     */
    fun reviewAfterRetake(
        before: GuidedScanPhase.Review,
        retaken: List<FaceSample>,
        saved: CalibrationCenters? = before.calibration,
    ): GuidedScanPhase.Review {
        val faces = retaken.map { it.face }.toSet()
        val samples = ScanReview.mergedSamples(before.samples, retaken)
        val review = ScanReview.retaken(before.review, retaken, before.samples, saved)
        return before.copy(
            review = review,
            offerCalibration = CalibrationOffer.offered(samples, saved, review),
            edited = true,
            corrections = before.corrections.filterKeys { Face.entries[it / 9] !in faces },
            samples = samples,
        )
    }

    fun calibrationCapture(
        phase: GuidedScanPhase.Calibration,
        stats: FrameStats,
        ring: CellReading?,
    ): GuidedScanPhase.Calibration {
        val quality =
            FrameQuality
                .assessShutter(stats)
        return if (quality == CaptureQuality.ACCEPTABLE) {
            phase.copy(session = phase.session.offer(ring), coaching = null)
        } else {
            phase.copy(coaching = GuidedScanController.coachingFor(quality, stats.framing))
        }
    }

    /** True when [effects] ask for AF/AE/AWB on the grid. */
    fun remeters(effects: List<GuidedScanEffect>): Boolean = GuidedScanEffect.Remeter in effects

    /** The scan's result when [effects] finish it, else null. */
    fun resultOf(
        effects: List<GuidedScanEffect>,
        saved: CalibrationCenters? = null,
    ): ScanReview? = effects.filterIsInstance<GuidedScanEffect.Done>().firstOrNull()?.let { ScanReview.fromSamples(it.samples, saved) }
}

/** A retake from review in progress (Story 10.16): the [faces] asked for, the review it started [from], its [controller]. */
internal class RetakeRun(
    val faces: Set<Face>,
    val from: GuidedScanPhase.Review,
    val controller: GuidedScanController,
)

/**
 * The capture freeze (PO 2026-11-16): after a face is captured the captured frame stays on screen for [durationMs], and
 * no capture decision (frame, shutter or *Use this face anyway*) runs until it ends. Touched on the analyzer thread only.
 */
internal class CaptureFreeze(
    private val durationMs: Long = FREEZE_MS,
) {
    private var until: Long? = null

    fun isFrozen(nowMs: Long): Boolean = until?.let { nowMs < it } == true

    fun start(nowMs: Long) {
        until = nowMs + durationMs
    }

    /** Runs [decision] unless frozen at [nowMs]; a capture among its effects starts a new freeze. */
    fun decide(
        nowMs: Long,
        decision: () -> List<GuidedScanEffect>,
    ): List<GuidedScanEffect> {
        if (isFrozen(nowMs)) return emptyList()
        return decision().also { effects -> if (effects.any { it is GuidedScanEffect.Capture }) until = nowMs + durationMs }
    }

    companion object {
        /** How long the captured frame stays frozen before it shrinks into the mini net. */
        const val FREEZE_MS = 400L
    }
}

/** Analyzer-thread hold, restarted for each immutable swatch session, including retries. */
internal class CalibrationHold {
    private var session: CalibrationSession? = null
    private var controller: GuidedScanController? = null

    val coaching get() = controller?.coaching

    fun onFrame(
        current: CalibrationSession,
        stats: FrameStats,
        rotation: Int,
        now: Long,
    ): List<GuidedScanEffect> {
        if (session !== current) {
            session = current
            controller =
                current.current?.let {
                    GuidedScanController(
                        plan =
                            listOf(
                                dev.cubentia.domain.recognition
                                    .ScanStep(it),
                            ),
                        reprompt = { emptyList() },
                    )
                }
        }
        if (current.pending != null) return emptyList()
        return controller?.onFrame(stats, rotation, now).orEmpty()
    }
}
