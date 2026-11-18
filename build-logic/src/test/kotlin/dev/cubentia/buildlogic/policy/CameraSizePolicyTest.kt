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
}
