package dev.cubentia.buildlogic.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CameraSizePolicyTest {
    @Test
    fun estimateIncludesThreeCopiesOfClassesAndOneOfRuntime() {
        val verdict = CameraSizePolicy.evaluate(20_000_000, 100_000, 500_000, listOf("camera-core.aar"))
        assertEquals(20_800_000L, verdict.estimatedBytes)
        assertTrue(verdict.passes)
    }

    @Test
    fun budgetIsExclusiveAndEveryForbiddenArtifactIsReported() {
        assertFalse(CameraSizePolicy.evaluate(29_999_996, 1, 1, emptyList()).passes)
        val verdict =
            CameraSizePolicy.evaluate(
                20_000_000,
                1,
                1,
                listOf("opencv.aar", "tensorflow-lite.aar", "litert.jar", "camera-core.aar", "litert.jar"),
            )
        assertFalse(verdict.passes)
        assertEquals(listOf("litert.jar", "opencv.aar", "tensorflow-lite.aar"), verdict.forbidden)
    }

    @Test
    fun allowLiteRtPermitsLiteRtAndCountsAssetsWhileStillRejectingOpenCv() {
        val allowed =
            CameraSizePolicy.evaluate(
                releaseEstimate = 12_000_000,
                classBytes = 100_000,
                runtimeBytes = 5_700_000,
                artifacts = listOf("litert-2.3.2.aar", "camera-core.aar"),
                allowLiteRt = true,
                assetBytes = 300_000,
            )
        assertTrue(allowed.passes)
        assertEquals(18_300_000L, allowed.estimatedBytes)
        assertTrue(allowed.forbidden.isEmpty())

        val rejected =
            CameraSizePolicy.evaluate(
                releaseEstimate = 12_000_000,
                classBytes = 1,
                runtimeBytes = 1,
                artifacts = listOf("litert-2.3.2.aar", "opencv-4.12.0.aar", "tensorflow-lite.aar"),
                allowLiteRt = true,
            )
        assertFalse(rejected.passes)
        assertEquals(listOf("opencv-4.12.0.aar", "tensorflow-lite.aar"), rejected.forbidden)
    }
}
