package dev.rubikxplorer.app.ui.freecube

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.rubikxplorer.app.R
import dev.rubikxplorer.domain.cube.Axis
import dev.rubikxplorer.domain.cube.CubeRotation
import dev.rubikxplorer.domain.cube.Face
import dev.rubikxplorer.domain.cube.FaceTurn
import dev.rubikxplorer.domain.cube.Move
import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.Slice
import dev.rubikxplorer.domain.cube.SliceTurn
import dev.rubikxplorer.domain.cube.Turn
import dev.rubikxplorer.domain.cube.WideTurn

/**
 * The plain name of [move], `<spoken>: <layer> <direction>[, like <face>]`, e.g. "R prime: right
 * face counter-clockwise" or "M: middle slice clockwise, like L". Every word comes from string
 * resources; letters come from [Notation].
 */
@Composable
fun movePlainName(move: Move): String {
    val token = Notation.format(move)
    val letter = token.take(1)
    val turn = turnOf(move)
    val turned =
        when (turn) {
            Turn.CLOCKWISE -> letter
            Turn.COUNTERCLOCKWISE -> stringResource(R.string.move_spoken_prime, letter)
            Turn.HALF -> stringResource(R.string.move_spoken_two, letter)
        }
    val spoken = if (move is WideTurn) stringResource(R.string.move_spoken_wide, turned) else turned
    val layer =
        when (move) {
            is FaceTurn -> stringResource(R.string.move_layer_face, stringResource(faceName(move.face)))
            is WideTurn -> stringResource(R.string.move_layer_wide, stringResource(faceName(move.face)))
            is SliceTurn -> stringResource(sliceName(move.slice))
            is CubeRotation -> stringResource(R.string.move_layer_rotation)
        }
    val direction =
        stringResource(
            when (turn) {
                Turn.CLOCKWISE -> R.string.move_turn_clockwise
                Turn.COUNTERCLOCKWISE -> R.string.move_turn_counterclockwise
                Turn.HALF -> R.string.move_turn_half
            },
        )
    val like = likeFace(move)
    return if (like == null) {
        stringResource(R.string.move_name, spoken, layer, direction)
    } else {
        stringResource(R.string.move_name_like, spoken, layer, direction, like.name)
    }
}

private fun turnOf(move: Move): Turn =
    when (move) {
        is FaceTurn -> move.turn
        is WideTurn -> move.turn
        is SliceTurn -> move.turn
        is CubeRotation -> move.turn
    }

/** The face a slice or Cube Rotation turns like (WCA: M like L, E like D, S like F; x like R, y like U, z like F). */
private fun likeFace(move: Move): Face? =
    when (move) {
        is SliceTurn -> {
            when (move.slice) {
                Slice.M -> Face.L
                Slice.E -> Face.D
                Slice.S -> Face.F
            }
        }

        is CubeRotation -> {
            when (move.axis) {
                Axis.X -> Face.R
                Axis.Y -> Face.U
                Axis.Z -> Face.F
            }
        }

        is FaceTurn, is WideTurn -> {
            null
        }
    }

@StringRes
private fun faceName(face: Face): Int =
    when (face) {
        Face.U -> R.string.move_face_u
        Face.R -> R.string.move_face_r
        Face.F -> R.string.move_face_f
        Face.D -> R.string.move_face_d
        Face.L -> R.string.move_face_l
        Face.B -> R.string.move_face_b
    }

@StringRes
private fun sliceName(slice: Slice): Int =
    when (slice) {
        Slice.M -> R.string.move_slice_m
        Slice.E -> R.string.move_slice_e
        Slice.S -> R.string.move_slice_s
    }
