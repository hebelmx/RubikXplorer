package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.predicate.Piece
import dev.rubikxplorer.domain.text.Text
import dev.rubikxplorer.domain.tutor.RecoveryPlanner

/**
 * Where the Learner's *Guide me back* stands (FR-12, AD-5): tutor state, so it never advances
 * [SessionState.revision]. Each state but [None] has the keyed [text] the lesson panel shows.
 */
sealed interface GuidedRecovery {
    /** The line the lesson panel shows, or null when there is none. */
    val text: Text?

    /** No Guided recovery asked for, or the last one ended. */
    data object None : GuidedRecovery {
        override val text: Text? get() = null
    }

    /** The plan is being computed by [effect]: "Finding the shortest way back…". */
    data class Planning(
        val effect: Effect.PlanRecovery,
    ) : GuidedRecovery {
        override val text: Text get() = RecoveryPlanner.PENDING
    }

    /** The shortest correction [moves], which restores the preserved [pieces]; [text] says so. */
    data class Planned(
        val moves: List<Move>,
        val pieces: Set<Piece>,
        override val text: Text,
    ) : GuidedRecovery

    /** No correction within the bound or the time: "I can't find a short way back…". */
    data object NotFound : GuidedRecovery {
        override val text: Text get() = RecoveryPlanner.NOT_FOUND
    }
}
