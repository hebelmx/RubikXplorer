package dev.rubikxplorer.app.ui.freecube

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rubikxplorer.app.render.Cube3DRenderer
import dev.rubikxplorer.app.render.RenderedStickersKey
import dev.rubikxplorer.app.ui.theme.RubikXplorerTheme
import dev.rubikxplorer.domain.appearance.StickerAppearance
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Facelets
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.input.Vector3
import dev.rubikxplorer.domain.input.ViewOrbit
import dev.rubikxplorer.domain.input.ViewPose
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
    private val ticks = mutableListOf<HapticFeedbackType>()
    private val haptics =
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                ticks.add(hapticFeedbackType)
            }
        }

    private fun launch(showDebugToggles: Boolean = true) {
        viewModel = FreeCubeViewModel()
        rule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                RubikXplorerTheme(darkTheme = dark) {
                    FreeCubeScreen(viewModel = viewModel, showDebugToggles = showDebugToggles)
                }
            }
        }
    }

    private fun key(move: Move): SemanticsNodeInteraction = rule.onNodeWithTag("moveKey:${Notation.format(move)}").performScrollTo()

    private fun tap(move: Move) = key(move).performClick()

    private fun toolbar(name: String) = rule.onNodeWithTag("toolbar:$name")

    private fun openMore() = rule.onNodeWithTag("moveKey:more").performScrollTo().performClick()

    private fun drawnColors(): List<Color> =
        rule
            .onNodeWithTag("cube3d")
            .fetchSemanticsNode()
            .config[RenderedStickersKey]
            .map { Color(it.fill) }

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
            val draw = rule.onNodeWithTag("cube3d").fetchSemanticsNode().config[RenderedStickersKey][index]
            assertEquals(appearance.fill, draw.fill)
            if (on) {
                assertEquals(appearance.glyphInk, draw.glyphInk)
                assertEquals(appearance.glyph, draw.glyph)
            } else {
                assertNull(draw.glyphInk)
                assertNull(draw.glyph)
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

        // The first tap starts alone; later taps accumulate behind it.
        // Geometry readback changes at completion, rather than the net flip midpoint.
        fun fast(k: Int) = k > 1 && burst.size - k >= Cube3DRenderer.FAST_FORWARD_AFTER
        (1..burst.size).forEach { k ->
            val budget = (if (fast(k)) Cube3DRenderer.TURN_MILLIS / 4 else Cube3DRenderer.TURN_MILLIS) + 3 * FRAME_MS
            stepUntilRendered(targets[k], budget, "item $k", allowed = setOf(targets[k - 1], targets[k]))
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

    @Test
    fun `off cube and two finger drags orbit live with no commit or tick`() {
        launch()
        val history = viewModel.history
        val stage = rule.onNodeWithTag("cube3d")
        stage.performTouchInput {
            down(Offset(2f, 2f))
            moveTo(Offset(42f, 20f))
        }
        val side =
            stage
                .fetchSemanticsNode()
                .size
                .let { minOf(it.width, it.height) }
                .toDouble()
        assertEquals(ViewOrbit.DEFAULT.dragged(40.0, 18.0, side), viewModel.renderer.orbit)
        stage.performTouchInput { up() }
        rule.runOnIdle { viewModel.renderer.resetView() }
        stage.performTouchInput {
            down(0, center - Offset(40f, 0f))
            down(1, center + Offset(40f, 0f))
            moveTo(0, center - Offset(40f, 0f) + Offset(0f, 30f), delayMillis = 0)
            moveTo(1, center + Offset(40f, 0f) + Offset(0f, 30f))
        }
        // The camera follows the pair's centroid: both fingers moved down 30px.
        assertEquals(ViewOrbit.DEFAULT.dragged(0.0, 30.0, side), viewModel.renderer.orbit)
        stage.performTouchInput {
            up(0)
            up(1)
        }
        assertEquals(history, viewModel.history)
        assertTrue(ticks.isEmpty())
        assertRenderedMatchesState()
    }

    @Test
    fun `view menu reset button and double tap keep history unchanged`() {
        launch()
        rule.onNodeWithTag("stage:resetView").assertContentDescriptionEquals("Reset view").assertTouchWidthIsAtLeast(48.dp)
        rule.onNodeWithTag("stage:resetView").assertTouchHeightIsAtLeast(48.dp)
        rule
            .onNodeWithTag("stage:viewMenu")
            .assertContentDescriptionEquals("View options")
            .assertTouchWidthIsAtLeast(48.dp)
            .assertTouchHeightIsAtLeast(48.dp)
            .performClick()
        rule
            .onNodeWithTag("viewRotate:left")
            .assertTouchHeightIsAtLeast(48.dp)
            .assertTouchWidthIsAtLeast(48.dp)
            .performClick()
        assertEquals(ViewOrbit.DEFAULT.rotated(45.0, 0.0), viewModel.renderer.orbit)
        rule.waitForIdle()
        assertEquals(viewModel.renderer.orbit.eye(), viewModel.renderer.glEye)
        rule.onNodeWithTag("stage:resetView").performClick()
        assertEquals(ViewOrbit.DEFAULT, viewModel.renderer.orbit)
        rule.waitForIdle()
        assertEquals(ViewOrbit.DEFAULT.eye(), viewModel.renderer.glEye)
        listOf("right" to (-45.0 to 0.0), "up" to (0.0 to -45.0), "down" to (0.0 to 45.0)).forEach { (direction, angles) ->
            rule.onNodeWithTag("stage:viewMenu").performClick()
            rule.onNodeWithTag("viewRotate:$direction").performClick()
            assertEquals(ViewOrbit.DEFAULT.rotated(angles.first, angles.second), viewModel.renderer.orbit)
            rule.onNodeWithTag("stage:resetView").performClick()
        }
        rule.runOnIdle { viewModel.renderer.rotateView(45.0, 10.0) }
        rule.onNodeWithTag("cube3d").performTouchInput { doubleClick() }
        assertEquals(ViewOrbit.DEFAULT, viewModel.renderer.orbit)
        assertEquals(CubeState.SOLVED, viewModel.history.state)
        assertTrue(ticks.isEmpty())
    }

    @Test
    fun `rotated view keeps key labels and current pose layer turns`() {
        launch()
        val labels = MoveKeys.face.associateWith { key(it).fetchSemanticsNode().config[SemanticsProperties.ContentDescription] }
        rule.runOnIdle { viewModel.renderer.rotateView(-15.0, -10.0) }
        labels.forEach { (move, label) ->
            assertEquals(label, key(move).fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        }
        tap(move(Face.R))
        assertEquals(CubeState.SOLVED.apply(move(Face.R)), viewModel.history.state)
        toolbar("reset").performClick()
        drag()
        assertEquals(CubeState.SOLVED.apply(move(Face.U)), viewModel.history.state)
        rule.waitForIdle()
        assertRenderedMatchesState()
    }

    @Test
    fun `clockwise twist commits z restores camera ticks and announces front briefly`() {
        launch()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        val stage = rule.onNodeWithTag("cube3d")
        stage.performTouchInput {
            down(0, center - Offset(50f, 0f))
            down(1, center + Offset(50f, 0f))
            down(2, Offset(2f, 2f))
            moveTo(0, center - Offset(35f, 35f), delayMillis = 0)
            moveTo(1, center + Offset(35f, 35f))
        }
        assertEquals(ViewOrbit.DEFAULT, viewModel.renderer.orbit)
        stage.performTouchInput {
            up(0)
            up(1)
        }
        val z =
            dev.rubikxplorer.domain.cube
                .CubeRotation(dev.rubikxplorer.domain.cube.Axis.Z, Turn.CLOCKWISE)
        assertEquals(CubeState.SOLVED.apply(z), viewModel.history.state)
        assertEquals(listOf(HapticFeedbackType.KeyboardTap), ticks)
        stage.performTouchInput { up(2) }
        rule.mainClock.advanceTimeBy(250)
        assertRenderedMatchesState()
        val frame = rule.onNodeWithTag("stage:frame")
        frame.assertTextEquals("VIRTUAL · Green front")
        assertEquals(LiveRegionMode.Polite, frame.fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
        rule.mainClock.advanceTimeBy(1500)
        frame.assertTextEquals("VIRTUAL")
        assertEquals(null, frame.fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `rotation keys announce their new front and newer rotations restart the timer`() {
        launch()
        openMore()
        rule.mainClock.autoAdvance = false
        val x =
            dev.rubikxplorer.domain.cube
                .CubeRotation(dev.rubikxplorer.domain.cube.Axis.X, Turn.CLOCKWISE)
        tap(x)
        rule.mainClock.advanceTimeBy(250)
        assertEquals(CubeState.SOLVED.apply(x), viewModel.history.state)
        assertRenderedMatchesState()
        rule.onNodeWithTag("stage:frame").assertTextEquals("VIRTUAL · Yellow front")
        rule.mainClock.advanceTimeBy(700)
        tap(x)
        rule.mainClock.advanceTimeBy(700)
        rule.onNodeWithTag("stage:frame").assertTextEquals("VIRTUAL · Blue front")
        toolbar("undo").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("stage:frame").assertTextEquals("VIRTUAL")
        toolbar("reset").performClick()
        val y =
            dev.rubikxplorer.domain.cube
                .CubeRotation(dev.rubikxplorer.domain.cube.Axis.Y, Turn.CLOCKWISE)
        tap(y)
        rule.mainClock.advanceTimeBy(250)
        assertEquals(CubeState.SOLVED.apply(y), viewModel.history.state)
        rule.onNodeWithTag("stage:frame").assertTextEquals("VIRTUAL · Red front")
    }

    @Test
    fun `a locked twist that retreats below the threshold keeps the camera and commits nothing`() {
        launch()
        rule.waitForIdle()
        val stage = rule.onNodeWithTag("cube3d")

        fun pair(degrees: Double): Pair<Offset, Offset> {
            val r = Math.toRadians(degrees)
            val arm = Offset((50 * kotlin.math.cos(r)).toFloat(), (50 * kotlin.math.sin(r)).toFloat())
            return arm to -arm
        }
        stage.performTouchInput {
            down(0, center - Offset(50f, 0f))
            down(1, center + Offset(50f, 0f))
            val (a40, b40) = pair(40.0)
            moveTo(0, center - a40, delayMillis = 0)
            moveTo(1, center - b40)
            val (a20, b20) = pair(20.0)
            moveTo(0, center - a20 + Offset(0f, 40f), delayMillis = 0)
            moveTo(1, center - b20 + Offset(0f, 40f))
        }
        assertEquals(ViewOrbit.DEFAULT, viewModel.renderer.orbit)
        stage.performTouchInput {
            up(0)
            up(1)
        }
        rule.waitForIdle()
        assertEquals(ViewOrbit.DEFAULT, viewModel.renderer.orbit)
        assertEquals(CubeState.SOLVED, viewModel.history.state)
        assertTrue(ticks.isEmpty())
    }

    private fun gesturePoints(delta: Vector3 = Vector3(-1.0, 0.0, 0.0)): Pair<Offset, Offset> {
        val bounds = rule.onNodeWithTag("cube3d").fetchSemanticsNode().boundsInRoot
        val pose = ViewPose(bounds.width.toInt(), bounds.height.toInt(), viewModel.renderer.orbit.eye())
        val start = Vector3(0.0, 1.0, 1.5)

        fun offset(point: Vector3): Offset = pose.project(point).let { Offset(it.x.toFloat(), it.y.toFloat()) }
        return offset(start) to offset(start + delta)
    }

    private fun drag(delta: Vector3 = Vector3(-1.0, 0.0, 0.0)) {
        val (start, end) = gesturePoints(delta)
        rule.onNodeWithTag("cube3d").performTouchInput {
            down(start)
            moveTo(end)
            up()
        }
    }

    @Test
    fun `swipe commits and ticks only on lift and draws the same U as a key within 250 ms`() {
        launch()
        rule.waitForIdle()
        val (start, end) = gesturePoints()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("cube3d").performTouchInput {
            down(start)
            moveTo(end)
        }
        assertEquals(CubeState.SOLVED, viewModel.history.state)
        assertTrue(ticks.isEmpty())
        rule.onNodeWithTag("cube3d").performTouchInput { up() }
        val expected = CubeState.SOLVED.apply(move(Face.U))
        assertEquals(expected, viewModel.history.state)
        assertEquals(listOf(HapticFeedbackType.KeyboardTap), ticks)
        rule.mainClock.advanceTimeBy(250)
        assertRenderedMatchesState()
        rule.mainClock.autoAdvance = true
        toolbar("reset").performClick()
        ticks.clear()
        tap(move(Face.U))
        assertEquals(expected, viewModel.history.state)
        assertEquals(listOf(HapticFeedbackType.KeyboardTap), ticks)
        rule.waitForIdle()
        assertRenderedMatchesState()
    }

    @Test
    fun `short diagonal off cube and multi pointer gestures leave history haptics and drawing unchanged`() {
        launch()
        drag(Vector3(-0.2, 0.0, 0.0))
        drag(Vector3(-0.6, -0.5, 0.0))
        val (start, end) = gesturePoints()
        rule.onNodeWithTag("cube3d").performTouchInput {
            down(Offset(1f, 1f))
            moveTo(end)
            up()
        }
        rule.onNodeWithTag("cube3d").performTouchInput {
            down(0, start)
            down(1, start + Offset(5f, 5f))
            up(1)
            moveTo(0, end)
            up(0)
        }
        rule.waitForIdle()
        assertEquals(CubeState.SOLVED, viewModel.history.state)
        assertFalse(viewModel.history.canUndo)
        assertTrue(ticks.isEmpty())
        assertRenderedMatchesState()
    }

    @Test
    fun `haptics switch controls both gestures and keys without blocking commits`() {
        launch()
        val toggle = rule.onNodeWithTag("freeCube:haptics")
        toggle
            .performScrollTo()
            .assertIsOn()
            .performClick()
            .assertIsOff()
        drag()
        tap(move(Face.U))
        assertEquals(CubeState.SOLVED.apply(move(Face.U), move(Face.U)), viewModel.history.state)
        assertTrue(ticks.isEmpty())
        toggle.performScrollTo().performClick().assertIsOn()
        drag()
        tap(move(Face.U))
        assertEquals(CubeState.SOLVED, viewModel.history.state)
        assertEquals(listOf(HapticFeedbackType.KeyboardTap, HapticFeedbackType.KeyboardTap), ticks)
        rule.waitForIdle()
        assertRenderedMatchesState()
    }

    @Test
    fun `haptics switch is visible in release and turning it off silences gestures that still commit`() {
        launch(showDebugToggles = false)
        rule.onNodeWithTag("debug:glyphs").assertDoesNotExist()
        rule
            .onNodeWithTag("freeCube:haptics")
            .performScrollTo()
            .assertIsToggleable()
            .assertTextEquals("Haptics", includeEditableText = false)
            .assertIsOn()
            .performClick()
            .assertIsOff()
        assertFalse(viewModel.hapticsEnabled)
        drag()
        assertEquals(CubeState.SOLVED.apply(move(Face.U)), viewModel.history.state)
        assertTrue(ticks.isEmpty())
        rule.waitForIdle()
        assertRenderedMatchesState()
    }

    @Test
    fun `gesture during animation commits immediately`() {
        launch()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("moveKey:R").performClick()
        drag()
        assertEquals(CubeState.SOLVED.apply(move(Face.R), move(Face.U)), viewModel.history.state)
        assertEquals(2, ticks.size)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertRenderedMatchesState()
    }

    private companion object {
        const val FRAME_MS = 16L

        /** Longest a presentation item may take (spec: ≤ 250 ms; a quarter of that when fast-forwarded). */
        const val MAX_ITEM_MS = 250L
    }
}
