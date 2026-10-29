package dev.rubikxplorer.domain.cube

/**
 * An immutable Cube State: the [cubies] relative to the centers plus the whole-cube [orientation]
 * that maps Learner-facing faces to home faces.
 */
data class CubeState(
    val cubies: Cubies,
    val orientation: Orientation,
) {
    /**
     * True when every cubie is home; [orientation] is ignored. Use this, not `== SOLVED`: equality
     * also compares [orientation], so a solved cube held in another orientation is not equal to [SOLVED].
     */
    val isSolved: Boolean
        get() = cubies == Cubies.SOLVED

    /** A new state with [move] applied; face Moves act through [orientation] and never change it. */
    fun apply(move: Move): CubeState =
        when (move) {
            is FaceTurn -> copy(cubies = cubies.turn(orientation.homeFaceAt(move.face), move.turn))
        }

    /** A new state with [moves] applied in order. */
    fun apply(moves: List<Move>): CubeState = moves.fold(this) { state, move -> state.apply(move) }

    /** A new state with [moves] applied in order. */
    fun apply(vararg moves: Move): CubeState = apply(moves.asList())

    companion object {
        /** Solved, in the home frame. */
        val SOLVED: CubeState = CubeState(Cubies.SOLVED, Orientation.HOME)
    }
}
