package dev.rubikxplorer.app.data

import dev.rubikxplorer.application.session.Checkpoint
import dev.rubikxplorer.application.session.CubeMode
import dev.rubikxplorer.application.session.SessionKind
import dev.rubikxplorer.domain.tutor.TeachingMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

internal fun checkpointFixture(): Checkpoint =
    Checkpoint(
        SessionKind.LESSON,
        "v1",
        "lesson-01-01",
        "stage-01",
        "WWWWWWWWWRRRRRRRRRGGGGGGGGGYYYYYYYYYOOOOOOOOOBBBBBBBBB",
        "UF",
        "WWWWWWWWWRRRRRRRRRGGGGGGGGGYYYYYYYYYOOOOOOOOOBBBBBBBBB",
        "UF",
        "",
        CubeMode.VIRTUAL,
        TeachingMode.GUIDE_ME,
        true,
        123,
    )

class CheckpointPayloadTest {
    @Test fun roundTripKeysAndAllFrozenFormats() {
        val expected = checkpointFixture()
        val encoded = CheckpointPayload.encode(expected)
        assertEquals(expected, CheckpointPayload.decode(encoded))
        assertEquals(
            setOf(
                "checkpointFormat",
                "kind",
                "contentRevision",
                "lessonId",
                "stageId",
                "start",
                "startOrientation",
                "cube",
                "cubeOrientation",
                "history",
                "mode",
                "teachingMode",
                "adaptiveAssistance",
                "savedAt",
            ),
            Json.parseToJsonElement(encoded).jsonObject.keys,
        )
        for (format in 1..CheckpointPayload.CURRENT) {
            val resource = checkNotNull(javaClass.getResource("/checkpoint/format-$format.json"))
            assertEquals(expected, CheckpointPayload.decode(resource.readText()))
        }
    }

    @Test fun unknownAndBrokenPayloadsFail() {
        for (payload in listOf(
            "broken",
            "{}",
            CheckpointPayload.encode(checkpointFixture()).replace("\"checkpointFormat\":1", "\"checkpointFormat\":99"),
        )) {
            assertThrows(Exception::class.java) { CheckpointPayload.decode(payload) }
        }
    }
}
