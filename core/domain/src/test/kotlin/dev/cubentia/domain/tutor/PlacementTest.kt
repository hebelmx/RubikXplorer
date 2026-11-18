package dev.cubentia.domain.tutor

import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.CubeState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlacementTest {
    private val stages = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.stages

    @Test
    fun `solved cube asks for a new scan or Stage 1`() {
        assertEquals(PlacementOutcome.Solved, Placement.evaluate(CubeState.SOLVED, stages))
    }

    @Test
    fun `a U turn leaves a non-white cross`() {
        assertEquals(PlacementOutcome.NonWhiteCross(stages.first()), Placement.evaluate(TutorFixtures.state("U"), stages))
    }

    @Test
    fun `a scramble with no cross starts at Stage 1`() {
        val scramble = TutorFixtures.state("R2 U' F L2 D B' R U2 B2 D' L F2 U' R2 D2 L' B U2")
        assertEquals(PlacementOutcome.Suggested(stages.first()), Placement.evaluate(scramble, stages))
    }

    @Test
    fun `a white cross starts at first-layer corners`() = assertEntry("lesson-03-01", PlacementOutcome.Suggested(stages[2]))

    @Test
    fun `a solved first layer starts at the middle layer`() = assertEntry("lesson-04-01", PlacementOutcome.Suggested(stages[3]))

    @Test
    fun `solved first two layers offer the faster last layer`() = assertEntry("lesson-05-01", PlacementOutcome.AtLastLayer(stages[4]))

    @Test
    fun `an oriented last layer starts at Stage 6`() = assertEntry("lesson-06-02", PlacementOutcome.Suggested(stages[5]))

    @Test
    fun `lesson 06-01 still needs last-layer orientation`() = assertEntry("lesson-06-01", PlacementOutcome.AtLastLayer(stages[4]))

    @Test
    fun `non-white cross placement survives whole-cube rotation`() {
        assertEquals(PlacementOutcome.NonWhiteCross(stages.first()), Placement.evaluate(TutorFixtures.state("U x y"), stages))
    }

    private fun assertEntry(
        id: String,
        expected: PlacementOutcome,
    ) {
        val state = TutorFixtures.entryOf(TutorFixtures.shipped(id))
        assertEquals(expected, Placement.evaluate(state, stages))
    }
}
