package dev.cubentia.domain.tutor

import dev.cubentia.domain.content.CaseId
import dev.cubentia.domain.content.Stage
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.FaceletTables
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.lastlayer.LastLayerMatcher
import dev.cubentia.domain.lastlayer.LastLayerReading
import dev.cubentia.domain.lastlayer.LastLayerView
import dev.cubentia.domain.predicate.CubeSolved
import dev.cubentia.domain.predicate.LastLayerOriented
import dev.cubentia.domain.predicate.ObjectiveProgress
import dev.cubentia.domain.predicate.Predicate
import dev.cubentia.domain.predicate.StateReached
import dev.cubentia.domain.predicate.WhiteLayer

/** A face turn in words: the face named by its center [color] as the Learner holds the cube, turned by [turn]. */
data class FaceTurnWords(
    val color: Color,
    val turn: Turn,
)

/** What Placement read on an accepted cube, checked in this order (EXPERIENCE.md *Placement outcomes*). */
sealed interface PlacementOutcome {
    data object Solved : PlacementOutcome

    /** One or two face turns from solved: [turns] in the order to make them. */
    data class NearlySolved(
        val turns: List<FaceTurnWords>,
    ) : PlacementOutcome

    /** No white cross and no other cross: [stage] is the first Stage. */
    data class NothingSolved(
        val stage: Stage,
    ) : PlacementOutcome

    /**
     * No white cross, but a cross (or, with [firstTwoLayers], the first two layers) solved on [color]. [stage] is the
     * first Stage with a predicate (*First cross*); [lastLayerStage] is *Last-layer orientation*, only with [firstTwoLayers].
     */
    data class NonWhite(
        val color: Color,
        val firstTwoLayers: Boolean,
        val stage: Stage,
        val lastLayerStage: Stage?,
    ) : PlacementOutcome

    /** [stage] is the first unmet Stage; [progress] is its own part of its Objective. */
    data class PartlySolved(
        val stage: Stage,
        val progress: ObjectiveProgress,
    ) : PlacementOutcome

    /** The first two layers are solved; [case] is named only when exactly one Case fits. */
    data class AtLastLayer(
        val stage: Stage,
        val oriented: Boolean,
        val case: CaseId?,
    ) : PlacementOutcome
}

/** Places an accepted cube using the final Lesson's completion predicate in each Course Stage. Never calls the Solver. */
object Placement {
    private val SINGLE_TURNS: List<FaceTurn> = Face.entries.flatMap { face -> Turn.entries.map { FaceTurn(face, it) } }

    fun evaluate(
        state: CubeState,
        stages: List<Stage>,
        matcher: LastLayerMatcher? = null,
    ): PlacementOutcome {
        if (state.isSolved) return PlacementOutcome.Solved
        nearlySolved(state)?.let { return it }

        val predicateStages =
            stages.mapNotNull { stage ->
                val predicate = stage.lessons.lastOrNull()?.completion
                if (predicate == null || predicate is StateReached) null else stage to predicate
            }
        if (predicateStages.isEmpty()) return PlacementOutcome.NothingSolved(stages.first())
        val unmet = predicateStages.indexOfFirst { (_, predicate) -> !predicate.isSatisfied(state) }
        if (unmet < 0) return PlacementOutcome.Solved
        if (unmet == 0) return nonWhite(state, stages, predicateStages)

        val (stage, predicate) = predicateStages[unmet]
        return when (predicate) {
            LastLayerOriented -> {
                PlacementOutcome.AtLastLayer(stage, oriented = false, case = caseOf(state, matcher))
            }

            CubeSolved -> {
                PlacementOutcome.AtLastLayer(stage, oriented = true, case = caseOf(state, matcher))
            }

            else -> {
                // The earlier predicate Stage is met, so its whole total is the done part this Stage keeps.
                val own = predicate.progress(state)
                val before = predicateStages[unmet - 1].second.progress(state).total
                val progress = if (own.total > before) ObjectiveProgress(own.done - before, own.total - before) else own
                PlacementOutcome.PartlySolved(stage, progress)
            }
        }
    }

    // AD-8: every single face turn, then every pair on two different faces; a single turn wins.
    private fun nearlySolved(state: CubeState): PlacementOutcome.NearlySolved? {
        val single = SINGLE_TURNS.firstOrNull { state.apply(it).isSolved }
        if (single != null) return PlacementOutcome.NearlySolved(listOf(words(state, single)))
        for (first in SINGLE_TURNS) {
            val after = state.apply(first)
            val second = SINGLE_TURNS.firstOrNull { it.face != first.face && after.apply(it).isSolved } ?: continue
            return PlacementOutcome.NearlySolved(listOf(words(state, first), words(after, second)))
        }
        return null
    }

    private fun words(
        state: CubeState,
        turn: FaceTurn,
    ): FaceTurnWords = FaceTurnWords(state.toFacelets().colors[FaceletTables.center(turn.face)], turn.turn)

    private fun nonWhite(
        state: CubeState,
        stages: List<Stage>,
        predicateStages: List<Pair<Stage, Predicate>>,
    ): PlacementOutcome {
        val others = Color.entries.filter { it != Color.WHITE }
        val layers = others.firstOrNull { WhiteLayer.firstTwoLayersSolved(state, it) }
        val color =
            layers ?: others.firstOrNull { WhiteLayer.crossSolved(state, it) }
                ?: return PlacementOutcome.NothingSolved(stages.first())
        val lastLayerStage = if (layers != null) predicateStages.firstOrNull { it.second == LastLayerOriented }?.first else null
        return PlacementOutcome.NonWhite(color, layers != null, predicateStages.first().first, lastLayerStage)
    }

    // The LL is read as Recognize reads it (AD-19): yellow on U.
    private fun caseOf(
        state: CubeState,
        matcher: LastLayerMatcher?,
    ): CaseId? {
        if (matcher == null) return null
        val reading = LastLayerView.of(state, Color.YELLOW) as? LastLayerReading.View ?: return null
        return matcher.candidates(reading.view).singleOrNull()
    }
}
