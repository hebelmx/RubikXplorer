package dev.cubentia.domain.predicate

import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.FaceletTables
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The first-layer corners (Stage 3): each of the 4 corners touching the face whose center is WHITE
 * shows WHITE there, and its other two stickers match the center colors of the faces they sit on.
 * It says nothing about the cross; combine with [WhiteCrossSolved] in an [AllOf] to keep it.
 */
@Serializable
@SerialName("white-corners-solved")
data object WhiteCornersSolved : Predicate {
    private const val CORNERS = 4

    override fun isSatisfied(state: CubeState): Boolean = correctCorners(state).size == CORNERS

    override fun preservedPieces(state: CubeState): Set<Piece> = correctCorners(state)

    override fun progress(state: CubeState): ObjectiveProgress = ObjectiveProgress(correctCorners(state).size, CORNERS)

    /** The white corners that sit correctly, each named by its three colors. */
    private fun correctCorners(state: CubeState): Set<Piece> = WhiteLayer.correctPieces(state, FaceletTables.CORNER_FACELETS)
}
