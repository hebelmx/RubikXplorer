package dev.rubikxplorer.app.data

import dev.rubikxplorer.application.session.Checkpoint
import dev.rubikxplorer.application.session.CubeMode
import dev.rubikxplorer.application.session.SessionKind
import dev.rubikxplorer.domain.tutor.TeachingMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Format-one decoder is retained when subsequent formats are introduced. */
@Serializable
internal data class CheckpointPayload(
    val checkpointFormat: Int,
    val kind: String,
    val contentRevision: String,
    val lessonId: String,
    val stageId: String,
    val start: String,
    val startOrientation: String,
    val cube: String,
    val cubeOrientation: String,
    val history: String,
    val mode: String,
    val teachingMode: String,
    val adaptiveAssistance: Boolean,
    val savedAt: Long,
) {
    private fun checkpoint(): Checkpoint =
        Checkpoint(
            kind = SessionKind.valueOf(kind),
            contentRevision = contentRevision,
            lessonId = lessonId,
            stageId = stageId,
            start = start,
            startOrientation = startOrientation,
            cube = cube,
            cubeOrientation = cubeOrientation,
            history = history,
            mode = CubeMode.valueOf(mode),
            teachingMode = TeachingMode.valueOf(teachingMode),
            adaptiveAssistance = adaptiveAssistance,
            savedAt = savedAt,
        )

    companion object {
        const val CURRENT = 1
        private val json = Json { encodeDefaults = true }

        fun decode(payload: String): Checkpoint =
            when (
                val format =
                    json
                        .parseToJsonElement(payload)
                        .jsonObject
                        .getValue("checkpointFormat")
                        .jsonPrimitive.int
            ) {
                1 -> json.decodeFromString<CheckpointPayload>(payload).checkpoint()
                else -> error("Unknown checkpoint format $format")
            }

        fun encode(checkpoint: Checkpoint): String =
            json.encodeToString(
                CheckpointPayload(
                    checkpointFormat = CURRENT,
                    kind = checkpoint.kind.name,
                    contentRevision = checkpoint.contentRevision,
                    lessonId = checkpoint.lessonId,
                    stageId = checkpoint.stageId,
                    start = checkpoint.start,
                    startOrientation = checkpoint.startOrientation,
                    cube = checkpoint.cube,
                    cubeOrientation = checkpoint.cubeOrientation,
                    history = checkpoint.history,
                    mode = checkpoint.mode.name,
                    teachingMode = checkpoint.teachingMode.name,
                    adaptiveAssistance = checkpoint.adaptiveAssistance,
                    savedAt = checkpoint.savedAt,
                ),
            )
    }
}
