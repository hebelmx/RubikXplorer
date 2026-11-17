package dev.cubentia.app.camera

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
import dev.cubentia.domain.recognition.StickerConfidence

/** The Guided Scan Activity's decisions, kept pure so they are unit-tested without a camera or a permission dialog. */
internal object GuidedScanFlow {
    /** Only whole, confident posed faces are evidence. No Expected state participates. */
    fun quickCheckOf(
        samples: List<FaceSample>,
        saved: CalibrationCenters,
    ): PhysicalObservation.Partial {
        val result =
            ScanAssembler
                .assemble(samples, saved)
        if (result !is ScanResult.Partial) {
            return PhysicalObservation
                .Partial(
                    emptyMap(),
                )
        }
        return result.observation.copy(
            faces =
                result.observation.faces.filterKeys { face ->
                    result.confidence[face]?.let {
                        it.size == 9 &&
                            it.all { confidence -> confidence == StickerConfidence.CONFIDENT }
                    } ==
                        true
                },
        )
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

    /** What the viewfinder shows for the controller's current state. */
    fun phaseOf(controller: GuidedScanController): GuidedScanPhase.Viewfinder =
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
        )

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
