package dev.rubikxplorer.application.freecube

import dev.rubikxplorer.domain.cube.Move

/** Inputs to the in-memory free cube; scramble generation belongs to the caller. */
sealed interface FreeCubeEvent {
    data class MoveCommitted(
        val move: Move,
    ) : FreeCubeEvent

    data object Undo : FreeCubeEvent

    data object Redo : FreeCubeEvent

    data object Reset : FreeCubeEvent

    data class Scrambled(
        val moves: List<Move>,
    ) : FreeCubeEvent
}
