package dev.cubentia.app

import dev.cubentia.application.usecase.Recognition
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.PhysicalObservation
import dev.cubentia.domain.recognition.ScanReview
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.UnreadSlot
import dev.cubentia.domain.tutor.Certainty
import dev.cubentia.domain.tutor.PlacementOutcome

/** release and benchmark builds: no scan diagnostics are computed or logged. */
@Suppress("UNUSED_PARAMETER")
object ScanDiagnostics {
    /** GH #406: release creates no continuous stage-timing accumulator. */
    const val QUICK_SCAN_TIMINGS = false

    fun onQuickScanTimingWindow(body: String) = Unit

    fun onQuickScanTimingRun(body: String?) = Unit

    fun onQuickScanFrameFailed(failedFrames: Int) = Unit

    fun onTorch(
        hasFlash: Boolean,
        torch: Boolean,
    ) = Unit

    fun onFrame(
        stats: FrameStats,
        nowMs: Long,
        latticeScale: Double,
        latticeDx: Double,
        latticeDy: Double,
        latticeScore: Double,
    ) = Unit

    /** GH #405: never invokes [unread]. */
    fun onQuickScanUnread(
        nowMs: Long,
        snapshot: ContinuousScanSnapshot,
        unread: () -> List<UnreadSlot>,
    ) = Unit

    fun onAssembly(
        round: String,
        samples: List<FaceSample>,
        saved: CalibrationCenters?,
    ) = Unit

    fun onQuickCheck(
        expected: CubeState,
        observation: PhysicalObservation,
        certainty: Certainty?,
    ) = Unit

    fun startRun(): Long? = null

    fun onQuickScanRun(
        started: Long?,
        fallbackFaces: Int,
        corrections: Map<Int, Color>?,
        initial: List<Color?>?,
        outcome: String,
        earlyReview: Boolean = false,
    ) = Unit

    fun onGuidedScanRun(
        started: Long?,
        repromptFaces: Int,
        review: ScanReview?,
        initial: List<Color?>?,
        corrections: Map<Int, Color>?,
        outcome: String,
    ) = Unit

    fun onQuickCheckCaptured(
        started: Long?,
        captures: Long,
    ) = Unit

    fun discardQuickCheckCapture() = Unit

    fun onQuickCheckRun(
        expected: CubeState,
        observation: PhysicalObservation?,
        certainty: Certainty?,
        matchesExpected: Boolean?,
    ) = Unit

    fun onCaseCaptured() = Unit

    fun discardCaseCapture() = Unit

    fun onRecognizeRun(
        result: Recognition?,
        captures: Int?,
        captureOutcome: String = "-",
    ) = Unit

    fun onPlacementScanAccepted() = Unit

    fun onPlacementSuggested(outcome: PlacementOutcome) = Unit

    fun onPlacementRun(accepted: String?) = Unit
}
