package dev.cubentia.app.camera.recorder

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.TruthSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class RecorderRulesTest {
    private val sticker = Lab(50.0, 0.0, 0.0)

    /** WHITE and YELLOW at the given distances from [sticker]; the other four far away. */
    private fun calibration(
        white: Double,
        yellow: Double,
    ) = CalibrationCenters(
        mapOf(
            Color.WHITE to Lab(50.0 + white, 0.0, 0.0),
            Color.YELLOW to Lab(50.0 - yellow, 0.0, 0.0),
            Color.RED to Lab(50.0, 80.0, 0.0),
            Color.ORANGE to Lab(50.0, 0.0, 80.0),
            Color.GREEN to Lab(50.0, -80.0, 0.0),
            Color.BLUE to Lab(50.0, 0.0, -80.0),
        ),
    )

    @Test fun `clear win is prefilled and a close call stays blank`() {
        assertEquals(listOf(Color.WHITE), prefill(listOf(sticker), calibration(white = 5.0, yellow = 30.0)))
        assertEquals(listOf<Color?>(null), prefill(listOf(sticker), calibration(white = 20.0, yellow = 28.0)))
        assertEquals(listOf(Color.WHITE), prefill(listOf(sticker), calibration(white = 10.0, yellow = 20.0)))
    }

    @Test fun `setting a color enters it and advances to the next blank, wrapping`() {
        val entry =
            TruthEntry(
                colors = listOf(Color.RED, Color.RED) + List(7) { null },
                sources = List(2) { TruthSource.ENTERED } + List(7) { null },
                selected = 2,
            ).set(Color.BLUE)
        assertEquals(Color.BLUE, entry.colors[2])
        assertEquals(TruthSource.ENTERED, entry.sources[2])
        assertEquals(3, entry.selected)

        val wrap = TruthEntry(colors = listOf<Color?>(null) + List(8) { Color.RED }, selected = 8).set(Color.RED)
        assertEquals(0, wrap.selected)
        val done = wrap.set(Color.WHITE)
        assertNull(done.selected)
        assertTrue(done.complete)
        assertEquals(done, done.set(Color.GREEN))
    }

    @Test fun `prefill refreshes until the first entry, then freezes, and an equal entry still counts as entered`() {
        val first = TruthEntry(selected = null).withPrefill(listOf(null) + List(8) { Color.WHITE })
        assertEquals(0, first.selected)
        assertEquals(listOf(null) + List(8) { TruthSource.PREFILLED }, first.sources)
        val refreshed = first.select(5).withPrefill(List(9) { Color.YELLOW })
        assertEquals(5, refreshed.selected)
        assertEquals(List(9) { Color.YELLOW }, refreshed.colors)

        val entered = refreshed.set(Color.YELLOW)
        assertEquals(TruthSource.ENTERED, entered.sources[5])
        assertNull(entered.selected)
        assertEquals(entered, entered.withPrefill(List(9) { Color.RED }))
        assertFalse(TruthEntry().complete)
    }

    @Test fun `bad white is implausible, good white passes, logo ring is not uniform`() {
        val bad = Lab(76.0, 20.0, 71.0)
        assertTrue(swatchWarnings(Color.WHITE, bad, List(12) { bad }, emptyMap()).any { it is SwatchWarning.Implausible })
        val good = Lab(88.0, -6.0, -16.0)
        assertEquals(emptyList<SwatchWarning>(), swatchWarnings(Color.WHITE, good, List(12) { good }, emptyMap()))
        val dark = Lab(55.0, 0.0, 0.0)
        assertTrue(swatchWarnings(Color.WHITE, dark, List(12) { dark }, emptyMap()).any { it is SwatchWarning.Implausible })

        val logoRing = List(6) { Lab(88.0, 0.0, 0.0) } + List(6) { Lab(40.0, 0.0, 0.0) }
        val warnings = swatchWarnings(Color.WHITE, mean(logoRing), logoRing, emptyMap())
        assertTrue(SwatchWarning.NotUniform in warnings, warnings.toString())
        warnings.forEach { assertTrue(it.describe().isNotBlank()) }
    }

    @Test fun `gray color and a center too close to another saved center warn`() {
        val gray = Lab(50.0, 10.0, 10.0)
        assertTrue(swatchWarnings(Color.RED, gray, List(12) { gray }, emptyMap()).any { it is SwatchWarning.Implausible })
        val orange = Lab(60.0, 45.0, 55.0)
        val red = Lab(58.0, 48.0, 50.0)
        val warnings = swatchWarnings(Color.ORANGE, orange, List(12) { orange }, mapOf(Color.RED to red, Color.ORANGE to orange))
        assertEquals(listOf(Color.RED), warnings.filterIsInstance<SwatchWarning.TooClose>().map { it.other })
    }

    @Test fun `ring has twelve points at thirty percent of the cell side`() {
        val ring = ringOffsets()
        assertEquals(12, ring.size)
        ring.forEach { (x, y) -> assertEquals(0.30, hypot(x, y), 1e-9) }
    }

    @Test fun `frames scale to a 1024 long edge and small frames are kept`() {
        assertEquals(1024 to 576, scaledSize(1920, 1080))
        assertEquals(576 to 1024, scaledSize(1080, 1920))
        assertEquals(640 to 480, scaledSize(640, 480))
    }

    @Test fun `realignment re-meters once after 600 ms steady and not again while unchanged`() {
        val trigger = RemeterTrigger()
        val before = List(9) { Lab(50.0, 0.0, 0.0) }
        val after = List(9) { Lab(80.0, 0.0, 0.0) }
        trigger.settle(before, 0)
        var time = 0L
        val fires = mutableListOf<Long>()

        fun feed(
            grid: List<Lab>,
            until: Long,
        ) {
            while (time < until) {
                time += 100
                if (trigger.onFrame(grid, time)) fires += time
            }
        }
        feed(before, 2000)
        assertEquals(emptyList<Long>(), fires)
        feed(after, 2500)
        assertEquals(emptyList<Long>(), fires, "not yet steady for 600 ms")
        feed(after, 6000)
        assertEquals(listOf(2700L), fires)
    }

    @Test fun `a change inside the settle window becomes the reference and does not re-meter`() {
        val trigger = RemeterTrigger()
        trigger.settle(List(9) { Lab(50.0, 0.0, 0.0) }, 0)
        val shifted = List(9) { Lab(80.0, 0.0, 0.0) }
        var fired = 0
        var time = 0L
        while (time < 3000) {
            time += 100
            if (trigger.onFrame(shifted, time)) fired++
        }
        assertEquals(0, fired)
    }

    @Test fun `confirmed truth keeps per-sticker sources and is null until complete`() {
        val prefilled = List(9) { if (it == 4) null else Color.RED }
        val partial = TruthEntry(selected = null).withPrefill(prefilled)
        assertEquals(null, partial.confirmed())
        val (colors, sources) = partial.set(Color.WHITE).confirmed()!!
        assertEquals(List(9) { if (it == 4) Color.WHITE else Color.RED }, colors)
        assertEquals(List(9) { if (it == 4) TruthSource.ENTERED else TruthSource.PREFILLED }, sources)
    }

    @Test fun `unsteady frames restart the steady window`() {
        val trigger = RemeterTrigger()
        trigger.settle(List(9) { Lab(50.0, 0.0, 0.0) }, 0)
        var fired = 0
        var time = 2000L
        repeat(20) { index ->
            time += 100
            if (trigger.onFrame(List(9) { Lab(if (index % 2 == 0) 80.0 else 95.0, 0.0, 0.0) }, time)) fired++
        }
        assertEquals(0, fired)
    }
}
