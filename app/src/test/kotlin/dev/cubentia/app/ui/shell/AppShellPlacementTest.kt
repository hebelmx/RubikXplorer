package dev.cubentia.app.ui.shell

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
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
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.cube.ValidationResult
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
        with(dev.cubentia.app.ui.editor.EditorFixtures) {
            rule.assertEditorGated()
            rule.enterEveryFace()
        }
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

    private fun assertPlacementSurface(expected: Color) {
        rule.onNodeWithTag("placement:screen").assertIsDisplayed()
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(expected, pixels[0, 0])
    }

    private fun show(
        enabled: Boolean = true,
        hasScan: Boolean = true,
        failedCatalog: Boolean = false,
        dark: Boolean = false,
        restoration: StateRestorationTester? = null,
        awaitWelcome: Boolean = true,
    ) {
        val catalog =
            if (failedCatalog) {
                LessonCatalog.of(ContentLoadResult.Failed(ContentLoadError.MissingResource("missing.json")))
            } else {
                LessonCatalog.of(shipped)
            }
        val freeCube = FreeCubeViewModel()
        val setContent: (@androidx.compose.runtime.Composable () -> Unit) -> Unit = restoration?.let { it::setContent } ?: rule::setContent
        setContent {
            view = androidx.compose.ui.platform.LocalView.current
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
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
}
