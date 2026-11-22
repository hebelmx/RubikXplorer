package dev.cubentia.application.usecase

import dev.cubentia.application.session.Effect
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.method.MethodSegment
import dev.cubentia.domain.method.MethodSolve
import dev.cubentia.domain.method.MethodSolveResult
import dev.cubentia.domain.method.SegmentTag
import dev.cubentia.domain.method.SolveMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.TimeSource

/** Story 15.1 (FR-30, AD-21): the method-shaped solve as a timed, re-verified Effect on Default. */
class SolveByMethodTest {
    private val bundle by lazy {
        val result = ContentLoader.load(ContentSource.SHIPPED)
        check(result is ContentLoadResult.Loaded) { "the shipped content does not load: $result" }
        result.bundle
    }

    private fun turns(text: String): List<FaceTurn> = (Notation.parse(text) as NotationResult.Parsed).moves.map { it as FaceTurn }

    private val scrambled: CubeState = CubeState.SOLVED.apply(turns("R U F' L2 D B' R2 U' F L D2 B"))

    private fun effect(state: CubeState = scrambled) =
        Effect.MethodSolve(effectId = 61, revision = 17, state = state, method = SolveMethod.BEGINNER)

    private val cross = StageId("stage-02-first-cross")

    @Test
    fun `solved - re-verified segments echo the effect's id and revision, off the caller's thread`() =
        runBlocking {
            val caller = Thread.currentThread()
            var worker: Thread? = null
            val spy =
                SolveByMethod(bundle, solver = { state, method, content, go ->
                    worker = Thread.currentThread()
                    MethodSolve.solve(state, method, content, go)
                })
            val held = scrambled.reoriented(Orientation.of(Face.D, Face.F))
            val completion = spy(effect(held))
            assertEquals(61L, completion.effectId)
            assertEquals(17L, completion.revision)
            val solved = assertInstanceOf(MethodSolveOutcome.Solved::class.java, completion.outcome)
            assertTrue(solved.segments.isNotEmpty())
            assertTrue(held.apply(solved.segments.flatMap { it.moves }).isSolved)
            assertNotSame(caller, worker)
            assertEquals(3_000L, SolveByMethod.DEADLINE_MILLIS)
        }

    @Test
    fun `a solved cube is an empty solution`() =
        runBlocking {
            assertEquals(MethodSolveOutcome.Solved(emptyList()), SolveByMethod(bundle)(effect(CubeState.SOLVED)).outcome)
        }

    @Test
    fun `timeout - a tiny deadline or a stopping solve gives TimedOut and no Moves`() =
        runBlocking {
            val spinning =
                SolveByMethod(bundle, deadlineMillis = 50, solver = { _, _, _, shouldContinue ->
                    @Suppress("ControlFlowWithEmptyBody")
                    while (shouldContinue()) {
                    }
                    MethodSolveResult.Stopped
                })
            val started = TimeSource.Monotonic.markNow()
            val completion = spinning(effect())
            assertEquals(MethodSolveOutcome.TimedOut, completion.outcome)
            assertEquals(61L, completion.effectId)
            assertEquals(17L, completion.revision)
            assertTrue(started.elapsedNow().inWholeMilliseconds < 2_000)
            val stopping = SolveByMethod(bundle, solver = { _, _, _, _ -> MethodSolveResult.Stopped })
            assertEquals(MethodSolveOutcome.TimedOut, stopping(effect()).outcome)
        }

    @Test
    fun `failed - a result that does not solve, breaks its Stage, or is not found`() =
        runBlocking {
            fun returning(result: MethodSolveResult) = SolveByMethod(bundle, solver = { _, _, _, _ -> result })

            val notSolving = MethodSolveResult.Solved(listOf(MethodSegment(SegmentTag.OfStage(cross), turns("D"))))
            assertEquals(MethodSolveOutcome.Failed, returning(notSolving)(effect()).outcome)
            // A cross segment that ends with the cross is accepted; one that ends without it fails.
            val input = CubeState.SOLVED.apply(turns("R"))
            val validCross = MethodSolveResult.Solved(listOf(MethodSegment(SegmentTag.OfStage(cross), turns("R'"))))
            assertInstanceOf(MethodSolveOutcome.Solved::class.java, returning(validCross)(effect(input)).outcome)
            // Every Stage predicate holds at its boundary, but the cube ends unsolved.
            val endsUnsolved = MethodSolveResult.Solved(listOf(MethodSegment(SegmentTag.OfStage(cross), turns("R' D"))))
            assertEquals(MethodSolveOutcome.Failed, returning(endsUnsolved)(effect(input)).outcome)
            // Stage 5's segment breaks the cross (its own predicate still holds) and Stage 6 repairs it.
            val breaksEarlier =
                MethodSolveResult.Solved(
                    listOf(
                        MethodSegment(SegmentTag.OfStage(cross), turns("R'")),
                        MethodSegment(SegmentTag.OfStage(StageId("stage-05-last-layer-orientation")), turns("U")),
                        MethodSegment(SegmentTag.OfStage(StageId("stage-06-last-layer-completion")), turns("U'")),
                    ),
                )
            assertEquals(MethodSolveOutcome.Failed, returning(breaksEarlier)(effect(input)).outcome)
            val broken =
                MethodSolveResult.Solved(
                    listOf(
                        MethodSegment(SegmentTag.OfStage(cross), turns("U")),
                        MethodSegment(SegmentTag.OfStage(StageId("stage-06-last-layer-completion")), turns("U' R'")),
                    ),
                )
            assertEquals(MethodSolveOutcome.Failed, returning(broken)(effect(input)).outcome)
            val unknownStage = MethodSolveResult.Solved(listOf(MethodSegment(SegmentTag.OfStage(StageId("stage-09-none")), turns("R'"))))
            assertEquals(MethodSolveOutcome.Failed, returning(unknownStage)(effect(input)).outcome)
            val empty = MethodSolveResult.Solved(listOf(MethodSegment(SegmentTag.OfStage(cross), emptyList())))
            assertEquals(MethodSolveOutcome.Failed, returning(empty)(effect(CubeState.SOLVED)).outcome)
            val case =
                MethodSolveResult.Solved(
                    listOf(
                        MethodSegment(
                            SegmentTag.OfCase(
                                dev.cubentia.domain.content
                                    .CaseId("case-oll-45"),
                            ),
                            listOf(FaceTurn(Face.R, Turn.COUNTERCLOCKWISE)),
                        ),
                    ),
                )
            assertEquals(MethodSolveOutcome.Failed, returning(case)(effect(input)).outcome)
            assertEquals(MethodSolveOutcome.Failed, returning(MethodSolveResult.NotFound)(effect()).outcome)
        }

    @Test
    fun `failed - a thrown exception`() =
        runBlocking {
            assertEquals(MethodSolveOutcome.Failed, SolveByMethod(bundle, solver = { _, _, _, _ -> error("boom") })(effect()).outcome)
        }

    @Test
    fun `cancelling the caller stops the solve and emits no completion`() =
        runBlocking {
            val running = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            var completed = false
            val spinning =
                SolveByMethod(bundle, solver = { _, _, _, shouldContinue ->
                    running.complete(Unit)
                    @Suppress("ControlFlowWithEmptyBody")
                    while (shouldContinue()) {
                    }
                    stopped.complete(Unit)
                    MethodSolveResult.Stopped
                })
            val job =
                async {
                    spinning(effect())
                    completed = true
                }
            running.await()
            job.cancelAndJoin()
            stopped.await()
            assertFalse(completed)
        }
}
