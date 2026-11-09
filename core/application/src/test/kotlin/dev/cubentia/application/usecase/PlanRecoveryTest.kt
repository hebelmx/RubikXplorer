package dev.cubentia.application.usecase

import dev.cubentia.application.session.Effect
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.content.FaceletString
import dev.cubentia.domain.content.FaceletStringResult
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.tutor.RecoveryPlan
import dev.cubentia.domain.tutor.RecoveryPlanner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant
import kotlin.time.TimeSource

/** Story 4.5 (AD-8): the Guided recovery search as a timed Effect on Default, never thrown. */
class PlanRecoveryTest {
    private val bundle by lazy {
        val result = ContentLoader.load(ContentSource.SHIPPED)
        check(result is ContentLoadResult.Loaded) { "the shipped content does not load: $result" }
        result.bundle
    }

    private val lesson by lazy { bundle.stages.flatMap { it.lessons }.single { it.id.value == "lesson-03-01" } }

    private val entry: CubeState by lazy {
        val facelets = (FaceletString.decode(lesson.entryState) as FaceletStringResult.Decoded).facelets
        (CubeValidator.validate(facelets) as ValidationResult.Valid).state
    }

    private fun moves(notation: String): List<Move> = (Notation.parse(notation) as NotationResult.Parsed).moves

    private fun effect(
        played: String,
        maxDepth: Int = bundle.tutorConfig.recoveryMaxDepth,
    ) = Effect.PlanRecovery(
        effectId = 41,
        revision = 9,
        state = entry.apply(moves(played)),
        goal = RecoveryPlanner.goal(lesson, listOf(entry, entry)),
        maxDepth = maxDepth,
        moveSet = bundle.tutorConfig.moves,
    )

    private val at = Instant.fromEpochMilliseconds(5_000)

    @Test
    fun `found - the planner's shortest plan, computed off the caller's thread`() =
        runBlocking {
            assertEquals(moves("R'"), PlanRecovery()(effect("R")))
            val caller = Thread.currentThread()
            var worker: Thread? = null
            val spy =
                PlanRecovery({ from, goal, depth, set, go ->
                    worker = Thread.currentThread()
                    RecoveryPlanner.plan(from, goal, depth, set, go)
                })
            val planned = checkNotNull(spy(effect("R U F")))
            assertTrue(planned.size in 1..3)
            assertTrue(effect("R U F").goal.isMet(entry.apply(moves("R U F")).apply(planned)))
            assertNotSame(caller, worker)
        }

    @Test
    fun `not found - no plan within the bound`() =
        runBlocking {
            assertNull(PlanRecovery()(effect("R U F", maxDepth = 1)))
        }

    @Test
    fun `timeout - a search that spins until told to stop gives no plan, never throws`() =
        runBlocking {
            val stopped = CompletableDeferred<Unit>()
            val spinning =
                PlanRecovery(
                    planner = { _, _, _, _, shouldContinue ->
                        @Suppress("ControlFlowWithEmptyBody")
                        while (shouldContinue()) {
                        }
                        stopped.complete(Unit)
                        RecoveryPlan.Stopped
                    },
                    deadlineMillis = 50,
                )
            val started = TimeSource.Monotonic.markNow()
            assertNull(spinning(effect("R")))
            assertTrue(started.elapsedNow().inWholeMilliseconds < 2_000)
            stopped.await()
        }

    @Test
    fun `a planner failure is no plan`() =
        runBlocking {
            assertNull(PlanRecovery({ _, _, _, _, _ -> error("boom") })(effect("R")))
            assertNull(PlanRecovery({ _, _, _, _, _ -> RecoveryPlan.NotFound })(effect("R")))
        }

    @Test
    fun `cancelling the caller stops the search`() =
        runBlocking {
            val running = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val spinning =
                PlanRecovery({ _, _, _, _, shouldContinue ->
                    running.complete(Unit)
                    @Suppress("ControlFlowWithEmptyBody")
                    while (shouldContinue()) {
                    }
                    stopped.complete(Unit)
                    RecoveryPlan.Stopped
                })
            val job = async { spinning(effect("R")) }
            running.await()
            job.cancelAndJoin()
            stopped.await()
        }

    @Test
    fun `the result echoes the effect's id and revision`() {
        val e = effect("R")
        assertEquals(SessionEvent.RecoveryPlanned(41, 9, moves("R'"), at), e.result(moves("R'"), at))
        assertEquals(SessionEvent.RecoveryPlanFailed(41, 9, at), e.result(null, at))
        assertEquals(PlanRecovery.DEADLINE_MILLIS, 3_000L)
    }
}
