package dev.cubentia.app.ui.shell

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.cubentia.app.content.LessonCatalog
import dev.cubentia.app.data.SerialWriter
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
    private var ready = true
    private var generating = false
    private val loadGate = CompletableDeferred<Unit>()
    private val flow =
        SolveFlowViewModel(
            object : SolverSource {
                override val isReady get() = ready

                override suspend fun load(onGenerating: () -> Unit): SolverLoad {
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

    private fun show() {
        val catalog = LessonCatalog.of(ContentLoadResult.Loaded(bundle))
        val freeCube = FreeCubeViewModel()
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CubentiaTheme {
                AppShell(host, catalog, freeCube, false, testFlags(), solveFlow = flow)
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

    @Test fun solvedEditorEntryOffersLearnAndEndsPlayback() {
        show()
        click("nav:solve")
        rule.onNodeWithTag("solve:enterColors").assertExists()
        rule.onNodeWithText("Scan my cube").assertDoesNotExist()
        click("solve:enterColors")
        rule.onNodeWithTag("solve:pauseNote").assertDoesNotExist()
        click("editor:accept")
        rule.waitUntil(10000) { host.state.value.kind == SessionKind.SOLVE_PLAYBACK }
        rule.onNodeWithTag("solvePlayback:screen").assertExists()
        rule.onNodeWithTag("nav:bar").assertDoesNotExist()
        rule.onNodeWithTag("solve:learnOffer").assertTextEquals("Want to learn how to do this yourself?")
        click("solve:learnOffer")
        rule.waitUntil(10000) { host.state.value.kind != SessionKind.SOLVE_PLAYBACK }
        rule.onNodeWithTag("nav:learn").assertExists()
        rule.onNodeWithTag("solve:enterColors").assertDoesNotExist()
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
        ready = false
        show()
        click("nav:solve")
        click("solve:enterColors")
        rule.onNodeWithTag("solve:pauseNote").assertTextEquals("Starting a solve pauses your lesson.")
        click("editor:accept")
        rule.onNodeWithTag("solve:preparing").assertTextEquals(
            "Preparing the solver. The first solve after the app starts takes longer. Later solves are faster.",
        )
        rule.onNodeWithTag("solve:progress").assertExists()
        assertEquals(lesson, host.state.value.lesson)
        click("solve:cancel")
        rule.onNodeWithTag("solve:error").assertDoesNotExist()
        assertEquals(lesson, host.state.value.lesson)
        ready = true
        solve(CubeState.SOLVED)
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
}
