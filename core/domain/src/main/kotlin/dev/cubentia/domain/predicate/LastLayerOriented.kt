package dev.cubentia.domain.predicate

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceletTables
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The last layer oriented (Stage 5): all 9 stickers of the face opposite the WHITE-centered face
 * match that face's center color (the 4 edges and 4 corners on that face show that color there).
 * Permutation of the pieces does not matter.
 */
@Serializable
@SerialName("last-layer-oriented")
data object LastLayerOriented : Predicate {
    private const val LAST_LAYER_PIECES = 8
    private const val PER_FACE = 9

    override fun isSatisfied(state: CubeState): Boolean = orientedPieces(state).size == LAST_LAYER_PIECES

    override fun preservedPieces(state: CubeState): Set<Piece> = orientedPieces(state)

    override fun progress(state: CubeState): ObjectiveProgress = ObjectiveProgress(orientedPieces(state).size, LAST_LAYER_PIECES)

    private fun orientedPieces(state: CubeState): Set<Piece> {
        val colors = state.toFacelets().colors
        val white = Face.entries.single { colors[FaceletTables.center(it)] == Color.WHITE }
        val opposite = white.opposite
        val oppositeColor = colors[FaceletTables.center(opposite)]
        val pieces = FaceletTables.EDGE_FACELETS + FaceletTables.CORNER_FACELETS
        return pieces
            .mapNotNull { stickers ->
                val onOpposite = stickers.firstOrNull { Face.entries[it / PER_FACE] == opposite } ?: return@mapNotNull null
                if (colors[onOpposite] == oppositeColor) Piece(stickers.map { colors[it] }.toSet()) else null
            }.toSet()
    }
}
