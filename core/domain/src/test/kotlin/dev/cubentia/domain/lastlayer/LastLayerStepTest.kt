package dev.cubentia.domain.lastlayer

import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.predicate.LastLayerStepReached
import dev.cubentia.domain.predicate.Predicate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LastLayerStepTest {
    private fun moves(text: String) = (Notation.parse(text) as NotationResult.Parsed).moves

    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle

    @Test fun `every step counts the same pieces as its progress and round trips through JSON`() {
        LastLayerStep.entries.forEach { step ->
            val predicate = LastLayerStepReached(step)
            val json = ContentLoader.json.encodeToString<Predicate>(predicate)
            assertEquals(predicate, ContentLoader.json.decodeFromString<Predicate>(json))
            bundle.twoLookStages.flatMap { it.lessons }.forEach { lesson ->
                val state = (LastLayerMatcher.caseState(lesson.demonstration, Color.YELLOW) as CaseStateResult.Derived).state
                assertEquals(predicate.preservedPieces(state).size, predicate.progress(state).done)
                assertEquals(predicate.progress(state).done == step.pieces, predicate.isSatisfied(state))
                assertEquals(predicate.progress(state), predicate.progress(state.apply(moves("x2"))))
                assertEquals(predicate.progress(state), predicate.progress(state.apply(moves("y"))))
            }
            val broken = CubeState.SOLVED.apply(moves("R"))
            assertFalse(predicate.isSatisfied(broken))
            assertEquals(0, predicate.progress(broken).done)
        }
    }

    @Test fun `permutation goals choose the best AUF and distinguish a corner goal from solved edges`() {
        val solved = CubeState.SOLVED.copy(orientation = Orientation.of(Face.D, Face.F))
        for (auf in listOf("", "U", "U2", "U'", "y U")) {
            LastLayerStep.entries.forEach { step ->
                assertTrue(LastLayerStepReached(step).isSatisfied(solved.apply(moves(auf))), "$step $auf")
            }
        }
        val ua = bundle.algorithms.single { it.id.value == "alg-pll-ua-a" }
        val state = (LastLayerMatcher.caseState(ua, Color.YELLOW) as CaseStateResult.Derived).state
        assertTrue(LastLayerStepReached(LastLayerStep.CORNERS_PERMUTED).isSatisfied(state))
        assertFalse(LastLayerStepReached(LastLayerStep.SOLVED_UP_TO_AUF).isSatisfied(state))
    }

    private fun caseState(id: String): CubeState =
        (LastLayerMatcher.caseState(bundle.algorithms.single { it.id.value == id }, Color.YELLOW) as CaseStateResult.Derived).state

    private fun done(state: CubeState): Map<LastLayerStep, Int> =
        LastLayerStep.entries.associateWith { LastLayerStepReached(it).progress(state).done }

    @Test fun `partial Cases count the pieces each goal already meets`() {
        // OLL 45 (T shape): a line of two yellow edges and the two corners beside it face up.
        val oll45 = done(caseState("alg-oll-45-a"))
        assertEquals(2, oll45.getValue(LastLayerStep.EDGES_ORIENTED))
        assertEquals(4, oll45.getValue(LastLayerStep.ORIENTED))
        // Ua: all oriented, corners home, three edges cycle, so one edge sits home.
        assertEquals(
            mapOf(
                LastLayerStep.EDGES_ORIENTED to 4,
                LastLayerStep.ORIENTED to 8,
                LastLayerStep.CORNERS_PERMUTED to 4,
                LastLayerStep.SOLVED_UP_TO_AUF to 5,
            ),
            done(caseState("alg-pll-ua-a")),
        )
        // T: two adjacent corners and two opposite edges swap; no U turn places more than two of each.
        assertEquals(
            mapOf(
                LastLayerStep.EDGES_ORIENTED to 4,
                LastLayerStep.ORIENTED to 8,
                LastLayerStep.CORNERS_PERMUTED to 2,
                LastLayerStep.SOLVED_UP_TO_AUF to 4,
            ),
            done(caseState("alg-pll-t-a")),
        )
    }
}
