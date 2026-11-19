package dev.cubentia.domain.cube

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class FaceTurnMergeTest {
    @Test
    fun `merges only consecutive turns of the same face until stable`() {
        val examples =
            listOf(
                "B2 D' L2 F' U2 R R2" to "B2 D' L2 F' U2 R'",
                "R R'" to "",
                "U2 U2" to "",
                "R U U' R" to "R2",
                "R R R" to "R'",
                "R U F" to "R U F",
                "" to "",
                "R L R" to "R L R",
            )
        for ((input, expected) in examples) {
            val moves = (Notation.parse(input) as NotationResult.Parsed).moves.map { it as FaceTurn }
            assertEquals(expected, Notation.format(moves.mergeSameFaceTurns()), input)
        }
    }

    @Test
    fun `merging preserves the cube state and leaves no adjacent same-face turns`() {
        val random = Random(202)
        for (length in 0..40) {
            repeat(50) {
                val input =
                    buildList<FaceTurn> {
                        repeat(length) {
                            val previous = lastOrNull()
                            val face = if (previous != null && random.nextBoolean()) previous.face else Face.entries.random(random)
                            add(FaceTurn(face, Turn.entries.random(random)))
                        }
                    }
                val merged = input.mergeSameFaceTurns()
                assertTrue(merged.zipWithNext().none { (first, second) -> first.face == second.face }, input.toString())
                assertEquals(CubeState.SOLVED.apply(input), CubeState.SOLVED.apply(merged), input.toString())
            }
        }
    }
}
