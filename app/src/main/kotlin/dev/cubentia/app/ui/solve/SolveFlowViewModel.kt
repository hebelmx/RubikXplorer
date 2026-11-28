package dev.cubentia.app.ui.solve

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.app.solver.MethodSolveWarmUp
import dev.cubentia.app.solver.SolveDeadline
import dev.cubentia.app.solver.SolverLoad
import dev.cubentia.app.solver.SolverSource
import dev.cubentia.app.ui.editor.editedFrom
import dev.cubentia.application.session.Effect
import dev.cubentia.application.usecase.SolveCube
import dev.cubentia.application.usecase.SolveCubeOutcome
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
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

    /**
     * The open editor: [fresh] when it opens on the default solved cube with no cube accepted yet (#253), and [set] the
     * stickers already set in it (kept across a Solve tab switch). [seeded] (GH #321) when a scan's stickers opened it:
     * only those in [set] hold a color, every other non-center sticker is unset ([initial] holds a placeholder there).
     */
    data class Editing(
        val initial: Facelets,
        val error: Text? = null,
        val note: Text? = null,
        val fresh: Boolean = false,
        val set: Set<Int> = emptySet(),
        val seeded: Boolean = false,
    ) : SolvePhase

    data class Solving(
        val preparation: Preparation,
    ) : SolvePhase
}

/**
 * The Solve tab's flow from an accepted cube to a verified solution. [deadline] is the process's one solve deadline
 * (Story 15.4); once the pruning tables are ready, [warmUp] (when given) starts the process's one method-solve warm-up.
 */
@HiltViewModel
class SolveFlowViewModel
    private constructor(
        private val source: SolverSource,
        private val warmUp: MethodSolveWarmUp?,
        private val deadline: SolveDeadline,
    ) : ViewModel() {
        @Inject
        constructor(
            source: SolverSource,
            deadline: SolveDeadline,
            warmUp: MethodSolveWarmUp,
        ) : this(source, warmUp, deadline)

        /** A flow with the release deadline and no warm-up (tests and the shell's default). */
        constructor(source: SolverSource) : this(source, null, SolveDeadline.RELEASE)

        private val mutablePhase = MutableStateFlow<SolvePhase>(SolvePhase.Home)
        val phase = mutablePhase.asStateFlow()
        private val mutablePendingScan = MutableStateFlow<CubeState?>(null)
        val pendingScan: StateFlow<CubeState?> = mutablePendingScan.asStateFlow()
        private var accepted = CubeState.SOLVED.toFacelets()

        /** Whether a cube has been accepted or confirmed in this ViewModel; until then the editor opens fresh. */
        private var hasAccepted = false

        /** The accepted scan that started this solve flow, including any Editor retry. */
        private var scanned: Facelets? = null

        /** The accepted scan's measured centers (GH #172), kept and cleared with [scanned]. */
        private var scannedColors: CalibrationCenters? = null
        private var revision = 0L

        /** The open editor's unaccepted stickers, kept across a Solve tab switch (in memory only, never on disk). */
        private var draft: Facelets? = null

        /** The open editor's set stickers, kept with [draft]. */
        private var draftSet: Set<Int> = emptySet()

        /** The stickers the open editor opened on, kept across a Solve tab switch: what an edit is measured against. */
        private var opened: Facelets? = null

        private val mutableConfirmDiscard = MutableStateFlow(false)

        /** Whether the "Discard the colors you entered?" dialog shows (GH #354). */
        val confirmDiscard: StateFlow<Boolean> = mutableConfirmDiscard.asStateFlow()

        /** Shows [next]; any editor draft belongs to the phase it replaces, so it is dropped. */
        private fun show(next: SolvePhase) {
            draft = null
            draftSet = emptySet()
            opened = (next as? SolvePhase.Editing)?.initial
            mutableConfirmDiscard.value = false
            mutablePhase.value = next
        }

        /**
         * Whether the open editor holds Learner input (GH #354): a seeded (scan-kept) editor always; a fresh one once a
         * sticker is set; any other once a sticker differs from the cube it opened on.
         */
        private fun holdsInput(editing: SolvePhase.Editing): Boolean {
            if (editing.seeded) return true
            val current = draft ?: editing.initial
            val set = if (draft != null) draftSet else editing.set
            return if (editing.fresh) set.isNotEmpty() else editedFrom(opened ?: editing.initial, current)
        }

        /** Close or Back in the editor: asks before discarding the Learner's input, else closes at once (GH #354). */
        fun requestCloseEditor() {
            val current = mutablePhase.value
            if (current is SolvePhase.Editing && holdsInput(current)) mutableConfirmDiscard.value = true else closeEditor()
        }

        /** *Keep entering*: dismisses the dialog, keeping the editor and its stickers. */
        fun keepEditing() {
            mutableConfirmDiscard.value = false
        }

        /** *Discard*: drops the entry and returns to Solve Home. */
        fun discardAndClose() = closeEditor()

        /** The Learner changed a sticker in the open editor; kept so leaving the Solve tab does not lose it. */
        fun onEdit(
            facelets: Facelets,
            set: Set<Int>,
        ) {
            if (mutablePhase.value is SolvePhase.Editing) {
                draft = facelets
                draftSet = set
            }
        }

        private var job: Job? = null

        fun onScanOutcome(outcome: ScanOutcome) {
            when (outcome) {
                is ScanOutcome.Accepted -> {
                    scanned = outcome.state.toFacelets()
                    scannedColors = outcome.runScanColors
                    mutablePendingScan.value = outcome.state
                }

                is ScanOutcome.FellBackToEditor -> {
                    // Only Accepted is the Full verified baseline: a fallback's stickers only seed the Editor (GH #321).
                    scanned = null
                    scannedColors = null
                    mutablePendingScan.value = null
                    show(outcome.stickers?.let(::seededEditing) ?: fellBackEditing())
                }

                ScanOutcome.Cancelled -> {
                    Unit
                }
            }
        }

        private fun fellBackEditing() =
            SolvePhase.Editing(CubeState.SOLVED.toFacelets(), note = Text("msg.solve.scan-fell-back"), fresh = true)

        /**
         * The Editor seeded with a fallback's [stickers] in the home frame (GH #321): read stickers are set, every other
         * non-center one unset, holding its face's home color only as a placeholder; centers are always home colors.
         * Stickers of the wrong size open the blank Editor instead.
         */
        private fun seededEditing(stickers: List<Color?>): SolvePhase.Editing {
            if (stickers.size != Facelets.SIZE) return fellBackEditing()
            val home = { index: Int -> Color.home(Face.entries[index / 9]) }
            val colors = List(Facelets.SIZE) { index -> if (index % 9 == 4) home(index) else stickers[index] ?: home(index) }
            val set = stickers.indices.filterTo(mutableSetOf()) { index -> index % 9 != 4 && stickers[index] != null }
            return SolvePhase.Editing(Facelets(colors), note = Text("msg.solve.scan-kept"), set = set, seeded = true)
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
            mutablePendingScan.value = null
            show(SolvePhase.Editing(CubeState.SOLVED.toFacelets(), note = Text("msg.solve.scan-camera-denied"), fresh = true))
        }

        fun enterColors() {
            scanned = null
            scannedColors = null
            mutablePendingScan.value = null
            show(SolvePhase.Editing(accepted, fresh = !hasAccepted))
        }

        fun closeEditor() {
            scanned = null
            scannedColors = null
            mutablePendingScan.value = null
            revision++
            job?.cancel()
            show(SolvePhase.Home)
        }

        fun leaveSolveTab() {
            cancelSolving()
            // The editor leaves composition with the tab: it reopens on the unaccepted stickers.
            val current = mutablePhase.value
            val edited = draft
            if (current is SolvePhase.Editing && edited != null) mutablePhase.value = current.copy(initial = edited, set = draftSet)
            draft = null
            draftSet = emptySet()
            mutableConfirmDiscard.value = false
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
            show(SolvePhase.Editing(accepted, fresh = !hasAccepted))
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
            hasAccepted = true
            scanned = null
            scannedColors = null
            show(SolvePhase.AlreadySolved)
        }

        fun accept(
            state: CubeState,
            onSolved: suspend (CubeState, List<FaceTurn>) -> Unit,
        ) {
            val attempt = ++revision
            job?.cancel()
            accepted = state.reoriented(Orientation.HOME).toFacelets()
            hasAccepted = true
            show(SolvePhase.Solving(if (source.isReady) Preparation.NONE else Preparation.WARMING_UP))
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
                                                    show(SolvePhase.Solving(Preparation.FIRST_USE))
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
                                    show(SolvePhase.Solving(Preparation.NONE))
                                    // The tables are prepared: warm the method solver once, off-main and outside every deadline.
                                    warmUp?.start()
                                    SolveCube(loaded.solver, deadline.millis)(Effect.SolveCube(attempt, attempt, state)).outcome
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
                                show(SolvePhase.Home)
                            }
                        }

                        is SolveCubeOutcome.TimedOut -> {
                            show(SolvePhase.Editing(accepted, outcome.recovery))
                        }

                        is SolveCubeOutcome.Failed -> {
                            show(SolvePhase.Editing(accepted, outcome.recovery))
                        }
                    }
                }
        }
    }
