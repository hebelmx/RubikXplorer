package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * GH #158: [GuidedScanController.SAME_CENTER_DELTA_E] against the recorded live fixtures. Red and orange are the
 * closest centers, so a red face after an orange one (or the reverse) must be captured, never rejected as repeated,
 * while the same face shown again still is.
 */
class RepeatedFaceFixtureTest {
    /** Each live set's six recorded faces, keyed by the true center color. */
    private val sets: Map<String, Map<Color, LiveFixtureRecord>> =
        File("../../Fixtures/live")
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .sortedBy { it.name }
            .associate { dir ->
                dir.name to
                    dir
                        .listFiles { file -> file.extension == "json" }
                        .orEmpty()
                        .map { LiveFixtureJson.decode(it.readText()) }
                        .associateBy { it.labeledSample.truth[FaceSample.CENTER_INDEX] }
            }

    private fun stats(record: LiveFixtureRecord) =
        FrameStats(
            record.labeledSample.sample.stickers
                .map { CellReading(it, plain = true) },
            120.0,
            0.0,
            100.0,
            12,
        )

    /** The cube moved on: a dark frame between two faces, so the face just captured no longer holds. */
    private val moved = FrameStats(List(9) { null }, 10.0, 0.0, 100.0, 0)

    /** Shoots [first], lets the cube move, then shoots [second]; returns the second shot's effects. */
    private fun shootTwice(
        first: LiveFixtureRecord,
        second: LiveFixtureRecord,
    ): Pair<GuidedScanController, List<GuidedScanEffect>> {
        val controller = GuidedScanController()
        controller.onFrame(stats(first), 0, 0)
        assertTrue(controller.onShutter(10).first() is GuidedScanEffect.Capture)
        controller.onFrame(moved, 0, 20)
        controller.onFrame(stats(second), 0, 30)
        return controller to controller.onShutter(40)
    }

    @Test
    fun `every live set is recorded with six distinct centers`() {
        assertEquals(12, sets.size)
        sets.forEach { (name, faces) -> assertEquals(Color.entries.toSet(), faces.keys, name) }
    }

    @Test
    fun `a red face after an orange one, or the reverse, is captured, not rejected as repeated`() {
        sets.forEach { (name, faces) ->
            val red = faces.getValue(Color.RED)
            val orange = faces.getValue(Color.ORANGE)
            listOf(red to orange, orange to red).forEach { (first, second) ->
                val (controller, effects) = shootTwice(first, second)
                assertTrue(effects.firstOrNull() is GuidedScanEffect.Capture, "$name: ${second.labeledSample.truth[4]} rejected")
                assertEquals(2, controller.captured.size, name)
                assertTrue(controller.coaching != Coaching.WRONG_FACE, name)
            }
        }
    }

    @Test
    fun `the same face shown again is still rejected with WRONG_FACE`() {
        sets.forEach { (name, faces) ->
            faces.values.forEach { face ->
                val (controller, effects) = shootTwice(face, face)
                assertEquals(emptyList<GuidedScanEffect>(), effects, name)
                assertEquals(Coaching.WRONG_FACE, controller.coaching, name)
                assertEquals(1, controller.captured.size, name)
            }
        }
    }

    @Test
    fun `every recorded center is at least twice the threshold from each other color's center`() {
        val margin = 2 * GuidedScanController.SAME_CENTER_DELTA_E
        sets.forEach { (name, faces) ->
            faces.forEach { (color, face) ->
                val center = checkNotNull(face.labeledSample.sample.center)
                faces.filterKeys { it != color }.forEach { (other, otherFace) ->
                    val distance = StickerClassifier.deltaE(center, checkNotNull(otherFace.labeledSample.sample.center))
                    assertTrue(distance >= margin, "$name: $color vs $other center $distance < $margin")
                }
            }
        }
    }
}
