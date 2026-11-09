package dev.cubentia.app.render.gl

import dev.cubentia.app.render.PresentationItem
import dev.cubentia.app.session.allMoves
import dev.cubentia.domain.cube.CubeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationQueueTest {
    @Test fun speedScalesDuration() {
        for ((speed, millis) in listOf(
            dev.cubentia.application.preference.AnimationSpeed.FAST to 100L,
            dev.cubentia.application.preference.AnimationSpeed.SLOW to 400L,
        )) {
            val q = PresentationQueue().also { it.animationSpeed = speed }
            val move = allMoves[0]
            q.submit(listOf(PresentationItem.Animate(move, 1, CubeState.SOLVED.apply(move).toFacelets())))
            q.onFrame(0)
            q.onFrame((millis - 1) * 1_000_000)
            assertTrue(q.isBusy)
            q.onFrame(millis * 1_000_000)
            assertFalse(q.isBusy)
        }
    }

    @Test fun timingAndCancellationCommitGeometryRatherThanTarget() {
        val q = PresentationQueue()
        val move = allMoves[0]
        val target = CubeState.SOLVED.apply(move).toFacelets()
        q.submit(listOf(PresentationItem.Animate(move, 1, CubeState.SOLVED.toFacelets())))
        q.onFrame(0)
        q.onFrame(50_000_000)
        assertNotEquals(target, q.model.facelets())
        q.onFrame(200_000_000)
        assertEquals(target, q.model.facelets())
        q.submit(listOf(PresentationItem.Animate(move.inverse(), 2, target)))
        q.onFrame(210_000_000)
        q.finishCurrent()
        assertEquals(CubeState.SOLVED.toFacelets(), q.model.facelets())
    }

    @Test fun snapNowDropsTheTurnInFlightAndEverythingWaiting() {
        val q = PresentationQueue()
        val move = allMoves[0]
        val turned = CubeState.SOLVED.apply(move).toFacelets()
        q.submit(listOf(PresentationItem.Animate(move, 1, turned), PresentationItem.Animate(move, 2, turned)))
        q.onFrame(0)
        q.onFrame(50_000_000)
        val target = CubeState.SOLVED.apply(allMoves[3]).toFacelets()
        q.snapNow(target)
        assertEquals(target, q.model.facelets())
        assertFalse(q.isBusy)
        q.onFrame(400_000_000)
        assertEquals(target, q.model.facelets())
    }

    @Test fun burstKeepsEveryTargetAndSnapWaitsForItsFrame() {
        val q = PresentationQueue()
        var state = CubeState.SOLVED
        val items =
            allMoves.take(8).mapIndexed { i, move ->
                state = state.apply(move)
                PresentationItem.Animate(move, i.toLong(), state.toFacelets())
            }
        val snap = CubeState.SOLVED.toFacelets()
        q.submit(items + PresentationItem.Snap(9, snap))
        var time = 0L
        items.forEachIndexed { i, item ->
            q.onFrame(time)
            val duration = if (8 - i >= 3) 50L else 200L
            q.onFrame(time + (duration - 1) * 1_000_000)
            assertNotEquals(item.target, q.model.facelets())
            time += duration * 1_000_000
            q.onFrame(time)
            assertEquals(item.target, q.model.facelets())
            time += 16_000_000
        }
        assertTrue(q.isBusy)
        q.onFrame(time)
        assertEquals(snap, q.model.facelets())
        assertFalse(q.isBusy)
    }
}
