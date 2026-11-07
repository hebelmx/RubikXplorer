package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.cube.Color
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceletTables
import dev.rubikxplorer.domain.predicate.AllOf
import dev.rubikxplorer.domain.predicate.AlwaysTrue
import dev.rubikxplorer.domain.predicate.AnyOf
import dev.rubikxplorer.domain.predicate.CubeSolved
import dev.rubikxplorer.domain.predicate.LastLayerCornersPlaced
import dev.rubikxplorer.domain.predicate.LastLayerCrossSolved
import dev.rubikxplorer.domain.predicate.LastLayerOriented
import dev.rubikxplorer.domain.predicate.MiddleLayerSolved
import dev.rubikxplorer.domain.predicate.Piece
import dev.rubikxplorer.domain.predicate.Predicate
import dev.rubikxplorer.domain.predicate.StateReached
import dev.rubikxplorer.domain.predicate.WhiteCornersSolved
import dev.rubikxplorer.domain.predicate.WhiteCrossSolved
import dev.rubikxplorer.domain.predicate.WhiteLayer
import dev.rubikxplorer.domain.predicate.WhitePieceSolved
import dev.rubikxplorer.domain.text.Text

/** What a Diagnosis rule found: the [category], its keyed [explanation] and the [disturbed] preserved pieces. */
internal data class Finding(
    val category: ErrorCategory,
    val explanation: Text,
    val disturbed: Set<Piece>,
)

/**
 * The deterministic, ordered Diagnosis rules (AD-5, FR-9): a pure table over the Lesson, its entry
 * state, the state the tutor expected and the one it observed. How sure the tutor is belongs to
 * [Assess], which weighs the candidates; these rules only name what changed. "No candidate explains
 * the evidence" (unknown discrepancy) is also [Assess]'s rule, checked right after [orientation].
 */
internal object DiagnosisRules {
    /** The categories that mean the Move disturbed a preserved piece: a *Test me* Move with one is Harmful. */
    val DISTURBANCE: Set<ErrorCategory> =
        setOf(
            ErrorCategory.COMPLETED_LAYER_DISTURBED,
            ErrorCategory.WRONG_PIECE,
            ErrorCategory.WRONG_MOVE_OR_DIRECTION,
        )

    /**
     * Rule 1 (AD-2): the cubies are unchanged but the cube is held in another orientation than the
     * expected one, whatever it is. The copy names the entry's colors on the Learner's D and F.
     * Rotating back to the Lesson's hold is a recovery, which [Assess.classify] tells apart.
     */
    fun orientation(
        entry: CubeState,
        expected: CubeState,
        observed: CubeState,
    ): Finding? {
        val turned = observed.cubies == expected.cubies && observed.orientation != expected.orientation
        if (!turned) return null
        val explanation =
            Text(
                "msg.diagnosis.unexpected-orientation",
                mapOf(
                    "bottom" to colorId(entry.orientation.colorAt(Face.D)),
                    "front" to colorId(entry.orientation.colorAt(Face.F)),
                ),
            )
        return Finding(ErrorCategory.UNEXPECTED_ORIENTATION, explanation, emptySet())
    }

    /**
     * Rules 3–7, in order, for the current step `s` at [expected] (rule 1 is [orientation], rule 2
     * belongs to [Assess]):
     * 3. a disturbed piece the Lesson preserves → COMPLETED_LAYER_DISTURBED;
     * 4. a disturbed white-layer piece while `s`'s target piece is not yet solved → WRONG_PIECE;
     * 5. any other disturbance → WRONG_MOVE_OR_DIRECTION;
     * 6. a watched piece of `s` newly in its slot but twisted or flipped → WRONG_PIECE_ORIENTATION;
     * 7. else nothing.
     */
    fun findings(
        lesson: Lesson,
        entry: CubeState,
        expected: CubeState,
        observed: CubeState,
    ): Finding? {
        orientation(entry, expected, observed)?.let { return it }
        val step = lesson.steps.getOrNull(Tutor.currentStep(lesson, entry, expected)) ?: return null
        val disturbed = disturbed(lesson, entry, expected, observed)
        if (disturbed.isNotEmpty()) {
            val layer = disturbed intersect lesson.preserve.preservedPieces(expected)
            if (layer.isNotEmpty()) {
                val explanation = Text("msg.diagnosis.completed-layer-disturbed", mapOf("layer" to layerId(layer)))
                return Finding(ErrorCategory.COMPLETED_LAYER_DISTURBED, explanation, disturbed)
            }
            val target = leaves(step.done).filterIsInstance<WhitePieceSolved>().firstOrNull { !it.isSatisfied(expected) }
            val moved = WhitePieceSolved.NAMES.firstOrNull { WhitePieceSolved.pieceNamed(it) in disturbed }
            if (target != null && moved != null) {
                val explanation =
                    Text("msg.diagnosis.wrong-piece", mapOf("moved" to selectId(moved), "target" to selectId(target.piece)))
                return Finding(ErrorCategory.WRONG_PIECE, explanation, disturbed)
            }
            return Finding(ErrorCategory.WRONG_MOVE_OR_DIRECTION, Text("msg.diagnosis.wrong-move-or-direction"), disturbed)
        }
        val twisted =
            watched(step.done).firstOrNull { piece ->
                WhiteLayer.misorientedInSlot(observed, piece) && !WhiteLayer.misorientedInSlot(expected, piece)
            } ?: return null
        val kind = if (WhitePieceSolved.isEdge(twisted)) "edge" else "corner"
        val explanation = Text("msg.diagnosis.wrong-piece-orientation", mapOf("kind" to kind, "face" to whiteFace(observed)))
        return Finding(ErrorCategory.WRONG_PIECE_ORIENTATION, explanation, emptySet())
    }

    /**
     * The preserved pieces of the step at [expected] that [observed] no longer keeps. Preserved are
     * `lesson.preserve`'s pieces plus those of every other step already done (effective done, AD-6);
     * a piece is disturbed when no longer among the same predicates' pieces at [observed] and the
     * Move actually moved it (an `anyOf` may pick another branch, which moves nothing).
     */
    fun disturbed(
        lesson: Lesson,
        entry: CubeState,
        expected: CubeState,
        observed: CubeState,
    ): Set<Piece> {
        val s = Tutor.currentStep(lesson, entry, expected)
        val kept =
            listOf(lesson.preserve) +
                lesson.steps.indices
                    .filter { it != s && Tutor.stepDone(lesson, it, expected) }
                    .map { lesson.steps[it].done }
        val before = kept.flatMapTo(mutableSetOf()) { it.preservedPieces(expected) }
        val after = kept.flatMapTo(mutableSetOf()) { it.preservedPieces(observed) }
        return (before - after) intersect movedPieces(expected, observed)
    }

    /** Every leaf predicate of [predicate], walking `allOf` and `anyOf`. */
    private fun leaves(predicate: Predicate): List<Predicate> =
        when (predicate) {
            is AllOf -> predicate.predicates.flatMap(::leaves)

            is AnyOf -> predicate.predicates.flatMap(::leaves)

            is AlwaysTrue, is StateReached, is WhiteCornersSolved, is WhiteCrossSolved, is WhitePieceSolved,
            MiddleLayerSolved, LastLayerCrossSolved, LastLayerOriented, LastLayerCornersPlaced, CubeSolved,
            -> listOf(predicate)
        }

    /** The white-layer pieces [predicate] watches, in canonical order. */
    private fun watched(predicate: Predicate): List<Piece> {
        val pieces =
            leaves(predicate).flatMapTo(mutableSetOf()) { leaf ->
                when (leaf) {
                    is WhiteCrossSolved -> WhitePieceSolved.EDGE_NAMES

                    is WhiteCornersSolved -> WhitePieceSolved.CORNER_NAMES

                    is WhitePieceSolved -> listOf(leaf.piece)

                    is AllOf, is AnyOf, is AlwaysTrue, is StateReached,
                    MiddleLayerSolved, LastLayerCrossSolved, LastLayerOriented, LastLayerCornersPlaced, CubeSolved,
                    -> emptyList()
                }
            }
        return WhitePieceSolved.NAMES.filter { it in pieces }.mapNotNull(WhitePieceSolved::pieceNamed)
    }

    /** The pieces in a slot whose cubie or its twist or flip differs between [a] and [b], named by their colors. */
    private fun movedPieces(
        a: CubeState,
        b: CubeState,
    ): Set<Piece> {
        val x = a.cubies
        val y = b.cubies
        val corners =
            x.cornerPermutation.indices
                .filter { x.cornerPermutation[it] != y.cornerPermutation[it] || x.cornerOrientation[it] != y.cornerOrientation[it] }
                .flatMap { listOf(x.cornerPermutation[it], y.cornerPermutation[it]) }
                .map { piece(FaceletTables.CORNER_FACES[it]) }
        val edges =
            x.edgePermutation.indices
                .filter { x.edgePermutation[it] != y.edgePermutation[it] || x.edgeOrientation[it] != y.edgeOrientation[it] }
                .flatMap { listOf(x.edgePermutation[it], y.edgePermutation[it]) }
                .map { piece(FaceletTables.EDGE_FACES[it]) }
        return (corners + edges).toSet()
    }

    private fun piece(faces: List<Face>): Piece = Piece(faces.map(Color::home).toSet())

    /** `cross` when every disturbed layer piece is a white edge, `first_layer` when all are white-layer pieces, else `other`. */
    private fun layerId(layer: Set<Piece>): String =
        when {
            layer.all { Color.WHITE in it.colors && WhitePieceSolved.isEdge(it) } -> "cross"
            layer.all { Color.WHITE in it.colors } -> "first_layer"
            else -> "other"
        }

    /** The Learner face holding the WHITE center at [state], as a select id. */
    private fun whiteFace(state: CubeState): String =
        when (Face.entries.single { state.orientation.colorAt(it) == Color.WHITE }) {
            Face.U -> "up"
            Face.D -> "down"
            Face.F -> "front"
            Face.B -> "back"
            Face.L -> "left"
            Face.R -> "right"
        }

    private fun colorId(color: Color): String = color.name.lowercase()

    /** ICU select keywords cannot hold `-`, so a canonical piece name is passed with `_` (`white_green`). */
    internal fun selectId(name: String): String = name.replace('-', '_')
}
