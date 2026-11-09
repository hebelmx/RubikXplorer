package dev.cubentia.application.freecube

import dev.cubentia.application.session.Presentation

/** The only writer of free-cube history: commit first, then present the committed target. */
fun reduceFreeCube(
    state: FreeCubeState,
    event: FreeCubeEvent,
): FreeCubeTransition {
    val history = state.history
    val move =
        when (event) {
            is FreeCubeEvent.MoveCommitted -> event.move
            FreeCubeEvent.Undo -> history.undoable?.inverse() ?: return FreeCubeTransition(state)
            FreeCubeEvent.Redo -> history.redoable ?: return FreeCubeTransition(state)
            FreeCubeEvent.Reset, is FreeCubeEvent.Scrambled -> null
        }
    val nextHistory =
        when (event) {
            is FreeCubeEvent.MoveCommitted -> history.commit(event.move)
            FreeCubeEvent.Undo -> history.undo()
            FreeCubeEvent.Redo -> history.redo()
            FreeCubeEvent.Reset -> history.reset()
            is FreeCubeEvent.Scrambled -> event.moves.fold(history.reset()) { current, turn -> current.commit(turn) }
        }
    val next = FreeCubeState(nextHistory, state.revision + 1)
    val presentation =
        if (move == null) {
            Presentation.Snap(next.revision, nextHistory.state)
        } else {
            Presentation.Animate(move, next.revision, nextHistory.state)
        }
    return FreeCubeTransition(next, presentation)
}
