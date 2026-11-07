package dev.rubikxplorer.app.ui.freecube

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dev.rubikxplorer.app.random.KotlinRandomSource
import dev.rubikxplorer.app.render.Cube3DRenderer
import dev.rubikxplorer.app.render.CubeRenderer
import dev.rubikxplorer.app.render.PresentationItem
import dev.rubikxplorer.app.session.toItem
import dev.rubikxplorer.application.freecube.FreeCubeEvent
import dev.rubikxplorer.application.freecube.FreeCubeState
import dev.rubikxplorer.application.freecube.FreeCubeTransition
import dev.rubikxplorer.application.freecube.reduceFreeCube
import dev.rubikxplorer.application.usecase.NewScramble
import dev.rubikxplorer.domain.cube.MoveHistory
import dev.rubikxplorer.domain.input.MoveIntent
import dev.rubikxplorer.domain.predicate.WhiteCrossSolved

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
