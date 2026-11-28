package dev.cubentia.app.solver

import androidx.lifecycle.viewModelScope
import dev.cubentia.app.ui.solve.SolveFlowViewModel
import dev.cubentia.app.ui.solve.SolvePhase
import dev.cubentia.application.port.CubeSolver
import dev.cubentia.application.port.SolveResult
import dev.cubentia.application.session.Effect
import dev.cubentia.application.usecase.MethodSolveFunction
import dev.cubentia.application.usecase.MethodSolveOutcome
import dev.cubentia.application.usecase.SolveByMethod
import dev.cubentia.domain.content.ContentBundle
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.method.MethodSolveResult
import dev.cubentia.domain.method.SolveMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeSource

/** Story 15.4 (AD-21): the one throwaway method-solve warm-up runs once, untimed, cancellable, and never reaches a session. */
@OptIn(ExperimentalCoroutinesApi::class)
class MethodSolveWarmUpTest {
    private val bundle: ContentBundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val scopes = mutableListOf<CoroutineScope>()
    private val logs: MutableList<Pair<String, Throwable?>> = Collections.synchronizedList(mutableListOf())
    private val log: (String, Throwable?) -> Unit = { message, failure -> logs += message to failure }

    @BeforeEach fun main() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterEach fun reset() {
        scopes.forEach { it.cancel() }
        Dispatchers.resetMain()
    }

    private fun scope(): CoroutineScope = CoroutineScope(SupervisorJob()).also { scopes += it }

    private fun warmUp(
        solver: MethodSolveFunction,
        content: suspend () -> ContentBundle? = { bundle },
    ) = MethodSolveWarmUp(bundle = content, scope = scope(), solver = solver, log = log)

    @Test fun runsExactlyOnceUntimedOnTheFixedStateWithTwoLook() =
        runBlocking {
            val calls = AtomicInteger()
            val seen = CompletableDeferred<Pair<CubeState, SolveMethod>>()
            val warmUp =
                warmUp(
                    MethodSolveFunction { state, method, content, statuses, _ ->
                        calls.incrementAndGet()
                        assertSame(bundle, content)
                        assertTrue(statuses.isEmpty())
                        seen.complete(state to method)
                        // Longer than the 3 s release deadline: the warm-up has none.
                        Thread.sleep(3_100)
                        MethodSolveResult.Solved(emptyList())
                    },
                )
            val started = TimeSource.Monotonic.markNow()
            val job = checkNotNull(warmUp.start())
            assertNull(warmUp.start())
            withTimeout(10_000) { job.join() }
            assertNull(warmUp.start())
            assertTrue(started.elapsedNow().inWholeMilliseconds >= 3_000)
            assertTrue(job.isCompleted)
            assertFalse(job.isCancelled)
            assertEquals(1, calls.get())
            assertEquals(MethodSolveWarmUp.WARM_UP_STATE to SolveMethod.TWO_LOOK, seen.await())
            assertEquals(SolveMethod.TWO_LOOK, MethodSolveWarmUp.WARM_UP_METHOD)
            assertEquals(25, MethodSolveWarmUp.WARM_UP_SCRAMBLE.split(' ').size)
            assertFalse(MethodSolveWarmUp.WARM_UP_STATE.isSolved)
            assertTrue(logs.isEmpty())
        }

    @Test fun cancellingStopsTheSearchAndCompletesCancelledWithoutThrowing() =
        runBlocking {
            val running = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val warmUp =
                warmUp(
                    MethodSolveFunction { _, _, _, _, shouldContinue ->
                        running.complete(Unit)
                        @Suppress("ControlFlowWithEmptyBody")
                        while (shouldContinue()) {
                        }
                        stopped.complete(Unit)
                        MethodSolveResult.Stopped
                    },
                )
            val job = checkNotNull(warmUp.start())
            withTimeout(5_000) { running.await() }
            warmUp.cancel()
            withTimeout(5_000) { job.join() }
            assertTrue(stopped.isCompleted)
            assertTrue(job.isCancelled)
            assertEquals(1, logs.size)
            assertNull(warmUp.start())
        }

    @Test fun anExceptionANotFoundOrMissingContentIsLoggedNotThrown() =
        runBlocking {
            val failing = warmUp(MethodSolveFunction { _, _, _, _, _ -> error("boom") })
            withTimeout(5_000) { checkNotNull(failing.start()).join() }
            assertInstanceOf(IllegalStateException::class.java, logs.single().second)

            logs.clear()
            val notFound = warmUp(MethodSolveFunction { _, _, _, _, _ -> MethodSolveResult.NotFound })
            val notFoundJob = checkNotNull(notFound.start())
            withTimeout(5_000) { notFoundJob.join() }
            assertFalse(notFoundJob.isCancelled)
            assertEquals(1, logs.size)

            logs.clear()
            val searched = AtomicInteger()
            val noContent =
                warmUp(
                    MethodSolveFunction { _, _, _, _, _ ->
                        searched.incrementAndGet()
                        MethodSolveResult.NotFound
                    },
                    content = { null },
                )
            withTimeout(5_000) { checkNotNull(noContent.start()).join() }
            assertEquals(0, searched.get())
            assertEquals(1, logs.size)
        }

    @Test fun aRealSolveIsNotQueuedBehindARunningWarmUp() =
        runBlocking {
            val release = CountDownLatch(1)
            val blocked = CompletableDeferred<Unit>()
            val warmUp =
                warmUp(
                    MethodSolveFunction { _, _, _, _, _ ->
                        blocked.complete(Unit)
                        release.await(30, TimeUnit.SECONDS)
                        MethodSolveResult.Solved(emptyList())
                    },
                )
            val job = checkNotNull(warmUp.start())
            try {
                withTimeout(5_000) { blocked.await() }
                val real =
                    SolveByMethod(
                        bundle,
                        deadlineMillis = 2_000,
                        solver = MethodSolveFunction { _, _, _, _, _ -> MethodSolveResult.Solved(emptyList()) },
                    )
                val completed = withTimeout(5_000) { real(Effect.MethodSolve(3, 5, CubeState.SOLVED, SolveMethod.TWO_LOOK)) }
                assertEquals(MethodSolveOutcome.Solved(emptyList()), completed.outcome)
                assertTrue(job.isActive, "the warm-up is still running while the real solve completed")
            } finally {
                release.countDown()
            }
            withTimeout(5_000) { job.join() }
        }

    @Test fun theWarmUpNeverReachesASession() =
        runBlocking {
            val move = FaceTurn(Face.R, Turn.CLOCKWISE)
            val cube = CubeState.SOLVED.apply(move)
            val warmed = CompletableDeferred<Unit>()
            val warmUp =
                warmUp(
                    MethodSolveFunction { _, _, _, _, _ ->
                        warmed.complete(Unit)
                        MethodSolveResult.Solved(emptyList())
                    },
                )
            val source =
                object : SolverSource {
                    override val isReady = true

                    override suspend fun load(onGenerating: () -> Unit) =
                        SolverLoad.Ready(CubeSolver { SolveResult.Solved(listOf(move.inverse())) })
                }
            val vm = SolveFlowViewModel(source, SolveDeadline.RELEASE, warmUp)
            val vmJob: Job = vm.viewModelScope.coroutineContext.job
            try {
                val published = Collections.synchronizedList(mutableListOf<Pair<CubeState, List<FaceTurn>>>())
                vm.accept(cube) { state, moves -> published += state to moves }
                withTimeout(5_000) { vm.phase.first { it == SolvePhase.Home } }
                withTimeout(5_000) { warmed.await() }
                delay(100)
                // Only the SolveCube solution is published, once; the warm-up already ran, so it never starts again.
                assertEquals(listOf(cube to listOf(move.inverse())), published.toList())
                assertNull(warmUp.start())
            } finally {
                vmJob.cancel()
            }
        }
}
