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

    /**
     * A new state with [move] applied. Face Moves act through [orientation] and never change it; a
     * Cube Rotation changes only [orientation]; wide and slice Moves apply as their face-turn
     * equivalent plus a rotation (e.g. `r = L x`, `M = R L' x'`), so they also act through [orientation].
     */
    fun apply(move: Move): CubeState =
        when (move) {
            is FaceTurn -> copy(cubies = cubies.turn(orientation.homeFaceAt(move.face), move.turn))
            is CubeRotation -> copy(orientation = orientation.rotated(move.axis, move.turn))
            is WideTurn -> apply(parts(move))
            is SliceTurn -> apply(parts(move))
        }

    /**
     * The 54 stickers as the Learner sees them, through [orientation]. Each Learner sticker is mapped
     * to the home sticker it shows, then read from the cubie in that home slot using the [Cubies]
     * twist and flip conventions.
     */
    fun toFacelets(): Facelets = Facelets(List(Facelets.SIZE) { homeColor(FaceletTables.toHome(orientation, it)) })

    private fun homeColor(index: Int): Color {
        val t = FaceletTables
        val corner = t.CORNER_FACELETS.indexOfFirst { index in it }
        if (corner >= 0) {
            val facet = t.CORNER_FACELETS[corner].indexOf(index)
            val twist = cubies.cornerOrientation[corner]
            return Color.home(t.CORNER_FACES[cubies.cornerPermutation[corner]][(facet - twist + Cubies.TWISTS) % Cubies.TWISTS])
        }
        val edge = t.EDGE_FACELETS.indexOfFirst { index in it }
        if (edge >= 0) {
            val facet = t.EDGE_FACELETS[edge].indexOf(index)
            return Color.home(t.EDGE_FACES[cubies.edgePermutation[edge]][(facet + cubies.edgeOrientation[edge]) % Cubies.FLIPS])
        }
        return Color.home(Face.entries[index / 9])
    }

    /** A new state with [moves] applied in order. */
    fun apply(moves: List<Move>): CubeState = moves.fold(this) { state, move -> state.apply(move) }

    /** A new state with [moves] applied in order. */
    fun apply(vararg moves: Move): CubeState = apply(moves.asList())

    companion object {
        /** Solved, in the home frame. */
        val SOLVED: CubeState = CubeState(Cubies.SOLVED, Orientation.HOME)

        /**
         * The Learner-frame face turns and rotation equal to a wide Move, each part taking the
         * Move's turn (primes flip it): `r = L x`, `l = R x'`, `u = D y`, `d = U y'`, `f = B z`,
         * `b = F z'`. The rotation turns all layers; the opposite face turn cancels it on the far layer.
         */
        internal fun parts(move: WideTurn): List<Move> {
            val t = move.turn
            val i = t.inverse()
            return when (move.face) {
                Face.R -> listOf(FaceTurn(Face.L, t), CubeRotation(Axis.X, t))
                Face.L -> listOf(FaceTurn(Face.R, t), CubeRotation(Axis.X, i))
                Face.U -> listOf(FaceTurn(Face.D, t), CubeRotation(Axis.Y, t))
                Face.D -> listOf(FaceTurn(Face.U, t), CubeRotation(Axis.Y, i))
                Face.F -> listOf(FaceTurn(Face.B, t), CubeRotation(Axis.Z, t))
                Face.B -> listOf(FaceTurn(Face.F, t), CubeRotation(Axis.Z, i))
            }
        }

        /**
         * The Learner-frame face turns and rotation equal to a slice Move (WCA: M like L, E like D,
         * S like F), each part taking the Move's turn (primes flip it): `M = R L' x'`,
         * `E = U D' y'`, `S = F' B z`. The two face turns cancel the rotation on both outer layers.
         */
        internal fun parts(move: SliceTurn): List<Move> {
            val t = move.turn
            val i = t.inverse()
            return when (move.slice) {
                Slice.M -> listOf(FaceTurn(Face.R, t), FaceTurn(Face.L, i), CubeRotation(Axis.X, i))
                Slice.E -> listOf(FaceTurn(Face.U, t), FaceTurn(Face.D, i), CubeRotation(Axis.Y, i))
                Slice.S -> listOf(FaceTurn(Face.F, i), FaceTurn(Face.B, t), CubeRotation(Axis.Z, t))
            }
        }
    }
}
