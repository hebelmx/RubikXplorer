package dev.cubentia.application.usecase

import dev.cubentia.application.session.entryState
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.lastlayer.LastLayerStep
import dev.cubentia.domain.predicate.LastLayerStepReached
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TwoLookPracticeTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val algorithms = bundle.algorithms.map { (Notation.parse(it.notation) as NotationResult.Parsed).moves }

    @Test fun `seeded fresh scrambles have solved F2L yellow on U and LL unsolved even with AUF`() {
        val first = TwoLookPractice(SeededRandomSource(98))
        val second = TwoLookPractice(SeededRandomSource(98))
        val entries =
            (1..20).map {
                val lesson = first.lesson(algorithms)
                assertEquals(lesson, second.lesson(algorithms))
                val state = entryState(lesson)
                assertEquals(Color.YELLOW, state.orientation.colorAt(Face.U))
                assertTrue(lesson.preserve.isSatisfied(state))
                assertFalse(lesson.completion.isSatisfied(state))
                assertFalse(LastLayerStepReached(LastLayerStep.SOLVED_UP_TO_AUF).isSatisfied(state))
                state
            }
        assertTrue(entries.distinct().size > 1)
    }
}
