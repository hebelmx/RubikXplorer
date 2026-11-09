package dev.cubentia.domain.cube

/**
 * Corner and edge cubies relative to the centers, in the home frame.
 *
 * Corner slots: URF, UFL, ULB, UBR, DFR, DLF, DBL, DRB (0..7). Edge slots: UR, UF, UL, UB, DR, DF,
 * DL, DB, FR, FL, BL, BR (0..11). `cornerPermutation[i]` is the cubie in slot `i` (cubie `k` is the
 * one whose solved slot is `k`) and `cornerOrientation[i]` its twist there: how many facets clockwise
 * from the slot's U/D facet the cubie's U/D sticker sits. `edgeOrientation[i]` is 1 when the edge's
 * reference sticker (U/D, else F/B) is not on the slot's reference facet.
 *
 * Construction checks shape only (sizes, ranges, permutations); legality (twist, flip, parity) is
 * `CubeValidator`'s job.
 */
@ConsistentCopyVisibility
data class Cubies internal constructor(
    val cornerPermutation: List<Int>,
    val cornerOrientation: List<Int>,
    val edgePermutation: List<Int>,
    val edgeOrientation: List<Int>,
) {
    init {
        requirePermutation("cornerPermutation", cornerPermutation, CORNERS)
        requireValues("cornerOrientation", cornerOrientation, CORNERS, TWISTS)
        requirePermutation("edgePermutation", edgePermutation, EDGES)
        requireValues("edgeOrientation", edgeOrientation, EDGES, FLIPS)
    }

    /** These cubies after the home [face] is turned by [turn]. */
    internal fun turn(
        face: Face,
        turn: Turn,
    ): Cubies {
        val quarter = QUARTER_TURNS.getValue(face)
        var result = this
        repeat(turn.quarters) { result = result.compose(quarter) }
        return result
    }

    /** Applies [move] after this: slot `i` receives what was in slot `move.cornerPermutation[i]`. */
    private fun compose(move: Cubies): Cubies =
        Cubies(
            cornerPermutation = List(CORNERS) { cornerPermutation[move.cornerPermutation[it]] },
            cornerOrientation =
                List(CORNERS) { (cornerOrientation[move.cornerPermutation[it]] + move.cornerOrientation[it]) % TWISTS },
            edgePermutation = List(EDGES) { edgePermutation[move.edgePermutation[it]] },
            edgeOrientation = List(EDGES) { (edgeOrientation[move.edgePermutation[it]] + move.edgeOrientation[it]) % FLIPS },
        )

    companion object {
        internal const val CORNERS = 8
        internal const val EDGES = 12
        internal const val TWISTS = 3
        internal const val FLIPS = 2

        /** One face turn moves this many corners and this many edges. */
        internal const val PER_FACE_TURN = 4

        /** Every cubie in its own slot, untwisted and unflipped. */
        val SOLVED: Cubies =
            Cubies(List(CORNERS) { it }, List(CORNERS) { 0 }, List(EDGES) { it }, List(EDGES) { 0 })

        // One clockwise quarter turn of each home face, derived from the cube's geometry: rotate each
        // slot's position and reference-sticker normal by -90° about the face axis and read off which
        // slot it lands in and which facet the reference sticker lands on.
        private val QUARTER_TURNS: Map<Face, Cubies> =
            mapOf(
                Face.U to
                    Cubies(
                        listOf(3, 0, 1, 2, 4, 5, 6, 7),
                        listOf(0, 0, 0, 0, 0, 0, 0, 0),
                        listOf(3, 0, 1, 2, 4, 5, 6, 7, 8, 9, 10, 11),
                        listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                    ),
                Face.R to
                    Cubies(
                        listOf(4, 1, 2, 0, 7, 5, 6, 3),
                        listOf(2, 0, 0, 1, 1, 0, 0, 2),
                        listOf(8, 1, 2, 3, 11, 5, 6, 7, 4, 9, 10, 0),
                        listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                    ),
                Face.F to
                    Cubies(
                        listOf(1, 5, 2, 3, 0, 4, 6, 7),
                        listOf(1, 2, 0, 0, 2, 1, 0, 0),
                        listOf(0, 9, 2, 3, 4, 8, 6, 7, 1, 5, 10, 11),
                        listOf(0, 1, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0),
                    ),
                Face.D to
                    Cubies(
                        listOf(0, 1, 2, 3, 5, 6, 7, 4),
                        listOf(0, 0, 0, 0, 0, 0, 0, 0),
                        listOf(0, 1, 2, 3, 5, 6, 7, 4, 8, 9, 10, 11),
                        listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                    ),
                Face.L to
                    Cubies(
                        listOf(0, 2, 6, 3, 4, 1, 5, 7),
                        listOf(0, 1, 2, 0, 0, 2, 1, 0),
                        listOf(0, 1, 10, 3, 4, 5, 9, 7, 8, 2, 6, 11),
                        listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                    ),
                Face.B to
                    Cubies(
                        listOf(0, 1, 3, 7, 4, 5, 2, 6),
                        listOf(0, 0, 1, 2, 0, 0, 2, 1),
                        listOf(0, 1, 2, 11, 4, 5, 6, 10, 8, 9, 3, 7),
                        listOf(0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 1),
                    ),
            )

        private fun requirePermutation(
            name: String,
            values: List<Int>,
            size: Int,
        ) {
            require(values.size == size) { "$name must have $size entries, was ${values.size}" }
            require(values.sorted() == List(size) { it }) { "$name must be a permutation of 0..${size - 1}: $values" }
        }

        private fun requireValues(
            name: String,
            values: List<Int>,
            size: Int,
            range: Int,
        ) {
            require(values.size == size) { "$name must have $size entries, was ${values.size}" }
            require(values.all { it in 0 until range }) { "$name values must be in 0..${range - 1}: $values" }
        }
    }
}
