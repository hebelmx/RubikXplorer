package dev.cubentia.app.ui.shell

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import dev.cubentia.app.content.LessonCatalog
import dev.cubentia.app.data.SerialWriter
import dev.cubentia.app.preferences.PreferenceStore
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.app.session.testFlags
import dev.cubentia.app.ui.a11y.assertClickablesAtLeast48dp
import dev.cubentia.app.ui.a11y.assertClickablesLabelled
import dev.cubentia.app.ui.a11y.assertNoTextOverflow
import dev.cubentia.app.ui.a11y.fontScale200
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
import dev.cubentia.domain.content.CaseSet
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.FaceletString
import dev.cubentia.domain.content.FaceletStringResult
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
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

/** Story 14.1 (FR-4, AD-10, AD-18, UX Flow 8): the entry-level picker and the Learn *Find my place* sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class AppShellEntryLevelTest {
    @get:Rule val rule = createComposeRule()

    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
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
    private val events get() = records.map { it.event }
    private val repository =
        object : ProgressRepository {
            override suspend fun all(): ProgressLoad = ProgressLoad.Found(records.map { it.event }, records.toList())

            override suspend fun append(records: List<ProgressRecord>): ProgressSave {
                if (failWrite) return ProgressSave.Failed
                this@AppShellEntryLevelTest.records.addAll(records)
                return ProgressSave.Saved
            }
        }
    private val host = SessionHost(clock = TickingClock(), confined = Dispatchers.Main.immediate, writer = writer, progress = repository)

    @After
    fun closeWriter() {
        runBlocking { kotlinx.coroutines.withTimeout(10_000) { writer.close() } }
    }

    private fun show(
        welcome: Boolean = true,
        placement: Boolean = false,
        largeType: Boolean = false,
    ) {
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            val shell = @androidx.compose.runtime.Composable {
                CubentiaTheme {
                    AppShell(
                        host = host,
                        catalog = LessonCatalog.of(ContentLoadResult.Loaded(bundle)),
                        freeCube = FreeCubeViewModel(),
                        showDebugToggles = false,
                        flags = testFlags(placement = placement),
                        showWelcome = welcome,
                        preferenceStore = preferences,
                        placementFlow = flow,
                        onPlacementScan = { scanCount++ },
                    )
                }
            }
            if (largeType) fontScale200(shell) else shell()
        }
        waitForTag(if (welcome) "welcome" else "learn:findMyPlace")
    }

    private fun waitForTag(tag: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    }

    private fun waitGone(tag: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    }

    private fun openPickerFromWelcome() {
        rule.onNodeWithTag("welcome:canSolve").performClick()
        waitForTag("entryLevel")
    }

    private fun openSheet() {
        rule.onNodeWithTag("learn:findMyPlace").performClick()
        waitForTag("findMyPlace:chooseLevel")
    }

    private fun openPickerFromLearn() {
        openSheet()
        rule.onNodeWithTag("findMyPlace:chooseLevel").performClick()
        waitForTag("entryLevel")
        waitGone("findMyPlace:chooseLevel")
    }

    private fun assertStageSkipped(id: String) {
        rule
            .onNodeWithTag("learn:stage:status:$id")
            .performScrollTo()
            .assertTextContains("Skipped")
            .assertTextContains("◇")
    }

    private fun assertOnboarded() {
        runBlocking { writer.flush() }
        assertTrue(preferences.preferences.value.onboarded)
        assertTrue(savedPreferences.onboarded)
    }

    private fun entry(id: String): CubeState {
        val lesson = bundle.stages.flatMap { it.lessons }.single { it.id.value == id }
        val facelets = (FaceletString.decode(lesson.entryState) as FaceletStringResult.Decoded).facelets
        return (CubeValidator.validate(facelets) as ValidationResult.Valid).state
    }

    @Test
    fun `I can already solve it shows the title, four choices and the note, and no scan with PLACEMENT off`() {
        show()
        openPickerFromWelcome()

        rule.onNodeWithTag("entryLevel:title").assertTextContains("Where do you want to start?")
        rule.onNodeWithTag("entryLevel:firstTwoLayers").assertTextContains("I can solve the first two layers")
        rule.onNodeWithTag("entryLevel:beginner").assertTextContains("I solve with the beginner method")
        rule.onNodeWithTag("entryLevel:twoLook").assertTextContains("I know 2-look OLL and PLL")
        rule.onNodeWithTag("entryLevel:check").assertTextContains("Check me with a scramble instead")
        rule.onNodeWithTag("entryLevel:note").assertTextContains("Skipped Stages stay open, and nothing is marked mastered.")
        rule.onNodeWithTag("entryLevel:scan").assertDoesNotExist()
        rule.onNodeWithTag("lesson").assertDoesNotExist()
        assertTrue(records.isEmpty())
    }

    @Test
    fun `with PLACEMENT on the picker offers Not sure Scan my cube`() {
        show(placement = true)
        openPickerFromWelcome()
        rule.onNodeWithTag("entryLevel:scan").performScrollTo().assertTextContains("Not sure? Scan my cube")
    }

    @Test
    fun `Find my place offers only Choose my level with PLACEMENT off and it opens the same picker`() {
        show(welcome = false)
        openSheet()
        rule.onNodeWithTag("findMyPlace:title").assertTextContains("Find my place")
        rule.onNodeWithTag("findMyPlace:scan").assertDoesNotExist()
        rule.onNodeWithTag("findMyPlace:chooseLevel").assertTextContains("Choose my level").performClick()
        waitForTag("entryLevel")
        rule.onNodeWithTag("entryLevel:title").assertTextContains("Where do you want to start?")
        rule.onNodeWithTag("entryLevel:check").assertExists()
        assertTrue(records.isEmpty())
    }

    @Test
    fun `Find my place shows Scan my cube with PLACEMENT on`() {
        show(welcome = false, placement = true)
        openSheet()
        rule.onNodeWithTag("findMyPlace:scan").assertTextContains("Scan my cube")
        rule.onNodeWithTag("findMyPlace:chooseLevel").assertExists()
    }

    @Test
    fun `first two layers skips Stages 1 to 4, unlocks Last-layer orientation and lands on the Learn list`() {
        show()
        openPickerFromWelcome()
        rule.onNodeWithTag("entryLevel:firstTwoLayers").performClick()

        waitForTag("learn:topBar")
        rule.onNodeWithTag("entryLevel").assertDoesNotExist()
        rule.onNodeWithTag("stageDetail").assertDoesNotExist()
        bundle.stages.take(4).forEach { assertStageSkipped(it.id.value) }
        val stage5 = bundle.stages[4]
        assertEquals("stage-05-last-layer-orientation", stage5.id.value)
        assertEquals(StageStatus.IN_PROGRESS, StageProgress.status(stage5, bundle.stages, events))
        rule.onNodeWithTag("learn:stage:status:${stage5.id}").performScrollTo().assertTextContains("In progress")
        assertTrue(events.none { it is ProgressEvent.ProgressReset })
        assertOnboarded()
    }

    @Test
    @Config(qualifiers = "w411dp-h480dp")
    fun `beginner method skips Stages 1 to 7, shows Faster last layer and scrolls to it`() {
        show()
        openPickerFromWelcome()
        rule.onNodeWithTag("entryLevel:beginner").performClick()

        waitForTag("learn:fasterLastLayer")
        rule.waitForIdle()
        rule.onNodeWithTag("learn:fasterLastLayer").assertIsDisplayed()
        rule.onNodeWithTag("learn:stage:${bundle.stages.first().id}").assertIsNotDisplayed()
        bundle.stages.forEach { assertStageSkipped(it.id.value) }
        assertEquals(
            listOf(ProgressEvent.StageUnlocked(bundle.twoLookStages.first().id, events.last().at)),
            events.filterIsInstance<ProgressEvent.StageUnlocked>(),
        )
        assertOnboarded()
    }

    @Test
    fun `2-look skips the Course and both 2-look Stages and opens the Library on OLL with a removable chip`() {
        show()
        openPickerFromWelcome()
        rule.onNodeWithTag("entryLevel:twoLook").performClick()

        waitForTag("library:set:OLL")
        assertEquals(
            (bundle.stages + bundle.twoLookStages).map {
                it.id
            },
            events.filterIsInstance<ProgressEvent.StageSkippedByChoice>().map { it.stageId },
        )
        bundle.twoLookStages.forEach { assertEquals(StageStatus.SKIPPED, StageProgress.status(it, listOf(it), events)) }
        val oll = bundle.cases.filter { it.set == CaseSet.OLL }
        val pll = bundle.cases.first { it.set == CaseSet.PLL }
        rule.onNodeWithTag("library:grid").performScrollToKey(oll.last().id.value)
        assertTrue(runCatching { rule.onNodeWithTag("library:grid").performScrollToKey(pll.id.value) }.isFailure)
        assertOnboarded()

        rule.onNodeWithTag("library:set:OLL").performClick()
        rule.onNodeWithTag("library:set:OLL").assertDoesNotExist()
        rule.onNodeWithTag("library:grid").performScrollToKey(pll.id.value)
        rule.onNodeWithTag("library:case:${pll.id.value}").assertIsDisplayed()

        // The two 2-look Stage rows read Skipped on the Learn list.
        rule.onNodeWithTag("nav:learn").performClick()
        bundle.twoLookStages.forEach { assertStageSkipped(it.id.value) }
    }

    @Test
    fun `leaving the Library after 2-look returns to an unfiltered Library`() {
        show()
        openPickerFromWelcome()
        rule.onNodeWithTag("entryLevel:twoLook").performClick()
        waitForTag("library:set:OLL")

        rule.onNodeWithTag("nav:learn").performClick()
        rule.onNodeWithTag("nav:library").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("library:set:OLL").assertDoesNotExist()
        rule.onNodeWithTag("library:grid").performScrollToKey(
            bundle.cases
                .first { it.set == CaseSet.PLL }
                .id.value,
        )
    }

    @Test
    fun `giving up the check opened from Learn returns to the picker with no abandon line`() {
        show(welcome = false)
        openPickerFromLearn()
        rule.onNodeWithTag("entryLevel:check").performClick()
        waitForTag("lesson")
        rule.onNodeWithTag("lesson:skipCheckInstruction").assertTextContains("Solve this scramble your way. Hints are off.")

        rule.onNodeWithTag("lesson:close").performClick()
        waitForTag("entryLevel")
        rule.onNodeWithTag("welcome").assertDoesNotExist()
        rule.onNodeWithTag("welcome:abandoned").assertDoesNotExist()
        assertTrue(records.isEmpty())
    }

    @Test
    fun `giving up the check opened from Welcome returns to Welcome with Let's find where to start`() {
        show()
        openPickerFromWelcome()
        rule.onNodeWithTag("entryLevel:check").performClick()
        waitForTag("lesson")
        rule.onNodeWithTag("lesson:close").performClick()

        waitForTag("welcome")
        rule.onNodeWithTag("entryLevel").assertDoesNotExist()
        rule.onNodeWithTag("welcome:abandoned").assertTextContains("Let's find where to start")
    }

    @Test
    fun `Not sure Scan my cube runs Placement, closing returns to the picker, accepting starts the Stage`() {
        show(placement = true)
        openPickerFromWelcome()
        rule.onNodeWithTag("entryLevel:scan").performScrollTo().performClick()
        assertEquals(1, scanCount)
        rule.runOnIdle { flow.onScanOutcome(ScanOutcome.Accepted(entry("lesson-03-01"))) }
        waitForTag("placement:screen")
        rule.onNodeWithTag("placement:close").performClick()
        waitForTag("entryLevel")

        rule.onNodeWithTag("entryLevel:scan").performScrollTo().performClick()
        rule.runOnIdle { flow.onScanOutcome(ScanOutcome.Accepted(entry("lesson-03-01"))) }
        waitForTag("placement:screen")
        rule.onNodeWithTag("placement:start").performClick()
        waitForTag("stageDetail:title")
        rule.onNodeWithTag("stageDetail:title").assertTextContains("First-layer corners")
        rule.onNodeWithTag("entryLevel").assertDoesNotExist()
        assertOnboarded()
    }

    @Test
    fun `Scan my cube from the sheet runs Placement, closing returns to Learn, accepting starts the Stage`() {
        show(welcome = false, placement = true)
        openSheet()
        rule.onNodeWithTag("findMyPlace:scan").performClick()
        assertEquals(1, scanCount)
        waitGone("findMyPlace:scan")
        rule.runOnIdle { flow.onScanOutcome(ScanOutcome.Accepted(entry("lesson-03-01"))) }
        waitForTag("placement:screen")
        rule.onNodeWithTag("placement:close").performClick()
        waitForTag("learn:topBar")
        rule.onNodeWithTag("placement:screen").assertDoesNotExist()

        openSheet()
        rule.onNodeWithTag("findMyPlace:scan").performClick()
        rule.runOnIdle { flow.onScanOutcome(ScanOutcome.Accepted(entry("lesson-03-01"))) }
        waitForTag("placement:screen")
        rule.onNodeWithTag("placement:start").performClick()
        waitForTag("stageDetail:title")
        rule.onNodeWithTag("stageDetail:title").assertTextContains("First-layer corners")
    }

    @Test
    fun `Not sure Scan my cube on the picker opened from Learn runs Placement and closing returns to the picker`() {
        show(welcome = false, placement = true)
        openPickerFromLearn()
        rule.onNodeWithTag("entryLevel:scan").performScrollTo().performClick()
        assertEquals(1, scanCount)
        rule.runOnIdle { flow.onScanOutcome(ScanOutcome.Accepted(entry("lesson-03-01"))) }
        waitForTag("placement:screen")
        rule.onNodeWithTag("placement:close").performClick()
        waitForTag("entryLevel")
        rule.onNodeWithTag("placement:screen").assertDoesNotExist()
        assertTrue(records.isEmpty())
    }

    @Test
    fun `back from the picker returns to its caller and writes nothing`() {
        show()
        openPickerFromWelcome()
        rule.runOnIdle { back.onBackPressed() }
        waitForTag("welcome")
        rule.onNodeWithTag("entryLevel").assertDoesNotExist()
        rule.onNodeWithTag("welcome:abandoned").assertDoesNotExist()

        rule.onNodeWithTag("welcome:imNew").performClick()
        rule.onNodeWithTag("stageDetail:back").performClick()
        openPickerFromLearn()
        rule.runOnIdle { back.onBackPressed() }
        waitForTag("learn:topBar")
        rule.onNodeWithTag("entryLevel").assertDoesNotExist()

        openPickerFromLearn()
        rule.onNodeWithTag("entryLevel:back").performClick()
        waitForTag("learn:topBar")
        assertTrue(records.isEmpty())
    }

    @Test
    fun `a failed write keeps the picker and shows the save error`() {
        failWrite = true
        show()
        openPickerFromWelcome()
        rule.onNodeWithTag("entryLevel:firstTwoLayers").performClick()

        waitForTag("entryLevel:error")
        rule
            .onNodeWithTag("entryLevel:error")
            .performScrollTo()
            .assertTextContains("Could not save your progress. Try again.")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.onNodeWithTag("entryLevel").assertIsDisplayed()
        rule.onNodeWithTag("entryLevel:firstTwoLayers").assertIsEnabled()
        rule.onNodeWithTag("welcome").assertDoesNotExist()
        assertTrue(records.isEmpty())
        assertFalse(preferences.preferences.value.onboarded)
    }

    @Test
    @Config(qualifiers = "w600dp-h960dp")
    fun `every picker and sheet control is labelled, 48dp, and fits and scrolls at 200 percent on 600dp`() {
        show(placement = true, largeType = true)
        openPickerFromWelcome()
        rule.assertClickablesLabelled("Entry level picker")
        rule.assertClickablesAtLeast48dp("Entry level picker")
        rule.assertNoTextOverflow("Entry level picker")
        rule.onNodeWithTag("entryLevel:scan").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("entryLevel:back").performClick()

        rule.onNodeWithTag("welcome:imNew").performScrollTo().performClick()
        rule.onNodeWithTag("stageDetail:back").performClick()
        openSheet()
        rule.onNodeWithTag("findMyPlace:scan").assertIsDisplayed()
        rule.onNodeWithTag("findMyPlace:chooseLevel").assertIsDisplayed()
        rule.assertClickablesLabelled("Find my place")
        rule.assertClickablesAtLeast48dp("Find my place")
        rule.assertNoTextOverflow("Find my place")
    }
}
