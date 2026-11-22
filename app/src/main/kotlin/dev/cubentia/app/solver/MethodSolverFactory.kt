package dev.cubentia.app.solver

import dev.cubentia.application.usecase.SolveByMethod
import dev.cubentia.domain.content.ContentBundle
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.method.MethodSegment
import dev.cubentia.domain.method.SolveMethod
import javax.inject.Inject
import javax.inject.Singleton

/** Builds the method-shaped solve (FR-30, AD-21) for a loaded [ContentBundle]; the app's one reads the real Algorithm Statuses. */
fun interface MethodSolverFactory {
    fun create(bundle: ContentBundle): SolveByMethod
}

/**
 * Method solutions already built in this app process, by input cube and method (Story 15.3), so choosing a style again
 * never rebuilds it. In memory only: a `SOLVE_PLAYBACK` is never resumed after the app closes (AD-3). Use from the main thread.
 */
@Singleton
class MethodSolutionCache
    @Inject
    constructor() {
        private val built = HashMap<Pair<Facelets, SolveMethod>, List<MethodSegment>>()

        operator fun get(
            input: Facelets,
            method: SolveMethod,
        ): List<MethodSegment>? = built[input to method]

        operator fun set(
            input: Facelets,
            method: SolveMethod,
            segments: List<MethodSegment>,
        ) {
            built[input to method] = segments
        }
    }
