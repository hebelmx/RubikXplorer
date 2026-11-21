package dev.cubentia.app.camera

import kotlin.math.max
import kotlin.math.min

/** An axis-aligned square: top-left corner ([left], [top]) and [side], in pixels of whatever space it lives in. */
internal data class GridSquare(
    val left: Double,
    val top: Double,
    val side: Double,
)

/**
 * Where the drawn grid sits in the camera frame (Story 10.2). The overlay draws a centered square of
 * [FrameReader.GRID_FRACTION] of the preview view's shorter side; the preview shows the frame's crop rectangle rotated
 * upright by `rotationDegrees` (clockwise) and scaled to fill the view, centered (PreviewView FILL_CENTER). Mapping the
 * drawn square back through that transform gives the analysed grid in frame (ImageProxy) pixels, so the analyzer reads
 * exactly what the Learner sees inside the grid on every layout — phone stacked, tablet two-pane or landscape — even
 * when the crop rectangle's aspect is not the view's (a viewport bound before the pane was resized, or none).
 */
internal object ViewfinderGrid {
    /** The drawn grid in view pixels: the centered square of [FrameReader.GRID_FRACTION] of the shorter side. */
    fun inView(
        viewWidth: Double,
        viewHeight: Double,
    ): GridSquare {
        val side = min(viewWidth, viewHeight) * FrameReader.GRID_FRACTION
        return GridSquare((viewWidth - side) / 2, (viewHeight - side) / 2, side)
    }

    /**
     * The drawn grid of a [viewWidth]×[viewHeight] preview in frame pixels, for a frame whose crop rectangle is
     * ([cropLeft], [cropTop], [cropRight], [cropBottom]) and whose [rotationDegrees] turn it upright. A view of unknown
     * size (≤ 0) is taken to have the crop's own upright aspect.
     */
    fun inFrame(
        viewWidth: Double,
        viewHeight: Double,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
        rotationDegrees: Int,
    ): GridSquare {
        val cropWidth = (cropRight - cropLeft).toDouble()
        val cropHeight = (cropBottom - cropTop).toDouble()
        val quarterTurn = Math.floorMod(rotationDegrees, HALF_TURN) == QUARTER_TURN
        // The crop as the Learner sees it, upright.
        val uprightWidth = if (quarterTurn) cropHeight else cropWidth
        val uprightHeight = if (quarterTurn) cropWidth else cropHeight
        val (width, height) = if (viewWidth > 0 && viewHeight > 0) viewWidth to viewHeight else uprightWidth to uprightHeight
        // FILL_CENTER: view pixels per frame pixel.
        val scale = max(width / uprightWidth, height / uprightHeight)
        val drawn = inView(width, height)
        // A centered square stays a centered square under rotation; only its offset from the view center (none for the
        // drawn grid, kept general) is rotated back into frame axes.
        val upX = (drawn.left + drawn.side / 2 - width / 2) / scale
        val upY = (drawn.top + drawn.side / 2 - height / 2) / scale
        val (frameX, frameY) =
            when (Math.floorMod(rotationDegrees, FULL_TURN)) {
                QUARTER_TURN -> upY to -upX
                HALF_TURN -> -upX to -upY
                THREE_QUARTER_TURN -> -upY to upX
                else -> upX to upY
            }
        val side = drawn.side / scale
        val centerX = cropLeft + cropWidth / 2 + frameX
        val centerY = cropTop + cropHeight / 2 + frameY
        return GridSquare(centerX - side / 2, centerY - side / 2, side)
    }

    /** Inverse of [inFrame]: maps a frame-pixel point ([x], [y]) to normalized [0..1] view coordinates under FILL_CENTER. */
    fun frameToViewFraction(
        x: Double,
        y: Double,
        viewWidth: Double,
        viewHeight: Double,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
        rotationDegrees: Int,
    ): Pair<Double, Double> {
        val cropWidth = (cropRight - cropLeft).toDouble()
        val cropHeight = (cropBottom - cropTop).toDouble()
        val quarterTurn = Math.floorMod(rotationDegrees, HALF_TURN) == QUARTER_TURN
        val uprightWidth = if (quarterTurn) cropHeight else cropWidth
        val uprightHeight = if (quarterTurn) cropWidth else cropHeight
        val (width, height) = if (viewWidth > 0 && viewHeight > 0) viewWidth to viewHeight else uprightWidth to uprightHeight
        val scale = max(width / uprightWidth, height / uprightHeight)
        val fx = x - (cropLeft + cropWidth / 2)
        val fy = y - (cropTop + cropHeight / 2)
        val (upX, upY) =
            when (Math.floorMod(rotationDegrees, FULL_TURN)) {
                QUARTER_TURN -> -fy to fx
                HALF_TURN -> -fx to -fy
                THREE_QUARTER_TURN -> fy to -fx
                else -> fx to fy
            }
        val vx = width / 2 + upX * scale
        val vy = height / 2 + upY * scale
        return (vx / width) to (vy / height)
    }

    private const val QUARTER_TURN = 90
    private const val HALF_TURN = 180
    private const val THREE_QUARTER_TURN = 270
    private const val FULL_TURN = 360
}
