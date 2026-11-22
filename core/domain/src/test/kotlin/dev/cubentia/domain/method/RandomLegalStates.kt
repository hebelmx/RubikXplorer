package dev.cubentia.domain.method

import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.FaceletTables
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.cube.ValidationResult
import kotlin.random.Random

/**
 * Corpus states for the method-solve tests, copied from `:core:solver`'s `SolverWorkload` because
 * `:core:domain` may not depend on `:core:solver` (AD-1).
 */
internal object RandomLegalStates {
    private const val DEEP_TURNS = 100

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
        val home = CubeState.SOLVED.toFacelets().colors
        val colors = home.toMutableList()
        for (slot in CORNERS.indices) {
            for (facet in 0..2) {
                colors[CORNERS[slot][facet]] = home[CORNERS[cp[slot]][(facet - co[slot] + 3) % 3]]
            }
        }
        for (slot in EDGES.indices) {
            for (facet in 0..1) {
                colors[EDGES[slot][facet]] = home[EDGES[ep[slot]][(facet + eo[slot]) % 2]]
            }
        }
        return (CubeValidator.validate(Facelets(colors)) as ValidationResult.Valid).state
    }

    /** A deep Scramble: [DEEP_TURNS] random face turns from solved. */
    fun deepScramble(random: Random): CubeState =
        CubeState.SOLVED.apply(List(DEEP_TURNS) { FaceTurn(Face.entries[random.nextInt(6)], Turn.entries[random.nextInt(3)]) })

    private val CORNERS = FaceletTables.CORNER_FACELETS

    private val EDGES = FaceletTables.EDGE_FACELETS
}
