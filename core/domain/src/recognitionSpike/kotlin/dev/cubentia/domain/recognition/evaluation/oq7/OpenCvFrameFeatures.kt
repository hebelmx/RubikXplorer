package dev.cubentia.domain.recognition.evaluation.oq7

import dev.cubentia.domain.recognition.Lab
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/** The `O-*` pipeline: JPEG frame → float32 CIELAB → per-cell masked 3-D histogram mode (see [FrameGrid], [LabHistogram]). */
object OpenCvFrameFeatures {
    /** Cells measured, and cells whose `calcHist` peak differs from the pure-Kotlin [LabHistogram.peak] (port check). */
    var cells = 0
        private set
    var peakMismatches = 0
        private set

    fun extract(file: File): List<CellFeature> {
        val bgr = Imgcodecs.imread(file.path, Imgcodecs.IMREAD_COLOR)
        require(!bgr.empty()) { "Cannot read frame ${file.path}" }
        val scaled = Mat()
        bgr.convertTo(scaled, CvType.CV_32FC3, 1.0 / 255.0)
        val lab = Mat()
        Imgproc.cvtColor(scaled, lab, Imgproc.COLOR_BGR2Lab)
        return FrameGrid.cells(lab.cols(), lab.rows()).map { roi -> cell(lab.submat(Rect(roi.x, roi.y, roi.width, roi.height)).clone()) }
    }

    private fun cell(roi: Mat): CellFeature {
        val data = FloatArray(roi.rows() * roi.cols() * 3)
        roi.get(0, 0, data)
        val pixels =
            List(roi.rows() * roi.cols()) { Lab(data[it * 3].toDouble(), data[it * 3 + 1].toDouble(), data[it * 3 + 2].toDouble()) }
        val mask = Mat(roi.rows(), roi.cols(), CvType.CV_8UC1)
        mask.put(0, 0, ByteArray(pixels.size) { if (LabHistogram.unmasked(pixels[it])) 0xff.toByte() else 0 })
        val histogram = Mat()
        val l = LabHistogram.L_MAX.toFloat()
        val ab = LabHistogram.AB_RANGE.toFloat()
        Imgproc.calcHist(
            listOf(roi),
            MatOfInt(0, 1, 2),
            mask,
            histogram,
            MatOfInt(LabHistogram.L_BINS, LabHistogram.AB_BINS, LabHistogram.AB_BINS),
            MatOfFloat(0f, l, -ab, ab, -ab, ab),
        )
        var peak: HistogramBin? = null
        var best = 0.0
        for (i in 0 until LabHistogram.L_BINS) {
            for (j in 0 until LabHistogram.AB_BINS) {
                for (k in 0 until LabHistogram.AB_BINS) {
                    val count = histogram.get(intArrayOf(i, j, k))[0]
                    if (count > best) {
                        best = count
                        peak = HistogramBin(i, j, k)
                    }
                }
            }
        }
        cells++
        if (peak != LabHistogram.peak(pixels)) peakMismatches++
        return LabHistogram.feature(pixels, peak)
    }
}
