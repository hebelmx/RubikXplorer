package dev.rubikxplorer.app.session

import dev.rubikxplorer.app.render.PresentationItem
import dev.rubikxplorer.application.session.Presentation
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Turn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The reducer's presentation reaches the renderer as the same kind of step, Move, revision and target. */
class PresentationMappingTest {
    private val r = FaceTurn(Face.R, Turn.CLOCKWISE)
    private val turned = CubeState.SOLVED.apply(r)

    @Test
    fun `animate maps to an animate item with the same move, revision and target`() {
        assertEquals(
            PresentationItem.Animate(r, 7, turned.toFacelets()),
            Presentation.Animate(r, 7, turned).toItem(),
        )
    }

    @Test
    fun `snap maps to a snap item with the same revision and target`() {
        assertEquals(
            PresentationItem.Snap(3, CubeState.SOLVED.toFacelets()),
            Presentation.Snap(3, CubeState.SOLVED).toItem(),
        )
    }

    @Test
    fun `a list maps item by item, in order`() {
        val items = listOf(Presentation.Snap(1, CubeState.SOLVED), Presentation.Animate(r, 2, turned)).toItems()
        assertEquals(
            listOf(PresentationItem.Snap(1, CubeState.SOLVED.toFacelets()), PresentationItem.Animate(r, 2, turned.toFacelets())),
            items,
        )
    }
}
