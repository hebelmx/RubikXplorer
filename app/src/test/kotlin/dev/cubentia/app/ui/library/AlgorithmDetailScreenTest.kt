package dev.cubentia.app.ui.library

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.render.Cube3DRenderer
import dev.cubentia.app.render.RenderStyle
import dev.cubentia.app.render.RenderedOrbitKey
import dev.cubentia.app.render.RenderedStickersKey
import dev.cubentia.app.ui.lesson.DemonstrationPlayerState
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.app.ui.theme.Spacing
import dev.cubentia.domain.appearance.StickerAppearance
import dev.cubentia.domain.content.Algorithm
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.input.ViewOrbit
import dev.cubentia.domain.playback.Demonstration
import dev.cubentia.domain.playback.DemonstrationResult
import dev.cubentia.domain.playback.MoveNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class AlgorithmDetailScreenTest {
    @get:Rule val rule = createComposeRule()
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val algorithm = bundle.algorithms.first { it.id.value == "alg-pll-t-a" }
    private val case = bundle.cases.first { it.id == algorithm.caseId }
    private val strings = ContentStrings(bundle.strings)
    private val demo = (Demonstration.ofCase(algorithm) as DemonstrationResult.Ready).demonstration

    private fun show(
        algorithm: Algorithm = this.algorithm,
        diagram: Boolean = true,
    ) {
        rule.setContent { CubentiaTheme { AlgorithmDetailScreen(case, algorithm, strings, {}, Modifier.fillMaxSize(), diagram) } }
    }

    private fun assertBoundary(boundary: Int) {
        rule.waitForIdle()
        val draws = rule.onNodeWithTag("cube3d").fetchSemanticsNode().config[RenderedStickersKey]
        assertEquals(
            demo.start
                .apply(demo.moves.take(boundary))
                .toFacelets()
                .colors
                .map { StickerAppearance.of(it).fill },
            draws.map { it.fill },
        )
        rule.onNodeWithTag("playback:counter").assertTextEquals("$boundary / ${demo.moves.size}")
    }

    private fun orbit() = rule.onNodeWithTag("cube3d").fetchSemanticsNode().config[RenderedOrbitKey]

    private fun assertCurrent(
        index: Int,
        current: Boolean,
    ) = rule
        .onNodeWithTag("algorithmDetail:move:$index", useUnmergedTree = true)
        .assert(SemanticsMatcher.expectValue(CurrentMoveKey, current))

    private fun scrub(boundary: Int) {
        rule.onNodeWithTag("playback:scrub").performSemanticsAction(SemanticsActions.SetProgress) { it(boundary.toFloat()) }
    }

    @Test fun `starts at Case with VIRTUAL label diagram notation and no status controls`() {
        show()
        assertBoundary(0)
        rule.onNodeWithTag(DIAGRAM_TAG).assertExists()
        rule.onNodeWithTag("stage:frame").assertExists()
        rule.onNodeWithTag("algorithmDetail:title").assertTextEquals(strings.resolve(case.name))
        rule.onNodeWithText("Not in your set yet.").assertDoesNotExist()
        rule.onNodeWithText("Not started").assertDoesNotExist()
    }

    @Test fun `scrubbing both directions settles exactly and selects only indexed repeated Move`() {
        show()
        for (boundary in listOf(3, demo.moves.size, 1, 0, 3)) {
            scrub(boundary)
            assertBoundary(boundary)
        }
        assertCurrent(2, true)
        assertCurrent(0, false)
        // T perm repeats R-prime at indices 2 and 4; selection must follow the index.
        assertEquals(demo.moves[2], demo.moves[4])
        assertCurrent(4, false)
        rule.onNodeWithTag("playback:note").assertTextEquals(strings.resolve(MoveNote.of(demo.moves[2])))
    }

    @Test fun `scrub cancels outstanding turns and agrees with its boundary before the clock advances`() {
        val state = DemonstrationPlayerState(demo, reduceMotion = false)
        val boundary = demo.start.apply(demo.moves.take(2)).toFacelets()
        rule.setContent { CubentiaTheme { state.renderer.Content(RenderStyle(false), Modifier.size(300.dp)) } }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { repeat(4) { state.stepForward() } }
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        rule.runOnIdle {
            assertNotNull(state.renderer.presentedMove)
            state.scrubTo(2.0 / demo.moves.size)
            assertEquals(2, state.position)
            assertEquals(boundary, state.renderer.renderedFacelets())
            assertNull(state.renderer.presentedMove)
        }
        rule.mainClock.advanceTimeBy(1000)
        rule.runOnIdle { assertEquals(boundary, state.renderer.renderedFacelets()) }
    }

    @Test fun `scrub during turn shows its boundary on the next frame and stays paused`() {
        show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("playback:playPause").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        scrub(3)
        rule.mainClock.advanceTimeByFrame()
        assertBoundary(3)
        rule.mainClock.advanceTimeBy(1000)
        assertBoundary(3)
        rule.onNodeWithTag("playback:playPause").assertContentDescriptionEquals("Play")
        rule.mainClock.advanceTimeBy(3000)
        assertBoundary(3)
    }

    @Test fun `rapid scrubs and a touch drag settle on the last boundary`() {
        show()
        for (boundary in listOf(2, 9, 1, 6)) scrub(boundary)
        rule.mainClock.advanceTimeBy(1000)
        assertBoundary(6)
        rule.onNodeWithTag("playback:scrub").performTouchInput { swipe(centerLeft, centerRight + Offset(200f, 0f)) }
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithTag("playback:counter").assertTextEquals("${demo.moves.size} / ${demo.moves.size}")
        assertBoundary(demo.moves.size)
    }

    @Test fun `pause holds the boundary`() {
        show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("playback:playPause").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("playback:playPause").assertContentDescriptionEquals("Pause").performClick()
        rule.mainClock.advanceTimeBy(1000)
        val held =
            rule
                .onNodeWithTag("playback:counter")
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .first()
                .text
                .substringBefore(" /")
                .toInt()
        rule.mainClock.advanceTimeBy(2000)
        assertBoundary(held)
    }

    @Test fun `strip stays left to right under an RTL locale`() {
        rule.setContent {
            CubentiaTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AlgorithmDetailScreen(case, algorithm, strings, {}, Modifier.fillMaxSize())
                }
            }
        }
        val first = rule.onNodeWithTag("algorithmDetail:move:0", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val second = rule.onNodeWithTag("algorithmDetail:move:1", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("$first before $second", first.left < second.left)
    }

    @Test fun `dragging the detail cube orbits and double tap resets it`() {
        show()
        assertEquals(ViewOrbit.DEFAULT, orbit())
        rule.onNodeWithTag("cube3d").performTouchInput { swipe(center, center + Offset(60f, 30f)) }
        rule.waitForIdle()
        assertNotEquals(ViewOrbit.DEFAULT, orbit())
        rule.onNodeWithTag("cube3d").performTouchInput { doubleClick() }
        rule.waitForIdle()
        assertEquals(ViewOrbit.DEFAULT, orbit())
        assertBoundary(0)
    }

    @Test fun `two finger twist in free view orbits and commits nothing`() {
        val cube = Cube3DRenderer(demo.start.toFacelets())
        var commits = 0
        rule.setContent {
            CubentiaTheme {
                cube.Content(RenderStyle(false), Modifier.size(300.dp), onMoveIntent = { commits++ }, freeViewRotation = true)
            }
        }
        rule.onNodeWithTag("cube3d").performTouchInput {
            down(0, center + Offset(-60f, 0f))
            down(1, center + Offset(60f, 0f))
            moveBy(0, Offset(0f, 80f))
            moveBy(1, Offset(0f, -80f))
            up(0)
            up(1)
        }
        rule.runOnIdle {
            assertEquals(demo.start.toFacelets(), cube.renderedFacelets())
            assertEquals(0, commits)
        }
    }

    @Test fun `transport steps speed and view gestures never turn layers`() {
        show()
        rule.onNodeWithTag("playback:forward").performClick()
        assertBoundary(1)
        rule.onNodeWithTag("playback:back").performClick()
        assertBoundary(0)
        rule.onNodeWithTag("playback:speed:DOUBLE").performClick().assertIsSelected()
        rule.onNodeWithTag("cube3d").performTouchInput {
            swipe(
                center,
                center +
                    androidx.compose.ui.geometry
                        .Offset(50f, 30f),
            )
        }
        assertBoundary(0)
        rule.onNodeWithTag("cube3d").performTouchInput { doubleClick() }
        assertBoundary(0)
        rule.onNodeWithTag("stage:resetView").assertExists()
    }

    @Test fun `free view drag orbits on cube and double tap resets without committing a Move`() {
        val cube = Cube3DRenderer(demo.start.toFacelets())
        var commits = 0
        rule.setContent {
            CubentiaTheme {
                cube.Content(RenderStyle(false), Modifier.size(300.dp), onMoveIntent = { commits++ }, freeViewRotation = true)
            }
        }
        rule.onNodeWithTag("cube3d").performTouchInput {
            swipe(
                center,
                center +
                    androidx.compose.ui.geometry
                        .Offset(60f, 30f),
            )
        }
        rule.runOnIdle {
            assertNotEquals(ViewOrbit.DEFAULT, cube.orbit)
            assertEquals(demo.start.toFacelets(), cube.renderedFacelets())
            assertEquals(0, commits)
        }
        rule.onNodeWithTag("cube3d").performTouchInput { doubleClick() }
        rule.runOnIdle {
            assertEquals(ViewOrbit.DEFAULT, cube.orbit)
            assertEquals(demo.start.toFacelets(), cube.renderedFacelets())
            assertEquals(0, commits)
        }
    }

    @Test fun `diagram cut has no placeholder`() {
        show(diagram = false)
        rule.onNodeWithTag(DIAGRAM_TAG).assertDoesNotExist()
        // The card merges for TalkBack; inspect its individual visual children in the unmerged tree.
        rule.onNodeWithTag("algorithmDetail:strip", useUnmergedTree = true).assertExists()
        demo.moves.indices.forEach { index ->
            rule.onNodeWithTag("algorithmDetail:move:$index", useUnmergedTree = true).assertExists()
        }
    }

    @Test fun `bad notation keeps back and shows failure without player`() {
        show(algorithm.copy(notation = "invalid"))
        rule.onNodeWithTag("algorithmDetail:back").assertExists()
        rule.onNodeWithText("The Library could not be loaded.").assertExists()
        rule.onNodeWithTag("cube3d").assertDoesNotExist()
        rule.onNodeWithTag("playback:playPause").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp")
    fun `landscape cube and transport stay reachable`() {
        show()
        rule.onNodeWithTag("cube3d").assertIsDisplayed()
        rule.onNodeWithTag("playback:playPause").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w560dp-h400dp")
    fun `landscape just under the two pane width keeps every transport button usable`() = assertTransportUsable()

    @Test
    @Config(qualifiers = "w400dp-h300dp")
    fun `narrow landscape stacks so every transport button is displayed and clickable`() = assertTransportUsable()

    private fun assertTransportUsable() {
        show()
        rule.onNodeWithTag("cube3d").assertIsDisplayed()
        val rootWidth = rule.onRoot().getUnclippedBoundsInRoot().right
        for (button in listOf("restart", "back", "playPause", "forward", "end")) {
            val node =
                rule
                    .onNodeWithTag("playback:$button")
                    .assertIsDisplayed()
                    .assertHasClickAction()
                    .assertWidthIsAtLeast(Spacing.touchMin)
            assertTrue("$button runs past the window", node.getUnclippedBoundsInRoot().right <= rootWidth)
        }
        rule.onNodeWithTag("playback:forward").performClick()
        rule.onNodeWithTag("playback:counter").assertTextEquals("1 / ${demo.moves.size}")
        rule.onNodeWithTag("playback:back").performClick()
        rule.onNodeWithTag("playback:counter").assertTextEquals("0 / ${demo.moves.size}")
    }
}
