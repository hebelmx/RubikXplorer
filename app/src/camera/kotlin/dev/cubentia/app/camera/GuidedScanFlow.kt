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
import dev.cubentia.domain.recognition.PhysicalObservation
import dev.cubentia.domain.recognition.ScanAssembler
import dev.cubentia.domain.recognition.ScanResult
import dev.cubentia.domain.recognition.ScanReview
import dev.cubentia.domain.recognition.SelfCalibration
import dev.cubentia.domain.recognition.StickerClassification
import dev.cubentia.domain.recognition.StickerClassifier
import dev.cubentia.domain.recognition.StickerConfidence

/** The Guided Scan Activity's decisions, kept pure so they are unit-tested without a camera or a permission dialog. */
internal object GuidedScanFlow {
    /**
     * Only whole posed faces with every non-center sticker CONFIDENT are evidence. No Expected state participates. The
     * stickers are read as every scan and its diagnostics read them ([calibrationFor]); the center is judged against
     * [saved] ([centerAgrees]).
     */
    fun quickCheckOf(
        samples: List<FaceSample>,
        saved: CalibrationCenters,
    ): PhysicalObservation.Partial {
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
     * asks for each once, with its named top; a re-capture still doubtful is then no evidence ([quickCheckOf]).
     */
    fun quickCheckRetakes(
        samples: List<FaceSample>,
        saved: CalibrationCenters,
    ): List<Face> {
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
    ): ScanReview {
        val stickers = review.stickers.mapIndexed { i, color -> corrections[i] ?: color }
        return ScanReview(stickers, stickers.indexOfFirst { it == null }.takeIf { it >= 0 })
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

    fun phaseOf(quick: dev.cubentia.domain.recognition.QuickScanController): GuidedScanPhase? {
        if (quick.inCorner) return GuidedScanPhase.CornerViewfinder(quick.hold, quick.captured, quick.coaching)
        val grid = quick.fallback ?: return null
        return phaseOf(grid).copy(
            captured = quick.captured,
            again =
                if (grid.repeating) {
                    RepromptProgress(grid.repromptIndex + 1, grid.repromptCount)
                } else {
                    RepromptProgress(grid.stepIndex + 1, grid.planSize)
                },
            retake = true,
        )
    }

    fun reviewOf(
        effects: List<GuidedScanEffect>,
        saved: CalibrationCenters?,
    ): GuidedScanPhase.Review? =
        effects.filterIsInstance<GuidedScanEffect.Done>().firstOrNull()?.let {
            val review = ScanReview.fromSamples(it.samples, saved)
            GuidedScanPhase.Review(
                review,
                CalibrationOffer
                    .offered(it.samples, saved, review),
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
