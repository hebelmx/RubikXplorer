package dev.rubikxplorer.application.session

import dev.rubikxplorer.domain.content.FaceletString
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.Orientation
import dev.rubikxplorer.domain.tutor.TeachingMode

/** Durable Objective boundary, independent of content and transient tutor state. */
data class Checkpoint(
    val kind: SessionKind,
    val contentRevision: String,
    val lessonId: String,
    val stageId: String,
    val start: String,
    val startOrientation: String,
    val cube: String,
    val cubeOrientation: String,
    val history: String,
    val mode: CubeMode,
    val teachingMode: TeachingMode,
    val adaptiveAssistance: Boolean,
    val savedAt: Long,
) {
    companion object {
        fun of(
            effect: Effect.PersistCheckpoint,
            contentRevision: String,
        ): Checkpoint =
            Checkpoint(
                SessionKind.LESSON,
                contentRevision,
                effect.lessonId,
                effect.stageId,
                FaceletString.encode(effect.history.start.toFacelets()),
                effect.history.start.orientation
                    .id(),
                FaceletString.encode(effect.history.state.toFacelets()),
                effect.history.state.orientation
                    .id(),
                Notation.format(effect.history.done),
                effect.mode,
                effect.teachingMode,
                effect.adaptiveAssistance,
                effect.at.toEpochMilliseconds(),
            )
    }
}

enum class CubeMode { VIRTUAL, PHYSICAL }

internal fun Orientation.id(): String = up.name + front.name
