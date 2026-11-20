package dev.cubentia.app.ui.playback

import android.app.Application
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.render.Cube3DRenderer
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.app.ui.theme.RubikTheme
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackTransportScrubTest {
    @get:Rule val rule = createComposeRule()
    private val moves = (Notation.parse("R U R' U'") as NotationResult.Parsed).moves
    private val state = NetPlayback(CubeState.SOLVED, moves, true, Cube3DRenderer(CubeState.SOLVED.toFacelets()))

    @Test fun `scrub is absent by default`() {
        rule.setContent { CubentiaTheme { PlaybackTransport(state, ContentStrings(emptyMap())) } }
        rule.onNodeWithTag("playback:scrub").assertDoesNotExist()
    }

    @Test fun `counter uses the data style by default`() {
        var expectedStyle = TextStyle.Default
        rule.setContent {
            CubentiaTheme {
                expectedStyle = RubikTheme.textStyles.data.copy(color = RubikTheme.tokens.inkSecondary)
                PlaybackTransport(state, ContentStrings(emptyMap()))
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("playback:counter").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(expectedStyle, layouts.single().layoutInput.style)
    }

    @Test fun `scrubber active track is ink-secondary, the thumb ink-primary and the inactive track hairline`() {
        state.stepForward()
        state.stepForward()
        val tokens = dev.cubentia.app.ui.theme.LightTokens
        rule.setContent {
            CubentiaTheme(darkTheme = false) {
                PlaybackTransport(state, ContentStrings(emptyMap()), onScrub = {})
            }
        }
        rule.waitForIdle()
        val pixels = rule.onNodeWithTag("playback:scrub").captureToImage().toPixelMap()
        val colors = buildList { for (x in 0 until pixels.width) for (y in 0 until pixels.height) add(pixels[x, y]) }.toSet()
        // Thumb at the middle: 1/8 of the width samples the active track, 7/8 the inactive track.
        val left = (0 until pixels.height).map { pixels[pixels.width / 8, it] }.toSet()
        val right = (0 until pixels.height).map { pixels[pixels.width * 7 / 8, it] }.toSet()
        assertTrue("active track colors $left", tokens.inkSecondary in left)
        assertFalse("active track colors $left", tokens.inkPrimary in left)
        assertTrue("inactive track colors $right", tokens.hairline in right)
        assertTrue("thumb missing", tokens.inkPrimary in colors)
    }

    @Test fun `slider exposes all boundaries and sends rounded fractions`() {
        var fraction = -1.0
        rule.setContent { CubentiaTheme { PlaybackTransport(state, ContentStrings(emptyMap()), onScrub = { fraction = it }) } }
        val slider = rule.onNodeWithTag("playback:scrub")
        val range = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0f..4f, range.range)
        assertEquals(3, range.steps)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        rule.runOnIdle { assertEquals(0.5, fraction, 0.0) }
    }
}
