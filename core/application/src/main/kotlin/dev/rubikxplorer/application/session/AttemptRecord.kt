package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.tutor.AssistanceLevel
import kotlin.time.Instant

enum class AttemptOutcome { COMPLETED, ABANDONED }

enum class AttemptEvent { STARTED, ENDED }

data class AttemptRecord(
    val sessionId: String,
    val revision: Long,
    val event: AttemptEvent,
    val objectiveId: String,
    val outcome: AttemptOutcome?,
    val errors: Int?,
    val hints: Int?,
    val assistanceLevel: AssistanceLevel?,
    val at: Instant,
) {
    companion object {
        fun of(
            effect: Effect.AttemptStarted,
            sessionId: String,
        ): AttemptRecord =
            AttemptRecord(sessionId, effect.revision, AttemptEvent.STARTED, effect.objectiveId, null, null, null, null, effect.at)

        fun of(
            effect: Effect.AttemptEnded,
            sessionId: String,
        ): AttemptRecord =
            AttemptRecord(
                sessionId,
                effect.revision,
                AttemptEvent.ENDED,
                effect.objectiveId,
                effect.outcome,
                effect.errors,
                effect.hints,
                effect.assistanceLevel,
                effect.at,
            )
    }
}
