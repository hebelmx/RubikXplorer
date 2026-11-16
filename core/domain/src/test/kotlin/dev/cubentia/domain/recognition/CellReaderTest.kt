package dev.cubentia.domain.recognition

import dev.cubentia.domain.recognition.evaluation.oq7.LabHistogram
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class CellReaderTest {
    private fun read(pixels: List<Lab>): CellReading = (CellReader.read(pixels) as CellReadResult.Read).reading

    @Test
    fun `empty cell is a sealed failure, not an exception`() {
        assertEquals(CellReadResult.Failed(CellReadFailure.NO_PIXELS), CellReader.read(emptyList()))
    }

    @Test
    fun `non-finite pixel is a sealed failure`() {
        assertEquals(
            CellReadResult.Failed(CellReadFailure.INVALID_PIXEL),
            CellReader.read(listOf(Lab(50.0, 0.0, 0.0), Lab(Double.NaN, 0.0, 0.0))),
        )
        assertEquals(
            CellReadResult.Failed(CellReadFailure.INVALID_PIXEL),
            CellReader.read(listOf(Lab(50.0, Double.POSITIVE_INFINITY, 0.0))),
        )
        assertEquals(
            CellReadResult.Failed(CellReadFailure.INVALID_PIXEL),
            CellReader.read(listOf(Lab(50.0, 0.0, Double.NEGATIVE_INFINITY))),
        )
    }

    @Test
    fun `uniform cell is plain and reads its color`() {
        val reading = read(List(20) { Lab(55.0, -60.0, 40.0) })
        assertEquals(Lab(55.0, -60.0, 40.0), reading.value)
        assertTrue(reading.plain)
    }

    @Test
    fun `glare is masked and a logo below 60 percent share is not plain`() {
        val white = List(5) { Lab(90.0, 0.0, 0.0) }
        val logo = List(5) { Lab(40.0, 0.0, -40.0) }
        val glare = List(10) { Lab(99.0, 0.0, 0.0) }
        val reading = read(white + logo + glare)
        // 5 and 5 unmasked: tie goes to the first bin in L, a, b order (the logo's lower L bin).
        assertEquals(Lab(40.0, 0.0, -40.0), reading.value)
        assertFalse(reading.plain)
        // 6 of 10 is exactly the 60% plain share.
        assertTrue(read(List(6) { Lab(90.0, 0.0, 0.0) } + List(4) { Lab(40.0, 0.0, -40.0) }).plain)
    }

    @Test
    fun `all-glare cell falls back to all pixels`() {
        val reading = read(listOf(Lab(98.0, 0.0, 0.0), Lab(100.0, 2.0, 2.0)))
        assertEquals(Lab(99.0, 1.0, 1.0), reading.value)
        assertFalse(reading.plain)
    }

    @Test
    fun `out-of-range pixels are not binned`() {
        val reading = read(listOf(Lab(50.0, 200.0, 0.0), Lab(50.0, 300.0, 0.0)))
        assertEquals(Lab(50.0, 250.0, 0.0), reading.value)
        assertFalse(reading.plain)
    }

    @Test
    fun `matches the spike histogram mode on the same pixels`() {
        val random = Random(153)
        repeat(300) { round ->
            val clusters =
                List(
                    1 + random.nextInt(3),
                ) { Lab(random.nextDouble(0.0, 100.0), random.nextDouble(-120.0, 120.0), random.nextDouble(-120.0, 120.0)) }
            val pixels =
                List(1 + random.nextInt(200)) {
                    val base = clusters[random.nextInt(clusters.size)]
                    val spread = if (round % 5 == 0) 40.0 else 6.0
                    Lab(
                        (base.l + random.nextDouble(-spread, spread)).coerceIn(0.0, 100.0),
                        base.a + random.nextDouble(-spread, spread) * 2,
                        base.b + random.nextDouble(-spread, spread) * 2,
                    )
                }
            val spike = LabHistogram.feature(pixels, LabHistogram.peak(pixels))
            val reading = read(pixels)
            assertEquals(spike.mode, reading.value, "round $round")
            assertEquals(spike.modeShare >= LabHistogram.PLAIN_SHARE, reading.plain, "round $round")
            assertEquals(spike.modeShare, CellReader.mode(pixels).share, "round $round")
        }
    }
}
