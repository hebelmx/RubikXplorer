package dev.cubentia.domain.lastlayer

import dev.cubentia.domain.content.Algorithm
import dev.cubentia.domain.content.AlgorithmId
import dev.cubentia.domain.content.CaseId
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Color.BLUE
import dev.cubentia.domain.cube.Color.GREEN
import dev.cubentia.domain.cube.Color.ORANGE
import dev.cubentia.domain.cube.Color.RED
import dev.cubentia.domain.cube.Color.WHITE
import dev.cubentia.domain.cube.Color.YELLOW
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationError
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.cube.inverse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Story 9.3: the recognition diagram derived from a Case's state. */
class RecognitionDiagramTest {
    private val bundle = AlgorithmVerification.shippedBundle()
    private val matcher = (LastLayerMatcher.of(bundle.cases, bundle.algorithms) as LastLayerMatcherResult.Built).matcher
    private val primaries = bundle.algorithms.filter { it.isPrimary }

    private companion object {
        val YELLOW_FRAME: Orientation = Orientation.of(Face.D, Face.F)

        // Written from the Kociemba net, not from the view: facelets 0..8 are U, 9.. R, 18.. F, 36.. L, 45.. B.
        val FACELET_GRID: List<Int?> =
            listOf(
                listOf(null, 47, 46, 45, null),
                listOf(36, 0, 1, 2, 11),
                listOf(37, 3, 4, 5, 10),
                listOf(38, 6, 7, 8, 9),
                listOf(null, 18, 19, 20, null),
            ).flatten()
        val CORNERS = listOf(0, 4, 20, 24)
    }

    private fun moves(text: String): List<Move> = (Notation.parse(text) as NotationResult.Parsed).moves

    private fun diagramOf(
        algorithm: Algorithm,
        lastLayer: Color = YELLOW,
    ): RecognitionDiagram = (RecognitionDiagram.derive(algorithm, lastLayer) as RecognitionDiagramResult.Derived).diagram

    private fun algorithm(notation: String) = Algorithm(AlgorithmId("alg-x-a"), CaseId("case-x"), notation)

    @Test
    fun `each shipped Case's diagram is the yellow-framed facelets of its state`() {
        assertEquals(78, primaries.size)
        primaries.forEach { primary ->
            val facelets =
                CubeState.SOLVED
                    .copy(orientation = YELLOW_FRAME)
                    .apply(moves(primary.notation).inverse())
                    .toFacelets()
            val cells = diagramOf(primary).cells
            assertEquals(FACELET_GRID.map { it?.let(facelets.colors::get) }, cells, primary.id.value)
            assertEquals(RecognitionDiagram.SIZE * RecognitionDiagram.SIZE, cells.size, primary.id.value)
            assertEquals(YELLOW, cells[12], primary.id.value)
            CORNERS.forEach { assertNull(cells[it], "${primary.id} corner $it") }
        }
    }

    @Test
    fun `each shipped Case's yellow view matches exactly its own Case`() {
        primaries.forEach { primary ->
            val state = (LastLayerMatcher.caseState(primary, YELLOW) as CaseStateResult.Derived).state
            val view = (LastLayerView.of(state, YELLOW) as LastLayerReading.View).view
            assertEquals(setOf(primary.caseId), matcher.candidates(view), primary.id.value)
            assertEquals(diagramOf(primary), RecognitionDiagram.of(view), primary.id.value)
        }
    }

    @Test
    fun `the T perm reads as worked out by hand`() {
        val cells = diagramOf(bundle.algorithms.first { it.id.value == "alg-pll-t-a" }).cells
        assertEquals(List(9) { YELLOW }, listOf(6, 7, 8, 11, 12, 13, 16, 17, 18).map { cells[it] })
        assertEquals(listOf(GREEN, GREEN, ORANGE), listOf(21, 22, 23).map { cells[it] }, "F band")
        assertEquals(listOf(GREEN, RED, BLUE), listOf(9, 14, 19).map { cells[it] }, "R band")
        assertEquals(listOf(BLUE, BLUE, ORANGE), listOf(1, 2, 3).map { cells[it] }, "B band")
        assertEquals(listOf(RED, ORANGE, RED), listOf(5, 10, 15).map { cells[it] }, "L band")
    }

    @Test
    fun `the last-layer color can be chosen`() {
        val primary = primaries.first()
        val facelets = CubeState.SOLVED.apply(moves(primary.notation).inverse()).toFacelets()
        assertEquals(FACELET_GRID.map { it?.let(facelets.colors::get) }, diagramOf(primary, WHITE).cells)
    }

    @Test
    fun `the default last-layer color is YELLOW`() {
        val primary = primaries.first()
        assertEquals(RecognitionDiagram.derive(primary, YELLOW), RecognitionDiagram.derive(primary))
    }

    @Test
    fun `an unseen sticker stays empty`() {
        val diagram = RecognitionDiagram.of(LastLayerView.partial(YELLOW, GREEN, List(LastLayerView.STICKERS) { null }))
        assertEquals(List(25) { index -> YELLOW.takeIf { index == 12 } }, diagram.cells)
    }

    @Test
    fun `bad Notation is a sealed BadNotation`() {
        assertEquals(
            RecognitionDiagramResult.BadNotation(NotationError.BadToken("Q", 1)),
            RecognitionDiagram.derive(algorithm("R Q")),
        )
    }

    @Test
    fun `Notation that disturbs the first two layers is sealed FirstTwoLayersUnsolved`() {
        assertEquals(RecognitionDiagramResult.FirstTwoLayersUnsolved, RecognitionDiagram.derive(algorithm("R")))
    }

    @Test
    fun `caseState without a last-layer color stays in the home frame`() {
        primaries.forEach { primary ->
            assertEquals(
                CaseStateResult.Derived(CubeState.SOLVED.apply(moves(primary.notation).inverse())),
                LastLayerMatcher.caseState(primary),
                primary.id.value,
            )
        }
    }
}
