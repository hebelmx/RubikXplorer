package dev.rubikxplorer.application.session

import dev.rubikxplorer.application.preference.Preference
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.text.Text
import dev.rubikxplorer.domain.tutor.RecoveryGoal

/**
 * An asynchronous request a [Transition] hands to the host (AD-3). Each carries its [effectId] and the
 * [revision] of the state it was issued in; its result event echoes both, or it is discarded.
 */
sealed interface Effect {
    val effectId: Long
    val revision: Long

    data class UpdatePreference(
        override val effectId: Long,
        override val revision: Long,
        val preference: Preference,
    ) : Effect

    data class AttemptStarted(
        override val effectId: Long,
        override val revision: Long,
        val objectiveId: String,
        val at: kotlin.time.Instant,
    ) : Effect

    data class AttemptEnded(
        override val effectId: Long,
        override val revision: Long,
        val objectiveId: String,
        val outcome: AttemptOutcome,
        val errors: Int,
        val hints: Int,
        val assistanceLevel: dev.rubikxplorer.domain.tutor.AssistanceLevel,
        val at: kotlin.time.Instant,
    ) : Effect

    data class PersistCheckpoint(
        override val effectId: Long,
        override val revision: Long,
        val lessonId: String,
        val stageId: String,
        val history: dev.rubikxplorer.domain.cube.MoveHistory,
        val teachingMode: dev.rubikxplorer.domain.tutor.TeachingMode,
        val adaptiveAssistance: Boolean,
        val at: kotlin.time.Instant,
    ) : Effect

    /** Solve a validated Cube State without changing the session. */
    data class SolveCube(
        override val effectId: Long,
        override val revision: Long,
        val state: CubeState,
    ) : Effect

    /** Ask the Learner whether to reset, with [prompt]; answered by `ResetConfirmed` or `ResetDeclined`. */
    data class ConfirmReset(
        override val effectId: Long,
        override val revision: Long,
        val prompt: Text,
    ) : Effect

    /**
     * Plan the Guided recovery (FR-12, AD-8): the shortest sequence of at most [maxDepth] Moves of
     * [moveSet] that takes [state] to one meeting [goal], run by `PlanRecovery` off the reducer;
     * answered by `RecoveryPlanned` or `RecoveryPlanFailed`.
     */
    data class PlanRecovery(
        override val effectId: Long,
        override val revision: Long,
        val state: CubeState,
        val goal: RecoveryGoal,
        val maxDepth: Int,
        val moveSet: List<Move>,
    ) : Effect
}

/** A result for effect [effectId], issued at [issuedRevision], that arrived when the state was at [currentRevision] or no longer awaited it. */
data class EffectResultDiscarded(
    val effectId: Long,
    val issuedRevision: Long,
    val currentRevision: Long,
)
