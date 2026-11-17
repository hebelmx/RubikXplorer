package dev.cubentia.app.camera

import dev.cubentia.domain.recognition.GuidedScanController
import dev.cubentia.domain.recognition.GuidedScanEffect
import dev.cubentia.domain.recognition.ScanReview

/** The Guided Scan Activity's decisions, kept pure so they are unit-tested without a camera or a permission dialog. */
internal object GuidedScanFlow {
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
        )

    fun reviewOf(
        effects: List<GuidedScanEffect>,
        saved: dev.cubentia.domain.recognition.CalibrationCenters?,
    ): GuidedScanPhase.Review? =
        effects.filterIsInstance<GuidedScanEffect.Done>().firstOrNull()?.let {
            val review = ScanReview.fromSamples(it.samples, saved)
            GuidedScanPhase.Review(
                review,
                dev.cubentia.domain.recognition.CalibrationOffer
                    .offered(it.samples, saved, review),
            )
        }

    fun calibrationCapture(
        phase: GuidedScanPhase.Calibration,
        stats: dev.cubentia.domain.recognition.FrameStats,
        ring: dev.cubentia.domain.recognition.CellReading?,
    ): GuidedScanPhase.Calibration {
        val quality =
            dev.cubentia.domain.recognition.FrameQuality
                .assessShutter(stats)
        return if (quality == dev.cubentia.domain.recognition.CaptureQuality.ACCEPTABLE) {
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
        saved: dev.cubentia.domain.recognition.CalibrationCenters? = null,
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
