package dev.cubentia.app.camera

import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.recognition.CornerFixtureJson
import dev.cubentia.domain.recognition.CornerFixturePaths
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.FrameQuality
import dev.cubentia.domain.recognition.ScanAssembler
import dev.cubentia.domain.recognition.ScanResult
import dev.cubentia.domain.recognition.SelfCalibration
import dev.cubentia.domain.recognition.StickerConfidence
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

class CornerFixtureHarnessTest {
    @Test
    fun `measure the recorded device corners`() {
        val root = File("../Fixtures/corner")
        assumeTrue(root.isDirectory, "Corner fixtures are collected at device session C")
        var correct = 0
        var wrong = 0
        var uncertain = 0
        var scans = 0
        var valid = 0
        root.listFiles(File::isDirectory).orEmpty().sorted().forEach { group ->
            val files = (1..2).map { hold -> File(group, CornerFixturePaths.fileName(hold)) }.filter(File::isFile)
            val samples = mutableListOf<FaceSample>()
            val holds = mutableSetOf<Int>()
            files.forEach { file ->
                val record = CornerFixtureJson.decode(file.readText())
                holds.add(record.hold)
                val frame = ImageIO.read(File(group, record.frame)).toCornerFrame()
                val corner = CornerReader.read(frame, CornerGuide(grid(frame.width, frame.height, 0)))
                record.labeledSamples.forEachIndexed { face, labeled ->
                    val stats = corner.faces[face].takeIf { corner.detected }
                    labeled.truth.forEachIndexed { index, truth ->
                        val reading = stats?.cells?.getOrNull(index)?.let { classify(it.value, record.calibration) }
                        when {
                            reading == null || reading.confidence == StickerConfidence.UNCERTAIN -> uncertain++
                            reading.color == truth -> correct++
                            else -> wrong++
                        }
                    }
                    if (stats != null && stats.cells.size == 9 && stats.cells.all { it != null }) {
                        samples.add(
                            labeled.sample.copy(
                                stickers = stats.cells.map { it!!.value },
                                quality = FrameQuality.assessShutter(stats),
                                centerPlain = stats.cells[4]!!.plain,
                            ),
                        )
                    }
                }
            }
            if (holds.size == 2) {
                scans++
                val calibration = SelfCalibration.from(samples) as? SelfCalibration.Calibrated
                val full = calibration?.let { ScanAssembler.assemble(samples, it.calibration) as? ScanResult.Full }
                if (full != null && CubeValidator.validate(full.facelets) is ValidationResult.Valid) valid++
            }
        }
        println("Quick Scan corner fixtures: correct=$correct confidentlyWrong=$wrong uncertain=$uncertain validScans=$valid/$scans")
    }
}
