package dev.cubentia.app.ui.shell

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import dev.cubentia.app.ui.placement.PlacementPhase
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.app.ui.theme.DarkTokens
import dev.cubentia.app.ui.theme.LightTokens
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
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.lastlayer.CaseStateResult
import dev.cubentia.domain.lastlayer.LastLayerMatcher
import dev.cubentia.domain.tutor.ProgressEvent
import dev.cubentia.domain.tutor.StageProgress
import dev.cubentia.domain.tutor.StageStatus
import kotlinx.coroutines.CompletableDeferred
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import dev.cubentia.domain.cube.Color as DomainColor

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
    private var view: android.view.View? = null
    private var scanCount = 0
    private var failWrite = false
    private var pendingWrite: CompletableDeferred<ProgressSave>? = null

    /** Holds an append until completed, then saves it; [appends] counts every append call. */
    private var gatedWrite: CompletableDeferred<Unit>? = null

    @Volatile private var appends = 0
    private val records = mutableListOf<ProgressRecord>()
    private val repository =
        object : ProgressRepository {
            override suspend fun all(): ProgressLoad = ProgressLoad.Found(records.map { it.event }, records.toList())

            override suspend fun append(records: List<ProgressRecord>): ProgressSave {
                appends++
                gatedWrite?.await()
                pendingWrite?.let { return it.await() }
                if (failWrite) return ProgressSave.Failed
                this@AppShellPlacementTest.records.addAll(records)
                return ProgressSave.Saved
            }
        }
    private val host = SessionHost(clock = TickingClock(), confined = Dispatchers.Main.immediate, writer = writer, progress = repository)

    @After
    fun closeWriter() {
        // Bounded: a write left waiting on the main thread must fail the test, not hang it.
        runBlocking { kotlinx.coroutines.withTimeout(10_000) { writer.close() } }
    }

    @Test
    fun `Welcome launches scan and accepting Stage 3 persists skips and opens its detail`() {
        show()
        startScan()
        assertEquals(1, scanCount)
        deliver(ScanOutcome.Accepted(entry("lesson-03-01")))
        rule.onNodeWithTag("placement:prompt").assertTextContains("Start at First-layer corners?")
        rule.onNodeWithTag("placement:chooseAnother").assertIsDisplayed()
        ShadowLog.clear()
        rule.onNodeWithTag("placement:start").performClick()

        assertDetail("First-layer corners")
        // Debug builds log the saved choice for evaluateMatrix (Story 10.8); release diagnostics are a no-op.
        if (dev.cubentia.app.BuildConfig.DEBUG) {
            val run = ShadowLog.getLogsForTag("CubentiaScanDiag").map { it.msg }.single { it.startsWith("placement run ") }
            assertTrue(run, run.contains(" suggested=${bundle.stages[2].id.value} accepted=${bundle.stages[2].id.value} "))
        }
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
    fun `a partly solved cube reads, reasons and suggests in order, then notes the set-up`() {
        show()
        startScan()
        deliver(ScanOutcome.Accepted(entry("lesson-03-01")))

        assertPrompt(
            "Your white cross is done, and 3 of 4 first-layer corners are in place.",
            "First-layer corners picks up from here.",
            "Start at First-layer corners?",
        )
        rule.onNodeWithTag("placement:reading", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("placement:reason", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("placement:suggestion", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("placement:setup").assertTextEquals("The lesson sets up its own cube, so you can put yours aside.")
        val prompt = rule.onNodeWithTag("placement:prompt").fetchSemanticsNode().config
        assertEquals(LiveRegionMode.Polite, prompt[SemanticsProperties.LiveRegion])
    }

    @Test
    fun `a cube one turn from solved names the turn and offers no Start`() {
        show()
        startScan()
        deliver(ScanOutcome.Accepted(CubeState.SOLVED.apply(FaceTurn(Face.U, Turn.COUNTERCLOCKWISE))))

        assertPrompt(
            "Your cube is one turn from solved: turn the white face clockwise.",
            "Finish it, then scramble it and scan again, or pick a Stage.",
        )
        rule.onNodeWithTag("placement:scanAgain").assertIsDisplayed()
        rule.onNodeWithTag("placement:chooseAnother").assertIsDisplayed()
        rule.onNodeWithTag("placement:start").assertDoesNotExist()
        rule.onNodeWithTag("placement:setup").assertIsDisplayed()
    }

    @Test
    fun `two turns and a half turn are named in words, in the order to make them`() {
        show()
        startScan()
        deliver(ScanOutcome.Accepted(state("R U")))
        rule
            .onNodeWithTag("placement:reading", useUnmergedTree = true)
            .assertTextEquals(
                "Your cube is two turns from solved: turn the white face counter-clockwise, then turn the red face counter-clockwise.",
            )
        rule.onNodeWithTag("placement:close").performClick()
        waitForTag("welcome")

        startScan()
        deliver(ScanOutcome.Accepted(state("D2")))
        rule
            .onNodeWithTag("placement:reading", useUnmergedTree = true)
            .assertTextEquals("Your cube is one turn from solved: turn the yellow face a half turn.")
    }

    @Test
    fun `a cube entered in the Editor gets its Case named`() {
        val primary = bundle.algorithms.single { it.isPrimary && it.caseId.value == "case-oll-01" }
        // Held in the home frame, as the editor shows it: white on U, the OLL 1 last layer on D.
        val case = (LastLayerMatcher.caseState(primary, DomainColor.YELLOW) as CaseStateResult.Derived).state.reoriented(Orientation.HOME)
        val target = case.toFacelets().colors
        val solved = CubeState.SOLVED.toFacelets().colors
        show()
        startScan()
        deliver(ScanOutcome.FellBackToEditor)
        with(dev.cubentia.app.ui.editor.EditorFixtures) { rule.enterEveryFace() }
        repeat(6) { face ->
            if (rule.onAllNodesWithTag("editor:face:$face").fetchSemanticsNodes().isNotEmpty()) {
                rule.onNodeWithTag("editor:face:$face").performScrollTo().performClick()
                rule.waitForIdle()
            }
            (face * 9 until face * 9 + 9).filter { target[it] != solved[it] }.forEach { index ->
                rule.onNodeWithTag("sticker:$index").performScrollTo().performClick()
                rule.onNodeWithTag("swatch:${target[index].name}").performScrollTo().performClick()
                rule.waitForIdle()
            }
        }
        rule.onNodeWithTag("editor:accept").performScrollTo().performClick()
        rule
            .onNodeWithTag("placement:reading", useUnmergedTree = true)
            .assertTextContains("It looks like OLL 1.", substring = true)
    }

    @Test
    fun `a yellow cross credits the solve and starts at First cross`() {
        show()
        startScan()
        deliver(ScanOutcome.Accepted(state("R U R'")))
        assertPrompt(
            "Your yellow cross is solved, nice work.",
            NON_WHITE_REASON,
            "Start at First cross?",
        )
        rule.onNodeWithTag("placement:lastLayerAnyway").assertDoesNotExist()
        rule.onNodeWithTag("placement:start").performClick()

        assertDetail("First cross")
        assertSkipped(1)
        assertOnboarded()
    }

    @Test
    fun `first two layers from yellow start at First cross`() {
        show()
        startScan()
        deliver(ScanOutcome.Accepted(state(YELLOW_F2L)))
        assertPrompt(
            "Your first two layers are solved, nice work. You started from yellow.",
            NON_WHITE_REASON,
            "Start at First cross?",
        )
        rule.onNodeWithTag("placement:chooseAnother").assertIsDisplayed()
        rule.onNodeWithTag("placement:start").performClick()

        assertDetail("First cross")
        assertSkipped(1)
        assertEquals(StageStatus.IN_PROGRESS, StageProgress.status(bundle.stages[1], bundle.stages, host.progressEvents.value))
    }

    @Test
    fun `first two layers from yellow can show the last layer anyway`() {
        show()
        startScan()
        deliver(ScanOutcome.Accepted(state(YELLOW_F2L)))
        rule.onNodeWithTag("placement:lastLayerAnyway").assertTextContains("Show me the last layer anyway").performClick()

        assertDetail("Last-layer orientation")
        assertSkipped(4)
    }

    @Test
    fun `an oriented last layer reads as oriented and offers the faster last layer`() {
        show()
        startScan()
        deliver(ScanOutcome.Accepted(entry("lesson-06-02")))
        rule
            .onNodeWithTag("placement:reading", useUnmergedTree = true)
            .assertTextContains(
                "Your first two layers are solved. Only the last layer is left: the top is oriented; the pieces need moving into place.",
                substring = true,
            )
        rule
            .onNodeWithTag("placement:reason", useUnmergedTree = true)
            .assertTextEquals("Last-layer completion (or Faster last layer) covers this.")
        rule.onNodeWithTag("placement:suggestion", useUnmergedTree = true).assertTextEquals("Start at Last-layer completion?")
        rule.onNodeWithTag("placement:fasterLastLayer").assertIsDisplayed()
        rule.onNodeWithTag("placement:chooseAnother").assertIsDisplayed()
    }

    @Test
    fun `a shipped OLL Case is named in the last-layer reading`() {
        val primary = bundle.algorithms.single { it.isPrimary && it.caseId.value == "case-oll-01" }
        val case = (LastLayerMatcher.caseState(primary, DomainColor.YELLOW) as CaseStateResult.Derived).state
        show()
        startScan()
        deliver(ScanOutcome.Accepted(case))
        assertPrompt(
            "Your first two layers are solved. Only the last layer is left: the top needs orienting. It looks like OLL 1.",
            "Last-layer orientation (or Faster last layer) covers this.",
            "Start at Last-layer orientation?",
        )
    }

    @Test
    fun `every Placement result text comes from content strings`() {
        // Every content string is replaced by its key in brackets, so any other text was hard-coded or came from strings.xml.
        val keyed = shipped.copy(bundle = bundle.copy(strings = bundle.strings.mapValues { (key, _) -> "[[$key]]" }))
        show(catalogResult = keyed)
        val cubes =
            listOf(
                CubeState.SOLVED,
                state("U'"),
                state("R U"),
                state(SCRAMBLE),
                state("R U R'"),
                state(YELLOW_F2L),
                entry("lesson-03-01"),
                entry("lesson-04-01"),
                entry("lesson-05-01"),
                entry("lesson-06-02"),
            )
        val keyedLine = Regex("""\[\[[a-z0-9.-]+]]( \[\[[a-z0-9.-]+]])*""")
        for (cube in cubes) {
            startScan()
            deliver(ScanOutcome.Accepted(cube))
            rule.onNodeWithTag("placement:setup").assertIsDisplayed()
            val texts =
                rule
                    .onAllNodes(hasAnyAncestor(hasTestTag("placement:screen")) and hasTextProperty(), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }
            assertTrue(texts.isNotEmpty())
            texts.forEach { assertTrue("hard-coded Placement text: $it", keyedLine.matches(it)) }
            rule.onNodeWithTag("placement:close").performClick()
            waitForTag("welcome")
        }
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
    fun `a solved cube offers Scan again, Start Stage 1 and I can already solve it in that order`() {
        show()
        startScan()
        deliver(ScanOutcome.Accepted(CubeState.SOLVED))

        assertPrompt("Your cube is solved.", "Scramble it and scan again, or start Stage 1.")
        val tops =
            listOf("placement:scanAgain", "placement:startStage1", "placement:canAlreadySolve").map { tag ->
                rule
                    .onNodeWithTag(tag)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            }
        assertEquals(tops.sorted(), tops)
        assertEquals(3, tops.distinct().size)
        rule.onNodeWithTag("placement:canAlreadySolve").assertTextEquals(bundle.strings.getValue("btn.placement.can-already-solve"))
        rule.onNodeWithTag("placement:canAlreadySolve").assertTextEquals("I can already solve it")
    }

    @Test
    fun `I can already solve it opens the entry-level picker and writes nothing`() {
        show()
        startScan()
        deliver(ScanOutcome.Accepted(CubeState.SOLVED))
        rule.onNodeWithTag("placement:canAlreadySolve").performClick()

        waitForTag("entryLevel")
        rule.onNodeWithTag("placement:screen").assertDoesNotExist()
        assertTrue(flow.phase.value is PlacementPhase.Result)
        runBlocking { writer.flush() }
        assertTrue(records.isEmpty())
        assertFalse(preferences.preferences.value.onboarded)
        assertFalse(savedPreferences.onboarded)
    }

    @Test
    fun `top-bar and system Back on the picker return to the solved outcome without writing`() {
        show()
        startScan()
        deliver(ScanOutcome.Accepted(CubeState.SOLVED))

        rule.onNodeWithTag("placement:canAlreadySolve").performClick()
        waitForTag("entryLevel")
        rule.onNodeWithTag("entryLevel:back").performClick()
        waitForTag("placement:prompt")
        rule.onNodeWithTag("entryLevel").assertDoesNotExist()
        assertPrompt("Your cube is solved.", "Scramble it and scan again, or start Stage 1.")
        rule.onNodeWithTag("placement:canAlreadySolve").assertIsDisplayed()

        rule.onNodeWithTag("placement:canAlreadySolve").performClick()
        waitForTag("entryLevel")
        rule.runOnIdle { back.onBackPressed() }
        waitForTag("placement:prompt")
        rule.onNodeWithTag("entryLevel").assertDoesNotExist()
        assertPrompt("Your cube is solved.", "Scramble it and scan again, or start Stage 1.")

        runBlocking { writer.flush() }
        assertTrue(records.isEmpty())
        assertFalse(preferences.preferences.value.onboarded)
        assertFalse(savedPreferences.onboarded)
    }

    @Test
    fun `choosing first two layers from the solved outcome's picker skips Stages 1 to 4 and lands on Learn`() {
        show()
        startScan()
        deliver(ScanOutcome.Accepted(CubeState.SOLVED))
        rule.onNodeWithTag("placement:canAlreadySolve").performClick()
        waitForTag("entryLevel")
        rule.onNodeWithTag("entryLevel:firstTwoLayers").performClick()

        waitForTag("learn:topBar")
        rule.onNodeWithTag("entryLevel").assertDoesNotExist()
        rule.onNodeWithTag("placement:screen").assertDoesNotExist()
        rule.onNodeWithTag("stageDetail").assertDoesNotExist()
        val events = records.map { it.event }
        assertEquals(
            bundle.stages.take(4).map { it.id },
            events.filterIsInstance<ProgressEvent.StageSkippedByChoice>().map { it.stageId },
        )
        assertTrue(events.none { it is ProgressEvent.StageSkippedByPlacement })
        bundle.stages.take(4).forEach { assertEquals(StageStatus.SKIPPED, StageProgress.status(it, bundle.stages, events)) }
        assertEquals(StageStatus.IN_PROGRESS, StageProgress.status(bundle.stages[4], bundle.stages, events))
        assertEquals(PlacementPhase.Idle, flow.phase.value)
        assertOnboarded()
    }

    @Test
    fun `a failed level write from the solved outcome's picker keeps the picker with its error`() {
        failWrite = true
        show()
        startScan()
        deliver(ScanOutcome.Accepted(CubeState.SOLVED))
        rule.onNodeWithTag("placement:canAlreadySolve").performClick()
        waitForTag("entryLevel")
        rule.onNodeWithTag("entryLevel:firstTwoLayers").performClick()

        waitForTag("entryLevel:error")
        rule.onNodeWithTag("entryLevel").assertIsDisplayed()
        rule.onNodeWithTag("placement:screen").assertDoesNotExist()
        assertTrue(records.isEmpty())
        runBlocking { writer.flush() }
        assertFalse(preferences.preferences.value.onboarded)
        assertFalse(savedPreferences.onboarded)
    }

    @Test
    fun `Not sure Scan my cube on the solved outcome's picker starts a new scan whose result shows`() {
        show()
        startScan()
        deliver(ScanOutcome.Accepted(CubeState.SOLVED))
        rule.onNodeWithTag("placement:canAlreadySolve").performClick()
        waitForTag("entryLevel")
        rule.onNodeWithTag("entryLevel:scan").performScrollTo().performClick()
        assertEquals(2, scanCount)
        deliver(ScanOutcome.Accepted(entry("lesson-03-01")))

        waitForTag("placement:prompt")
        rule.onNodeWithTag("entryLevel").assertDoesNotExist()
        rule.onNodeWithTag("placement:prompt").assertTextContains("Start at First-layer corners?")
        assertTrue(records.isEmpty())
    }

    @Test
    @Config(qualifiers = "w600dp-h960dp")
    fun `the solved outcome's controls are labelled, 48dp, and fit at 200 percent on 600dp`() {
        show(largeType = true)
        startScan()
        deliver(ScanOutcome.Accepted(CubeState.SOLVED))
        rule.onNodeWithTag("placement:canAlreadySolve").performScrollTo().assertIsDisplayed()

        rule.assertClickablesLabelled("Placement solved outcome")
        rule.assertClickablesAtLeast48dp("Placement solved outcome")
        rule.assertNoTextOverflow("Placement solved outcome")
    }

    @Test
    fun `fallback opens the manual editor and evaluates its accepted cube`() {
        show()
        startScan()
        deliver(ScanOutcome.FellBackToEditor)
        rule
            .onNodeWithTag("placement:editorNote")
            .assertTextContains(bundle.strings.getValue("msg.solve.scan-fell-back"))
        with(dev.cubentia.app.ui.editor.EditorFixtures) {
            rule.assertEditorGated()
            rule.enterEveryFace()
        }
        rule.onNodeWithTag("editor:accept").performScrollTo().performClick()
        assertPrompt("Your cube is solved.", "Scramble it and scan again, or start Stage 1.")
        rule.onNodeWithTag("placement:setup").assertIsDisplayed()
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
    fun `failed write keeps the placement result and does not onboard`() {
        failWrite = true
        show()
        startScan()
        deliver(ScanOutcome.Accepted(entry("lesson-03-01")))
        rule.onNodeWithTag("placement:start").performClick()

        val failure = bundle.strings.getValue("msg.placement.save-failed")
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(failure)).fetchSemanticsNodes().size == 1 }
        rule.onNodeWithText(failure).assertIsDisplayed()
        rule.onNodeWithTag("placement:prompt").assertTextContains("Start at First-layer corners?")
        rule.onNodeWithTag("stageDetail").assertDoesNotExist()
        assertTrue(records.isEmpty())
        assertEquals(StageStatus.LOCKED, StageProgress.status(bundle.stages[2], bundle.stages, host.progressEvents.value))
        runBlocking { writer.flush() }
        assertFalse(preferences.preferences.value.onboarded)
        assertFalse(savedPreferences.onboarded)

        // The failure belongs to the phase it was shown in.
        rule.onNodeWithTag("placement:chooseAnother").performClick()
        rule.onNodeWithText(failure).assertDoesNotExist()
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

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `dark placement paints the themed surface in all phases and uses primary ink for the prompt`() {
        show(dark = true)
        startScan()
        deliver(ScanOutcome.Accepted(entry("lesson-03-01")))

        assertPlacementSurface(DarkTokens.surfaceBase)
        val prompt = rule.onNodeWithTag("placement:prompt").captureToImage().toPixelMap()
        assertTrue(
            "Expected primary ink in the rendered prompt",
            (0 until prompt.height).any { y -> (0 until prompt.width).any { x -> prompt[x, y] == DarkTokens.inkPrimary } },
        )

        rule.onNodeWithTag("placement:chooseAnother").performClick()
        rule.onNodeWithTag("placement:stage:stage-01-fundamentals").assertIsDisplayed()
        assertPlacementSurface(DarkTokens.surfaceBase)

        rule.onNodeWithTag("placement:close").performClick()
        rule.onNodeWithTag("placement:close").performClick()
        startScan()
        deliver(ScanOutcome.FellBackToEditor)
        rule.onNodeWithTag("placement:editorNote").assertIsDisplayed()
        assertPlacementSurface(DarkTokens.surfaceBase)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `light placement result paints the themed surface`() {
        show()
        startScan()
        deliver(ScanOutcome.Accepted(entry("lesson-03-01")))

        assertPlacementSurface(LightTokens.surfaceBase)
    }

    @Test
    fun `Close steps from Choosing to Result and exits Result and Editing without writing`() {
        show()
        startScan()
        deliver(ScanOutcome.Accepted(entry("lesson-03-01")))
        rule.onNodeWithContentDescription(bundle.strings.getValue("btn.solve.close")).assertIsDisplayed()
        rule.onNodeWithTag("placement:close").assertIsDisplayed()

        rule.onNodeWithTag("placement:chooseAnother").performClick()
        rule.onNodeWithTag("placement:close").assertIsDisplayed().performClick()
        rule.onNodeWithTag("placement:prompt").assertTextContains("Start at First-layer corners?")
        assertTrue(records.isEmpty())

        rule.onNodeWithTag("placement:close").assertIsDisplayed().performClick()
        rule.onNodeWithTag("welcome").assertIsDisplayed()

        startScan()
        deliver(ScanOutcome.FellBackToEditor)
        rule.onNodeWithTag("placement:editorNote").assertIsDisplayed()
        rule.onNodeWithTag("placement:close").assertIsDisplayed().performClick()
        rule.onNodeWithTag("welcome").assertIsDisplayed()

        runBlocking { writer.flush() }
        assertTrue(records.isEmpty())
        assertFalse(preferences.preferences.value.onboarded)
        assertFalse(savedPreferences.onboarded)
    }

    @Test
    fun `Close and system Back cannot dismiss placement while a save is pending`() {
        val save = CompletableDeferred<ProgressSave>()
        pendingWrite = save
        try {
            show()
            startScan()
            deliver(ScanOutcome.Accepted(entry("lesson-03-01")))
            rule.onNodeWithTag("placement:start").performClick()

            rule.onNodeWithTag("placement:close").assertIsDisplayed().assertIsNotEnabled()
            rule.runOnIdle { back.onBackPressed() }
            rule.onNodeWithTag("placement:prompt").assertIsDisplayed()
            rule.onNodeWithTag("welcome").assertDoesNotExist()
        } finally {
            save.complete(ProgressSave.Failed)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `dark placement paints behind the status bar and keeps the 56dp top bar below it`() {
        show(dark = true)
        val top = 96
        rule.runOnIdle {
            val insets =
                androidx.core.view.WindowInsetsCompat
                    .Builder()
                    .setInsets(
                        androidx.core.view.WindowInsetsCompat.Type
                            .statusBars(),
                        androidx.core.graphics.Insets
                            .of(0, top, 0, 0),
                    ).build()
            androidx.core.view.ViewCompat
                .dispatchApplyWindowInsets(view!!, insets)
        }
        rule.waitForIdle()
        startScan()
        deliver(ScanOutcome.Accepted(entry("lesson-03-01")))

        val pixels = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(DarkTokens.surfaceBase, pixels[pixels.width / 2, top / 2])
        val bar = rule.onNodeWithTag("placement:topBar").fetchSemanticsNode()
        assertTrue("top bar top ${bar.boundsInRoot.top} under the $top px status bar", bar.boundsInRoot.top >= top)
        assertTrue("top bar is at least 56dp", bar.boundsInRoot.height >= 56 * bar.layoutInfo.density.density - 0.5f)
        val close = rule.onNodeWithTag("placement:close").fetchSemanticsNode().boundsInRoot
        assertTrue(close.top >= top)
    }

    @Test
    fun `recreating the shell while the accept write is in flight still opens the accepted Stage and onboards`() {
        val gate = CompletableDeferred<Unit>()
        gatedWrite = gate
        val restoration = StateRestorationTester(rule)
        show(restoration = restoration)
        startScan()
        deliver(ScanOutcome.Accepted(entry("lesson-03-01")))
        rule.onNodeWithTag("placement:start").performClick()
        rule.waitUntil(5_000) { appends == 1 }

        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        // The write is still saving in the new composition: a second accept is inert.
        rule.onNodeWithTag("placement:close").assertIsNotEnabled()
        rule.onNodeWithTag("placement:start").performClick()
        gate.complete(Unit)

        assertDetail("First-layer corners")
        assertSkipped(2)
        assertOnboarded()
        assertEquals(1, appends)
    }

    @Test
    fun `a cold start with Placement skips saved but not onboarded opens Learn, not Welcome`() {
        records +=
            bundle.stages.take(2).mapIndexed { index, stage ->
                ProgressRecord("placement", index.toLong(), ProgressEvent.StageSkippedByPlacement(stage.id))
            }
        show(awaitWelcome = false)
        waitForTag("learn")
        rule.onNodeWithTag("welcome").assertDoesNotExist()
        assertFalse(preferences.preferences.value.onboarded)
    }

    @Test
    fun `a late scan outcome after I'm new leaves Placement idle and hidden`() {
        show()
        startScan()
        rule.onNodeWithTag("welcome:imNew").performClick()
        assertDetail("Cube fundamentals")
        for (late in listOf(ScanOutcome.Accepted(entry("lesson-03-01")), ScanOutcome.FellBackToEditor)) {
            deliver(late)
            rule.waitForIdle()
            assertEquals(PlacementPhase.Idle, flow.phase.value)
            assertEquals(null, flow.acceptedScan.value)
            rule.onNodeWithTag("placement:screen").assertDoesNotExist()
        }
        rule.runOnIdle { flow.scanDenied() }
        rule.waitForIdle()
        assertEquals(PlacementPhase.Idle, flow.phase.value)
        rule.onNodeWithTag("placement:screen").assertDoesNotExist()
        rule.onNodeWithTag("stageDetail:title").assertTextContains("Cube fundamentals")
    }

    @Test
    fun `a late scan outcome during the Course skip check does not show Placement`() {
        show()
        startScan()
        rule.onNodeWithTag("welcome:canSolve").performClick()
        rule.onNodeWithTag("entryLevel:check").performClick()
        waitForTag("lesson")
        deliver(ScanOutcome.Accepted(entry("lesson-03-01")))
        rule.waitForIdle()
        rule.onNodeWithTag("placement:screen").assertDoesNotExist()
        rule.onNodeWithTag("lesson").assertExists()
        assertEquals(PlacementPhase.Idle, flow.phase.value)
        assertEquals(null, flow.acceptedScan.value)
    }

    @Test
    fun `resetting progress with a stale Placement phase shows Welcome`() {
        show()
        rule.onNodeWithTag("welcome:imNew").performClick()
        assertDetail("Cube fundamentals")
        rule.onNodeWithTag("nav:you").performClick()
        rule.onNodeWithTag("you:settings").performClick()
        // Late outcomes land while Settings covers the shell.
        deliver(ScanOutcome.Accepted(entry("lesson-03-01")))
        deliver(ScanOutcome.FellBackToEditor)
        rule.onNodeWithTag("settings:resetProgress").performScrollTo().performClick()
        rule.onNodeWithTag("settings:resetProgress:confirm").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("settings:resetProgress:confirm").fetchSemanticsNodes().isEmpty() }
        rule.waitForIdle()
        rule.onNodeWithTag("settings:close").performClick()
        waitForTag("welcome")
        rule.onNodeWithTag("placement:screen").assertDoesNotExist()
        assertEquals(PlacementPhase.Idle, flow.phase.value)
    }

    private fun assertPrompt(vararg lines: String) {
        val texts =
            rule
                .onNodeWithTag("placement:prompt")
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .map { it.text }
        assertEquals(lines.toList(), texts)
    }

    private fun state(moves: String): CubeState = CubeState.SOLVED.apply((Notation.parse(moves) as NotationResult.Parsed).moves)

    private fun hasTextProperty() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)

    private fun assertPlacementSurface(expected: Color) {
        rule.onNodeWithTag("placement:screen").assertIsDisplayed()
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(expected, pixels[0, 0])
    }

    private fun show(
        enabled: Boolean = true,
        hasScan: Boolean = true,
        failedCatalog: Boolean = false,
        catalogResult: ContentLoadResult = shipped,
        dark: Boolean = false,
        restoration: StateRestorationTester? = null,
        awaitWelcome: Boolean = true,
        largeType: Boolean = false,
    ) {
        val catalog =
            if (failedCatalog) {
                LessonCatalog.of(ContentLoadResult.Failed(ContentLoadError.MissingResource("missing.json")))
            } else {
                LessonCatalog.of(catalogResult)
            }
        val freeCube = FreeCubeViewModel()
        val setContent: (@androidx.compose.runtime.Composable () -> Unit) -> Unit = restoration?.let { it::setContent } ?: rule::setContent
        setContent {
            view = androidx.compose.ui.platform.LocalView.current
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            val shell = @androidx.compose.runtime.Composable {
                CubentiaTheme(darkTheme = dark) {
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
            if (largeType) fontScale200(shell) else shell()
        }
        if (awaitWelcome) waitForTag("welcome")
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

    private companion object {
        const val NON_WHITE_REASON =
            "Our lessons start with the white face and leave yellow for the end, so to follow them, start with the white cross."

        /** Device session C: the first two layers solved from yellow, white on top. */
        const val YELLOW_F2L = "R U2 R' U' R U' R'"

        const val SCRAMBLE = "R2 U' F L2 D B' R U2 B2 D' L F2 U' R2 D2 L' B U2"
    }
}
