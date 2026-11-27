package dev.cubentia.buildlogic.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FixtureWriterPolicyTest {
    @Test
    fun `names every writer-package class and ignores the neutral ports and the rest of the camera code`() {
        val violations =
            FixtureWriterPolicy.violations(
                listOf(
                    "dev/cubentia/app/camera/ContinuousFrameTap.class",
                    "dev/cubentia/app/camera/ContinuousSessionWriterFactory.class",
                    "dev/cubentia/app/camera/GuidedScanActivity.class",
                    "dev/cubentia/app/camera/session/ContinuousSessionWriter.class",
                    "classes/dev/cubentia/app/camera/session/RgbPng.class",
                    "dev/cubentia/app/camera/sessions/Other.class",
                    "dev/cubentia/app/camera/continuous/session/Other.class",
                ),
            )
        assertEquals(
            listOf(
                "Non-debuggable variant contains fixture-writer class classes/dev/cubentia/app/camera/session/RgbPng.class",
                "Non-debuggable variant contains fixture-writer class dev/cubentia/app/camera/session/ContinuousSessionWriter.class",
            ),
            violations,
        )
        assertTrue(FixtureWriterPolicy.isWriterClass("dev\\cubentia\\app\\camera\\session\\A.class"))
        assertFalse(FixtureWriterPolicy.isWriterClass("dev/cubentia/app/camera/continuous/ContinuousScanPipeline.class"))
        assertEquals(emptyList<String>(), FixtureWriterPolicy.violations(emptyList()))
    }
}
