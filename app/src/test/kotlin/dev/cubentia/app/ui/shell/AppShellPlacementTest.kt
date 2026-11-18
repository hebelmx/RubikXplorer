package dev.cubentia.app.ui.shell

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.cubentia.app.content.LessonCatalog
import dev.cubentia.app.data.SerialWriter
import dev.cubentia.app.preferences.PreferenceStore
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.app.session.testFlags
import dev.cubentia.app.ui.freecube.FreeCubeViewModel
import dev.cubentia.app.ui.placement.PlacementFlowViewModel
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.application.port.PreferenceSave
import dev.cubentia.application.port.PreferencesLoad
import dev.cubentia.application.port.PreferencesRepository
import dev.cubentia.application.port.ProgressLoad
import dev.cubentia.application.port.ProgressRecord
import dev.cubentia.application.port.ProgressRepository
import dev.cubentia.application.port.ProgressSave
import dev.cubentia.application.preference.Preferences
import dev.cubentia.domain.content.ContentLoadError
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.FaceletString
import dev.cubentia.domain.content.FaceletStringResult
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.tutor.ProgressEvent
import dev.cubentia.domain.tutor.StageProgress
import dev.cubentia.domain.tutor.StageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class AppShellPlacementTest {
    @get:Rule val rule = createComposeRule()

    private val shipped = ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded
    private val bundle = shipped.bundle
    private val writer = SerialWriter()
    private var savedPreferences = Preferences.DEFAULT
    private val preferences =
        PreferenceStore(
            object : PreferencesRepository {
                override suspend fun load(): PreferencesLoad = PreferencesLoad.Found(savedPreferences)

                override suspend fun save(preferences: Preferences): PreferenceSave {
                    savedPreferences = preferences
                    return PreferenceSave.Saved
                }

                override suspend fun reset(): PreferenceSave = save(Preferences.DEFAULT)
            },
            writer,
        )
    private val flow = PlacementFlowViewModel()
    private lateinit var back: OnBackPressedDispatcher
    private var scanCount = 0
    private var failWrite = false
    private val records = mutableListOf<ProgressRecord>()
    private val repository =
        object : ProgressRepository {
            override suspend fun all(): ProgressLoad = ProgressLoad.Found(records.map { it.event }, records.toList())

            override suspend fun append(records: List<ProgressRecord>): ProgressSave {
                if (failWrite) return ProgressSave.Failed
                this@AppShellPlacementTest.records.addAll(records)
                return ProgressSave.Saved
            }
        }
    private val host = SessionHost(clock = TickingClock(), confined = Dispatchers.Main.immediate, writer = writer, progress = repository)

    @After
    fun closeWriter() {
        runBlocking { writer.close() }
    }

    @Test
    fun `Welcome launches scan and accepting Stage 3 persists skips and opens its detail`() {
        show()
        startScan()
        assertEquals(1, scanCount)
        deliver(ScanOutcome.Accepted(entry("lesson-03-01")))
        rule.onNodeWithTag("placement:prompt").assertTextContains("Start at First-layer corners?")
        rule.onNodeWithTag("placement:chooseAnother").assertIsDisplayed()
        rule.onNodeWithTag("placement:start").performClick()

        assertDetail("First-layer corners")
        assertSkipped(2)
        assertEquals(StageStatus.IN_PROGRESS, StageProgress.status(bundle.stages[2], bundle.stages, host.progressEvents.value))
        assertTrue(records.any { it.event is ProgressEvent.StageUnlocked })
        assertOnboarded()
    }

    @Test
    fun `Faster last layer skips the whole Course and scrolls to the 2-look rows`() {
        show()
        startScan()
        deliver(ScanOutcome.Accepted(entry("lesson-05-01")))
        rule.onNodeWithTag("placement:fasterLastLayer").performClick()

        rule.onNodeWithTag("stageDetail").assertDoesNotExist()
        waitForTag("learn:fasterLastLayer")
        rule.onNodeWithTag("learn:fasterLastLayer").assertIsDisplayed()
        bundle.twoLookStages.forEach { rule.onNodeWithTag("learn:stage:${it.id}").assertIsDisplayed() }
        assertSkipped(7)
        val unlocked = records.mapNotNull { it.event as? ProgressEvent.StageUnlocked }
        assertEquals(listOf(bundle.twoLookStages.first().id), unlocked.map { it.stageId })
    }

    @Test
    fun `a non-white cross shows the white-cross guidance and still offers another Stage`() {
        show()
        startScan()
        deliver(ScanOutcome.Accepted(CubeState.SOLVED.apply(listOf(FaceTurn(Face.U, Turn.CLOCKWISE)))))
        rule.onNodeWithTag("placement:prompt").assertTextContains("Start with a white cross for this course.")
        rule.onNodeWithTag("placement:chooseAnother").assertIsDisplayed()
        rule.onNodeWithTag("placement:start").performClick()

        assertDetail("Cube fundamentals")
        assertTrue(records.isEmpty())
        assertOnboarded()
    }

    @Test
    fun `cancelling a rescan from the solved result returns to Welcome`() {
        show()
        startScan()
        deliver(ScanOutcome.Accepted(CubeState.SOLVED))
        rule.onNodeWithTag("placement:scanAgain").performClick()
        deliver(ScanOutcome.Cancelled)

        rule.onNodeWithTag("welcome").assertIsDisplayed()
        rule.onNodeWithTag("placement:prompt").assertDoesNotExist()
        assertTrue(records.isEmpty())
        assertFalse(preferences.preferences.value.onboarded)
    }

    @Test
    fun `choosing 2-look PLL skips the whole Course and opens PLL detail`() {
        show()
        startScan()
        deliver(ScanOutcome.Accepted(entry("lesson-03-01")))
        rule.onNodeWithTag("placement:chooseAnother").performClick()
        (bundle.stages + bundle.twoLookStages).forEachIndexed { index, stage ->
            rule
                .onNodeWithTag("placement:stage:${stage.id.value}")
                .assertTextContains("${index + 1}. ${bundle.strings.getValue(stage.title.value)}")
        }
        rule.onNodeWithTag("placement:stage:stage-09-two-look-pll").performScrollTo().performClick()

        assertDetail("2-look PLL")
        assertSkipped(7)
        val unlocked = records.mapNotNull { it.event as? ProgressEvent.StageUnlocked }
        assertEquals(listOf(bundle.twoLookStages.last().id), unlocked.map { it.stageId })
    }

    @Test
    fun `solved cube can rescan and then start Stage 1 without writing progress`() {
        show()
        startScan()
        deliver(ScanOutcome.Accepted(CubeState.SOLVED))
        rule.onNodeWithTag("placement:chooseAnother").assertDoesNotExist()
        rule.onNodeWithTag("placement:scanAgain").performClick()
        assertEquals(2, scanCount)
        deliver(ScanOutcome.Accepted(CubeState.SOLVED))
        rule.onNodeWithTag("placement:startStage1").performClick()

        assertDetail("Cube fundamentals")
        assertTrue(records.isEmpty())
        assertOnboarded()
    }

    @Test
    fun `fallback opens the manual editor and evaluates its accepted cube`() {
        show()
        startScan()
        deliver(ScanOutcome.FellBackToEditor)
        rule
            .onNodeWithTag("placement:editorNote")
            .assertTextContains(bundle.strings.getValue("msg.solve.scan-fell-back"))
        rule.onNodeWithTag("editor:accept").performScrollTo().performClick()
        rule.onNodeWithTag("placement:prompt").assertTextContains("Your cube is solved — scramble it and scan again, or start Stage 1.")
        assertTrue(records.isEmpty())
        assertFalse(preferences.preferences.value.onboarded)
    }

    @Test
    fun `camera denial opens the manual editor with the denial note`() {
        show()
        startScan()
        rule.runOnIdle { flow.scanDenied() }

        rule
            .onNodeWithTag("placement:editorNote")
            .assertTextContains(bundle.strings.getValue("msg.solve.scan-camera-denied"))
        rule.onNodeWithTag("editor:accept").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `cancelled scan returns to Welcome without progress`() {
        show()
        startScan()
        deliver(ScanOutcome.Cancelled)

        rule.onNodeWithTag("welcome").assertIsDisplayed()
        assertTrue(records.isEmpty())
        assertFalse(preferences.preferences.value.onboarded)
    }

    @Test
    fun `failed write still onboards with the actual progress`() {
        failWrite = true
        show()
        startScan()
        deliver(ScanOutcome.Accepted(entry("lesson-03-01")))
        rule.onNodeWithTag("placement:start").performClick()

        assertDetail("First-layer corners")
        assertTrue(records.isEmpty())
        assertEquals(StageStatus.LOCKED, StageProgress.status(bundle.stages[2], bundle.stages, host.progressEvents.value))
        assertOnboarded()
    }

    @Test
    fun `flag OFF hides the action and keeps the beginner welcome path`() {
        show(enabled = false)
        rule.onNodeWithTag("welcome:startFromCube").assertDoesNotExist()
        rule.onNodeWithTag("welcome:imNew").performClick()
        assertDetail("Cube fundamentals")
        assertOnboarded()
    }

    @Test
    fun `GA with no scan action hides the entry point`() {
        show(hasScan = false)
        rule.onNodeWithTag("welcome:startFromCube").assertDoesNotExist()
        rule.onNodeWithTag("welcome:imNew").assertIsDisplayed()
    }

    @Test
    fun `a failed catalog hides the entry point even with Placement on and a scan action`() {
        show(failedCatalog = true)
        rule.onNodeWithTag("welcome:startFromCube").assertDoesNotExist()
        rule.onNodeWithTag("welcome:imNew").assertIsDisplayed()
        assertEquals(0, scanCount)
    }

    @Test
    fun `system Back steps from the chooser to the suggestion, then to Welcome, without writing`() {
        show()
        startScan()
        deliver(ScanOutcome.Accepted(entry("lesson-03-01")))
        rule.onNodeWithTag("placement:chooseAnother").performClick()
        rule.onNodeWithTag("placement:stage:stage-01-fundamentals").assertExists()

        rule.runOnIdle { back.onBackPressed() }
        rule.onNodeWithTag("placement:prompt").assertTextContains("Start at First-layer corners?")
        rule.onNodeWithTag("placement:stage:stage-01-fundamentals").assertDoesNotExist()

        rule.runOnIdle { back.onBackPressed() }
        rule.onNodeWithTag("welcome").assertIsDisplayed()
        rule.onNodeWithTag("placement:prompt").assertDoesNotExist()

        startScan()
        deliver(ScanOutcome.FellBackToEditor)
        rule.onNodeWithTag("placement:editorNote").assertExists()
        rule.runOnIdle { back.onBackPressed() }
        rule.onNodeWithTag("welcome").assertIsDisplayed()

        assertTrue(records.isEmpty())
        assertFalse(preferences.preferences.value.onboarded)
    }

    private fun show(
        enabled: Boolean = true,
        hasScan: Boolean = true,
        failedCatalog: Boolean = false,
    ) {
        val catalog =
            if (failedCatalog) {
                LessonCatalog.of(ContentLoadResult.Failed(ContentLoadError.MissingResource("missing.json")))
            } else {
                LessonCatalog.of(shipped)
            }
        val freeCube = FreeCubeViewModel()
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CubentiaTheme {
                AppShell(
                    host = host,
                    catalog = catalog,
                    freeCube = freeCube,
                    showDebugToggles = false,
                    flags = testFlags(placement = enabled),
                    showWelcome = true,
                    preferenceStore = preferences,
                    placementFlow = flow,
                    onPlacementScan = if (hasScan) ({ scanCount++ }) else null,
                )
            }
        }
        waitForTag("welcome")
    }

    private fun startScan() {
        rule.onNodeWithTag("welcome:startFromCube").performClick()
    }

    private fun deliver(outcome: ScanOutcome) {
        rule.runOnIdle { flow.onScanOutcome(outcome) }
    }

    private fun entry(id: String): CubeState {
        val lesson = bundle.stages.flatMap { it.lessons }.single { it.id.value == id }
        val facelets = (FaceletString.decode(lesson.entryState) as FaceletStringResult.Decoded).facelets
        return (CubeValidator.validate(facelets) as ValidationResult.Valid).state
    }

    private fun assertDetail(title: String) {
        waitForTag("stageDetail:title")
        rule.onNodeWithTag("stageDetail:title").assertTextContains(title)
    }

    private fun waitForTag(tag: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    }

    private fun assertOnboarded() {
        runBlocking { writer.flush() }
        assertTrue(preferences.preferences.value.onboarded)
        assertTrue(savedPreferences.onboarded)
    }

    private fun assertSkipped(count: Int) {
        val stages = bundle.stages
        val events = records.map { it.event }
        assertEquals(stages.take(count).map { it.id }, events.filterIsInstance<ProgressEvent.StageSkippedByPlacement>().map { it.stageId })
        stages.take(count).forEach { assertEquals(StageStatus.SKIPPED_BY_PLACEMENT, StageProgress.status(it, stages, events)) }
    }
}
