package dev.cubentia.domain.recognition.evaluation.scan

import dev.cubentia.domain.recognition.CellReadResult
import dev.cubentia.domain.recognition.CellReader
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.evaluation.oq7.FrameGrid
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.pow

/**
 * The all-Kotlin decision K reader on a recorded frame: JDK ImageIO JPEG decode, sRGB → CIELAB (D65, the recorder's
 * formula) per pixel, the recorder's grid ([FrameGrid]: central 60% ROI of each cell) and [CellReader] per cell.
 * No OpenCV. Cells come back in the frame's pixel order, row-major.
 */
object FrameReader {
    fun read(file: File): List<CellReading> {
        val image = requireNotNull(ImageIO.read(file)) { "Cannot decode frame ${file.path}" }
        return FrameGrid.cells(image.width, image.height).map { roi ->
            val pixels = ArrayList<Lab>(roi.width * roi.height)
            for (y in roi.y until roi.y + roi.height) {
                for (x in roi.x until roi.x + roi.width) pixels += lab(image.getRGB(x, y))
            }
            when (val result = CellReader.read(pixels)) {
                is CellReadResult.Read -> result.reading
                is CellReadResult.Failed -> error("Cannot read a cell of ${file.path}: ${result.failure}")
            }
        }
    }

    private val LINEAR =
        DoubleArray(256) { channel ->
            val value = channel / 255.0
            if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }

    /** sRGB (packed ARGB) → CIELAB, D65; the same formula as the fixture recorder's `rgbToLab`. */
    fun lab(argb: Int): Lab {
        val r = LINEAR[argb shr 16 and 0xff]
        val g = LINEAR[argb shr 8 and 0xff]
        val b = LINEAR[argb and 0xff]
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883

        fun f(value: Double) = if (value > 0.0088564517) value.pow(1.0 / 3.0) else 7.787037 * value + 16.0 / 116.0
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return Lab((116.0 * fy - 16.0).coerceIn(0.0, 100.0), 500.0 * (fx - fy), 200.0 * (fy - fz))
    }
}
