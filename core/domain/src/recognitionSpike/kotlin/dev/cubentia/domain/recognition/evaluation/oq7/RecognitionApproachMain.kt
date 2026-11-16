package dev.cubentia.domain.recognition.evaluation.oq7

import nu.pattern.OpenCV
import java.io.File

fun main(args: Array<String>) {
    require(args.size == 4) { "Usage: RecognitionApproachMain <fixtures root> <document path> <OpenCV AAR> <LiteRT AAR>" }
    OpenCV.loadLocally()
    Oq7Runner.run(File(args[0]), File(args[1]), listOf(File(args[2]), File(args[3])), OpenCvFrameFeatures::extract) {
        listOf(
            "Pure-Kotlin histogram port: `LabHistogram.peak` equals the OpenCV `calcHist` peak bin in " +
                "${OpenCvFrameFeatures.cells - OpenCvFrameFeatures.peakMismatches} of ${OpenCvFrameFeatures.cells} frame cells.",
        )
    }
}
