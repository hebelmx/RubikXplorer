package dev.cubentia.domain.tutor

import dev.cubentia.domain.content.Lesson
import dev.cubentia.domain.content.TutorConfig
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.MoveHistory
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.predicate.AllOf
import dev.cubentia.domain.predicate.Piece
import dev.cubentia.domain.text.Text

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

    /**
     * A Diagnosis of [category] for [observed], explained by [explanation] and naming the [disturbed]
     * pieces, as sure as [enumerator]'s consistent candidates allow.
     */
    fun diagnose(
        category: ErrorCategory,
        enumerator: CandidateEnumerator,
        observed: CubeState,
        explanation: Text,
        disturbed: Set<Piece> = emptySet(),
    ): Diagnosis {
        val consistent = enumerator.consistentWith(observed)
        return when (val result = Diagnosis.verified(category, consistent, explanation, disturbed)) {
            is DiagnosisResult.Verified -> {
                result.diagnosis
            }

            is DiagnosisResult.NotUnique -> {
                if (consistent.isEmpty()) {
                    Diagnosis.unknown(category, explanation, disturbed)
                } else {
                    Diagnosis.likely(category, consistent, explanation, disturbed)
                }
            }
        }
    }

    /**
     * What went wrong between the [expected] and the [observed] Cube State of [lesson] (entered at
     * [entry]), by the ordered rules of [DiagnosisRules], or null when nothing did. Candidates come
     * from one [CandidateEnumerator] over [expected] re-held in [observed]'s orientation, configured by
     * [config]: when none explains [observed] (and the cube is not merely turned) the Diagnosis is an
     * UNKNOWN_DISCREPANCY.
     */
    fun diagnose(
        lesson: Lesson,
        entry: CubeState,
        expected: CubeState,
        observed: CubeState,
        config: TutorConfig,
    ): Diagnosis? {
        val held = CubeState(expected.cubies, observed.orientation)
        val enumerator = CandidateEnumerator.of(held, config)
        DiagnosisRules.orientation(entry, expected, observed)?.let { return diagnose(it, enumerator, observed) }
        if (enumerator.consistentWith(observed).isEmpty()) {
            return Diagnosis.unknown(
                ErrorCategory.UNKNOWN_DISCREPANCY,
                Text("msg.diagnosis.unknown-discrepancy"),
                DiagnosisRules.disturbed(lesson, entry, expected, observed),
            )
        }
        return DiagnosisRules.findings(lesson, entry, expected, observed)?.let { diagnose(it, enumerator, observed) }
    }

    /**
     * Diagnoses an entered physical Move [move] as evidence when the Learner reports a mismatch.
     * Evaluates [move] applied to [stateBeforeMove] against [expected]. Certainty is VERIFIED only if
     * exactly one candidate sequence in [CandidateEnumerator] fits, otherwise LIKELY with the candidate(s)
     * shown, or UNKNOWN if no candidate fits. A [move] on the authored path ([Tutor.isOnPath]) is no
     * error, so it has no Diagnosis (FR-9).
     */
    fun diagnosePhysicalMove(
        lesson: Lesson,
        entry: CubeState,
        expected: CubeState,
        stateBeforeMove: CubeState,
        move: Move,
        config: TutorConfig,
    ): Diagnosis? {
        if (Tutor.isOnPath(lesson, entry, stateBeforeMove, move)) return null
        val observed = stateBeforeMove.apply(move)
        val held = CubeState(expected.cubies, observed.orientation)
        val enumerator = CandidateEnumerator.of(held, config)
        DiagnosisRules.orientation(entry, expected, observed)?.let { return diagnose(it, enumerator, observed) }
        val finding =
            DiagnosisRules.findings(lesson, entry, expected, observed)
                ?: Finding(ErrorCategory.WRONG_MOVE_OR_DIRECTION, Text("msg.diagnosis.wrong-move-or-direction"), emptySet())
        return diagnose(finding.category, enumerator, observed, finding.explanation, finding.disturbed)
    }

    /**
     * The Diagnosis of entered [facelets] that cannot be a real cube: INVALID_ENTERED_STATE, explained
     * by the validator's reason. With no Cube State there is no candidate, so it is UNKNOWN. Null when
     * the facelets are valid.
     */
    fun diagnoseEntry(facelets: Facelets): Diagnosis? =
        when (val result = CubeValidator.validate(facelets)) {
            is ValidationResult.Valid -> null
            is ValidationResult.Invalid -> Diagnosis.unknown(ErrorCategory.INVALID_ENTERED_STATE, result.reason, emptySet())
        }

    /**
     * How the *Test me* Move [move] from [before] to [after] relates to the step of [lesson] (entered
     * at [entry]) derived from [before], checked in order:
     * - ON_PATH, with no Diagnosis: [before] is an authored path state and [move] its next authored
     *   Move ([Tutor.isOnPath]);
     * - HARMFUL: the Diagnosis is a disturbance (a preserved piece moved);
     * - PROGRESS: the step's effective done, `allOf(lesson.preserve, done)`, made progress;
     * - NEUTRAL: anything else.
     * A Diagnosis that is not a disturbance (a turned cube, a twisted piece, an unknown change) rides
     * along with PROGRESS or NEUTRAL. A Cube Rotation back to the entry's hold is a recovery: it is
     * NEUTRAL with no Diagnosis.
     */
    @Suppress("LongParameterList")
    fun classify(
        lesson: Lesson,
        entry: CubeState,
        before: CubeState,
        move: Move,
        after: CubeState,
        config: TutorConfig,
    ): MoveAssessment {
        if (Tutor.isOnPath(lesson, entry, before, move)) return MoveAssessment(MoveFeedback.ON_PATH, null)
        val recovered = after.cubies == before.cubies && after.orientation == entry.orientation
        val diagnosis = if (recovered) null else diagnose(lesson, entry, before, after, config)
        if (diagnosis != null && diagnosis.category in DiagnosisRules.DISTURBANCE) return MoveAssessment(MoveFeedback.HARMFUL, diagnosis)
        val step = lesson.steps.getOrNull(Tutor.currentStep(lesson, entry, before))
        val done = AllOf(listOfNotNull(lesson.preserve, step?.done))
        val feedback = if (done.progress(after) > done.progress(before)) MoveFeedback.PROGRESS else MoveFeedback.NEUTRAL
        return MoveAssessment(feedback, diagnosis)
    }

    private fun diagnose(
        finding: Finding,
        enumerator: CandidateEnumerator,
        observed: CubeState,
    ): Diagnosis = diagnose(finding.category, enumerator, observed, finding.explanation, finding.disturbed)

    /**
     * The [TutorDecision] for [lesson] with Move [history] (started at the Lesson's entry state), in
     * virtual mode, taught in [mode]; [config] configures the [CandidateEnumerator]. [revealRequested]
     * is true once the Learner asked to see the step's Moves, [hintRung] is the shown step's Hint Ladder
     * rung ([HintLadder]), [noticeStep] is a completed step whose
     * Notice waits for *Next*, [lastMoveFeedback] and [lastDiagnosis] are [classify]'s word on the
     * last committed Move, and [recovery] is the Guided recovery the session holds (pending or ready,
     * computed by the `PlanRecovery` Effect, never here).
     *
     * - `satisfied` is the Completion Predicate on the current state;
     * - the shown step is [Tutor.shownStep]; `advance` is `satisfied` or a pending [noticeStep];
     * - `certainty` confirms the virtual state against itself (a Full observation); with [isPhysical] and
     *   not [isVerified] (the session holding a Full observation of the current state) it is
     *   [lastDiagnosis]'s Certainty whenever there is one, even while `advance` hides that Diagnosis, and
     *   [Certainty.UNKNOWN] during [isMismatch], else [Certainty.LIKELY]: a predicted cube is not observed, so never VERIFIED (AD-5);
     * - `diagnosis` is [lastDiagnosis] in [TeachingMode.TEST_ME] or with [isPhysical], while not `advance`, else null;
     * - `recovery` is [recovery], passed through;
     * - `reveal` is true in [TeachingMode.SHOW_ME], once [revealRequested], or once [hintRung] reached
     *   [HintRung.TARGET] (a Hint never shows more than the shown step's Moves);
     * - `feedback` is [lastMoveFeedback] in [TeachingMode.TEST_ME], else null: a Move that completes a
     *   step or the Lesson keeps its word (On path) beside the Notice or completion;
     * - `nextAction` is FINISH when satisfied, NEXT_STEP when `advance`, WATCH in *Show me*, else TURN;
     * - `explanation` is the shown Diagnosis's explanation, else the shown step's Notice when
     *   `advance`, else its Goal.
     */
    @Suppress("LongParameterList")
    fun decide(
        lesson: Lesson,
        history: MoveHistory,
        config: TutorConfig,
        mode: TeachingMode,
        revealRequested: Boolean,
        hintRung: HintRung,
        noticeStep: Int?,
        lastMoveFeedback: MoveFeedback?,
        lastDiagnosis: Diagnosis?,
        recovery: Recovery,
        entry: CubeState = history.start,
        isPhysical: Boolean = false,
        isMismatch: Boolean = false,
        isVerified: Boolean = false,
    ): TutorDecision {
        val state = history.state
        val satisfied = lesson.completion.isSatisfied(state)
        val shown = lesson.steps.getOrNull(Tutor.shownStep(lesson, entry, state, noticeStep))
        val advance = satisfied || noticeStep != null
        val nextAction =
            when {
                satisfied -> NextAction.FINISH
                advance -> NextAction.NEXT_STEP
                mode == TeachingMode.SHOW_ME -> NextAction.WATCH
                else -> NextAction.TURN
            }
        val explanationKey = if (advance) shown?.notice else shown?.goal
        val testing = mode == TeachingMode.TEST_ME && !advance
        val diagnosis = lastDiagnosis.takeIf { testing || (isPhysical && !advance) }
        val certainty =
            when {
                isPhysical && !isVerified && lastDiagnosis != null -> lastDiagnosis.certainty
                isPhysical && isMismatch -> Certainty.UNKNOWN
                isPhysical && !isVerified -> Certainty.LIKELY
                else -> confirm(CandidateEnumerator.of(state, config), state)
            }
        return TutorDecision(
            objectiveId = lesson.objective,
            satisfied = satisfied,
            diagnosis = diagnosis,
            certainty = certainty,
            nextAction = nextAction,
            explanation = diagnosis?.explanation ?: Text((explanationKey ?: lesson.objective).value),
            recovery = recovery,
            reveal = mode == TeachingMode.SHOW_ME || revealRequested || hintRung >= HintRung.TARGET,
            advance = advance,
            feedback = lastMoveFeedback.takeIf { mode == TeachingMode.TEST_ME },
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
 * A named mistake (AD-5): its [category], how sure the tutor is ([certainty]), the [candidates]
 * that explain the evidence, the keyed [explanation] the Learner reads and the [disturbed] preserved
 * pieces (Story 4.4 outlines them). Made only here: [verified] for the one-candidate case, which
 * refuses any other count (AD-12), and [Assess.diagnose] for the rest.
 */
@ConsistentCopyVisibility
data class Diagnosis private constructor(
    val category: ErrorCategory,
    val certainty: Certainty,
    val candidates: List<Candidate>,
    val explanation: Text,
    val disturbed: Set<Piece>,
) {
    /** The Recovery Path the [category] calls for. */
    val recoveryPath: RecoveryPath get() = category.recoveryPath

    /**
     * This Diagnosis of a Move known only from the Learner's word, on a cube no face of which was observed:
     * [Certainty.VERIFIED] would overstate it, so it is at most [Certainty.LIKELY] (AD-5).
     */
    fun predicted(): Diagnosis = copy(certainty = if (certainty == Certainty.VERIFIED) Certainty.LIKELY else certainty)

    /**
     * This Diagnosis once the cube has moved on the Learner's word since its evidence: it is [predicted],
     * and its [candidates] explained the cube before that Move, so it names none.
     */
    fun carried(): Diagnosis = predicted().copy(candidates = emptyList())

    companion object {
        /** A verified Diagnosis of [category] when [consistent] holds exactly one candidate; otherwise a refusal with the count. */
        internal fun verified(
            category: ErrorCategory,
            consistent: List<Candidate>,
            explanation: Text,
            disturbed: Set<Piece> = emptySet(),
        ): DiagnosisResult =
            if (consistent.size == 1) {
                DiagnosisResult.Verified(Diagnosis(category, Certainty.VERIFIED, consistent.toList(), explanation, disturbed.toSet()))
            } else {
                DiagnosisResult.NotUnique(consistent.size)
            }

        internal fun likely(
            category: ErrorCategory,
            consistent: List<Candidate>,
            explanation: Text,
            disturbed: Set<Piece> = emptySet(),
        ): Diagnosis {
            require(consistent.size > 1) { "a likely Diagnosis needs several candidates, had ${consistent.size}" }
            return Diagnosis(category, Certainty.LIKELY, consistent.toList(), explanation, disturbed.toSet())
        }

        internal fun unknown(
            category: ErrorCategory,
            explanation: Text,
            disturbed: Set<Piece> = emptySet(),
        ): Diagnosis = Diagnosis(category, Certainty.UNKNOWN, emptyList(), explanation, disturbed.toSet())
    }
}

/** What [Assess.classify] says about one *Test me* Move: its [feedback] and, if it names a mistake, the [diagnosis]. */
data class MoveAssessment(
    val feedback: MoveFeedback,
    val diagnosis: Diagnosis?,
)

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
