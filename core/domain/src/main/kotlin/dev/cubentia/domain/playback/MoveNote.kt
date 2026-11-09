package dev.cubentia.domain.playback

import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.SliceTurn
import dev.cubentia.domain.cube.WideTurn
import dev.cubentia.domain.text.Text

/**
 * The one source of per-Move notes (AD-20): a fixed, argument-free message per Move value saying
 * which layer turns, which way, and how its pieces move, never why (Principle 3). Keys take the form
 * `msg.move-note.<face|wide|slice|rotation>.<name>.<clockwise|counterclockwise|half>`, so every one
 * of the 54 Move values has its own message in `content/strings/en.json`.
 */
object MoveNote {
    private const val PREFIX = "msg.move-note"

    /** The note for [move]. */
    fun of(move: Move): Text {
        val (kind, name, turn) =
            when (move) {
                is FaceTurn -> Triple("face", move.face.name, move.turn)
                is WideTurn -> Triple("wide", move.face.name, move.turn)
                is SliceTurn -> Triple("slice", move.slice.name, move.turn)
                is CubeRotation -> Triple("rotation", move.axis.name, move.turn)
            }
        val key = listOf(PREFIX, kind, name.lowercase(), turn.name.lowercase()).joinToString(".")
        return Text(key)
    }
}
