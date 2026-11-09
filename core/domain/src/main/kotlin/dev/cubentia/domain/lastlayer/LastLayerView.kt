package dev.cubentia.domain.lastlayer

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceletTables
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Orientation

/**
 * The last layer in the canonical frame (AD-19): the LL on U, read as [STICKERS] stickers in four
 * blocks of [BLOCK] in F, R, B, L order. Each block is the U edge sticker, the U corner sticker
 * clockwise of it, then that side's top row, so `U^k` is a cyclic shift by `BLOCK * k`.
 *
 * A null sticker is unseen. [ring] holds the center colors of F, R, B, L in this frame.
 */
@ConsistentCopyVisibility
data class LastLayerView private constructor(
    val stickers: List<Color?>,
    val lastLayer: Color,
    val ring: List<Color>,
) {
    companion object {
        /** Stickers in one view: 8 on U (centers are not read) and 12 on the sides. */
        const val STICKERS: Int = 20

        /** Stickers in one block of the view. */
        const val BLOCK: Int = 5

        private const val PER_FACE = 9

        private val SIDES = listOf(Face.F, Face.R, Face.B, Face.L)

        // Facelet indices with the LL on U, in view order.
        private val LAYOUT =
            listOf(
                listOf(7, 6, 18, 19, 20),
                listOf(5, 8, 9, 10, 11),
                listOf(1, 2, 45, 46, 47),
                listOf(3, 0, 36, 37, 38),
            ).flatten()

        private val TOP_LAYER = LAYOUT.toSet() + FaceletTables.center(Face.U)

        /**
         * Reads [state] with the [lastLayer] color on U. A state already holding that color on U
         * keeps its own front, so a `y` turn changes the view; any other state is re-framed to a
         * fixed front. Re-framing moves no pieces, since cubies are relative to the centers.
         */
        fun of(
            state: CubeState,
            lastLayer: Color,
        ): LastLayerReading {
            val canonical = canonicalFrame(lastLayer)
            val frame = if (state.orientation.up == canonical.up) state.orientation else canonical
            val colors = state.copy(orientation = frame).toFacelets().colors
            if (!isFirstTwoLayersSolved(colors)) return LastLayerReading.FirstTwoLayersUnsolved
            return LastLayerReading.View(LastLayerView(LAYOUT.map { colors[it] }, lastLayer, ringOf(frame)))
        }

        /**
         * A view of what was seen: [stickers] in view order with null for unseen, [lastLayer] on U
         * and the [front] center color on F. A wrong size or a [front] not adjacent to [lastLayer]
         * is a programming error.
         */
        fun partial(
            lastLayer: Color,
            front: Color,
            stickers: List<Color?>,
        ): LastLayerView {
            require(stickers.size == STICKERS) { "a view must have $STICKERS stickers, was ${stickers.size}" }
            return LastLayerView(stickers, lastLayer, ringOf(Orientation.of(homeFaceOf(lastLayer), homeFaceOf(front))))
        }

        /** The frame a cube is held in to read the [lastLayer] color: that color on U and a fixed front. */
        internal fun canonicalFrame(lastLayer: Color): Orientation {
            val up = homeFaceOf(lastLayer)
            return Orientation.of(up, fixedFront(up))
        }

        private fun ringOf(frame: Orientation): List<Color> = SIDES.map { frame.colorAt(it) }

        private fun homeFaceOf(color: Color): Face = Face.entries.first { Color.home(it) == color }

        private fun fixedFront(up: Face): Face = if (up == Face.F || up == Face.B) Face.U else Face.F

        private fun isFirstTwoLayersSolved(colors: List<Color>): Boolean =
            (0 until Facelets.SIZE).filter { it !in TOP_LAYER }.all { index ->
                colors[index] == colors[FaceletTables.center(Face.entries[index / PER_FACE])]
            }
    }
}

/** The outcome of [LastLayerView.of]. */
sealed interface LastLayerReading {
    /** The first two layers are solved; [view] is the LL. */
    data class View(
        val view: LastLayerView,
    ) : LastLayerReading

    /** The first two layers are not solved, so there is no LL to read. */
    data object FirstTwoLayersUnsolved : LastLayerReading
}
