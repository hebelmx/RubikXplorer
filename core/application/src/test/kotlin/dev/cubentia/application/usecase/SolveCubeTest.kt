package dev.cubentia.application.usecase

import dev.cubentia.application.port.CubeSolver
import dev.cubentia.application.port.SolveResult
import dev.cubentia.application.session.Effect
import dev.cubentia.domain.cube.Axis
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.cube.ValidationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.TimeSource

class SolveCubeTest {
    private fun request(state: CubeState = CubeState.SOLVED) = Effect.SolveCube(51, 73, state)

    private fun orientedSolved(): List<CubeState> {
        val states = mutableMapOf(Orientation.HOME to CubeState.SOLVED)
        while (states.size < Orientation.ALL.size) {
            states.values.toList().forEach { state ->
                Axis.entries.forEach { axis ->
                    val next = state.apply(CubeRotation(axis, Turn.CLOCKWISE))
                    states[next.orientation] = next
                }
            }
        }
        return states.values.toList()
    }

    @Test
    fun `every orientation face and amount maps back to the exact input and echoes metadata`() =
        runBlocking<Unit> {
            orientedSolved().forEach { solved ->
                Face.entries.forEach { face ->
                    Turn.entries.forEach { turn ->
                        val move = FaceTurn(face, turn)
                        val original = solved.apply(move)
                        val validated = CubeValidator.validate(original.toFacelets())
                        val state = assertInstanceOf(ValidationResult.Valid::class.java, validated).state
                        val caller = Thread.currentThread()
                        val handler =
                            SolveCube(
                                CubeSolver { cubies ->
                                    assertSame(state.cubies, cubies)
                                    assertNotSame(caller, Thread.currentThread())
                                    SolveResult.Solved(listOf(FaceTurn(state.orientation.homeFaceAt(face), turn.inverse())))
                                },
                            )
                        val completion = handler(request(state))
                        assertEquals(51L, completion.effectId)
                        assertEquals(73L, completion.revision)
                        val outcome = assertInstanceOf(SolveCubeOutcome.Solved::class.java, completion.outcome)
                        assertEquals(listOf(move.inverse()), outcome.moves)
                        assertTrue(state.apply(outcome.moves).isSolved)
                    }
                }
            }
        }

    @Test
    fun `empty solution accepted only for solved input including rotated states`() =
        runBlocking<Unit> {
            val handler = SolveCube(CubeSolver { SolveResult.Solved(emptyList()) })
            orientedSolved().forEach { state ->
                assertEquals(SolveCubeOutcome.Solved(emptyList()), handler(request(state)).outcome)
            }
            val unsolved = CubeState.SOLVED.apply(FaceTurn(Face.R, Turn.HALF))
            assertInstanceOf(SolveCubeOutcome.Failed::class.java, handler(request(unsolved)).outcome)
        }

    @Test
    fun `wrong answer and adapter failures return localized recovery without partial moves`() =
        runBlocking<Unit> {
            val failures = listOf(SolveResult.Invalid, SolveResult.Failed, SolveResult.Solved(listOf(FaceTurn(Face.U, Turn.CLOCKWISE))))
            failures.forEach { result ->
                val failed = assertInstanceOf(SolveCubeOutcome.Failed::class.java, SolveCube(CubeSolver { result })(request()).outcome)
                assertRecoveryExists(failed.recovery.key)
            }
            val exceptional = SolveCube(CubeSolver { error("adapter unavailable") })(request())
            val failed = assertInstanceOf(SolveCubeOutcome.Failed::class.java, exceptional.outcome)
            assertRecoveryExists(failed.recovery.key)
            val adapterTimeout = SolveCube(CubeSolver { SolveResult.TimedOut })(request())
            val timedOut = assertInstanceOf(SolveCubeOutcome.TimedOut::class.java, adapterTimeout.outcome)
            assertRecoveryExists(timedOut.recovery.key)
        }

    @Test
    fun `independent real three second deadline cancels adapter work`() =
        runBlocking<Unit> {
            var stopped = false
            val started = TimeSource.Monotonic.markNow()
            val completion =
                withTimeout(6_000) {
                    SolveCube(
                        CubeSolver {
                            try {
                                awaitCancellation()
                            } finally {
                                stopped = true
                            }
                        },
                    )(request())
                }
            assertTrue(started.elapsedNow().inWholeMilliseconds >= 2_900)
            assertTrue(stopped)
            assertInstanceOf(SolveCubeOutcome.TimedOut::class.java, completion.outcome)
            assertEquals(51L, completion.effectId)
            assertEquals(73L, completion.revision)
        }

    @Test
    fun `caller cancellation emits no completion and adapter cancellation retains identity`() =
        runBlocking<Unit> {
            val entered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            var completed = false
            val job =
                async {
                    SolveCube(
                        CubeSolver {
                            entered.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                stopped.complete(Unit)
                            }
                        },
                    )(request())
                    completed = true
                }
            entered.await()
            job.cancelAndJoin()
            assertTrue(stopped.isCompleted)
            assertFalse(completed)
            val cancellation = CancellationException("adapter cancelled")
            try {
                SolveCube(CubeSolver { throw cancellation })(request())
                throw AssertionError("Cancellation was swallowed")
            } catch (actual: CancellationException) {
                // Debug stack-trace recovery may copy the exception while preserving its cause.
                assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === cancellation })
            }
        }

    @Test
    fun `noncommuting solution preserves every move in order across all orientations`() =
        runBlocking<Unit> {
            val scramble =
                listOf(
                    FaceTurn(Face.R, Turn.CLOCKWISE),
                    FaceTurn(Face.U, Turn.COUNTERCLOCKWISE),
                    FaceTurn(Face.F, Turn.HALF),
                    FaceTurn(Face.L, Turn.CLOCKWISE),
                )
            val inverse = scramble.asReversed().map { it.inverse() }
            orientedSolved().forEach { solved ->
                val state = solved.apply(scramble)
                val answer = inverse.map { FaceTurn(state.orientation.homeFaceAt(it.face), it.turn) }
                val completion = SolveCube(CubeSolver { SolveResult.Solved(answer) })(request(state))
                val outcome = assertInstanceOf(SolveCubeOutcome.Solved::class.java, completion.outcome)
                assertEquals(inverse, outcome.moves)
                assertTrue(state.apply(outcome.moves).isSolved)
            }
        }

    @Test
    fun `cancellation during sequence verification stops consuming adapter moves`() =
        runBlocking<Unit> {
            var reads = 0
            var completed = false
            try {
                SolveCube(
                    CubeSolver {
                        val context = currentCoroutineContext()
                        val moves =
                            object : AbstractList<FaceTurn>() {
                                override val size = 1_000

                                override fun get(index: Int): FaceTurn {
                                    reads++
                                    if (index == 10) context.cancel(CancellationException("cancel during verification"))
                                    return FaceTurn(Face.U, Turn.HALF)
                                }
                            }
                        SolveResult.Solved(moves)
                    },
                )(request())
                completed = true
            } catch (_: CancellationException) {
                // The cancellation must escape without publishing any result.
            }
            assertFalse(completed)
            assertEquals(11, reads)
        }

    @Test
    fun `nested adapter deadline propagates cancellation instead of handler timeout`() =
        runBlocking<Unit> {
            var completed = false
            val started = TimeSource.Monotonic.markNow()
            try {
                SolveCube(
                    CubeSolver {
                        withTimeout(25) { awaitCancellation() }
                    },
                )(request())
                completed = true
            } catch (_: TimeoutCancellationException) {
                assertTrue(started.elapsedNow().inWholeMilliseconds < 2_000)
            }
            assertFalse(completed)
        }

    private fun assertRecoveryExists(key: String) {
        val bundle = checkNotNull(javaClass.getResourceAsStream("/content/strings/en.json")).bufferedReader().use { it.readText() }
        assertTrue(bundle.contains("\"$key\":"))
    }
}
