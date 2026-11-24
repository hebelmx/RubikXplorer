package dev.cubentia.app.ui.editor

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.cubentia.app.render.StickerColorKey
import dev.cubentia.domain.appearance.StickerAppearance
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

    /** [facelets] with each face in [turns] turned that many clockwise quarter turns (centers stay). */
    fun turned(
        facelets: Facelets,
        turns: Map<Int, Int>,
    ): Facelets =
        Facelets(
            (0 until 6).flatMap { face ->
                dev.cubentia.domain.recognition.ScanAssembler
                    .rotateClockwise(facelets.colors.subList(face * 9, face * 9 + 9), turns[face] ?: 0)
            },
        )

    /** The instruction line of a fresh editor before any face is entered. */
    const val SIX_FACES_LEFT = "6 faces left to enter: Up, Front, Right, Back, Left, Down."

    /** Asserts the open editor shows [instruction] as its instruction line and keeps Accept disabled (#253). */
    fun ComposeTestRule.assertEditorGated(instruction: String = SIX_FACES_LEFT) {
        onNodeWithTag("editor:instruction").performScrollTo().assertTextEquals(instruction)
        onNodeWithTag("editor:accept").performScrollTo().assertIsNotEnabled()
    }

    /**
     * Enters every face of an open editor (a fresh entry counts a face once all its stickers are set, GH #352) by
     * re-painting each non-center sticker with the color it shows; in one-face mode through its face tab.
     */
    fun ComposeTestRule.enterEveryFace() {
        repeat(6) { face ->
            if (onAllNodesWithTag("editor:face:$face").fetchSemanticsNodes().isNotEmpty()) {
                onNodeWithTag("editor:face:$face").performScrollTo().performClick()
                waitForIdle()
            }
            (face * 9 until face * 9 + 9).filter { it % 9 != 4 }.forEach { index ->
                val sticker = onNodeWithTag("sticker:$index").performScrollTo()
                sticker.performClick()
                val shown = sticker.fetchSemanticsNode().config[StickerColorKey]
                val color =
                    Color.entries.first {
                        androidx.compose.ui.graphics
                            .Color(StickerAppearance.of(it).fill) == shown
                    }
                onNodeWithTag("swatch:${color.name}").performScrollTo().performClick()
                waitForIdle()
            }
        }
    }
}
