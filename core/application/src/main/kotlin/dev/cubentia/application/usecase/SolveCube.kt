package dev.cubentia.application.usecase

import dev.cubentia.application.port.CubeSolver
import dev.cubentia.application.port.SolveResult
import dev.cubentia.application.session.Effect
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.mergeSameFaceTurns
import dev.cubentia.domain.text.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Revision-tagged completion for the future solve host; never mutates a session. */
data class SolveCubeCompleted(
    val effectId: Long,
    val revision: Long,
    val outcome: SolveCubeOutcome,
)

/** Only independently verified Moves may reach presentation. Failures never carry partial Moves. */
sealed interface SolveCubeOutcome {
    data class Solved(
        val moves: List<FaceTurn>,
    ) : SolveCubeOutcome

    data class TimedOut(
        val recovery: Text = Text("msg.solve.timed-out"),
    ) : SolveCubeOutcome

    data class Failed(
        val recovery: Text = Text("msg.solve.failed"),
    ) : SolveCubeOutcome
}

/**
 * Runs the cubies-only adapter on Default, with an independent [deadlineMillis] deadline (AD-8: [DEADLINE_MILLIS] in
 * release and benchmark builds; `:app` passes 5 s in debuggable builds, chosen once per process).
 */
class SolveCube(
    private val solver: CubeSolver,
    val deadlineMillis: Long = DEADLINE_MILLIS,
) {
    suspend operator fun invoke(effect: Effect.SolveCube): SolveCubeCompleted {
        val outcome =
            withTimeoutOrNull(deadlineMillis) {
                withContext(Dispatchers.Default) {
                    try {
                        when (val result = solver.solve(effect.state.cubies)) {
                            is SolveResult.Solved -> {
                                val context = currentCoroutineContext()
                                val moves = mutableListOf<FaceTurn>()
                                var verified = effect.state
                                val merged = result.moves.mergeSameFaceTurns { context.ensureActive() }
                                for (move in merged) {
                                    context.ensureActive()
                                    val mapped =
                                        FaceTurn(
                                            Face.entries.first { effect.state.orientation.homeFaceAt(it) == move.face },
                                            move.turn,
                                        )
                                    verified = verified.apply(mapped)
                                    moves += mapped
                                }
                                context.ensureActive()
                                if (verified.isSolved) SolveCubeOutcome.Solved(moves.toList()) else SolveCubeOutcome.Failed()
                            }

                            SolveResult.TimedOut -> {
                                SolveCubeOutcome.TimedOut()
                            }

                            SolveResult.Invalid, SolveResult.Failed -> {
                                SolveCubeOutcome.Failed()
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        SolveCubeOutcome.Failed()
                    }
                }
            } ?: SolveCubeOutcome.TimedOut()
        currentCoroutineContext().ensureActive()
        return SolveCubeCompleted(effect.effectId, effect.revision, outcome)
    }

    companion object {
        /** The release and benchmark deadline, the same three seconds as the Solver's search cap. */
        const val DEADLINE_MILLIS: Long = 3_000L
    }
}
