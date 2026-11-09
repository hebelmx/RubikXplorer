package dev.cubentia.app.ui.editor

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult

/** Sticker fixtures for the editor I/O matrix. */
internal object EditorFixtures {
    val solved: List<Color> = CubeState.SOLVED.toFacelets().colors

    val centers = listOf(4, 13, 22, 31, 40, 49)

    /** A legal scramble by face turns only, so centers stay home. */
    val scrambled: Facelets =
        CubeState.SOLVED
            .apply((Notation.parse("R U F' D2 L B' R2 U'") as NotationResult.Parsed).moves)
            .toFacelets()

    fun swap(
        colors: List<Color>,
        a: Int,
        b: Int,
    ): List<Color> =
        colors.toMutableList().also {
            it[a] = colors[b]
            it[b] = colors[a]
        }

    /** Sticker at `from[k]` moves to `from[k + 1]`, the last wrapping to the first. */
    fun cycle(
        colors: List<Color>,
        vararg from: Int,
    ): List<Color> =
        colors.toMutableList().also { out ->
            from.indices.forEach { k -> out[from[(k + 1) % from.size]] = colors[from[k]] }
        }

    /** URF corner twisted and UR edge flipped: legal pieces, two non-local failures. */
    val twistedAndFlipped: Facelets = Facelets(swap(cycle(solved, 8, 9, 20), 5, 10))

    /** UR and UF edges swapped: a parity failure. */
    val swappedEdges: Facelets = Facelets(swap(swap(solved, 5, 7), 10, 19))
}
