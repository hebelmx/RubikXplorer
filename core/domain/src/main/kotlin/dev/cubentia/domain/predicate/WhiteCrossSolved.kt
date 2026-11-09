package dev.cubentia.domain.predicate

import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.FaceletTables
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The white cross, aligned: each of the 4 edges on the face whose center is WHITE shows WHITE there,
 * and its other sticker matches the center color of the face it sits on. Color-anchored: it reads the
 * Learner's stickers and finds every face by its center, never assuming WHITE is on U.
 */
@Serializable
@SerialName("white-cross-solved")
data object WhiteCrossSolved : Predicate {
    private const val CROSS_EDGES = 4

    override fun isSatisfied(state: CubeState): Boolean = correctEdges(state).size == CROSS_EDGES

    override fun preservedPieces(state: CubeState): Set<Piece> = correctEdges(state)

    override fun progress(state: CubeState): ObjectiveProgress = ObjectiveProgress(correctEdges(state).size, CROSS_EDGES)

    /** The cross edges that sit correctly, each named by its two colors. */
    private fun correctEdges(state: CubeState): Set<Piece> = WhiteLayer.correctPieces(state, FaceletTables.EDGE_FACELETS)
}
