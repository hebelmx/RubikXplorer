package dev.rubikxplorer.app.ui.freecube

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dev.rubikxplorer.app.render.CubeRenderer
import dev.rubikxplorer.app.render.MoveIntent
import dev.rubikxplorer.app.render.Net2DRenderer
import dev.rubikxplorer.app.render.PresentationItem
import dev.rubikxplorer.app.render.RenderStyle
import dev.rubikxplorer.domain.cube.MoveHistory

/**
 * The free cube (FR-1, FR-3): commit before animate (AD-4). Every input commits to the pure
 * [MoveHistory] first, then submits a [PresentationItem] to the renderer, which never touches the
 * history. The free cube is not a Learning Session: nothing here is persisted.
 */
class FreeCubeViewModel(
    /** The 2D net adapter; its queue and drawn state outlive configuration changes with this ViewModel. */
    val renderer: Net2DRenderer = Net2DRenderer(),
) : ViewModel() {
    private val port: CubeRenderer = renderer
    private var revision = 0L

    var history by mutableStateOf(MoveHistory())
        private set

    var style by mutableStateOf(RenderStyle())
        private set

    fun onMoveIntent(intent: MoveIntent) {
        history = history.commit(intent.move)
        port.submit(listOf(PresentationItem.Animate(intent.move, ++revision, history.state.toFacelets())))
    }

    fun onUndo() {
        val undone = history.undoable ?: return
        history = history.undo()
        port.submit(listOf(PresentationItem.Animate(undone.inverse(), ++revision, history.state.toFacelets())))
    }

    fun onRedo() {
        val redone = history.redoable ?: return
        history = history.redo()
        port.submit(listOf(PresentationItem.Animate(redone, ++revision, history.state.toFacelets())))
    }

    /** Back to solved with no confirmation; not undoable. */
    fun onReset() {
        history = history.reset()
        port.submit(listOf(PresentationItem.Snap(++revision, history.state.toFacelets())))
    }

    fun onColorBlindGlyphsChange(enabled: Boolean) {
        style = style.copy(colorBlindGlyphs = enabled)
    }
}
