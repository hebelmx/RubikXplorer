package dev.rubikxplorer.domain.predicate

import dev.rubikxplorer.domain.cube.Color
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceletTables

/** Shared reading of the white layer for the color-anchored white-face predicates. */
internal object WhiteLayer {
    private const val PER_FACE = 9

    /**
     * The pieces among [pieces] (sticker indices per slot) that touch the face whose center is WHITE
     * and sit correctly: WHITE on that face and every other sticker equal to the center color of the
     * face it sits on. Each piece is named by its colors.
     */
    fun correctPieces(
        state: CubeState,
        pieces: List<List<Int>>,
    ): Set<Piece> {
        val colors = state.toFacelets().colors
        val white = Face.entries.single { colors[FaceletTables.center(it)] == Color.WHITE }
        return pieces
            .mapNotNull { stickers ->
                val onWhite = stickers.firstOrNull { faceOf(it) == white } ?: return@mapNotNull null
                val sides = stickers.filter { it != onWhite }
                val correct =
                    colors[onWhite] == Color.WHITE && sides.all { colors[it] == colors[FaceletTables.center(faceOf(it))] }
                if (correct) Piece(stickers.map { colors[it] }.toSet()) else null
            }.toSet()
    }

    private fun faceOf(index: Int): Face = Face.entries[index / PER_FACE]
}
