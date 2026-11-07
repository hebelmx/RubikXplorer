package dev.rubikxplorer.domain.predicate

import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.text.Text
import dev.rubikxplorer.domain.tutor.DiagnosisRules

/** Learner-facing descriptions of the current Completion Predicate, without piece logic. */
object CubeSummary {
    fun of(
        state: CubeState,
        predicate: Predicate,
    ): List<Text> =
        when (predicate) {
            AlwaysTrue -> {
                emptyList()
            }

            is AllOf -> {
                predicate.predicates.flatMap { of(state, it) }.distinct()
            }

            is AnyOf -> {
                val selected =
                    predicate.predicates.firstOrNull { it.isSatisfied(state) }
                        ?: predicate.predicates.reduce { best, candidate ->
                            val a = best.progress(state)
                            val b = candidate.progress(state)
                            if (b.done.toLong() * a.total > a.done.toLong() * b.total) candidate else best
                        }
                of(state, selected)
            }

            is WhitePieceSolved -> {
                listOf(
                    Text(
                        if (predicate.isSatisfied(state)) "msg.cube-summary.piece-placed" else "msg.cube-summary.piece-not-placed",
                        mapOf("piece" to DiagnosisRules.selectId(predicate.piece)),
                    ),
                )
            }

            WhiteCrossSolved -> {
                progress(state, predicate, "msg.cube-summary.cross-solved", "msg.cube-summary.cross-progress")
            }

            WhiteCornersSolved -> {
                progress(state, predicate, "msg.cube-summary.corners-solved", "msg.cube-summary.corners-progress")
            }

            MiddleLayerSolved -> {
                progress(state, predicate, "msg.cube-summary.middle-layer-solved", "msg.cube-summary.middle-layer-progress")
            }

            LastLayerCrossSolved -> {
                progress(state, predicate, "msg.cube-summary.last-layer-cross-solved", "msg.cube-summary.last-layer-cross-progress")
            }

            LastLayerOriented -> {
                progress(state, predicate, "msg.cube-summary.last-layer-oriented", "msg.cube-summary.last-layer-oriented-progress")
            }

            LastLayerCornersPlaced -> {
                progress(state, predicate, "msg.cube-summary.last-layer-corners-placed", "msg.cube-summary.last-layer-corners-progress")
            }

            CubeSolved -> {
                progress(state, predicate, "msg.cube-summary.cube-solved", "msg.cube-summary.cube-progress")
            }

            is StateReached -> {
                progress(state, predicate, "msg.cube-summary.pattern-reached", "msg.cube-summary.pattern-progress")
            }
        }

    private fun progress(
        state: CubeState,
        predicate: Predicate,
        solved: String,
        partial: String,
    ): List<Text> {
        val progress = predicate.progress(state)
        return listOf(
            if (predicate.isSatisfied(state)) {
                Text(solved)
            } else {
                Text(
                    partial,
                    mapOf(
                        "done" to progress.done,
                        "total" to progress.total,
                    ),
                )
            },
        )
    }
}
