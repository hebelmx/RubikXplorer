package dev.cubentia.domain.cube

/**
 * One of the 24 whole-cube rotations, given by which home face sits at Learner U ([up]) and at
 * Learner F ([front]). The other four Learner faces follow from geometry: right = up × front.
 */
@ConsistentCopyVisibility
data class Orientation private constructor(
    val up: Face,
    val front: Face,
) {
    /** The home face currently shown at the Learner-facing [face]. */
    fun homeFaceAt(face: Face): Face =
        when (face) {
            Face.U -> up
            Face.F -> front
            Face.R -> right
            Face.D -> up.opposite
            Face.B -> front.opposite
            Face.L -> right.opposite
        }

    /** The center color shown on the Learner-facing [face]. */
    fun colorAt(face: Face): Color = Color.home(homeFaceAt(face))

    /** This orientation after the whole cube is turned about [axis] by [turn], as seen by the Learner. */
    internal fun rotated(
        axis: Axis,
        turn: Turn,
    ): Orientation {
        var result = this
        repeat(turn.quarters) { result = result.quarterTurn(axis) }
        return result
    }

    // One clockwise quarter: x brings Learner F up, y brings Learner R to the front, z brings Learner L up.
    private fun quarterTurn(axis: Axis): Orientation =
        when (axis) {
            Axis.X -> Orientation(homeFaceAt(Face.F), homeFaceAt(Face.D))
            Axis.Y -> Orientation(up, homeFaceAt(Face.R))
            Axis.Z -> Orientation(homeFaceAt(Face.L), front)
        }

    private val right: Face
        get() = faceOf(cross(axis(up), axis(front)))

    companion object {
        /** The home frame: WHITE on U, GREEN on F, RED on R. */
        val HOME: Orientation = Orientation(Face.U, Face.F)

        /** All 24 rotations. */
        val ALL: List<Orientation> =
            Face.entries.flatMap { up ->
                Face.entries.filter { isAdjacent(up, it) }.map { front -> Orientation(up, front) }
            }

        /** The rotation with home face [up] at Learner U and home face [front] at Learner F. */
        fun of(
            up: Face,
            front: Face,
        ): Orientation {
            require(isAdjacent(up, front)) { "up $up and front $front must be adjacent faces" }
            return Orientation(up, front)
        }

        private fun isAdjacent(
            a: Face,
            b: Face,
        ) = a != b && a.opposite != b

        // Home axes: R = +x, U = +y, F = +z (right-handed: R × U = F, U × F = R).
        private fun axis(face: Face): List<Int> =
            when (face) {
                Face.R -> listOf(1, 0, 0)
                Face.L -> listOf(-1, 0, 0)
                Face.U -> listOf(0, 1, 0)
                Face.D -> listOf(0, -1, 0)
                Face.F -> listOf(0, 0, 1)
                Face.B -> listOf(0, 0, -1)
            }

        private fun faceOf(vector: List<Int>): Face = Face.entries.first { axis(it) == vector }

        private fun cross(
            a: List<Int>,
            b: List<Int>,
        ): List<Int> =
            listOf(
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0],
            )
    }
}
