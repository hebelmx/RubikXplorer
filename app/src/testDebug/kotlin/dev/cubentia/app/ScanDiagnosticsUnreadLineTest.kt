package dev.cubentia.app

import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.recognition.continuous.ContinuousScanCue
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.FaceSlot
import dev.cubentia.domain.recognition.continuous.UnreadSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/** GH #405: the debug `quickScan unread` line, its gating and its suppression. */
@RunWith(RobolectricTestRunner::class)
class ScanDiagnosticsUnreadLineTest {
    @Before fun clear() {
        ShadowLog.clear()
        ScanDiagnostics.resetQuickScanUnread()
    }

    private fun lines() = ShadowLog.getLogsForTag(ScanDiagnostics.TAG).map { it.msg }.filter { it.startsWith("quickScan unread ") }

    private fun scanning(
        frameIndex: Int = 7,
        anchored: Boolean = true,
        mappedFaces: Int = 6,
        committed: Int = 49,
    ) = ContinuousScanSnapshot.Scanning(
        frameIndex = frameIndex,
        net = Face.entries.associateWith { List(9) { null } },
        conflictedSlots = emptyList(),
        trackedQuads = emptyList(),
        globalAnchored = anchored,
        cue = ContinuousScanCue.TiltRemainingStickers(committed),
        committedCount = committed,
        mappedFacesCount = mappedFaces,
        invariantViolationCount = 0,
        pendingFragmentFaces = 0,
    )

    private val unread =
        listOf(
            UnreadSlot.NotObserved(FaceSlot(Face.L, 2)),
            UnreadSlot.NotReady(FaceSlot(Face.U, 1), qualitySum = 0.4213, count = 1),
            UnreadSlot.BelowMargin(FaceSlot(Face.F, 3), bestD = 31.24, margin = 5.06),
            UnreadSlot.AboveDistance(FaceSlot(Face.R, 5), chosenD = 58.0),
            UnreadSlot.Reopened(FaceSlot(Face.B, 7)),
        )

    @Test fun `a stalled anchored six-face scan logs each unread slot with its numbers and thresholds`() {
        ScanDiagnostics.onQuickScanUnread(10_000L, scanning()) { unread }
        assertEquals(
            listOf(
                "quickScan unread frame=7 committed=49 notObserved=1 notReady=1 belowMargin=1 aboveDistance=1 reopened=1 " +
                    "slots=L2:notObserved,U1:notReady(q0.42/min0.55,n1/min2),F3:belowMargin(d31.2/max36,m5.1/min8)," +
                    "R5:aboveDistance(d58.0/max55),B7:reopened",
            ),
            lines(),
        )
    }

    @Test fun `an unanchored, partial or complete scan logs nothing and never asks for the slots`() {
        var asked = 0
        val supplier = {
            asked++
            unread
        }
        ScanDiagnostics.onQuickScanUnread(10_000L, scanning(anchored = false), supplier)
        ScanDiagnostics.onQuickScanUnread(20_000L, scanning(mappedFaces = 5), supplier)
        val complete =
            ContinuousScanSnapshot.Complete(
                frameIndex = 3,
                state = CubeState.SOLVED.reoriented(Orientation.HOME),
                net = Face.entries.associateWith { List(9) { null } },
                conflictedSlots = emptyList(),
                trackedQuads = emptyList(),
                cue = ContinuousScanCue.AllVerified,
                committedCount = 54,
                mappedFacesCount = 6,
                pendingFragmentFaces = 0,
            )
        ScanDiagnostics.onQuickScanUnread(30_000L, complete, supplier)
        assertEquals(0, asked)
        assertTrue(lines().isEmpty())
    }

    @Test fun `nothing unread logs nothing`() {
        ScanDiagnostics.onQuickScanUnread(10_000L, scanning()) { emptyList() }
        assertTrue(lines().isEmpty())
    }

    @Test fun `a line within 1000 ms of the last is suppressed without asking for the slots`() {
        ScanDiagnostics.onQuickScanUnread(10_000L, scanning(frameIndex = 1)) { unread }
        var asked = false
        ScanDiagnostics.onQuickScanUnread(10_999L, scanning(frameIndex = 2)) {
            asked = true
            unread.take(1)
        }
        assertFalse(asked)
        ScanDiagnostics.onQuickScanUnread(11_000L, scanning(frameIndex = 3)) { unread.take(1) }
        val logged = lines()
        assertEquals(2, logged.size)
        assertTrue(logged[0], logged[0].startsWith("quickScan unread frame=1 "))
        assertTrue(logged[1], logged[1].startsWith("quickScan unread frame=3 committed=49 notObserved=1 notReady=0 "))
    }

    @Test fun `a repeat of the last line on a later frame is suppressed until something changes`() {
        ScanDiagnostics.onQuickScanUnread(10_000L, scanning(frameIndex = 1)) { unread }
        ScanDiagnostics.onQuickScanUnread(20_000L, scanning(frameIndex = 50)) { unread }
        // The suppressed repeat restarts the 1000 ms gate: the slots are not asked for again within it.
        var asked = false
        ScanDiagnostics.onQuickScanUnread(20_999L, scanning(frameIndex = 51, committed = 50)) {
            asked = true
            unread
        }
        assertFalse(asked)
        ScanDiagnostics.onQuickScanUnread(30_000L, scanning(frameIndex = 90, committed = 50)) { unread }
        val logged = lines()
        assertEquals(2, logged.size)
        assertTrue(logged[1], logged[1].startsWith("quickScan unread frame=90 committed=50 "))
    }
}
