package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.RecognitionFixtures.LABS
import dev.cubentia.domain.recognition.continuous.ContinuousFaceHandoff
import dev.cubentia.domain.recognition.continuous.ContinuousScanHandoff
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 10.32: the Guided viewfinder of Quick Scan's hand-over keeps the two guards of Story 10.17. */
class GuidedScanHandOverTest {
    private fun frame(color: Color) = FrameStats(List(9) { CellReading(LABS.getValue(color), true) }, 120.0, 0.0, 100.0, 12)

    private fun kept(
        vararg faces: Face,
        unread: Face? = null,
        anchored: Boolean = true,
    ) = ContinuousScanHandoff(
        faces.associateWith { face ->
            ContinuousFaceHandoff(
                face,
                List(9) { Color.home(face) },
                List(9) { StickerConfidence.CONFIDENT },
                List(9) { index -> LABS.getValue(Color.home(face)).takeUnless { face == unread && index == 0 } },
                orientationLocked = true,
            )
        },
        globalAnchored = anchored,
    )

    private fun plan(kept: ContinuousScanHandoff) = GuidedScanPlan.steps.filter { it.face !in kept.faces }

    private fun GuidedScanController.shoot(
        color: Color,
        now: Long,
    ): List<GuidedScanEffect> {
        onFrame(frame(color), 0, now)
        return onShutter(now + 1)
    }

    @Test
    fun `a kept face shown again is never captured for an asked face`() {
        val handoff = kept(Face.U, Face.F, Face.R)
        val controller = GuidedScanController.handOver(plan(handoff), handoff)
        assertEquals(3, controller.planSize)
        assertEquals(Face.B, controller.step?.face)
        // The white face Quick Scan kept is still in view: rejected as a repeated face, nothing stored.
        assertTrue(controller.shoot(Color.WHITE, 0).isEmpty())
        assertEquals(Coaching.WRONG_FACE, controller.coaching)
        assertEquals(0, controller.stepIndex)
        val captured = controller.shoot(Color.BLUE, 100).filterIsInstance<GuidedScanEffect.Capture>().single()
        assertEquals(Face.B, captured.sample.face)
        assertEquals(1, controller.stepIndex)
        // Without the kept faces the same white frame would have been stored as the blue face.
        val unguarded = GuidedScanController(plan(handoff)) { emptyList() }
        assertEquals(
            Face.B,
            unguarded
                .shoot(Color.WHITE, 0)
                .filterIsInstance<GuidedScanEffect.Capture>()
                .single()
                .sample.face,
        )
    }

    @Test
    fun `only anchored kept faces with nine valid Labs are known`() {
        assertEquals(listOf(Face.U, Face.R), ScanAssembler.keptSamples(kept(Face.U, Face.F, Face.R, unread = Face.F)).map { it.face })
        assertTrue(ScanAssembler.keptSamples(kept(Face.U, Face.F, anchored = false)).isEmpty())
        val sample = ScanAssembler.keptSamples(kept(Face.U)).single()
        assertEquals(Color.WHITE, sample.centerColor)
        assertEquals(LABS.getValue(Color.WHITE), sample.center)
        assertNull(GuidedScanController.handOver(emptyList(), kept(Face.U)).step)
    }

    @Test
    fun `the hand-over waits two and a half seconds before its first automatic capture`() {
        fun firstCapture(controller: GuidedScanController): Long =
            (0L..5_000L step 100).first { now -> controller.onFrame(frame(Color.BLUE), 0, now).any { it is GuidedScanEffect.Capture } }
        val handoff = kept(Face.U, Face.F, Face.R)
        assertTrue(firstCapture(GuidedScanController(plan(handoff)) { emptyList() }) < GuidedScanController.HAND_OVER_DELAY_MS)
        assertEquals(GuidedScanController.HAND_OVER_DELAY_MS, firstCapture(GuidedScanController.handOver(plan(handoff), handoff)))
    }
}
