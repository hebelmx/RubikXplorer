package dev.rubikxplorer.domain.playback

import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult
import dev.rubikxplorer.domain.playback.PlaybackItem.Animate
import dev.rubikxplorer.domain.playback.PlaybackItem.Snap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AC 1: every row of the playback I/O matrix. */
class PlaybackControllerTest {
    private fun moves(text: String): List<Move> = (Notation.parse(text) as NotationResult.Parsed).moves

    private val start = CubeState.SOLVED.apply(moves("R U"))
    private val three = moves("F R' U2")
    private val seven = moves("F R' U2 L D B' x")

    private fun state(
        list: List<Move>,
        n: Int,
    ) = start.apply(list.take(n))

    private fun controller(
        list: List<Move> = three,
        reduceMotion: Boolean = false,
    ) = PlaybackController.of(start, list, reduceMotion)

    private fun animate(
        list: List<Move>,
        n: Int,
    ) = Animate(list[n - 1], n, state(list, n))

    @Test
    fun `play emits the next Move now, then one per step, and pauses at the end`() {
        val c = controller()
        assertFalse(c.isPlaying)
        val played = c.play()
        assertTrue(played.controller.isPlaying)
        assertEquals(listOf(animate(three, 1)), played.items)
        assertEquals(emptyList<PlaybackItem>(), played.controller.advance(599).items)
        val two = played.controller.advance(600)
        assertEquals(listOf(animate(three, 2)), two.items)
        val end = two.controller.advance(1200)
        assertEquals(listOf(animate(three, 3)), end.items)
        assertFalse(end.controller.isPlaying)
        assertTrue(end.controller.atEnd)
        assertEquals(state(three, 3), end.controller.state)
        assertEquals(emptyList<PlaybackItem>(), end.controller.advance(5_000).items)
    }

    @Test
    fun `a long advance emits every Move it covers`() {
        val update = controller(seven).play().controller.advance(1_800)
        assertEquals((2..4).map { animate(seven, it) }, update.items)
        assertTrue(update.controller.isPlaying)
        assertEquals(4, update.controller.position)
    }

    @Test
    fun `speed sets the cadence and keeps accumulated time`() {
        assertEquals(1_200, PlaybackSpeed.HALF.stepMillis)
        assertEquals(600, PlaybackSpeed.NORMAL.stepMillis)
        assertEquals(300, PlaybackSpeed.DOUBLE.stepMillis)

        val half =
            controller()
                .withSpeed(PlaybackSpeed.HALF)
                .controller
                .play()
                .controller
        assertEquals(PlaybackSpeed.HALF, half.speed)
        assertEquals(emptyList<PlaybackItem>(), half.advance(1_199).items)
        assertEquals(listOf(animate(three, 2)), half.advance(1_200).items)

        val double = controller().play().controller.withSpeed(PlaybackSpeed.DOUBLE)
        assertEquals(emptyList<PlaybackItem>(), double.items)
        assertEquals(listOf(animate(three, 2)), double.controller.advance(300).items)

        // 200 ms at 1x, then 2x: 100 ms more reaches the 300 ms step.
        val mid =
            controller()
                .play()
                .controller
                .advance(200)
                .controller
        val faster = mid.withSpeed(PlaybackSpeed.DOUBLE).controller
        assertEquals(listOf(animate(three, 2)), faster.advance(100).items)
    }

    @Test
    fun `pause emits nothing and stops advance`() {
        val paused = controller().play().controller.pause()
        assertEquals(emptyList<PlaybackItem>(), paused.items)
        assertFalse(paused.controller.isPlaying)
        assertEquals(emptyList<PlaybackItem>(), paused.controller.advance(10_000).items)
        assertEquals(1, paused.controller.position)
    }

    @Test
    fun `step forward and back pause and animate one Move`() {
        val playing = controller().play().controller
        val forward = playing.stepForward()
        assertFalse(forward.controller.isPlaying)
        assertEquals(listOf(animate(three, 2)), forward.items)

        val atOne = controller().play().controller
        val back = atOne.stepBack()
        assertFalse(back.controller.isPlaying)
        assertEquals(listOf(Animate(three[0].inverse(), 0, start)), back.items)

        val fromTwo = forward.controller.stepBack()
        assertEquals(listOf(Animate(three[1].inverse(), 1, state(three, 1))), fromTwo.items)
    }

    @Test
    fun `step forward at the end and step back at 0 are no-ops`() {
        val c = controller()
        assertEquals(PlaybackUpdate(c, emptyList()), c.stepBack())
        val end = c.seek(3).controller
        assertEquals(PlaybackUpdate(end, emptyList()), end.stepForward())
    }

    @Test
    fun `replay snaps to the start and plays`() {
        val end = controller().seek(3).controller
        val replay = end.replay()
        assertEquals(listOf(Snap(0, start), animate(three, 1)), replay.items)
        assertTrue(replay.controller.isPlaying)
        assertEquals(1, replay.controller.position)
    }

    @Test
    fun `play at the end behaves as replay`() {
        val end = controller().seek(3).controller
        assertEquals(end.replay().items, end.play().items)
    }

    @Test
    fun `play while playing changes nothing`() {
        val playing = controller().play().controller
        assertEquals(PlaybackUpdate(playing, emptyList()), playing.play())
    }

    @Test
    fun `a one-Move list pauses right after play`() {
        val one = moves("F'")
        val played = PlaybackController.of(start, one).play()
        assertEquals(listOf(Animate(one[0], 1, start.apply(one))), played.items)
        assertFalse(played.controller.isPlaying)
    }

    @Test
    fun `scrub snaps to the nearest Move boundary and pauses`() {
        val scrubbed = controller(seven).play().controller.scrubTo(0.4)
        assertEquals(listOf(Snap(3, state(seven, 3))), scrubbed.items)
        assertFalse(scrubbed.controller.isPlaying)
        assertEquals(3, scrubbed.controller.position)
        assertEquals(listOf(Snap(0, start)), controller(seven).scrubTo(-2.0).items)
        assertEquals(listOf(Snap(7, state(seven, 7))), controller(seven).scrubTo(3.0).items)
    }

    @Test
    fun `seek is clamped`() {
        assertEquals(listOf(Snap(0, start)), controller().seek(-4).items)
        assertEquals(listOf(Snap(3, state(three, 3))), controller().seek(99).items)
        assertEquals(listOf(Snap(2, state(three, 2))), controller().seek(2).items)
    }

    @Test
    fun `reduce motion turns every Animate into a Snap at the same position and cadence`() {
        val c = controller(reduceMotion = true)
        assertTrue(c.reduceMotion)
        val played = c.play()
        assertEquals(listOf(Snap(1, state(three, 1))), played.items)
        assertEquals(emptyList<PlaybackItem>(), played.controller.advance(599).items)
        val two = played.controller.advance(600)
        assertEquals(listOf(Snap(2, state(three, 2))), two.items)
        assertEquals(listOf(Snap(3, state(three, 3))), two.controller.stepForward().items)
        assertEquals(listOf(Snap(1, state(three, 1))), two.controller.stepBack().items)
        assertEquals(listOf(Snap(0, start), Snap(1, state(three, 1))), two.controller.replay().items)
    }

    @Test
    fun `an empty list emits nothing for every operation`() {
        val c = PlaybackController.of(start, emptyList())
        assertEquals(0, c.size)
        assertEquals(start, c.start)
        listOf(
            c.play(),
            c.stepForward(),
            c.stepBack(),
            c.replay(),
            c.scrubTo(0.5),
            c.seek(1),
            c.advance(1_000),
            c.withSpeed(PlaybackSpeed.DOUBLE),
            c.pause(),
        ).forEach {
            assertEquals(emptyList<PlaybackItem>(), it.items)
            assertFalse(it.controller.isPlaying)
            assertEquals(0, it.controller.position)
        }
    }

    @Test
    fun `negative elapsed time counts as none`() {
        val playing = controller().play().controller
        val back = playing.advance(-1_000)
        assertEquals(emptyList<PlaybackItem>(), back.items)
        assertEquals(listOf(animate(three, 2)), back.controller.advance(600).items)
    }

    @Test
    fun `every target is the start with the played Moves applied`() {
        var c = controller(seven).play().controller
        val items = mutableListOf<PlaybackItem>()
        repeat(10) {
            val u = c.advance(250)
            items += u.items
            c = u.controller
        }
        assertEquals((2..5).toList(), items.map { it.position })
        items.forEach { assertEquals(start.apply(seven.take(it.position)), it.target) }
    }
}
