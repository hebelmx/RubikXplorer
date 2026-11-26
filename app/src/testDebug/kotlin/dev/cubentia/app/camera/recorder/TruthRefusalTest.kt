package dev.cubentia.app.camera.recorder

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.StickerTruthCheck
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Story 10.34: both labellers show the Story 10.28 refusal text from one [StickerTruthCheck]. */
class TruthRefusalTest {
    private fun solved(): MutableList<Color?> = Color.entries.flatMap { color -> List<Color?>(9) { color } }.toMutableList()

    @Test
    fun `the refusal text is the Story 10_28 text, with unknowns appended`() {
        val colors = solved()
        colors[colors.indexOfFirst { it == Color.BLUE }] = Color.WHITE
        assertEquals("Not saved: White 10 (over) · Blue 8 (under)", truthRefusal(StickerTruthCheck.ofColors(colors)))
        val unknown = solved().also { it[0] = null }
        assertEquals("Not saved: White 8 (under) · 1 unknown", truthRefusal(StickerTruthCheck.ofColors(unknown)))
        assertEquals(
            countRefusal(ColorCountCheck(StickerTruthCheck.ofColors(colors).counts)),
            truthRefusal(StickerTruthCheck.ofColors(colors)),
        )
    }

    @Test
    fun `continuous labels pre-fill from the last net and keep blanks as unknowns`() {
        val net = solved().also { it[3] = null }
        val faces = sessionPrefill(net)
        val truth = sessionTruth(faces)
        assertEquals(net, truth.map { it.color })
        assertEquals(null, truth[3].source)
        assertEquals(dev.cubentia.domain.recognition.TruthSource.PREFILLED, truth[0].source)
        assertEquals(false, truth[0].reviewed)
    }
}
