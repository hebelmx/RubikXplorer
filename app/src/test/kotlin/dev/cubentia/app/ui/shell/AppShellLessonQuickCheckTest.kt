package dev.cubentia.app.ui.shell

import android.app.Application
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import dev.cubentia.app.content.LessonCatalog
import dev.cubentia.app.preferences.PreferenceStore
import dev.cubentia.app.scan.QuickCheckCapture
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.app.session.testFlags
import dev.cubentia.app.ui.freecube.FreeCubeViewModel
import dev.cubentia.app.ui.lesson.QuickCheckHighlightedKey
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.application.port.FlagLevel
import dev.cubentia.application.session.Effect
import dev.cubentia.application.session.SessionKind
import dev.cubentia.application.session.SyncState
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Story 14.3: a 2-look Lesson opened from Learn on the main shell, in Physical-cube Mode, with and without `CAMERA_SCAN`. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class AppShellLessonQuickCheckTest {
    @get:Rule val rule = createComposeRule()

    private val loaded = ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded
    private val preferences = PreferenceStore()
    private val host = SessionHost(TickingClock(), Dispatchers.Default.limitedParallelism(1), preferences = preferences)
    private val started = mutableListOf<Effect.QuickCheck>()

    private fun openTwoLookLessonInPhysicalMode(cameraScan: FlagLevel) {
        runBlocking { host.passSkipCheck(loaded.bundle.stages.map { it.id }, loaded.bundle.stages) }
        rule.setContent {
            CubentiaTheme {
                AppShell(
                    host,
                    LessonCatalog.of(loaded),
                    FreeCubeViewModel(),
                    false,
                    testFlags(cameraScan = cameraScan),
                    preferenceStore = preferences,
                    // A capture is wired: only CAMERA_SCAN decides whether Quick Check shows.
                    quickCheck = QuickCheckCapture { started += it },
                )
            }
        }
        rule.onNodeWithTag("nav:learn").performClick()
        rule.onNodeWithTag("learn:stage:stage-08-two-look-oll").performScrollTo().performClick()
        rule.onNodeWithTag("learn:lesson:lesson-08-01").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.kind == SessionKind.TWO_LOOK }
        rule.waitForIdle()
        rule.onNodeWithText("Physical mode", useUnmergedTree = true).performTouchInput { click() }
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()
        // The Confirm card's Scan it is a camera control too: only CAMERA_SCAN above OFF shows it.
        if (cameraScan == FlagLevel.OFF) {
            rule.onNodeWithTag("confirmCube:scan").assertDoesNotExist()
        } else {
            rule.onNodeWithTag("confirmCube:scan").assertExists()
        }
        rule.onNodeWithTag("lesson:quickCheck").assertDoesNotExist()
        rule.onNodeWithTag("confirmCube:matches").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()
        val before = host.state.value.expected
        rule.onNodeWithTag("lesson:done").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.expected != before }
        rule.waitForIdle()
        assertFalse(host.state.value.objectiveMet)
    }

    @Test fun `CAMERA_SCAN on offers Quick Check mid-step in a 2-look Lesson`() {
        openTwoLookLessonInPhysicalMode(FlagLevel.GA)
        rule
            .onNodeWithTag("lesson:quickCheck")
            .assert(SemanticsMatcher.expectValue(QuickCheckHighlightedKey, false))
            .performScrollTo()
            .performClick()
        rule.waitUntil(5_000) { started.isNotEmpty() }
        assertTrue(host.state.value.pendingQuickCheck != null)
    }

    @Test fun `CAMERA_SCAN OFF shows no camera control and Done still advances a 2-look Lesson`() {
        openTwoLookLessonInPhysicalMode(FlagLevel.OFF)
        rule.onNodeWithTag("lesson:quickCheck").assertDoesNotExist()
        val before = host.state.value.expected
        rule.onNodeWithTag("lesson:done").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.expected != before }
        rule.onNodeWithTag("lesson:quickCheck").assertDoesNotExist()
        assertTrue(started.isEmpty())
    }
}
