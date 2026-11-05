package dev.rubikxplorer.app.data

import dev.rubikxplorer.application.session.SessionKind
import dev.rubikxplorer.application.session.SessionSnapshot
import dev.rubikxplorer.domain.tutor.AssistanceLevel
import dev.rubikxplorer.domain.tutor.HintRung
import dev.rubikxplorer.domain.tutor.TeachingMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class SnapshotPayload(
    val format: Int = 1,
    val kind: String,
    val sessionId: String,
    val revision: Long,
    val lessonId: String?,
    val contentRevision: String?,
    val start: String,
    val done: String,
    val undone: String,
    val solution: String,
    val checkpoint: String?,
    val teachingMode: String,
    val adaptiveAssistance: Boolean,
    val hintsUsed: Int,
    val hintRung: String,
    val revealRequested: Boolean,
    val noticeStep: Int?,
    val stepMistakes: Map<Int, Int>,
    val cleanSteps: Int,
    val cleanCredited: Set<Int>,
    val stepHelped: Set<Int>,
    val assistanceDismissed: Map<Int, String>,
) {
    fun snapshot(): SessionSnapshot {
        require(format == 1)
        return SessionSnapshot(
            kind = SessionKind.valueOf(kind),
            sessionId = sessionId,
            revision = revision,
            lessonId = lessonId,
            contentRevision = contentRevision,
            start = start,
            done = done,
            undone = undone,
            solution = solution,
            checkpoint = checkpoint,
            teachingMode = TeachingMode.valueOf(teachingMode),
            adaptiveAssistance = adaptiveAssistance,
            hintsUsed = hintsUsed,
            hintRung = HintRung.valueOf(hintRung),
            revealRequested = revealRequested,
            noticeStep = noticeStep,
            stepMistakes = stepMistakes,
            cleanSteps = cleanSteps,
            cleanCredited = cleanCredited,
            stepHelped = stepHelped,
            assistanceDismissed = assistanceDismissed.mapValues { AssistanceLevel.valueOf(it.value) },
        )
    }

    companion object {
        private val json = Json { encodeDefaults = true }

        fun encode(snapshot: SessionSnapshot): String =
            json.encodeToString(
                SnapshotPayload(
                    kind = snapshot.kind.name,
                    sessionId = snapshot.sessionId,
                    revision = snapshot.revision,
                    lessonId = snapshot.lessonId,
                    contentRevision = snapshot.contentRevision,
                    start = snapshot.start,
                    done = snapshot.done,
                    undone = snapshot.undone,
                    solution = snapshot.solution,
                    checkpoint = snapshot.checkpoint,
                    teachingMode = snapshot.teachingMode.name,
                    adaptiveAssistance = snapshot.adaptiveAssistance,
                    hintsUsed = snapshot.hintsUsed,
                    hintRung = snapshot.hintRung.name,
                    revealRequested = snapshot.revealRequested,
                    noticeStep = snapshot.noticeStep,
                    stepMistakes = snapshot.stepMistakes,
                    cleanSteps = snapshot.cleanSteps,
                    cleanCredited = snapshot.cleanCredited,
                    stepHelped = snapshot.stepHelped,
                    assistanceDismissed = snapshot.assistanceDismissed.mapValues { it.value.name },
                ),
            )

        fun decode(payload: String): SessionSnapshot = json.decodeFromString<SnapshotPayload>(payload).snapshot()
    }
}
