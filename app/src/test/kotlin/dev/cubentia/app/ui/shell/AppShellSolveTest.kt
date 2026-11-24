package dev.cubentia.app.ui.shell

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.viewModelScope
import dev.cubentia.app.content.CatalogState
import dev.cubentia.app.content.LessonCatalog
import dev.cubentia.app.data.SerialWriter
import dev.cubentia.app.render.StickerColorKey
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.app.session.SessionHost
import dev.cubentia.app.session.TickingClock
import dev.cubentia.app.session.testFlags
import dev.cubentia.app.solver.SolverLoad
import dev.cubentia.app.solver.SolverSource
import dev.cubentia.app.ui.freecube.FreeCubeViewModel
import dev.cubentia.app.ui.solve.SolveFlowViewModel
import dev.cubentia.app.ui.solve.SolvePhase
import dev.cubentia.app.ui.theme.CubentiaTheme
import dev.cubentia.application.port.CheckpointLoad
import dev.cubentia.application.port.CheckpointRepository
import dev.cubentia.application.port.CheckpointSave
import dev.cubentia.application.port.CubeSolver
import dev.cubentia.application.port.FlagLevel
import dev.cubentia.application.port.SessionSnapshotRepository
import dev.cubentia.application.port.SnapshotLoad
import dev.cubentia.application.port.SnapshotSave
import dev.cubentia.application.port.SolveResult
import dev.cubentia.application.session.AttemptRecord
import dev.cubentia.application.session.Checkpoint
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.application.session.SessionKind
import dev.cubentia.application.session.SessionSnapshot
import dev.cubentia.application.session.SessionState
import dev.cubentia.application.session.reduce
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.cube.Turn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.cubentia.domain.cube.Color as CubeColor

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class AppShellSolveTest {
    @Before fun mainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @get:Rule val rule = createComposeRule()
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val writer = SerialWriter()
    private var checkpoint: Checkpoint? = null
    private val checkpoints =
        object : CheckpointRepository {
            override suspend fun clear(): CheckpointSave {
                checkpoint = null
                return CheckpointSave.Saved
            }

            override suspend fun save(checkpoint: Checkpoint): CheckpointSave {
                this@AppShellSolveTest.checkpoint = checkpoint
                return CheckpointSave.Saved
            }

            override suspend fun find(kind: SessionKind): CheckpointLoad {
                val saved = checkpoint ?: return CheckpointLoad.None
                return CheckpointLoad.Found(saved)
            }
        }
    private var host =
        SessionHost(
            TickingClock(),
            Dispatchers.Default.limitedParallelism(1),
            writer = writer,
            checkpoints = checkpoints,
        )
    private var solver: CubeSolver = CubeSolver { SolveResult.Solved(emptyList()) }
    private var allowSolverLoad = true

    @Volatile private var ready = true

    // Opt-in: the source keeps its solver after its first gated load, like PruningTableSolverSource (#262).
    private var keepsSolver = false

    @Volatile private var tableLoads = 0
    private var generating = false
    private val loadGate = CompletableDeferred<Unit>()
    private val flow =
        SolveFlowViewModel(
            object : SolverSource {
                override val isReady get() = ready

                override suspend fun load(onGenerating: () -> Unit): SolverLoad {
                    if (!allowSolverLoad) throw AssertionError("Already-solved confirmation must not load the solver")
                    if (generating) onGenerating()
                    if (!ready) {
                        tableLoads++
                        loadGate.await()
                        if (keepsSolver) ready = true
                    }
                    return SolverLoad.Ready(solver)
                }
            },
        )

    @After fun close() {
        try {
            runBlocking {
                flow.viewModelScope.coroutineContext.job
                    .cancelAndJoin()
                // A style restart still in flight must not outlive its test (Story 15.3).
                styles.viewModelScope.coroutineContext.job
                    .cancelAndJoin()
                writer.close()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private var back: OnBackPressedDispatcher? = null

    private var view: android.view.View? = null

    private fun show(
        dark: Boolean = false,
        scan: (() -> Unit)? = null,
        quickScan: (() -> Unit)? = null,
        quickLevel: FlagLevel = FlagLevel.OFF,
        cameraScan: dev.cubentia.application.port.FlagLevel = dev.cubentia.application.port.FlagLevel.OFF,
        quickCheck: dev.cubentia.app.scan.QuickCheckCapture? = null,
        catalog: LessonCatalog = LessonCatalog.of(ContentLoadResult.Loaded(bundle)),
    ) {
        val freeCube = FreeCubeViewModel()
        rule.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CubentiaTheme(darkTheme = dark) {
                AppShell(
                    host,
                    catalog,
                    freeCube,
                    false,
                    testFlags(cameraScan = cameraScan, quickScan = quickLevel),
                    solveFlow = flow,
                    onScan = scan,
                    onQuickScan = quickScan,
                    quickCheck = quickCheck,
                    solutionStyles = styles,
                )
            }
        }
        rule.waitForIdle()
    }

    private val styles =
        dev.cubentia.app.ui.solve.SolutionStyleViewModel(
            {
                dev.cubentia.application.usecase
                    .SolveByMethod(it)
            },
            dev.cubentia.app.solver
                .MethodSolutionCache(),
        )

    private fun click(tag: String) {
        // The shell reacts to host and solve-flow state set off the main thread, which waitForIdle does not track: wait
        // until the target is composed (exactly once) before clicking it.
        rule.waitUntil(10000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    private fun solve(cube: CubeState) {
        rule.runOnIdle { flow.accept(cube) { state, moves -> host.dispatch { SessionEvent.SolvePlaybackStarted(state, moves, it) } } }
        rule.waitUntil(10000) { host.state.value.kind == SessionKind.SOLVE_PLAYBACK }
        rule.waitForIdle()
    }

    @Test fun scanButtonOpensEditorWithTheDenialNote() {
        show(scan = flow::scanDenied, cameraScan = dev.cubentia.application.port.FlagLevel.GA)
        click("nav:solve")
        rule.onNodeWithTag("solve:scan").assertExists()
        click("solve:scan")
        rule.onNodeWithTag("solve:scanNote").assertTextEquals("Camera access is off, so enter your cube's colors here.")
        rule.onNodeWithTag("editor:accept").assertExists()
        with(dev.cubentia.app.ui.editor.EditorFixtures) { rule.assertEditorGated() }
    }

    @Test fun scanFallBackOpensAFreshEditor() {
        show(scan = { flow.onScanOutcome(ScanOutcome.FellBackToEditor()) }, cameraScan = dev.cubentia.application.port.FlagLevel.GA)
        click("nav:solve")
        click("solve:scan")
        with(dev.cubentia.app.ui.editor.EditorFixtures) { rule.assertEditorGated() }
    }

    @Test fun betaMakesManualEntryPrimaryAndExplainsTheScan() {
        show(scan = {}, cameraScan = dev.cubentia.application.port.FlagLevel.BETA)
        click("nav:solve")
        assertActionOrder("solve:enterColors", "solve:scan")
        rule.onNodeWithTag("solve:scan").assertContentDescriptionEquals("Scan my cube, BETA")
        rule.onNodeWithTag("solve:scan:beta", useUnmergedTree = true).assertTextEquals("BETA")
        rule.onNodeWithTag("solve:scan:betaNote").assertTextEquals("May misread colors in poor light. Manual entry always works.")
    }

    @Test fun quickScanBetaShowsItsOwnLabelAndInvokesItsAction() {
        var launched = false
        show(scan = {}, cameraScan = FlagLevel.GA, quickScan = { launched = true }, quickLevel = FlagLevel.BETA)
        rule.onNodeWithTag("solve:quickScan").assertDoesNotExist()
        click("nav:solve")
        rule.onNodeWithTag("solve:scan").assertExists()
        rule.onNodeWithTag("solve:quickScan").assertContentDescriptionEquals("Quick Scan, BETA")
        rule.onNodeWithTag("solve:quickScan:beta", useUnmergedTree = true).assertTextEquals("BETA")
        rule.onNodeWithTag("solve:quickScan:betaNote").assertExists()
        click("solve:quickScan")
        assertEquals(true, launched)
    }

    @Test fun quickScanGaShowsTheBetaLabelWhileCameraScanIsBeta() {
        // EXPERIENCE *Feature flag BETA*: every camera feature is BETA-labelled while CAMERA_SCAN is BETA.
        show(scan = {}, cameraScan = FlagLevel.BETA, quickScan = {}, quickLevel = FlagLevel.GA)
        click("nav:solve")
        rule.onNodeWithTag("solve:quickScan").assertContentDescriptionEquals("Quick Scan, BETA")
        rule.onNodeWithTag("solve:quickScan:beta", useUnmergedTree = true).assertTextEquals("BETA")
        rule.onNodeWithTag("solve:scan:betaNote").assertExists()
    }

    @Test fun quickScanGaWithCameraScanGaShowsNoBetaCopy() {
        show(scan = {}, cameraScan = FlagLevel.GA, quickScan = {}, quickLevel = FlagLevel.GA)
        click("nav:solve")
        rule.onNodeWithTag("solve:quickScan").assertExists()
        rule.onNodeWithTag("solve:quickScan:beta", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("solve:quickScan:betaNote").assertDoesNotExist()
        assertNoScanBeta()
    }

    @Test fun quickScanOffHidesTheAction() {
        show(scan = {}, cameraScan = FlagLevel.GA, quickScan = {})
        click("nav:solve")
        rule.onNodeWithTag("solve:quickScan").assertDoesNotExist()
    }

    @Test fun quickScanWithoutActionStaysHidden() {
        show(scan = {}, cameraScan = FlagLevel.GA, quickLevel = FlagLevel.BETA)
        click("nav:solve")
        rule.onNodeWithTag("solve:quickScan").assertDoesNotExist()
        rule.onNodeWithTag("solve:quickScan:betaNote").assertDoesNotExist()
    }

    @Test fun gaKeepsScanPrimaryWithNoBetaCopy() {
        show(scan = {}, cameraScan = dev.cubentia.application.port.FlagLevel.GA)
        click("nav:solve")
        assertActionOrder("solve:scan", "solve:enterColors")
        assertNoScanBeta()
    }

    private fun assertActionOrder(
        first: String,
        second: String,
    ) {
        val firstBounds = rule.onNodeWithTag(first).fetchSemanticsNode().boundsInRoot
        val secondBounds = rule.onNodeWithTag(second).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(firstBounds.right <= secondBounds.left)
    }

    private fun assertNoScanBeta() {
        rule.onNodeWithTag("solve:scan:beta", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("solve:scan:betaNote").assertDoesNotExist()
    }

    @Test fun offFlagHidesEvenABoundScanAction() {
        show(scan = { error("Hidden action must not launch") })
        click("nav:solve")
        rule.onNodeWithTag("solve:scan").assertDoesNotExist()
        assertNoScanBeta()
        rule.onNodeWithTag("solve:enterColors").assertExists()
    }

    @Test fun solvedEditorEntryOffersLearnAndEndsPlayback() {
        allowSolverLoad = false
        show()
        click("nav:solve")
        rule.onNodeWithTag("solve:enterColors").assertExists()
        rule.onNodeWithText("Scan my cube").assertDoesNotExist()
        click("solve:enterColors")
        rule.onNodeWithTag("solve:pauseNote").assertDoesNotExist()
        with(dev.cubentia.app.ui.editor.EditorFixtures) {
            rule.assertEditorGated()
            rule.enterEveryFace()
        }
        click("editor:accept")
        assertAlreadySolved()
        click("solve:alreadySolved:learn")
        rule.onNodeWithTag("nav:learn").assertIsSelected()
        assertEquals(SolvePhase.Home, flow.phase.value)
        rule.onNodeWithTag("solve:enterColors").assertDoesNotExist()
    }

    @Test fun solvedScanReturnsToSolveHome() {
        allowSolverLoad = false
        val cube = CubeState.SOLVED.reoriented(Orientation.of(Face.U, Face.R))
        show(
            scan = { flow.onScanOutcome(ScanOutcome.Accepted(cube)) },
            cameraScan = dev.cubentia.application.port.FlagLevel.GA,
        )
        click("nav:solve")
        click("solve:scan")
        rule.onNodeWithTag("editor:accept").assertDoesNotExist()
        assertAlreadySolved()
        click("solve:alreadySolved:again")
        rule.onNodeWithTag("solve:enterColors").assertExists()
        rule.onNodeWithTag("solve:scan").assertExists()
        click("solve:scan")
        rule.onNodeWithTag("editor:accept").assertDoesNotExist()
        assertAlreadySolved()
        rule.runOnIdle { back!!.onBackPressed() }
        rule.onNodeWithTag("solve:enterColors").assertExists()
    }

    private fun assertAlreadySolved() {
        rule.onNodeWithTag("solve:alreadySolved").assertTextEquals(
            "Your cube is already solved. Scramble it and try again, or learn to solve it yourself.",
        )
        rule
            .onNodeWithTag("solve:alreadySolved")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.onNodeWithTag("nav:bar").assertExists()
        rule.onNodeWithTag("solvePlayback:screen").assertDoesNotExist()
        rule.onNodeWithTag("editor:accept").assertDoesNotExist()
        assertNotEquals(SessionKind.SOLVE_PLAYBACK, host.state.value.kind)
    }

    private fun seedScrambledEditor(cube: CubeState) {
        solver = CubeSolver { SolveResult.TimedOut }
        rule.runOnIdle { flow.accept(cube) { _, _ -> error("unexpected playback") } }
        rule.waitUntil(10000) {
            flow.phase.value ==
                SolvePhase.Editing(
                    cube.toFacelets(),
                    dev.cubentia.domain.text
                        .Text("msg.solve.timed-out"),
                )
        }
    }

    @Test fun scrambledSolutionFinishesAndCloseReturnsToSolve() {
        val move = FaceTurn(Face.R, Turn.CLOCKWISE)
        solver = CubeSolver { SolveResult.Solved(listOf(move.inverse())) }
        show()
        click("nav:solve")
        solve(CubeState.SOLVED.apply(move))
        rule.onNodeWithTag("solve:learnOffer").assertDoesNotExist()
        click("playback:end")
        rule.onNodeWithTag("solve:learnOffer").assertExists()
        click("solvePlayback:close")
        rule.waitUntil(10000) { host.state.value.kind != SessionKind.SOLVE_PLAYBACK }
        rule.onNodeWithTag("solve:enterColors").assertExists()
    }

    @Test fun editorRetryAfterTimeoutStartsPlaybackOfTheEnteredCubeAndBackCloses() {
        val move = FaceTurn(Face.R, Turn.CLOCKWISE)
        val cube = CubeState.SOLVED.apply(move)
        solver = CubeSolver { SolveResult.TimedOut }
        show()
        click("nav:solve")
        rule.runOnIdle { flow.accept(cube) { _, _ -> error("unexpected playback") } }
        rule.waitUntil(10000) { flow.phase.value is SolvePhase.Editing }
        solver = CubeSolver { SolveResult.Solved(listOf(move.inverse())) }
        click("editor:accept")
        rule.waitUntil(10000) { host.state.value.kind == SessionKind.SOLVE_PLAYBACK }
        rule.waitForIdle()
        assertEquals(cube, host.state.value.entry)
        assertEquals(listOf(move.inverse()), host.state.value.solution)
        rule.onNodeWithTag("solvePlayback:screen").assertExists()
        rule.runOnIdle { back!!.onBackPressed() }
        rule.waitUntil(10000) { host.state.value.kind != SessionKind.SOLVE_PLAYBACK }
        rule.waitForIdle()
        rule.onNodeWithTag("solve:enterColors").assertExists()
    }

    @Test fun firstUsePreparationShowsCopyAndCancelKeepsColors() {
        ready = false
        generating = true
        val cube = CubeState.SOLVED.apply(FaceTurn(Face.R, Turn.CLOCKWISE))
        show()
        click("nav:solve")
        rule.runOnIdle { flow.accept(cube) { _, _ -> error("unexpected playback") } }
        rule.waitUntil(10000) { flow.phase.value == SolvePhase.Solving(dev.cubentia.app.ui.solve.Preparation.FIRST_USE) }
        rule.onNodeWithTag("solve:preparing").assertTextEquals("Setting up the solver for first use. This can take a little while.")
        rule.onNodeWithTag("solve:progress").assertExists()
        rule.onNodeWithTag("solve:cancel").assertTextEquals("Cancel")
        click("solve:cancel")
        assertEquals(SolvePhase.Editing(cube.toFacelets()), flow.phase.value)
        rule.onNodeWithTag("editor:accept").assertExists()
        rule.onNodeWithTag("solve:preparing").assertDoesNotExist()
    }

    @Test fun unacceptedEditorStickersSurviveATabSwitch() {
        show()
        click("nav:solve")
        click("solve:enterColors")
        with(dev.cubentia.app.ui.editor.EditorFixtures) { rule.assertEditorGated() }
        rule.onNodeWithTag("sticker:0").performClick()
        rule.onNodeWithTag("swatch:RED").performScrollTo().performClick()
        // Up counts once all 8 of its stickers are set (GH #352): auto-advance walks the other seven.
        repeat(7) { rule.onNodeWithTag("swatch:WHITE").performScrollTo().performClick() }
        rule.waitForIdle()
        val fiveLeft = "5 faces left to enter: Front, Right, Back, Left, Down."
        with(dev.cubentia.app.ui.editor.EditorFixtures) { rule.assertEditorGated(fiveLeft) }
        val red =
            androidx.compose.ui.graphics
                .Color(
                    dev.cubentia.domain.appearance.StickerAppearance
                        .of(CubeColor.RED)
                        .fill,
                )
        click("nav:learn")
        click("nav:solve")
        // The reopened editor selects the first unset sticker (Right 1); in one-face mode show Up again.
        if (rule.onAllNodesWithTag("editor:face:0").fetchSemanticsNodes().isNotEmpty()) click("editor:face:0")
        assertEquals(red, rule.onNodeWithTag("sticker:0").fetchSemanticsNode().config[StickerColorKey])
        // The set stickers survive too: Up still counts as entered.
        with(dev.cubentia.app.ui.editor.EditorFixtures) { rule.assertEditorGated(fiveLeft) }
        // Close asks first (GH #354); Discard drops the draft: Enter colors opens on the accepted stickers again.
        click("solve:close")
        click("solve:discard:confirm")
        click("solve:enterColors")
        assertEquals(CubeColor.WHITE, (flow.phase.value as SolvePhase.Editing).initial.colors[0])
        org.junit.Assert.assertNotEquals(red, rule.onNodeWithTag("sticker:0").fetchSemanticsNode().config[StickerColorKey])
    }

    @Test fun closingAFreshEditorWithNothingEnteredReturnsHomeAtOnce() {
        show()
        click("nav:solve")
        click("solve:enterColors")
        click("solve:close")
        rule.onNodeWithTag("solve:discard").assertDoesNotExist()
        assertEquals(SolvePhase.Home, flow.phase.value)
        click("solve:enterColors")
        rule.runOnIdle { back!!.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag("solve:discard").assertDoesNotExist()
        assertEquals(SolvePhase.Home, flow.phase.value)
    }

    @Test fun closeAfterInputAsksAndKeepEnteringKeepsTheStickers() {
        show()
        click("nav:solve")
        click("solve:enterColors")
        listOf(0, 1, 2).forEach { index ->
            rule.onNodeWithTag("sticker:$index").performClick()
            rule.onNodeWithTag("swatch:RED").performScrollTo().performClick()
        }
        rule.waitForIdle()
        click("solve:close")
        rule.onNodeWithTag("solve:discard").assertExists()
        rule.onNodeWithText("Discard the colors you entered?").assertExists()
        rule.onNodeWithTag("solve:discard:confirm").assertTextEquals("Discard")
        rule.onNodeWithTag("solve:discard:cancel").assertTextEquals("Keep entering")
        click("solve:discard:cancel")
        rule.onNodeWithTag("solve:discard").assertDoesNotExist()
        val red =
            androidx.compose.ui.graphics
                .Color(
                    dev.cubentia.domain.appearance.StickerAppearance
                        .of(CubeColor.RED)
                        .fill,
                )
        listOf(0, 1, 2).forEach { assertEquals(red, rule.onNodeWithTag("sticker:$it").fetchSemanticsNode().config[StickerColorKey]) }
        // Painting moved the selection on to Up 4; Keep entering leaves it there.
        assertEquals(
            true,
            rule
                .onNodeWithTag("sticker:3")
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Selected),
        )
        // System Back asks the same; Back on the dialog keeps entering; Discard returns to Solve Home.
        rule.runOnIdle { back!!.onBackPressed() }
        rule.onNodeWithTag("solve:discard").assertExists()
        rule.runOnIdle {
            (
                org.robolectric.shadows.ShadowDialog
                    .getLatestDialog() as androidx.activity.ComponentDialog
            ).onBackPressedDispatcher
                .onBackPressed()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("solve:discard").assertDoesNotExist()
        assertTrue(flow.phase.value is SolvePhase.Editing)
        rule.runOnIdle { back!!.onBackPressed() }
        click("solve:discard:confirm")
        assertEquals(SolvePhase.Home, flow.phase.value)
        rule.onNodeWithTag("solve:enterColors").assertExists()
    }

    @Test fun leavingSolveCancelsARunningSolve() {
        ready = false
        var published = false
        show()
        click("nav:solve")
        rule.runOnIdle { flow.accept(CubeState.SOLVED) { _, _ -> published = true } }
        rule.waitForIdle()
        rule.onNodeWithTag("solve:preparing").assertTextEquals(
            "Preparing the solver.",
        )
        click("nav:learn")
        assertEquals(SolvePhase.Editing(CubeState.SOLVED.toFacelets()), flow.phase.value)
        loadGate.complete(Unit)
        rule.waitForIdle()
        Thread.sleep(200)
        rule.waitForIdle()
        assertEquals(false, published)
        assertNotEquals(SessionKind.SOLVE_PLAYBACK, host.state.value.kind)
    }

    @Test fun preparingShowsOnTheFirstSolveOnlyWhenTheSourceKeepsItsSolver() {
        // Like PruningTableSolverSource: not ready until its first load completes, then it keeps its solver (#262).
        ready = false
        keepsSolver = true
        val first = FaceTurn(Face.R, Turn.CLOCKWISE)
        solver = CubeSolver { SolveResult.Solved(listOf(first.inverse())) }
        show()
        click("nav:solve")
        rule.runOnIdle {
            flow.accept(
                CubeState.SOLVED.apply(first),
            ) { state, moves -> host.dispatch { SessionEvent.SolvePlaybackStarted(state, moves, it) } }
        }
        rule.waitUntil(10000) { rule.onAllNodesWithTag("solve:preparing").fetchSemanticsNodes().size == 1 }
        rule.onNodeWithTag("solve:preparing").assertTextEquals("Preparing the solver.")
        loadGate.complete(Unit)
        rule.waitUntil(10000) { host.state.value.kind == SessionKind.SOLVE_PLAYBACK }
        click("solvePlayback:close")
        rule.waitUntil(10000) { host.state.value.kind != SessionKind.SOLVE_PLAYBACK }
        rule.waitForIdle()
        // Hold the second solve so the solving state is observable. The NonCancellable gate only keeps the solving state
        // held: SolveCube's 3 s deadline still applies, so the gate must be released within it (as ScreenAudit notes).
        val second = FaceTurn(Face.U, Turn.CLOCKWISE)
        val solveGate = CompletableDeferred<Unit>()
        solver =
            CubeSolver {
                withContext(NonCancellable) { solveGate.await() }
                SolveResult.Solved(listOf(second.inverse()))
            }
        rule.runOnIdle {
            flow.accept(
                CubeState.SOLVED.apply(second),
            ) { state, moves -> host.dispatch { SessionEvent.SolvePlaybackStarted(state, moves, it) } }
        }
        rule.waitUntil(10000) { rule.onAllNodesWithTag("solve:progress").fetchSemanticsNodes().size == 1 }
        rule.onNodeWithTag("solve:preparing").assertDoesNotExist()
        assertEquals(1, tableLoads)
        solveGate.complete(Unit)
        rule.waitUntil(10000) { host.state.value.kind == SessionKind.SOLVE_PLAYBACK }
        assertEquals(CubeState.SOLVED.apply(second), host.state.value.entry)
    }

    @Test fun physicalLastDoneOffersLearn() {
        val move = FaceTurn(Face.R, Turn.CLOCKWISE)
        solver = CubeSolver { SolveResult.Solved(listOf(move.inverse())) }
        show()
        click("nav:solve")
        solve(CubeState.SOLVED.apply(move))
        click("playback:followOnMyCube")
        rule.waitUntil(10000) { host.state.value.isPhysical }
        rule.waitForIdle()
        rule.onNodeWithTag("solve:learnOffer").assertDoesNotExist()
        click("playback:done")
        rule.waitUntil(10000) { rule.onAllNodesWithTag("solve:learnOffer").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("solve:learnOffer").assertTextEquals("Want to learn how to do this yourself?")
    }

    @Test fun physicalPlaybackQuickCheckAndScanAllCanResolveAnAlreadySolvedCube() {
        val move = FaceTurn(Face.R, Turn.CLOCKWISE)
        val cube = CubeState.SOLVED.apply(move)
        val started = mutableListOf<dev.cubentia.application.session.Effect.QuickCheck>()
        show(
            cameraScan = dev.cubentia.application.port.FlagLevel.GA,
            quickCheck =
                dev.cubentia.app.scan
                    .QuickCheckCapture { started += it },
        )
        click("nav:solve")
        click("solve:enterColors")
        seedScrambledEditor(cube)
        solver = CubeSolver { SolveResult.Solved(listOf(move, FaceTurn(Face.R, Turn.HALF))) }
        click("editor:accept")
        rule.waitUntil(10000) { host.state.value.kind == SessionKind.SOLVE_PLAYBACK }
        rule.waitForIdle()
        click("playback:followOnMyCube")
        rule.waitUntil(10000) { host.state.value.isPhysical }
        rule.waitForIdle()
        click("playback:quickCheck")
        rule.waitUntil(10000) { started.isNotEmpty() }
        val first = started.single()
        assertEquals(first, host.state.value.pendingQuickCheck)
        val wrong =
            host.state.value.expected
                .apply(FaceTurn(Face.U, Turn.CLOCKWISE))
                .reoriented(dev.cubentia.domain.cube.Orientation.HOME)
                .toFacelets()
                .colors
        runBlocking {
            host.dispatch {
                SessionEvent.QuickCheckObserved(
                    first.effectId,
                    first.revision,
                    dev.cubentia.domain.recognition.PhysicalObservation
                        .Partial(first.faces.associateWith { wrong.subList(it.ordinal * 9, it.ordinal * 9 + 9) }),
                    it,
                )
            }
        }
        rule.waitUntil(10000) { host.state.value.offersFullScan }
        rule.waitForIdle()
        click("playback:scanAll")
        rule.waitUntil(10000) { started.size == 2 }
        assertEquals(Face.entries.toSet(), started[1].faces)
        assertEquals(started[1], host.state.value.pendingQuickCheck)
        allowSolverLoad = false
        runBlocking {
            host.dispatch {
                SessionEvent.QuickCheckObserved(
                    started[1].effectId,
                    started[1].revision,
                    dev.cubentia.domain.recognition.PhysicalObservation
                        .Full(CubeState.SOLVED),
                    it,
                )
            }
        }
        rule.waitUntil(10000) { host.state.value.canResolveObserved }
        click("playback:useMyCube")
        rule.waitUntil(10000) { host.state.value.kind != SessionKind.SOLVE_PLAYBACK }
        assertAlreadySolved()
    }

    @Test fun playbackFromAnAcceptedScanPlansAtMostTwoFacesAndUseMyCubeReSolvesAFullMismatch() {
        val move = FaceTurn(Face.R, Turn.CLOCKWISE)
        val cube = CubeState.SOLVED.apply(move)
        solver = CubeSolver { SolveResult.Solved(listOf(move.inverse())) }
        val started = mutableListOf<dev.cubentia.application.session.Effect.QuickCheck>()
        show(
            cameraScan = dev.cubentia.application.port.FlagLevel.GA,
            scan = {
                flow.onScanOutcome(
                    dev.cubentia.app.scan.ScanOutcome
                        .Accepted(cube),
                )
            },
            quickCheck =
                dev.cubentia.app.scan
                    .QuickCheckCapture { started += it },
        )
        click("nav:solve")
        click("solve:scan")
        rule.onNodeWithTag("editor:accept").assertDoesNotExist()
        rule.waitUntil(10000) { host.state.value.kind == SessionKind.SOLVE_PLAYBACK }
        rule.waitForIdle()
        click("playback:followOnMyCube")
        rule.waitUntil(10000) { host.state.value.isPhysical }
        rule.waitForIdle()
        assertEquals(emptySet<Face>(), host.state.value.touchedSinceVerified)
        click("playback:quickCheck")
        rule.waitUntil(10000) { started.isNotEmpty() }
        val first = started.single()
        assertEquals(2, first.faces.size)
        // The cube is not where playback expects: a Full observation shows it one turn off.
        val actual = cube.apply(FaceTurn(Face.U, Turn.CLOCKWISE))
        solver = CubeSolver { SolveResult.Solved(listOf(FaceTurn(Face.U, Turn.COUNTERCLOCKWISE), move.inverse())) }
        runBlocking {
            host.dispatch {
                SessionEvent.QuickCheckObserved(
                    first.effectId,
                    first.revision,
                    dev.cubentia.domain.recognition.PhysicalObservation
                        .Full(actual),
                    it,
                )
            }
        }
        rule.waitUntil(10000) { host.state.value.canResolveObserved }
        rule.waitForIdle()
        rule.onNodeWithTag("playback:useMyCube").assertTextEquals("Use my cube as it is now")
        click("playback:useMyCube")
        rule.waitUntil(10000) { host.state.value.entry == actual && host.state.value.isPhysical }
        rule.waitForIdle()
        val restarted = host.state.value
        assertEquals(SessionKind.SOLVE_PLAYBACK, restarted.kind)
        assertEquals(actual, restarted.expected)
        assertEquals(2, restarted.solution.size)
        assertEquals(emptySet<Face>(), restarted.touchedSinceVerified)
        rule.onNodeWithTag("playback:useMyCube").assertDoesNotExist()
        // The re-solve plays as *Shortest*, and a style now builds from the observed cube.
        chip(dev.cubentia.app.ui.solve.SolutionStyle.SHORTEST).assertIsSelected()
        chip(dev.cubentia.app.ui.solve.SolutionStyle.BEGINNER).assertIsEnabled()
        chooseBeginnerAndAwaitRestart()
        val beginner = host.state.value
        assertEquals(actual, beginner.entry)
        org.junit.Assert.assertTrue(beginner.entry.apply(beginner.solution).isSolved)
        org.junit.Assert.assertTrue(beginner.isPhysical)
        // The restart keeps the re-solve's Full verified baseline.
        assertEquals(emptySet<Face>(), beginner.touchedSinceVerified)
        assertEquals(
            dev.cubentia.domain.recognition.PhysicalObservation
                .Full(actual),
            beginner.observed,
        )
    }

    @Test fun aStyleRestartFromAnAcceptedScanKeepsTheScanBaseline() {
        val move = FaceTurn(Face.R, Turn.CLOCKWISE)
        val cube = CubeState.SOLVED.apply(move)
        solver = CubeSolver { SolveResult.Solved(shortest) }
        val colors =
            dev.cubentia.domain.recognition.CalibrationCenters(
                dev.cubentia.domain.cube.Color.entries.associateWith {
                    dev.cubentia.domain.recognition
                        .Lab(50.0, it.ordinal * 4.0, -it.ordinal * 2.0)
                },
            )
        show(
            cameraScan = dev.cubentia.application.port.FlagLevel.GA,
            scan = { flow.onScanOutcome(ScanOutcome.Accepted(cube, colors)) },
        )
        click("nav:solve")
        click("solve:scan")
        rule.waitUntil(10000) { host.state.value.kind == SessionKind.SOLVE_PLAYBACK }
        rule.waitForIdle()
        assertEquals(emptySet<Face>(), host.state.value.touchedSinceVerified)
        assertEquals(colors, host.runScanColors())
        chooseBeginnerAndAwaitRestart()
        val restarted = host.state.value
        assertNotEquals(shortest, restarted.solution)
        assertEquals(cube, restarted.entry)
        assertEquals(emptySet<Face>(), restarted.touchedSinceVerified)
        // The run-scan colors survive the restart for this run's Quick Checks (GH #172).
        assertEquals(colors, host.runScanColors())
    }

    /** Taps Beginner and waits until its solution has restarted the session, back in physical mode when it was. */
    private fun chooseBeginnerAndAwaitRestart() {
        val before = host.state.value
        click("solutionStyle:BEGINNER")
        rule.waitUntil(10000) {
            val now = host.state.value
            styles.state.value.playing == dev.cubentia.app.ui.solve.SolutionStyle.BEGINNER &&
                now.revision > before.revision &&
                now.isPhysical == before.isPhysical
        }
        rule.waitForIdle()
    }

    @Test fun aScanHeldOffHomeStartsPlaybackInItsOwnFrame() {
        val move = FaceTurn(Face.R, Turn.CLOCKWISE)
        val held = CubeState.SOLVED.apply(move).reoriented(Orientation.of(Face.U, Face.R))
        solver = CubeSolver { SolveResult.Solved(listOf(move.inverse())) }
        show(
            scan = { flow.onScanOutcome(ScanOutcome.Accepted(held)) },
            cameraScan = dev.cubentia.application.port.FlagLevel.GA,
        )
        click("nav:solve")
        click("solve:scan")
        rule.waitUntil(10000) { host.state.value.kind == SessionKind.SOLVE_PLAYBACK }
        assertEquals(held, host.state.value.entry)
        assertEquals(
            dev.cubentia.domain.recognition.PhysicalObservation
                .Full(held),
            host.state.value.observed,
        )
    }

    @Test fun aFailedScanSolveRetriedFromTheEditorKeepsTheScanBaseline() {
        val move = FaceTurn(Face.R, Turn.CLOCKWISE)
        val held = CubeState.SOLVED.apply(move).reoriented(Orientation.of(Face.U, Face.R))
        solver = CubeSolver { SolveResult.TimedOut }
        show(
            scan = { flow.onScanOutcome(ScanOutcome.Accepted(held)) },
            cameraScan = dev.cubentia.application.port.FlagLevel.GA,
        )
        click("nav:solve")
        click("solve:scan")
        rule.waitUntil(10000) { flow.phase.value is SolvePhase.Editing }
        assertEquals(held.reoriented(Orientation.HOME).toFacelets(), (flow.phase.value as SolvePhase.Editing).initial)
        solver = CubeSolver { SolveResult.Solved(listOf(move.inverse())) }
        click("editor:accept")
        rule.waitUntil(10000) { host.state.value.kind == SessionKind.SOLVE_PLAYBACK }
        val entry = host.state.value.entry
        assertEquals(
            dev.cubentia.domain.recognition.PhysicalObservation
                .Full(entry),
            host.state.value.observed,
        )
    }

    @Test fun leavingSolveAfterAFailedScanSolveDropsTheScanBaseline() {
        val move = FaceTurn(Face.R, Turn.CLOCKWISE)
        val cube = CubeState.SOLVED.apply(move)
        solver = CubeSolver { SolveResult.TimedOut }
        show(
            scan = { flow.onScanOutcome(ScanOutcome.Accepted(cube)) },
            cameraScan = dev.cubentia.application.port.FlagLevel.GA,
        )
        click("nav:solve")
        click("solve:scan")
        rule.waitUntil(10000) { flow.phase.value is SolvePhase.Editing }
        click("nav:learn")
        click("nav:solve")
        solver = CubeSolver { SolveResult.Solved(listOf(move.inverse())) }
        click("editor:accept")
        rule.waitUntil(10000) { host.state.value.kind == SessionKind.SOLVE_PLAYBACK }
        assertEquals(cube, host.state.value.entry)
        assertNotEquals(
            dev.cubentia.domain.recognition.PhysicalObservation
                .Full(cube),
            host.state.value.observed,
        )
    }

    @Test fun acceptedScanWaitsForCatalogAndStartsPlaybackOnce() {
        val catalogIo = StandardTestDispatcher()
        val catalog = LessonCatalog({ ContentLoadResult.Loaded(bundle) }, catalogIo)
        val move = FaceTurn(Face.R, Turn.CLOCKWISE)
        val cube = CubeState.SOLVED.apply(move)
        val solves =
            java.util.concurrent.atomic
                .AtomicInteger()
        solver =
            CubeSolver {
                solves.incrementAndGet()
                SolveResult.Solved(listOf(move.inverse()))
            }
        show(catalog = catalog)
        click("nav:solve")
        val beforeScan = host.state.value
        rule.runOnIdle { flow.onScanOutcome(ScanOutcome.Accepted(cube)) }
        rule.onNodeWithTag("editor:accept").assertDoesNotExist()
        assertEquals(CatalogState.Loading, catalog.state.value)
        assertEquals(cube, flow.pendingScan.value)
        assertEquals(SolvePhase.Home, flow.phase.value)
        assertEquals(beforeScan, host.state.value)
        assertEquals(0, solves.get())

        catalogIo.scheduler.runCurrent()
        rule.waitUntil(10000) { host.state.value.kind == SessionKind.SOLVE_PLAYBACK }
        rule.onNodeWithTag("solvePlayback:screen").assertExists()
        rule.onNodeWithTag("editor:accept").assertDoesNotExist()
        assertNull(flow.pendingScan.value)
        assertEquals(cube, host.state.value.entry)
        assertEquals(beforeScan.revision + 1, host.state.value.revision)
        assertEquals(1, solves.get())
    }

    @Test fun lessonIsUntouchedDuringPreparationAndReturnsOnClose() {
        runBlocking {
            host.restore(bundle)
            host.dispatch {
                SessionEvent.LessonStarted(
                    bundle.stages
                        .first()
                        .lessons
                        .first(),
                    bundle.tutorConfig,
                    it,
                )
            }
        }
        val lesson = host.state.value.lesson
        show()
        click("nav:solve")
        rule.onNodeWithTag("solve:pauseNote").assertTextEquals("Starting a solve pauses your lesson.")
        assertPauseNoteMargin()
        click("solve:enterColors")
        val move = FaceTurn(Face.R, Turn.CLOCKWISE)
        val cube = CubeState.SOLVED.apply(move)
        seedScrambledEditor(cube)
        solver = CubeSolver { SolveResult.Solved(listOf(move.inverse())) }
        ready = false
        rule.onNodeWithTag("solve:pauseNote").assertTextEquals("Starting a solve pauses your lesson.")
        assertPauseNoteMargin()
        click("editor:accept")
        rule.onNodeWithTag("solve:preparing").assertTextEquals(
            "Preparing the solver.",
        )
        rule.onNodeWithTag("solve:progress").assertExists()
        assertPauseNoteMargin()
        assertEquals(lesson, host.state.value.lesson)
        click("solve:cancel")
        rule.onNodeWithTag("solve:error").assertDoesNotExist()
        assertEquals(lesson, host.state.value.lesson)
        ready = true
        click("editor:accept")
        rule.waitUntil(10000) { host.state.value.kind == SessionKind.SOLVE_PLAYBACK }
        click("solvePlayback:close")
        rule.waitUntil(10000) { host.state.value.kind == SessionKind.LESSON }
        click("nav:learn")
        rule.onNodeWithTag("learn:continue").assertExists()
        assertEquals(lesson, host.state.value.lesson)
    }

    @Test fun timeoutKeepsAcceptedStickersAndShowsRecoveryCopy() {
        solver = CubeSolver { SolveResult.TimedOut }
        val cube = CubeState.SOLVED.apply(FaceTurn(Face.F, Turn.CLOCKWISE))
        show()
        click("nav:solve")
        rule.runOnIdle { flow.accept(cube) { _, _ -> error("unexpected playback") } }
        rule.waitUntil(10000) { flow.phase.value is SolvePhase.Editing }
        rule.onNodeWithTag("solve:error").assertTextEquals("Couldn't find a solution in time. Your colors are still here — try again.")
        rule
            .onNodeWithTag("solve:error")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertEquals(cube.toFacelets(), (flow.phase.value as SolvePhase.Editing).initial)
        rule.onNodeWithTag("solvePlayback:screen").assertDoesNotExist()
        assertNotEquals(SessionKind.SOLVE_PLAYBACK, host.state.value.kind)
    }

    @Test fun coldStartDiscardsPlaybackSnapshot() {
        val state =
            reduce(
                SessionState.IDLE,
                SessionEvent.SolvePlaybackStarted(CubeState.SOLVED, emptyList(), kotlin.time.Instant.fromEpochMilliseconds(0)),
            ).state
        val snapshot = SessionSnapshot.of(state, "old-playback", bundle.contentRevision)!!
        val repository =
            object : SessionSnapshotRepository {
                override suspend fun latest() = SnapshotLoad.Found(snapshot)

                override suspend fun save(
                    snapshot: SessionSnapshot,
                    attempts: List<AttemptRecord>,
                ) = SnapshotSave.Saved

                override suspend fun clear(attempts: List<AttemptRecord>) = SnapshotSave.Saved
            }
        host = SessionHost(TickingClock(), Dispatchers.Default.limitedParallelism(1), repository, writer)
        show()
        val restored = CoroutineScope(Dispatchers.Default).async { host.awaitRestored() }
        rule.waitUntil(10000) { restored.isCompleted }
        rule.onNodeWithTag("solvePlayback:screen").assertDoesNotExist()
        rule.onNodeWithTag("nav:bar").assertExists()
        assertNotEquals(SessionKind.SOLVE_PLAYBACK, host.state.value.kind)
    }

    @Test
    @Config(qualifiers = "w800dp-h1000dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun darkRailEditorPaintsThemeSurface() {
        show(dark = true)
        click("nav:solve")
        click("solve:enterColors")
        val pixels = rule.onNodeWithTag("shell").captureToImage().toPixelMap()
        assertEquals(dev.cubentia.app.ui.theme.DarkTokens.surfaceBase, pixels[pixels.width - 1, pixels.height - 1])
    }

    @Test
    @Config(qualifiers = "w800dp-h1000dp")
    fun railSolveControlsSitBelowTheStatusBar() {
        show()
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
        rule.onNodeWithTag("nav:rail").assertExists()
        click("nav:solve")
        assertBelow("solve:enterColors", top)
        click("solve:enterColors")
        assertBelow("solve:close", top)
    }

    private fun assertBelow(
        tag: String,
        top: Int,
    ) {
        val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("$tag top ${bounds.top} under the $top px status bar", bounds.top >= top)
    }

    private fun assertPauseNoteMargin() {
        val note = rule.onNodeWithTag("solve:pauseNote").fetchSemanticsNode()
        org.junit.Assert.assertTrue(note.boundsInRoot.left >= 16 * note.layoutInfo.density.density)
    }

    private fun startPlaybackFromTheEditor(
        cube: CubeState,
        moves: List<FaceTurn>,
    ) {
        click("nav:solve")
        click("solve:enterColors")
        seedScrambledEditor(cube)
        solver = CubeSolver { SolveResult.Solved(moves) }
        click("editor:accept")
        rule.waitUntil(10000) { host.state.value.kind == SessionKind.SOLVE_PLAYBACK }
        rule.waitForIdle()
    }

    /** A longer Solver answer for R (L and R commute) than the Beginner method's single R'. */
    private val shortest =
        listOf(FaceTurn(Face.L, Turn.CLOCKWISE), FaceTurn(Face.R, Turn.COUNTERCLOCKWISE), FaceTurn(Face.L, Turn.COUNTERCLOCKWISE))

    private fun chip(style: dev.cubentia.app.ui.solve.SolutionStyle) = rule.onNodeWithTag("solutionStyle:" + style.name)

    @Test fun solutionStylesOpenOnShortestAndAMethodRestartsAtStepOneWithItsSegmentLabel() {
        val move = FaceTurn(Face.R, Turn.CLOCKWISE)
        val cube = CubeState.SOLVED.apply(move)
        show()
        startPlaybackFromTheEditor(cube, shortest)
        dev.cubentia.app.ui.solve.SolutionStyle.entries
            .forEach { chip(it).assertExists() }
        chip(dev.cubentia.app.ui.solve.SolutionStyle.SHORTEST).assertIsSelected()
        chip(dev.cubentia.app.ui.solve.SolutionStyle.BEGINNER).assertIsNotSelected()
        chip(dev.cubentia.app.ui.solve.SolutionStyle.SHORTEST).assertTextEquals("Shortest")
        // No progress yet: Beginner is the suggested method.
        rule
            .onNodeWithTag("solutionStyle:BEGINNER:suggested", useUnmergedTree = true)
            .assertTextEquals("Suggested")
        rule.onNodeWithTag("solutionStyle:TWO_LOOK:suggested", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("playback:segment").assertDoesNotExist()
        rule.onNodeWithTag("solutionStyle:message").assertDoesNotExist()

        click("playback:end")
        click("solutionStyle:BEGINNER")
        rule.waitUntil(10000) { host.state.value.solution != shortest }
        rule.waitForIdle()
        val beginner = host.state.value
        assertEquals(SessionKind.SOLVE_PLAYBACK, beginner.kind)
        assertEquals(cube, beginner.entry)
        org.junit.Assert.assertTrue(cube.apply(beginner.solution).isSolved)
        chip(dev.cubentia.app.ui.solve.SolutionStyle.BEGINNER).assertIsSelected()
        rule.onNodeWithTag("playback:counter").assertTextEquals("0 / " + beginner.solution.size)
        rule.onNodeWithTag("playback:segment").assertTextEquals("First cross")
        val segment = rule.onNodeWithTag("playback:segment").fetchSemanticsNode().boundsInRoot
        val counter = rule.onNodeWithTag("playback:counter").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(segment.bottom <= counter.top)

        click("solutionStyle:SHORTEST")
        rule.waitUntil(10000) { host.state.value.solution == shortest }
        rule.waitForIdle()
        chip(dev.cubentia.app.ui.solve.SolutionStyle.SHORTEST).assertIsSelected()
        rule.onNodeWithTag("playback:counter").assertTextEquals("0 / 3")
        rule.onNodeWithTag("playback:segment").assertDoesNotExist()
    }

    @Test fun aMethodStyleHighlightsThePhysicalQuickCheckAtItsSegmentEndsOnly() {
        // A scramble the Beginner method solves in more than one segment.
        val scramble =
            listOf(
                FaceTurn(Face.R, Turn.CLOCKWISE),
                FaceTurn(Face.U, Turn.CLOCKWISE),
                FaceTurn(Face.F, Turn.CLOCKWISE),
            )
        val cube = CubeState.SOLVED.apply(scramble)
        val shortest = scramble.asReversed().map { it.inverse() }
        show(
            cameraScan = FlagLevel.GA,
            quickCheck =
                dev.cubentia.app.scan
                    .QuickCheckCapture {},
        )
        startPlaybackFromTheEditor(cube, shortest)
        click("solutionStyle:BEGINNER")
        rule.waitUntil(10000) { host.state.value.solution != shortest }
        rule.waitForIdle()
        click("playback:followOnMyCube")
        rule.waitUntil(10000) { host.state.value.isPhysical }
        rule.waitForIdle()
        val size = host.state.value.solution.size
        val ends =
            dev.cubentia.app.ui.solve
                .segmentLabels(styles.state.value.segments, bundle)!!
                .also { assertEquals(host.state.value.solution, it.moves) }
                .ends

        fun assertHighlighted(highlighted: Boolean) {
            rule
                .onNodeWithTag("playback:quickCheck")
                .assertContentDescriptionEquals("Quick Check")
                .assert(SemanticsMatcher.expectValue(dev.cubentia.app.ui.lesson.QuickCheckHighlightedKey, highlighted))
        }
        assertHighlighted(false)
        var plainMidSegment = false
        var highlightedSegmentEnd = false
        for (position in 1..size) {
            val before = host.state.value.revision
            click("playback:done")
            rule.waitUntil(10000) { host.state.value.revision > before }
            rule.waitForIdle()
            rule.onNodeWithTag("playback:counter").assertTextEquals("$position / $size")
            val boundary = position in ends || position == size
            assertHighlighted(boundary)
            if (!boundary) plainMidSegment = true
            if (position in ends && position < size) highlightedSegmentEnd = true
        }
        org.junit.Assert.assertTrue("a plain mid-segment step in $ends of $size", plainMidSegment)
        org.junit.Assert.assertTrue("a highlighted segment end before the last step in $ends of $size", highlightedSegmentEnd)
    }

    @Test fun physicalStyleLocksAfterDoneUntilBackToStepOneAndAMethodKeepsFollowing() {
        val move = FaceTurn(Face.R, Turn.CLOCKWISE)
        val cube = CubeState.SOLVED.apply(move)
        show()
        startPlaybackFromTheEditor(cube, shortest)
        rule.onNodeWithText("Physical mode", useUnmergedTree = true).performTouchInput { click() }
        rule.waitUntil(10000) { host.state.value.isPhysical }
        rule.waitForIdle()
        chip(dev.cubentia.app.ui.solve.SolutionStyle.BEGINNER).assertIsEnabled()
        click("playback:done")
        rule.waitUntil(10000) { host.state.value.expected != cube }
        rule.waitForIdle()
        dev.cubentia.app.ui.solve.SolutionStyle.entries
            .forEach { chip(it).assertIsNotEnabled() }
        rule.onNodeWithTag("solutionStyle:message").assertTextEquals("Go back to step 1 to change style")
        click("playback:back")
        rule.waitUntil(10000) { host.state.value.expected == cube }
        rule.waitForIdle()
        chip(dev.cubentia.app.ui.solve.SolutionStyle.BEGINNER).assertIsEnabled()
        rule.onNodeWithTag("solutionStyle:message").assertDoesNotExist()

        click("solutionStyle:BEGINNER")
        rule.waitUntil(10000) { host.state.value.solution != shortest && host.state.value.isPhysical }
        rule.waitForIdle()
        val following = host.state.value
        assertEquals(cube, following.expected)
        org.junit.Assert.assertTrue(cube.apply(following.solution).isSolved)
        rule.onNodeWithTag("playback:counter").assertTextEquals("0 / " + following.solution.size)
        rule.onNodeWithTag("playback:segment").assertExists()
        rule.onNodeWithTag("playback:done").assertExists()

        // A *Done*, then a switch to the virtual cube: the real cube is still off the start, so the style stays locked.
        click("playback:done")
        rule.waitUntil(10000) { host.state.value.expected != cube }
        rule.onNodeWithText("Physical mode", useUnmergedTree = true).performTouchInput { click() }
        rule.waitUntil(10000) { !host.state.value.isPhysical }
        rule.waitForIdle()
        dev.cubentia.app.ui.solve.SolutionStyle.entries
            .forEach { chip(it).assertIsNotEnabled() }
        rule.onNodeWithTag("solutionStyle:message").assertTextEquals("Go back to step 1 to change style")
    }
}
