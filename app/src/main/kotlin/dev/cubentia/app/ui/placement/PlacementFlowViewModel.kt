package dev.cubentia.app.ui.placement

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.cubentia.app.ScanDiagnostics
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.application.usecase.PlaceFromScan
import dev.cubentia.domain.content.Stage
import dev.cubentia.domain.content.StageId
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.text.Text
import dev.cubentia.domain.tutor.Placement
import dev.cubentia.domain.tutor.PlacementOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

@HiltViewModel
class PlacementFlowViewModel private constructor(
    private val suggest: (CubeState, List<Stage>) -> PlacementOutcome,
) : ViewModel() {
    @Inject
    constructor(placeFromScan: PlaceFromScan) : this(placeFromScan::suggest)

    internal constructor() : this(Placement::evaluate)

    private val mutablePhase = MutableStateFlow<PlacementPhase>(PlacementPhase.Idle)
    val phase = mutablePhase.asStateFlow()

    private val mutableAcceptedScan = MutableStateFlow<CubeState?>(null)
    val acceptedScan = mutableAcceptedScan.asStateFlow()

    fun onScanOutcome(outcome: ScanOutcome) {
        when (outcome) {
            is ScanOutcome.Accepted -> {
                ScanDiagnostics.onPlacementScanAccepted()
                mutableAcceptedScan.value = outcome.state
            }

            ScanOutcome.FellBackToEditor -> {
                mutablePhase.value = PlacementPhase.Editing(Text("msg.solve.scan-fell-back"))
            }

            ScanOutcome.Cancelled -> {
                reset()
            }
        }
    }

    fun evaluateScan(stages: List<Stage>) {
        val state = mutableAcceptedScan.value ?: return
        mutableAcceptedScan.value = null
        val outcome = suggest(state, stages)
        ScanDiagnostics.onPlacementSuggested(outcome)
        mutablePhase.value = PlacementPhase.Result(outcome)
    }

    fun scanDenied() {
        mutablePhase.value = PlacementPhase.Editing(Text("msg.solve.scan-camera-denied"))
    }

    fun editorAccepted(
        state: CubeState,
        stages: List<Stage>,
    ) {
        mutablePhase.value = PlacementPhase.Result(suggest(state, stages))
    }

    fun chooseAnother() {
        val result = mutablePhase.value as? PlacementPhase.Result ?: return
        mutablePhase.value = PlacementPhase.Choosing(result.outcome)
    }

    fun accepted(stageId: StageId) = ScanDiagnostics.onPlacementRun(stageId.value)

    override fun onCleared() = ScanDiagnostics.onPlacementRun(null)

    fun scanningAgain() = ScanDiagnostics.onPlacementRun(null)

    fun back() {
        val current = mutablePhase.value
        if (current !is PlacementPhase.Choosing) ScanDiagnostics.onPlacementRun(null)
        mutablePhase.value = if (current is PlacementPhase.Choosing) PlacementPhase.Result(current.outcome) else PlacementPhase.Idle
    }

    fun reset() {
        ScanDiagnostics.onPlacementRun(null)
        mutableAcceptedScan.value = null
        mutablePhase.value = PlacementPhase.Idle
    }
}
