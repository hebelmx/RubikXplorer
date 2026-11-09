package dev.cubentia.app.ui.lesson

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.cubentia.app.content.CatalogEntry
import dev.cubentia.app.content.CatalogState
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.application.port.RandomSource
import dev.cubentia.application.session.SessionKind
import dev.cubentia.application.session.SyncState
import dev.cubentia.application.usecase.StagePractice
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.tutor.HintRung
import dev.cubentia.domain.tutor.TeachingMode
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PracticeScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val host =
        SessionHost(
            TickingClock(),
            Dispatchers.Default.limitedParallelism(1),
        )

    private val seededRandom =
        object : RandomSource {
            private val rnd = java.util.Random(42)

            override fun nextInt(until: Int): Int = rnd.nextInt(until)
        }

    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val stagePractice = StagePractice(seededRandom)

    @Test
    fun `independent practice keeps the Physical mode switch and it reaches physical`() {
        val ready = CatalogState.Ready(bundle)
        val stage = bundle.stages.first { it.id == StagePractice.STAGE_2_ID }
        val entry = CatalogEntry(stagePractice.practiceLesson(StagePractice.STAGE_2_ID), stage, bundle.tutorConfig)

        rule.setContent {
            CubentiaTheme {
                LessonScreen(
                    host = host,
                    entry = entry,
                    strings = ready.strings,
                    modes = TeachingMode.entries,
                    onClose = {},
                    isPractice = true,
                )
            }
        }
        rule.waitUntil(5_000) { host.state.value.kind == SessionKind.PRACTICE }
        rule.waitForIdle()

        rule.onNodeWithTag("lesson:physicalMode").onChildAt(0).assertTextEquals("Physical mode")
        rule.onNodeWithTag("lesson:physicalModeSwitch").assertExists().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()

        rule.onNodeWithTag("confirmCube:title").assertTextContains("Confirm your cube")
    }

    @Test
    fun `practice screen step card shows only objective with hints on request`() {
        val ready = CatalogState.Ready(bundle)
        val stage = bundle.stages.first { it.id == StagePractice.STAGE_2_ID }
        val practiceLesson = stagePractice.practiceLesson(StagePractice.STAGE_2_ID)
        val entry = CatalogEntry(practiceLesson, stage, bundle.tutorConfig)

        rule.setContent {
            CubentiaTheme {
                LessonScreen(
                    host = host,
                    entry = entry,
                    strings = ready.strings,
                    modes = TeachingMode.entries,
                    onClose = {},
                    isPractice = true,
                )
            }
        }
        rule.waitUntil(5_000) {
            host.state.value.kind == SessionKind.PRACTICE
        }
        rule.waitForIdle()

        // Practice session kind is active
        assertEquals(SessionKind.PRACTICE, host.state.value.kind)

        // Goal/Objective is displayed
        rule.onNodeWithTag("lesson:goal").assertExists()
        rule.onNodeWithTag("lesson:goal").assertTextContains(ready.strings.resolve(practiceLesson.objective))

        // Step number, Keep, Watch, Moves, TeachingMode switcher are hidden in practice mode
        rule.onNodeWithTag("lesson:stepNumber").assertDoesNotExist()
        rule.onNodeWithTag("lesson:keep").assertDoesNotExist()
        rule.onNodeWithTag("lesson:watch").assertDoesNotExist()
        rule.onNodeWithTag("lesson:moves").assertDoesNotExist()
        rule.onNodeWithTag("teachingMode").assertDoesNotExist()

        // Hint ladder starts at Hint (0/2) and no hint line is shown initially
        rule
            .onNodeWithTag("lesson:hint")
            .performScrollTo()
            .assertExists()
            .assertIsEnabled()
            .assertTextContains("Hint (0/2)")
        rule.onNodeWithTag("lesson:hint:line").assertDoesNotExist()

        // First hint tap climbs to Hint (1/2) and reveals hint line
        rule.onNodeWithTag("lesson:hint").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.hintRung == HintRung.CLUE }
        rule.waitForIdle()
        rule
            .onNodeWithTag("lesson:hint")
            .assertExists()
            .assertIsEnabled()
            .assertTextContains("Hint (1/2)")
        rule.onNodeWithTag("lesson:hint:line").assertExists().assertTextContains("Read the Goal again", substring = true)

        // Second hint tap climbs to Hint (2/2)
        rule.onNodeWithTag("lesson:hint").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.hintRung == HintRung.TARGET }
        rule.waitForIdle()
        rule
            .onNodeWithTag("lesson:hint")
            .assertExists()
            .assertIsNotEnabled()
            .assertTextContains("Hint (2/2)")
        rule.onNodeWithTag("lesson:hint:line").assertExists().assertTextContains("Focus on the pieces needed for this Stage's goal.")
    }
}
