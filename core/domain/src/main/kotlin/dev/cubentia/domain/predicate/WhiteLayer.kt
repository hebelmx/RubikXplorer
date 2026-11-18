package dev.cubentia.domain.predicate

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceletTables

/** Shared reading of the white layer for the color-anchored white-face predicates. */
internal object WhiteLayer {
    private const val PER_FACE = 9

    /**
     * The pieces among [pieces] (sticker indices per slot) that touch the face whose center is [centerColor]
     * and sit correctly: [centerColor] on that face and every other sticker equal to the center color of the
     * face it sits on. Each piece is named by its colors.
     */
    fun correctPieces(
        state: CubeState,
        pieces: List<List<Int>>,
        centerColor: Color = Color.WHITE,
    ): Set<Piece> {
        val colors = state.toFacelets().colors
        val centerFace = Face.entries.single { colors[FaceletTables.center(it)] == centerColor }
        return pieces
            .mapNotNull { stickers ->
                val onCenterFace = stickers.firstOrNull { faceOf(it) == centerFace } ?: return@mapNotNull null
                val sides = stickers.filter { it != onCenterFace }
                val correct =
                    colors[onCenterFace] == centerColor && sides.all { colors[it] == colors[FaceletTables.center(faceOf(it))] }
                if (correct) Piece(stickers.map { colors[it] }.toSet()) else null
            }.toSet()
    }

    /**
     * True when [piece]'s colors sit in the slot whose face centers are exactly its colors, but the
     * piece is not correct there: twisted (a corner) or flipped (an edge). Color-anchored like
     * [correctPieces], so it never reads `state.orientation` in effect.
     */
    fun misorientedInSlot(
        state: CubeState,
        piece: Piece,
    ): Boolean {
        val colors = state.toFacelets().colors
        val slot =
            (FaceletTables.CORNER_FACELETS + FaceletTables.EDGE_FACELETS).firstOrNull { stickers ->
                stickers.map { colors[FaceletTables.center(faceOf(it))] }.toSet() == piece.colors
            } ?: return false
        val holds = slot.map { colors[it] }.toSet() == piece.colors
        val correct = slot.all { colors[it] == colors[FaceletTables.center(faceOf(it))] }
        return holds && !correct
    }

    private fun faceOf(index: Int): Face = Face.entries[index / PER_FACE]
}
