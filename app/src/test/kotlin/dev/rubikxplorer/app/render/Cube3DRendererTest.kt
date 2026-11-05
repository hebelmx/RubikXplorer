package dev.rubikxplorer.app.render

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.session.allMoves
import dev.rubikxplorer.app.ui.editor.colorName
import dev.rubikxplorer.app.ui.theme.RubikTheme
import dev.rubikxplorer.app.ui.theme.RubikXplorerTheme
import dev.rubikxplorer.domain.cube.Axis
import dev.rubikxplorer.domain.cube.CubeRotation
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Facelets
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult
import dev.rubikxplorer.domain.cube.Slice
import dev.rubikxplorer.domain.cube.SliceTurn
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.input.MoveArrow
import dev.rubikxplorer.domain.input.ScreenPoint
import dev.rubikxplorer.domain.input.Vector3
import dev.rubikxplorer.domain.input.ViewOrbit
import dev.rubikxplorer.domain.input.ViewPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class Cube3DRendererTest {
    @get:Rule val rule = createComposeRule()

    @Test fun netAndMeshHaveIdenticalAppearanceWithGlyphsOnAndOff() {
        val state = CubeState.SOLVED.apply(allMoves.take(19)).toFacelets()
        val net = Net2DRenderer(state)
        val cube = Cube3DRenderer(state)
        var glyphs by mutableStateOf(false)
        rule.setContent {
            Column {
                net.Content(RenderStyle(glyphs), Modifier.size(300.dp, 225.dp))
                cube.Content(RenderStyle(glyphs), Modifier.size(300.dp))
            }
        }
        listOf(false, true, false).forEach { enabled ->
            rule.runOnIdle { glyphs = enabled }
            rule.waitForIdle()
            val draws = rule.onNodeWithTag("cube3d").fetchSemanticsNode().config[RenderedStickersKey]
            draws.forEachIndexed { i, draw ->
                val cell = rule.onNodeWithTag("sticker:$i").fetchSemanticsNode().config
                assertEquals(cell[StickerColorKey], Color(draw.fill))
                assertEquals(cell.getOrNull(StickerGlyphInkKey), draw.glyphInk?.let { Color(it) })
                assertEquals(enabled, draw.glyph != null)
            }
        }
    }

    @Test
    fun glyphToggleDoesNotCompleteAnActiveTurnEarly() {
        val cube = Cube3DRenderer()
        var glyphs by mutableStateOf(false)
        rule.setContent { cube.Content(RenderStyle(glyphs), Modifier.size(300.dp)) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        val move = allMoves.first()
        val target = CubeState.SOLVED.apply(move).toFacelets()
        rule.runOnIdle { cube.submit(listOf(PresentationItem.Animate(move, 1, target))) }
        rule.mainClock.advanceTimeBy(50)
        rule.runOnIdle { glyphs = true }
        rule.mainClock.advanceTimeBy(50)
        assertEquals(CubeState.SOLVED.toFacelets(), cube.renderedFacelets())
        rule.mainClock.advanceTimeBy(150)
        assertEquals(target, cube.renderedFacelets())
        val draws = rule.onNodeWithTag("cube3d").fetchSemanticsNode().config[RenderedStickersKey]
        assertTrue(draws.all { it.glyph != null && it.glyphInk != null })
    }

    @Test fun exposesLocalizedStateDerivedDescription() {
        val cube = Cube3DRenderer()
        rule.setContent { cube.Content(RenderStyle(false), Modifier.size(300.dp)) }
        rule.waitForIdle()

        fun config() = rule.onNodeWithTag("cube3d").fetchSemanticsNode().config
        assertEquals(listOf("Cube, White on top, Green in front"), config()[SemanticsProperties.ContentDescription])
        assertEquals("Solved", config()[SemanticsProperties.StateDescription])
        val move = allMoves.first()
        rule.runOnIdle { cube.submit(listOf(PresentationItem.Snap(1, CubeState.SOLVED.apply(move).toFacelets()))) }
        rule.waitForIdle()
        assertEquals("Not solved", config()[SemanticsProperties.StateDescription])
    }

    @Test fun descriptionReachesTheAccessibilityTreeAsOneFocusableNodeSolvedAndScrambled() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.getSystemService(AccessibilityManager::class.java)).apply {
            setEnabled(true)
            setTouchExplorationEnabled(true)
            setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
        }
        val cube = Cube3DRenderer()
        lateinit var host: View
        rule.setContent {
            host = LocalView.current
            cube.Content(RenderStyle(false), Modifier.size(300.dp))
        }
        rule.waitForIdle()

        // Expected text comes from resources and the drawn facelets (U center 4, F center 22).
        fun describe(facelets: Facelets) =
            app.getString(
                R.string.cube_description,
                app.getString(colorName(facelets.colors[4])),
                app.getString(colorName(facelets.colors[22])),
            )
        assertSpoken(host, describe(cube.renderedFacelets()), app.getString(R.string.cube_state_solved))
        val scrambled = CubeState.SOLVED.apply(allMoves.take(19)).toFacelets()
        rule.runOnIdle { cube.submit(listOf(PresentationItem.Snap(19, scrambled))) }
        rule.waitForIdle()
        assertEquals(scrambled, cube.renderedFacelets())
        assertSpoken(host, describe(scrambled), app.getString(R.string.cube_state_unsolved))
    }

    /** Asserts what TalkBack actually gets: the platform node tree and touch-explore hit testing. */
    private fun assertSpoken(
        host: View,
        description: String,
        state: String,
    ) {
        // Walk the platform tree from the host the way TalkBack does; semantics on an interop node are
        // replaced by its holder View and never show up here.
        val reachable =
            rule.runOnIdle {
                val provider = host.accessibilityNodeProvider!!
                // getChildId is @hide; it packs (virtualId shl 32) | hostViewId.
                val childId = AccessibilityNodeInfo::class.java.getMethod("getChildId", Int::class.javaPrimitiveType)
                val found = mutableListOf<AccessibilityNodeInfo>()
                val pending = ArrayDeque(listOf(AccessibilityNodeProvider.HOST_VIEW_ID))
                while (pending.isNotEmpty()) {
                    val info = provider.createAccessibilityNodeInfo(pending.removeFirst()) ?: continue
                    found += info
                    repeat(info.childCount) { i ->
                        val virtualId = ((childId.invoke(info, i) as Long) shr 32).toInt()
                        if (virtualId != AccessibilityNodeProvider.HOST_VIEW_ID) pending += virtualId
                    }
                }
                found
            }
        val cubeNodes = reachable.filter { it.contentDescription?.toString() == description }
        assertEquals(1, cubeNodes.size)
        val info = cubeNodes.single()
        assertTrue(description.isNotBlank() && description.length <= MAX_SPOKEN)
        assertTrue(info.isScreenReaderFocusable)
        assertTrue(info.isVisibleToUser)
        assertEquals(state, info.stateDescription?.toString())
        // Touch exploration: a hover over the cube must land on that Compose node. If it hits the
        // embedded GL view instead, Compose hands it to the View system and TalkBack stays silent.
        val center = Rect().also { info.getBoundsInScreen(it) }
        val hover =
            MotionEvent.obtain(0, 0, MotionEvent.ACTION_HOVER_ENTER, center.exactCenterX(), center.exactCenterY(), 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
        assertTrue(rule.runOnIdle { host.dispatchGenericMotionEvent(hover) })
        hover.recycle()
    }

    @Test fun arrowLabelStaysInsideTheStageAtALargeFontScaleNearTheEdge() {
        val move = (Notation.parse("B'") as NotationResult.Parsed).moves.single()
        var label = IntSize.Zero
        var sidePx = 0f
        var gap = 0f
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                RubikXplorerTheme {
                    label =
                        rememberTextMeasurer().measure(Notation.format(move), RubikTheme.textStyles.notation).size
                    with(LocalDensity.current) {
                        sidePx = 300.dp.toPx()
                        gap = 8.dp.toPx()
                    }
                }
            }
        }
        rule.waitForIdle()
        // Robolectric measures the scaled line height but no glyph advances, so give each glyph a
        // line-height width, as wide as enlarged notation renders on a device.
        label = IntSize(label.height * Notation.format(move).length, label.height)
        val stage = Size(sidePx, sidePx)
        val pose = ViewPose(sidePx.toInt(), sidePx.toInt(), ViewOrbit.DEFAULT.eye())
        val tip = pose.project(MoveArrow.arc(move, ViewOrbit.DEFAULT.eye()).last()).let { Offset(it.x.toFloat(), it.y.toFloat()) }
        // The tip sits near the right edge: a label placed past it would leave the stage.
        assertTrue(tip.x + gap + label.width > stage.width)
        val topLeft = Cube3DRenderer.arrowLabelTopLeft(tip, label, stage, gap)
        assertTrue(topLeft.x >= 0f && topLeft.y >= 0f)
        assertTrue(topLeft.x + label.width <= stage.width && topLeft.y + label.height <= stage.height)
        // Inward beside the tip: left of it, not over it.
        assertTrue(topLeft.x + label.width <= tip.x)
        // Every edge clamps, and a label larger than the stage pins to its top-left.
        assertEquals(Offset(10f, 0f), Cube3DRenderer.arrowLabelTopLeft(Offset(2f, -40f), IntSize(30, 20), Size(100f, 100f), 8f))
        assertEquals(Offset(0f, 80f), Cube3DRenderer.arrowLabelTopLeft(Offset(60f, 140f), IntSize(80, 20), Size(100f, 100f), 8f))
        assertEquals(Offset(0f, 0f), Cube3DRenderer.arrowLabelTopLeft(Offset(60f, 50f), IntSize(300, 200), Size(100f, 100f), 8f))
    }

    @Test fun `arrow stays a curve with its direction of travel when the camera lies in the turn plane`() {
        // E from a level camera, U from eye height 1 (the U layer's plane) and x from a camera in x = 0.
        val cases =
            listOf(
                SliceTurn(Slice.E, Turn.CLOCKWISE) to ViewOrbit(0.0, 0.0).eye(),
                FaceTurn(Face.U, Turn.COUNTERCLOCKWISE) to Vector3(3.0, 1.0, 8.0),
                CubeRotation(Axis.X, Turn.CLOCKWISE) to Vector3(0.0, 4.0, 8.0),
            )
        cases.forEach { (move, eye) ->
            val pose = ViewPose(300, 300, eye)
            val plain = MoveArrow.arc(move, eye).map { pose.project(it) }
            // Reproduces the defect: the plain projection is a straight line.
            assertTrue("$move plain", Cube3DRenderer.curvature(plain) < 1e-6)
            val drawn = Cube3DRenderer.arrowPoints(move, pose)
            val screen = drawn.map { ScreenPoint(it.x.toDouble(), it.y.toDouble()) }
            assertTrue("$move curved", Cube3DRenderer.curvature(screen) >= Cube3DRenderer.MIN_ARC_CURVATURE)
            // Same start and tip, and every step still advances from start toward the tip.
            assertEquals(plain.first().x, screen.first().x, 1e-3)
            assertEquals(plain.first().y, screen.first().y, 1e-3)
            assertEquals(plain.last().x, screen.last().x, 1e-3)
            assertEquals(plain.last().y, screen.last().y, 1e-3)
            val cx = plain.last().x - plain.first().x
            val cy = plain.last().y - plain.first().y
            screen.zipWithNext().forEach { (a, b) -> assertTrue("$move forward", (b.x - a.x) * cx + (b.y - a.y) * cy > 0) }
        }
        // Off the plane the arc is drawn exactly as projected.
        listOf(FaceTurn(Face.R, Turn.CLOCKWISE), FaceTurn(Face.U, Turn.CLOCKWISE), SliceTurn(Slice.E, Turn.CLOCKWISE)).forEach { move ->
            val eye = ViewOrbit.DEFAULT.eye()
            val pose = ViewPose(300, 300, eye)
            val plain = MoveArrow.arc(move, eye).map { pose.project(it).let { p -> Offset(p.x.toFloat(), p.y.toFloat()) } }
            assertEquals(plain, Cube3DRenderer.arrowPoints(move, pose))
        }
    }

    private companion object {
        const val MAX_SPOKEN = 120
    }
}
