package dev.cubentia.application.usecase

import dev.cubentia.application.session.Effect
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A method-shaped solve: [MethodSolve.solve]'s shape, injectable for tests. */
fun interface MethodSolveFunction {
    fun solve(
        state: CubeState,
        method: SolveMethod,
        bundle: ContentBundle,
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
 * Never calls the Solver. Every result is re-verified before it is returned: the input with all
 * segments applied must be solved, and each Stage segment's completion predicate must hold where it ends.
 */
class SolveByMethod(
    private val bundle: ContentBundle,
    private val deadlineMillis: Long = DEADLINE_MILLIS,
    private val solver: MethodSolveFunction = MethodSolveFunction(MethodSolve::solve),
) {
    suspend operator fun invoke(effect: Effect.MethodSolve): MethodSolveCompleted {
        val outcome =
            withTimeoutOrNull(deadlineMillis) {
                withContext(Dispatchers.Default) {
                    try {
                        val context = currentCoroutineContext()
                        when (val result = solver.solve(effect.state, effect.method, bundle) { context.isActive }) {
                            is MethodSolveResult.Solved -> {
                                if (verified(effect.state, result.segments)) {
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

    private suspend fun verified(
        input: CubeState,
        segments: List<MethodSegment>,
    ): Boolean {
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
                        bundle.stages
                            .firstOrNull { it.id == tag.stageId }
                            ?.lessons
                            ?.lastOrNull()
                            ?.completion ?: return false
                }

                // Story 15.2 adds Case verification; until then no Case segment is trusted.
                is SegmentTag.OfCase -> {
                    return false
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
