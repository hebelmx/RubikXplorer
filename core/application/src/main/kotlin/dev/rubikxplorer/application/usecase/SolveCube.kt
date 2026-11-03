package dev.rubikxplorer.application.usecase

import dev.rubikxplorer.application.port.CubeSolver
import dev.rubikxplorer.application.port.SolveResult
import dev.rubikxplorer.application.session.Effect
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.text.Text
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

/** Runs the cubies-only adapter on Default, with an independent three-second deadline. */
class SolveCube(
    private val solver: CubeSolver,
) {
    suspend operator fun invoke(effect: Effect.SolveCube): SolveCubeCompleted {
        val outcome =
            withTimeoutOrNull(DEADLINE_MILLIS) {
                withContext(Dispatchers.Default) {
                    try {
                        when (val result = solver.solve(effect.state.cubies)) {
                            is SolveResult.Solved -> {
                                val context = currentCoroutineContext()
                                val moves = mutableListOf<FaceTurn>()
                                var verified = effect.state
                                for (move in result.moves) {
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

    private companion object {
        const val DEADLINE_MILLIS = 3_000L
    }
}
