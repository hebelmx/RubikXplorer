package dev.cubentia.domain.input

import dev.cubentia.domain.cube.Move

sealed interface DragPreview {
    data class Active(
        val move: Move,
        val degrees: Double,
    ) : DragPreview

    data object CancelZone : DragPreview
}
