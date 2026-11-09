package dev.cubentia.domain.predicate

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceletTables
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The last-layer cross (Stage 5): each of the 4 edges on the face opposite the WHITE-centered face
 * shows that face's center color there. Color-anchored: it finds WHITE by its center, takes its
 * opposite face, and checks the 4 edge stickers on that opposite face.
 */
@Serializable
@SerialName("last-layer-cross-solved")
data object LastLayerCrossSolved : Predicate {
    private const val CROSS_EDGES = 4
    private const val PER_FACE = 9

    override fun isSatisfied(state: CubeState): Boolean = correctEdges(state).size == CROSS_EDGES

    override fun preservedPieces(state: CubeState): Set<Piece> = correctEdges(state)

    override fun progress(state: CubeState): ObjectiveProgress = ObjectiveProgress(correctEdges(state).size, CROSS_EDGES)

    private fun correctEdges(state: CubeState): Set<Piece> {
        val colors = state.toFacelets().colors
        val white = Face.entries.single { colors[FaceletTables.center(it)] == Color.WHITE }
        val opposite = white.opposite
        val oppositeColor = colors[FaceletTables.center(opposite)]
        return FaceletTables.EDGE_FACELETS
            .mapNotNull { stickers ->
                val onOpposite = stickers.firstOrNull { Face.entries[it / PER_FACE] == opposite } ?: return@mapNotNull null
                if (colors[onOpposite] == oppositeColor) Piece(stickers.map { colors[it] }.toSet()) else null
            }.toSet()
    }
}
