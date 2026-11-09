package dev.cubentia.app.ui.freecube

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dev.cubentia.app.random.KotlinRandomSource
import dev.cubentia.app.render.Cube3DRenderer
import dev.cubentia.app.render.CubeRenderer
import dev.cubentia.app.render.PresentationItem
import dev.cubentia.app.session.toItem
import dev.cubentia.application.freecube.FreeCubeEvent
import dev.cubentia.application.freecube.FreeCubeState
import dev.cubentia.application.freecube.FreeCubeTransition
import dev.cubentia.application.freecube.reduceFreeCube
import dev.cubentia.application.usecase.NewScramble
import dev.cubentia.domain.cube.MoveHistory
import dev.cubentia.domain.input.MoveIntent
import dev.cubentia.domain.predicate.WhiteCrossSolved

/** How the last history change moved [WhiteCrossSolved] progress (debug Objective overlay only). */
enum class ProgressChange {
    CLOSER,
    UNCHANGED,
    FURTHER,
}

/**
 * The free cube (FR-1, FR-3): commit before animate (AD-4). Every input commits to the pure
 * [MoveHistory] first, then submits a [PresentationItem] to the renderer, which never touches the
 * history. The free cube is not a Learning Session: nothing here is persisted.
 */
class FreeCubeViewModel(
    /** The 3D adapter; its queue and drawn state outlive configuration changes with this ViewModel. */
    val renderer: Cube3DRenderer = Cube3DRenderer(),
    private val scramble: NewScramble = NewScramble(KotlinRandomSource()),
) : ViewModel() {
    private val port: CubeRenderer = renderer
    var state by mutableStateOf(FreeCubeState())
        private set

    val history: MoveHistory get() = state.history

    /** How the last history change moved [WhiteCrossSolved] progress; `null` before the first change. */
    var lastChange by mutableStateOf<ProgressChange?>(null)
        private set

    fun onMoveIntent(intent: MoveIntent) = dispatch(FreeCubeEvent.MoveCommitted(intent.move))

    fun onUndo() = dispatch(FreeCubeEvent.Undo)

    fun onRedo() = dispatch(FreeCubeEvent.Redo)

    /** Back to solved with no confirmation; not undoable. */
    fun onReset() = dispatch(FreeCubeEvent.Reset)

    /** Generate outside the reducer, then commit the Scramble in one transition. */
    fun onScramble() = dispatch(FreeCubeEvent.Scrambled(scramble()))

    /** Dispatch every input through the sole history owner before submitting its presentation. */
    fun dispatch(event: FreeCubeEvent): FreeCubeTransition {
        val transition = reduceFreeCube(state, event)
        val presentation = transition.presentation ?: return transition
        val before = WhiteCrossSolved.progress(history.state)
        val after = WhiteCrossSolved.progress(transition.state.history.state)
        state = transition.state
        lastChange =
            when {
                after > before -> ProgressChange.CLOSER
                after < before -> ProgressChange.FURTHER
                else -> ProgressChange.UNCHANGED
            }
        port.submit(listOf(presentation.toItem()))
        return transition
    }
}
