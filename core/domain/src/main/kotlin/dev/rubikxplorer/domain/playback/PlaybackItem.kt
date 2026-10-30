package dev.rubikxplorer.domain.playback

import dev.rubikxplorer.domain.cube.CubeState
import dev.rubikxplorer.domain.cube.Move

/**
 * One presentation step of playback: the drawn cube should end at [target], the state after the
 * first [position] Moves (`target == start.apply(moves.take(position))`).
 */
sealed interface PlaybackItem {
    val position: Int
    val target: CubeState

    /** Animate [move] into [target] (stepping back animates the inverse of the undone Move). */
    data class Animate(
        val move: Move,
        override val position: Int,
        override val target: CubeState,
    ) : PlaybackItem

    /** Jump straight to [target] with no animation (seek, scrub, replay, or any step under reduce motion). */
    data class Snap(
        override val position: Int,
        override val target: CubeState,
    ) : PlaybackItem
}

/** The result of every [PlaybackController] operation: the next [controller] and the [items] to present, in order. */
data class PlaybackUpdate(
    val controller: PlaybackController,
    val items: List<PlaybackItem>,
)
