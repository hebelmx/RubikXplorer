package dev.rubikxplorer.domain.cube

/**
 * The 54 sticker colors of a cube as the Learner sees it, in URFDLB face order, each face row-major
 * per the Kociemba net. Index = `face.ordinal * 9 + row * 3 + col`:
 *
 * ```
 *              U0 U1 U2                 U: B edge on top
 *              U3 U4 U5                 D: F edge on top
 *              U6 U7 U8                 R, F, L, B: U edge on top
 *   L0 L1 L2   F0 F1 F2   R0 R1 R2   B0 B1 B2
 *   L3 L4 L5   F3 F4 F5   R3 R4 R5   B3 B4 B5
 *   L6 L7 L8   F6 F7 F8   R6 R7 R8   B6 B7 B8
 *              D0 D1 D2
 *              D3 D4 D5
 *              D6 D7 D8
 * ```
 *
 * U = 0..8, R = 9..17, F = 18..26, D = 27..35, L = 36..44, B = 45..53. Any 54 colors are accepted;
 * reach a [CubeState] only through [CubeValidator.validate].
 */
data class Facelets(
    val colors: List<Color>,
) {
    init {
        require(colors.size == SIZE) { "Facelets must have $SIZE colors, was ${colors.size}" }
    }

    companion object {
        /** Number of stickers. */
        const val SIZE: Int = 54
    }
}

/**
 * Where each sticker sits on its piece, by slot, following the [Cubies] slot order. Corner facets run
 * clockwise from the slot's U/D facet; edge facets start at the reference facet (U/D, else F/B).
 * The same tables serve the Learner frame and the home frame, since both use the same net.
 */
internal object FaceletTables {
    private const val PER_FACE = 9
    private const val CENTER_CELL = 4

    val CORNER_FACES: List<List<Face>> =
        listOf(
            listOf(Face.U, Face.R, Face.F),
            listOf(Face.U, Face.F, Face.L),
            listOf(Face.U, Face.L, Face.B),
            listOf(Face.U, Face.B, Face.R),
            listOf(Face.D, Face.F, Face.R),
            listOf(Face.D, Face.L, Face.F),
            listOf(Face.D, Face.B, Face.L),
            listOf(Face.D, Face.R, Face.B),
        )

    val CORNER_FACELETS: List<List<Int>> =
        listOf(
            listOf(8, 9, 20),
            listOf(6, 18, 38),
            listOf(0, 36, 47),
            listOf(2, 45, 11),
            listOf(29, 26, 15),
            listOf(27, 44, 24),
            listOf(33, 53, 42),
            listOf(35, 17, 51),
        )

    val EDGE_FACES: List<List<Face>> =
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

    val EDGE_FACELETS: List<List<Int>> =
        listOf(
            listOf(5, 10),
            listOf(7, 19),
            listOf(3, 37),
            listOf(1, 46),
            listOf(32, 16),
            listOf(28, 25),
            listOf(30, 43),
            listOf(34, 52),
            listOf(23, 12),
            listOf(21, 41),
            listOf(50, 39),
            listOf(48, 14),
        )

    /** Sticker index of the center of [face]. */
    fun center(face: Face): Int = face.ordinal * PER_FACE + CENTER_CELL

    val CENTERS: List<Int> = Face.entries.map { center(it) }

    /** The home-frame sticker index shown at Learner sticker [index] under [orientation]. */
    fun toHome(
        orientation: Orientation,
        index: Int,
    ): Int {
        if (index % PER_FACE == CENTER_CELL) return center(orientation.homeFaceAt(Face.entries[index / PER_FACE]))
        return pieceToHome(orientation, index, CORNER_FACES, CORNER_FACELETS)
            ?: checkNotNull(pieceToHome(orientation, index, EDGE_FACES, EDGE_FACELETS)) { "no piece holds $index" }
    }

    /** `map[home] = learner` for every sticker under [orientation]. */
    fun learnerOf(orientation: Orientation): IntArray {
        val map = IntArray(Facelets.SIZE)
        for (learner in 0 until Facelets.SIZE) map[toHome(orientation, learner)] = learner
        return map
    }

    private fun pieceToHome(
        orientation: Orientation,
        index: Int,
        faces: List<List<Face>>,
        facelets: List<List<Int>>,
    ): Int? {
        val slot = facelets.indexOfFirst { index in it }
        if (slot < 0) return null
        val homeFaces = faces[slot].map { orientation.homeFaceAt(it) }
        val homeSlot = faces.indexOfFirst { it.toSet() == homeFaces.toSet() }
        val facet = facelets[slot].indexOf(index)
        return facelets[homeSlot][faces[homeSlot].indexOf(homeFaces[facet])]
    }
}
