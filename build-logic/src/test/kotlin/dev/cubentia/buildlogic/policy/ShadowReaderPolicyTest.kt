package dev.cubentia.buildlogic.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ShadowReaderPolicyTest {
    @Test
    fun `names every offending asset and class and ignores continuous and corner models`() {
        val violations =
            ShadowReaderPolicy.violations(
                assets =
                    listOf(
                        "corner_keypoints.tflite",
                        "sticker_centers_v2_int8.tflite",
                        "models/sticker_reader.tflite",
                        "sticker_reader.txt",
                    ),
                classes =
                    listOf(
                        "dev/cubentia/app/camera/CornerReader.class",
                        "dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.class",
                        "dev/cubentia/app/camera/sticker/ShadowStickerReader.class",
                    ),
            )
        assertEquals(
            listOf(
                "Non-debuggable variant contains sticker model asset models/sticker_reader.tflite",
                "Non-debuggable variant contains shadow-reader class dev/cubentia/app/camera/sticker/ShadowStickerReader.class",
            ),
            violations,
        )
    }

    @Test
    fun `the camera size sum leaves out debug-only tools and legacy corner classes`() {
        assertEquals(
            listOf("dev/cubentia/app/camera/sticker/**", "dev/cubentia/app/camera/session/**"),
            CameraSizePolicy.debugOnlyClassGlobs,
        )
        assertEquals(
            listOf("dev/cubentia/app/camera/corner/**"),
            CameraSizePolicy.legacyCornerClassGlobs,
        )
    }
}
