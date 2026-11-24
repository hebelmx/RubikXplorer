package dev.cubentia.app.ui.editor

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.StickerOrder
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.recognition.FaceTurnFit
import dev.cubentia.domain.recognition.ScanAssembler

/**
 * A face just completed by a paint while auto-advance moved on to [next] (URFDLB indices); [serial] tells one event
 * from the next, so the face-done line shows once per event.
 */
data class FaceDone(
    val face: Int,
    val next: Int,
    val serial: Int,
)

/**
 * The Manual Cube Editor's state (FR-14): the entered [facelets], the [selected] sticker and the
 * [face] shown when a compact window edits one face at a time. Painting auto-advances the selection
 * along the review net's order (EXPERIENCE.md "Sticker review net"), so a face takes about one touch
 * per sticker. Centers are always the home-frame colors (WHITE U, GREEN F) and cannot be selected.
 * Validation is derived live through [CubeValidator]; this is not a session (AD-3/AD-20), so nothing
 * here is persisted or goes through a reducer.
 *
 * A [fresh] editor (opened on the default solved cube with no caller cube, #253) counts a face as entered only once all
 * its stickers are [set] ([complete], GH #352/#353), shows no problem until every face is entered ([unentered]) and opens with the first sticker
 * selected. Once every face is entered and the stickers are invalid, each paint tries the faces' quarter turns through
 * [ScanAssembler.orient] (AD-9) once every color count is right: a unique fit replaces the stickers ([turned]), turning
 * the set flags, the selection and [cueIndex] with them; otherwise [checkFace] names the face to check.
 *
 * A [seeded] editor (GH #321: a Solve-tab scan fell back to it) holds the scanned stickers as [set] and every other
 * non-center sticker [isUnset] (its color in [facelets] is only a placeholder, drawn as a blank well); a face counts as
 * entered only once all its stickers are set ([complete]), and it opens on the first unset sticker.
 */
class ManualCubeEditorState(
    initial: Facelets = CubeState.SOLVED.toFacelets(),
    val fresh: Boolean = false,
    initialSet: Set<Int> = emptySet(),
    val seeded: Boolean = false,
) {
    /** The stickers as entered, centers forced to home colors. */
    var facelets: Facelets by mutableStateOf(withHomeCenters(initial))
        private set

    /** Stickers painted in this editor (a same-color tap included), carried over from [initialSet]. */
    var set: Set<Int> by mutableStateOf(initialSet.filterTo(mutableSetOf(), ::isEditable))
        private set

    /**
     * The sticker the next palette color paints (moved on by [paint]); on a [fresh] or [seeded] entry the first sticker
     * in [StickerOrder] not yet [set] (null when every sticker is set), so a restored draft never lands on an entered one.
     */
    var selected: Int? by mutableStateOf(if (fresh || seeded) StickerOrder.next(Facelets.SIZE - 1, wrap = true) { it !in set } else null)
        private set

    /** The face (URFDLB index) shown in one-face mode; follows the selected sticker. */
    var face: Int by mutableIntStateOf(selected?.let { it / PER_FACE } ?: 0)
        private set

    /** The sticker the last paint set, moved with its face when that paint turned it: where the update cue plays. */
    var cueIndex: Int? by mutableStateOf(null)
        private set

    /** Faces (URFDLB indices) the last paint turned to fit the only valid cube; cleared by the next paint. */
    var turned: List<Int> by mutableStateOf(emptyList())
        private set

    /** The last face-done event, or null before any. */
    var faceDone: FaceDone? by mutableStateOf(null)
        private set

    /**
     * Whether [face] counts as entered: when [fresh] or [seeded], once it is [complete] (GH #352/#353: a partly entered
     * face is never turned or checked); else always.
     */
    fun entered(face: Int): Boolean = if (fresh || seeded) complete(face) else true

    /** Whether sticker [index] of a [seeded] editor has no color yet (a blank well); never a center. */
    fun isUnset(index: Int): Boolean = seeded && isEditable(index) && index !in set

    /** Whether all 8 non-center stickers of [face] are [set]. */
    fun complete(face: Int): Boolean = stickersOf(face).filter(::isEditable).all { it in set }

    /** Faces not yet entered, in Learner order U, F, R, B, L, D; empty unless [fresh] or [seeded]. */
    val unentered: List<Int> by derivedStateOf { LEARNER_ORDER.filter { !entered(it) } }

    /**
     * The quarter-turn fit of [facelets] ([ScanAssembler.orient]), tried only when every face is entered, the stickers
     * are invalid and every color appears exactly 9 times (a turn never changes counts); null otherwise.
     */
    private val fit: FaceTurnFit? by derivedStateOf {
        val colors = facelets.colors
        val countsRight = Color.entries.all { color -> colors.count { it == color } == PER_COLOR }
        if (unentered.isNotEmpty() || !countsRight || CubeValidator.validate(facelets) is ValidationResult.Valid) {
            null
        } else {
            ScanAssembler.orient(facelets)
        }
    }

    /**
     * The first face to check (Learner order) when the counts are right but no turning, or several, fit; null
     * otherwise. Derived from the stickers, so it survives recreation and a Solve tab switch.
     */
    val checkFace: Int? by derivedStateOf {
        (fit as? FaceTurnFit.Unresolved)?.let { unresolved ->
            val involved = unresolved.involved.map { it.ordinal }
            LEARNER_ORDER.firstOrNull { it in involved }
        }
    }

    /** Every reported failure of [facelets], in validator order; empty while faces are [unentered]. */
    val problems: List<ValidationResult.Invalid> by derivedStateOf {
        if (unentered.isNotEmpty()) emptyList() else CubeValidator.problems(facelets)
    }

    /** The problem lines to show (counts, named pieces, global rules), empty while faces are [unentered]. */
    val lines: List<EditorProblem> by derivedStateOf {
        if (unentered.isNotEmpty()) emptyList() else EditorProblems.of(facelets, problems)
    }

    /** The numbered problems (those naming a piece), in line order; problem number = position + 1. */
    val numbered: List<EditorProblem.Piece> by derivedStateOf { lines.filterIsInstance<EditorProblem.Piece>() }

    /** Numbered markers for every sticker of a numbered problem's piece (Scan review's markers, nothing focused). */
    val marks: Map<Int, StickerProblemMark> by derivedStateOf {
        numbered.flatMap { it.piece }.distinct().associateWith { sticker ->
            StickerProblemMark(
                numbers = numbered.indices.filter { sticker in numbered[it].piece }.map { it + 1 },
                conflicts = numbered.indices.filter { sticker in numbered[it].positions }.map { it + 1 },
                focusedNumber = null,
            )
        }
    }

    /** Sticker indices of the numbered problems' pieces, to outline with the cube-highlight rule. */
    val outlined: Set<Int> by derivedStateOf { marks.keys }

    /** Faces (URFDLB indices) whose tab carries the warning dot: holding an [outlined] sticker, or [checkFace]. */
    val facesWithProblems: Set<Int> by derivedStateOf {
        outlined.mapTo(mutableSetOf()) { it / PER_FACE }.also { faces -> checkFace?.let(faces::add) }
    }

    /** The validated state of exactly [facelets], or null while they are invalid or faces are [unentered]. */
    val validState: CubeState? by derivedStateOf {
        if (unentered.isNotEmpty()) null else (CubeValidator.validate(facelets) as? ValidationResult.Valid)?.state
    }

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

    /**
     * Selects face [face]'s first editable sticker (the full-net status strip's tab tap), so the cursor moves there.
     * Out-of-range faces are ignored.
     */
    fun selectFace(face: Int) {
        if (face !in 0 until FACES) return
        select(face * PER_FACE)
    }

    /**
     * Paints the selected sticker [color] and marks it [set], then advances the selection to the next editable sticker
     * in [StickerOrder] (URFDLB row-major, centers skipped, crossing faces), so [face] follows it. A tap
     * of the color the sticker already holds still advances. After sticker 53 the selection stays put
     * (no wrap). Ignored while nothing is selected. Completing a face while the selection moves to another face records
     * a [faceDone]; with every face entered and the stickers invalid, the faces' turns are then tried.
     */
    fun paint(color: Color) {
        val index = selected ?: return
        val painted = index / PER_FACE
        val wasComplete = complete(painted)
        setSticker(index, color)
        val next = StickerOrder.next(index) ?: index
        select(next)
        if (!wasComplete && complete(painted) && next / PER_FACE != painted) {
            faceDone = FaceDone(painted, next / PER_FACE, (faceDone?.serial ?: 0) + 1)
        }
        applyFit(index)
    }

    /**
     * Paint mode (#249): paints sticker [index] [color] and marks it [set] (a same-color tap included), with the same
     * [cueIndex] and turn fit as [paint], but leaves the selection where it is and records no [faceDone]. Centers and
     * out-of-range indices are ignored.
     */
    fun paintAt(
        index: Int,
        color: Color,
    ) {
        if (!isEditable(index)) return
        setSticker(index, color)
        applyFit(index)
    }

    /** Sets sticker [index] to [color], marks it [set], clears [turned] and puts the update cue on it. */
    private fun setSticker(
        index: Int,
        color: Color,
    ) {
        if (facelets.colors[index] != color) {
            facelets = Facelets(facelets.colors.toMutableList().also { it[index] = color })
        }
        set = set + index
        turned = emptyList()
        cueIndex = index
    }

    /**
     * With a unique quarter-turn fit, replaces the stickers by it and turns the set flags, the selection and the update
     * cue of the just-painted sticker [index] with their faces.
     */
    private fun applyFit(index: Int) {
        val resolved = fit as? FaceTurnFit.Resolved ?: return
        if (resolved.turns.isEmpty()) return
        val turns = resolved.turns.mapKeys { it.key.ordinal }

        fun moved(sticker: Int): Int = turnedIndex(sticker, turns[sticker / PER_FACE] ?: 0)
        set = set.mapTo(mutableSetOf(), ::moved)
        selected = selected?.let(::moved)
        cueIndex = moved(index)
        facelets = resolved.facelets
        turned = resolved.turned.map { it.ordinal }
    }

    companion object {
        private const val PER_FACE = 9
        private const val PER_COLOR = 9
        private const val CENTER_CELL = 4
        private const val FACES = 6

        /** The Learner's face order (URFDLB indices): U, F, R, B, L, D. */
        val LEARNER_ORDER = listOf(0, 2, 1, 5, 4, 3)

        /** Whether sticker [index] is a center (fixed to its home color). */
        fun isCenter(index: Int): Boolean = index % PER_FACE == CENTER_CELL

        /** Whether the Learner may select and paint sticker [index]. */
        fun isEditable(index: Int): Boolean = index in 0 until Facelets.SIZE && !isCenter(index)

        private fun stickersOf(face: Int): IntRange = face * PER_FACE until (face + 1) * PER_FACE

        /** Where sticker [index] lands when its face is turned [turns] clockwise quarter turns (centers stay). */
        internal fun turnedIndex(
            index: Int,
            turns: Int,
        ): Int {
            val face = index / PER_FACE
            val cells = ScanAssembler.rotateClockwise((0 until PER_FACE).toList(), turns)
            return face * PER_FACE + cells.indexOf(index % PER_FACE)
        }

        /**
         * Persists, across recreation: the 54 color ordinals, the selected index (-1 for none), the shown face, fresh
         * (1 or 0), the set stickers and the turned faces (each as a count then the indices), and seeded (1 or 0). The face
         * to check is derived from the stickers, so it is not saved.
         */
        internal val Saver: Saver<ManualCubeEditorState, Any> =
            listSaver(
                save = { state ->
                    buildList {
                        addAll(state.facelets.colors.map { it.ordinal })
                        add(state.selected ?: -1)
                        add(state.face)
                        add(if (state.fresh) 1 else 0)
                        add(state.set.size)
                        addAll(state.set.sorted())
                        add(state.turned.size)
                        addAll(state.turned)
                        add(if (state.seeded) 1 else 0)
                    }
                },
                restore = { saved ->
                    var at = Facelets.SIZE
                    val colors = Facelets(saved.take(Facelets.SIZE).map { Color.entries[it] })
                    val selected = saved[at++]
                    val face = saved[at++]
                    val fresh = saved[at++] == 1
                    val set = saved.subList(at + 1, at + 1 + saved[at]).toSet().also { at += 1 + it.size }
                    val turned = saved.subList(at + 1, at + 1 + saved[at]).toList().also { at += 1 + it.size }
                    val seeded = saved.getOrNull(at) == 1
                    ManualCubeEditorState(colors, fresh, set, seeded).also { state ->
                        state.selected = null
                        state.select(selected)
                        state.showFace(face)
                        state.turned = turned
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
