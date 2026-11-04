package dev.rubikxplorer.domain.input

import dev.rubikxplorer.domain.cube.Move

/** A Learner's request to turn [move], from any input (key, gesture, accessibility action). */
data class MoveIntent(
    val move: Move,
)
