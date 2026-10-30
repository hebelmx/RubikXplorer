package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Move

/** One step for the renderer (AD-4): the committed [target] state at [revision], reached by the step. */
sealed interface Presentation {
    val revision: Long
    val target: CubeState

    /** Animate [move] (Undo animates the inverse of the undone Move). */
    data class Animate(
        val move: Move,
        override val revision: Long,
        override val target: CubeState,
    ) : Presentation

    /** Jump straight to [target] with no animation (Lesson start, Reset). */
    data class Snap(
        override val revision: Long,
        override val target: CubeState,
    ) : Presentation
}
