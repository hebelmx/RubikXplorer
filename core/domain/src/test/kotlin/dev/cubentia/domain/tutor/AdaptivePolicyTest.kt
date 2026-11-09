package dev.cubentia.domain.tutor

import dev.cubentia.domain.content.AdaptiveThresholds
import dev.cubentia.domain.text.Text
import dev.cubentia.domain.tutor.TutorFixtures.state
import dev.cubentia.domain.tutor.TutorFixtures.twoStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 4.6 (FR-13): adaptive assistance's level table, its line, the reduced ladder top and the guided next Move. */
class AdaptivePolicyTest {
    private val shipped = TutorFixtures.CONFIG.adaptive

    @Test
    fun `the shipped thresholds explain after one mistake, demonstrate after two, guide after three and reduce after two clean steps`() {
        val table =
            listOf(
                Triple(0, 0, AssistanceLevel.USUAL),
                Triple(0, 1, AssistanceLevel.USUAL),
                Triple(0, 2, AssistanceLevel.REDUCED),
                Triple(0, 5, AssistanceLevel.REDUCED),
                Triple(1, 0, AssistanceLevel.EXPLAIN),
                Triple(1, 5, AssistanceLevel.EXPLAIN),
                Triple(2, 0, AssistanceLevel.DEMONSTRATE),
                Triple(3, 0, AssistanceLevel.GUIDE),
                Triple(9, 9, AssistanceLevel.GUIDE),
            )
        table.forEach { (mistakes, clean, expected) ->
            assertEquals(expected, AdaptivePolicy.level(shipped, mistakes, clean), "mistakes $mistakes, clean $clean")
        }
    }

    @Test
    fun `each level changes exactly at its configured threshold`() {
        val config = AdaptiveThresholds(2, 4, 6, 3)
        val table =
            listOf(
                Triple(1, 0, AssistanceLevel.USUAL),
                Triple(2, 0, AssistanceLevel.EXPLAIN),
                Triple(3, 0, AssistanceLevel.EXPLAIN),
                Triple(4, 0, AssistanceLevel.DEMONSTRATE),
                Triple(5, 0, AssistanceLevel.DEMONSTRATE),
                Triple(6, 0, AssistanceLevel.GUIDE),
                Triple(0, 2, AssistanceLevel.USUAL),
                Triple(0, 3, AssistanceLevel.REDUCED),
                Triple(1, 3, AssistanceLevel.REDUCED),
                Triple(2, 3, AssistanceLevel.EXPLAIN),
            )
        table.forEach { (mistakes, clean, expected) ->
            assertEquals(expected, AdaptivePolicy.level(config, mistakes, clean), "mistakes $mistakes, clean $clean")
        }
    }

    @Test
    fun `each level sets a floor and a top on the Hint Ladder, in order of help`() {
        assertEquals(
            listOf(
                AssistanceLevel.REDUCED,
                AssistanceLevel.USUAL,
                AssistanceLevel.EXPLAIN,
                AssistanceLevel.DEMONSTRATE,
                AssistanceLevel.GUIDE,
            ),
            AssistanceLevel.entries,
        )
        assertEquals(HintRung.NONE to HintRung.TARGET, AssistanceLevel.REDUCED.floor to AssistanceLevel.REDUCED.top)
        assertEquals(HintRung.NONE to HintRung.DEMONSTRATION, AssistanceLevel.USUAL.floor to AssistanceLevel.USUAL.top)
        assertEquals(HintRung.CLUE to HintRung.DEMONSTRATION, AssistanceLevel.EXPLAIN.floor to AssistanceLevel.EXPLAIN.top)
        assertEquals(HintRung.TARGET to HintRung.DEMONSTRATION, AssistanceLevel.DEMONSTRATE.floor to AssistanceLevel.DEMONSTRATE.top)
        assertEquals(HintRung.TARGET to HintRung.DEMONSTRATION, AssistanceLevel.GUIDE.floor to AssistanceLevel.GUIDE.top)
    }

    @Test
    fun `the line says why help changed, with the mistake count, and is absent at the usual level`() {
        assertNull(AdaptivePolicy.line(AssistanceLevel.USUAL, 0))
        assertNull(AdaptivePolicy.line(AssistanceLevel.USUAL, 4))
        assertEquals(Text("msg.assistance.explain", mapOf("count" to 1)), AdaptivePolicy.line(AssistanceLevel.EXPLAIN, 1))
        assertEquals(Text("msg.assistance.demonstrate", mapOf("count" to 2)), AdaptivePolicy.line(AssistanceLevel.DEMONSTRATE, 2))
        assertEquals(Text("msg.assistance.guide", mapOf("count" to 3)), AdaptivePolicy.line(AssistanceLevel.GUIDE, 3))
        assertEquals(Text("msg.assistance.reduced", mapOf("count" to 0)), AdaptivePolicy.line(AssistanceLevel.REDUCED, 0))
    }

    @Test
    fun `a reduced ladder stops climbing at its top`() {
        assertTrue(HintLadder.canClimb(TeachingMode.TEST_ME, HintRung.CLUE, false, HintRung.TARGET))
        assertFalse(HintLadder.canClimb(TeachingMode.TEST_ME, HintRung.TARGET, false, HintRung.TARGET))
        assertFalse(HintLadder.canClimb(TeachingMode.TEST_ME, HintRung.DEMONSTRATION, false, HintRung.TARGET))
        assertTrue(HintLadder.canClimb(TeachingMode.TEST_ME, HintRung.TARGET, false))
    }

    @Test
    fun `the next Move is the next authored Move on the path, and null off it or after the final Move`() {
        val entry = state("F U'")
        val (u, fPrime) = TutorFixtures.moves("U F'")
        assertEquals(u, Tutor.nextMove(twoStep, entry, entry))
        assertEquals(fPrime, Tutor.nextMove(twoStep, entry, state("F U' U")))
        assertNull(Tutor.nextMove(twoStep, entry, state("F U' R")))
        assertNull(Tutor.nextMove(twoStep, entry, state("F U' U F'")))
    }

    @Test
    fun `halfway through an authored half turn the next Move is the quarter that finishes it`() {
        val entry = state("L2 B2 R2 F2")
        val (f, fPrime) = TutorFixtures.moves("F F'")
        assertEquals(f, Tutor.nextMove(TutorFixtures.fourEdges, entry, state("L2 B2 R2 F2 F")))
        assertEquals(fPrime, Tutor.nextMove(TutorFixtures.fourEdges, entry, state("L2 B2 R2 F2 F'")))
        assertTrue(Tutor.isOnPath(TutorFixtures.fourEdges, entry, state("L2 B2 R2 F2 F"), f))
        assertEquals(TutorFixtures.moves("R2").single(), Tutor.nextMove(TutorFixtures.fourEdges, entry, state("L2 B2 R2 F2 F F")))
    }
}
