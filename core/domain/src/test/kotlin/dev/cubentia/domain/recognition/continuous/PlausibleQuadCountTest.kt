package dev.cubentia.domain.recognition.continuous

import dev.cubentia.domain.cube.CubeState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * GH #409: [ContinuousFaceletMapper.lastPlausibleQuadCount] is how many of the last frame's detections passed the
 * plausibility filter (the debug overlay's `kept`): an implausible quad is not counted, an empty frame and [reset] give 0,
 * and so does every frame once a layer twist is latched.
 */
class PlausibleQuadCountTest {
    private val frames =
        SyntheticCubeStream.generateSequence(
            SyntheticCubeStream.ScenarioConfig(name = "kept", state = CubeState.SOLVED, jitterPx = 0.0, nFrames = 10),
        )

    @Test
    fun `only plausible detections are counted, and an empty frame or reset gives zero`() {
        val mapper = ContinuousFaceletMapper()
        assertEquals(0, mapper.lastPlausibleQuadCount)
        val frame = frames.first { it.detections.isNotEmpty() }
        mapper.processFrame(0, frame.detections)
        val kept = mapper.lastPlausibleQuadCount
        assertTrue(kept in 1..frame.detections.size, "kept=$kept of ${frame.detections.size}")
        // A 10 px square is below the area floor: filtered out, not counted.
        val tiny =
            frame.detections.first().copy(
                quad = Quad2D(listOf(Point2D(5.0, 5.0), Point2D(15.0, 5.0), Point2D(15.0, 15.0), Point2D(5.0, 15.0))),
            )
        mapper.processFrame(1, frame.detections + tiny)
        assertEquals(kept, mapper.lastPlausibleQuadCount)
        mapper.processFrame(2, emptyList())
        assertEquals(0, mapper.lastPlausibleQuadCount)
        mapper.processFrame(3, frame.detections)
        assertEquals(kept, mapper.lastPlausibleQuadCount)
        mapper.reset()
        assertEquals(0, mapper.lastPlausibleQuadCount)
    }

    @Test
    fun `once a layer twist is latched no frame keeps a quad`() {
        val cfg = SyntheticCubeStream.buildScenarios().first { it.name == "7_mid_scan_layer_twist_U" }
        val mapper = ContinuousFaceletMapper()
        var twisted = false
        for (fr in SyntheticCubeStream.generateSequence(cfg)) {
            val snap = mapper.processFrame(fr.frameIndex, fr.detections)
            if (twisted) {
                assertEquals(0, mapper.lastPlausibleQuadCount, "frame ${fr.frameIndex}")
            }
            if (snap.twistDetected) twisted = true
        }
        assertTrue(twisted, "the scenario latches a twist")
    }
}
