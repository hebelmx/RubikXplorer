package dev.rubikxplorer.domain.cube

/** A Move. Sealed so Notation (story 1.3) can add wide, slice and Cube Rotation variants. */
sealed interface Move {
    /** The Move that undoes this one. */
    fun inverse(): Move
}

/** Turn amount, as seen looking at the face from outside the cube. */
enum class Turn {
    CLOCKWISE,
    HALF,
    COUNTERCLOCKWISE,
    ;

    /** Number of clockwise quarter turns with the same effect. */
    internal val quarters: Int
        get() =
            when (this) {
                CLOCKWISE -> 1
                HALF -> 2
                COUNTERCLOCKWISE -> 3
            }

    fun inverse(): Turn =
        when (this) {
            CLOCKWISE -> COUNTERCLOCKWISE
            HALF -> HALF
            COUNTERCLOCKWISE -> CLOCKWISE
        }
}

/** A face Move: turns the home face that the current orientation places at the Learner-facing [face]. */
data class FaceTurn(
    val face: Face,
    val turn: Turn,
) : Move {
    override fun inverse(): FaceTurn = FaceTurn(face, turn.inverse())
}

/** The sequence that undoes this one: reversed, each Move inverted. */
fun List<Move>.inverse(): List<Move> = asReversed().map { it.inverse() }
