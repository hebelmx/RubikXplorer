package dev.rubikxplorer.domain.predicate

import dev.rubikxplorer.domain.cube.Color
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Cubies
import dev.rubikxplorer.domain.cube.FaceletTables
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A Notation exercise (Stage 1): the pattern [moves] make on a solved cube, Moves read in the home
 * frame (WHITE on U, GREEN on F). Judged by the resulting cubies, which are relative to the centers,
 * so any equivalent sequence counts and holding the cube in another orientation does not matter.
 * Progress counts the 20 corner and edge slots whose cubie and twist or flip match the target.
 */
@Serializable
@SerialName("state-reached")
data class StateReached(
    val moves: String,
) : Predicate {
    @Transient
    private val target: Cubies =
        requireNotNull(Notation.parse(moves) as? NotationResult.Parsed) { "state-reached moves are not valid Notation: \"$moves\"" }
            .let { CubeState.SOLVED.apply(it.moves).cubies }

    override fun isSatisfied(state: CubeState): Boolean = state.cubies == target

    override fun preservedPieces(state: CubeState): Set<Piece> = matchingPieces(state.cubies)

    override fun progress(state: CubeState): ObjectiveProgress = ObjectiveProgress(matchingPieces(state.cubies).size, SLOTS)

    /** The pieces in slots that already match the target, each named by its colors. */
    private fun matchingPieces(cubies: Cubies): Set<Piece> {
        val corners =
            (0 until CORNER_SLOTS)
                .filter {
                    cubies.cornerPermutation[it] == target.cornerPermutation[it] &&
                        cubies.cornerOrientation[it] == target.cornerOrientation[it]
                }.map { piece(FaceletTables.CORNER_FACES[cubies.cornerPermutation[it]].map(Color::home)) }
        val edges =
            (0 until EDGE_SLOTS)
                .filter {
                    cubies.edgePermutation[it] == target.edgePermutation[it] &&
                        cubies.edgeOrientation[it] == target.edgeOrientation[it]
                }.map { piece(FaceletTables.EDGE_FACES[cubies.edgePermutation[it]].map(Color::home)) }
        return (corners + edges).toSet()
    }

    private fun piece(colors: List<Color>) = Piece(colors.toSet())

    private companion object {
        const val CORNER_SLOTS = 8
        const val EDGE_SLOTS = 12
        const val SLOTS = CORNER_SLOTS + EDGE_SLOTS
    }
}
