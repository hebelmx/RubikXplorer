package dev.cubentia.application.session

import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.text.Text

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

    /** Instruct the Learner to turn their physical cube. */
    data class Instruct(
        val move: Move,
        val text: Text,
        override val revision: Long,
        override val target: CubeState,
    ) : Presentation {
        companion object {
            fun of(
                move: Move,
                revision: Long,
                target: CubeState,
            ): Instruct =
                Instruct(
                    move = move,
                    text = Text("msg.physical.undo", mapOf("move" to Notation.format(move))),
                    revision = revision,
                    target = target,
                )
        }
    }

    companion object {
        fun instruct(
            move: Move,
            revision: Long,
            target: CubeState,
        ): Instruct = Instruct.of(move, revision, target)
    }
}
