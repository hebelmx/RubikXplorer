package dev.cubentia.domain.playback

import dev.cubentia.domain.cube.Axis
import dev.cubentia.domain.cube.CubeRotation
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Move
import dev.cubentia.domain.cube.Slice
import dev.cubentia.domain.cube.SliceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.cube.WideTurn

/** Ink-only description in grid coordinates, with row and column zero at the top left. */
data class MovePicture(
    val view: View = View.FRONT,
    val shadedCells: Set<Cell>,
    val arrow: Arrow,
    val doubledOutline: Boolean = false,
    val superscriptTwo: Boolean = false,
) {
    enum class View { FRONT, TOP }

    data class Cell(
        val row: Int,
        val col: Int,
    )

    enum class Shape { CURVED, ROUND, STRAIGHT }

    enum class Direction {
        UP,
        DOWN,
        LEFT,
        RIGHT,
        CW,
        CCW,
        ;

        fun reversed(): Direction =
            when (this) {
                UP -> DOWN
                DOWN -> UP
                LEFT -> RIGHT
                RIGHT -> LEFT
                CW -> CCW
                CCW -> CW
            }
    }

    /** Span is the moving column range for vertical arrows, row range for horizontal arrows,
     * and the full grid for round arrows. */
    data class Arrow(
        val shape: Shape,
        val direction: Direction,
        val span: IntRange,
        val behind: Boolean = false,
        val doubled: Boolean = false,
    )

    fun reversed(): MovePicture = copy(arrow = arrow.copy(direction = arrow.direction.reversed()))

    companion object {
        private val grid = 0..2

        fun of(move: Move): MovePicture =
            when (move) {
                is FaceTurn -> {
                    facePicture(move.face).turned(move.turn)
                }

                is WideTurn -> {
                    facePicture(move.face, wide = true).turned(move.turn)
                }

                is SliceTurn -> {
                    slicePicture(move.slice).turned(move.turn)
                }

                is CubeRotation -> {
                    val face =
                        when (move.axis) {
                            Axis.X -> Face.R
                            Axis.Y -> Face.U
                            Axis.Z -> Face.F
                        }
                    val picture = facePicture(face)
                    picture
                        .copy(
                            shadedCells = emptySet(),
                            arrow = picture.arrow.copy(span = grid),
                            doubledOutline = true,
                        ).turned(move.turn)
                }
            }

        private fun facePicture(
            face: Face,
            wide: Boolean = false,
        ): MovePicture =
            when (face) {
                Face.R -> {
                    linear(Shape.CURVED, Direction.UP, if (wide) 1..2 else 2..2)
                }

                Face.L -> {
                    linear(Shape.CURVED, Direction.DOWN, if (wide) 0..1 else 0..0)
                }

                Face.U -> {
                    linear(Shape.CURVED, Direction.LEFT, if (wide) 0..1 else 0..0)
                }

                Face.D -> {
                    linear(Shape.CURVED, Direction.RIGHT, if (wide) 1..2 else 2..2)
                }

                Face.F -> {
                    MovePicture(
                        shadedCells = cells(grid, grid),
                        arrow = Arrow(Shape.ROUND, Direction.CW, grid, doubled = wide),
                    )
                }

                Face.B -> {
                    MovePicture(
                        shadedCells = emptySet(),
                        arrow = Arrow(Shape.ROUND, Direction.CCW, grid, behind = true, doubled = wide),
                    )
                }
            }

        private fun slicePicture(slice: Slice): MovePicture =
            when (slice) {
                Slice.M -> linear(Shape.STRAIGHT, Direction.DOWN, 1..1)
                Slice.E -> linear(Shape.STRAIGHT, Direction.RIGHT, 1..1)
                Slice.S -> linear(Shape.STRAIGHT, Direction.RIGHT, 1..1).copy(view = View.TOP)
            }

        private fun linear(
            shape: Shape,
            direction: Direction,
            span: IntRange,
        ): MovePicture =
            MovePicture(
                shadedCells = if (direction == Direction.UP || direction == Direction.DOWN) cells(grid, span) else cells(span, grid),
                arrow = Arrow(shape, direction, span),
            )

        private fun cells(
            rows: IntRange,
            cols: IntRange,
        ): Set<Cell> = rows.flatMap { row -> cols.map { col -> Cell(row, col) } }.toSet()

        private fun MovePicture.turned(turn: Turn): MovePicture =
            when (turn) {
                Turn.CLOCKWISE -> this
                Turn.COUNTERCLOCKWISE -> reversed()
                Turn.HALF -> copy(superscriptTwo = true)
            }
    }
}
