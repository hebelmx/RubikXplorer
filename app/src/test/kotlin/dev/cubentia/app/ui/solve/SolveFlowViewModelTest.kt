package dev.cubentia.app.ui.solve

import androidx.lifecycle.viewModelScope
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.app.solver.SolverLoad
import dev.cubentia.app.solver.SolverSource
import dev.cubentia.application.port.CubeSolver
import dev.cubentia.application.port.SolveResult
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.text.Text
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SolveFlowViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scopes = mutableListOf<Job>()
    private val lateResultGate = CompletableDeferred<Unit>()

    @BeforeEach fun main() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach fun reset() {
        // A published phase or a cancellation request can precede the last use of Main.
        runBlocking {
            scopes.forEach { it.cancel() }
            lateResultGate.complete(Unit)
            scopes.joinAll()
        }
        Dispatchers.resetMain()
    }

    private fun viewModel(source: SolverSource) = SolveFlowViewModel(source).also { scopes += it.viewModelScope.coroutineContext.job }

    private val move = FaceTurn(Face.R, Turn.CLOCKWISE)
    private val cube = CubeState.SOLVED.apply(move)

    private fun source(
        solver: CubeSolver,
        ready: Boolean = true,
        gate: CompletableDeferred<Unit>? = null,
    ) = object : SolverSource {
        override val isReady = ready

        override suspend fun load(onGenerating: () -> Unit): SolverLoad {
            gate?.await()
            return SolverLoad.Ready(solver)
        }
    }

    private suspend fun SolveFlowViewModel.editing(): SolvePhase.Editing =
        withTimeout(5000) { phase.first { it is SolvePhase.Editing } as SolvePhase.Editing }

    @Test fun confirmingSolvedCubeSkipsSolverAndClearsScanInEitherOrientation() {
        val vm =
            viewModel(
                object : SolverSource {
                    override val isReady: Boolean get() = throw AssertionError("Solver readiness must not be checked")

                    override suspend fun load(onGenerating: () -> Unit): SolverLoad = throw AssertionError("Solver must not load")
                },
            )
        for (orientation in listOf(Orientation.HOME, Orientation.of(Face.U, Face.R))) {
            val solved = CubeState.SOLVED.reoriented(orientation)
            vm.onScanOutcome(ScanOutcome.Accepted(solved))
            assertTrue(vm.isAcceptedScan())
            vm.confirm(checkNotNull(vm.takePendingScan())) { _, _ -> throw AssertionError("Playback must not start") }
            assertEquals(SolvePhase.AlreadySolved, vm.phase.value)
            assertFalse(vm.isAcceptedScan())
            vm.closeEditor()
            assertEquals(SolvePhase.Home, vm.phase.value)
            vm.enterColors()
            assertEquals(SolvePhase.Editing(CubeState.SOLVED.toFacelets()), vm.phase.value)
        }
    }

    @Test fun confirmingScrambledCubePublishesTheSolution() =
        runBlocking {
            val moves = listOf(move.inverse())
            val vm = viewModel(source(CubeSolver { SolveResult.Solved(moves) }))
            val result = CompletableDeferred<Pair<CubeState, List<FaceTurn>>>()
            vm.onScanOutcome(ScanOutcome.Accepted(cube))
            vm.confirm(checkNotNull(vm.takePendingScan())) { state, solution -> result.complete(state to solution) }
            assertEquals(cube to moves, withTimeout(5000) { result.await() })
            withTimeout(5000) { vm.phase.first { it == SolvePhase.Home } }
            assertFalse(vm.isAcceptedScan())
        }

    @Test fun acceptedScanStaysPendingUntilTakenOnce() {
        val vm = viewModel(source(CubeSolver { error("Scan must not solve before confirmation") }))
        vm.onScanOutcome(ScanOutcome.Cancelled)
        assertEquals(SolvePhase.Home, vm.phase.value)
        assertNull(vm.pendingScan.value)
        vm.onScanOutcome(ScanOutcome.Accepted(cube))
        assertEquals(SolvePhase.Home, vm.phase.value)
        assertTrue(vm.isAcceptedScan())
        assertEquals(cube, vm.pendingScan.value)
        assertEquals(cube, vm.takePendingScan())
        assertNull(vm.takePendingScan())
        assertNull(vm.pendingScan.value)
    }

    @Test fun scanFallbackAndManualEntryOpenTheEditorWithExistingNotes() {
        val vm = viewModel(source(CubeSolver { error("Fallback must not solve") }))
        for ((openEditor, note) in listOf(
            vm::enterColors to null,
            vm::scanDenied to Text("msg.solve.scan-camera-denied"),
            { vm.onScanOutcome(ScanOutcome.FellBackToEditor) } to Text("msg.solve.scan-fell-back"),
        )) {
            vm.onScanOutcome(ScanOutcome.Accepted(cube))
            vm.takePendingScan()
            openEditor()
            assertFalse(vm.isAcceptedScan())
            assertEquals(SolvePhase.Editing(CubeState.SOLVED.toFacelets(), note = note), vm.phase.value)
            vm.closeEditor()
        }
    }

    @Test fun scanSolveRecoveryUsesTheHomeFrameAndKeepsTheBaselineOnRetry() =
        runBlocking {
            val held = cube.reoriented(Orientation.of(Face.U, Face.R))
            for ((failure, recovery) in listOf(
                null to null,
                SolveResult.TimedOut to Text("msg.solve.timed-out"),
                SolveResult.Failed to Text("msg.solve.failed"),
            )) {
                var solveResult = failure
                val enteredSolver = CompletableDeferred<Unit>()
                val vm =
                    viewModel(
                        source(
                            CubeSolver {
                                enteredSolver.complete(Unit)
                                solveResult ?: awaitCancellation()
                            },
                        ),
                    )
                vm.onScanOutcome(ScanOutcome.Accepted(held))
                vm.confirm(checkNotNull(vm.takePendingScan())) { _, _ -> error("unexpected playback") }
                if (failure == null) {
                    withTimeout(5000) { enteredSolver.await() }
                    vm.cancel()
                }
                assertEquals(SolvePhase.Editing(cube.toFacelets(), recovery), vm.editing())
                assertTrue(vm.isAcceptedScan())

                // Retry both the original colors and a corrected entry across the recovery cases.
                val retryMove = if (failure == SolveResult.Failed) FaceTurn(Face.U, Turn.CLOCKWISE) else move
                val retry = CubeState.SOLVED.apply(retryMove)
                solveResult = SolveResult.Solved(listOf(retryMove.inverse()))
                val fromScan = CompletableDeferred<Boolean>()
                vm.confirm(retry) { _, _ -> fromScan.complete(vm.isAcceptedScan()) }
                assertTrue(withTimeout(5000) { fromScan.await() })
                withTimeout(5000) { vm.phase.first { it == SolvePhase.Home } }
                assertFalse(vm.isAcceptedScan())
            }
        }

    @Test fun closingTheFallbackEditorOrLeavingSolveClearsTheScanBaseline() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            for (leaveTab in listOf(false, true)) {
                val vm = viewModel(source(CubeSolver { SolveResult.Solved(listOf(move.inverse())) }, gate = gate))
                vm.onScanOutcome(ScanOutcome.Accepted(cube))
                vm.confirm(checkNotNull(vm.takePendingScan())) { _, _ -> error("unexpected playback") }
                vm.cancel()
                assertTrue(vm.isAcceptedScan())
                if (leaveTab) vm.leaveSolveTab() else vm.closeEditor()
                assertFalse(vm.isAcceptedScan())

                vm.onScanOutcome(ScanOutcome.Accepted(cube))
                if (leaveTab) vm.leaveSolveTab() else vm.closeEditor()
                assertNull(vm.takePendingScan())
                vm.enterColors()
                assertFalse(vm.isAcceptedScan())
            }
        }

    @Test fun fallbackAndDenialAfterASolveUseTheFreshEditorState() =
        runBlocking {
            val vm = viewModel(source(CubeSolver { SolveResult.Solved(listOf(move.inverse())) }))
            vm.accept(cube) { _, _ -> }
            withTimeout(5000) { vm.phase.first { it == SolvePhase.Home } }
            vm.onScanOutcome(dev.cubentia.app.scan.ScanOutcome.FellBackToEditor)
            assertEquals(CubeState.SOLVED.toFacelets(), (vm.phase.value as SolvePhase.Editing).initial)
            vm.closeEditor()
            vm.scanDenied()
            assertEquals(CubeState.SOLVED.toFacelets(), (vm.phase.value as SolvePhase.Editing).initial)
        }

    @Test fun preparationEndsBeforeVerifiedSolutionIsPublished() =
        runBlocking {
            val load = CompletableDeferred<Unit>()
            val solve = CompletableDeferred<Unit>()
            val enteredSolver = CompletableDeferred<Unit>()
            val result = CompletableDeferred<List<FaceTurn>>()
            val vm =
                viewModel(
                    source(
                        CubeSolver {
                            enteredSolver.complete(Unit)
                            solve.await()
                            SolveResult.Solved(listOf(move.inverse()))
                        },
                        false,
                        load,
                    ),
                )
            vm.accept(cube) { state, moves ->
                assertEquals(cube, state)
                result.complete(moves)
            }
            assertEquals(SolvePhase.Solving(Preparation.WARMING_UP), vm.phase.value)
            load.complete(Unit)
            withTimeout(5000) { enteredSolver.await() }
            assertEquals(SolvePhase.Solving(Preparation.NONE), vm.phase.value)
            solve.complete(Unit)
            assertEquals(listOf(move.inverse()), withTimeout(5000) { result.await() })
            withTimeout(5000) { vm.phase.first { it == SolvePhase.Home } }
            Unit
        }

    @Test fun timeoutFailureAndInvalidSolutionKeepColors() =
        runBlocking {
            for ((result, key) in listOf(
                SolveResult.TimedOut to "msg.solve.timed-out",
                SolveResult.Failed to "msg.solve.failed",
                SolveResult.Solved(emptyList()) to "msg.solve.failed",
            )) {
                val vm = viewModel(source(CubeSolver { result }))
                vm.accept(cube) { _, _ -> error("unexpected playback") }
                val editing = vm.editing()
                assertEquals(cube.toFacelets(), editing.initial)
                assertEquals(key, editing.error?.key)
            }
        }

    @Test fun generationSignalShowsFirstUseAndStopsAfterReady() =
        runBlocking {
            val loadGate = CompletableDeferred<Unit>()
            val solving = CompletableDeferred<Unit>()
            val vm =
                viewModel(
                    object : SolverSource {
                        override val isReady = false

                        override suspend fun load(onGenerating: () -> Unit): SolverLoad {
                            onGenerating()
                            loadGate.await()
                            return SolverLoad.Ready(
                                CubeSolver {
                                    solving.complete(Unit)
                                    awaitCancellation()
                                },
                            )
                        }
                    },
                )
            vm.accept(cube) { _, _ -> error("unexpected playback") }
            withTimeout(5000) { vm.phase.first { it == SolvePhase.Solving(Preparation.FIRST_USE) } }
            loadGate.complete(Unit)
            withTimeout(5000) { solving.await() }
            assertEquals(SolvePhase.Solving(Preparation.NONE), vm.phase.value)
            vm.cancel()
            assertEquals(SolvePhase.Editing(cube.toFacelets()), vm.phase.value)
        }

    @Test fun staleGenerationSignalsCannotChangeCancelledOrSupersedingAttempt() =
        runBlocking {
            val signals = kotlinx.coroutines.channels.Channel<() -> Unit>(2)
            val vm =
                viewModel(
                    object : SolverSource {
                        override val isReady = false

                        override suspend fun load(onGenerating: () -> Unit): SolverLoad {
                            signals.send(onGenerating)
                            awaitCancellation()
                        }
                    },
                )
            vm.accept(cube) { _, _ -> error("unexpected playback") }
            val stale = withTimeout(5000) { signals.receive() }
            vm.cancel()
            stale()
            assertEquals(SolvePhase.Editing(cube.toFacelets()), vm.phase.value)
            vm.accept(CubeState.SOLVED) { _, _ -> error("unexpected playback") }
            val current = withTimeout(5000) { signals.receive() }
            stale()
            assertEquals(SolvePhase.Solving(Preparation.WARMING_UP), vm.phase.value)
            current()
            assertEquals(SolvePhase.Solving(Preparation.FIRST_USE), vm.phase.value)
            vm.cancel()
        }

    @Test fun loadFailurePreservesColors() =
        runBlocking {
            val vm =
                viewModel(
                    object : SolverSource {
                        override val isReady = false

                        override suspend fun load(onGenerating: () -> Unit) = SolverLoad.Failed
                    },
                )
            vm.accept(cube) { _, _ -> error("unexpected playback") }
            assertEquals(cube.toFacelets(), vm.editing().initial)
            assertEquals("msg.solve.failed", vm.editing().error?.key)
        }

    @Test fun cancelDiscardsNoncooperativeLateResult() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val ended = CompletableDeferred<Unit>()
            var published = false
            val vm =
                viewModel(
                    source(
                        CubeSolver {
                            started.complete(Unit)
                            withContext(NonCancellable) { lateResultGate.await() }
                            ended.complete(Unit)
                            SolveResult.Solved(listOf(move.inverse()))
                        },
                    ),
                )
            vm.accept(cube) { _, _ -> published = true }
            withTimeout(5000) { started.await() }
            vm.cancel()
            assertEquals(SolvePhase.Editing(cube.toFacelets()), vm.phase.value)
            lateResultGate.complete(Unit)
            withTimeout(5000) { ended.await() }
            delay(100)
            assertFalse(published)
            assertEquals(SolvePhase.Editing(cube.toFacelets()), vm.phase.value)
        }

    @Test fun secondAcceptSupersedesFirst() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val ended = CompletableDeferred<Unit>()
            val result = CompletableDeferred<CubeState>()
            val calls =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val vm =
                viewModel(
                    source(
                        CubeSolver {
                            if (calls.incrementAndGet() == 1) {
                                started.complete(Unit)
                                withContext(NonCancellable) { lateResultGate.await() }
                                ended.complete(Unit)
                                SolveResult.Solved(listOf(move.inverse()))
                            } else {
                                SolveResult.Solved(emptyList())
                            }
                        },
                    ),
                )
            vm.accept(cube) { _, _ -> error("stale playback") }
            withTimeout(5000) { started.await() }
            vm.accept(CubeState.SOLVED) { state, _ -> result.complete(state) }
            assertEquals(CubeState.SOLVED, withTimeout(5000) { result.await() })
            lateResultGate.complete(Unit)
            withTimeout(5000) { ended.await() }
            delay(100)
            assertEquals(SolvePhase.Home, vm.phase.value)
        }

    @Test fun realDeadlineReturnsToEditor() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val vm =
                viewModel(
                    source(
                        CubeSolver {
                            started.complete(Unit)
                            awaitCancellation()
                        },
                    ),
                )
            vm.accept(cube) { _, _ -> error("unexpected playback") }
            withTimeout(5000) { started.await() }
            dispatcher.scheduler.advanceTimeBy(2999)
            dispatcher.scheduler.runCurrent()
            assertEquals(SolvePhase.Solving(Preparation.NONE), vm.phase.value)
            dispatcher.scheduler.advanceTimeBy(2)
            dispatcher.scheduler.runCurrent()
            val editing = vm.editing()
            assertEquals(cube.toFacelets(), editing.initial)
            assertEquals("msg.solve.timed-out", editing.error?.key)
        }

    @Test fun cancellingPreparationKeepsColorsAndAllowsRetry() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val vm = viewModel(source(CubeSolver { SolveResult.Solved(emptyList()) }, false, gate))
            vm.accept(CubeState.SOLVED) { _, _ -> error("cancelled playback") }
            vm.cancel()
            assertEquals(SolvePhase.Editing(CubeState.SOLVED.toFacelets()), vm.phase.value)
            gate.complete(Unit)
            val published = CompletableDeferred<Unit>()
            vm.accept(CubeState.SOLVED) { _, moves ->
                assertTrue(moves.isEmpty())
                published.complete(Unit)
            }
            withTimeout(5000) { published.await() }
            withTimeout(5000) { vm.phase.first { it == SolvePhase.Home } }
            vm.enterColors()
            vm.closeEditor()
            assertEquals(SolvePhase.Home, vm.phase.value)
        }
}
