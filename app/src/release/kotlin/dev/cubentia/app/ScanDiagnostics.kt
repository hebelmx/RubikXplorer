package dev.cubentia.app

import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.PhysicalObservation
import dev.cubentia.domain.tutor.Certainty

/** release and benchmark builds: no scan diagnostics are computed or logged. */
object ScanDiagnostics {
    @Suppress("UNUSED_PARAMETER")
    fun onCornerFrame(
        nowMs: Long,
        detected: Boolean,
        faces: List<FrameStats?>,
        score: Double,
    ) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun onQuickScanRun(
        elapsedMs: Long,
        fallbackFaces: Int,
        repromptFaces: Int,
        corrections: Int,
        outcome: String,
    ) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun onFrame(
        stats: FrameStats,
        nowMs: Long,
        latticeScale: Double,
        latticeDx: Double,
        latticeDy: Double,
        latticeScore: Double,
    ) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun onAssembly(
        round: String,
        samples: List<FaceSample>,
        saved: CalibrationCenters?,
    ) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun onQuickCheck(
        expected: CubeState,
        observation: PhysicalObservation,
        certainty: Certainty?,
    ) = Unit
}
