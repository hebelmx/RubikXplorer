package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face

/**
 * An observation of a physical cube (AD-5). Either a [Full] validated cube state,
 * or a [Partial] observation of individual faces which is never completed from expected state.
 */
sealed interface PhysicalObservation {
    companion object {
        /** Capture protocol bound for Quick Check; larger observations use a Full Guided Scan. */
        const val QUICK_CHECK_MAX_CAPTURES = 2
    }

    data class Full(
        val state: CubeState,
    ) : PhysicalObservation

    data class Partial(
        val faces: Map<Face, List<Color>>,
        val orientationHint: String? = null,
    ) : PhysicalObservation
}
