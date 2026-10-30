package dev.rubikxplorer.domain.predicate

import dev.rubikxplorer.domain.cube.Color
import dev.rubikxplorer.domain.cube.FaceletTables
import dev.rubikxplorer.domain.cube.Facelets

/** A cubie named by its sticker [colors] (2 for an edge, 3 for a corner), so it is orientation-invariant. */
data class Piece(
    val colors: Set<Color>,
) {
    companion object {
        /**
         * The piece that sticker [index] of [facelets] belongs to, named by the colors of every sticker
         * in that corner or edge slot; `null` for a center, which is not a [Piece].
         */
        fun at(
            facelets: Facelets,
            index: Int,
        ): Piece? {
            val slot =
                FaceletTables.CORNER_FACELETS.firstOrNull { index in it }
                    ?: FaceletTables.EDGE_FACELETS.firstOrNull { index in it }
                    ?: return null
            return Piece(slot.map { facelets.colors[it] }.toSet())
        }
    }
}
