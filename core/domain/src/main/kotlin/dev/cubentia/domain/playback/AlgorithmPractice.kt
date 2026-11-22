package dev.cubentia.domain.playback

import dev.cubentia.domain.content.Algorithm
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationError
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.inverse
import dev.cubentia.domain.lastlayer.CaseStateResult
import dev.cubentia.domain.lastlayer.LastLayerMatcher
import dev.cubentia.domain.lastlayer.LastLayerView
import dev.cubentia.domain.lastlayer.RecognitionDiagram

/**
 * Practicing an Algorithm on a real cube (FR-18, UX Flow 11): the Moves a `SOLVE_PLAYBACK` session plays from
 * [start], with the Algorithm's first Move at [algorithmStart]. From solved the Moves are the set-up (the
 * Algorithm's inverse, AD-19) then the Algorithm; from the Case state they are the Algorithm only. The frame is the
 * one the Library's Demonstration uses, so the set-up reaches exactly the Case the detail showed. Build one with [of].
 */
data class AlgorithmPractice(
    val start: CubeState,
    val moves: List<Move>,
    val algorithmStart: Int,
) {
    /** The set-up Moves (empty when the cube already shows the Case). */
    val setUp: List<Move> get() = moves.take(algorithmStart)

    /** The Algorithm's Moves. */
    val algorithm: List<Move> get() = moves.drop(algorithmStart)

    companion object {
        /**
         * The practice of [algorithm] held with [lastLayer] on U: [fromSetUp] starts from solved and sets the Case up
         * first; otherwise it starts at the Case state ([LastLayerMatcher.caseState]). Never throws.
         */
        fun of(
            algorithm: Algorithm,
            fromSetUp: Boolean,
            lastLayer: Color = RecognitionDiagram.LIBRARY_LAST_LAYER,
        ): AlgorithmPracticeResult {
            val moves =
                when (val parsed = Notation.parse(algorithm.notation)) {
                    is NotationResult.Invalid -> return AlgorithmPracticeResult.InvalidNotation(parsed.error)
                    is NotationResult.Parsed -> parsed.moves
                }
            if (fromSetUp) {
                val solved = CubeState.SOLVED.copy(orientation = LastLayerView.canonicalFrame(lastLayer))
                val setUp = moves.inverse()
                return AlgorithmPracticeResult.Ready(AlgorithmPractice(solved, setUp + moves, setUp.size))
            }
            return when (val derived = LastLayerMatcher.caseState(algorithm, lastLayer)) {
                is CaseStateResult.BadNotation -> AlgorithmPracticeResult.InvalidNotation(derived.error)
                is CaseStateResult.Derived -> AlgorithmPracticeResult.Ready(AlgorithmPractice(derived.state, moves, 0))
            }
        }
    }
}

/** The outcome of [AlgorithmPractice.of]. */
sealed interface AlgorithmPracticeResult {
    /** The Algorithm can be practiced. */
    data class Ready(
        val practice: AlgorithmPractice,
    ) : AlgorithmPracticeResult

    /** The Algorithm's Notation does not parse. */
    data class InvalidNotation(
        val error: NotationError,
    ) : AlgorithmPracticeResult
}
