package dev.cubentia.app.ui.placement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.cubentia.app.ScanDiagnostics
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.application.usecase.PlaceFromScan
import dev.cubentia.domain.content.Stage
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.lastlayer.LastLayerMatcher
import dev.cubentia.domain.text.Text
import dev.cubentia.domain.tutor.Placement
import dev.cubentia.domain.tutor.PlacementOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PlacementPhase {
    data object Idle : PlacementPhase

    data class Editing(
        val note: Text,
    ) : PlacementPhase

    data class Result(
        val outcome: PlacementOutcome,
    ) : PlacementPhase

    data class Choosing(
        val outcome: PlacementOutcome,
    ) : PlacementPhase
}

/** The accept write of a suggested Stage; held by the view model so an Activity recreation mid-write keeps its outcome. */
sealed interface PlacementAcceptance {
    data object Idle : PlacementAcceptance

    data object Saving : PlacementAcceptance

    data object Failed : PlacementAcceptance

    data class Saved(
        val stageId: StageId,
        val faster: Boolean,
    ) : PlacementAcceptance
}

@HiltViewModel
class PlacementFlowViewModel private constructor(
    private val suggest: (CubeState, List<Stage>, LastLayerMatcher?) -> PlacementOutcome,
) : ViewModel() {
    @Inject
    constructor(placeFromScan: PlaceFromScan) : this(placeFromScan::suggest)

    internal constructor() : this(Placement::evaluate)

    private val mutablePhase = MutableStateFlow<PlacementPhase>(PlacementPhase.Idle)
    val phase = mutablePhase.asStateFlow()

    private val mutableAcceptedScan = MutableStateFlow<CubeState?>(null)
    val acceptedScan = mutableAcceptedScan.asStateFlow()

    private val mutableAcceptance = MutableStateFlow<PlacementAcceptance>(PlacementAcceptance.Idle)
    val acceptance = mutableAcceptance.asStateFlow()

    /** Bumped by [reset], so a write still saving when the flow is reset cannot report into the next one. */
    private var acceptEpoch = 0

    /** Shows [next]; a shown save failure belongs to the phase it was shown in. */
    private fun show(next: PlacementPhase) {
        if (next != mutablePhase.value && mutableAcceptance.value == PlacementAcceptance.Failed) {
            mutableAcceptance.value = PlacementAcceptance.Idle
        }
        mutablePhase.value = next
    }

    /** Runs the accept write [save] in this view model's scope; ignored while a write is already saving. */
    fun accept(
        stageId: StageId,
        faster: Boolean,
        save: suspend () -> Boolean,
    ) {
        if (mutableAcceptance.value == PlacementAcceptance.Saving) return
        val epoch = acceptEpoch
        mutableAcceptance.value = PlacementAcceptance.Saving
        viewModelScope.launch {
            val saved =
                try {
                    save()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            if (epoch == acceptEpoch) {
                mutableAcceptance.value = if (saved) PlacementAcceptance.Saved(stageId, faster) else PlacementAcceptance.Failed
            }
        }
    }

    /** Returns a finished accept write once, clearing it. */
    fun consumeSaved(): PlacementAcceptance.Saved? {
        val saved = mutableAcceptance.value as? PlacementAcceptance.Saved ?: return null
        mutableAcceptance.value = PlacementAcceptance.Idle
        return saved
    }

    /** Clears a shown save failure, as when the Learner scans again. */
    fun clearSaveFailure() {
        if (mutableAcceptance.value == PlacementAcceptance.Failed) mutableAcceptance.value = PlacementAcceptance.Idle
    }

    fun onScanOutcome(outcome: ScanOutcome) {
        when (outcome) {
            is ScanOutcome.Accepted -> {
                ScanDiagnostics.onPlacementScanAccepted()
                mutableAcceptedScan.value = outcome.state
            }

            ScanOutcome.FellBackToEditor -> {
                show(PlacementPhase.Editing(Text("msg.solve.scan-fell-back")))
            }

            ScanOutcome.Cancelled -> {
                reset()
            }
        }
    }

    /** Places the accepted scan; [matcher] names a last-layer Case when exactly one fits. */
    fun evaluateScan(
        stages: List<Stage>,
        matcher: LastLayerMatcher? = null,
    ) {
        val state = mutableAcceptedScan.value ?: return
        mutableAcceptedScan.value = null
        val outcome = suggest(state, stages, matcher)
        ScanDiagnostics.onPlacementSuggested(outcome)
        show(PlacementPhase.Result(outcome))
    }

    fun scanDenied() {
        show(PlacementPhase.Editing(Text("msg.solve.scan-camera-denied")))
    }

    fun editorAccepted(
        state: CubeState,
        stages: List<Stage>,
        matcher: LastLayerMatcher? = null,
    ) {
        show(PlacementPhase.Result(suggest(state, stages, matcher)))
    }

    fun chooseAnother() {
        val result = mutablePhase.value as? PlacementPhase.Result ?: return
        show(PlacementPhase.Choosing(result.outcome))
    }

    fun accepted(stageId: StageId) = ScanDiagnostics.onPlacementRun(stageId.value)

    override fun onCleared() = ScanDiagnostics.onPlacementRun(null)

    fun scanningAgain() = ScanDiagnostics.onPlacementRun(null)

    fun back() {
        val current = mutablePhase.value
        if (current !is PlacementPhase.Choosing) ScanDiagnostics.onPlacementRun(null)
        show(if (current is PlacementPhase.Choosing) PlacementPhase.Result(current.outcome) else PlacementPhase.Idle)
    }

    fun reset() {
        ScanDiagnostics.onPlacementRun(null)
        mutableAcceptedScan.value = null
        acceptEpoch++
        mutableAcceptance.value = PlacementAcceptance.Idle
        mutablePhase.value = PlacementPhase.Idle
    }
}
