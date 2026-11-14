package dev.cubentia.app.camera.recorder

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.DeviceInfo
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.LabeledFaceSample
import dev.cubentia.domain.recognition.LiveFixtureJson
import dev.cubentia.domain.recognition.LiveFixturePaths
import dev.cubentia.domain.recognition.LiveFixtureRecord
import java.io.File
import java.time.Instant

/** One recorder capture as a self-contained record; [truth] is the operator's entry, never the prediction. */
internal fun fixtureRecord(
    device: DeviceInfo,
    cube: String,
    light: String,
    capturedAt: Instant,
    calibration: CalibrationCenters,
    sample: FaceSample,
    truth: List<Color>,
): LiveFixtureRecord =
    LiveFixtureRecord(
        device = device,
        cube = cube.trim(),
        light = light.trim(),
        capturedAt = capturedAt.toString(),
        calibration = calibration,
        labeledSample = LabeledFaceSample(sample, truth),
    )

/** Writes [record] to `<root>/Fixtures/live/<device>_<cube>_<light>/<FACE>.json`; a recapture replaces the face. */
internal fun writeFixture(
    root: File,
    record: LiveFixtureRecord,
): File {
    val directory = File(root, "Fixtures/live/${LiveFixturePaths.directoryName(record.device.model, record.cube, record.light)}")
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create $directory" }
    val file = File(directory, LiveFixturePaths.fileName(record.labeledSample.sample.face))
    file.writeText(LiveFixtureJson.encode(record))
    return file
}
