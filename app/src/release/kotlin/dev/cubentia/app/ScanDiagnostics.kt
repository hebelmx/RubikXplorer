package dev.cubentia.app

import dev.cubentia.application.usecase.Recognition
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CornerCaptureReport
import dev.cubentia.domain.recognition.CornerHoldEvent
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.PhysicalObservation
import dev.cubentia.domain.recognition.ScanReview
import dev.cubentia.domain.recognition.SideLight
import dev.cubentia.domain.tutor.Certainty
import dev.cubentia.domain.tutor.PlacementOutcome

/** release and benchmark builds: no scan diagnostics are computed or logged. */
@Suppress("UNUSED_PARAMETER")
object ScanDiagnostics {
    fun onCornerFrame(
        nowMs: Long,
        detected: Boolean,
        faces: List<FrameStats?>,
        score: Double,
        light: SideLight.Verdict? = null,
        lightHeld: Boolean = false,
        torch: Boolean = false,
    ) = Unit

    fun onHoldEvent(event: CornerHoldEvent) = Unit

    fun onCornerCapture(report: CornerCaptureReport) = Unit

    fun onCalibrationOffer(
        hold: Int,
        heldMs: Long?,
    ) = Unit

    fun onMetering(
        supported: Boolean,
        onOutline: Boolean,
    ) = Unit

    fun onMeteringSettled(
        settleMs: Long,
        timeout: Boolean,
        lock: String,
        futureMs: Long? = null,
    ) = Unit

    fun onExposureLockReleased(
        confirmed: Boolean,
        heldMs: Long?,
    ) = Unit

    fun onTorch(
        hasFlash: Boolean,
        torch: Boolean,
    ) = Unit

    fun onQuickScanRun(
        started: Long?,
        fallbackFaces: Int,
        repromptFaces: Int,
        initialCorrections: Map<Int, Color>?,
        outcome: String,
        state: List<Color?>?,
        initial: List<Color?>?,
    ) = Unit

    fun onFrame(
        stats: FrameStats,
        nowMs: Long,
        latticeScale: Double,
        latticeDx: Double,
        latticeDy: Double,
        latticeScore: Double,
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
