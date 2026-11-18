package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.text.Text

/** Classified stickers in corner net order, with unseen non-centers represented by null. */
data class CornerView(
    val top: List<Color?>,
    val left: List<Color?>,
    val right: List<Color?>,
) {
    init {
        require(listOf(top, left, right).all { it.size == 9 && it[FaceSample.CENTER_INDEX] != null })
    }
}

sealed interface CaseCaptureEffect {
    data object Remeter : CaseCaptureEffect

    data class CornerCaptured(
        val hold: Int,
    ) : CaseCaptureEffect

    data class Done(
        val views: List<CornerView>,
    ) : CaseCaptureEffect
}

class CaseCaptureController(
    private val saved: CalibrationCenters,
    val corners: Int,
) {
    init {
        require(corners in 1..2)
    }

    private val corner = CornerHold()
    private val views = mutableListOf<CornerView>()
    private var previous: List<Lab?>? = null
    val hold: Int get() = views.size + 1
    val coaching: Coaching? get() = corner.coaching
    var prompt = holdPrompt()
        private set

    fun onFrame(
        detected: Boolean,
        faces: List<FrameStats?>,
        now: Long,
    ): List<CaseCaptureEffect> {
        if (hold > corners) return emptyList()
        return when (
            corner.onFrame(detected, faces, now, clearWhenUnlocated = true) { values ->
                previous?.let { corner.changed(values, it, GuidedScanController.FACE_CHANGE_DELTA_E) } ?: true
            }
        ) {
            CornerHold.Decision.Wait -> emptyList()
            CornerHold.Decision.Remeter -> listOf(CaseCaptureEffect.Remeter)
            CornerHold.Decision.Capture -> onShutter()
        }
    }

    fun onShutter(): List<CaseCaptureEffect> {
        if (hold > corners) return emptyList()
        val view = ScanAssembler.cornerView(corner.latest, saved) ?: return emptyList()
        previous = corner.values()
        corner.reset()
        val (top, left, right) = view
        val sideCenters = listOf(left[4], right[4])
        val wrongCorner =
            when {
                top[4] != Color.YELLOW -> {
                    Text("msg.recognize.turn-yellow-up")
                }

                sideCenters.any { it == Color.WHITE || it == Color.YELLOW } -> {
                    Text("msg.recognize.keep-yellow-up")
                }

                ScanAssembler.lastLayerView(view) == CornerLastLayer.NotOnRing -> {
                    if (hold == 2) Text("msg.recognize.same-corner") else holdPrompt()
                }

                views.any { it.left[4] in sideCenters || it.right[4] in sideCenters } -> {
                    Text("msg.recognize.same-corner")
                }

                else -> {
                    null
                }
            }
        if (wrongCorner != null) {
            prompt = wrongCorner
            return emptyList()
        }
        val captured = CaseCaptureEffect.CornerCaptured(hold)
        views += view
        if (hold > corners) return listOf(captured, CaseCaptureEffect.Done(views.toList()))
        prompt = holdPrompt()
        return listOf(captured)
    }

    private fun holdPrompt(): Text = if (hold == 1) Text("msg.recognize.hold-first") else Text("msg.recognize.hold-second")
}
