package dev.cubentia.app.data

import dev.cubentia.application.session.Checkpoint
import dev.cubentia.application.session.CubeMode
import dev.cubentia.application.session.SessionKind
import dev.cubentia.domain.tutor.TeachingMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** A consistent Checkpoint: start is SOLVED after `x F U'`, and cube is start after the history `R U y F'`. */
internal fun checkpointFixture(): Checkpoint =
    Checkpoint(
        SessionKind.LESSON,
        "v1",
        "lesson-01-01",
        "stage-01",
        "GGOGGOGGOYYYGRRGRROOBYYYYYYRRRBBBBBBWWWOOBOOBGRRWWWWWW",
        "FD",
        "YGGYGGWOOGWWWWWWWWRYYRRRORRYBBRBBRBBGGGYYGYYBOOROOBOOB",
        "FR",
        "R U y F'",
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
