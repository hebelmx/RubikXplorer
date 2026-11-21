package dev.cubentia.domain.tutor

import dev.cubentia.domain.content.CaseId
import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.lastlayer.CaseStateResult
import dev.cubentia.domain.lastlayer.LastLayerMatcher
import dev.cubentia.domain.lastlayer.LastLayerMatcherResult
import dev.cubentia.domain.predicate.ObjectiveProgress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlacementTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle
    private val stages = bundle.stages
    private val matcher = (LastLayerMatcher.of(bundle.cases, bundle.algorithms) as LastLayerMatcherResult.Built).matcher

    @Test
    fun `solved cube asks for a new scan or Stage 1`() {
        assertEquals(PlacementOutcome.Solved, Placement.evaluate(CubeState.SOLVED, stages))
    }

    @Test
    fun `one turn from solved names the turn by its center color`() {
        assertEquals(
            PlacementOutcome.NearlySolved(listOf(FaceTurnWords(Color.WHITE, Turn.CLOCKWISE))),
            Placement.evaluate(TutorFixtures.state("U'"), stages),
        )
    }

    @Test
    fun `two turns from solved names both turns in the order to make them`() {
        assertEquals(
            PlacementOutcome.NearlySolved(
                listOf(FaceTurnWords(Color.WHITE, Turn.COUNTERCLOCKWISE), FaceTurnWords(Color.RED, Turn.COUNTERCLOCKWISE)),
            ),
            Placement.evaluate(TutorFixtures.state("R U"), stages),
        )
    }

    @Test
    fun `a single turn wins over any pair`() {
        assertEquals(
            PlacementOutcome.NearlySolved(listOf(FaceTurnWords(Color.YELLOW, Turn.HALF))),
            Placement.evaluate(TutorFixtures.state("D2"), stages),
        )
    }

    @Test
    fun `a last-layer turn is nearly solved, not a non-white cross`() {
        assertEquals(
            PlacementOutcome.NearlySolved(listOf(FaceTurnWords(Color.WHITE, Turn.COUNTERCLOCKWISE))),
            Placement.evaluate(TutorFixtures.state("U"), stages),
        )
    }

    @Test
    fun `a scramble with no cross is nothing solved and starts at Stage 1`() {
        val scramble = TutorFixtures.state("R2 U' F L2 D B' R U2 B2 D' L F2 U' R2 D2 L' B U2")
        assertEquals(PlacementOutcome.NothingSolved(stages.first()), Placement.evaluate(scramble, stages))
    }

    @Test
    fun `a yellow cross without a white cross starts at First cross`() {
        assertEquals(
            PlacementOutcome.NonWhite(Color.YELLOW, firstTwoLayers = false, stage = stages[1], lastLayerStage = null),
            Placement.evaluate(TutorFixtures.state("R U R'"), stages),
        )
    }

    @Test
    fun `first two layers solved from yellow offer the last layer anyway`() {
        assertEquals(
            PlacementOutcome.NonWhite(Color.YELLOW, firstTwoLayers = true, stage = stages[1], lastLayerStage = stages[4]),
            Placement.evaluate(TutorFixtures.state("R U2 R' U' R U' R'"), stages),
        )
    }

    @Test
    fun `a white cross starts at first-layer corners with its corner count`() =
        assertEntry("lesson-03-01", PlacementOutcome.PartlySolved(stages[2], ObjectiveProgress(LESSON_03_01_CORNERS, 4)))

    @Test
    fun `a solved first layer starts at the middle layer with its edge count`() =
        assertEntry("lesson-04-01", PlacementOutcome.PartlySolved(stages[3], ObjectiveProgress(LESSON_04_01_EDGES, 4)))

    @Test
    fun `solved first two layers need the top oriented`() =
        assertEntry("lesson-05-01", PlacementOutcome.AtLastLayer(stages[4], oriented = false, case = null))

    @Test
    fun `an oriented last layer starts at Stage 6`() =
        assertEntry("lesson-06-02", PlacementOutcome.AtLastLayer(stages[5], oriented = true, case = null))

    @Test
    fun `lesson 06-01 still needs last-layer orientation`() =
        assertEntry("lesson-06-01", PlacementOutcome.AtLastLayer(stages[4], oriented = false, case = null))

    @Test
    fun `a shipped OLL Case is named with a matcher and not without one`() {
        val id = CaseId("case-oll-01")
        val primary = bundle.algorithms.single { it.isPrimary && it.caseId == id }
        val state = (LastLayerMatcher.caseState(primary, Color.YELLOW) as CaseStateResult.Derived).state

        assertEquals(PlacementOutcome.AtLastLayer(stages[4], oriented = false, case = id), Placement.evaluate(state, stages, matcher))
        assertEquals(PlacementOutcome.AtLastLayer(stages[4], oriented = false, case = null), Placement.evaluate(state, stages))
    }

    @Test
    fun `a shipped PLL Case is named on an oriented last layer`() {
        val id = CaseId("case-pll-ua")
        val primary = bundle.algorithms.single { it.isPrimary && it.caseId == id }
        val state = (LastLayerMatcher.caseState(primary, Color.YELLOW) as CaseStateResult.Derived).state

        assertEquals(PlacementOutcome.AtLastLayer(stages[5], oriented = true, case = id), Placement.evaluate(state, stages, matcher))
    }

    @Test
    fun `one-turn placement survives whole-cube rotation`() {
        assertEquals(
            PlacementOutcome.NearlySolved(listOf(FaceTurnWords(Color.WHITE, Turn.CLOCKWISE))),
            Placement.evaluate(TutorFixtures.state("U' x y"), stages),
        )
    }

    @Test
    fun `non-white placement survives whole-cube rotation`() {
        assertEquals(
            PlacementOutcome.NonWhite(Color.YELLOW, firstTwoLayers = false, stage = stages[1], lastLayerStage = null),
            Placement.evaluate(TutorFixtures.state("R U R' x y"), stages),
        )
        assertEquals(
            PlacementOutcome.NonWhite(Color.YELLOW, firstTwoLayers = true, stage = stages[1], lastLayerStage = stages[4]),
            Placement.evaluate(TutorFixtures.state("R U2 R' U' R U' R' z y"), stages),
        )
    }

    private fun assertEntry(
        id: String,
        expected: PlacementOutcome,
    ) {
        val state = TutorFixtures.entryOf(TutorFixtures.shipped(id))
        assertEquals(expected, Placement.evaluate(state, stages))
    }

    private companion object {
        const val LESSON_03_01_CORNERS = 3
        const val LESSON_04_01_EDGES = 3
    }
}
