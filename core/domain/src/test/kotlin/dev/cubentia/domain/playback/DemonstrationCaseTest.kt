package dev.cubentia.domain.playback

import dev.cubentia.domain.content.ContentLoadResult
import dev.cubentia.domain.content.ContentLoader
import dev.cubentia.domain.content.ContentSource
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import dev.cubentia.domain.lastlayer.CaseStateResult
import dev.cubentia.domain.lastlayer.LastLayerMatcher
import dev.cubentia.domain.lastlayer.LastLayerMatcherResult
import dev.cubentia.domain.lastlayer.LastLayerReading
import dev.cubentia.domain.lastlayer.LastLayerView
import dev.cubentia.domain.lastlayer.RecognitionDiagram
import dev.cubentia.domain.lastlayer.RecognitionDiagramResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DemonstrationCaseTest {
    private val bundle = (ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle

    @Test
    fun `all 78 Algorithms start at their yellow Case and play every boundary to solved`() {
        assertEquals(78, bundle.algorithms.size)
        val matcher = (LastLayerMatcher.of(bundle.cases, bundle.algorithms) as LastLayerMatcherResult.Built).matcher
        bundle.algorithms.forEach { algorithm ->
            val demo = (Demonstration.ofCase(algorithm) as DemonstrationResult.Ready).demonstration
            val start = (LastLayerMatcher.caseState(algorithm, RecognitionDiagram.LIBRARY_LAST_LAYER) as CaseStateResult.Derived).state
            assertEquals(start, demo.start, algorithm.id.value)
            val view = (LastLayerView.of(demo.start, RecognitionDiagram.LIBRARY_LAST_LAYER) as LastLayerReading.View).view
            assertEquals((RecognitionDiagram.derive(algorithm) as RecognitionDiagramResult.Derived).diagram, RecognitionDiagram.of(view))
            val moves = (Notation.parse(algorithm.notation) as NotationResult.Parsed).moves
            assertEquals(moves, demo.moves)
            assertEquals(1, demo.steps.size)
            assertTrue(
                demo.steps
                    .single()
                    .focus.keep
                    .isEmpty(),
            )
            assertTrue(
                demo.steps
                    .single()
                    .focus.target
                    .isEmpty(),
            )
            for (boundary in 0..moves.size) {
                val update = PlaybackController.of(demo.start, demo.moves, false).scrubTo(boundary.toDouble() / moves.size)
                assertEquals(boundary, update.controller.position)
                assertFalse(update.controller.isPlaying)
                assertEquals(start.apply(moves.take(boundary)), (update.items.single() as PlaybackItem.Snap).target)
            }
            val end = (LastLayerView.of(start.apply(moves), RecognitionDiagram.LIBRARY_LAST_LAYER) as LastLayerReading.View).view
            assertTrue(matcher.isSolvedUpToAuf(end), algorithm.id.value)
        }
    }

    @Test
    fun `bad notation returns a sealed failure`() {
        assertTrue(Demonstration.ofCase(bundle.algorithms.first().copy(notation = "invalid")) is DemonstrationResult.InvalidNotation)
    }
}
