package dev.rubikxplorer.domain.predicate

import dev.rubikxplorer.domain.cube.Color
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceletTables
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
    private const val PER_FACE = 9

    override fun isSatisfied(state: CubeState): Boolean = correctEdges(state).size == CROSS_EDGES

    override fun preservedPieces(state: CubeState): Set<Piece> = correctEdges(state)

    override fun progress(state: CubeState): ObjectiveProgress = ObjectiveProgress(correctEdges(state).size, CROSS_EDGES)

    /** The cross edges that sit correctly, each named by its two colors. */
    private fun correctEdges(state: CubeState): Set<Piece> {
        val colors = state.toFacelets().colors
        val white = Face.entries.single { colors[FaceletTables.center(it)] == Color.WHITE }
        return FaceletTables.EDGE_FACELETS
            .mapNotNull { stickers ->
                val onWhite = stickers.firstOrNull { faceOf(it) == white } ?: return@mapNotNull null
                val side = stickers.single { it != onWhite }
                val sideColor = colors[side]
                val correct = colors[onWhite] == Color.WHITE && sideColor == colors[FaceletTables.center(faceOf(side))]
                if (correct) Piece(setOf(Color.WHITE, sideColor)) else null
            }.toSet()
    }

    private fun faceOf(index: Int): Face = Face.entries[index / PER_FACE]
}
