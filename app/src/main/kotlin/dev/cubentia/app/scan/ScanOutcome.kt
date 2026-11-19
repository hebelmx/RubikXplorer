package dev.cubentia.app.scan

import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.recognition.CalibrationCenters

sealed interface ScanOutcome {
    /**
     * [runScanColors] are the six centers a Guided 6-face Scan measured when no calibration was saved (GH #172), for
     * the physical run it starts; null otherwise.
     */
    data class Accepted(
        val state: CubeState,
        val runScanColors: CalibrationCenters? = null,
    ) : ScanOutcome

    data object Cancelled : ScanOutcome

    data object FellBackToEditor : ScanOutcome
}
