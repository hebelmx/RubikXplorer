package dev.cubentia.app.scan

import dev.cubentia.domain.cube.Color
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

    /**
     * The scan fell back to the Manual Cube Editor (GH #321). [stickers] are the scan's 54 home-frame stickers (null =
     * unset), the review's fixes included, when at least one face was captured; null otherwise. Only the Solve tab seeds
     * its Editor from them; every other caller ignores them. Never filled from an expected state (AD-5).
     */
    data class FellBackToEditor(
        val stickers: List<Color?>? = null,
    ) : ScanOutcome
}
