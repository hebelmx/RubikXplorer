package dev.cubentia.domain.cube

import dev.cubentia.domain.text.Text

/**
 * The outcome of [CubeValidator.validate]: a legal [Valid] state or its first [Invalid] reason.
 * [CubeValidator.problems] lists every [Invalid] reason at once.
 */
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
 * The single gate from [Facelets] to [CubeState]. Rules run in order: color counts, impossible
 * pieces (centers, corners, edges), twisted corner, flipped edge, parity. [validate] returns the first
 * failure (or `Valid`); [problems] reports all failures for an editor, never a state. Never throws.
 */
object CubeValidator {
    private const val PER_COLOR = 9

    fun validate(facelets: Facelets): ValidationResult {
        val analysis = analyze(facelets)
        return analysis.problems.firstOrNull() ?: ValidationResult.Valid.of(checkNotNull(analysis.state))
    }

    /**
     * Every failure an editor can report at once, in [validate]'s rule order; empty exactly when
     * [validate] is `Valid`, and otherwise led by [validate]'s failure. Wrong color counts and
     * impossible centers stand alone; impossible corners and edges are both listed; once every piece
     * decodes, each applicable non-local failure (twist, flip, parity) gets its own entry. Only reports
     * failures: a `Valid` result is reachable through [validate] alone.
     */
    fun problems(facelets: Facelets): List<ValidationResult.Invalid> = analyze(facelets).problems

    /** The failures found, and the validated state when there are none. */
    private class Analysis(
        val problems: List<ValidationResult.Invalid>,
        val state: CubeState? = null,
    )

    private fun analyze(facelets: Facelets): Analysis {
        val colors = facelets.colors
        val counts = Color.entries.associateWith { c -> colors.count { it == c } }
        if (counts.values.any { it != PER_COLOR }) return Analysis(listOf(ValidationResult.WrongColorCounts(counts)))

        val orientation =
            orientationOf(colors)
                ?: return Analysis(listOf(ValidationResult.ImpossiblePiece(FaceletTables.CENTERS.sorted(), PieceKind.CENTER)))
        val learnerOf = FaceletTables.learnerOf(orientation)
        val home = List(Facelets.SIZE) { colors[learnerOf[it]] }

        val corners = FaceletTables.CORNER_FACELETS.map { slot -> decodeCorner(slot.map { home[it] }) }
        val edges = FaceletTables.EDGE_FACELETS.map { slot -> decodeEdge(slot.map { home[it] }) }
        val impossible =
            listOfNotNull(
                faults(corners, FaceletTables.CORNER_FACELETS, learnerOf)?.let { ValidationResult.ImpossiblePiece(it, PieceKind.CORNER) },
                faults(edges, FaceletTables.EDGE_FACELETS, learnerOf)?.let { ValidationResult.ImpossiblePiece(it, PieceKind.EDGE) },
            )
        if (impossible.isNotEmpty()) return Analysis(impossible)

        val cubies =
            Cubies(
                cornerPermutation = corners.map { it!!.cubie },
                cornerOrientation = corners.map { it!!.twist },
                edgePermutation = edges.map { it!!.cubie },
                edgeOrientation = edges.map { it!!.twist },
            )
        val global =
            buildList {
                if (cubies.cornerOrientation.sum() % Cubies.TWISTS != 0) add(ValidationResult.TwistedCorner)
                if (cubies.edgeOrientation.sum() % Cubies.FLIPS != 0) add(ValidationResult.FlippedEdge)
                if (parity(cubies.cornerPermutation) != parity(cubies.edgePermutation)) add(ValidationResult.ParityViolation)
            }
        return Analysis(global, if (global.isEmpty()) CubeState(cubies, orientation) else null)
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
