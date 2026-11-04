package dev.rubikxplorer.app.ui.freecube

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dev.rubikxplorer.app.random.KotlinRandomSource
import dev.rubikxplorer.app.render.Cube3DRenderer
import dev.rubikxplorer.app.render.CubeRenderer
import dev.rubikxplorer.app.render.MoveIntent
import dev.rubikxplorer.app.render.PresentationItem
import dev.rubikxplorer.app.render.RenderStyle
import dev.rubikxplorer.application.usecase.NewScramble
import dev.rubikxplorer.domain.cube.MoveHistory
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
    private var revision = 0L

    var history by mutableStateOf(MoveHistory())
        private set

    /** How the last history change moved [WhiteCrossSolved] progress; `null` before the first change. */
    var lastChange by mutableStateOf<ProgressChange?>(null)
        private set

    var style by mutableStateOf(RenderStyle())
        private set

    fun onMoveIntent(intent: MoveIntent) {
        record(history.commit(intent.move))
        port.submit(listOf(PresentationItem.Animate(intent.move, ++revision, history.state.toFacelets())))
    }

    fun onUndo() {
        val undone = history.undoable ?: return
        record(history.undo())
        port.submit(listOf(PresentationItem.Animate(undone.inverse(), ++revision, history.state.toFacelets())))
    }

    fun onRedo() {
        val redone = history.redoable ?: return
        record(history.redo())
        port.submit(listOf(PresentationItem.Animate(redone, ++revision, history.state.toFacelets())))
    }

    /** Back to solved with no confirmation; not undoable. */
    fun onReset() {
        record(history.reset())
        port.submit(listOf(PresentationItem.Snap(++revision, history.state.toFacelets())))
    }

    /** A fresh random Scramble from solved: each of its Moves is committed (and undoable), then drawn in one snap. */
    fun onScramble() {
        record(scramble().fold(history.reset()) { h, move -> h.commit(move) })
        port.submit(listOf(PresentationItem.Snap(++revision, history.state.toFacelets())))
    }

    fun onColorBlindGlyphsChange(enabled: Boolean) {
        style = style.copy(colorBlindGlyphs = enabled)
    }

    /** The one writer of [history]; also recomputes [lastChange]. */
    private fun record(next: MoveHistory) {
        val before = WhiteCrossSolved.progress(history.state)
        val after = WhiteCrossSolved.progress(next.state)
        history = next
        lastChange =
            when {
                after > before -> ProgressChange.CLOSER
                after < before -> ProgressChange.FURTHER
                else -> ProgressChange.UNCHANGED
            }
    }
}
