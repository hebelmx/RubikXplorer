package dev.rubikxplorer.domain.cube

/**
 * Test-only reader from a [CubeState] to its 54 sticker colors as the Learner sees them, in URFDLB
 * order, row-major per the Kociemba net (U: B edge on top; D: F edge on top; F R B L: U edge on top).
 *
 * Derived from 3D sticker geometry and the [Cubies] slot/twist/flip conventions only (never from the
 * Move tables): each Learner facelet is a (position, normal) pair in Learner coordinates, mapped to
 * home coordinates through the orientation, then looked up in the home slot that holds it.
 */
internal object StickerReader {
    private val CORNER_SLOTS: List<List<Face>> =
        listOf(
            listOf(Face.U, Face.R, Face.F),
            listOf(Face.U, Face.F, Face.L),
            listOf(Face.U, Face.L, Face.B),
            listOf(Face.U, Face.B, Face.R),
            listOf(Face.D, Face.F, Face.R),
            listOf(Face.D, Face.L, Face.F),
            listOf(Face.D, Face.B, Face.L),
            listOf(Face.D, Face.R, Face.B),
        ).map { clockwiseFromUD(it) }

    // (reference facet, other facet): U/D if the slot has one, else F/B.
    private val EDGE_SLOTS: List<List<Face>> =
        listOf(
            listOf(Face.U, Face.R),
            listOf(Face.U, Face.F),
            listOf(Face.U, Face.L),
            listOf(Face.U, Face.B),
            listOf(Face.D, Face.R),
            listOf(Face.D, Face.F),
            listOf(Face.D, Face.L),
            listOf(Face.D, Face.B),
            listOf(Face.F, Face.R),
            listOf(Face.F, Face.L),
            listOf(Face.B, Face.L),
            listOf(Face.B, Face.R),
        )

    fun read(state: CubeState): List<Color> =
        Face.entries.flatMap { face ->
            (0 until 9).map { cell ->
                val learnerPosition = netPosition(face, cell / 3, cell % 3)
                val position = toHome(state.orientation, learnerPosition)
                val normal = toHome(state.orientation, axis(face))
                colorAt(state.cubies, position, faceOf(normal))
            }
        }

    /** Learner-frame position of row [r], column [c] of the net drawing of [face]; axes R=+x U=+y F=+z. */
    private fun netPosition(
        face: Face,
        r: Int,
        c: Int,
    ): List<Int> =
        when (face) {
            Face.U -> listOf(c - 1, 1, r - 1)
            Face.R -> listOf(1, 1 - r, 1 - c)
            Face.F -> listOf(c - 1, 1 - r, 1)
            Face.D -> listOf(c - 1, -1, 1 - r)
            Face.L -> listOf(-1, 1 - r, c - 1)
            Face.B -> listOf(1 - c, 1 - r, -1)
        }

    private fun colorAt(
        cubies: Cubies,
        position: List<Int>,
        facet: Face,
    ): Color {
        val faces = Face.entries.filter { dot(axis(it), position) == 1 }.toSet()
        return when (faces.size) {
            1 -> {
                Color.home(faces.single())
            }

            2 -> {
                val slot = EDGE_SLOTS.indexOfFirst { it.toSet() == faces }
                val f = EDGE_SLOTS[slot].indexOf(facet)
                Color.home(EDGE_SLOTS[cubies.edgePermutation[slot]][(f + cubies.edgeOrientation[slot]) % 2])
            }

            else -> {
                val slot = CORNER_SLOTS.indexOfFirst { it.toSet() == faces }
                val f = CORNER_SLOTS[slot].indexOf(facet)
                Color.home(CORNER_SLOTS[cubies.cornerPermutation[slot]][(f - cubies.cornerOrientation[slot] + 3) % 3])
            }
        }
    }

    /** The corner's faces starting at its U/D face and going clockwise as seen from outside the corner. */
    private fun clockwiseFromUD(faces: List<Face>): List<Face> {
        val ud = faces.single { it == Face.U || it == Face.D }
        val corner = faces.map { axis(it) }.reduce(::add)
        val next = faces.single { it != ud && dot(cross(axis(ud), axis(it)), corner) < 0 }
        return listOf(ud, next, faces.single { it != ud && it != next })
    }

    private fun toHome(
        o: Orientation,
        v: List<Int>,
    ): List<Int> =
        add(
            add(scale(axis(o.homeFaceAt(Face.R)), v[0]), scale(axis(o.homeFaceAt(Face.U)), v[1])),
            scale(axis(o.homeFaceAt(Face.F)), v[2]),
        )

    private fun axis(face: Face): List<Int> =
        when (face) {
            Face.R -> listOf(1, 0, 0)
            Face.L -> listOf(-1, 0, 0)
            Face.U -> listOf(0, 1, 0)
            Face.D -> listOf(0, -1, 0)
            Face.F -> listOf(0, 0, 1)
            Face.B -> listOf(0, 0, -1)
        }

    private fun faceOf(v: List<Int>): Face = Face.entries.single { axis(it) == v }

    private fun add(
        a: List<Int>,
        b: List<Int>,
    ) = List(3) { a[it] + b[it] }

    private fun scale(
        a: List<Int>,
        k: Int,
    ) = a.map { it * k }

    private fun dot(
        a: List<Int>,
        b: List<Int>,
    ) = (0 until 3).sumOf { a[it] * b[it] }

    private fun cross(
        a: List<Int>,
        b: List<Int>,
    ) = listOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
}
