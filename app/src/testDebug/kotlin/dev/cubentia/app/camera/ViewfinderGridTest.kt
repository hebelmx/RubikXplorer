package dev.cubentia.app.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 10.2: the analysed grid is the drawn grid's field of view on every layout. */
class ViewfinderGridTest {
    /** A view point for a frame point, as PreviewView FILL_CENTER shows the crop rotated upright (written independently). */
    private fun toView(
        x: Double,
        y: Double,
        viewWidth: Double,
        viewHeight: Double,
        crop: IntArray,
        rotation: Int,
    ): Pair<Double, Double> {
        val (left, top, right, bottom) = crop.toList()
        val bx = x - (left + right) / 2.0
        val by = y - (top + bottom) / 2.0
        val (ux, uy) =
            when (rotation) {
                90 -> -by to bx
                180 -> -bx to -by
                270 -> by to -bx
                else -> bx to by
            }
        val uprightWidth = if (rotation % 180 == 90) bottom - top else right - left
        val uprightHeight = if (rotation % 180 == 90) right - left else bottom - top
        val scale = maxOf(viewWidth / uprightWidth, viewHeight / uprightHeight)
        return viewWidth / 2 + ux * scale to viewHeight / 2 + uy * scale
    }

    /** Asserts the analysed grid, mapped onto the view, covers exactly the drawn grid. */
    private fun assertAnalysesDrawnGrid(
        name: String,
        viewWidth: Double,
        viewHeight: Double,
        crop: IntArray,
        rotation: Int,
    ): GridSquare {
        val grid = ViewfinderGrid.inFrame(viewWidth, viewHeight, crop[0], crop[1], crop[2], crop[3], rotation)
        val corners =
            listOf(grid.left to grid.top, grid.left + grid.side to grid.top + grid.side).map { (x, y) ->
                toView(x, y, viewWidth, viewHeight, crop, rotation)
            }
        val drawn = ViewfinderGrid.inView(viewWidth, viewHeight)
        val tolerance = 1e-6
        assertEquals(drawn.left, corners.minOf { it.first }, tolerance, "$name left")
        assertEquals(drawn.top, corners.minOf { it.second }, tolerance, "$name top")
        assertEquals(drawn.left + drawn.side, corners.maxOf { it.first }, tolerance, "$name right")
        assertEquals(drawn.top + drawn.side, corners.maxOf { it.second }, tolerance, "$name bottom")
        // The analysed grid lies inside the frame's crop.
        assertTrue(grid.left >= crop[0] - tolerance && grid.left + grid.side <= crop[2] + tolerance, "$name inside crop x")
        assertTrue(grid.top >= crop[1] - tolerance && grid.top + grid.side <= crop[3] + tolerance, "$name inside crop y")
        return grid
    }

    @Test
    fun `the drawn grid is the centered square of the grid fraction of the shorter side`() {
        assertSquare(GridSquare(60.0, 705.0, 480.0), ViewfinderGrid.inView(600.0, 1890.0))
    }

    @Test
    fun `phone stacked layout, 640x480 frame turned 90 degrees, with and without a viewport crop`() {
        // S24 FE portrait: the viewfinder pane is the full width and 45 % of the height.
        assertAnalysesDrawnGrid("viewport", 1080.0, 990.0, intArrayOf(100, 0, 540, 480), 90)
        assertAnalysesDrawnGrid("full frame", 1080.0, 990.0, intArrayOf(0, 0, 640, 480), 90)
        assertAnalysesDrawnGrid("full frame 270", 1080.0, 990.0, intArrayOf(0, 0, 640, 480), 270)
    }

    @Test
    fun `tablet portrait two-pane layout, a tall narrow pane`() {
        // Lenovo TB128XU portrait: the preview pane is ~600x1890; the viewport crop is 640x203 of a 640x480 frame.
        val viewport = assertAnalysesDrawnGrid("viewport", 600.0, 1890.0, intArrayOf(0, 138, 640, 341), 90)
        assertEquals(0.8 * 203, viewport.side, 1e-6)
        // Without a matching viewport (none, or one bound before the pane was resized) the drawn grid is a much smaller
        // part of the frame than the centered 80 % square of the crop.
        val fullFrame = assertAnalysesDrawnGrid("full frame", 600.0, 1890.0, intArrayOf(0, 0, 640, 480), 90)
        assertTrue(fullFrame.side < 0.5 * 0.8 * 480, "side ${fullFrame.side}")
        assertAnalysesDrawnGrid("full frame 270", 600.0, 1890.0, intArrayOf(0, 0, 640, 480), 270)
        assertAnalysesDrawnGrid("sensor upright", 600.0, 1890.0, intArrayOf(0, 0, 480, 640), 0)
    }

    @Test
    fun `landscape layout, frame already upright or upside down`() {
        assertAnalysesDrawnGrid("viewport", 1200.0, 1000.0, intArrayOf(32, 0, 608, 480), 0)
        assertAnalysesDrawnGrid("full frame", 1200.0, 1000.0, intArrayOf(0, 0, 640, 480), 0)
        assertAnalysesDrawnGrid("upside down", 1200.0, 1000.0, intArrayOf(10, 20, 630, 470), 180)
    }

    @Test
    fun `an unknown view size reads the centered square of the crop`() {
        assertSquare(GridSquare(148.0, 48.0, 384.0), ViewfinderGrid.inFrame(0.0, 0.0, 100, 0, 580, 480, 90))
    }

    private fun assertSquare(
        expected: GridSquare,
        actual: GridSquare,
    ) {
        assertEquals(expected.left, actual.left, 1e-6, "left")
        assertEquals(expected.top, actual.top, 1e-6, "top")
        assertEquals(expected.side, actual.side, 1e-6, "side")
    }
}
