package dev.cubentia.app.ui.editor

import androidx.compose.runtime.saveable.SaverScope
import dev.cubentia.app.ui.editor.EditorFixtures.centers
import dev.cubentia.app.ui.editor.EditorFixtures.scrambled
import dev.cubentia.app.ui.editor.EditorFixtures.solved
import dev.cubentia.app.ui.editor.EditorFixtures.swappedEdges
import dev.cubentia.app.ui.editor.EditorFixtures.twistedAndFlipped
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.PieceKind
import dev.cubentia.domain.cube.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The editor's I/O matrix on the plain state holder. */
class ManualCubeEditorStateTest {
    @Test
    fun `a selected sticker takes the painted color and selection advances within the face`() {
        val state = ManualCubeEditorState()
        state.select(0)
        state.paint(Color.RED)
        assertEquals(Color.RED, state.facelets.colors[0])
        assertEquals(1, state.selected)
        assertEquals(0, state.face)
        assertEquals(solved.drop(1), state.facelets.colors.drop(1))
    }

    @Test
    fun `advance skips the center`() {
        val state = ManualCubeEditorState()
        state.select(3)
        state.paint(Color.BLUE)
        assertEquals(5, state.selected)
    }

    @Test
    fun `advance crosses into the next face and the shown face follows`() {
        val state = ManualCubeEditorState()
        state.select(8)
        state.paint(Color.RED)
        assertEquals(9, state.selected)
        assertEquals(1, state.face)
    }

    @Test
    fun `painting the color a sticker already holds still advances`() {
        val state = ManualCubeEditorState()
        state.select(0)
        state.paint(Color.WHITE)
        assertEquals(CubeState.SOLVED.toFacelets(), state.facelets)
        assertEquals(1, state.selected)
    }

    @Test
    fun `the last sticker keeps the selection without wrapping`() {
        val state = ManualCubeEditorState()
        state.select(52)
        state.paint(Color.RED)
        assertEquals(53, state.selected)
        state.paint(Color.GREEN)
        assertEquals(Color.RED, state.facelets.colors[52])
        assertEquals(Color.GREEN, state.facelets.colors[53])
        assertEquals(53, state.selected)
        assertEquals(5, state.face)
        assertEquals(solved.take(52), state.facelets.colors.take(52))
    }

    @Test
    fun `tapping an earlier sticker fixes it and advances from it`() {
        val state = ManualCubeEditorState()
        state.select(8)
        state.paint(Color.RED)
        state.paint(Color.RED)
        assertEquals(10, state.selected)
        state.select(2)
        state.paint(Color.GREEN)
        assertEquals(Color.GREEN, state.facelets.colors[2])
        assertEquals(3, state.selected)
        assertEquals(0, state.face)
    }

    @Test
    fun `centers are not selectable and are forced to home colors`() {
        val initial = Facelets(List(Facelets.SIZE) { Color.BLUE })
        val state = ManualCubeEditorState(initial)
        centers.forEach { assertEquals(solved[it], state.facelets.colors[it]) }
        centers.forEach {
            state.select(it)
            assertNull(state.selected)
        }
        state.select(-1)
        state.select(Facelets.SIZE)
        assertNull(state.selected)
        state.select(3)
        state.select(4)
        assertEquals(3, state.selected)
    }

    @Test
    fun `painting with nothing selected changes nothing`() {
        val state = ManualCubeEditorState()
        state.paint(Color.RED)
        assertEquals(CubeState.SOLVED.toFacelets(), state.facelets)
        assertNull(state.selected)
    }

    @Test
    fun `wrong counts give one counts line naming each color that is off, in palette order`() {
        val state = ManualCubeEditorState()
        state.select(18)
        state.paint(Color.RED)
        assertTrue(state.problems.single() is ValidationResult.WrongColorCounts)
        assertEquals(EditorProblem.Counts(listOf(Color.RED to 10, Color.GREEN to 8)), state.lines.first())
        // The counts line marks no sticker; the UFL corner it made (white, red, orange) is named on its own line.
        assertEquals(
            EditorProblem.Piece("msg.editor.piece-opposite", PieceKind.CORNER, listOf(0, 2, 4), 0, listOf(18, 38), listOf(6, 18, 38)),
            state.lines[1],
        )
        assertEquals(setOf(6, 18, 38), state.outlined)
        assertNull(state.validState)
    }

    @Test
    fun `an impossible corner outlines exactly its stickers`() {
        val state = ManualCubeEditorState()
        state.select(18)
        state.paint(Color.WHITE)
        state.select(8)
        state.paint(Color.GREEN)
        val problem = state.problems.single() as ValidationResult.ImpossiblePiece
        assertEquals(setOf(6, 8, 9, 18, 20, 38), state.outlined)
        assertEquals(problem.positions.toSet(), state.outlined)
        assertNull(state.validState)
        // Each piece is named by its faces (U/D, then F/B, then R/L) and numbered, with markers on its stickers.
        assertEquals(
            listOf(
                EditorProblem.Piece(
                    "msg.editor.piece-repeats.green",
                    PieceKind.CORNER,
                    listOf(0, 2, 1),
                    2,
                    listOf(8, 20),
                    listOf(8, 9, 20),
                ),
                EditorProblem.Piece(
                    "msg.editor.piece-repeats.white",
                    PieceKind.CORNER,
                    listOf(0, 2, 4),
                    2,
                    listOf(6, 18),
                    listOf(6, 18, 38),
                ),
            ),
            state.lines,
        )
        assertEquals(StickerProblemMark(listOf(1), listOf(1), null), state.marks[8])
        assertEquals(StickerProblemMark(listOf(1), emptyList(), null), state.marks[9])
        assertEquals(StickerProblemMark(listOf(2), emptyList(), null), state.marks[38])
    }

    @Test
    fun `outlines are the union of every positioned problem`() {
        val entry = Facelets(EditorFixtures.swap(EditorFixtures.swap(solved, 9, 20), 10, 7))
        val state = ManualCubeEditorState(entry)
        assertEquals(2, state.problems.size)
        assertEquals(setOf(5, 7, 8, 9, 10, 12, 19, 20, 23), state.outlined)
        // The UR edge shows two whites; the other bad pieces are named as impossible, each once.
        assertEquals(
            listOf(
                "msg.editor.piece-repeats.white",
                "msg.editor.piece-impossible",
                "msg.editor.piece-impossible",
                "msg.editor.piece-impossible",
            ),
            state.lines.map { (it as EditorProblem.Piece).key },
        )
    }

    @Test
    fun `the saver round-trips painted stickers and the selection`() {
        val state = ManualCubeEditorState()
        state.select(18)
        state.paint(Color.WHITE)
        state.select(7)
        val saved = with(ManualCubeEditorState.Saver) { SaverScope { true }.save(state) }
        val restored = ManualCubeEditorState.Saver.restore(saved!!)!!
        assertEquals(state.facelets, restored.facelets)
        assertEquals(7, restored.selected)

        val unselected = ManualCubeEditorState()
        val savedNone = with(ManualCubeEditorState.Saver) { SaverScope { true }.save(unselected) }
        assertNull(ManualCubeEditorState.Saver.restore(savedNone!!)!!.selected)
    }

    @Test
    fun `the shown face follows the selection and switching faces clears a selection elsewhere`() {
        val state = ManualCubeEditorState()
        assertEquals(0, state.face)
        state.select(20)
        assertEquals(2, state.face)
        state.showFace(2)
        assertEquals(20, state.selected)
        state.showFace(5)
        assertEquals(5, state.face)
        assertNull(state.selected)
        state.showFace(6)
        state.showFace(-1)
        assertEquals(5, state.face)

        val saved = with(ManualCubeEditorState.Saver) { SaverScope { true }.save(state) }
        assertEquals(5, ManualCubeEditorState.Saver.restore(saved!!)!!.face)
    }

    @Test
    fun `faces with problems are the faces of outlined stickers`() {
        val state = ManualCubeEditorState()
        state.select(18)
        state.paint(Color.WHITE)
        state.select(8)
        state.paint(Color.GREEN)
        // The faces of the outlined pieces, plus the face to check (Up), which fits no turning.
        assertEquals(0, state.checkFace)
        assertEquals(setOf(0, 1, 2, 4), state.facesWithProblems)
    }

    @Test
    fun `a twisted corner and a flipped edge give two lines and no outlines`() {
        val state = ManualCubeEditorState(twistedAndFlipped)
        assertEquals(listOf(ValidationResult.TwistedCorner, ValidationResult.FlippedEdge), state.problems)
        assertEquals(state.problems.map { EditorProblem.Global(it.reason) }, state.lines)
        assertEquals(emptySet<Int>(), state.outlined)
        assertNull(state.validState)
        // Counts right and every piece legal: no face turn can fix it, so no face is named; its own lines explain.
        assertNull(state.checkFace)
        assertEquals(emptySet<Int>(), state.facesWithProblems)
    }

    @Test
    fun `swapped pieces give the parity line`() {
        val state = ManualCubeEditorState(swappedEdges)
        assertEquals(listOf(ValidationResult.ParityViolation), state.problems)
        assertNull(state.validState)
    }

    @Test
    fun `a valid entry has no problems and yields the exact validated state`() {
        listOf(CubeState.SOLVED.toFacelets(), scrambled).forEach { entry ->
            val state = ManualCubeEditorState(entry)
            assertEquals(emptyList<ValidationResult.Invalid>(), state.problems)
            val valid = state.validState
            assertNotNull(valid)
            assertEquals(entry, valid!!.toFacelets())
        }
    }

    @Test
    fun `repainting back to valid re-enables the valid state`() {
        val state = ManualCubeEditorState()
        state.select(0)
        state.paint(Color.RED)
        assertNull(state.validState)
        state.select(0)
        state.paint(Color.WHITE)
        assertEquals(CubeState.SOLVED.toFacelets(), state.validState?.toFacelets())
    }

    private val editable = (0 until Facelets.SIZE).filter(ManualCubeEditorState::isEditable)

    /** A fresh editor on [initial] with every editable sticker set but [unset], selecting [last] to paint next. */
    private fun almostEntered(
        initial: Facelets,
        last: Int = 53,
        unset: Set<Int> = emptySet(),
    ): ManualCubeEditorState =
        ManualCubeEditorState(initial, fresh = true, initialSet = editable.toSet() - unset - last).also { it.select(last) }

    @Test
    fun `a fresh entry selects the first sticker and shows no problem until every face is entered`() {
        val state = ManualCubeEditorState(fresh = true)
        assertEquals(0, state.selected)
        assertEquals(listOf(0, 2, 1, 5, 4, 3), state.unentered)
        assertEquals(emptyList<EditorProblem>(), state.lines)
        assertEquals(emptyList<ValidationResult.Invalid>(), state.problems)
        assertEquals(emptyMap<Int, StickerProblemMark>(), state.marks)
        assertEquals(emptySet<Int>(), state.facesWithProblems)
        assertNull(state.validState)
        // A different color on Up and same-color taps on Front and Left: Right, Back and Down are left, with no error.
        state.paint(Color.RED)
        state.select(18)
        state.paint(Color.GREEN)
        state.select(36)
        state.paint(Color.ORANGE)
        assertEquals(listOf(1, 5, 3), state.unentered)
        assertEquals(emptyList<EditorProblem>(), state.lines)
        assertNull(state.validState)
    }

    @Test
    fun `a caller's cube counts every face as entered and selects nothing`() {
        val state = ManualCubeEditorState(twistedAndFlipped)
        assertNull(state.selected)
        assertEquals(emptyList<Int>(), state.unentered)
        assertEquals(2, state.lines.size)
        assertNull(state.checkFace)
    }

    @Test
    fun `a face entered at 180 degrees is turned to the only valid cube and its set stickers turn with it`() {
        val entered = EditorFixtures.turned(scrambled, mapOf(0 to 2))
        val state = almostEntered(entered, unset = setOf(0))
        assertNull(state.validState)
        state.paint(scrambled.colors[53])
        assertEquals(scrambled, state.facelets)
        assertEquals(listOf(0), state.turned)
        assertEquals(scrambled, state.validState?.toFacelets())
        assertNull(state.checkFace)
        // Up's unset sticker 0 sits at 8 after the half turn.
        assertTrue(0 in state.set)
        assertFalse(8 in state.set)
        // The next paint clears the note.
        state.select(1)
        state.paint(state.facelets.colors[1])
        assertEquals(emptyList<Int>(), state.turned)
    }

    @Test
    fun `a valid entry as entered is kept even when another turning is also valid`() {
        val r2 = CubeState.SOLVED.apply((Notation.parse("R2") as NotationResult.Parsed).moves).toFacelets()
        val state = almostEntered(r2)
        state.paint(r2.colors[53])
        assertEquals(r2, state.facelets)
        assertEquals(emptyList<Int>(), state.turned)
        assertEquals(r2, state.validState?.toFacelets())
    }

    @Test
    fun `several valid turnings name the first involved face to check and mark its tab`() {
        val r2 = CubeState.SOLVED.apply((Notation.parse("R2") as NotationResult.Parsed).moves).toFacelets()
        val entered = EditorFixtures.turned(r2, mapOf(0 to 1))
        val state = almostEntered(entered)
        state.paint(entered.colors[53])
        assertEquals(entered, state.facelets)
        assertEquals(0, state.checkFace)
        assertTrue(0 in state.facesWithProblems)
        assertNull(state.validState)
    }

    @Test
    fun `an entry no turning fits names a face to check`() {
        val other = (9 until Facelets.SIZE).first { it % 9 != 4 && scrambled.colors[it] != scrambled.colors[0] }
        val entered = Facelets(EditorFixtures.swap(scrambled.colors, 0, other))
        val state = almostEntered(entered)
        state.paint(entered.colors[53])
        assertEquals(entered, state.facelets)
        assertNotNull(state.checkFace)
        assertNull(state.validState)
        assertTrue(state.lines.isNotEmpty())
    }

    @Test
    fun `a turn moves the selection and the update cue with their face`() {
        // Up entered at 180 degrees, its sticker 6 painted last: the fix turns Up by a half turn.
        val entered = EditorFixtures.turned(scrambled, mapOf(0 to 2))
        val state = almostEntered(entered, last = 6)
        state.paint(entered.colors[6])
        assertEquals(scrambled, state.facelets)
        // Painted 6 lands on 2; the selection, advanced to 7, lands on 1.
        assertEquals(2, state.cueIndex)
        assertEquals(1, state.selected)
        assertEquals(0, state.face)
    }

    @Test
    fun `a half-turn-symmetric face entered at 270 degrees turns by the fit's quarter turn`() {
        // After F' B, Up reads the same after a half turn; entered 270 degrees, one clockwise quarter turn fits.
        val cube = CubeState.SOLVED.apply((Notation.parse("F' B") as NotationResult.Parsed).moves).toFacelets()
        val entered = EditorFixtures.turned(cube, mapOf(0 to 3))
        val state = almostEntered(entered, last = 3, unset = setOf(0))
        state.paint(entered.colors[3])
        assertEquals(cube, state.facelets)
        assertEquals(listOf(0), state.turned)
        // One clockwise quarter turn: 3 -> 1, the advanced selection 5 -> 7, and the unset 0 -> 2.
        assertEquals(1, state.cueIndex)
        assertEquals(7, state.selected)
        assertFalse(2 in state.set)
        assertEquals((editable.toSet() - 2), state.set)
    }

    @Test
    fun `wrong counts never name a face to check`() {
        val entered = Facelets(solved.toMutableList().also { it[18] = Color.RED })
        val state = almostEntered(entered)
        state.paint(entered.colors[53])
        assertTrue(state.lines.first() is EditorProblem.Counts)
        assertNull(state.checkFace)
        assertEquals(emptyList<Int>(), state.turned)
        assertEquals(entered, state.facelets)
        // Non-fresh too: a caller's cube with wrong counts.
        assertNull(ManualCubeEditorState(entered).checkFace)
    }

    @Test
    fun `a restored invalid draft names its face to check at once`() {
        val r2 = CubeState.SOLVED.apply((Notation.parse("R2") as NotationResult.Parsed).moves).toFacelets()
        val state = ManualCubeEditorState(EditorFixtures.turned(r2, mapOf(0 to 1)), fresh = true, initialSet = editable.toSet())
        assertEquals(0, state.checkFace)
        assertTrue(0 in state.facesWithProblems)
        // Every sticker is set, so nothing is preselected.
        assertNull(state.selected)
    }

    @Test
    fun `a restored fresh draft selects its first unset sticker`() {
        val state = ManualCubeEditorState(fresh = true, initialSet = setOf(0, 1, 2, 3, 5, 6, 7, 8, 9, 10))
        assertEquals(11, state.selected)
        assertEquals(1, state.face)
        assertEquals(0, ManualCubeEditorState(fresh = true).selected)
    }

    @Test
    fun `set stickers and complete faces are tracked, a same-color tap included`() {
        val state = ManualCubeEditorState(fresh = true)
        assertFalse(state.complete(0))
        repeat(7) { state.paint(Color.WHITE) }
        assertEquals(setOf(0, 1, 2, 3, 5, 6, 7), state.set)
        assertFalse(state.complete(0))
        state.paint(Color.WHITE)
        assertTrue(state.complete(0))
        assertFalse(state.complete(1))
    }

    @Test
    fun `completing a face while auto-advance moves on records a face-done event`() {
        val state = ManualCubeEditorState(fresh = true)
        repeat(7) { state.paint(Color.WHITE) }
        assertNull(state.faceDone)
        state.paint(Color.WHITE)
        assertEquals(FaceDone(0, 1, 1), state.faceDone)
        // Completing Front with its first sticker last: the selection stays on Front, so no event.
        state.select(19)
        repeat(7) { state.paint(Color.GREEN) }
        state.select(18)
        state.paint(Color.GREEN)
        assertTrue(state.complete(2))
        assertEquals(FaceDone(0, 1, 1), state.faceDone)
        // Right's last sticker moves on to Front: the next event.
        state.select(9)
        repeat(8) { state.paint(Color.RED) }
        assertEquals(FaceDone(1, 2, 2), state.faceDone)
    }

    @Test
    fun `the saver round-trips fresh, set stickers, turned faces and the face to check`() {
        val entered = EditorFixtures.turned(scrambled, mapOf(0 to 2))
        val turned = almostEntered(entered, unset = setOf(0))
        turned.paint(scrambled.colors[53])
        val restored = roundTrip(turned)
        assertEquals(turned.facelets, restored.facelets)
        assertTrue(restored.fresh)
        assertEquals(turned.set, restored.set)
        assertEquals(listOf(0), restored.turned)
        assertEquals(turned.selected, restored.selected)

        val r2 = CubeState.SOLVED.apply((Notation.parse("R2") as NotationResult.Parsed).moves).toFacelets()
        val ambiguous = almostEntered(EditorFixtures.turned(r2, mapOf(0 to 1)))
        ambiguous.paint(ambiguous.facelets.colors[53])
        assertEquals(0, roundTrip(ambiguous).checkFace)

        val caller = roundTrip(ManualCubeEditorState(scrambled))
        assertFalse(caller.fresh)
        assertNull(caller.selected)
        assertNull(caller.checkFace)
    }

    @Test
    fun `selecting a face selects its first sticker and ignores out-of-range faces`() {
        val state = ManualCubeEditorState()
        assertNull(state.selected)
        state.selectFace(5)
        assertEquals(45, state.selected)
        assertEquals(5, state.face)
        state.selectFace(1)
        assertEquals(9, state.selected)
        assertEquals(1, state.face)
        state.selectFace(6)
        state.selectFace(-1)
        assertEquals(9, state.selected)
    }

    private fun roundTrip(state: ManualCubeEditorState): ManualCubeEditorState {
        val saved = with(ManualCubeEditorState.Saver) { SaverScope { true }.save(state) }
        return ManualCubeEditorState.Saver.restore(saved!!)!!
    }

    @Test
    fun `paint mode paints a sticker directly, keeps the selection and records no face-done event`() {
        val state = ManualCubeEditorState(fresh = true)
        listOf(0, 1, 2, 3, 5, 6, 7, 8).forEach { state.paintAt(it, Color.RED) }
        assertTrue((0 until 9).filter { it != 4 }.all { state.facelets.colors[it] == Color.RED })
        assertTrue(state.complete(0))
        assertEquals(0, state.selected)
        assertEquals(0, state.face)
        assertEquals(8, state.cueIndex)
        assertNull(state.faceDone)
        // A same-color paint still marks the sticker set; a center is ignored.
        state.paintAt(9, Color.RED)
        assertTrue(9 in state.set)
        state.paintAt(13, Color.BLUE)
        assertEquals(Color.RED, state.facelets.colors[13])
        assertFalse(13 in state.set)
        assertEquals(9, state.cueIndex)
    }

    @Test
    fun `paint mode applies the unique turn fit and moves the selection and the cue with the face`() {
        val entered = EditorFixtures.turned(scrambled, mapOf(0 to 2))
        val state = almostEntered(entered, last = 6)
        state.paintAt(6, entered.colors[6])
        assertEquals(scrambled, state.facelets)
        assertEquals(listOf(0), state.turned)
        // Painted 6 lands on 2, and so does the paused selection on 6.
        assertEquals(2, state.cueIndex)
        assertEquals(2, state.selected)
        assertEquals(scrambled, state.validState?.toFacelets())
    }
}
