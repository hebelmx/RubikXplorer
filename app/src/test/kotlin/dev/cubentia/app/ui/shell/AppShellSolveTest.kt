package dev.cubentia.app.ui.shell

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.cubentia.app.content.LessonCatalog
import dev.cubentia.app.data.SerialWriter
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
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
    private var ready = true
    private var generating = false
    private val loadGate = CompletableDeferred<Unit>()
    private val flow =
        SolveFlowViewModel(
            object : SolverSource {
                override val isReady get() = ready

                override suspend fun load(onGenerating: () -> Unit): SolverLoad {
                    if (!allowSolverLoad) throw AssertionError("Already-solved confirmation must not load the solver")
                    if (generating) onGenerating()
                    if (!ready) loadGate.await()
                    return SolverLoad.Ready(solver)
                }
            },
        )

    @After fun close() {
        rule.runOnIdle { flow.closeEditor() }
        runBlocking { writer.close() }
        Dispatchers.resetMain()
    }

    private var back: OnBackPressedDispatcher? = null

    private var view: android.view.View? = null

    private fun show(
        dark: Boolean = false,
        scan: (() -> Unit)? = null,
        cameraScan: dev.cubentia.application.port.FlagLevel = dev.cubentia.application.port.FlagLevel.OFF,
        quickCheck: dev.cubentia.app.scan.QuickCheckCapture? = null,
    ) {
        val catalog = LessonCatalog.of(ContentLoadResult.Loaded(bundle))
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
                    testFlags(cameraScan = cameraScan),
                    solveFlow = flow,
                    onScan = scan,
                    quickCheck = quickCheck,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun click(tag: String) {
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
    }

    @Test fun betaMakesManualEntryPrimaryAndExplainsTheScan() {
        show(scan = {}, cameraScan = dev.cubentia.application.port.FlagLevel.BETA)
        click("nav:solve")
        assertActionOrder("solve:enterColors", "solve:scan")
        rule.onNodeWithTag("solve:scan").assertContentDescriptionEquals("Scan my cube, BETA")
        rule.onNodeWithTag("solve:scan:beta", useUnmergedTree = true).assertTextEquals("BETA")
        rule.onNodeWithTag("solve:scan:betaNote").assertTextEquals("May misread colors in poor light. Manual entry always works.")
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
        click("editor:accept")
        assertAlreadySolved()
        click("solve:alreadySolved:again")
        rule.onNodeWithTag("solve:enterColors").assertExists()
        rule.onNodeWithTag("solve:scan").assertExists()
        click("solve:scan")
        click("editor:accept")
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

    @Test fun leavingSolveCancelsARunningSolve() {
        ready = false
        var published = false
        show()
        click("nav:solve")
        rule.runOnIdle { flow.accept(CubeState.SOLVED) { _, _ -> published = true } }
        rule.waitForIdle()
        rule.onNodeWithTag("solve:preparing").assertTextEquals(
            "Preparing the solver. The first solve after the app starts takes longer. Later solves are faster.",
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

    @Test fun physicalPlaybackQuickCheckAndScanAllReachTheCapture() {
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
        click("editor:accept")
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
            "Preparing the solver. The first solve after the app starts takes longer. Later solves are faster.",
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
}
