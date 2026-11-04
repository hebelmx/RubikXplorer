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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.rubikxplorer.app.R
import dev.rubikxplorer.app.session.allMoves
import dev.rubikxplorer.app.ui.editor.colorName
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Facelets
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

    private companion object {
        const val MAX_SPOKEN = 120
    }
}
