package dev.cubentia.solver

import dev.cubentia.application.port.SolveResult
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Cubies
import dev.cubentia.solver.benchmark.JvmHeapPoolSampler
import dev.cubentia.solver.benchmark.SolveCorpusHarness
import dev.cubentia.solver.benchmark.SolverWorkload
import dev.cubentia.solver.benchmark.randomLegalState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class TwoPhaseCubeSolverTest {
    private val tables get() = SolverWorkload.preparedTables

    @Test
    fun `mandatory ten thousand uniform legal states plus solved and deep Scrambles`() {
        val workload = SolverWorkload(10_000, 20_261_031L)
        val stats = SolveCorpusHarness(heapSampler = JvmHeapPoolSampler()).run(workload)
        println(stats.report(workload.name))
        assertEquals(10_101, stats.cases)
        assertEquals(0, stats.failures, stats.report(workload.name))
        assertTrue(stats.withinBudgetShare >= 0.95, stats.report(workload.name))
    }

    @Test
    fun `solved single turns and phase two states solve deterministically`() =
        runBlocking {
            val solver = TwoPhaseCubeSolver(tables)
            assertEquals(SolveResult.Solved(emptyList()), solver.solve(CubeState.SOLVED.cubies))
            val random = Random(33)
            val states =
                MoveTables.phase1Moves.map { CubeState.SOLVED.apply(it) } +
                    List(20) { CubeState.SOLVED.apply(List(30) { MoveTables.phase2Moves[random.nextInt(10)] }) }
            for (state in states) {
                val result = solver.solve(state.cubies)
                assertTrue(result is SolveResult.Solved)
                assertTrue(state.apply((result as SolveResult.Solved).moves).isSolved)
                assertEquals(result, solver.solve(state.cubies))
            }
        }

    @Test
    fun `illegal orientation and parity cubies return Invalid`() =
        runBlocking {
            // Reflect only in this defensive port test; domain construction remains validation-only.
            val constructor =
                Cubies::class.java.getDeclaredConstructor(
                    List::class.java,
                    List::class.java,
                    List::class.java,
                    List::class.java,
                )
            val solved = Cubies.SOLVED
            val bad =
                listOf(
                    constructor.newInstance(
                        solved.cornerPermutation,
                        listOf(1, 0, 0, 0, 0, 0, 0, 0),
                        solved.edgePermutation,
                        solved.edgeOrientation,
                    ),
                    constructor.newInstance(
                        solved.cornerPermutation,
                        solved.cornerOrientation,
                        solved.edgePermutation,
                        listOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                    ),
                    constructor.newInstance(
                        listOf(1, 0, 2, 3, 4, 5, 6, 7),
                        solved.cornerOrientation,
                        solved.edgePermutation,
                        solved.edgeOrientation,
                    ),
                )
            for (cubies in bad) assertEquals(SolveResult.Invalid, TwoPhaseCubeSolver(tables).solve(cubies))
        }

    @Test
    fun `deadline during active search returns only TimedOut`() =
        runBlocking {
            var now = 0L
            var visited = 0
            val solver =
                TwoPhaseCubeSolver(tables, { now }) {
                    if (++visited == 2) now = 3_000_000_000L
                }
            val state = randomLegalState(Random(3303))
            assertEquals(SolveResult.TimedOut, solver.solve(state.cubies))
            assertEquals(2, visited)
        }

    @Test
    fun `active search cancellation propagates promptly without a published result`() =
        runBlocking {
            var checkpoints = 0
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val solver =
                TwoPhaseCubeSolver(tables, System::nanoTime) {
                    if (++checkpoints == 2) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                }
            val state = randomLegalState(Random(3303))
            var published = false
            val job = async(Dispatchers.Default) { solver.solve(state.cubies).also { published = true } }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val start = System.nanoTime()
                job.cancel()
                release.countDown()
                assertThrows<CancellationException> { runBlocking { job.await() } }
                assertTrue(System.nanoTime() - start < 1_000_000_000L)
                assertTrue(!published)
            } finally {
                release.countDown()
                job.cancel()
            }
        }
}
