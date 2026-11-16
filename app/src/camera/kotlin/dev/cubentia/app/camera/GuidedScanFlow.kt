package dev.cubentia.app.camera

import dev.cubentia.app.scan.GuidedScanCapture
import dev.cubentia.domain.recognition.GuidedScanController
import dev.cubentia.domain.recognition.GuidedScanEffect

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
        GuidedScanPhase.Viewfinder(controller.stepIndex, controller.captured, controller.coaching, controller.canUseAnyway)

    /** True when [effects] ask for AF/AE/AWB on the grid. */
    fun remeters(effects: List<GuidedScanEffect>): Boolean = GuidedScanEffect.Remeter in effects

    /** The scan's result when [effects] finish it, else null. */
    fun resultOf(effects: List<GuidedScanEffect>): GuidedScanCapture? =
        effects.filterIsInstance<GuidedScanEffect.Done>().firstOrNull()?.let { GuidedScanCapture.Captured(it.samples) }
}
