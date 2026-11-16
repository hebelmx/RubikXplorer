package dev.cubentia.app.scan

import dev.cubentia.domain.cube.CubeState

sealed interface ScanOutcome {
    data class Accepted(
        val state: CubeState,
    ) : ScanOutcome

    data object Cancelled : ScanOutcome

    data object FellBackToEditor : ScanOutcome
}
