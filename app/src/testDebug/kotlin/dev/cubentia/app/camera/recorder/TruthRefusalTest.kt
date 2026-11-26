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

    @Test
    fun `continuous Save labels is locked until every pre-filled sticker is checked, then passes`() {
        val faces = sessionPrefill(solved()).toMutableList()
        val untouched = StickerTruthCheck.of(sessionTruth(faces))
        assertEquals(54, untouched.unreviewedPrefills)
        assertEquals("Save locked: tap the 54 pre-filled stickers not checked yet", continuousLabelLock(untouched))
        assertEquals("Not saved: 54 pre-filled not checked", truthRefusal(untouched))
        // Tapping every sticker but one still locks Save labels.
        for (face in faces.indices) for (i in 0 until 9) if (face != 5 || i != 8) faces[face] = faces[face].tap(i)
        assertEquals(
            "Save locked: tap the 1 pre-filled stickers not checked yet",
            continuousLabelLock(StickerTruthCheck.of(sessionTruth(faces))),
        )
        faces[5] = faces[5].tap(8)
        val reviewed = StickerTruthCheck.of(sessionTruth(faces))
        assertEquals(null, continuousLabelLock(reviewed))
        assertEquals(true, reviewed.passed)
        val labels =
            dev.cubentia.domain.recognition.continuous.session.ContinuousSessionLabels
                .of(sessionTruth(faces), null)
        assertEquals(dev.cubentia.domain.recognition.continuous.session.SessionCountCheck.PASSED, labels.countCheck)
        assertEquals(
            dev.cubentia.domain.recognition.continuous.session.SessionCountCheck.REFUSED,
            dev.cubentia.domain.recognition.continuous.session.ContinuousSessionLabels
                .of(sessionTruth(sessionPrefill(solved())), "trust me")
                .countCheck,
        )
    }
}
