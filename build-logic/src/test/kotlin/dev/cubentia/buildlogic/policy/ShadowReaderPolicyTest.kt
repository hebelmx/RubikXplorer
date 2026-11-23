package dev.cubentia.buildlogic.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShadowReaderPolicyTest {
    @Test
    fun `composes only when debuggable with a corner-model flag`() {
        assertTrue(ShadowReaderPolicy.composes(debuggable = true, cornerModelEnabled = true))
        assertFalse(ShadowReaderPolicy.composes(debuggable = true, cornerModelEnabled = false))
        assertFalse(ShadowReaderPolicy.composes(debuggable = false, cornerModelEnabled = true))
    }

    @Test
    fun `names every offending asset and class and ignores the rest`() {
        val violations =
            ShadowReaderPolicy.violations(
                assets = listOf("corner_keypoints.tflite", "models/sticker_reader.tflite", "sticker_reader.txt"),
                classes =
                    listOf(
                        "dev/cubentia/app/camera/CornerReader.class",
                        "dev/cubentia/app/camera/sticker/ShadowStickerReader.class",
                        "dev/cubentia/app/camera/recorder/RecorderBurst.class",
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
    fun `the camera size sum leaves out both debug-only tools`() {
        assertEquals(
            listOf("dev/cubentia/app/camera/recorder/**", "dev/cubentia/app/camera/sticker/**"),
            CameraSizePolicy.debugOnlyClassGlobs,
        )
    }
}
