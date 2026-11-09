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
import dev.cubentia.domain.cube.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The editor's I/O matrix on the plain state holder. */
class ManualCubeEditorStateTest {
    @Test
    fun `a selected sticker takes the painted color and selection stays`() {
        val state = ManualCubeEditorState()
        state.select(0)
        state.paint(Color.RED)
        assertEquals(Color.RED, state.facelets.colors[0])
        assertEquals(0, state.selected)
        assertEquals(solved.drop(1), state.facelets.colors.drop(1))
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
    }

    @Test
    fun `wrong counts give one line and no outlines`() {
        val state = ManualCubeEditorState()
        state.select(0)
        state.paint(Color.RED)
        assertTrue(state.problems.single() is ValidationResult.WrongColorCounts)
        assertEquals(emptySet<Int>(), state.outlined)
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
    }

    @Test
    fun `outlines are the union of every positioned problem`() {
        val entry = Facelets(EditorFixtures.swap(EditorFixtures.swap(solved, 9, 20), 10, 7))
        val state = ManualCubeEditorState(entry)
        assertEquals(2, state.problems.size)
        assertEquals(setOf(5, 7, 8, 9, 10, 12, 19, 20, 23), state.outlined)
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
        assertEquals(setOf(0, 1, 2, 4), state.facesWithProblems)
    }

    @Test
    fun `a twisted corner and a flipped edge give two lines and no outlines`() {
        val state = ManualCubeEditorState(twistedAndFlipped)
        assertEquals(listOf(ValidationResult.TwistedCorner, ValidationResult.FlippedEdge), state.problems)
        assertEquals(emptySet<Int>(), state.outlined)
        assertNull(state.validState)
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
        state.paint(Color.WHITE)
        assertEquals(CubeState.SOLVED.toFacelets(), state.validState?.toFacelets())
    }
}
