package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StickerTruthCheckTest {
    private fun solved(): MutableList<Color?> = Color.entries.flatMap { color -> List<Color?>(9) { color } }.toMutableList()

    private fun truths(
        colors: List<Color?>,
        source: TruthSource? = TruthSource.ENTERED,
    ) = colors.map { StickerTruth(it, if (it == null) null else source, reviewed = it != null) }

    @Test
    fun `nine of each colour over 54 stickers passes and decides PASSED whatever the reason`() {
        val check = StickerTruthCheck.of(truths(solved()))
        assertTrue(check.passed)
        assertEquals(Color.entries.associateWith { 9 }, check.counts)
        assertEquals(0, check.unknownCount)
        assertEquals(StickerTruthDecision.Passed, check.decide(null))
        assertEquals(StickerTruthDecision.Passed, check.decide("any reason"))
    }

    @Test
    fun `over and under are named in colour order in colour order`() {
        val colors = solved()
        colors[colors.indexOfFirst { it == Color.BLUE }] = Color.WHITE
        val check = StickerTruthCheck.of(truths(colors))
        assertFalse(check.passed)
        assertEquals(mapOf(Color.WHITE to 10), check.over)
        assertEquals(mapOf(Color.BLUE to 8), check.under)
    }

    @Test
    fun `unknown stickers fail the check and are counted`() {
        val colors = solved()
        colors[0] = null
        val check = StickerTruthCheck.of(truths(colors))
        assertFalse(check.passed)
        assertEquals(1, check.unknownCount)
        assertEquals(mapOf(Color.WHITE to 8), check.under)
    }

    @Test
    fun `an off-count group is overridden only with a non-blank trimmed reason`() {
        val colors = solved()
        colors[0] = Color.RED
        val check = StickerTruthCheck.of(truths(colors, TruthSource.PREFILLED))
        assertEquals(StickerTruthDecision.Refused, check.decide(null))
        assertEquals(StickerTruthDecision.Refused, check.decide(""))
        assertEquals(StickerTruthDecision.Refused, check.decide("   \t "))
        assertEquals(StickerTruthDecision.Overridden("logo sticker"), check.decide("  logo sticker "))
        assertNull(StickerTruthCheck.overrideReason(" "))
        assertEquals("x", StickerTruthCheck.overrideReason(" x "))
    }

    @Test
    fun `provenance travels with each truth sticker and the check needs exactly 54`() {
        val truth = StickerTruth(Color.GREEN, TruthSource.PREFILLED, reviewed = false)
        assertEquals(TruthSource.PREFILLED, truth.source)
        assertFalse(truth.reviewed)
        assertThrows(IllegalArgumentException::class.java) { StickerTruthCheck.of(truths(solved().take(53))) }
        assertEquals(9, StickerTruthCheck.ofColors(List(9) { Color.RED }).counts[Color.RED])
    }
}
