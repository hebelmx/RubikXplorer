package dev.cubentia.domain.tutor

import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.tutor.TutorFixtures.CONFIG
import dev.cubentia.domain.tutor.TutorFixtures.moves
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Story 4.1 I/O matrix row 6: the one bounded, deterministic candidate enumeration. */
class CandidateEnumeratorTest {
    private val faceTurns = CONFIG.moves

    @Test
    fun `18 face turns to depth 2 give 289 candidates, never the same face twice in a row`() {
        assertEquals(18, faceTurns.size)
        val all = CandidateEnumerator(CubeState.SOLVED, 2, faceTurns).candidates()
        assertEquals(1 + 18 + 18 * 15, all.size)
        all.forEach { candidate ->
            candidate.moves.zipWithNext().forEach { (a, b) ->
                assertFalse((a as FaceTurn).face == (b as FaceTurn).face, "${candidate.moves}")
            }
            assertEquals(CubeState.SOLVED.apply(candidate.moves), candidate.state)
        }
        assertEquals(all.size, all.map { it.moves }.toSet().size)
    }

    @Test
    fun `the order is breadth-first in move-set order and deterministic`() {
        val all = CandidateEnumerator(CubeState.SOLVED, 2, faceTurns).candidates()
        assertEquals(emptyList<Any>(), all.first().moves)
        assertEquals(faceTurns.map { listOf(it) }, all.subList(1, 19).map { it.moves })
        assertEquals(moves("U D"), all[19].moves)
        assertEquals(all.map { it.moves }, CandidateEnumerator(CubeState.SOLVED, 2, faceTurns).candidates().map { it.moves })
        assertEquals(all.map { it.moves.size }.sorted(), all.map { it.moves.size })
    }

    @Test
    fun `depth 0 gives only the expected state and the factory reads the config`() {
        val expected = TutorFixtures.state("R U")
        assertEquals(listOf(Candidate(emptyList(), expected)), CandidateEnumerator(expected, 0, faceTurns).candidates())
        val configured = CandidateEnumerator.of(expected, CONFIG)
        assertEquals(CONFIG.maxDepth, configured.maxDepth)
        assertEquals(CONFIG.moves, configured.moveSet)
        assertEquals(expected, configured.expected)
    }

    @Test
    fun `a repeated non-face Move is skipped too`() {
        val rotations = moves("x y")
        val all = CandidateEnumerator(CubeState.SOLVED, 2, rotations).candidates().map { it.moves }
        assertEquals(listOf(emptyList(), moves("x"), moves("y"), moves("x y"), moves("y x")), all)
    }

    @Test
    fun `consistentWith keeps only candidates that reach the observed state`() {
        val e = CandidateEnumerator(CubeState.SOLVED, 2, faceTurns)
        assertEquals(listOf(moves("R")), e.consistentWith(TutorFixtures.state("R")).map { it.moves })
        assertTrue(e.consistentWith(TutorFixtures.state("R U F")).isEmpty())
    }

    @Test
    fun `a negative depth or an empty move set is a programmer error`() {
        assertThrows<IllegalArgumentException> { CandidateEnumerator(CubeState.SOLVED, -1, faceTurns) }
        assertThrows<IllegalArgumentException> { CandidateEnumerator(CubeState.SOLVED, 2, emptyList()) }
    }
}
