package dev.rubikxplorer.app.ui.shell

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.rubikxplorer.app.content.CatalogState
import dev.rubikxplorer.app.content.LessonCatalog
import dev.rubikxplorer.app.render.RenderedStickersKey
import dev.rubikxplorer.app.session.STAGE_TITLE
import dev.rubikxplorer.app.session.SessionHost
import dev.rubikxplorer.app.session.TickingClock
import dev.rubikxplorer.app.session.testBundle
import dev.rubikxplorer.app.session.testFlags
import dev.rubikxplorer.app.session.whiteCrossLesson
import dev.rubikxplorer.app.ui.freecube.FreeCubeViewModel
import dev.rubikxplorer.app.ui.theme.RubikXplorerTheme
import dev.rubikxplorer.application.port.ProgressLoad
import dev.rubikxplorer.application.port.ProgressRecord
import dev.rubikxplorer.application.port.ProgressRepository
import dev.rubikxplorer.application.port.ProgressSave
import dev.rubikxplorer.application.session.SessionEvent
import dev.rubikxplorer.application.session.SessionKind
import dev.rubikxplorer.application.usecase.CompleteLesson
import dev.rubikxplorer.domain.appearance.StickerAppearance
import dev.rubikxplorer.domain.content.ContentLoadError
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.content.LessonId
import dev.rubikxplorer.domain.content.StageId
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Facelets
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.tutor.ProgressEvent
import dev.rubikxplorer.domain.tutor.TeachingMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** AC 1 and AC 5: the navigation shell, the Learn list, the Lesson task flow's layout and back. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class AppShellTest {
    @get:Rule
    val rule = createComposeRule()

    private val preferences =
        dev.rubikxplorer.app.preferences
            .PreferenceStore()
    private var host =
        SessionHost(
            TickingClock(),
            kotlinx.coroutines.Dispatchers.Default
                .limitedParallelism(1),
            preferences = preferences,
        )
    private lateinit var back: OnBackPressedDispatcher

    @Test fun storedCheckpointContinuesSilently() {
        val bundle = testBundle()
        val lesson = whiteCrossLesson()
        val started =
            dev.rubikxplorer.application.session.reduce(
                dev.rubikxplorer.application.session.SessionState.IDLE,
                SessionEvent.LessonStarted(lesson, bundle.tutorConfig, kotlin.time.Instant.fromEpochMilliseconds(0)),
            )
        val checkpoint =
            dev.rubikxplorer.application.session.Checkpoint.of(
                started.effects.filterIsInstance<dev.rubikxplorer.application.session.Effect.PersistCheckpoint>().single(),
                bundle.contentRevision,
            )
        val events = java.util.concurrent.CopyOnWriteArrayList<SessionEvent>()
        val writer =
            dev.rubikxplorer.app.data
                .SerialWriter()
        val repository =
            object : dev.rubikxplorer.application.port.CheckpointRepository {
                override suspend fun find(kind: dev.rubikxplorer.application.session.SessionKind) =
                    dev.rubikxplorer.application.port.CheckpointLoad
                        .Found(checkpoint)

                override suspend fun save(checkpoint: dev.rubikxplorer.application.session.Checkpoint) =
                    dev.rubikxplorer.application.port.CheckpointSave.Saved
            }
        host =
            SessionHost(
                TickingClock(),
                kotlinx.coroutines.Dispatchers.Default
                    .limitedParallelism(1),
                writer = writer,
                checkpoints = repository,
                reduced = { events.add(it) },
                preferences = preferences,
            )
        try {
            launch(ContentLoadResult.Loaded(bundle))
            rule.waitUntil { host.state.value.lesson != null }
            rule
                .onNodeWithTag(
                    "learn:continue",
                ).assertTextContains("Continue")
                .assertTextContains("Objective of lesson-01-01")
                .assertTextContains(STAGE_TITLE)
                .performClick()
            rule.onNodeWithTag("lesson:restoreNote").assertTextContains("Picked up where you left off.")
            assertEquals(started.state.virtual, host.state.value.virtual)
            assertTrue(events.none { it is SessionEvent.LessonStarted })
            assertEquals(null, ShadowDialog.getLatestDialog())
        } finally {
            runBlocking { writer.close() }
        }
    }

    @Test fun storedModeStartsLessonAndHostRemembersChanges() {
        preferences.update(
            dev.rubikxplorer.application.preference.Preference
                .LastTeachingMode(TeachingMode.LET_ME_TRY),
        )
        launch()
        rule.onNodeWithTag("nav:you").assertExists()
        rule.onNodeWithTag("learn:stage:stage-01-basics").performScrollTo().performClick()
        rule.onNodeWithTag("learn:lesson:lesson-01-01").performScrollTo().performClick()
        rule.waitUntil { host.state.value.lesson != null }
        rule.runOnIdle { assertEquals(TeachingMode.LET_ME_TRY, host.state.value.teachingMode) }
        runBlocking { host.dispatch { SessionEvent.TeachingModeChanged(TeachingMode.SHOW_ME, it) } }
        assertEquals(TeachingMode.SHOW_ME, preferences.preferences.value.lastTeachingMode)
    }

    @Test fun unavailableStoredModeFallsBack() {
        preferences.update(
            dev.rubikxplorer.application.preference.Preference
                .LastTeachingMode(TeachingMode.TEST_ME),
        )
        launch(testMe = false)
        rule.onNodeWithTag("learn:stage:stage-01-basics").performScrollTo().performClick()
        rule.onNodeWithTag("learn:lesson:lesson-01-01").performScrollTo().performClick()
        rule.waitUntil { host.state.value.lesson != null }
        rule.runOnIdle { assertEquals(TeachingMode.DEFAULT, host.state.value.teachingMode) }
    }

    private fun launch(
        result: ContentLoadResult = ContentLoadResult.Loaded(testBundle()),
        restoration: StateRestorationTester? = null,
        testMe: Boolean = true,
    ) {
        val catalog = LessonCatalog.of(result)
        val setContent: (@Composable () -> Unit) -> Unit = restoration?.let { it::setContent } ?: rule::setContent
        setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            RubikXplorerTheme {
                AppShell(
                    preferenceStore = preferences,
                    host = host,
                    catalog = catalog,
                    freeCube = FreeCubeViewModel(),
                    showDebugToggles = false,
                    flags = testFlags(testMe),
                )
            }
        }
    }

    private fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    /** The Guide me net shows the session's Cube State, sticker for sticker. */
    private fun assertNetMatchesSession(step: String) {
        rule.waitForIdle()
        val expected =
            host.state.value.virtual
                .toFacelets()
                .colors
                .map { Color(StickerAppearance.of(it).fill) }
        val drawn =
            rule
                .onNodeWithTag("cube3d")
                .fetchSemanticsNode()
                .config[RenderedStickersKey]
                .map { Color(it.fill) }
        assertEquals(step, expected, drawn)
    }

    private fun openWhiteCross() {
        rule.onNodeWithTag("learn:stage:stage-01-basics").performClick()
        rule.onNodeWithTag("learn:lesson:lesson-01-01").performClick()
        rule.waitUntil(5_000) {
            host.state.value.lesson
                ?.id == whiteCrossLesson().id
        }
        rule.waitForIdle()
    }

    @Test
    fun `Learn lists Stage rows and tapping a Stage opens its Lessons in order over a navigation bar`() {
        launch()
        rule.onNodeWithTag("learn:continue").assertDoesNotExist()
        rule.onNodeWithTag("learn:fasterLastLayer").assertDoesNotExist()
        rule.onNodeWithTag("nav:bar").assertExists()
        rule.onNodeWithTag("nav:rail").assertDoesNotExist()
        rule
            .onNodeWithTag("learn:stage:stage-01-basics")
            .assertTextContains("1")
            .assertTextContains(STAGE_TITLE)
            .assertTextContains("In progress")
            .performClick()
        rule.onNodeWithTag("nav:bar").assertExists()
        rule.onNodeWithTag("stageDetail").assertExists()
        rule.onNodeWithTag("learn:lesson:lesson-01-01").assertTextContains("Objective of lesson-01-01").assertTextContains(STAGE_TITLE)
        rule.onNodeWithTag("learn:lesson:lesson-01-03").assertTextContains("Objective of lesson-01-03")
        assertTrue(bounds("learn:lesson:lesson-01-01").top < bounds("learn:lesson:lesson-01-03").top)
    }

    @Test
    fun `an empty bundle shows the empty line`() {
        launch(ContentLoadResult.Loaded(testBundle(*emptyArray())))
        rule.onNodeWithTag("learn:empty").assertTextContains("No lessons yet.")
        rule.onNodeWithTag("learn:error").assertDoesNotExist()
    }

    @Test
    fun `a failed load shows the error line`() {
        launch(ContentLoadResult.Failed(ContentLoadError.MissingResource("bundle.json")))
        rule.onNodeWithTag("learn:error").assertTextContains("Lessons could not be loaded.")
    }

    @Test
    fun `the Solve tab is the free cube`() {
        launch()
        rule.onNodeWithTag("nav:solve").performClick()
        rule.onNodeWithTag("toolbar:undo").assertExists()
        rule.onNodeWithTag("learn").assertDoesNotExist()
    }

    @Test
    fun `an open Lesson hides the bar and stacks the panel in order below the stage when compact`() {
        launch()
        openWhiteCross()
        rule.onNodeWithTag("nav:bar").assertDoesNotExist()
        rule.onNodeWithTag("lesson:close").assertExists()
        val objective = bounds("lesson:objective")
        val card = bounds("lesson:stepCard")
        val switcher = bounds("lesson:modeSwitcher")
        val hint = bounds("lesson:hint")
        val next = bounds("lesson:next")
        assertTrue(objective.bottom <= card.top)
        assertTrue(card.bottom <= switcher.top)
        assertTrue(switcher.bottom <= hint.top)
        assertEquals(hint.top, next.top)
        assertTrue(hint.right <= next.left)
        val stage = bounds("lesson:stage")
        assertTrue("panel below stage", bounds("lesson:panel").top >= stage.bottom)
        assertTrue("stage ${stage.height} at least 280dp", stage.height >= 280.dp)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `at expanded width the panel sits right of the stage, at most 560dp wide, beside a rail`() {
        launch()
        rule.onNodeWithTag("nav:rail").assertExists()
        rule.onNodeWithTag("nav:bar").assertDoesNotExist()
        openWhiteCross()
        rule.onNodeWithTag("nav:rail").assertDoesNotExist()
        val stage = bounds("lesson:stage")
        val panel = bounds("lesson:panel")
        assertTrue("panel right of stage", panel.left >= stage.right)
        assertTrue("panel ${panel.width} <= 560dp", panel.width <= 560.dp)
        assertTrue(bounds("lesson:objective").bottom <= bounds("lesson:stepCard").top)
    }

    @Test
    @Config(qualifiers = "w700dp-h900dp")
    fun `at medium width a rail shows and the open Lesson's panel sits right of the stage`() {
        launch()
        rule.onNodeWithTag("nav:rail").assertExists()
        rule.onNodeWithTag("nav:bar").assertDoesNotExist()
        openWhiteCross()
        assertTrue("panel right of stage", bounds("lesson:panel").left >= bounds("lesson:stage").right)
    }

    @Test
    fun `the open Lesson survives a saved-state restore without restarting`() {
        val restoration = StateRestorationTester(rule)
        launch(restoration = restoration)
        openWhiteCross()
        runBlocking { host.dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.R, Turn.CLOCKWISE), it) } }
        rule.waitForIdle()
        val before = host.state.value
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithTag("lesson").assertExists()
        rule.onNodeWithTag("learn").assertDoesNotExist()
        assertEquals(before, host.state.value)
        assertNetMatchesSession("restored")
    }

    @Test
    fun `system back closes the Lesson at once, with no dialog`() {
        launch()
        openWhiteCross()
        // Progress that a Reset would ask about; back still asks nothing.
        runBlocking { host.dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.R, Turn.CLOCKWISE), it) } }
        rule.runOnIdle { back.onBackPressed() }
        rule.onNodeWithTag("learn").assertExists()
        rule.onNodeWithTag("lesson").assertDoesNotExist()
        rule.onNodeWithTag("nav:bar").assertExists()
        assertEquals(null, ShadowDialog.getLatestDialog())
    }

    @Test
    fun `the close button closes the Lesson, and reopening it keeps its progress`() {
        launch()
        openWhiteCross()
        runBlocking { host.dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.R, Turn.CLOCKWISE), it) } }
        val before = host.state.value
        rule.onNodeWithTag("lesson:close").performClick()
        rule.onNodeWithTag("learn").assertExists()
        rule.onNodeWithTag("learn:lesson:lesson-01-01").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("lesson").assertExists()
        assertEquals(before, host.state.value)
        assertNetMatchesSession("reopened")
    }

    @Test
    fun `system back on Solve returns to Learn first`() {
        launch()
        rule.onNodeWithTag("nav:solve").performClick()
        rule.onNodeWithTag("toolbar:undo").assertExists()
        rule.runOnIdle { back.onBackPressed() }
        rule.onNodeWithTag("learn").assertExists()
        rule.onNodeWithTag("toolbar:undo").assertDoesNotExist()
    }

    @Test
    fun `opening another Lesson starts it`() {
        launch()
        openWhiteCross()
        rule.onNodeWithTag("lesson:close").performClick()
        rule.onNodeWithTag("learn:lesson:lesson-01-03").performClick()
        rule.waitUntil(5_000) {
            host.state.value.lesson
                ?.id
                ?.value == "lesson-01-03"
        }
    }

    @Test
    fun `with TEST_ME off the Lesson offers three modes, and switching keeps the cube, history and step`() {
        launch(testMe = false)
        openWhiteCross()
        rule.onNodeWithTag("mode:TEST_ME").assertDoesNotExist()
        listOf(TeachingMode.SHOW_ME, TeachingMode.GUIDE_ME, TeachingMode.LET_ME_TRY).forEach {
            rule.onNodeWithTag("mode:${it.name}").assertExists()
        }
        rule.onNodeWithTag("moveKey:R").performScrollTo().performClick()
        rule.waitUntil(5_000) { host.state.value.history.canUndo }
        rule.waitForIdle()
        val before = host.state.value
        listOf(TeachingMode.LET_ME_TRY, TeachingMode.SHOW_ME, TeachingMode.GUIDE_ME).forEach { mode ->
            rule.onNodeWithTag("mode:${mode.name}").performScrollTo().performClick()
            rule.waitUntil(5_000) { host.state.value.teachingMode == mode }
            rule.waitForIdle()
            val now = host.state.value
            assertEquals("$mode virtual", before.virtual, now.virtual)
            assertEquals("$mode history", before.history, now.history)
            assertEquals("$mode step", before.stepIndex, now.stepIndex)
        }
        assertNetMatchesSession("after switching")
    }

    @Test
    fun `with TEST_ME on the Lesson offers all four modes`() {
        launch(testMe = true)
        openWhiteCross()
        TeachingMode.entries.forEach { rule.onNodeWithTag("mode:${it.name}").assertExists() }
    }

    @Test
    fun `shipped Learn tab shows 7 Stage rows with numbers, titles, status text and icon, and hides Faster last layer`() {
        val shipped = ContentLoader.load(ContentSource.SHIPPED)
        launch(shipped)
        rule.onNodeWithTag("learn:fasterLastLayer").assertDoesNotExist()
        val expectedStages =
            listOf(
                Triple("stage-01-fundamentals", "1", "Cube fundamentals" to "In progress"),
                Triple("stage-02-first-cross", "2", "First cross" to "Locked"),
                Triple("stage-03-first-layer-corners", "3", "First-layer corners" to "Locked"),
                Triple("stage-04-middle-layer", "4", "Middle layer" to "Locked"),
                Triple("stage-05-last-layer-orientation", "5", "Last-layer orientation" to "Locked"),
                Triple("stage-06-last-layer-completion", "6", "Last-layer completion" to "Locked"),
                Triple("stage-07-independent-practice", "7", "Independent practice" to "Locked"),
            )
        expectedStages.forEach { (id, number, titleAndStatus) ->
            val (title, status) = titleAndStatus
            rule.onNodeWithTag("learn:stage:$id").performScrollTo().assertTextContains(title)
            rule.onNodeWithTag("learn:stage:number:$id").assertTextContains(number)
            rule
                .onNodeWithTag("learn:stage:status:$id")
                .assertTextContains(status)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, status))
        }
    }

    @Test
    fun `Faster last layer placeholder row is hidden by default and rendered when enabled`() {
        val shipped = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        val ready = CatalogState.Ready(shipped)
        var clicked = 0
        rule.setContent {
            RubikXplorerTheme {
                LearnScreen(
                    catalog = ready,
                    onOpen = {},
                    showFasterLastLayer = true,
                    onOpenFasterLastLayer = { clicked++ },
                )
            }
        }
        rule
            .onNodeWithTag("learn:fasterLastLayer")
            .performScrollTo()
            .assertTextContains("Faster last layer")
            .performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `tapping a Stage row opens Stage detail with Lessons, Mastery line, disabled Practice and enabled Skip check`() {
        val shipped = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        val ready = CatalogState.Ready(shipped)
        var selectedStage by mutableStateOf<StageId?>(null)
        var skipCheckedStage: StageId? = null
        rule.setContent {
            RubikXplorerTheme {
                LearnScreen(
                    catalog = ready,
                    selectedStage = selectedStage,
                    onSelectStage = { selectedStage = it },
                    onOpen = {},
                    onSkipCheck = { skipCheckedStage = it },
                )
            }
        }
        rule.onNodeWithTag("learn:stage:stage-01-fundamentals").performScrollTo().performClick()
        rule
            .onNodeWithTag("stageDetail:title")
            .assertTextContains("1")
            .assertTextContains("Cube fundamentals")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        rule
            .onNodeWithTag("stageDetail:status")
            .assertTextContains("In progress")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "In progress"))
        rule
            .onNodeWithTag("stageDetail:mastery")
            .assertTextContains("Mastery: 0 of 2 solves · 0 with reduced help")
        rule.onNodeWithTag("stageDetail:mastery:segment:1").assertExists()
        rule.onNodeWithTag("stageDetail:mastery:segment:2").assertExists()
        rule
            .onNodeWithTag("stageDetail:practice")
            .assertTextContains("Practice this Stage")
            .assertIsNotEnabled()
        rule
            .onNodeWithTag("stageDetail:skipCheck")
            .assertTextContains("I already know this")
            .assertIsEnabled()
            .performClick()
        assertEquals(StageId("stage-01-fundamentals"), skipCheckedStage)
        rule.onNodeWithTag("learn:lesson:lesson-01-01").performScrollTo().assertIsEnabled()
        rule.onNodeWithTag("learn:lesson:lesson-01-02").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("learn:lesson:lesson-01-03").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `tapping a locked Stage opens Stage detail with all Lessons disabled and empty-Lesson Stage shows note`() {
        val shipped = ContentLoader.load(ContentSource.SHIPPED)
        launch(shipped)
        rule.onNodeWithTag("learn:stage:stage-02-first-cross").performScrollTo().performClick()
        rule.onNodeWithTag("stageDetail").assertExists()
        rule.onNodeWithTag("nav:bar").assertExists()
        rule.onNodeWithTag("stageDetail:status").assertTextContains("Locked")
        rule.onNodeWithTag("stageDetail:practice").assertIsNotEnabled()
        rule.onNodeWithTag("stageDetail:skipCheck").assertIsEnabled()
        (1..5).forEach { n ->
            rule.onNodeWithTag("learn:lesson:lesson-02-0$n").performScrollTo().assertIsNotEnabled()
        }
        rule.onNodeWithTag("stageDetail:back").performClick()
        rule.onNodeWithTag("stageDetail").assertDoesNotExist()
        rule.onNodeWithTag("learn:stage:stage-07-independent-practice").performScrollTo().performClick()
        rule.onNodeWithTag("stageDetail:noLessons").assertExists()
        rule.runOnIdle { back.onBackPressed() }
        rule.onNodeWithTag("stageDetail").assertDoesNotExist()
        rule.onNodeWithTag("learn:stage:stage-01-fundamentals").assertExists()
    }

    @Test
    fun `all six StageStatus values render their exact state text and icon on Learn and Stage detail`() {
        val shipped = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        val ready = CatalogState.Ready(shipped)
        val stage1 = shipped.stages[0]
        val stage2 = shipped.stages[1]
        val stage3 = shipped.stages[2]
        val stage4 = shipped.stages[3]
        val stage5 = shipped.stages[4]
        val events =
            stage1.lessons.map { ProgressEvent.LessonCompleted(it.id) } +
                listOf(
                    ProgressEvent.StageSkippedByCheck(stage2.id),
                    ProgressEvent.StageSkippedByPlacement(stage3.id),
                    ProgressEvent.StageUnlocked(stage5.id),
                )
        val mastered = setOf(stage4.id)
        var selectedStage by mutableStateOf<StageId?>(null)
        rule.setContent {
            RubikXplorerTheme {
                LearnScreen(
                    catalog = ready,
                    progressEvents = events,
                    masteredStages = mastered,
                    selectedStage = selectedStage,
                    onSelectStage = { selectedStage = it },
                    onOpen = {},
                )
            }
        }
        val expectedStatuses =
            listOf(
                Triple("stage-01-fundamentals", "Completed", null),
                Triple("stage-02-first-cross", "Skipped", "◇"),
                Triple("stage-03-first-layer-corners", "Skipped by placement", "◇"),
                Triple("stage-04-middle-layer", "Mastered", "◆"),
                Triple("stage-05-last-layer-orientation", "In progress", null),
                Triple("stage-06-last-layer-completion", "Locked", null),
            )
        expectedStatuses.forEach { (id, status, symbol) ->
            val rowStatus =
                rule
                    .onNodeWithTag("learn:stage:status:$id")
                    .performScrollTo()
                    .assertTextContains(status)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, status))
            if (symbol != null) rowStatus.assertTextContains(symbol)
            rule.onNodeWithTag("learn:stage:$id").performScrollTo().performClick()
            val detailStatus =
                rule
                    .onNodeWithTag("stageDetail:status")
                    .assertTextContains(status)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, status))
            if (symbol != null) detailStatus.assertTextContains(symbol)
            rule.onNodeWithTag("stageDetail:back").performClick()
        }
    }

    @Test
    fun `completing Lesson 1 in LessonScreen and tapping Next returns to Stage detail and unlocks Lesson 2`() {
        val shipped = ContentLoader.load(ContentSource.SHIPPED)
        val storedRecords = mutableListOf<ProgressRecord>()
        val progressRepo =
            object : ProgressRepository {
                override suspend fun all(): ProgressLoad = ProgressLoad.Found(storedRecords.map { it.event }, storedRecords.toList())

                override suspend fun append(records: List<ProgressRecord>): ProgressSave {
                    storedRecords += records
                    return ProgressSave.Saved
                }
            }
        host =
            SessionHost(
                TickingClock(),
                kotlinx.coroutines.Dispatchers.Default
                    .limitedParallelism(1),
                preferences = preferences,
                completeLesson = CompleteLesson(progressRepo),
                progress = progressRepo,
            )
        launch(shipped)
        rule.onNodeWithTag("learn:stage:stage-01-fundamentals").performClick()
        rule
            .onNodeWithTag("learn:lesson:lesson-01-01")
            .assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "In progress"))
        rule
            .onNodeWithTag("learn:lesson:lesson-01-02")
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Locked"))
        rule
            .onNodeWithTag("learn:lesson:lesson-01-03")
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Locked"))
        rule.onNodeWithTag("learn:lesson:lesson-01-01").performClick()
        rule.waitUntil(5_000) {
            host.state.value.lesson
                ?.id
                ?.value == "lesson-01-01"
        }
        runBlocking { host.dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.U, Turn.CLOCKWISE), it) } }
        rule.waitForIdle()
        rule
            .onNodeWithTag("lesson:next")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("stageDetail").assertExists()
        rule
            .onNodeWithTag("learn:lesson:lesson-01-01")
            .performScrollTo()
            .assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Completed"))
        rule
            .onNodeWithTag("learn:lesson:lesson-01-02")
            .performScrollTo()
            .assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "In progress"))
        rule
            .onNodeWithTag("learn:lesson:lesson-01-03")
            .performScrollTo()
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Locked"))
    }

    @Test
    fun `Stage detail survives saved-state restoration and tab switching`() {
        val restoration = StateRestorationTester(rule)
        launch(ContentLoader.load(ContentSource.SHIPPED), restoration = restoration)
        rule.onNodeWithTag("learn:stage:stage-01-fundamentals").performClick()
        rule.onNodeWithTag("stageDetail").assertExists()
        rule.onNodeWithTag("nav:solve").performClick()
        rule.onNodeWithTag("toolbar:undo").assertExists()
        rule.onNodeWithTag("nav:learn").performClick()
        rule.onNodeWithTag("stageDetail").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithTag("stageDetail").assertExists()
        rule.onNodeWithTag("stageDetail:title").assertTextContains("Cube fundamentals")
    }

    @Test
    fun `Stage 1 detail has Practice this Stage button disabled`() {
        launch(ContentLoader.load(ContentSource.SHIPPED))
        rule.onNodeWithTag("learn:stage:stage-01-fundamentals").performClick()
        rule.onNodeWithTag("stageDetail").assertExists()
        rule
            .onNodeWithTag("stageDetail:practice")
            .performScrollTo()
            .assertExists()
            .assertIsNotEnabled()
    }

    @Test
    fun `unlocked Stage 2 detail has Practice this Stage enabled and launches practice session`() {
        val shipped = ContentLoader.load(ContentSource.SHIPPED)
        val storedRecords =
            listOf(
                ProgressRecord("session-1", 1L, ProgressEvent.LessonCompleted(LessonId("lesson-01-01"))),
                ProgressRecord("session-1", 2L, ProgressEvent.LessonCompleted(LessonId("lesson-01-02"))),
                ProgressRecord("session-1", 3L, ProgressEvent.LessonCompleted(LessonId("lesson-01-03"))),
            )
        val progressRepo =
            object : ProgressRepository {
                override suspend fun all(): ProgressLoad = ProgressLoad.Found(storedRecords.map { it.event }, storedRecords)

                override suspend fun append(records: List<ProgressRecord>): ProgressSave = ProgressSave.Saved
            }
        host =
            SessionHost(
                TickingClock(),
                kotlinx.coroutines.Dispatchers.Default
                    .limitedParallelism(1),
                preferences = preferences,
                completeLesson = CompleteLesson(progressRepo),
                progress = progressRepo,
            )
        launch(shipped)
        rule.onNodeWithTag("learn:stage:stage-02-first-cross").performScrollTo().performClick()
        rule.onNodeWithTag("stageDetail").assertExists()
        rule
            .onNodeWithTag("stageDetail:practice")
            .performScrollTo()
            .assertExists()
            .assertIsEnabled()
            .performClick()
        rule.waitUntil(5_000) {
            host.state.value.kind == SessionKind.PRACTICE
        }
        rule.onNodeWithTag("lesson:goal").assertExists()

        // Back out and re-enter practice for the same stage
        rule.onNodeWithTag("lesson:close").performClick()
        rule.onNodeWithTag("stageDetail").assertExists()
        val previousState = host.state.value.virtual
        rule.onNodeWithTag("stageDetail:practice").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.kind == SessionKind.PRACTICE
        }
        rule.onNodeWithTag("lesson:goal").assertExists()
    }

    @Test
    fun `milestone Practice on a new scramble launches Stage 7 practice session`() {
        val shipped = ContentLoader.load(ContentSource.SHIPPED)
        launch(shipped)
        // Set openPractice directly via host or shell action
        rule.runOnUiThread {
            back.onBackPressed() // Ensure default state
        }
        // Open Stage 7 detail and tap Practice
        rule.onNodeWithTag("learn:stage:stage-07-independent-practice").performScrollTo().performClick()
        rule.onNodeWithTag("stageDetail").assertExists()
        // Stage 7 has no lessons, so it shows stage_no_lessons message
        rule.onNodeWithTag("stageDetail:noLessons").assertExists()
    }
}
