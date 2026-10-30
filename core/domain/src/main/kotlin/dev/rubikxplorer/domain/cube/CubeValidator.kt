package dev.rubikxplorer.domain.cube

import dev.rubikxplorer.domain.text.Text

/** The outcome of [CubeValidator.validate]: a legal [Valid] state or one [Invalid] reason. */
sealed interface ValidationResult {
    /** The stickers form a real cube; `state.toFacelets()` equals the validated input. */
    @ConsistentCopyVisibility
    data class Valid private constructor(
        val state: CubeState,
    ) : ValidationResult {
        internal companion object {
            internal fun of(state: CubeState): Valid = Valid(state)
        }
    }

    /** Why the stickers cannot be a real cube. */
    sealed interface Invalid : ValidationResult {
        /** Plain-language reason, as a content key. */
        val reason: Text

        /** The offending sticker indices, ascending; empty when the rule is global. */
        val positions: List<Int>
    }

    /** Some color does not appear exactly 9 times. [counts] covers all six colors, including zeros. */
    data class WrongColorCounts(
        val counts: Map<Color, Int>,
    ) : Invalid {
        override val reason: Text get() = Text("msg.validation.wrong-color-counts")
        override val positions: List<Int> get() = emptyList()
    }

    /** Some pieces of kind [piece] show colors no real piece can; [positions] are all their stickers. */
    data class ImpossiblePiece(
        override val positions: List<Int>,
        val piece: PieceKind,
    ) : Invalid {
        override val reason: Text get() = Text("msg.validation.impossible-piece", mapOf("piece" to piece.id))
    }

    /** The corner twists do not sum to 0 mod 3. */
    data object TwistedCorner : Invalid {
        override val reason: Text get() = Text("msg.validation.twisted-corner")
        override val positions: List<Int> get() = emptyList()
    }

    /** The edge flips do not sum to 0 mod 2. */
    data object FlippedEdge : Invalid {
        override val reason: Text get() = Text("msg.validation.flipped-edge")
        override val positions: List<Int> get() = emptyList()
    }

    /** The corner and edge permutations have different parities. */
    data object ParityViolation : Invalid {
        override val reason: Text get() = Text("msg.validation.parity")
        override val positions: List<Int> get() = emptyList()
    }
}

/** The kind of piece an [ValidationResult.ImpossiblePiece] names; [id] is its `piece` text argument. */
enum class PieceKind(
    val id: String,
) {
    CENTER("center"),
    CORNER("corner"),
    EDGE("edge"),
}

/**
 * The single gate from [Facelets] to [CubeState]. Rules run in order and the first failure is
 * returned: color counts, impossible pieces (centers, corners, edges), twisted corner, flipped edge,
 * parity. Never throws.
 */
object CubeValidator {
    private const val PER_COLOR = 9

    fun validate(facelets: Facelets): ValidationResult {
        val colors = facelets.colors
        val counts = Color.entries.associateWith { c -> colors.count { it == c } }
        if (counts.values.any { it != PER_COLOR }) return ValidationResult.WrongColorCounts(counts)

        val orientation =
            orientationOf(colors)
                ?: return ValidationResult.ImpossiblePiece(FaceletTables.CENTERS.sorted(), PieceKind.CENTER)
        val learnerOf = FaceletTables.learnerOf(orientation)
        val home = List(Facelets.SIZE) { colors[learnerOf[it]] }

        val corners = FaceletTables.CORNER_FACELETS.map { slot -> decodeCorner(slot.map { home[it] }) }
        faults(corners, FaceletTables.CORNER_FACELETS, learnerOf)?.let {
            return ValidationResult.ImpossiblePiece(it, PieceKind.CORNER)
        }
        val edges = FaceletTables.EDGE_FACELETS.map { slot -> decodeEdge(slot.map { home[it] }) }
        faults(edges, FaceletTables.EDGE_FACELETS, learnerOf)?.let {
            return ValidationResult.ImpossiblePiece(it, PieceKind.EDGE)
        }

        val cubies =
            Cubies(
                cornerPermutation = corners.map { it!!.cubie },
                cornerOrientation = corners.map { it!!.twist },
                edgePermutation = edges.map { it!!.cubie },
                edgeOrientation = edges.map { it!!.twist },
            )
        return when {
            cubies.cornerOrientation.sum() % Cubies.TWISTS != 0 -> ValidationResult.TwistedCorner
            cubies.edgeOrientation.sum() % Cubies.FLIPS != 0 -> ValidationResult.FlippedEdge
            parity(cubies.cornerPermutation) != parity(cubies.edgePermutation) -> ValidationResult.ParityViolation
            else -> ValidationResult.Valid.of(CubeState(cubies, orientation))
        }
    }

    /** A decoded piece: which cubie sits in the slot and its twist (or flip) there. */
    private data class Piece(
        val cubie: Int,
        val twist: Int,
    )

    private val homeFaceOf: Map<Color, Face> = Face.entries.associateBy { Color.home(it) }

    /** The orientation the six centers show, or null when they show none of the 24. */
    private fun orientationOf(colors: List<Color>): Orientation? {
        val up = homeFaceOf.getValue(colors[FaceletTables.center(Face.U)])
        val front = homeFaceOf.getValue(colors[FaceletTables.center(Face.F)])
        if (up == front || up.opposite == front) return null
        val orientation = Orientation.of(up, front)
        return orientation.takeIf { o -> Face.entries.all { colors[FaceletTables.center(it)] == o.colorAt(it) } }
    }

    /** Corner colors in slot facet order (clockwise from the U/D facet), or null if no real corner shows them. */
    private fun decodeCorner(colors: List<Color>): Piece? {
        val faces = colors.map { homeFaceOf.getValue(it) }
        val twist = faces.indexOfFirst { it == Face.U || it == Face.D }
        if (twist < 0) return null
        val fromUD = List(Cubies.TWISTS) { faces[(twist + it) % Cubies.TWISTS] }
        val cubie = FaceletTables.CORNER_FACES.indexOf(fromUD)
        return if (cubie < 0) null else Piece(cubie, twist)
    }

    /** Edge colors in slot facet order (reference facet first), or null if no real edge shows them. */
    private fun decodeEdge(colors: List<Color>): Piece? {
        val faces = colors.map { homeFaceOf.getValue(it) }
        val straight = FaceletTables.EDGE_FACES.indexOf(faces)
        if (straight >= 0) return Piece(straight, 0)
        val flipped = FaceletTables.EDGE_FACES.indexOf(faces.reversed())
        return if (flipped >= 0) Piece(flipped, 1) else null
    }

    /**
     * Learner sticker indices, ascending, of every slot that shows no real cubie or a cubie that also
     * appears elsewhere; null when every slot holds a distinct real cubie.
     */
    private fun faults(
        pieces: List<Piece?>,
        facelets: List<List<Int>>,
        learnerOf: IntArray,
    ): List<Int>? {
        val seen = pieces.filterNotNull().groupingBy { it.cubie }.eachCount()
        val bad = pieces.indices.filter { pieces[it] == null || seen.getValue(pieces[it]!!.cubie) > 1 }
        if (bad.isEmpty()) return null
        return bad.flatMap { slot -> facelets[slot].map { learnerOf[it] } }.sorted()
    }

    private fun parity(permutation: List<Int>): Int {
        var inversions = 0
        for (i in permutation.indices) {
            for (j in i + 1 until permutation.size) if (permutation[i] > permutation[j]) inversions++
        }
        return inversions % 2
    }
}
