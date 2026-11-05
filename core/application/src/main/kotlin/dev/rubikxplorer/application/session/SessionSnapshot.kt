package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.content.FaceletString
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.tutor.AssistanceLevel
import dev.rubikxplorer.domain.tutor.HintRung
import dev.rubikxplorer.domain.tutor.TeachingMode

/** Process-death data only: no content, derived step or pending effect is persisted. */
data class SessionSnapshot(
    val attemptOpen: Boolean = false,
    val attemptAssistance: AssistanceLevel = AssistanceLevel.USUAL,
    val kind: SessionKind,
    val sessionId: String,
    val revision: Long,
    val lessonId: String?,
    val contentRevision: String?,
    val start: String,
    val done: String,
    val undone: String,
    val solution: String,
    val checkpoint: String?,
    val teachingMode: TeachingMode,
    val adaptiveAssistance: Boolean,
    val hintsUsed: Int,
    val hintRung: HintRung,
    val revealRequested: Boolean,
    val noticeStep: Int?,
    val stepMistakes: Map<Int, Int>,
    val cleanSteps: Int,
    val cleanCredited: Set<Int>,
    val stepHelped: Set<Int>,
    val assistanceDismissed: Map<Int, AssistanceLevel>,
) {
    companion object {
        fun of(
            state: SessionState,
            sessionId: String,
            contentRevision: String?,
        ): SessionSnapshot? {
            val kind = state.kind ?: return null
            return SessionSnapshot(
                attemptOpen = state.attemptOpen,
                attemptAssistance = state.attemptAssistance,
                kind = kind,
                sessionId = sessionId,
                revision = state.revision,
                lessonId = state.lesson?.id?.value,
                contentRevision = contentRevision.takeIf { kind == SessionKind.LESSON },
                start = FaceletString.encode(state.entry.toFacelets()),
                done = Notation.format(state.history.done),
                undone = Notation.format(state.history.undone),
                solution = Notation.format(state.solution),
                checkpoint = state.checkpoint?.let { Notation.format(it.done) },
                teachingMode = state.teachingMode,
                adaptiveAssistance = state.adaptiveAssistance,
                hintsUsed = state.hintsUsed,
                hintRung = state.hintRung,
                revealRequested = state.revealRequested,
                noticeStep = state.noticeStep,
                stepMistakes = state.stepMistakes,
                cleanSteps = state.cleanSteps,
                cleanCredited = state.cleanCredited,
                stepHelped = state.stepHelped,
                assistanceDismissed = state.assistanceDismissed,
            )
        }
    }
}
