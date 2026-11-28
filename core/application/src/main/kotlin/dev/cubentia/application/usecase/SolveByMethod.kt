package dev.cubentia.application.usecase

import dev.cubentia.application.port.AlgorithmStatusLoad
import dev.cubentia.application.port.AlgorithmStatusRepository
import dev.cubentia.application.session.Effect
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.AlgorithmStatus
import dev.cubentia.domain.content.ContentBundle
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.method.MethodSegment
import dev.cubentia.domain.method.MethodSolve
import dev.cubentia.domain.method.MethodSolveResult
import dev.cubentia.domain.method.SegmentTag
import dev.cubentia.domain.method.SolveMethod
import dev.cubentia.domain.predicate.Predicate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A method-shaped solve: [MethodSolve.solve]'s shape, injectable for tests. */
fun interface MethodSolveFunction {
    fun solve(
        state: CubeState,
        method: SolveMethod,
        bundle: ContentBundle,
        statuses: Map<AlgorithmId, AlgorithmStatus>,
        shouldContinue: () -> Boolean,
    ): MethodSolveResult
}

/** Revision-tagged completion of an [Effect.MethodSolve]; never mutates a session. */
data class MethodSolveCompleted(
    val effectId: Long,
    val revision: Long,
    val outcome: MethodSolveOutcome,
)

/** Only re-verified segments may reach presentation. Failures never carry partial Moves. */
sealed interface MethodSolveOutcome {
    data class Solved(
        val segments: List<MethodSegment>,
    ) : MethodSolveOutcome

    data object TimedOut : MethodSolveOutcome

    data object Failed : MethodSolveOutcome
}

/**
 * Runs the method-shaped solve of an [Effect.MethodSolve] (FR-30, AD-21) on [Dispatchers.Default]
 * with a [deadlineMillis] timeout, asking the search to stop once the coroutine is no longer active.
 * Never calls the Solver. The Learner's Algorithm Statuses come from [algorithmStatuses] (none, or a
 * failed load, means no preference). Every result is re-verified before it is returned: the input with
 * all segments applied must be solved, each Stage segment's completion predicate and every earlier one
 * must hold where it ends, and the segments must keep to the method's shape ([MethodSolve.conforms]).
 */
class SolveByMethod(
    private val bundle: ContentBundle,
    val deadlineMillis: Long = DEADLINE_MILLIS,
    private val solver: MethodSolveFunction = MethodSolveFunction(MethodSolve::solve),
    private val algorithmStatuses: AlgorithmStatusRepository? = null,
) {
    suspend operator fun invoke(effect: Effect.MethodSolve): MethodSolveCompleted {
        val outcome =
            withTimeoutOrNull(deadlineMillis) {
                withContext(Dispatchers.Default) {
                    try {
                        val context = currentCoroutineContext()
                        val statuses = statuses()
                        when (val result = solver.solve(effect.state, effect.method, bundle, statuses) { context.isActive }) {
                            is MethodSolveResult.Solved -> {
                                if (verified(effect.state, effect.method, statuses, result.segments)) {
                                    MethodSolveOutcome.Solved(result.segments.toList())
                                } else {
                                    MethodSolveOutcome.Failed
                                }
                            }

                            MethodSolveResult.Stopped -> {
                                MethodSolveOutcome.TimedOut
                            }

                            MethodSolveResult.NotFound -> {
                                MethodSolveOutcome.Failed
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        MethodSolveOutcome.Failed
                    }
                }
            } ?: MethodSolveOutcome.TimedOut
        currentCoroutineContext().ensureActive()
        return MethodSolveCompleted(effect.effectId, effect.revision, outcome)
    }

    private suspend fun statuses(): Map<AlgorithmId, AlgorithmStatus> =
        (algorithmStatuses?.statuses()?.first() as? AlgorithmStatusLoad.Loaded)?.statuses ?: emptyMap()

    private suspend fun verified(
        input: CubeState,
        method: SolveMethod,
        statuses: Map<AlgorithmId, AlgorithmStatus>,
        segments: List<MethodSegment>,
    ): Boolean {
        if (!MethodSolve.conforms(segments, method, bundle, statuses)) return false
        val stages = if (method == SolveMethod.BEGINNER) bundle.stages else bundle.stages + bundle.twoLookStages
        val context = currentCoroutineContext()
        var state = input
        val reached = mutableListOf<Predicate>()
        for (segment in segments) {
            context.ensureActive()
            if (segment.moves.isEmpty()) return false
            state = state.apply(segment.moves)
            when (val tag = segment.tag) {
                is SegmentTag.OfStage -> {
                    reached +=
                        stages
                            .firstOrNull { it.id == tag.stageId }
                            ?.lessons
                            ?.lastOrNull()
                            ?.completion ?: return false
                }

                // A Case segment's shape was checked by conforms (never in a beginner result); it adds no Stage.
                is SegmentTag.OfCase -> {
                    Unit
                }
            }
            // This segment's Stage and every earlier segment's Stage must all hold where it ends.
            if (!reached.all { it.isSatisfied(state) }) return false
        }
        return state.isSolved
    }

    companion object {
        /** The solve's time cap, the same three seconds as the Solver's. */
        const val DEADLINE_MILLIS: Long = 3_000L
    }
}
