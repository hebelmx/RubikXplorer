package dev.rubikxplorer.domain.predicate

import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceletTables
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The cube solved (Stage 6): all six faces uniform. Progress counts solved pieces (12 edges + 8 corners)
 * out of 20.
 */
@Serializable
@SerialName("cube-solved")
data object CubeSolved : Predicate {
    private const val TOTAL_PIECES = 20
    private const val PER_FACE = 9

    override fun isSatisfied(state: CubeState): Boolean = solvedPieces(state).size == TOTAL_PIECES

    override fun preservedPieces(state: CubeState): Set<Piece> = solvedPieces(state)

    override fun progress(state: CubeState): ObjectiveProgress = ObjectiveProgress(solvedPieces(state).size, TOTAL_PIECES)

    private fun solvedPieces(state: CubeState): Set<Piece> {
        val colors = state.toFacelets().colors
        val pieces = FaceletTables.EDGE_FACELETS + FaceletTables.CORNER_FACELETS
        return pieces
            .mapNotNull { stickers ->
                val correct = stickers.all { colors[it] == colors[FaceletTables.center(Face.entries[it / PER_FACE])] }
                if (correct) Piece(stickers.map { colors[it] }.toSet()) else null
            }.toSet()
    }
}
