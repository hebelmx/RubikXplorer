package dev.rubikxplorer.domain.playback

import dev.rubikxplorer.domain.content.ContentProblem
import dev.rubikxplorer.domain.content.FaceletString
import dev.rubikxplorer.domain.content.FaceletStringResult
import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.cube.Color
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.CubeValidator
import dev.rubikxplorer.domain.cube.Facelets
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationError
import dev.rubikxplorer.domain.cube.NotationResult
import dev.rubikxplorer.domain.cube.ValidationResult
import dev.rubikxplorer.domain.predicate.Piece

/**
 * What a Demonstration step puts in focus, derived from the Lesson's predicates (no content field):
 * - [keep]: the pieces the Lesson's `preserve` predicate holds at the step's start;
 * - [target]: the pieces the step newly brings into the `completion` predicate's preserved set;
 * - [involved]: the pieces that leave their stickers' start positions at any Move of the step.
 *
 * Centers are always lit; every other piece outside [lit] is dimmed.
 */
data class StepFocus(
    val keep: Set<Piece>,
    val target: Set<Piece>,
    val involved: Set<Piece>,
) {
    /** The pieces drawn undimmed (centers are lit too, but are not pieces). */
    val lit: Set<Piece> get() = keep + involved + target
}

/** One explained step: its [moves], the index of its first Move in [Demonstration.moves], and its [focus]. */
data class DemonstrationStep(
    val moves: List<Move>,
    val firstMove: Int,
    val focus: StepFocus,
)

/**
 * A Lesson's Demonstration (FR-6): the concatenated Moves of its steps, played from its entry state
 * [start], with the [StepFocus] of each step. Build one with [of].
 */
class Demonstration private constructor(
    val start: CubeState,
    val steps: List<DemonstrationStep>,
) {
    /** Every step's Moves, in order. */
    val moves: List<Move> = steps.flatMap { it.moves }

    /**
     * The index of the step that holds Move [moveIndex]; steps without Moves hold none. Indices past
     * either end map to the first or last step that has Moves, and 0 when no step has any.
     */
    fun stepIndexAt(moveIndex: Int): Int {
        val holding = steps.indexOfLast { it.moves.isNotEmpty() && it.firstMove <= moveIndex }
        return if (holding >= 0) holding else steps.indexOfFirst { it.moves.isNotEmpty() }.coerceAtLeast(0)
    }

    companion object {
        /** The Demonstration of [lesson], or why its entry state or step Moves cannot be played. Never throws. */
        fun of(lesson: Lesson): DemonstrationResult {
            val start =
                when (val entry = entryState(lesson)) {
                    is EntryState.Ok -> entry.state
                    is EntryState.Bad -> return DemonstrationResult.InvalidEntryState(entry.problem)
                }
            val stepMoves =
                lesson.steps.mapIndexed { index, step ->
                    when (val parsed = Notation.parse(step.moves)) {
                        is NotationResult.Parsed -> parsed.moves
                        is NotationResult.Invalid -> return DemonstrationResult.InvalidNotation(index, parsed.error)
                    }
                }
            var state = start
            var first = 0
            val steps =
                stepMoves.map { moves ->
                    val s0 = state
                    val path = moves.runningFold(s0) { s, m -> s.apply(m) }
                    val s1 = path.last()
                    val focus =
                        StepFocus(
                            keep = lesson.preserve.preservedPieces(s0),
                            target = lesson.completion.preservedPieces(s1) - lesson.completion.preservedPieces(s0),
                            involved = involved(s0, path.drop(1)),
                        )
                    DemonstrationStep(moves, first, focus).also {
                        state = s1
                        first += moves.size
                    }
                }
            return DemonstrationResult.Ready(Demonstration(start, steps))
        }

        private sealed interface EntryState {
            data class Ok(
                val state: CubeState,
            ) : EntryState

            data class Bad(
                val problem: ContentProblem,
            ) : EntryState
        }

        private fun entryState(lesson: Lesson): EntryState =
            when (val decoded = FaceletString.decode(lesson.entryState)) {
                is FaceletStringResult.Decoded -> {
                    when (val result = CubeValidator.validate(decoded.facelets)) {
                        is ValidationResult.Valid -> EntryState.Ok(result.state)
                        is ValidationResult.Invalid -> EntryState.Bad(ContentProblem.ImpossibleEntryState(lesson.id, result))
                    }
                }

                else -> {
                    EntryState.Bad(ContentProblem.MalformedEntryState(lesson.id, decoded))
                }
            }

        /** The pieces whose stickers sit anywhere other than in [s0] in at least one of [later]. */
        private fun involved(
            s0: CubeState,
            later: List<CubeState>,
        ): Set<Piece> {
            val home = placements(s0.toFacelets())
            return later
                .flatMap { state ->
                    val now = placements(state.toFacelets())
                    home.keys.filter { home[it] != now[it] }
                }.toSet()
        }

        /** Each piece's stickers as `index → color`. */
        private fun placements(facelets: Facelets): Map<Piece, Map<Int, Color>> =
            (0 until Facelets.SIZE)
                .mapNotNull { index -> Piece.at(facelets, index)?.let { it to index } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, indices) -> indices.associateWith { facelets.colors[it] } }
    }
}

/** The outcome of [Demonstration.of]. */
sealed interface DemonstrationResult {
    /** The Lesson's Demonstration can be played. */
    data class Ready(
        val demonstration: Demonstration,
    ) : DemonstrationResult

    /** The Moves of step [stepIndex] (0-based) are not valid Notation. */
    data class InvalidNotation(
        val stepIndex: Int,
        val error: NotationError,
    ) : DemonstrationResult

    /** The Lesson's entry state is malformed or impossible: a [ContentProblem.MalformedEntryState] or [ContentProblem.ImpossibleEntryState]. */
    data class InvalidEntryState(
        val problem: ContentProblem,
    ) : DemonstrationResult
}
