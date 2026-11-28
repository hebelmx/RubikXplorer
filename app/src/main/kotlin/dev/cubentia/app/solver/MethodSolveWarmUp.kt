package dev.cubentia.app.solver

import dev.cubentia.application.usecase.MethodSolveFunction
import dev.cubentia.domain.content.ContentBundle
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.method.MethodSolve
import dev.cubentia.domain.method.MethodSolveResult
import dev.cubentia.domain.method.SolveMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One throwaway method solve per process (AD-21, Story 15.4), so the first real `SolveByMethod` does not pay the one-time
 * class loading and JIT inside its deadline. [start] launches it at most once, in its own coroutine on
 * [Dispatchers.Default] in [scope], untimed and cancellable ([cancel]); it shares no lock or dispatcher with
 * `SolveByMethod`, so a real solve runs alongside it and never waits for it. The result is discarded: it is never
 * dispatched to a session and never cached. A missing [bundle], a `NotFound`, an exception or a cancellation only
 * reaches [log] (the app logs it in debuggable builds only).
 */
class MethodSolveWarmUp(
    private val bundle: suspend () -> ContentBundle?,
    private val scope: CoroutineScope,
    private val solver: MethodSolveFunction = MethodSolveFunction(MethodSolve::solve),
    private val log: (String, Throwable?) -> Unit,
) {
    private val started = AtomicBoolean(false)

    @Volatile private var job: Job? = null

    /** Launches the warm-up the first time it is called and returns its job; every later call does nothing and returns null. */
    fun start(): Job? {
        if (!started.compareAndSet(false, true)) return null
        val launched =
            scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                try {
                    val content = bundle()
                    if (content == null) {
                        log("warm-up-skipped:content-not-loaded", null)
                        return@launch
                    }
                    val result = solver.solve(WARM_UP_STATE, WARM_UP_METHOD, content, emptyMap()) { isActive }
                    ensureActive()
                    if (result !is MethodSolveResult.Solved) log("warm-up-result:$result", null)
                } catch (cancelled: CancellationException) {
                    log("warm-up-cancelled", cancelled)
                    throw cancelled
                } catch (failure: Exception) {
                    log("warm-up-failed", failure)
                }
            }
        job = launched
        launched.start()
        return launched
    }

    /** Cancels a running warm-up; its search stops at the next `shouldContinue` check. */
    fun cancel() {
        job?.cancel()
    }

    companion object {
        /** The fixed 25-move scramble the warm-up solves, in Notation (one token per Move). */
        val WARM_UP_SCRAMBLE: String =
            listOf(
                "R",
                "U",
                "F'",
                "L2",
                "D",
                "B'",
                "R2",
                "U'",
                "F",
                "L",
                "D2",
                "B",
                "U2",
                "R'",
                "F2",
                "L'",
                "U",
                "D'",
                "B2",
                "R",
                "F'",
                "D",
                "L2",
                "U'",
                "B",
            ).joinToString(" ")

        val WARM_UP_METHOD: SolveMethod = SolveMethod.TWO_LOOK

        /** [CubeState.SOLVED] after [WARM_UP_SCRAMBLE]. */
        val WARM_UP_STATE: CubeState =
            CubeState.SOLVED.apply((Notation.parse(WARM_UP_SCRAMBLE) as NotationResult.Parsed).moves)
    }
}
