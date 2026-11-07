package dev.rubikxplorer.domain.predicate

import dev.rubikxplorer.domain.cube.Color
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceletTables
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The last-layer corners placed (Stage 6): each of the 4 corners touching the face opposite the
 * WHITE-centered face sits in its correct slot (its three colors match the three face centers
 * meeting at that corner), regardless of twist.
 */
@Serializable
@SerialName("last-layer-corners-placed")
data object LastLayerCornersPlaced : Predicate {
    private const val CORNERS = 4
    private const val PER_FACE = 9

    override fun isSatisfied(state: CubeState): Boolean = placedCorners(state).size == CORNERS

    override fun preservedPieces(state: CubeState): Set<Piece> = placedCorners(state)

    override fun progress(state: CubeState): ObjectiveProgress = ObjectiveProgress(placedCorners(state).size, CORNERS)

    private fun placedCorners(state: CubeState): Set<Piece> {
        val colors = state.toFacelets().colors
        val white = Face.entries.single { colors[FaceletTables.center(it)] == Color.WHITE }
        val opposite = white.opposite
        return FaceletTables.CORNER_FACELETS
            .mapNotNull { stickers ->
                val faces = stickers.map { Face.entries[it / PER_FACE] }
                if (opposite !in faces) return@mapNotNull null
                val slotColors = stickers.map { colors[it] }.toSet()
                val targetColors = faces.map { colors[FaceletTables.center(it)] }.toSet()
                if (slotColors == targetColors) Piece(slotColors) else null
            }.toSet()
    }
}
