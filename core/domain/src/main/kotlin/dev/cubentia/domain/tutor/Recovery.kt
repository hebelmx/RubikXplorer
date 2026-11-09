package dev.cubentia.domain.tutor

import dev.cubentia.domain.cube.Move

/** The Guided recovery the tutor can offer (AD-5). */
sealed interface Recovery {
    /** A recovery plan is being computed by the effect [effectId]. */
    data class Pending(
        val effectId: Long,
    ) : Recovery

    /** The shortest correction, [moves], is ready. */
    data class Ready(
        val moves: List<Move>,
    ) : Recovery

    /** No Guided recovery is offered. */
    data object Unavailable : Recovery
}
