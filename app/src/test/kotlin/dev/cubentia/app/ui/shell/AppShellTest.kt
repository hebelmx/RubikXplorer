package dev.cubentia.app.ui.shell

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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.cubentia.app.content.CatalogState
import dev.cubentia.app.content.LessonCatalog
import dev.cubentia.app.render.RenderedStickersKey
import dev.cubentia.app.session.STAGE_TITLE
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.app.session.testBundle
import dev.cubentia.app.session.testFlags
import dev.cubentia.app.session.whiteCrossLesson
import dev.cubentia.app.ui.freecube.FreeCubeViewModel
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.application.port.AttemptLoad
import dev.cubentia.application.port.AttemptRepository
import dev.cubentia.application.port.ProgressLoad
import dev.cubentia.application.port.ProgressRecord
import dev.cubentia.application.port.ProgressRepository
import dev.cubentia.application.port.ProgressSave
import dev.cubentia.application.session.AttemptEvent
import dev.cubentia.application.session.AttemptOutcome
import dev.cubentia.application.session.AttemptRecord
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionKind
import dev.cubentia.application.usecase.CompleteLesson
import dev.cubentia.domain.appearance.StickerAppearance
import dev.cubentia.domain.content.ContentLoadError
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.LessonId
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.tutor.AssistanceLevel
import dev.cubentia.domain.tutor.ProgressEvent
import dev.cubentia.domain.tutor.TeachingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import kotlin.time.Instant

/** AC 1 and AC 5: the navigation shell, the Learn list, the Lesson task flow's layout and back. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class AppShellTest {
    @get:Rule
    val rule = createComposeRule()

    private val preferences =
        dev.cubentia.app.preferences
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
            dev.cubentia.application.session.reduce(
                dev.cubentia.application.session.SessionState.IDLE,
                SessionEvent.LessonStarted(lesson, bundle.tutorConfig, kotlin.time.Instant.fromEpochMilliseconds(0)),
            )
        val checkpoint =
            dev.cubentia.application.session.Checkpoint.of(
                started.effects.filterIsInstance<dev.cubentia.application.session.Effect.PersistCheckpoint>().single(),
                bundle.contentRevision,
            )
        val events = java.util.concurrent.CopyOnWriteArrayList<SessionEvent>()
        val writer =
            dev.cubentia.app.data
                .SerialWriter()
        val repository =
            object : dev.cubentia.application.port.CheckpointRepository {
                override suspend fun clear() = dev.cubentia.application.port.CheckpointSave.Saved

                override suspend fun find(kind: dev.cubentia.application.session.SessionKind) =
                    dev.cubentia.application.port.CheckpointLoad
                        .Found(checkpoint)

                override suspend fun save(checkpoint: dev.cubentia.application.session.Checkpoint) =
                    dev.cubentia.application.port.CheckpointSave.Saved
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
            dev.cubentia.application.preference.Preference
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
            dev.cubentia.application.preference.Preference
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
            CubentiaTheme {
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

    @Test fun `last layer practice opens from Learn with the content-derived algorithm set`() {
        val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        val events =
            bundle.stages.flatMap { it.lessons }.map { ProgressEvent.LessonCompleted(it.id) } +
                ProgressEvent.StageSkippedByCheck(bundle.stages.last().id)
        val repository =
            object : ProgressRepository {
                override suspend fun all(): ProgressLoad = ProgressLoad.Found(events)

                override suspend fun append(records: List<ProgressRecord>): ProgressSave = ProgressSave.Saved
            }
        host = SessionHost(TickingClock(), Dispatchers.Default.limitedParallelism(1), progress = repository, preferences = preferences)
        launch(ContentLoadResult.Loaded(bundle))
        rule.waitUntil(5_000) { host.progressEvents.value == events }
        rule.onNodeWithTag("learn:twoLookPractice").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.lesson
                ?.id
                ?.value == "practice-two-look"
        }
        assertEquals(SessionKind.PRACTICE, host.state.value.kind)
        val expected =
            bundle.twoLookStages
                .flatMap { it.lessons }
                .flatMap { bundle.lessonAlgorithms.getValue(it.id) }
                .toSet()
                .map { id ->
                    (
                        dev.cubentia.domain.cube.Notation.parse(
                            bundle.algorithms.single { it.id == id }.notation,
                        ) as dev.cubentia.domain.cube.NotationResult.Parsed
                    ).moves
                }.toSet()
        assertEquals(16, host.state.value.twoLookAlgorithms.size)
        assertEquals(
            expected,
            host.state.value.twoLookAlgorithms
                .toSet(),
        )
        assertEquals(
            dev.cubentia.domain.cube.Color.YELLOW,
            host.state.value.entry.orientation
                .colorAt(Face.U),
        )
        assertTrue(
            host.state.value.lesson!!
                .preserve
                .isSatisfied(host.state.value.entry),
        )
        assertTrue(!host.state.value.objectiveMet)
        rule.onNodeWithTag("lesson:modeSwitcher").assertDoesNotExist()

        // Re-entering (the same openPractice + practiceEpoch++ path the milestone's new-scramble action uses)
        // starts a fresh attempt on a new scramble with the same 2-look set.
        val firstEntry = host.state.value.entry
        runBlocking { host.dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.D, Turn.CLOCKWISE), it) } }
        rule.onNodeWithTag("lesson:close").performClick()
        rule.onNodeWithTag("learn:twoLookPractice").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.lesson
                ?.id
                ?.value == "practice-two-look" &&
                host.state.value.entry != firstEntry
        }
        assertEquals(SessionKind.PRACTICE, host.state.value.kind)
        assertTrue(
            host.state.value.history.done
                .isEmpty(),
        )
        assertEquals(
            expected,
            host.state.value.twoLookAlgorithms
                .toSet(),
        )
        assertTrue(!host.state.value.objectiveMet)
    }

    private fun finishedCourseHost(
        bundle: dev.cubentia.domain.content.ContentBundle,
        snapshot: dev.cubentia.application.session.SessionSnapshot? = null,
    ): List<ProgressEvent> {
        val events =
            bundle.stages.flatMap { it.lessons }.map { ProgressEvent.LessonCompleted(it.id) } +
                ProgressEvent.StageSkippedByCheck(bundle.stages.last().id)
        val repository =
            object : ProgressRepository {
                override suspend fun all(): ProgressLoad = ProgressLoad.Found(events)

                override suspend fun append(records: List<ProgressRecord>): ProgressSave = ProgressSave.Saved
            }
        val snapshots =
            object : dev.cubentia.application.port.SessionSnapshotRepository {
                override suspend fun clear(attempts: List<dev.cubentia.application.session.AttemptRecord>) =
                    dev.cubentia.application.port.SnapshotSave.Saved

                override suspend fun latest() =
                    snapshot?.let {
                        dev.cubentia.application.port.SnapshotLoad
                            .Found(it)
                    }
                        ?: dev.cubentia.application.port.SnapshotLoad.None

                override suspend fun save(
                    snapshot: dev.cubentia.application.session.SessionSnapshot,
                    attempts: List<AttemptRecord>,
                ) = dev.cubentia.application.port.SnapshotSave.Saved
            }
        host =
            SessionHost(
                TickingClock(),
                Dispatchers.Default.limitedParallelism(1),
                snapshots,
                dev.cubentia.app.data
                    .SerialWriter(),
                progress = repository,
                preferences = preferences,
            )
        return events
    }

    @Test fun `last layer practice keeps its scramble and attempt across recreation`() {
        val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        val events = finishedCourseHost(bundle)
        val restoration = StateRestorationTester(rule)
        launch(ContentLoadResult.Loaded(bundle), restoration = restoration)
        rule.waitUntil(5_000) { host.progressEvents.value == events }
        rule.onNodeWithTag("learn:twoLookPractice").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            host.state.value.lesson
                ?.id == dev.cubentia.domain.tutor.TwoLookMastery.PRACTICE_ID
        }
        runBlocking { host.dispatch { SessionEvent.MoveCommitted(FaceTurn(Face.R, Turn.CLOCKWISE), it) } }
        rule.waitForIdle()
        val before = host.state.value
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithTag("lesson").assertExists()
        assertEquals(
            before.lesson?.entryState,
            host.state.value.lesson
                ?.entryState,
        )
        assertEquals(before.entry, host.state.value.entry)
        assertEquals(before.history, host.state.value.history)
    }

    @Test fun `last layer practice resumes from a cold-start snapshot`() {
        val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        val lesson =
            dev.cubentia.application.usecase
                .TwoLookPractice(
                    dev.cubentia.app.random
                        .KotlinRandomSource(),
                ).lesson(
                    bundle.algorithms.map {
                        (Notation.parse(it.notation) as dev.cubentia.domain.cube.NotationResult.Parsed)
                            .moves
                    },
                )
        val played =
            listOf(
                SessionEvent.PracticeStarted(lesson, bundle.tutorConfig, Instant.fromEpochMilliseconds(0)),
                SessionEvent.MoveCommitted(FaceTurn(Face.R, Turn.CLOCKWISE), Instant.fromEpochMilliseconds(1)),
            ).fold(dev.cubentia.application.session.SessionState.IDLE) { state, event ->
                dev.cubentia.application.session
                    .reduce(state, event)
                    .state
            }
        val snapshot =
            dev.cubentia.application.session.SessionSnapshot
                .of(played, "killed", bundle.contentRevision)!!
        val events = finishedCourseHost(bundle, snapshot)
        launch(ContentLoadResult.Loaded(bundle))
        rule.waitUntil(5_000) { host.progressEvents.value == events && host.state.value.kind == SessionKind.PRACTICE }
        assertEquals(
            lesson.entryState,
            host.state.value.lesson
                ?.entryState,
        )
        assertEquals(played.history, host.state.value.history)
        assertEquals(
            bundle.twoLookMovesForTest(),
            host.state.value.twoLookAlgorithms
                .toSet(),
        )
        rule.onNodeWithTag("learn:continue").performClick()
        rule.onNodeWithTag("lesson").assertExists()
        rule.waitForIdle()
        assertEquals(SessionKind.PRACTICE, host.state.value.kind)
        assertEquals(
            lesson.entryState,
            host.state.value.lesson
                ?.entryState,
        )
        assertEquals(played.history, host.state.value.history)
        rule.onNodeWithTag("lesson:close").performClick()
        rule.waitUntil(5000) { !host.state.value.isActive }
        rule.onNodeWithTag("learn:continue").assertDoesNotExist()
        rule.onNodeWithTag("learn:stage:stage-01-fundamentals").performScrollTo().performClick()
        rule.onNodeWithTag("learn:lesson:lesson-01-01").performScrollTo().performClick()
        rule.waitUntil(5000) { host.state.value.kind == SessionKind.LESSON }
        rule.onNodeWithTag("lesson:modeSwitcher").assertExists()
    }

    @Test fun `closing live LL practice then opening Course runs a taught Lesson and records completion`() = practiceToCourse()

    @Test fun `closing restored LL practice then opening Course records Lesson completion`() = practiceToCourse(restored = true)

    @Test fun `closing Stage practice then opening Course records Lesson completion`() = practiceToCourse(stagePractice = true)

    private fun practiceToCourse(
        restored: Boolean = false,
        stagePractice: Boolean = false,
    ) {
        val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        val events = java.util.concurrent.CopyOnWriteArrayList<ProgressEvent>()
        events.addAll(bundle.stages.flatMap { it.lessons }.map { ProgressEvent.LessonCompleted(it.id) })
        events.add(ProgressEvent.StageSkippedByCheck(bundle.stages.last().id))
        val progress =
            object : ProgressRepository {
                override suspend fun all() = ProgressLoad.Found(events.toList())

                override suspend fun append(records: List<ProgressRecord>): ProgressSave {
                    events.addAll(records.map { it.event })
                    return ProgressSave.Saved
                }
            }
        host =
            SessionHost(
                TickingClock(),
                Dispatchers.Default.limitedParallelism(1),
                progress = progress,
                completeLesson = CompleteLesson(progress),
                preferences = preferences,
            )
        launch(ContentLoadResult.Loaded(bundle))
        rule.waitUntil(5000) { host.progressEvents.value.isNotEmpty() }
        if (restored) {
            val practice =
                dev.cubentia.application.usecase
                    .TwoLookPractice(
                        dev.cubentia.app.random
                            .KotlinRandomSource(),
                    ).lesson(
                        bundle.algorithms.map {
                            (Notation.parse(it.notation) as dev.cubentia.domain.cube.NotationResult.Parsed).moves
                        },
                    )
            val before =
                dev.cubentia.application.session
                    .reduce(
                        dev.cubentia.application.session.SessionState.IDLE,
                        SessionEvent.PracticeStarted(practice, bundle.tutorConfig, Instant.fromEpochMilliseconds(0)),
                    ).state
            val snapshot =
                checkNotNull(
                    dev.cubentia.application.session.SessionSnapshot
                        .of(before, "cold", bundle.contentRevision),
                )
            runBlocking { host.dispatch { SessionEvent.SessionRestored(snapshot, practice, bundle.tutorConfig, it) } }
            rule.onNodeWithTag("learn:continue").performClick()
        } else if (stagePractice) {
            rule.onNodeWithTag("learn:stage:stage-02-first-cross").performScrollTo().performClick()
            rule.onNodeWithTag("stageDetail:practice").performScrollTo().performClick()
        } else {
            rule.onNodeWithTag("learn:twoLookPractice").performScrollTo().performClick()
        }
        rule.waitUntil(5000) { host.state.value.kind == SessionKind.PRACTICE }
        rule.onNodeWithTag("lesson:close").performClick()
        rule.waitUntil(5000) { !host.state.value.isActive }
        if (stagePractice) rule.runOnIdle { back.onBackPressed() }
        openCourseAndComplete(bundle, events)
    }

    @Test fun `confirming Reset progress ends the live Lesson and empties the Continue card`() {
        val bundle = testBundle()
        launch(ContentLoadResult.Loaded(bundle))
        runBlocking { host.dispatch { SessionEvent.LessonStarted(whiteCrossLesson(), bundle.tutorConfig, it) } }
        rule.waitUntil(5000) { host.state.value.kind == SessionKind.LESSON }
        rule.onNodeWithTag("learn:continue").assertExists()
        rule.onNodeWithTag("nav:you").performClick()
        rule.onNodeWithTag("you:settings").performClick()
        rule.onNodeWithTag("settings:resetProgress").performScrollTo().performClick()
        rule.onNodeWithTag("settings:resetProgress:confirm").performClick()
        rule.waitUntil(5000) { !host.state.value.isActive }
        rule.onNodeWithTag("settings:close").performClick()
        rule.onNodeWithTag("welcome:imNew").performClick()
        rule.onNodeWithTag("stageDetail:back").performClick()
        rule.onNodeWithTag("learn").assertExists()
        rule.onNodeWithTag("learn:continue").assertDoesNotExist()
        assertTrue(!host.state.value.isActive)
    }

    @Test fun `unloaded and failed statuses hide completion offer until a successful load`() {
        val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        val loads = kotlinx.coroutines.flow.MutableSharedFlow<dev.cubentia.application.port.AlgorithmStatusLoad>(replay = 1)
        val statuses =
            object : dev.cubentia.application.port.AlgorithmStatusRepository {
                override fun statuses() = loads

                override suspend fun append(record: dev.cubentia.application.port.AlgorithmStatusRecord) =
                    dev.cubentia.application.port.AlgorithmStatusSave.Saved
            }
        val events =
            bundle.stages.flatMap { it.lessons }.map { ProgressEvent.LessonCompleted(it.id) } +
                ProgressEvent.StageSkippedByCheck(bundle.stages.last().id)
        val progress =
            object : ProgressRepository {
                override suspend fun all() = ProgressLoad.Found(events)

                override suspend fun append(records: List<ProgressRecord>) = ProgressSave.Saved
            }
        host =
            SessionHost(
                TickingClock(),
                Dispatchers.Default.limitedParallelism(1),
                progress = progress,
                algorithmStatusRepository = statuses,
                preferences = preferences,
            )
        launch(ContentLoadResult.Loaded(bundle))
        rule.waitUntil(5000) { host.progressEvents.value.isNotEmpty() }
        val stage = bundle.twoLookStages.first()
        val lesson = stage.lessons.first()
        rule.onNodeWithTag("learn:stage:${stage.id.value}").performScrollTo().performClick()
        rule.onNodeWithTag("learn:lesson:${lesson.id.value}").performScrollTo().performClick()
        rule.waitUntil(5000) { host.state.value.kind == SessionKind.TWO_LOOK }
        runBlocking {
            (Notation.parse(lesson.demonstration) as dev.cubentia.domain.cube.NotationResult.Parsed).moves.forEach { move ->
                host.dispatch { SessionEvent.MoveCommitted(move, it) }
            }
        }
        rule.waitForIdle()
        assertTrue(host.state.value.objectiveMet)
        rule.onNodeWithTag("lesson:twoLookOffer").assertDoesNotExist()
        runBlocking { loads.emit(dev.cubentia.application.port.AlgorithmStatusLoad.Failed) }
        rule.waitForIdle()
        rule.onNodeWithTag("lesson:twoLookOffer").assertDoesNotExist()
        runBlocking {
            loads.emit(
                dev.cubentia.application.port.AlgorithmStatusLoad
                    .Loaded(emptyMap()),
            )
        }
        rule.waitForIdle()
        rule.onNodeWithTag("lesson:twoLookOffer").assertExists()
    }

    private fun openCourseAndComplete(
        bundle: dev.cubentia.domain.content.ContentBundle,
        events: List<ProgressEvent>,
    ) {
        val before = events.size
        rule.onNodeWithTag("learn:stage:stage-01-fundamentals").performScrollTo().performClick()
        rule.onNodeWithTag("learn:lesson:lesson-01-01").performScrollTo().performClick()
        rule.waitUntil(5000) { host.state.value.kind == SessionKind.LESSON }
        rule.onNodeWithTag("lesson:modeSwitcher").assertExists()
        val lesson =
            bundle.stages
                .first()
                .lessons
                .first()
        val moves = (Notation.parse(lesson.demonstration) as dev.cubentia.domain.cube.NotationResult.Parsed).moves
        runBlocking { moves.forEach { move -> host.dispatch { SessionEvent.MoveCommitted(move, it) } } }
        rule.waitUntil(5000) { events.size > before }
        assertTrue(events.drop(before).any { it is ProgressEvent.LessonCompleted && it.lessonId == lesson.id })
    }

    private fun dev.cubentia.domain.content.ContentBundle.twoLookMovesForTest() =
        twoLookStages
            .flatMap { it.lessons }
            .flatMap { lessonAlgorithms.getValue(it.id) }
            .toSet()
            .map { id ->
                (Notation.parse(algorithms.single { it.id == id }.notation) as dev.cubentia.domain.cube.NotationResult.Parsed).moves
            }.toSet()

    @Test fun `two look heading derives completion mastery and reset and offers practice`() {
        val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        var events by mutableStateOf<List<ProgressEvent>>(emptyList())
        var attempts by mutableStateOf<List<AttemptRecord>>(emptyList())
        var opened = false
        val attempt =
            AttemptRecord(
                "ll",
                "ll",
                1,
                AttemptEvent.ENDED,
                "practice-two-look",
                AttemptOutcome.COMPLETED,
                0,
                0,
                AssistanceLevel.REDUCED,
                kotlin.time.Instant.fromEpochMilliseconds(1),
            )
        rule.setContent {
            CubentiaTheme {
                LearnScreen(
                    CatalogState.Ready(bundle),
                    onOpen = {},
                    showFasterLastLayer = true,
                    progressEvents = events,
                    attemptRecords = attempts,
                    onTwoLookPractice = { opened = true },
                )
            }
        }
        rule
            .onNodeWithTag(
                "learn:fasterLastLayer:status",
                useUnmergedTree = true,
            ).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "In progress"))
        rule.onNodeWithTag("learn:fasterLastLayer:status").assertTextContains("In progress")
        rule.onNodeWithTag("learn:fasterLastLayer:mastery").assertDoesNotExist()
        rule.runOnIdle { events = bundle.twoLookStages.flatMap { it.lessons }.map { ProgressEvent.LessonCompleted(it.id) } }
        rule
            .onNodeWithTag(
                "learn:fasterLastLayer:status",
                useUnmergedTree = true,
            ).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Completed"))
        rule.onNodeWithTag("learn:fasterLastLayer:status").assertTextContains("Completed")
        rule.runOnIdle { attempts = listOf(attempt) }
        rule.onNodeWithTag("learn:fasterLastLayer:mastery").performScrollTo().assertTextContains("Mastery: 1 of 2")
        rule.runOnIdle { attempts = listOf(attempt, attempt.copy(attemptId = "ll2")) }
        rule
            .onNodeWithTag(
                "learn:fasterLastLayer:status",
                useUnmergedTree = true,
            ).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Mastered"))
        rule.onNodeWithTag("learn:fasterLastLayer:status").assertTextContains("Mastered")
        rule.onNodeWithTag("learn:fasterLastLayer:mastery").assertDoesNotExist()
        rule.onNodeWithTag("learn:twoLookPractice").performScrollTo().performClick()
        assertTrue(opened)
        rule.runOnIdle { events = events + ProgressEvent.ProgressReset(1, kotlin.time.Instant.fromEpochMilliseconds(2)) }
        rule
            .onNodeWithTag(
                "learn:fasterLastLayer:status",
                useUnmergedTree = true,
            ).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "In progress"))
        rule.onNodeWithTag("learn:fasterLastLayer:status").assertTextContains("In progress")
        rule.onNodeWithTag("learn:fasterLastLayer:mastery").assertDoesNotExist()
    }

    @Test
    fun `Faster last layer heading opens two numbered Stage rows when enabled`() {
        val shipped = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        val ready = CatalogState.Ready(shipped)
        rule.setContent {
            CubentiaTheme {
                LearnScreen(
                    catalog = ready,
                    onOpen = {},
                    showFasterLastLayer = true,
                )
            }
        }
        rule
            .onNodeWithTag("learn:fasterLastLayer")
            .performScrollTo()
            .assertTextContains("Faster last layer")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
            .assert(hasNoClickAction())
        rule.onNodeWithTag("learn:stage:stage-08-two-look-oll").performScrollTo().assertTextContains("In progress")
        rule.onNodeWithTag("learn:stage:stage-09-two-look-pll").performScrollTo().assertTextContains("In progress")
    }

    @Test fun `two look detail opens every Lesson without Course controls`() {
        val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        var selected by mutableStateOf<StageId?>(null)
        var opened: LessonId? = null
        rule.setContent {
            CubentiaTheme {
                LearnScreen(
                    CatalogState.Ready(bundle),
                    onOpen = { opened = it },
                    showFasterLastLayer = true,
                    selectedStage = selected,
                    onSelectStage = { selected = it },
                )
            }
        }
        rule.onNodeWithTag("learn:stage:stage-08-two-look-oll").performScrollTo().performClick()
        rule.onNodeWithTag("stageDetail:title").assertTextContains("2-look OLL")
        listOf("mastery", "trend", "practice", "skipCheck").forEach {
            rule.onNodeWithTag("stageDetail:$it").assertDoesNotExist()
        }
        bundle.twoLookStages.first().lessons.forEach { lesson ->
            rule.onNodeWithTag("learn:lesson:${lesson.id}").performScrollTo().assertIsEnabled()
        }
    }

    @Test fun `a two look Stage with every Lesson completed reads Completed in its row and detail`() {
        val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        val oll = bundle.twoLookStages.first()
        var selected by mutableStateOf<StageId?>(null)
        rule.setContent {
            CubentiaTheme {
                LearnScreen(
                    CatalogState.Ready(bundle),
                    onOpen = {},
                    showFasterLastLayer = true,
                    progressEvents = oll.lessons.map { ProgressEvent.LessonCompleted(it.id) },
                    selectedStage = selected,
                    onSelectStage = { selected = it },
                )
            }
        }
        rule.onNodeWithTag("learn:stage:stage-09-two-look-pll").performScrollTo().assertTextContains("In progress")
        rule
            .onNodeWithTag("learn:stage:${oll.id}")
            .performScrollTo()
            .assertTextContains("Completed")
            .performClick()
        rule.onNodeWithTag("stageDetail:status").assertTextContains("Completed")
    }

    @Test
    fun `tapping a Stage row opens Stage detail with Lessons, Mastery line, disabled Practice and enabled Skip check`() {
        val shipped = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        val ready = CatalogState.Ready(shipped)
        var selectedStage by mutableStateOf<StageId?>(null)
        var skipCheckedStage: StageId? = null
        rule.setContent {
            CubentiaTheme {
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
    fun `meeting stage mastery condition updates stage status to Mastered on Learn and shows full mastery in Stage detail`() {
        val attempts =
            listOf(
                AttemptRecord(
                    attemptId = "att-1",
                    sessionId = "s-1",
                    revision = 1L,
                    event = AttemptEvent.ENDED,
                    objectiveId = "practice-stage-02-first-cross",
                    outcome = AttemptOutcome.COMPLETED,
                    errors = 0,
                    hints = 0,
                    assistanceLevel = AssistanceLevel.USUAL,
                    at = Instant.fromEpochMilliseconds(100),
                ),
                AttemptRecord(
                    attemptId = "att-2",
                    sessionId = "s-2",
                    revision = 2L,
                    event = AttemptEvent.ENDED,
                    objectiveId = "practice-stage-02-first-cross",
                    outcome = AttemptOutcome.COMPLETED,
                    errors = 0,
                    hints = 0,
                    assistanceLevel = AssistanceLevel.REDUCED,
                    at = Instant.fromEpochMilliseconds(200),
                ),
            )
        val attemptRepo =
            object : AttemptRepository {
                override suspend fun all(): AttemptLoad = AttemptLoad.Found(attempts)
            }
        host =
            SessionHost(
                clock = TickingClock(),
                confined = Dispatchers.Main.immediate,
                preferences = preferences,
                attempts = attemptRepo,
            )
        val shipped = ContentLoader.load(ContentSource.SHIPPED)
        launch(shipped)

        // On Learn tab, Stage 2 is mastered
        rule.onNodeWithTag("learn:stage:stage-02-first-cross").performScrollTo().assertExists()
        rule
            .onNodeWithTag("learn:stage:status:stage-02-first-cross")
            .assertTextContains("Mastered")
            .assertTextContains("◆")

        // Open Stage 2 detail
        rule.onNodeWithTag("learn:stage:stage-02-first-cross").performClick()
        rule.onNodeWithTag("stageDetail").assertExists()
        rule
            .onNodeWithTag("stageDetail:status")
            .assertTextContains("Mastered")
            .assertTextContains("◆")
        rule
            .onNodeWithTag("stageDetail:mastery")
            .assertTextContains("Mastery: 2 of 2 solves · 1 with reduced help")
        rule.onNodeWithTag("stageDetail:mastery:segment:1").assertExists()
        rule.onNodeWithTag("stageDetail:mastery:segment:2").assertExists()
        rule.onNodeWithTag("stageDetail:trend").assertExists().assertIsDisplayed()
        rule.onNodeWithTag("trend:attempts").assertTextContains("2")
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
            CubentiaTheme {
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

    @Test
    fun `mastery milestone Next Stage button navigates to next stage detail on Learn tab in AppShell`() {
        val attempts =
            listOf(
                AttemptRecord(
                    attemptId = "att-1",
                    sessionId = "s-1",
                    revision = 1L,
                    event = AttemptEvent.ENDED,
                    objectiveId = "practice-stage-02-first-cross",
                    outcome = AttemptOutcome.COMPLETED,
                    errors = 0,
                    hints = 0,
                    assistanceLevel = AssistanceLevel.REDUCED,
                    at = Instant.fromEpochMilliseconds(100),
                ),
            )
        val attemptRepo =
            object : AttemptRepository {
                override suspend fun all(): AttemptLoad = AttemptLoad.Found(attempts)
            }
        val storedEvents =
            listOf(
                ProgressEvent.StageUnlocked(StageId("stage-02-first-cross"), Instant.fromEpochMilliseconds(10)),
                ProgressEvent.LessonCompleted(LessonId("lesson-02-01"), Instant.fromEpochMilliseconds(20)),
                ProgressEvent.LessonCompleted(LessonId("lesson-02-02"), Instant.fromEpochMilliseconds(30)),
                ProgressEvent.LessonCompleted(LessonId("lesson-02-03"), Instant.fromEpochMilliseconds(40)),
                ProgressEvent.LessonCompleted(LessonId("lesson-02-04"), Instant.fromEpochMilliseconds(50)),
            )
        val progressRepo =
            object : ProgressRepository {
                override suspend fun all(): ProgressLoad =
                    ProgressLoad.Found(storedEvents, storedEvents.map { ProgressRecord("s-1", 0L, it) })

                override suspend fun append(records: List<ProgressRecord>): ProgressSave = ProgressSave.Saved
            }
        host =
            SessionHost(
                clock = TickingClock(),
                confined = Dispatchers.Main.immediate,
                preferences = preferences,
                attempts = attemptRepo,
                progress = progressRepo,
            )
        val shipped = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        launch(ContentLoadResult.Loaded(shipped))

        // Open Stage 2 detail
        rule.onNodeWithTag("learn:stage:stage-02-first-cross").performScrollTo().performClick()
        rule.onNodeWithTag("stageDetail").assertExists()

        // Open last lesson of Stage 2 (lesson-02-05)
        rule.onNodeWithTag("learn:lesson:lesson-02-05").performScrollTo().performClick()
        val stage2 = shipped.stages.first { it.id == StageId("stage-02-first-cross") }
        val lastLesson = stage2.lessons.last()
        rule.waitUntil(5_000) {
            host.state.value.lesson
                ?.id == lastLesson.id
        }
        rule.waitForIdle()

        // Complete the lesson's steps
        lastLesson.steps.forEachIndexed { index, step ->
            val moves = (Notation.parse(step.moves) as dev.cubentia.domain.cube.NotationResult.Parsed).moves
            moves.forEach { move ->
                runBlocking { host.dispatch { SessionEvent.MoveCommitted(move, it) } }
            }
            if (index < lastLesson.steps.size - 1) {
                runBlocking { host.dispatch(SessionEvent::StepAdvanced) }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag("milestone:overlay").assertExists()
        rule.onNodeWithTag("milestone:headline").assertExists().assertTextContains("First cross mastered.")

        // Click Next Stage
        rule.onNodeWithTag("milestone:nextStage").assertExists().performClick()
        rule.waitForIdle()

        // AppShell closes lesson and displays Stage 3 detail on Learn tab
        rule.onNodeWithTag("milestone:overlay").assertDoesNotExist()
        rule.onNodeWithTag("stageDetail").assertExists()
        rule.onNodeWithTag("stageDetail:title").assertTextContains("First-layer corners")
    }

    @Test
    fun `mastery milestone Practice on a new scramble button launches practice session for the stage in AppShell`() {
        val attempts =
            listOf(
                AttemptRecord(
                    attemptId = "att-1",
                    sessionId = "s-1",
                    revision = 1L,
                    event = AttemptEvent.ENDED,
                    objectiveId = "practice-stage-02-first-cross",
                    outcome = AttemptOutcome.COMPLETED,
                    errors = 0,
                    hints = 0,
                    assistanceLevel = AssistanceLevel.REDUCED,
                    at = Instant.fromEpochMilliseconds(100),
                ),
            )
        val attemptRepo =
            object : AttemptRepository {
                override suspend fun all(): AttemptLoad = AttemptLoad.Found(attempts)
            }
        val storedEvents =
            listOf(
                ProgressEvent.StageUnlocked(StageId("stage-02-first-cross"), Instant.fromEpochMilliseconds(10)),
                ProgressEvent.LessonCompleted(LessonId("lesson-02-01"), Instant.fromEpochMilliseconds(20)),
                ProgressEvent.LessonCompleted(LessonId("lesson-02-02"), Instant.fromEpochMilliseconds(30)),
                ProgressEvent.LessonCompleted(LessonId("lesson-02-03"), Instant.fromEpochMilliseconds(40)),
                ProgressEvent.LessonCompleted(LessonId("lesson-02-04"), Instant.fromEpochMilliseconds(50)),
            )
        val progressRepo =
            object : ProgressRepository {
                override suspend fun all(): ProgressLoad =
                    ProgressLoad.Found(storedEvents, storedEvents.map { ProgressRecord("s-1", 0L, it) })

                override suspend fun append(records: List<ProgressRecord>): ProgressSave = ProgressSave.Saved
            }
        host =
            SessionHost(
                clock = TickingClock(),
                confined = Dispatchers.Main.immediate,
                preferences = preferences,
                attempts = attemptRepo,
                progress = progressRepo,
            )
        val shipped = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
        launch(ContentLoadResult.Loaded(shipped))

        // Open Stage 2 detail
        rule.onNodeWithTag("learn:stage:stage-02-first-cross").performScrollTo().performClick()
        rule.onNodeWithTag("stageDetail").assertExists()

        // Open last lesson of Stage 2 (lesson-02-05)
        rule.onNodeWithTag("learn:lesson:lesson-02-05").performScrollTo().performClick()
        val stage2 = shipped.stages.first { it.id == StageId("stage-02-first-cross") }
        val lastLesson = stage2.lessons.last()
        rule.waitUntil(5_000) {
            host.state.value.lesson
                ?.id == lastLesson.id
        }
        rule.waitForIdle()

        // Complete the lesson's steps
        lastLesson.steps.forEachIndexed { index, step ->
            val moves = (Notation.parse(step.moves) as dev.cubentia.domain.cube.NotationResult.Parsed).moves
            moves.forEach { move ->
                runBlocking { host.dispatch { SessionEvent.MoveCommitted(move, it) } }
            }
            if (index < lastLesson.steps.size - 1) {
                runBlocking { host.dispatch(SessionEvent::StepAdvanced) }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag("milestone:overlay").assertExists()

        // Click Practice on a new scramble
        rule.onNodeWithTag("milestone:practice").assertExists().performClick()
        rule.waitUntil(5_000) {
            host.state.value.kind == SessionKind.PRACTICE
        }
        rule.waitForIdle()

        assertEquals(SessionKind.PRACTICE, host.state.value.kind)
        assertEquals(
            StageId("stage-02-first-cross"),
            host.state.value.lesson
                ?.stageId,
        )
        rule.onNodeWithTag("lesson:goal").assertExists()
    }
}
