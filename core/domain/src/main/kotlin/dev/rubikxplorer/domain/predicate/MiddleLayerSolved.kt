package dev.rubikxplorer.domain.predicate

import dev.rubikxplorer.domain.cube.Color
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceletTables
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The middle layer (Stage 4): the 4 edges connecting the side faces (neither on the WHITE-centered face
 * nor on its opposite face) sit correctly, matching the center colors of the faces they sit on.
 */
@Serializable
@SerialName("middle-layer-solved")
data object MiddleLayerSolved : Predicate {
    private const val MIDDLE_EDGES = 4
    private const val PER_FACE = 9

    override fun isSatisfied(state: CubeState): Boolean = correctEdges(state).size == MIDDLE_EDGES

    override fun preservedPieces(state: CubeState): Set<Piece> = correctEdges(state)

    override fun progress(state: CubeState): ObjectiveProgress = ObjectiveProgress(correctEdges(state).size, MIDDLE_EDGES)

    private fun correctEdges(state: CubeState): Set<Piece> {
        val colors = state.toFacelets().colors
        val white = Face.entries.single { colors[FaceletTables.center(it)] == Color.WHITE }
        val opposite = white.opposite
        return FaceletTables.EDGE_FACELETS
            .mapNotNull { stickers ->
                val faces = stickers.map { Face.entries[it / PER_FACE] }
                if (white in faces || opposite in faces) return@mapNotNull null
                val correct = stickers.all { colors[it] == colors[FaceletTables.center(Face.entries[it / PER_FACE])] }
                if (correct) Piece(stickers.map { colors[it] }.toSet()) else null
            }.toSet()
    }
}
