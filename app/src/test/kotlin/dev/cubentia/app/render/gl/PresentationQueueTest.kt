package dev.cubentia.app.render.gl

import dev.cubentia.app.render.PresentationItem
import dev.cubentia.app.session.allMoves
import dev.cubentia.domain.cube.CubeState
import org.junit.Assert.assertArrayEquals
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

    @Test fun finishAllCommitsActiveAndWaitingTurnsOnBothSidesOfSnap() {
        val q = PresentationQueue()
        val initial = CubeState.SOLVED
        val first = allMoves[0]
        val second = allMoves[3]
        val last = allMoves[6]
        val checkpoint = initial.apply(first, second)
        val final = checkpoint.apply(last).toFacelets()
        q.submit(
            listOf(
                PresentationItem.Animate(first, 1, initial.apply(first).toFacelets()),
                PresentationItem.Animate(second, 2, checkpoint.toFacelets()),
            ),
        )
        q.onFrame(0)
        q.onFrame(50_000_000)
        q.finishAll()
        assertEquals(checkpoint.toFacelets(), q.model.facelets())
        q.submit(
            listOf(
                PresentationItem.Snap(3, initial.toFacelets()),
                PresentationItem.Snap(4, checkpoint.toFacelets()),
                PresentationItem.Animate(last, 5, final),
            ),
        )
        q.finishAll()
        assertEquals(final, q.model.facelets())
        assertFalse(q.isBusy)
    }

    @Test fun matchingTurnAdoptsGhostWithoutRewindingAndFinishesInRemainingTime() {
        val q = PresentationQueue()
        val initial = CubeState.SOLVED
        val move = allMoves[0]
        val target = initial.apply(move).toFacelets()
        q.model.setGhost(move, -54f)
        val layer = q.model.ghostLayer
        assertEquals(9, layer.size)
        val held = FloatArray(16).also { q.model.modelMatrix(layer.first(), it) }
        assertEquals(initial.toFacelets(), q.model.facelets())
        q.submit(listOf(PresentationItem.Animate(move, 1, target)))
        q.onFrame(0)
        val firstFrame = FloatArray(16).also { q.model.modelMatrix(layer.first(), it) }
        assertArrayEquals(held, firstFrame, 0f)
        assertTrue(q.model.ghostLayer.isEmpty())
        q.onFrame(79_000_000)
        assertTrue(q.isBusy)
        q.onFrame(80_000_000)
        assertFalse(q.isBusy)
        assertEquals(target, q.model.facelets())
    }

    @Test fun cancellationMismatchAndSnapClearGhost() {
        val q = PresentationQueue()
        val solved = CubeState.SOLVED.toFacelets()
        val move = allMoves[0]
        val other = allMoves[3]
        q.model.setGhost(move, 40f)
        val cubie = q.model.ghostLayer.first()
        q.model.clearGhost()
        val initial = FloatArray(16).also { q.model.modelMatrix(cubie, it) }
        assertEquals(solved, q.model.facelets())

        q.model.setGhost(move, 40f)
        q.submit(listOf(PresentationItem.Animate(other, 1, CubeState.SOLVED.apply(other).toFacelets())))
        q.onFrame(0)
        assertArrayEquals(initial, FloatArray(16).also { q.model.modelMatrix(cubie, it) }, 0f)
        assertTrue(q.model.ghostLayer.isEmpty())
        q.finishAll()

        q.model.setGhost(move, 40f)
        q.submit(listOf(PresentationItem.Snap(2, solved)))
        q.onFrame(100_000_000)
        assertTrue(q.model.ghostLayer.isEmpty())
        assertEquals(solved, q.model.facelets())
    }
}
