package dev.cubentia.app.ui.lesson

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import dev.cubentia.app.content.ContentStrings
import dev.cubentia.app.ui.a11y.assertClickablesAtLeast48dp
import dev.cubentia.app.ui.a11y.assertNoTextOverflow
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.application.session.CubeMode
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionState
import dev.cubentia.application.session.reduce
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.recognition.PhysicalObservation
import dev.cubentia.domain.text.Text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

internal fun ComposeTestRule.assertQuickCheckResult(
    frame: String,
    certainty: String,
    message: String? = null,
) {
    onNodeWithTag("quickCheck:result").assertExists()
    onNodeWithTag("quickCheck:frame").assertTextEquals(frame)
    onAllNodesWithTag("certainty:chip").assertCountEquals(1)
    onNodeWithTag("certainty:chip")
        .assert(hasAnyAncestor(hasTestTag("quickCheck:result")))
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    onNodeWithTag("certainty:label").assertTextEquals(certainty)
    if (message ==
        null
    ) {
        onNodeWithTag("quickCheck:message").assertDoesNotExist()
    } else {
        onNodeWithTag("quickCheck:message").assertTextEquals(message)
    }
}

internal fun quickCheckPartial(cube: CubeState): PhysicalObservation.Partial {
    val colors = cube.toFacelets().colors
    return PhysicalObservation.Partial(setOf(Face.U, Face.F).associateWith { colors.subList(it.ordinal * 9, it.ordinal * 9 + 9) })
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w600dp-h600dp", fontScale = 2.0f)
class QuickCheckResultCardTest {
    @get:Rule val rule = createComposeRule()

    @Test fun eachResultFitsAtDoubleFontSize() {
        val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        val strings = ContentStrings(bundle.strings)
        var session by mutableStateOf(SessionState.IDLE)
        var actions by mutableStateOf(emptyList<QuickCheckResultAction>())
        rule.setContent {
            CubentiaTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    QuickCheckResultCard(session, strings, actions)
                }
            }
        }
        rule.onNodeWithTag("quickCheck:result").assertDoesNotExist()
        // Playback starts only from a cube its solution solves.
        val scramble = FaceTurn(Face.U, Turn.CLOCKWISE)
        val expected = CubeState.SOLVED.apply(scramble)
        val actual = expected.apply(FaceTurn(Face.R, Turn.CLOCKWISE))
        val at = Instant.fromEpochMilliseconds(0)
        val start =
            reduce(SessionState.IDLE, SessionEvent.SolvePlaybackStarted(expected, listOf(scramble.inverse()), at, bundle.tutorConfig)).state
        check(start.expected == expected)
        val physical = reduce(start, SessionEvent.ModeSwitched(CubeMode.PHYSICAL, at)).state

        fun show(
            observation: PhysicalObservation,
            vararg resultActions: QuickCheckResultAction,
        ) {
            val pending = reduce(physical, SessionEvent.QuickCheckRequested(at = at)).state
            val effect = checkNotNull(pending.pendingQuickCheck)
            rule.runOnIdle {
                session = reduce(pending, SessionEvent.QuickCheckObserved(effect.effectId, effect.revision, observation, at)).state
                actions = resultActions.toList()
            }
        }
        val undo = QuickCheckResultAction(Text("msg.recovery.offer.undo"), "undo") {}
        val scan = QuickCheckResultAction(Text("btn.physical.scan-all-faces"), "scan") {}
        val use = QuickCheckResultAction(Text("btn.physical.use-my-cube"), "use") {}

        fun displayed(vararg labels: String) {
            labels.forEach { rule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
            rule.assertNoTextOverflow("Quick Check")
            rule.assertClickablesAtLeast48dp("Quick Check")
        }

        show(PhysicalObservation.Full(expected))
        displayed("VERIFIED", "✓", "Verified")
        show(quickCheckPartial(actual), undo, scan)
        displayed("EXPECTED", "≈", "Likely", "That turn probably moved something else.", "Candidate: R", "Undo", "Scan all 6 faces")
        show(PhysicalObservation.Full(actual), undo, use)
        displayed("EXPECTED", "✓", "Verified", "Undo", "Use my cube as it is now")
        show(PhysicalObservation.Partial(emptyMap()))
        displayed("EXPECTED", "?", "Unknown", "I can't tell what changed.")
    }
}
