package dev.cubentia.application.session

import dev.cubentia.application.preference.Preference
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.MoveHistory
import dev.cubentia.domain.method.SolveMethod
import dev.cubentia.domain.text.Text
import dev.cubentia.domain.tutor.AssistanceLevel
import dev.cubentia.domain.tutor.RecoveryGoal
import dev.cubentia.domain.tutor.TeachingMode

/**
 * An asynchronous request a [Transition] hands to the host (AD-3). Each carries its [effectId] and the
 * [revision] of the state it was issued in; its result event echoes both, or it is discarded.
 */
sealed interface Effect {
    /** Capture these home faces; answered by QuickCheckObserved or QuickCheckCancelled. */
    data class QuickCheck(
        override val effectId: Long,
        override val revision: Long,
        val faces: Set<Face>,
        val byLearner: Boolean,
    ) : Effect

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
        val assistanceLevel: AssistanceLevel,
        val at: kotlin.time.Instant,
    ) : Effect

    data class PersistCheckpoint(
        override val effectId: Long,
        override val revision: Long,
        val lessonId: String,
        val stageId: String,
        val history: MoveHistory,
        val teachingMode: TeachingMode,
        val adaptiveAssistance: Boolean,
        val at: kotlin.time.Instant,
        val mode: CubeMode = CubeMode.VIRTUAL,
        val physicalSolveUnconfirmed: Boolean = false,
    ) : Effect

    /** Solve a validated Cube State without changing the session. */
    data class SolveCube(
        override val effectId: Long,
        override val revision: Long,
        val state: CubeState,
    ) : Effect

    /**
     * Build a method-shaped solution of a validated Cube State (FR-30, AD-21) without changing the
     * session; run by `SolveByMethod` and answered by `MethodSolveCompleted`.
     */
    data class MethodSolve(
        override val effectId: Long,
        override val revision: Long,
        val state: CubeState,
        val method: SolveMethod,
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
