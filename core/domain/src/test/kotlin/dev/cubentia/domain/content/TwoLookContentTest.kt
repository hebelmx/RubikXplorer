package dev.cubentia.domain.content

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.lastlayer.CaseStateResult
import dev.cubentia.domain.lastlayer.LastLayerMatcher
import dev.cubentia.domain.predicate.LastLayerStepReached
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TwoLookContentTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle

    @Test fun `all sixteen lessons derive legal paths from exactly the selected Library Algorithms`() {
        assertEquals(7, bundle.stages.size)
        assertEquals(listOf(10, 6), bundle.twoLookStages.map { it.lessons.size })
        val expected =
            listOf("02", "45", "44", "21", "22", "23", "24", "25", "26", "27").map { "alg-oll-$it-a" } +
                listOf("t", "y", "ua", "ub", "h", "z").map { "alg-pll-$it-a" }
        assertEquals(
            expected,
            bundle.lessonAlgorithms.values
                .flatten()
                .map { it.value },
        )
        bundle.twoLookStages.forEachIndexed { stageIndex, stage ->
            stage.lessons.forEachIndexed { index, lesson ->
                assertEquals("lesson-%02d-%02d".format(stageIndex + 8, index + 1), lesson.id.value)
                assertTrue(lesson.prerequisites.isEmpty())
                assertTrue(lesson.completion is LastLayerStepReached)
                val algorithms = bundle.lessonAlgorithms.getValue(lesson.id).map { id -> bundle.algorithms.single { it.id == id } }
                val notation = algorithms.joinToString(" ") { it.notation }
                assertEquals(notation, lesson.demonstration)
                assertEquals(algorithms.map { it.notation }, lesson.steps.map { it.moves })
                val decoded = FaceletString.decode(lesson.entryState) as FaceletStringResult.Decoded
                var state = (CubeValidator.validate(decoded.facelets) as ValidationResult.Valid).state
                assertEquals((LastLayerMatcher.caseState(notation, Color.YELLOW) as CaseStateResult.Derived).state, state)
                assertTrue(lesson.preserve.isSatisfied(state), lesson.id.value)
                assertFalse(lesson.completion.isSatisfied(state), lesson.id.value)
                // AD-6: along the path, step k's done is false before its last Move and holds at its end
                // and at every later step boundary; the last step's done is the Completion Predicate.
                val stepMoves = lesson.steps.map { (Notation.parse(it.moves) as NotationResult.Parsed).moves }
                val ends = stepMoves.runningFold(0) { total, m -> total + m.size }.drop(1)
                val states = stepMoves.flatten().runningFold(state) { s, move -> s.apply(move) }
                lesson.steps.forEachIndexed { k, step ->
                    (0 until ends[k]).forEach { i ->
                        assertFalse(step.done.isSatisfied(states[i]), "${lesson.id.value} step ${k + 1} done after $i Moves")
                    }
                    ends.drop(k).forEach { end ->
                        assertTrue(step.done.isSatisfied(states[end]), "${lesson.id.value} step ${k + 1} not done after $end Moves")
                        assertTrue(lesson.preserve.isSatisfied(states[end]), lesson.id.value)
                    }
                }
                assertEquals(lesson.completion, lesson.steps.last().done, lesson.id.value)
                state = states.last()
                assertTrue(lesson.completion.isSatisfied(state))
            }
        }
    }

    @Test fun `demonstrations agree with the two look solve judge and matcher predicates`() {
        val lessons = bundle.twoLookStages.flatMap { it.lessons }
        val ids = lessons.flatMap { bundle.lessonAlgorithms.getValue(it.id) }.toSet()
        val algorithms = bundle.algorithms.filter { it.id in ids }.map { (Notation.parse(it.notation) as NotationResult.Parsed).moves }
        lessons.forEach { lesson ->
            val moves = (Notation.parse(lesson.demonstration) as NotationResult.Parsed).moves
            assertEquals(
                0,
                dev.cubentia.domain.lastlayer.TwoLookSolve
                    .offPathMoves(moves, algorithms),
                lesson.id.value,
            )
            assertTrue(lesson.completion is LastLayerStepReached)
            val decoded = FaceletString.decode(lesson.entryState) as FaceletStringResult.Decoded
            val entry = (CubeValidator.validate(decoded.facelets) as ValidationResult.Valid).state
            assertTrue(lesson.completion.isSatisfied(entry.apply(moves)), lesson.id.value)
        }
    }

    @Test fun `authored files contain only Algorithm references`() {
        val manifest = ContentLoader.json.decodeFromString<BundleManifest>(ContentSource.SHIPPED.read("bundle.json")!!.decodeToString())
        assertEquals(2, manifest.twoLook.size)
        manifest.twoLook.forEach { path ->
            val text = ContentSource.SHIPPED.read(path)!!.decodeToString()
            assertFalse(Regex("\"(moves|notation|demonstration|entryState)\"").containsMatchIn(text), path)
        }
    }
}
