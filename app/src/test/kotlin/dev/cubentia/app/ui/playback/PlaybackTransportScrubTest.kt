package dev.cubentia.app.ui.playback

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import dev.cubentia.app.render.Cube3DRenderer
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlaybackTransportScrubTest {
    @get:Rule val rule = createComposeRule()
    private val moves = (Notation.parse("R U R' U'") as NotationResult.Parsed).moves
    private val state = NetPlayback(CubeState.SOLVED, moves, true, Cube3DRenderer(CubeState.SOLVED.toFacelets()))

    @Test fun `scrub is absent by default`() {
        rule.setContent { CubentiaTheme { PlaybackTransport(state) } }
        rule.onNodeWithTag("playback:scrub").assertDoesNotExist()
    }

    @Test fun `slider exposes all boundaries and sends rounded fractions`() {
        var fraction = -1.0
        rule.setContent { CubentiaTheme { PlaybackTransport(state, onScrub = { fraction = it }) } }
        val slider = rule.onNodeWithTag("playback:scrub")
        val range = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0f..4f, range.range)
        assertEquals(3, range.steps)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        rule.runOnIdle { assertEquals(0.5, fraction, 0.0) }
    }
}
