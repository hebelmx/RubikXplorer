package dev.rubikxplorer.app.ui.freecube

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.rubikxplorer.app.render.Net2DRenderer
import dev.rubikxplorer.app.render.StickerColorKey
import dev.rubikxplorer.app.render.StickerGlyphInkKey
import dev.rubikxplorer.app.ui.theme.RubikXplorerTheme
import dev.rubikxplorer.domain.appearance.StickerAppearance
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Facelets
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AC 1–5 at the screen surface, on the JVM through Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class FreeCubeScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var viewModel: FreeCubeViewModel
    private var dark by mutableStateOf(false)

    private fun launch(showDebugToggles: Boolean = true) {
        viewModel = FreeCubeViewModel()
        rule.setContent {
            RubikXplorerTheme(darkTheme = dark) {
                FreeCubeScreen(viewModel = viewModel, showDebugToggles = showDebugToggles)
            }
        }
    }

    private fun key(move: Move): SemanticsNodeInteraction = rule.onNodeWithTag("moveKey:${Notation.format(move)}").performScrollTo()

    private fun tap(move: Move) = key(move).performClick()

    private fun toolbar(name: String) = rule.onNodeWithTag("toolbar:$name")

    private fun openMore() = rule.onNodeWithTag("moveKey:more").performScrollTo().performClick()

    private fun drawnColors(): List<Color> =
        (0 until Facelets.SIZE).map { rule.onNodeWithTag("sticker:$it").fetchSemanticsNode().config[StickerColorKey] }

    private fun expectedColors(facelets: Facelets) = facelets.colors.map { Color(StickerAppearance.of(it).fill) }

    private fun assertRenderedMatchesState() {
        val committed = viewModel.history.state.toFacelets()
        assertEquals(committed, viewModel.renderer.renderedFacelets())
        assertEquals(expectedColors(committed), drawnColors())
    }

    // Compose 1.12 has only `assertTouchWidthIsEqualTo`; these check the target's laid-out size is at least [min].
    private fun SemanticsNodeInteraction.assertTouchWidthIsAtLeast(min: Dp): SemanticsNodeInteraction {
        val width = getUnclippedBoundsInRoot().let { it.right - it.left }
        assertTrue("touch width $width < $min", width >= min)
        return this
    }

    private fun SemanticsNodeInteraction.assertTouchHeightIsAtLeast(min: Dp): SemanticsNodeInteraction {
        val height = getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue("touch height $height < $min", height >= min)
        return this
    }

    private fun move(
        face: Face,
        turn: Turn = Turn.CLOCKWISE,
    ) = FaceTurn(face, turn)

    @Test
    fun `sticker colors are identical in light and dark themes`() {
        launch()
        tap(move(Face.R))
        rule.waitForIdle()
        val light = drawnColors()
        dark = true
        rule.waitForIdle()
        assertEquals(light, drawnColors())
        assertEquals(expectedColors(CubeState.SOLVED.apply(move(Face.R)).toFacelets()), light)
    }

    @Test
    fun `every key commits its Move and the net matches the committed state after each one`() {
        launch()
        openMore()
        var expected = CubeState.SOLVED
        MoveKeys.all.forEach { move ->
            tap(move)
            rule.waitForIdle()
            expected = expected.apply(move)
            assertEquals(Notation.format(move), expected, viewModel.history.state)
            assertRenderedMatchesState()
        }
        assertEquals(54, MoveKeys.all.size)
        assertEquals(54, MoveKeys.all.toSet().size)
    }

    @Test(timeout = 120_000)
    fun `a burst with no idle in between drops no Move`() {
        launch()
        // Keys visible without scrolling (scrolling needs frames, which the paused clock withholds).
        val burst = MoveKeys.face.take(6) + MoveKeys.rotation.take(4)
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        burst.forEach { rule.onNodeWithTag("moveKey:${Notation.format(it)}").performClick() }
        assertEquals(CubeState.SOLVED.apply(burst), viewModel.history.state)
        assertNotEquals(viewModel.history.state.toFacelets(), viewModel.renderer.renderedFacelets())
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertRenderedMatchesState()
    }

    @Test
    fun `a fresh screen has nothing to undo or redo`() {
        launch()
        toolbar("undo").assertIsNotEnabled()
        toolbar("redo").assertIsNotEnabled()
        assertEquals(CubeState.SOLVED, viewModel.history.state)
    }

    @Test
    fun `commit enables undo only, and undo and redo walk the exact states`() {
        launch()
        tap(move(Face.R))
        assertEquals(CubeState.SOLVED.apply(move(Face.R)), viewModel.history.state)
        toolbar("undo").assertIsEnabled()
        toolbar("redo").assertIsNotEnabled()

        tap(move(Face.U))
        toolbar("undo").performClick()
        rule.waitForIdle()
        assertEquals(CubeState.SOLVED.apply(move(Face.R)), viewModel.history.state)
        assertRenderedMatchesState()
        toolbar("redo").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(CubeState.SOLVED.apply(move(Face.R), move(Face.U)), viewModel.history.state)
        assertRenderedMatchesState()
        toolbar("redo").assertIsNotEnabled()
    }

    @Test
    fun `a new Move clears redo`() {
        launch()
        tap(move(Face.R))
        toolbar("undo").performClick()
        tap(move(Face.F))
        rule.waitForIdle()
        assertEquals(CubeState.SOLVED.apply(move(Face.F)), viewModel.history.state)
        toolbar("redo").assertIsNotEnabled()
        assertRenderedMatchesState()
    }

    @Test
    fun `reset returns to solved with no dialog`() {
        launch()
        tap(move(Face.R))
        tap(move(Face.U, Turn.HALF))
        toolbar("undo").performClick()
        toolbar("reset").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(CubeState.SOLVED, viewModel.history.state)
        toolbar("undo").assertIsNotEnabled()
        toolbar("redo").assertIsNotEnabled()
        assertRenderedMatchesState()
    }

    @Test
    fun `color-blind glyphs switch on and off`() {
        launch()
        tap(move(Face.F))
        rule.waitForIdle()
        val glyphs = rule.onNodeWithTag("debug:glyphs")
        glyphs.assertIsOff()
        assertStickerGlyphs(on = false)
        glyphs.performClick().assertIsOn()
        rule.waitForIdle()
        assertStickerGlyphs(on = true)
        glyphs.performClick().assertIsOff()
        rule.waitForIdle()
        assertStickerGlyphs(on = false)
    }

    private fun assertStickerGlyphs(on: Boolean) {
        val colors = viewModel.renderer.renderedFacelets().colors
        colors.forEachIndexed { index, color ->
            val appearance = StickerAppearance.of(color)
            val node = rule.onNodeWithTag("sticker:$index", useUnmergedTree = true)
            val ink = node.fetchSemanticsNode().config.getOrNull(StickerGlyphInkKey)
            val children = node.onChildren().fetchSemanticsNodes()
            if (on) {
                assertEquals(Color(appearance.glyphInk), ink)
                node.onChildren()[0].assertTextEquals(appearance.glyph.toString())
            } else {
                assertNull(ink)
                assertTrue(children.isEmpty())
            }
        }
    }

    @Test
    fun `the debug toggle is hidden unless requested`() {
        launch(showDebugToggles = false)
        rule.onNodeWithTag("debug:glyphs").assertDoesNotExist()
    }

    @Test
    fun `keys announce their plain names`() {
        launch()
        openMore()
        val literal =
            mapOf(
                "R'" to "R prime: right face counter-clockwise",
                "r2" to "wide r two: right two layers half turn",
                "M" to "M: middle slice clockwise, like L",
                "x" to "x: whole cube clockwise, like R",
                "U2" to "U two: up face half turn",
            )
        literal.forEach { (token, name) -> rule.onNodeWithTag("moveKey:$token").performScrollTo().assertContentDescriptionEquals(name) }
        val names =
            MoveKeys.all.map { move ->
                key(move)
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(SemanticsProperties.ContentDescription)
                    .orEmpty()
                    .joinToString()
            }
        assertTrue(names.none { it.isBlank() })
        assertEquals(54, names.toSet().size)
    }

    @Test
    fun `long press shows the plain name and commits nothing`() {
        launch()
        val rPrime = move(Face.R, Turn.COUNTERCLOCKWISE)
        key(rPrime).performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithTag("stage:caption").assertTextEquals("R prime: right face counter-clockwise")
        assertEquals(CubeState.SOLVED, viewModel.history.state)
        assertFalse(viewModel.history.canUndo)
    }

    @Test
    fun `every key and toolbar button is at least 48dp`() {
        launch()
        openMore()
        MoveKeys.all.forEach { key(it).assertTouchWidthIsAtLeast(48.dp).assertTouchHeightIsAtLeast(48.dp) }
        rule.onNodeWithTag("moveKey:more").assertTouchWidthIsAtLeast(48.dp).assertTouchHeightIsAtLeast(48.dp)
        listOf("undo", "redo", "reset").forEach { toolbar(it).assertTouchWidthIsAtLeast(48.dp).assertTouchHeightIsAtLeast(48.dp) }
        listOf("undo" to "Undo", "redo" to "Redo", "reset" to "Reset").forEach { (tag, label) ->
            toolbar(tag).assertContentDescriptionEquals(label)
        }
    }

    private fun DpRect.centerX(): Float = (left.value + right.value) / 2

    private fun DpRect.centerY(): Float = (top.value + bottom.value) / 2

    @Test
    fun `every sticker sits in its Kociemba cross cell`() {
        launch()
        val net = rule.onNodeWithTag("net").getUnclippedBoundsInRoot()
        val cell = (net.right.value - net.left.value) / 12
        assertEquals(cell * 9, net.bottom.value - net.top.value, 0.5f)
        // Top-left (column, row) of each URFDLB face: U 3/0, R 6/3, F 3/3, D 3/6, L 0/3, B 9/3.
        val origin = listOf(3 to 0, 6 to 3, 3 to 3, 3 to 6, 0 to 3, 9 to 3)
        (0 until Facelets.SIZE).forEach { index ->
            val (faceColumn, faceRow) = origin[index / 9]
            val column = faceColumn + index % 9 % 3
            val row = faceRow + index % 9 / 3
            val bounds = rule.onNodeWithTag("sticker:$index").getUnclippedBoundsInRoot()
            assertEquals("sticker $index column", column, ((bounds.centerX() - net.left.value) / cell).toInt())
            assertEquals("sticker $index row", row, ((bounds.centerY() - net.top.value) / cell).toInt())
        }
    }

    /** Steps the paused clock a frame at a time until [target] is drawn; fails past [budgetMs]. */
    private fun stepUntilRendered(
        target: Facelets,
        budgetMs: Long,
        what: String,
        allowed: Set<Facelets> = setOf(target),
    ) {
        var elapsed = 0L
        while (viewModel.renderer.renderedFacelets() != target) {
            assertTrue("$what not settled within $budgetMs ms", elapsed <= budgetMs)
            assertTrue("$what: drew a state out of order", viewModel.renderer.renderedFacelets() in allowed)
            rule.mainClock.advanceTimeByFrame()
            elapsed += FRAME_MS
        }
    }

    @Test(timeout = 120_000)
    fun `one Move settles within 250 ms on the frame clock`() {
        launch()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        tap(move(Face.R))
        val committed = viewModel.history.state.toFacelets()
        rule.mainClock.advanceTimeBy(50)
        assertNotEquals("a Move animates rather than jumping", committed, viewModel.renderer.renderedFacelets())
        rule.mainClock.advanceTimeBy(200)
        assertEquals(committed, viewModel.renderer.renderedFacelets())
    }

    @Test(timeout = 120_000)
    fun `a burst passes through every intermediate state in order, fast-forwarded`() {
        launch()
        rule.waitForIdle()
        val f = MoveKeys.face
        // Visible without scrolling: U U' U2 R R' R2, in an order whose prefixes are all distinct.
        val burst = listOf(f[0], f[3], f[2], f[4], f[1], f[5])
        val targets = (0..burst.size).map { CubeState.SOLVED.apply(burst.take(it)).toFacelets() }
        assertEquals(targets.size, targets.toSet().size)
        rule.mainClock.autoAdvance = false
        burst.forEach { rule.onNodeWithTag("moveKey:${Notation.format(it)}").performClick() }

        // Item 1 starts on the first tap, alone in the queue; later ones fast-forward while more than
        // FAST_FORWARD_AFTER items are queued (counting themselves). An item is drawn at its midpoint,
        // so the gap between two settles is the second half of one item plus the first half of the next.
        fun fast(k: Int) = k > 1 && burst.size - k >= Net2DRenderer.FAST_FORWARD_AFTER

        fun half(k: Int) =
            if (k == 0) {
                0L
            } else if (fast(k)) {
                MAX_ITEM_MS / 4 / 2
            } else {
                MAX_ITEM_MS / 2
            }
        (1..burst.size).forEach { k ->
            // Each half starts on the next frame, so allow one frame per half plus one of slack.
            val budget = half(k - 1) + half(k) + 3 * FRAME_MS
            stepUntilRendered(targets[k], budget, "item $k (fast=${fast(k)})", allowed = setOf(targets[k - 1], targets[k]))
        }
        assertEquals(viewModel.history.state.toFacelets(), viewModel.renderer.renderedFacelets())
    }

    @Test(timeout = 120_000)
    fun `reset snaps to solved within one frame`() {
        launch()
        tap(move(Face.R))
        tap(move(Face.U))
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        toolbar("reset").performClick()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(CubeState.SOLVED.toFacelets(), viewModel.renderer.renderedFacelets())
    }

    private companion object {
        const val FRAME_MS = 16L

        /** Longest a presentation item may take (spec: ≤ 250 ms; a quarter of that when fast-forwarded). */
        const val MAX_ITEM_MS = 250L
    }
}
