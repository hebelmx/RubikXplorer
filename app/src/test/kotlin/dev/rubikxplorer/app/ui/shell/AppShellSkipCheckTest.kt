package dev.rubikxplorer.app.ui.shell

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.rubikxplorer.app.content.LessonCatalog
import dev.rubikxplorer.app.preferences.PreferenceStore
import dev.rubikxplorer.app.random.KotlinRandomSource
import dev.rubikxplorer.app.session.SessionHost
import dev.rubikxplorer.app.session.TickingClock
import dev.rubikxplorer.app.session.testBundle
import dev.rubikxplorer.app.session.testFlags
import dev.rubikxplorer.app.ui.freecube.FreeCubeViewModel
import dev.rubikxplorer.app.ui.theme.RubikXplorerTheme
import dev.rubikxplorer.application.port.AttemptLoad
import dev.rubikxplorer.application.port.AttemptRepository
import dev.rubikxplorer.application.port.ProgressLoad
import dev.rubikxplorer.application.port.ProgressRecord
import dev.rubikxplorer.application.port.ProgressRepository
import dev.rubikxplorer.application.port.ProgressSave
import dev.rubikxplorer.application.session.AttemptRecord
import dev.rubikxplorer.application.usecase.CompleteLesson
import dev.rubikxplorer.application.usecase.PassSkipCheck
import dev.rubikxplorer.application.usecase.StagePractice
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.content.FaceletString
import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.content.LessonId
import dev.rubikxplorer.domain.content.RecoveryRef
import dev.rubikxplorer.domain.content.StageId
import dev.rubikxplorer.domain.content.Step
import dev.rubikxplorer.domain.content.TextKey
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.predicate.AlwaysTrue
import dev.rubikxplorer.domain.predicate.CubeSolved
import dev.rubikxplorer.domain.predicate.WhiteCrossSolved
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class AppShellSkipCheckTest {
    @get:Rule val rule = createComposeRule()

    private val preferences = PreferenceStore()
    private val savedProgress = mutableListOf<ProgressRecord>()

    private val progressRepo =
        object : ProgressRepository {
            override suspend fun all(): ProgressLoad = ProgressLoad.Found(savedProgress.map { it.event }, savedProgress.toList())

            override suspend fun append(records: List<ProgressRecord>): ProgressSave {
                savedProgress.addAll(records)
                return ProgressSave.Saved
            }
        }

    private val attemptRepo =
        object : AttemptRepository {
            override suspend fun all(): AttemptLoad = AttemptLoad.Found(emptyList())
        }

    private fun createHost(): SessionHost =
        SessionHost(
            clock = TickingClock(),
            confined = Dispatchers.Main.immediate,
            preferences = preferences,
            completeLesson = CompleteLesson(progressRepo),
            progress = progressRepo,
            attempts = attemptRepo,
            passSkipCheck = PassSkipCheck(progressRepo),
        )

    private fun testControlledStagePractice(): StagePractice =
        object : StagePractice(KotlinRandomSource(Random(1))) {
            override fun courseSkipCheckLesson(): Lesson {
                // Scramble is 1 turn: U. Turning U' will solve it.
                val state = CubeState.SOLVED.apply(FaceTurn(Face.U, Turn.CLOCKWISE))
                val objective = TextKey("msg.practice.stage-07-independent-practice.objective")
                return Lesson(
                    id = LessonId("skip-check-course"),
                    stageId = STAGE_7_ID,
                    prerequisites = emptyList(),
                    objective = objective,
                    entryState = FaceletString.encode(state.toFacelets()),
                    demonstration = "",
                    learnerAction = objective,
                    completion = CubeSolved,
                    preserve = AlwaysTrue,
                    recovery = RecoveryRef("recovery-undo-last-move"),
                    steps =
                        listOf(
                            Step(
                                goal = objective,
                                preserve = TextKey(""),
                                watch = TextKey(""),
                                notice = TextKey(""),
                                moves = "",
                                done = CubeSolved,
                            ),
                        ),
                )
            }

            override fun skipCheckLesson(stageId: StageId): Lesson {
                if (stageId == STAGE_2_ID) {
                    // Scramble is 1 turn: F. Turning F' will solve the white cross.
                    val state = CubeState.SOLVED.apply(FaceTurn(Face.F, Turn.CLOCKWISE))
                    val objective = TextKey("msg.practice.stage-02-first-cross.objective")
                    return Lesson(
                        id = LessonId("skip-check-stage-02-first-cross"),
                        stageId = STAGE_2_ID,
                        prerequisites = emptyList(),
                        objective = objective,
                        entryState = FaceletString.encode(state.toFacelets()),
                        demonstration = "",
                        learnerAction = objective,
                        completion = WhiteCrossSolved,
                        preserve = AlwaysTrue,
                        recovery = RecoveryRef("recovery-undo-last-move"),
                        steps =
                            listOf(
                                Step(
                                    goal = objective,
                                    preserve = TextKey(""),
                                    watch = TextKey(""),
                                    notice = TextKey(""),
                                    moves = "",
                                    done = WhiteCrossSolved,
                                ),
                            ),
                    )
                }
                return super.skipCheckLesson(stageId)
            }
        }

    @Test
    fun `welcome first launch displays options and tapping Im new opens Stage 1 detail`() {
        val host = createHost()
        val shipped = ContentLoader.load(ContentSource.SHIPPED)
        val catalog = LessonCatalog.of(shipped)

        rule.setContent {
            RubikXplorerTheme {
                AppShell(
                    host = host,
                    catalog = catalog,
                    freeCube = FreeCubeViewModel(),
                    showDebugToggles = false,
                    flags = testFlags(),
                    preferenceStore = preferences,
                    showWelcome = true,
                )
            }
        }

        rule.onNodeWithTag("welcome:imNew").assertIsDisplayed().performClick()

        // Welcome dismisses, Learn tab opens with Stage 1 selected
        rule.onNodeWithTag("welcome").assertDoesNotExist()
        rule.onNodeWithTag("stageDetail").assertIsDisplayed()
        rule.onNodeWithTag("stageDetail:title").assertTextContains("1").assertTextContains("Cube fundamentals")
    }

    @Test
    fun `tapping I can already solve it opens course skip check with hints disabled and no mode switcher`() {
        val host = createHost()
        val shipped = ContentLoader.load(ContentSource.SHIPPED)
        val catalog = LessonCatalog.of(shipped)

        rule.setContent {
            RubikXplorerTheme {
                AppShell(
                    host = host,
                    catalog = catalog,
                    freeCube = FreeCubeViewModel(),
                    showDebugToggles = false,
                    flags = testFlags(),
                    preferenceStore = preferences,
                    showWelcome = true,
                    stagePractice = testControlledStagePractice(),
                )
            }
        }

        rule.onNodeWithTag("welcome:canSolve").performClick()

        // Course skip check opens
        rule.onNodeWithTag("lesson").assertIsDisplayed()
        rule
            .onNodeWithTag(
                "lesson:skipCheckInstruction",
            ).assertIsDisplayed()
            .assertTextContains("Solve this scramble your way. Hints are off.")
        rule.onNodeWithTag("lesson:hint").assertIsNotEnabled()
        rule.onNodeWithTag("lesson:modeSwitcher").assertDoesNotExist()
    }

    @Test
    fun `abandoning course skip check logs zero attempts and shows abandoned banner on Welcome`() {
        val host = createHost()
        val shipped = ContentLoader.load(ContentSource.SHIPPED)
        val catalog = LessonCatalog.of(shipped)

        rule.setContent {
            RubikXplorerTheme {
                AppShell(
                    host = host,
                    catalog = catalog,
                    freeCube = FreeCubeViewModel(),
                    showDebugToggles = false,
                    flags = testFlags(),
                    preferenceStore = preferences,
                    showWelcome = true,
                    stagePractice = testControlledStagePractice(),
                )
            }
        }

        rule.onNodeWithTag("welcome:canSolve").performClick()
        rule.onNodeWithTag("lesson").assertIsDisplayed()

        // Commit a move
        rule.onNodeWithTag("moveKey:R").performScrollTo().performClick()
        rule.waitForIdle()

        // Close/abandon skip check
        rule.onNodeWithTag("lesson:close").performClick()
        rule.waitForIdle()

        // Returns to Welcome with abandoned banner
        rule.onNodeWithTag("welcome").assertIsDisplayed()
        rule.onNodeWithTag("welcome:abandoned").assertIsDisplayed().assertTextContains("Let's find where to start")

        // Zero attempts logged!
        assertEquals(0, host.attemptRecords.value.size)
    }

    @Test
    fun `solving course skip check marks all seven stages Skipped never Mastered and unlocks Faster last layer`() {
        val host = createHost()
        val shipped = ContentLoader.load(ContentSource.SHIPPED)
        val catalog = LessonCatalog.of(shipped)

        rule.setContent {
            RubikXplorerTheme {
                AppShell(
                    host = host,
                    catalog = catalog,
                    freeCube = FreeCubeViewModel(),
                    showDebugToggles = false,
                    flags = testFlags(),
                    preferenceStore = preferences,
                    showWelcome = true,
                    stagePractice = testControlledStagePractice(),
                )
            }
        }

        rule.onNodeWithTag("welcome:canSolve").performClick()
        rule.onNodeWithTag("lesson").assertIsDisplayed()

        // Solve the 1-move scramble (U was applied, turn U' to solve)
        rule.onNodeWithTag("moveKey:U'").performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("lesson:objectiveMet").assertIsDisplayed()
        rule.onNodeWithTag("lesson:next").assertIsEnabled().performClick()
        rule.waitForIdle()

        // Returns to Learn tab
        rule.onNodeWithTag("learn").assertIsDisplayed()

        // All 7 stages show ◇ Skipped (never Mastered!)
        val stages = (shipped as ContentLoadResult.Loaded).bundle.stages
        stages.forEach { stage ->
            val status = rule.onNodeWithTag("learn:stage:status:${stage.id.value}").performScrollTo()
            status.assertTextContains("Skipped").assertTextContains("◇")
        }

        // Faster last layer row is unlocked
        rule.onNodeWithTag("learn:fasterLastLayer").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `abandoning Stage 2 skip check logs zero attempts and shows abandoned copy on Stage detail`() {
        val host = createHost()
        val shipped = ContentLoader.load(ContentSource.SHIPPED)
        val catalog = LessonCatalog.of(shipped)

        rule.setContent {
            RubikXplorerTheme {
                AppShell(
                    host = host,
                    catalog = catalog,
                    freeCube = FreeCubeViewModel(),
                    showDebugToggles = false,
                    flags = testFlags(),
                    preferenceStore = preferences,
                    showWelcome = false,
                    stagePractice = testControlledStagePractice(),
                )
            }
        }

        // Open Stage 2 detail
        rule.onNodeWithTag("learn:stage:stage-02-first-cross").performScrollTo().performClick()
        rule.onNodeWithTag("stageDetail").assertIsDisplayed()

        // Tap I already know this
        rule.onNodeWithTag("stageDetail:skipCheck").performClick()
        rule.onNodeWithTag("lesson").assertIsDisplayed()
        rule.onNodeWithTag("lesson:hint").assertIsNotEnabled()

        // Commit a move
        rule.onNodeWithTag("moveKey:R").performScrollTo().performClick()
        rule.waitForIdle()

        // Abandon check
        rule.onNodeWithTag("lesson:close").performClick()
        rule.waitForIdle()

        // Stage detail shows abandoned banner
        rule.onNodeWithTag("stageDetail").assertIsDisplayed()
        rule
            .onNodeWithTag(
                "stageDetail:skipCheckAbandoned",
            ).assertIsDisplayed()
            .assertTextContains("Let's do this Stage together — it'll be quick.")

        // Zero attempts logged!
        assertEquals(0, host.attemptRecords.value.size)
    }

    @Test
    fun `solving Stage 2 skip check marks Stage 2 Skipped never Mastered and unlocks Stage 3`() {
        val host = createHost()
        val shipped = ContentLoader.load(ContentSource.SHIPPED)
        val catalog = LessonCatalog.of(shipped)

        rule.setContent {
            RubikXplorerTheme {
                AppShell(
                    host = host,
                    catalog = catalog,
                    freeCube = FreeCubeViewModel(),
                    showDebugToggles = false,
                    flags = testFlags(),
                    preferenceStore = preferences,
                    showWelcome = false,
                    stagePractice = testControlledStagePractice(),
                )
            }
        }

        // Open Stage 2 detail
        rule.onNodeWithTag("learn:stage:stage-02-first-cross").performScrollTo().performClick()
        rule.onNodeWithTag("stageDetail:skipCheck").performClick()
        rule.onNodeWithTag("lesson").assertIsDisplayed()

        // Solve the 1-move scramble (F was applied, turn F' to solve white cross)
        rule.onNodeWithTag("moveKey:F'").performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("lesson:objectiveMet").assertIsDisplayed()
        rule.onNodeWithTag("lesson:next").assertIsEnabled().performClick()
        rule.waitForIdle()

        // Navigates to Stage 3 detail, which is now unlocked (In progress)
        rule.onNodeWithTag("stageDetail").assertIsDisplayed()
        rule.onNodeWithTag("stageDetail:title").assertTextContains("3").assertTextContains("First-layer corners")
        rule.onNodeWithTag("stageDetail:status").assertTextContains("In progress")

        // Back to Stage list
        rule.onNodeWithTag("stageDetail:back").performClick()
        rule
            .onNodeWithTag(
                "learn:stage:status:stage-02-first-cross",
            ).performScrollTo()
            .assertTextContains("Skipped")
            .assertTextContains("◇")
    }

    @Test
    fun `system back gesture during Stage 2 skip check logs zero attempts and shows abandoned copy`() {
        val host = createHost()
        val shipped = ContentLoader.load(ContentSource.SHIPPED)
        val catalog = LessonCatalog.of(shipped)
        lateinit var dispatcher: OnBackPressedDispatcher

        rule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            RubikXplorerTheme {
                AppShell(
                    host = host,
                    catalog = catalog,
                    freeCube = FreeCubeViewModel(),
                    showDebugToggles = false,
                    flags = testFlags(),
                    preferenceStore = preferences,
                    showWelcome = false,
                    stagePractice = testControlledStagePractice(),
                )
            }
        }

        rule.onNodeWithTag("learn:stage:stage-02-first-cross").performScrollTo().performClick()
        rule.onNodeWithTag("stageDetail:skipCheck").performClick()
        rule.onNodeWithTag("lesson").assertIsDisplayed()

        // Press system back
        rule.runOnIdle { dispatcher.onBackPressed() }
        rule.waitForIdle()

        // Stage detail shows abandoned banner
        rule.onNodeWithTag("stageDetail").assertIsDisplayed()
        rule.onNodeWithTag("stageDetail:skipCheckAbandoned").assertIsDisplayed()
        assertEquals(0, host.attemptRecords.value.size)
    }
}
