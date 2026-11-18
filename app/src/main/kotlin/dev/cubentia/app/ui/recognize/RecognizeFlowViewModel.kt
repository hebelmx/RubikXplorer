package dev.cubentia.app.ui.recognize

import androidx.lifecycle.ViewModel
import dev.cubentia.app.ScanDiagnostics
import dev.cubentia.app.scan.CaseCaptureOutcome
import dev.cubentia.application.usecase.Recognition
import dev.cubentia.application.usecase.RecognizeCase
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.recognition.CornerView
import dev.cubentia.domain.text.Text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface RecognizePhase {
    data object Idle : RecognizePhase

    data class Editing(
        val note: Text,
    ) : RecognizePhase

    data class Result(
        val recognition: Recognition,
    ) : RecognizePhase
}

class RecognizeFlowViewModel : ViewModel() {
    private sealed interface Input {
        data class Camera(
            val views: List<CornerView>,
        ) : Input

        data class Manual(
            val state: CubeState,
        ) : Input
    }

    private val mutablePhase = MutableStateFlow<RecognizePhase>(RecognizePhase.Idle)
    val phase = mutablePhase.asStateFlow()
    private var pendingInput: Input? = null
    private val mutablePending = MutableStateFlow(false)
    val pending = mutablePending.asStateFlow()

    fun onCapture(outcome: CaseCaptureOutcome) {
        when (outcome) {
            is CaseCaptureOutcome.Captured -> {
                queue(Input.Camera(outcome.views))
            }

            CaseCaptureOutcome.Cancelled -> {
                ScanDiagnostics.onRecognizeRun(null, null, "CANCELLED")
            }

            CaseCaptureOutcome.FellBackToEditor -> {
                ScanDiagnostics.onRecognizeRun(null, null, "EDITOR")
                edit("msg.solve.scan-fell-back")
            }
        }
    }

    fun scanDenied() = edit("msg.solve.scan-camera-denied")

    fun editorAccepted(state: CubeState) = queue(Input.Manual(state))

    fun evaluate(recognizeCase: RecognizeCase?) {
        val input = pendingInput ?: return
        val previous = (mutablePhase.value as? RecognizePhase.Result)?.recognition as? Recognition.Several
        if (input is Input.Camera && input.views.size == 1 && previous == null) {
            // An extra angle with no earlier candidates (e.g. after process death) never names a Case from one corner.
            ScanDiagnostics.discardCaseCapture()
            pendingInput = null
            mutablePending.value = false
            return
        }
        val result =
            when (input) {
                is Input.Camera -> recognizeCase?.fromCorners(input.views, previous?.caseIds.takeIf { input.views.size == 1 })
                is Input.Manual -> recognizeCase?.fromCube(input.state)
            }
        val recognition = result ?: Recognition.NoFit
        if (input is Input.Camera) ScanDiagnostics.onRecognizeRun(recognition, input.views.size)
        mutablePhase.value = RecognizePhase.Result(recognition)
        pendingInput = null
        mutablePending.value = false
    }

    fun reset() {
        pendingInput = null
        mutablePending.value = false
        mutablePhase.value = RecognizePhase.Idle
    }

    private fun queue(input: Input) {
        pendingInput = input
        mutablePending.value = true
    }

    private fun edit(note: String) {
        mutablePhase.value = RecognizePhase.Editing(Text(note))
    }
}
