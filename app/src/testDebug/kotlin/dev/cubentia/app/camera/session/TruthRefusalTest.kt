package dev.cubentia.app.camera.session

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.StickerTruthCheck
import dev.cubentia.domain.recognition.StickerTruthDecision
import dev.cubentia.domain.recognition.TruthSource
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionLabels
import dev.cubentia.domain.recognition.continuous.session.SessionCountCheck
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 10.34: the session labeller's refusal text, colour names, pre-fill and pre-fill lock, decided by [StickerTruthCheck]. */
class TruthRefusalTest {
    private fun solved(): MutableList<Color?> = Color.entries.flatMap { color -> List<Color?>(9) { color } }.toMutableList()

    @Test
    fun `the refusal text is the Story 10_28 text, with unknowns and unchecked pre-fills appended`() {
        val colors = solved()
        colors[colors.indexOfFirst { it == Color.BLUE }] = Color.WHITE
        assertEquals("Not saved: White 10 (over) · Blue 8 (under)", truthRefusal(StickerTruthCheck.ofColors(colors)))
        val unknowns =
            colors.toMutableList().also { list ->
                list.indices.filter { list[it] == Color.YELLOW }.take(2).forEach {
                    list[it] =
                        null
                }
            }
        assertEquals(
            "Not saved: White 10 (over) · Yellow 7 (under) · Blue 8 (under) · 2 unknown",
            truthRefusal(StickerTruthCheck.ofColors(unknowns)),
        )
        assertEquals(
            "Not saved: 3 pre-filled not checked",
            truthRefusal(StickerTruthCheck(StickerTruthCheck.ofColors(solved()).counts, 0, 3)),
        )
        assertEquals("Green", colorLabel(Color.GREEN))
        assertEquals("F · Green", faceChipLabel(Face.F))
        assertEquals("U · White", faceChipLabel(Face.U))
    }

    @Test
    fun `labels pre-fill from the session net and keep blanks as explicit unknowns`() {
        val net = solved().also { it[3] = null }
        val truth = sessionTruth(sessionPrefill(net))
        assertEquals(net, truth.map { it.color })
        assertNull(truth[3].source)
        assertEquals(TruthSource.PREFILLED, truth[0].source)
        assertFalse(truth[0].reviewed)
    }

    @Test
    fun `Save labels is locked until every pre-filled sticker is tapped, then passes`() {
        val faces = sessionPrefill(solved()).toMutableList()
        val untouched = StickerTruthCheck.of(sessionTruth(faces))
        assertEquals(54, untouched.unreviewedPrefills)
        assertEquals("Save locked: tap the 54 pre-filled stickers not checked yet", labelLock(untouched))
        // Tapping every sticker but one still locks Save labels: a pre-fill counts only once tapped.
        for (face in faces.indices) for (i in 0 until 9) if (face != 5 || i != 8) faces[face] = faces[face].tap(i)
        assertEquals("Save locked: tap the 1 pre-filled stickers not checked yet", labelLock(StickerTruthCheck.of(sessionTruth(faces))))
        faces[5] = faces[5].tap(8)
        val reviewed = StickerTruthCheck.of(sessionTruth(faces))
        assertNull(labelLock(reviewed))
        assertTrue(reviewed.passed)
        assertEquals(SessionCountCheck.PASSED, ContinuousSessionLabels.of(sessionTruth(faces), null).countCheck)
        // No reason overrides an unchecked pre-fill.
        assertEquals(SessionCountCheck.REFUSED, ContinuousSessionLabels.of(sessionTruth(sessionPrefill(solved())), "trust me").countCheck)
    }

    @Test
    fun `an unknown is stored as a null colour, counted, and an off count needs a non-empty reason`() {
        val faces = sessionPrefill(solved()).map { face -> (0 until 9).fold(face) { f, i -> f.tap(i) } }.toMutableList()
        // The palette's Unknown on U0, and Blue on U1: White 7, Blue 10, 1 unknown.
        faces[0] = faces[0].tap(0).set(null)
        faces[0] = faces[0].tap(1).set(Color.BLUE)
        val truth = sessionTruth(faces)
        assertNull(truth[0].color)
        assertEquals(TruthSource.ENTERED, truth[0].source)
        assertTrue(truth[0].reviewed)
        val check = StickerTruthCheck.of(truth)
        assertNull(labelLock(check))
        assertEquals("Not saved: White 7 (under) · Blue 10 (over) · 1 unknown", truthRefusal(check))
        assertEquals(StickerTruthDecision.Refused, check.decide("  "))
        assertEquals(StickerTruthDecision.Overridden("glare on U"), check.decide(" glare on U "))
        val overridden = ContinuousSessionLabels.of(truth, "glare on U")
        assertEquals(SessionCountCheck.OVERRIDDEN, overridden.countCheck)
        assertEquals("glare on U", overridden.overrideReason)
        assertNull(overridden.stickers[0].color)
        assertEquals(SessionCountCheck.REFUSED, ContinuousSessionLabels.of(truth, null).countCheck)
    }

    @Test
    fun `picking the pre-filled colour confirms the pre-fill, another colour enters it`() {
        val face = LabelFace.prefilled(List(9) { Color.RED })
        val confirmed = face.tap(4).set(Color.RED)
        assertEquals(TruthSource.PREFILLED, confirmed.sources[4])
        assertTrue(confirmed.reviewed[4])
        val entered = face.tap(4).set(Color.ORANGE)
        assertEquals(TruthSource.ENTERED, entered.sources[4])
        assertEquals(Color.ORANGE, entered.colors[4])
        // The selection moves on to the next sticker not checked yet.
        assertEquals(5, entered.selected)
    }
}
