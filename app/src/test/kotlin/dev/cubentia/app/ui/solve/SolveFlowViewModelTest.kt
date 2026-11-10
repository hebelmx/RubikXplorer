package dev.cubentia.app.ui.solve

import dev.cubentia.app.solver.SolverLoad
import dev.cubentia.app.solver.SolverSource
import dev.cubentia.application.port.CubeSolver
import dev.cubentia.application.port.SolveResult
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SolveFlowViewModelTest {
    @BeforeEach fun main() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach fun reset() {
        Dispatchers.resetMain()
    }

    private val move = FaceTurn(Face.R, Turn.CLOCKWISE)
    private val cube = CubeState.SOLVED.apply(move)

    private fun source(
        solver: CubeSolver,
        ready: Boolean = true,
        gate: CompletableDeferred<Unit>? = null,
    ) = object : SolverSource {
        override val isReady = ready

        override suspend fun load(): SolverLoad {
            gate?.await()
            return SolverLoad.Ready(solver)
        }
    }

    private suspend fun SolveFlowViewModel.editing(): SolvePhase.Editing =
        withTimeout(5000) { phase.first { it is SolvePhase.Editing } as SolvePhase.Editing }

    @Test fun preparationEndsBeforeVerifiedSolutionIsPublished() =
        runBlocking {
            val load = CompletableDeferred<Unit>()
            val solve = CompletableDeferred<Unit>()
            val enteredSolver = CompletableDeferred<Unit>()
            val result = CompletableDeferred<List<FaceTurn>>()
            val vm =
                SolveFlowViewModel(
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
            assertEquals(SolvePhase.Solving(true), vm.phase.value)
            load.complete(Unit)
            withTimeout(5000) { enteredSolver.await() }
            assertEquals(SolvePhase.Solving(false), vm.phase.value)
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
                val vm = SolveFlowViewModel(source(CubeSolver { result }))
                vm.accept(cube) { _, _ -> error("unexpected playback") }
                val editing = vm.editing()
                assertEquals(cube.toFacelets(), editing.initial)
                assertEquals(key, editing.error?.key)
            }
        }

    @Test fun loadFailurePreservesColors() =
        runBlocking {
            val vm =
                SolveFlowViewModel(
                    object : SolverSource {
                        override val isReady = false

                        override suspend fun load() = SolverLoad.Failed
                    },
                )
            vm.accept(cube) { _, _ -> error("unexpected playback") }
            assertEquals(cube.toFacelets(), vm.editing().initial)
            assertEquals("msg.solve.failed", vm.editing().error?.key)
        }

    @Test fun cancelDiscardsNoncooperativeLateResult() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val ended = CompletableDeferred<Unit>()
            var published = false
            val vm =
                SolveFlowViewModel(
                    source(
                        CubeSolver {
                            started.complete(Unit)
                            withContext(NonCancellable) { gate.await() }
                            ended.complete(Unit)
                            SolveResult.Solved(listOf(move.inverse()))
                        },
                    ),
                )
            vm.accept(cube) { _, _ -> published = true }
            withTimeout(5000) { started.await() }
            vm.cancel()
            assertEquals(SolvePhase.Editing(cube.toFacelets()), vm.phase.value)
            gate.complete(Unit)
            withTimeout(5000) { ended.await() }
            delay(100)
            assertFalse(published)
            assertEquals(SolvePhase.Editing(cube.toFacelets()), vm.phase.value)
        }

    @Test fun secondAcceptSupersedesFirst() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val ended = CompletableDeferred<Unit>()
            val result = CompletableDeferred<CubeState>()
            val calls =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val vm =
                SolveFlowViewModel(
                    source(
                        CubeSolver {
                            if (calls.incrementAndGet() == 1) {
                                started.complete(Unit)
                                withContext(NonCancellable) { gate.await() }
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
            gate.complete(Unit)
            withTimeout(5000) { ended.await() }
            delay(100)
            assertEquals(SolvePhase.Home, vm.phase.value)
        }

    @Test fun realDeadlineReturnsToEditor() =
        runBlocking {
            val dispatcher = UnconfinedTestDispatcher()
            Dispatchers.setMain(dispatcher)
            val started = CompletableDeferred<Unit>()
            val vm =
                SolveFlowViewModel(
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
            assertEquals(SolvePhase.Solving(false), vm.phase.value)
            dispatcher.scheduler.advanceTimeBy(2)
            dispatcher.scheduler.runCurrent()
            val editing = vm.editing()
            assertEquals(cube.toFacelets(), editing.initial)
            assertEquals("msg.solve.timed-out", editing.error?.key)
        }

    @Test fun cancellingPreparationKeepsColorsAndAllowsRetry() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val vm = SolveFlowViewModel(source(CubeSolver { SolveResult.Solved(emptyList()) }, false, gate))
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
