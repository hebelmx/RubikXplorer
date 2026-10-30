package dev.rubikxplorer.domain.playback

import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Move
import kotlin.math.roundToInt

/**
 * The one playback controller (AD-20) over a Move list, shared by Demonstrations, Solution Playback
 * and the Library. Pure and immutable: every operation returns a [PlaybackUpdate] with the next
 * controller and the [PlaybackItem]s to present. It never reads a clock; time arrives through
 * [advance]. It is not a session and is never checkpointed (deliberately not serializable).
 *
 * [position] is the number of Moves played (0..[size]). While playing, a Move is emitted on [play]
 * and then every [PlaybackSpeed.stepMillis] of [advance]; playback pauses itself at the end. With
 * [reduceMotion] every item that would be an [PlaybackItem.Animate] is a [PlaybackItem.Snap] at the
 * same position and cadence. With no Moves every operation emits nothing.
 */
class PlaybackController private constructor(
    /** The states after 0..size Moves; `states[0]` is the start. Shared between copies. */
    private val states: List<CubeState>,
    val moves: List<Move>,
    val position: Int,
    val isPlaying: Boolean,
    val speed: PlaybackSpeed,
    val reduceMotion: Boolean,
    /** Milliseconds accumulated toward the next Move while playing. */
    private val elapsed: Long,
) {
    /** The state playback starts from. */
    val start: CubeState get() = states.first()

    /** The number of Moves. */
    val size: Int get() = moves.size

    /** The state after [position] Moves. */
    val state: CubeState get() = states[position]

    /** True when every Move has been played. */
    val atEnd: Boolean get() = position == size

    /** Starts playing, emitting the next Move now; at the end this is [replay]. Already playing: no change. */
    fun play(): PlaybackUpdate =
        when {
            moves.isEmpty() || isPlaying -> unchanged()
            atEnd -> replay()
            else -> forward(copy(isPlaying = true, elapsed = 0))
        }

    /** Stops playing where it is. */
    fun pause(): PlaybackUpdate = PlaybackUpdate(copy(isPlaying = false, elapsed = 0), emptyList())

    /** Pauses and plays the next Move; at the end, no change. */
    fun stepForward(): PlaybackUpdate = if (atEnd) unchanged() else forward(copy(isPlaying = false, elapsed = 0))

    /** Pauses and takes back the last played Move, animating its inverse; at 0, no change. */
    fun stepBack(): PlaybackUpdate {
        if (position == 0) return unchanged()
        val back = position - 1
        val next = copy(position = back, isPlaying = false, elapsed = 0)
        return PlaybackUpdate(next, listOf(next.item(moves[back].inverse(), back)))
    }

    /** Back to the start (a [PlaybackItem.Snap]) and plays from there. */
    fun replay(): PlaybackUpdate {
        if (moves.isEmpty()) return unchanged()
        val rewound = copy(position = 0, isPlaying = true, elapsed = 0)
        val played = rewound.forward(rewound)
        return PlaybackUpdate(played.controller, listOf(PlaybackItem.Snap(0, start)) + played.items)
    }

    /** Changes the speed; time already accumulated toward the next Move is kept. */
    fun withSpeed(speed: PlaybackSpeed): PlaybackUpdate = PlaybackUpdate(copy(speed = speed), emptyList())

    /** Pauses and snaps to the Move boundary nearest [fraction] (clamped to 0..1) of the list. */
    fun scrubTo(fraction: Double): PlaybackUpdate = seek((fraction.coerceIn(0.0, 1.0) * size).roundToInt())

    /** Pauses and snaps to [position] (clamped to 0..[size]). */
    fun seek(position: Int): PlaybackUpdate {
        if (moves.isEmpty()) return unchanged()
        val to = position.coerceIn(0, size)
        val next = copy(position = to, isPlaying = false, elapsed = 0)
        return PlaybackUpdate(next, listOf(PlaybackItem.Snap(to, states[to])))
    }

    /** Lets [elapsedMillis] pass: while playing, emits one Move per [PlaybackSpeed.stepMillis] reached. */
    fun advance(elapsedMillis: Long): PlaybackUpdate {
        if (!isPlaying) return unchanged()
        var current = copy(elapsed = elapsed + elapsedMillis.coerceAtLeast(0))
        val items = mutableListOf<PlaybackItem>()
        while (current.isPlaying && current.elapsed >= speed.stepMillis) {
            val stepped = current.forward(current.copy(elapsed = current.elapsed - speed.stepMillis))
            items += stepped.items
            current = stepped.controller
        }
        return PlaybackUpdate(current, items)
    }

    /** [base] one Move further, pausing (and dropping accumulated time) when that reaches the end. */
    private fun forward(base: PlaybackController): PlaybackUpdate {
        val to = base.position + 1
        val ended = to == size
        val next = base.copy(position = to, isPlaying = base.isPlaying && !ended, elapsed = if (ended) 0 else base.elapsed)
        return PlaybackUpdate(next, listOf(next.item(moves[base.position], to)))
    }

    private fun item(
        move: Move,
        to: Int,
    ): PlaybackItem = if (reduceMotion) PlaybackItem.Snap(to, states[to]) else PlaybackItem.Animate(move, to, states[to])

    private fun unchanged(): PlaybackUpdate = PlaybackUpdate(this, emptyList())

    private fun copy(
        position: Int = this.position,
        isPlaying: Boolean = this.isPlaying,
        speed: PlaybackSpeed = this.speed,
        elapsed: Long = this.elapsed,
    ): PlaybackController = PlaybackController(states, moves, position, isPlaying, speed, reduceMotion, elapsed)

    companion object {
        /** Milliseconds between Moves at [PlaybackSpeed.NORMAL]. */
        const val STEP_MILLIS: Long = 600

        /** A paused controller at position 0 over [moves] from [start]. */
        fun of(
            start: CubeState,
            moves: List<Move>,
            reduceMotion: Boolean = false,
            speed: PlaybackSpeed = PlaybackSpeed.NORMAL,
        ): PlaybackController =
            PlaybackController(
                states = moves.runningFold(start) { state, move -> state.apply(move) },
                moves = moves.toList(),
                position = 0,
                isPlaying = false,
                speed = speed,
                reduceMotion = reduceMotion,
                elapsed = 0,
            )
    }
}
