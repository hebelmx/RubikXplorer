package dev.cubentia.domain.lastlayer

import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.MoveHistory
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TwoLookSolveTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val ids =
        bundle.twoLookStages
            .flatMap { it.lessons }
            .flatMap { bundle.lessonAlgorithms.getValue(it.id) }
            .toSet()
    private val algorithms = bundle.algorithms.filter { it.id in ids }.map { parse(it.notation) }

    private fun parse(text: String): List<Move> = (Notation.parse(text) as NotationResult.Parsed).moves

    private fun errors(text: String) = TwoLookSolve.offPathMoves(parse(text), algorithms)

    @Test fun `every selected algorithm and dot composition is covered`() {
        algorithms.forEach { assertEquals(0, TwoLookSolve.offPathMoves(it, algorithms), Notation.format(it)) }
        val a45 = bundle.algorithms.single { it.id.value == "alg-oll-45-a" }.notation
        val a44 = bundle.algorithms.single { it.id.value == "alg-oll-44-a" }.notation
        assertEquals(0, errors("$a45 U $a44"))
        assertEquals(0, errors("y U' $a45 U2"))
    }

    @Test fun `equivalent spellings and free adjustments are covered`() {
        algorithms.forEach { algorithm ->
            val expanded =
                Notation
                    .format(algorithm)
                    .replace("U2", "U U")
                    .replace("r2", "L2 x2")
                    .replace("r'", "L' x'")
                    .replace("r", "L x")
            assertEquals(0, errors(expanded), expanded)
        }
        assertEquals(0, errors("x x' y U2 z z' U'"))
        assertEquals(1, errors("D"))
        val full = bundle.algorithms.filter { it.id !in ids }.first { errors(it.notation) > 0 }
        assertTrue(errors(full.notation) > 0)
    }

    @Test fun `U turns and Algorithms count only while the starting top layer is on U`() {
        // x' U x turns F: rotations must not turn a free U into any other face.
        assertEquals(1, errors("x' U x"))
        assertEquals(3, errors("x2 U U2 U' x2"))
        val a45 = bundle.algorithms.single { it.id.value == "alg-oll-45-a" }.notation
        assertTrue(errors("z $a45 z'") > 0)
    }

    @Test fun `a replaced history counts as one more error`() {
        val entry = dev.cubentia.domain.cube.CubeState.SOLVED
        val history = MoveHistory(entry).commit(parse("U").single())
        assertEquals(0, TwoLookSolve.offPathMoves(history, entry, algorithms))
        assertEquals(1, TwoLookSolve.offPathMoves(MoveHistory(history.state), entry, algorithms))
    }

    @Test fun `undone slips are absent from committed history`() {
        val history = MoveHistory().commit(parse("D").single()).undo()
        assertEquals(0, TwoLookSolve.offPathMoves(history.done, algorithms))
    }

    @Test fun `dynamic programming chooses the minimum uncovered moves`() {
        assertEquals(1, TwoLookSolve.offPathMoves(parse("R U D"), listOf(parse("R U"), parse("U D"))))
        assertEquals(3, TwoLookSolve.offPathMoves(parse("R F D"), emptyList()))
    }
}
