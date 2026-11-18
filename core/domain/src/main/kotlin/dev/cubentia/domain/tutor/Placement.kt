package dev.cubentia.domain.tutor

import dev.cubentia.domain.content.Stage
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.FaceletTables
import dev.cubentia.domain.predicate.LastLayerOriented
import dev.cubentia.domain.predicate.StateReached
import dev.cubentia.domain.predicate.WhiteLayer

sealed interface PlacementOutcome {
    data object Solved : PlacementOutcome

    data class NonWhiteCross(
        val stage: Stage,
    ) : PlacementOutcome

    data class Suggested(
        val stage: Stage,
    ) : PlacementOutcome

    data class AtLastLayer(
        val stage: Stage,
    ) : PlacementOutcome
}

/** Places an accepted cube using the final Lesson's completion predicate in each Course Stage. */
object Placement {
    fun evaluate(
        state: CubeState,
        stages: List<Stage>,
    ): PlacementOutcome {
        if (state.isSolved) return PlacementOutcome.Solved

        val predicateStages =
            stages.mapNotNull { stage ->
                val predicate = stage.lessons.lastOrNull()?.completion
                if (predicate == null || predicate is StateReached) null else stage to predicate
            }
        val unmet = predicateStages.indexOfFirst { (_, predicate) -> !predicate.isSatisfied(state) }
        if (unmet <= 0) {
            val start = stages.first()
            val otherCross =
                unmet == 0 &&
                    Color.entries.any { color ->
                        color != Color.WHITE && WhiteLayer.correctPieces(state, FaceletTables.EDGE_FACELETS, color).size == 4
                    }
            return if (otherCross) PlacementOutcome.NonWhiteCross(start) else PlacementOutcome.Suggested(start)
        }

        val (stage, predicate) = predicateStages[unmet]
        return if (predicate == LastLayerOriented) PlacementOutcome.AtLastLayer(stage) else PlacementOutcome.Suggested(stage)
    }
}
