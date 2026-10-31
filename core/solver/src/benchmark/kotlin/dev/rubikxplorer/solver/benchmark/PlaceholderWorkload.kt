package dev.rubikxplorer.solver.benchmark

import dev.rubikxplorer.application.port.RandomSource
import dev.rubikxplorer.application.usecase.NewScramble
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.inverse
import kotlin.random.Random

/**
 * Placeholder NFR-3 workload until the solver exists: the solved state plus [scrambles] seeded
 * Scrambles from [NewScramble]; an attempt applies the Scramble's inverse (reversed, each Move
 * inverted) and succeeds when the cube is solved. It only exercises the harness and the cube engine.
 *
 * Story 3.3 replaces it with a workload that calls `CubeSolver.solve` on a representative
 * random-legal-state corpus.
 */
class PlaceholderWorkload(
    private val scrambles: Int,
    private val seed: Long,
) : SolveWorkload<PlaceholderWorkload.Case> {
    init {
        require(scrambles > 0) { "Scramble count must be positive: $scrambles" }
    }

    /** A corpus case: the state to solve and the Moves that produced it from home. */
    data class Case(
        val state: CubeState,
        val scramble: List<Move>,
    )

    override val name: String = "placeholder (Scramble then inverse), seed $seed"

    override fun corpus(): List<Case> {
        val newScramble = NewScramble(SeededRandomSource(seed))
        val scrambled =
            List(scrambles) {
                val moves = newScramble()
                Case(CubeState.SOLVED.apply(moves), moves)
            }
        return listOf(Case(CubeState.SOLVED, emptyList())) + scrambled
    }

    override fun attempt(case: Case): Boolean = case.state.apply(case.scramble.inverse()).isSolved
}

/** A [RandomSource] with a pinned seed, so the same seed always yields the same corpus. */
class SeededRandomSource(
    seed: Long,
) : RandomSource {
    private val random = Random(seed)

    override fun nextInt(until: Int): Int = random.nextInt(until)
}
