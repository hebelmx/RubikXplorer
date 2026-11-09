package dev.cubentia.domain.input

import dev.cubentia.domain.cube.Move

/** A Learner's request to turn [move], from any input (key, gesture, accessibility action). */
data class MoveIntent(
    val move: Move,
)
