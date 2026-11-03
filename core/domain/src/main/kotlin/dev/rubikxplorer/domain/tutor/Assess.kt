package dev.rubikxplorer.domain.tutor

import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.content.TutorConfig
import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.MoveHistory
import dev.rubikxplorer.domain.text.Text

/**
 * The tutor's evidence engine (AD-5, AD-12): the only producer of [Certainty], for confirmations and
 * Diagnoses alike, always from the candidates of one [CandidateEnumerator]. Exactly one consistent
 * candidate is [Certainty.VERIFIED], more than one [Certainty.LIKELY], none [Certainty.UNKNOWN]. A
 * Full observation (virtual mode: the virtual Cube State) covers every face, so coverage never lowers
 * it here. Pure and bounded.
 */
object Assess {
    /** How sure the tutor is that [observed] is what [enumerator] expects, judged by its consistent candidates. */
    fun confirm(
        enumerator: CandidateEnumerator,
        observed: CubeState,
    ): Certainty = certaintyOf(enumerator.consistentWith(observed).size)

    /** A Diagnosis of [category] for [observed], as sure as [enumerator]'s consistent candidates allow. */
    fun diagnose(
        category: ErrorCategory,
        enumerator: CandidateEnumerator,
        observed: CubeState,
    ): Diagnosis {
        val consistent = enumerator.consistentWith(observed)
        return when (val result = Diagnosis.verified(category, consistent)) {
            is DiagnosisResult.Verified -> {
                result.diagnosis
            }

            is DiagnosisResult.NotUnique -> {
                if (consistent.isEmpty()) {
                    Diagnosis.unknown(
                        category,
                    )
                } else {
                    Diagnosis.likely(category, consistent)
                }
            }
        }
    }

    /**
     * The [TutorDecision] for [lesson] with Move [history] (started at the Lesson's entry state), in
     * virtual mode, taught in [mode]; [config] configures the [CandidateEnumerator]. [revealRequested]
     * is true once the Learner asked to see the step's Moves, [noticeStep] is a completed step whose
     * Notice waits for *Next*, and [lastMoveFeedback] classifies the last committed Move.
     *
     * - `satisfied` is the Completion Predicate on the current state;
     * - the shown step is [Tutor.shownStep]; `advance` is `satisfied` or a pending [noticeStep];
     * - `certainty` confirms the virtual state against itself (a Full observation);
     * - no Diagnosis and no Guided recovery yet (Stories 4.2 and 4.5);
     * - `reveal` is true in [TeachingMode.SHOW_ME] or once [revealRequested];
     * - `feedback` is [lastMoveFeedback] in [TeachingMode.TEST_ME] while not `advance`, else null;
     * - `nextAction` is FINISH when satisfied, NEXT_STEP when `advance`, WATCH in *Show me*, else TURN;
     * - `explanation` is the shown step's Notice when `advance`, else its Goal.
     */
    @Suppress("LongParameterList")
    fun decide(
        lesson: Lesson,
        history: MoveHistory,
        config: TutorConfig,
        mode: TeachingMode,
        revealRequested: Boolean,
        noticeStep: Int?,
        lastMoveFeedback: MoveFeedback?,
    ): TutorDecision {
        val state = history.state
        val satisfied = lesson.completion.isSatisfied(state)
        val shown = lesson.steps.getOrNull(Tutor.shownStep(lesson, history.start, state, noticeStep))
        val advance = satisfied || noticeStep != null
        val nextAction =
            when {
                satisfied -> NextAction.FINISH
                advance -> NextAction.NEXT_STEP
                mode == TeachingMode.SHOW_ME -> NextAction.WATCH
                else -> NextAction.TURN
            }
        val explanationKey = if (advance) shown?.notice else shown?.goal
        return TutorDecision(
            objectiveId = lesson.objective,
            satisfied = satisfied,
            diagnosis = null,
            certainty = confirm(CandidateEnumerator.of(state, config), state),
            nextAction = nextAction,
            explanation = Text((explanationKey ?: lesson.objective).value),
            recovery = Recovery.Unavailable,
            reveal = mode == TeachingMode.SHOW_ME || revealRequested,
            advance = advance,
            feedback = lastMoveFeedback.takeIf { mode == TeachingMode.TEST_ME && !advance },
        )
    }

    private fun certaintyOf(consistent: Int): Certainty =
        when (consistent) {
            0 -> Certainty.UNKNOWN
            1 -> Certainty.VERIFIED
            else -> Certainty.LIKELY
        }
}

/**
 * A named mistake (AD-5): its [category], how sure the tutor is ([certainty]) and the [candidates]
 * that explain the evidence. Made only here: [verified] for the one-candidate case, which refuses any
 * other count (AD-12), and [Assess.diagnose] for the rest.
 */
@ConsistentCopyVisibility
data class Diagnosis private constructor(
    val category: ErrorCategory,
    val certainty: Certainty,
    val candidates: List<Candidate>,
) {
    /** The Recovery Path the [category] calls for. */
    val recoveryPath: RecoveryPath get() = category.recoveryPath

    companion object {
        /** A verified Diagnosis of [category] when [consistent] holds exactly one candidate; otherwise a refusal with the count. */
        internal fun verified(
            category: ErrorCategory,
            consistent: List<Candidate>,
        ): DiagnosisResult =
            if (consistent.size == 1) {
                DiagnosisResult.Verified(Diagnosis(category, Certainty.VERIFIED, consistent.toList()))
            } else {
                DiagnosisResult.NotUnique(consistent.size)
            }

        internal fun likely(
            category: ErrorCategory,
            consistent: List<Candidate>,
        ): Diagnosis {
            require(consistent.size > 1) { "a likely Diagnosis needs several candidates, had ${consistent.size}" }
            return Diagnosis(category, Certainty.LIKELY, consistent.toList())
        }

        internal fun unknown(category: ErrorCategory): Diagnosis = Diagnosis(category, Certainty.UNKNOWN, emptyList())
    }
}

/** The outcome of [Diagnosis.verified]. */
sealed interface DiagnosisResult {
    /** Exactly one candidate fit: the [diagnosis] is verified. */
    data class Verified(
        val diagnosis: Diagnosis,
    ) : DiagnosisResult

    /** [consistent] candidates fit, not exactly one, so the Diagnosis cannot be verified. */
    data class NotUnique(
        val consistent: Int,
    ) : DiagnosisResult
}
