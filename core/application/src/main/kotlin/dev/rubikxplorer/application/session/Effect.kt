package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.text.Text

/**
 * An asynchronous request a [Transition] hands to the host (AD-3). Each carries its [effectId] and the
 * [revision] of the state it was issued in; its result event echoes both, or it is discarded.
 */
sealed interface Effect {
    val effectId: Long
    val revision: Long

    /** Solve a validated Cube State without changing the session. */
    data class SolveCube(
        override val effectId: Long,
        override val revision: Long,
        val state: CubeState,
    ) : Effect

    /** Ask the Learner whether to reset, with [prompt]; answered by `ResetConfirmed` or `ResetDeclined`. */
    data class ConfirmReset(
        override val effectId: Long,
        override val revision: Long,
        val prompt: Text,
    ) : Effect
}

/** A result for effect [effectId], issued at [issuedRevision], that arrived when the state was at [currentRevision] or no longer awaited it. */
data class EffectResultDiscarded(
    val effectId: Long,
    val issuedRevision: Long,
    val currentRevision: Long,
)
