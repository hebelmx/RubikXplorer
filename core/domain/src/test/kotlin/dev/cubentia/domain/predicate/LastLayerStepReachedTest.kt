package dev.cubentia.domain.predicate

import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.lastlayer.LastLayerStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LastLayerStepReachedTest {
    @Test fun `an oriented last layer cannot hide broken first two layers`() {
        val solved = CubeState.SOLVED.copy(orientation = Orientation.of(Face.D, Face.F))
        val broken = solved.apply((Notation.parse("D") as NotationResult.Parsed).moves)
        assertTrue(LastLayerOriented.isSatisfied(broken))
        LastLayerStep.entries.forEach { step ->
            val predicate = LastLayerStepReached(step)
            assertFalse(predicate.isSatisfied(broken))
            assertEquals(ObjectiveProgress(0, step.pieces), predicate.progress(broken))
            assertTrue(predicate.preservedPieces(broken).isEmpty())
            val encoded = ContentLoader.json.encodeToString<Predicate>(predicate)
            assertEquals(predicate, ContentLoader.json.decodeFromString<Predicate>(encoded))
        }
    }
}
