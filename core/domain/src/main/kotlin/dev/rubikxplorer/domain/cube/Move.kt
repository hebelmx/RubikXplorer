package dev.rubikxplorer.domain.cube

/**
 * A Move: a [FaceTurn] (`R`), a [WideTurn] (`r`), a [SliceTurn] (`M`) or a [CubeRotation] (`x`).
 * Every variant is an immutable value of a name plus a [Turn], so each effect of a single token has
 * exactly one value: `R2'` parses to the same value as `R2`. Strings convert to and from Moves only through [Notation].
 */
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

/** A middle layer, named per WCA: [M] turns like L, [E] like D, [S] like F. */
enum class Slice {
    M,
    E,
    S,
}

/** A whole-cube rotation axis: [X] turns like R, [Y] like U, [Z] like F. */
enum class Axis {
    X,
    Y,
    Z,
}

/** A wide Move: the Learner-facing [face] plus the adjacent middle layer (`r` = `R` + `M'`). */
data class WideTurn(
    val face: Face,
    val turn: Turn,
) : Move {
    override fun inverse(): WideTurn = WideTurn(face, turn.inverse())
}

/** A slice Move: turns only the middle [slice] layer, in the WCA direction. */
data class SliceTurn(
    val slice: Slice,
    val turn: Turn,
) : Move {
    override fun inverse(): SliceTurn = SliceTurn(slice, turn.inverse())
}

/** A Cube Rotation: turns the whole cube about [axis]; changes only the orientation. */
data class CubeRotation(
    val axis: Axis,
    val turn: Turn,
) : Move {
    override fun inverse(): CubeRotation = CubeRotation(axis, turn.inverse())
}

/** The sequence that undoes this one: reversed, each Move inverted. */
fun List<Move>.inverse(): List<Move> = asReversed().map { it.inverse() }
