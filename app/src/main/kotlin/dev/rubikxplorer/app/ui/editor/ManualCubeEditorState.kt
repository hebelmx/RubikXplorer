package dev.rubikxplorer.app.ui.editor

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import dev.rubikxplorer.domain.cube.Color
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.CubeValidator
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.Facelets
import dev.rubikxplorer.domain.cube.ValidationResult

/**
 * The Manual Cube Editor's state (FR-14): the entered [facelets], the [selected] sticker and the
 * [face] shown when a compact window edits one face at a time. Centers
 * are always the home-frame colors (WHITE U, GREEN F) and cannot be selected. Validation is derived
 * live through [CubeValidator]; this is not a session (AD-3/AD-20), so nothing here is persisted or
 * goes through a reducer.
 */
class ManualCubeEditorState(
    initial: Facelets = CubeState.SOLVED.toFacelets(),
) {
    /** The stickers as entered, centers forced to home colors. */
    var facelets: Facelets by mutableStateOf(withHomeCenters(initial))
        private set

    /** The sticker the next palette color paints, or null before any sticker is tapped. */
    var selected: Int? by mutableStateOf(null)
        private set

    /** The face (URFDLB index) shown in one-face mode; follows the selected sticker. */
    var face: Int by mutableIntStateOf(0)
        private set

    /** Every reported failure of [facelets], in validator order; empty exactly when they are valid. */
    val problems: List<ValidationResult.Invalid> by derivedStateOf { CubeValidator.problems(facelets) }

    /** Sticker indices of the offending pieces, to outline in `status-invalid`. */
    val outlined: Set<Int> by derivedStateOf { problems.flatMapTo(mutableSetOf()) { it.positions } }

    /** Faces (URFDLB indices) holding at least one [outlined] sticker. */
    val facesWithProblems: Set<Int> by derivedStateOf { outlined.mapTo(mutableSetOf()) { it / PER_FACE } }

    /** The validated state of exactly [facelets], or null while they are invalid. */
    val validState: CubeState? by derivedStateOf { (CubeValidator.validate(facelets) as? ValidationResult.Valid)?.state }

    /** Selects sticker [index] for painting; centers and out-of-range indices are ignored. */
    fun select(index: Int) {
        if (!isEditable(index)) return
        selected = index
        face = index / PER_FACE
    }

    /**
     * Shows face [face] in one-face mode; a selection on another face is cleared so the palette never
     * paints a hidden sticker. Out-of-range faces are ignored.
     */
    fun showFace(face: Int) {
        if (face !in 0 until FACES) return
        this.face = face
        if (selected?.let { it / PER_FACE } != face) selected = null
    }

    /** Paints the selected sticker [color]; ignored while nothing is selected. Selection stays put. */
    fun paint(color: Color) {
        val index = selected ?: return
        if (facelets.colors[index] == color) return
        facelets = Facelets(facelets.colors.toMutableList().also { it[index] = color })
    }

    companion object {
        private const val PER_FACE = 9
        private const val CENTER_CELL = 4
        private const val FACES = 6

        /** Whether sticker [index] is a center (fixed to its home color). */
        fun isCenter(index: Int): Boolean = index % PER_FACE == CENTER_CELL

        /** Whether the Learner may select and paint sticker [index]. */
        fun isEditable(index: Int): Boolean = index in 0 until Facelets.SIZE && !isCenter(index)

        /**
         * Persists the 54 color ordinals, the selected index (-1 for none) and the shown face across
         * recreation.
         */
        internal val Saver: Saver<ManualCubeEditorState, Any> =
            listSaver(
                save = { state -> state.facelets.colors.map { it.ordinal } + (state.selected ?: -1) + state.face },
                restore = { saved ->
                    ManualCubeEditorState(Facelets(saved.take(Facelets.SIZE).map { Color.entries[it] }))
                        .also { state ->
                            state.select(saved[Facelets.SIZE])
                            state.showFace(saved.getOrElse(Facelets.SIZE + 1) { state.face })
                        }
                },
            )

        private fun withHomeCenters(facelets: Facelets): Facelets =
            Facelets(
                facelets.colors.mapIndexed { index, color ->
                    if (isCenter(index)) Color.home(Face.entries[index / PER_FACE]) else color
                },
            )
    }
}
