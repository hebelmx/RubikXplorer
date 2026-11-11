package dev.cubentia.solver.benchmark

import dev.cubentia.application.port.CubeSolver
import dev.cubentia.application.port.SolveResult
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.solver.PruningTables
import dev.cubentia.solver.TwoPhaseCubeSolver
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

/** No generating Moves or inverse solutions are retained. Verification uses the public domain oracle. */
class SolverWorkload(
    private val cases: Int,
    private val seed: Long,
    private val solver: CubeSolver = TwoPhaseCubeSolver(preparedTables),
) : SolveWorkload<CubeState> {
    init {
        require(cases > 0)
    }

    override val name = "two-phase; $cases uniform legal states + solved + 100 deep Scrambles; seed $seed"

    override fun corpus(): List<CubeState> {
        val random = Random(seed)
        val uniform = List(cases) { randomLegalState(random) }
        val deep =
            List(100) {
                CubeState.SOLVED.apply(List(100) { FaceTurn(Face.entries[random.nextInt(6)], Turn.entries[random.nextInt(3)]) })
            }
        return listOf(CubeState.SOLVED) + uniform + deep
    }

    override fun attempt(case: CubeState): Boolean =
        runBlocking {
            val result = solver.solve(case.cubies)
            result is SolveResult.Solved && case.apply(result.moves).isSolved
        }

    companion object {
        // Preparation is deliberately outside all timed attempts; production delivery remains 3.8.
        val preparedTables: PruningTables by lazy { PruningTables.generate() }
    }
}

/** Uniform permutations, constrained orientations and matched parity give uniform legal cubies. */
fun randomLegalState(random: Random): CubeState {
    val cp = (0..7).shuffled(random)
    val ep = (0..11).shuffled(random).toMutableList()

    fun parity(p: List<Int>) = p.indices.sumOf { i -> (0 until i).count { p[it] > p[i] } } % 2
    if (parity(cp) != parity(ep)) {
        val first = ep[0]
        ep[0] = ep[1]
        ep[1] = first
    }
    val co = MutableList(8) { if (it < 7) random.nextInt(3) else 0 }
    co[7] = (3 - co.sum() % 3) % 3
    val eo = MutableList(12) { if (it < 11) random.nextInt(2) else 0 }
    eo[11] = eo.sum() % 2
    val corners =
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
    val edges =
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
    val home = CubeState.SOLVED.toFacelets().colors
    val colors = home.toMutableList()
    for (slot in corners.indices) {
        for (facet in 0..2) {
            colors[corners[slot][facet]] = home[corners[cp[slot]][(facet - co[slot] + 3) % 3]]
        }
    }
    for (slot in edges.indices) {
        for (facet in 0..1) {
            colors[edges[slot][facet]] = home[edges[ep[slot]][(facet + eo[slot]) % 2]]
        }
    }
    return (CubeValidator.validate(Facelets(colors)) as ValidationResult.Valid).state
}
