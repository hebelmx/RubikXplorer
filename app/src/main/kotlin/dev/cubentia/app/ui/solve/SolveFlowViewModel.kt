package dev.cubentia.app.ui.solve

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.cubentia.app.solver.SolverLoad
import dev.cubentia.app.solver.SolverSource
import dev.cubentia.application.session.Effect
import dev.cubentia.application.usecase.SolveCube
import dev.cubentia.application.usecase.SolveCubeOutcome
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.text.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class Preparation { NONE, WARMING_UP, FIRST_USE }

sealed interface SolvePhase {
    data object Home : SolvePhase

    data class Editing(
        val initial: Facelets,
        val error: Text? = null,
    ) : SolvePhase

    data class Solving(
        val preparation: Preparation,
    ) : SolvePhase
}

@HiltViewModel
class SolveFlowViewModel
    @Inject
    constructor(
        private val source: SolverSource,
    ) : ViewModel() {
        private val mutablePhase = MutableStateFlow<SolvePhase>(SolvePhase.Home)
        val phase = mutablePhase.asStateFlow()
        private var accepted = CubeState.SOLVED.toFacelets()
        private var revision = 0L
        private var job: Job? = null

        fun enterColors() {
            mutablePhase.value = SolvePhase.Editing(accepted)
        }

        fun closeEditor() {
            revision++
            job?.cancel()
            mutablePhase.value = SolvePhase.Home
        }

        /** Cancels only a running solve, keeping the entered colors; does nothing in other phases. */
        fun cancelSolving() {
            if (mutablePhase.value is SolvePhase.Solving) cancel()
        }

        fun cancel() {
            revision++
            job?.cancel()
            mutablePhase.value = SolvePhase.Editing(accepted)
        }

        fun accept(
            state: CubeState,
            onSolved: suspend (CubeState, List<FaceTurn>) -> Unit,
        ) {
            val attempt = ++revision
            job?.cancel()
            accepted = state.toFacelets()
            mutablePhase.value = SolvePhase.Solving(if (source.isReady) Preparation.NONE else Preparation.WARMING_UP)
            job =
                viewModelScope.launch {
                    val outcome =
                        try {
                            when (
                                val loaded =
                                    withContext(Dispatchers.Default) {
                                        source.load {
                                            viewModelScope.launch {
                                                if (
                                                    attempt == revision &&
                                                    mutablePhase.value == SolvePhase.Solving(Preparation.WARMING_UP)
                                                ) {
                                                    mutablePhase.value = SolvePhase.Solving(Preparation.FIRST_USE)
                                                }
                                            }
                                        }
                                    }
                            ) {
                                SolverLoad.Failed -> {
                                    SolveCubeOutcome.Failed()
                                }

                                is SolverLoad.Ready -> {
                                    currentCoroutineContext().ensureActive()
                                    if (attempt != revision) return@launch
                                    mutablePhase.value = SolvePhase.Solving(Preparation.NONE)
                                    SolveCube(loaded.solver)(Effect.SolveCube(attempt, attempt, state)).outcome
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            SolveCubeOutcome.Failed()
                        }
                    currentCoroutineContext().ensureActive()
                    if (attempt != revision) return@launch
                    when (outcome) {
                        is SolveCubeOutcome.Solved -> {
                            onSolved(state, outcome.moves)
                            if (attempt == revision) mutablePhase.value = SolvePhase.Home
                        }

                        is SolveCubeOutcome.TimedOut -> {
                            mutablePhase.value = SolvePhase.Editing(accepted, outcome.recovery)
                        }

                        is SolveCubeOutcome.Failed -> {
                            mutablePhase.value = SolvePhase.Editing(accepted, outcome.recovery)
                        }
                    }
                }
        }
    }
