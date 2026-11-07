package dev.rubikxplorer.domain.tutor

import kotlin.time.Instant

/**
 * An outcome of a practice or lesson attempt.
 */
enum class AttemptOutcome {
    COMPLETED,
    ABANDONED,
}

/**
 * Lightweight abstraction of an attempt row for domain evaluation of Mastery (FR-4, FR-20, AD-6, AD-10).
 */
interface MasteryAttempt {
    val objectiveId: String
    val outcome: AttemptOutcome?
    val assistanceLevel: AssistanceLevel?
    val at: Instant
}

/**
 * A lightweight attempt record for domain tests and evaluation.
 */
data class StageAttempt(
    override val objectiveId: String,
    override val outcome: AttemptOutcome? = AttemptOutcome.COMPLETED,
    override val assistanceLevel: AssistanceLevel? = AssistanceLevel.REDUCED,
    override val at: Instant = Instant.fromEpochMilliseconds(0),
) : MasteryAttempt
