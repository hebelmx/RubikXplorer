package dev.cubentia.application.usecase

import dev.cubentia.application.port.AlgorithmStatusLoad
import dev.cubentia.application.port.AlgorithmStatusRecord
import dev.cubentia.application.port.AlgorithmStatusRepository
import dev.cubentia.application.port.AlgorithmStatusSave
import dev.cubentia.application.session.Effect
import dev.cubentia.domain.content.Algorithm
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmStatus
import dev.cubentia.domain.content.CaseId
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.cube.Axis
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.TimeSource

/** Stories 15.1 and 15.2 (FR-30, AD-21): the method-shaped solve as a timed, re-verified Effect on Default. */
class SolveByMethodTest {
    private val bundle by lazy {
        val result = ContentLoader.load(ContentSource.SHIPPED)
        check(result is ContentLoadResult.Loaded) { "the shipped content does not load: $result" }
        result.bundle
    }

    private fun turns(text: String): List<FaceTurn> = (Notation.parse(text) as NotationResult.Parsed).moves.map { it as FaceTurn }

    private val scrambled: CubeState = CubeState.SOLVED.apply(turns("R U F' L2 D B' R2 U' F L D2 B"))

    private fun effect(
        state: CubeState = scrambled,
        method: SolveMethod = SolveMethod.BEGINNER,
    ) = Effect.MethodSolve(effectId = 61, revision = 17, state = state, method = method)

    private val cross = StageId("stage-02-first-cross")

    @Test
    fun `solved - re-verified segments echo the effect's id and revision, off the caller's thread`() =
        runBlocking {
            val caller = Thread.currentThread()
            var worker: Thread? = null
            val spy =
                SolveByMethod(bundle, solver = { state, method, content, statuses, go ->
                    worker = Thread.currentThread()
                    MethodSolve.solve(state, method, content, statuses, go)
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
                SolveByMethod(bundle, deadlineMillis = 50, solver = { _, _, _, _, shouldContinue ->
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
            val stopping = SolveByMethod(bundle, solver = { _, _, _, _, _ -> MethodSolveResult.Stopped })
            assertEquals(MethodSolveOutcome.TimedOut, stopping(effect()).outcome)
        }

    @Test
    fun `failed - a result that does not solve, breaks its Stage, or is not found`() =
        runBlocking {
            fun returning(result: MethodSolveResult) = SolveByMethod(bundle, solver = { _, _, _, _, _ -> result })

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
            assertEquals(MethodSolveOutcome.Failed, SolveByMethod(bundle, solver = { _, _, _, _, _ -> error("boom") })(effect()).outcome)
        }

    @Test
    fun `cancelling the caller stops the solve and emits no completion`() =
        runBlocking {
            val running = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            var completed = false
            val spinning =
                SolveByMethod(bundle, solver = { _, _, _, _, shouldContinue ->
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

    // ---- Story 15.2: 2-look and full OLL + PLL ----

    private fun moves(text: String): List<Move> = (Notation.parse(text) as NotationResult.Parsed).moves

    private class FakeStatuses(
        private val load: AlgorithmStatusLoad,
    ) : AlgorithmStatusRepository {
        override fun statuses(): Flow<AlgorithmStatusLoad> = flowOf(load)

        override suspend fun append(record: AlgorithmStatusRecord): AlgorithmStatusSave = AlgorithmStatusSave.Saved
    }

    // First two layers solved, home hold; the last layer is OLL 45 (F R U R' U' F') undone with yellow up.
    private val oll45: CubeState =
        CubeState.SOLVED
            .reoriented(Orientation.of(Face.D, Face.F))
            .apply(moves("F R U R' U' F'").reversed().map { it.inverse() })
            .reoriented(Orientation.HOME)

    @Test
    fun `2-look and full - re-verified Case segments solve the input`() =
        runBlocking {
            listOf(SolveMethod.TWO_LOOK, SolveMethod.FULL_OLL_PLL).forEach { method ->
                listOf(scrambled, scrambled.reoriented(Orientation.of(Face.R, Face.F)), oll45).forEach { input ->
                    val completion = SolveByMethod(bundle)(effect(input, method))
                    assertEquals(61L, completion.effectId)
                    val solved = assertInstanceOf(MethodSolveOutcome.Solved::class.java, completion.outcome, "$method")
                    assertTrue(solved.segments.any { it.tag is SegmentTag.OfCase }, "$method")
                    assertTrue(input.apply(solved.segments.flatMap { it.moves }).isSolved, "$method")
                }
                assertEquals(MethodSolveOutcome.Solved(emptyList()), SolveByMethod(bundle)(effect(CubeState.SOLVED, method)).outcome)
            }
        }

    @Test
    fun `statuses come from the repository and none or a failed load is no preference`() =
        runBlocking {
            val learning = mapOf(AlgorithmId("alg-oll-45-a") to AlgorithmStatus.LEARNING)
            var seen: Map<AlgorithmId, AlgorithmStatus>? = null

            fun spying(repository: AlgorithmStatusRepository?) =
                SolveByMethod(bundle, algorithmStatuses = repository, solver = { state, method, content, statuses, go ->
                    seen = statuses
                    MethodSolve.solve(state, method, content, statuses, go)
                })
            val loaded = spying(FakeStatuses(AlgorithmStatusLoad.Loaded(learning)))(effect(oll45, SolveMethod.TWO_LOOK))
            assertEquals(learning, seen)
            assertInstanceOf(MethodSolveOutcome.Solved::class.java, loaded.outcome)
            spying(FakeStatuses(AlgorithmStatusLoad.Failed))(effect(oll45, SolveMethod.TWO_LOOK))
            assertEquals(emptyMap<AlgorithmId, AlgorithmStatus>(), seen)
            spying(null)(effect(oll45, SolveMethod.TWO_LOOK))
            assertEquals(emptyMap<AlgorithmId, AlgorithmStatus>(), seen)
        }

    @Test
    fun `failed - a Case segment with the wrong Case or a non-set Algorithm, or a Case in a beginner result`() =
        runBlocking {
            fun returning(result: MethodSolveResult) = SolveByMethod(bundle, solver = { _, _, _, _, _ -> result })

            val z2 = CubeRotation(Axis.Z, Turn.HALF)
            val good = MethodSolve.solve(oll45, SolveMethod.TWO_LOOK, bundle) as MethodSolveResult.Solved
            assertInstanceOf(MethodSolveOutcome.Solved::class.java, returning(good)(effect(oll45, SolveMethod.TWO_LOOK)).outcome)
            // OLL 45 played as one Case segment, tagged right: accepted when the rest solves; tagged wrong: Failed.
            val first = good.segments.first()
            assertEquals(SegmentTag.OfCase(CaseId("case-oll-45")), first.tag)
            val wrongTag =
                MethodSolveResult.Solved(listOf(first.copy(tag = SegmentTag.OfCase(CaseId("case-oll-44")))) + good.segments.drop(1))
            assertEquals(MethodSolveOutcome.Failed, returning(wrongTag)(effect(oll45, SolveMethod.TWO_LOOK)).outcome)
            // The same Moves solve the cube, but OLL 01 is not a 2-look Algorithm.
            val oll01 = "R U2 R2 F R F' U2 R' F R F'"
            val nonSetInput =
                CubeState.SOLVED
                    .reoriented(Orientation.of(Face.D, Face.F))
                    .apply(moves(oll01).reversed().map { it.inverse() })
                    .reoriented(Orientation.HOME)
            val nonSet =
                MethodSolveResult.Solved(
                    listOf(MethodSegment(SegmentTag.OfCase(CaseId("case-oll-01")), listOf(z2) + moves(oll01))),
                )
            assertEquals(MethodSolveOutcome.Failed, returning(nonSet)(effect(nonSetInput, SolveMethod.TWO_LOOK)).outcome)
            assertInstanceOf(
                MethodSolveOutcome.Solved::class.java,
                returning(nonSet)(effect(nonSetInput, SolveMethod.FULL_OLL_PLL)).outcome,
            )
            // A Case segment is never trusted in a beginner result, even one that conforms to 2-look.
            assertEquals(MethodSolveOutcome.Failed, returning(good)(effect(oll45, SolveMethod.BEGINNER)).outcome)
            // A lone AUF tagged with the method's last-layer Stage is accepted; tagged with the other method's, Failed.
            val auf = CubeState.SOLVED.apply(FaceTurn(Face.D, Turn.CLOCKWISE))
            val lone = MethodSolve.solve(auf, SolveMethod.TWO_LOOK, bundle) as MethodSolveResult.Solved
            assertInstanceOf(MethodSolveOutcome.Solved::class.java, returning(lone)(effect(auf, SolveMethod.TWO_LOOK)).outcome)
            assertEquals(MethodSolveOutcome.Failed, returning(lone)(effect(auf, SolveMethod.FULL_OLL_PLL)).outcome)
        }

    @Test
    fun `full - a preferred alternative Algorithm from the repository passes re-verification`() =
        runBlocking {
            val alternative = Algorithm(AlgorithmId("alg-oll-45-b"), CaseId("case-oll-45"), "y2 B L U L' U' B' y2")
            val content = bundle.copy(algorithms = bundle.algorithms + alternative)
            val statuses = FakeStatuses(AlgorithmStatusLoad.Loaded(mapOf(alternative.id to AlgorithmStatus.LEARNING)))
            val completion = SolveByMethod(content, algorithmStatuses = statuses)(effect(oll45, SolveMethod.FULL_OLL_PLL))
            val solved = assertInstanceOf(MethodSolveOutcome.Solved::class.java, completion.outcome)
            val body = moves(alternative.notation)
            assertTrue(
                solved.segments
                    .first()
                    .moves
                    .windowed(body.size)
                    .any { it == body },
                "${solved.segments.first().moves}",
            )
            assertTrue(oll45.apply(solved.segments.flatMap { it.moves }).isSolved)
        }
}
