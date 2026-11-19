package dev.cubentia.app.ui.solve

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.app.solver.SolverLoad
import dev.cubentia.app.solver.SolverSource
import dev.cubentia.application.session.Effect
import dev.cubentia.application.usecase.SolveCube
import dev.cubentia.application.usecase.SolveCubeOutcome
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.text.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class Preparation { NONE, WARMING_UP, FIRST_USE }

sealed interface SolvePhase {
    data object Home : SolvePhase

    data object AlreadySolved : SolvePhase

    data class Editing(
        val initial: Facelets,
        val error: Text? = null,
        val note: Text? = null,
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
        private val mutablePendingScan = MutableStateFlow<CubeState?>(null)
        val pendingScan: StateFlow<CubeState?> = mutablePendingScan.asStateFlow()
        private var accepted = CubeState.SOLVED.toFacelets()

        /** The accepted scan that started this solve flow, including any Editor retry. */
        private var scanned: Facelets? = null

        /** The accepted scan's measured centers (GH #172), kept and cleared with [scanned]. */
        private var scannedColors: CalibrationCenters? = null
        private var revision = 0L
        private var job: Job? = null

        fun onScanOutcome(outcome: ScanOutcome) {
            when (outcome) {
                is ScanOutcome.Accepted -> {
                    scanned = outcome.state.toFacelets()
                    scannedColors = outcome.runScanColors
                    mutablePendingScan.value = outcome.state
                }

                ScanOutcome.FellBackToEditor -> {
                    scanned = null
                    scannedColors = null
                    mutablePhase.value =
                        SolvePhase.Editing(CubeState.SOLVED.toFacelets(), note = Text("msg.solve.scan-fell-back"))
                }

                ScanOutcome.Cancelled -> {
                    Unit
                }
            }
        }

        fun takePendingScan(): CubeState? = mutablePendingScan.getAndUpdate { null }

        /**
         * Whether this solve started from an accepted scan. Playback uses the confirmed state as the Full verified
         * baseline, including after a retry from the Editor with corrected stickers.
         */
        fun isAcceptedScan(): Boolean = scanned != null

        /** The accepted scan's run-scan colors (GH #172), or null when this solve has none. */
        fun acceptedScanColors(): CalibrationCenters? = scannedColors.takeIf { scanned != null }

        fun scanDenied() {
            scanned = null
            scannedColors = null
            mutablePhase.value = SolvePhase.Editing(CubeState.SOLVED.toFacelets(), note = Text("msg.solve.scan-camera-denied"))
        }

        fun enterColors() {
            scanned = null
            scannedColors = null
            mutablePhase.value = SolvePhase.Editing(accepted)
        }

        fun closeEditor() {
            scanned = null
            scannedColors = null
            mutablePendingScan.value = null
            revision++
            job?.cancel()
            mutablePhase.value = SolvePhase.Home
        }

        fun leaveSolveTab() {
            cancelSolving()
            scanned = null
            scannedColors = null
            mutablePendingScan.value = null
        }

        /** Cancels only a running solve, keeping the entered colors; does nothing in other phases. */
        private fun cancelSolving() {
            if (mutablePhase.value is SolvePhase.Solving) cancel()
        }

        fun cancel() {
            revision++
            job?.cancel()
            mutablePhase.value = SolvePhase.Editing(accepted)
        }

        fun confirm(
            state: CubeState,
            onSolved: suspend (CubeState, List<FaceTurn>) -> Unit,
        ) {
            if (!state.isSolved) {
                accept(state, onSolved)
                return
            }
            revision++
            job?.cancel()
            // The editor fixes its centers to the home frame, so it reopens the solved cube held that way.
            accepted = state.reoriented(Orientation.HOME).toFacelets()
            scanned = null
            scannedColors = null
            mutablePhase.value = SolvePhase.AlreadySolved
        }

        fun accept(
            state: CubeState,
            onSolved: suspend (CubeState, List<FaceTurn>) -> Unit,
        ) {
            val attempt = ++revision
            job?.cancel()
            accepted = state.reoriented(Orientation.HOME).toFacelets()
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
                            if (attempt == revision) {
                                scanned = null
                                scannedColors = null
                                mutablePhase.value = SolvePhase.Home
                            }
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
