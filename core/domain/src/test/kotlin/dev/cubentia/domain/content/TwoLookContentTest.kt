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
                lesson.steps.forEach { step ->
                    state = state.apply((Notation.parse(step.moves) as NotationResult.Parsed).moves)
                    assertTrue(step.done.isSatisfied(state), lesson.id.value)
                    assertTrue(lesson.preserve.isSatisfied(state), lesson.id.value)
                }
                assertTrue(lesson.completion.isSatisfied(state))
            }
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
