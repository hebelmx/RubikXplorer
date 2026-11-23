package dev.cubentia.domain.playback

import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.cube.inverse
import dev.cubentia.domain.lastlayer.CaseStateResult
import dev.cubentia.domain.lastlayer.LastLayerMatcher
import dev.cubentia.domain.lastlayer.LastLayerView
import dev.cubentia.domain.lastlayer.RecognitionDiagram
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlgorithmPracticeTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle

    private fun ready(result: AlgorithmPracticeResult): AlgorithmPractice = (result as AlgorithmPracticeResult.Ready).practice

    @Test
    fun `every shipped Algorithm sets up from solved through exactly its Library Case state, then solves`() {
        assertEquals(78, bundle.algorithms.size)
        val solved = CubeState.SOLVED.copy(orientation = LastLayerView.canonicalFrame(RecognitionDiagram.LIBRARY_LAST_LAYER))
        bundle.algorithms.forEach { algorithm ->
            val id = algorithm.id.value
            val moves = (Notation.parse(algorithm.notation) as NotationResult.Parsed).moves
            val case = (LastLayerMatcher.caseState(algorithm, RecognitionDiagram.LIBRARY_LAST_LAYER) as CaseStateResult.Derived).state
            val practice = ready(AlgorithmPractice.of(algorithm, fromSetUp = true))
            assertEquals(solved, practice.start, id)
            assertEquals(moves.inverse() + moves, practice.moves, id)
            assertEquals(moves.size, practice.algorithmStart, id)
            assertEquals(moves, practice.algorithm, id)
            assertEquals(case, practice.start.apply(practice.setUp), id)
            assertTrue(practice.start.apply(practice.moves).isSolved, id)
            assertEquals(practice.start, practice.start.apply(practice.moves), id)
            // The Demonstration's start is the same Case, so what the detail showed is what the Learner set up.
            assertEquals((Demonstration.ofCase(algorithm) as DemonstrationResult.Ready).demonstration.start, case, id)
        }
    }

    @Test
    fun `every shipped Algorithm practiced on a cube already showing the Case starts at its Case state`() {
        bundle.algorithms.forEach { algorithm ->
            val id = algorithm.id.value
            val moves = (Notation.parse(algorithm.notation) as NotationResult.Parsed).moves
            val case = (LastLayerMatcher.caseState(algorithm, RecognitionDiagram.LIBRARY_LAST_LAYER) as CaseStateResult.Derived).state
            val practice = ready(AlgorithmPractice.of(algorithm, fromSetUp = false))
            assertEquals(case, practice.start, id)
            assertEquals(moves, practice.moves, id)
            assertEquals(0, practice.algorithmStart, id)
            assertTrue(practice.setUp.isEmpty(), id)
            assertTrue(practice.start.apply(practice.moves).isSolved, id)
        }
    }

    @Test
    fun `bad notation is a sealed failure on both paths`() {
        val bad = bundle.algorithms.first().copy(notation = "invalid")
        assertTrue(AlgorithmPractice.of(bad, fromSetUp = true) is AlgorithmPracticeResult.InvalidNotation)
        assertTrue(AlgorithmPractice.of(bad, fromSetUp = false) is AlgorithmPracticeResult.InvalidNotation)
    }
}
