package dev.cubentia.app.ui.shell

import android.app.Application
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.cubentia.app.content.LessonCatalog
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.app.session.testFlags
import dev.cubentia.app.ui.freecube.FreeCubeViewModel
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.application.port.FlagLevel
import dev.cubentia.application.port.ProgressLoad
import dev.cubentia.application.port.ProgressRecord
import dev.cubentia.application.port.ProgressRepository
import dev.cubentia.application.port.ProgressSave
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SyncState
import dev.cubentia.application.usecase.CompleteLesson
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.LessonId
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.tutor.ProgressEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AD-13: `AppShell` hands the `CAMERA_SCAN` flag to the shipped Stage 6 finale's physical prompt. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class AppShellCameraScanTest {
    @get:Rule
    val rule = createComposeRule()

    private val shipped = ContentLoader.load(ContentSource.SHIPPED)
    private val lessons = (shipped as ContentLoadResult.Loaded).bundle.stages.flatMap { it.lessons }
    private val finale = lessons.single { it.id == FINALE_ID }

    private val completedBeforeFinale =
        lessons.takeWhile { it.id != FINALE_ID }.mapIndexed { index, lesson ->
            ProgressRecord("session-1", index + 1L, ProgressEvent.LessonCompleted(lesson.id))
        }
    private val progress =
        object : ProgressRepository {
            override suspend fun all(): ProgressLoad = ProgressLoad.Found(completedBeforeFinale.map { it.event }, completedBeforeFinale)

            override suspend fun append(records: List<ProgressRecord>): ProgressSave = ProgressSave.Saved
        }
    private val host =
        SessionHost(
            TickingClock(),
            Dispatchers.Default.limitedParallelism(1),
            completeLesson = CompleteLesson(progress),
            progress = progress,
        )

    @Test
    fun `CAMERA_SCAN BETA shows the camera prompt at the Stage 6 finale`() {
        meetFinaleObjectiveInPhysicalMode(FlagLevel.BETA)

        rule.onNodeWithText(CAMERA_PROMPT).assertExists()
        rule.onNodeWithText(CAMERA_FREE_PROMPT).assertDoesNotExist()
    }

    @Test
    fun `CAMERA_SCAN OFF shows the camera-free prompt at the Stage 6 finale`() {
        meetFinaleObjectiveInPhysicalMode(FlagLevel.OFF)

        rule.onNodeWithText(CAMERA_FREE_PROMPT).assertExists()
        rule.onNodeWithText(CAMERA_PROMPT).assertDoesNotExist()
    }

    private fun meetFinaleObjectiveInPhysicalMode(cameraScan: FlagLevel) {
        rule.setContent {
            CubentiaTheme {
                AppShell(
                    host = host,
                    catalog = LessonCatalog.of(shipped),
                    freeCube = FreeCubeViewModel(),
                    showDebugToggles = false,
                    flags = testFlags(cameraScan = cameraScan),
                )
            }
        }
        rule.onNodeWithTag(STAGE_6_TAG).performScrollTo().performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasTestTag(FINALE_TAG) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(FINALE_TAG).performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.lesson
                ?.id == FINALE_ID
        }
        rule.waitForIdle()

        rule.onNodeWithTag("lesson:physicalModeSwitch").performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_AWAITING_CONFIRM }
        rule.waitForIdle()
        rule.onNodeWithTag("confirmCube:matches").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.syncState == SyncState.PHYSICAL_PREDICTED }
        rule.waitForIdle()

        confirmMoves(finale.steps[0].moves)
        runBlocking { host.dispatch(SessionEvent::StepAdvanced) }
        confirmMoves(finale.steps[1].moves)
        rule.waitUntil(5_000) { host.state.value.objectiveMet }
        rule.waitForIdle()
    }

    private fun confirmMoves(notation: String) {
        (Notation.parse(notation) as NotationResult.Parsed).moves.forEach { move ->
            runBlocking { host.dispatch { SessionEvent.PhysicalConfirmed(move, it) } }
        }
    }

    private companion object {
        val FINALE_ID = LessonId("lesson-06-03")
        const val STAGE_6_TAG = "learn:stage:stage-06-last-layer-completion"
        const val FINALE_TAG = "learn:lesson:lesson-06-03"
        const val CAMERA_PROMPT = "Your cube should be solved now. Check it with the camera, or tell me if it is."
        const val CAMERA_FREE_PROMPT = "Your cube should be solved now. Compare it with the picture, then tell me if it is."
    }
}
