package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult
import dev.rubikxplorer.domain.predicate.Predicate
import dev.rubikxplorer.domain.predicate.WhiteCrossSolved
import dev.rubikxplorer.domain.text.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** AC 1, 2, 4: Test me's feedback is the Completion Predicate's progress before vs after a Move. */
class MoveFeedbackTest {
    private fun state(text: String): CubeState = CubeState.SOLVED.apply((Notation.parse(text) as NotationResult.Parsed).moves)

    private fun classify(
        before: String,
        after: String,
    ) = MoveFeedback.classify(WhiteCrossSolved, state(before), state(after))

    @Test
    fun `a Move that raises progress is progress`() {
        // SOLVED + F + U' has no cross edge home; U brings three back.
        assertEquals(MoveFeedback.PROGRESS, classify("F U'", "F U' U"))
        assertEquals(MoveFeedback.PROGRESS, classify("F", "F F'"))
    }

    @Test
    fun `a Move that leaves progress alone is neutral`() {
        assertEquals(MoveFeedback.NEUTRAL, classify("F", "F D"))
        assertEquals(MoveFeedback.NEUTRAL, classify("F", "F"))
    }

    @Test
    fun `a Move that lowers progress is harmful`() {
        assertEquals(MoveFeedback.HARMFUL, classify("F", "F U"))
        assertEquals(MoveFeedback.HARMFUL, classify("", "R"))
    }

    @Test
    fun `each feedback names its calm content string`() {
        assertEquals(Text("msg.test-me.progress"), MoveFeedback.PROGRESS.text)
        assertEquals(Text("msg.test-me.neutral"), MoveFeedback.NEUTRAL.text)
        assertEquals(Text("msg.test-me.harmful"), MoveFeedback.HARMFUL.text)
    }

    @Test
    fun `classify and every Predicate function take only Cube States, so no Teaching Mode is reachable`() {
        val classify = MoveFeedback.Companion::class.java.declaredMethods.single { it.name == "classify" }
        assertEquals(listOf(Predicate::class.java, CubeState::class.java, CubeState::class.java), classify.parameterTypes.toList())
        val predicateFunctions = Predicate::class.java.declaredMethods.filter { !it.isSynthetic }
        assertEquals(setOf("isSatisfied", "preservedPieces", "progress"), predicateFunctions.map { it.name }.toSet())
        predicateFunctions.forEach { function ->
            assertEquals(listOf(CubeState::class.java), function.parameterTypes.toList(), function.name)
        }
    }
}
