package dev.cubentia.app.ui.playback

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MoveStripTest {
    @get:Rule val rule = createComposeRule()
    private val strings = ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)
    private val moves = List(12) { FaceTurn(Face.R, Turn.CLOCKWISE) }
    private val current = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Current move")

    @Test fun chipsSpeakTheirIndexAndKeepTheCurrentMoveFullyVisible() {
        val index = mutableStateOf(2)
        var selected = -1
        rule.setContent {
            CubentiaTheme {
                androidx.compose.foundation.layout.Box(Modifier.width(170.dp)) {
                    MoveStrip(moves, index.value, pictures = true, strings) { selected = it }
                }
            }
        }
        val third = rule.onNodeWithTag("playback:chip:2")
        third
            .assertContentDescriptionEquals("Move 3 of 12, R")
            .assert(current)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction()
            .performClick()
        assertEquals(2, selected)
        rule.runOnIdle { index.value = 11 }
        val row = rule.onNodeWithTag("playback:strip").fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithTag("playback:chip:11").assert(current).fetchSemanticsNode()
        assertTrue(last.size.width > 0)
        assertTrue(last.boundsInRoot.left >= row.left && last.boundsInRoot.right <= row.right)
        assertEquals(last.size.width.toFloat(), last.boundsInRoot.width, 0.5f)
    }

    @Test fun readOnlyChipStillSpeaksTheMoveAndCurrentState() {
        rule.setContent { CubentiaTheme { MoveStrip(moves.take(1), 0, pictures = false, strings, onSelect = null) } }
        rule
            .onNodeWithTag("playback:chip:0")
            .assertContentDescriptionEquals("Move 1 of 1, R")
            .assert(current)
            .assertHasNoClickAction()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
    }
}
