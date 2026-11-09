package dev.cubentia.application.usecase

import dev.cubentia.application.session.Effect
import dev.cubentia.application.session.SessionEvent
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.tutor.RecoveryGoal
import dev.cubentia.domain.tutor.RecoveryPlan
import dev.cubentia.domain.tutor.RecoveryPlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Instant

/** A Guided recovery search: [RecoveryPlanner.plan]'s shape, injectable for tests. */
fun interface RecoveryPlannerFunction {
    fun plan(
        from: CubeState,
        goal: RecoveryGoal,
        maxDepth: Int,
        moveSet: List<Move>,
        shouldContinue: () -> Boolean,
    ): RecoveryPlan
}

/**
 * Runs the Guided recovery search of an [Effect.PlanRecovery] (FR-12, AD-8) on [Dispatchers.Default]
 * with a [deadlineMillis] timeout (3 s, the Solver cap). The search is asked to stop as soon as the
 * coroutine is no longer active. Never calls the Solver and never mutates a session: the caller
 * dispatches [result].
 */
class PlanRecovery(
    private val planner: RecoveryPlannerFunction = RecoveryPlannerFunction(RecoveryPlanner::plan),
    private val deadlineMillis: Long = DEADLINE_MILLIS,
) {
    /** The planned Moves, or null when there is no plan: none in the bound, timed out, stopped or failed. */
    suspend operator fun invoke(effect: Effect.PlanRecovery): List<Move>? {
        val moves =
            withTimeoutOrNull(deadlineMillis) {
                withContext(Dispatchers.Default) {
                    try {
                        val context = currentCoroutineContext()
                        when (val plan = planner.plan(effect.state, effect.goal, effect.maxDepth, effect.moveSet) { context.isActive }) {
                            is RecoveryPlan.Found -> plan.moves.toList()
                            RecoveryPlan.NotFound, RecoveryPlan.Stopped -> null
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        currentCoroutineContext().ensureActive()
        return moves
    }

    companion object {
        /** The search's time cap, the same three seconds as the Solver's. */
        const val DEADLINE_MILLIS: Long = 3_000L
    }
}

/** The result event for this effect: [SessionEvent.RecoveryPlanned] with [moves], or [SessionEvent.RecoveryPlanFailed] when null. */
fun Effect.PlanRecovery.result(
    moves: List<Move>?,
    at: Instant,
): SessionEvent =
    if (moves == null) {
        SessionEvent.RecoveryPlanFailed(effectId, revision, at)
    } else {
        SessionEvent.RecoveryPlanned(effectId, revision, moves, at)
    }
