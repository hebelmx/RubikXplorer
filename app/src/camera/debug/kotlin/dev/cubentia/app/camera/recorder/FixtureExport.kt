package dev.cubentia.app.camera.recorder

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CornerFixtureJson
import dev.cubentia.domain.recognition.CornerFixturePaths
import dev.cubentia.domain.recognition.CornerFixtureRecord
import dev.cubentia.domain.recognition.CornerGroupManifest
import dev.cubentia.domain.recognition.DeviceInfo
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.LabeledFaceSample
import dev.cubentia.domain.recognition.LiveFixtureJson
import dev.cubentia.domain.recognition.LiveFixturePaths
import dev.cubentia.domain.recognition.LiveFixtureRecord
import dev.cubentia.domain.recognition.TruthSource
import java.io.File
import java.time.Instant

/**
 * One recorder capture as a self-contained schema-2 record. [truth] is the operator's confirmed face; [sources] says per
 * sticker whether the operator entered it or left a close-call-gated prefill. [hasFrame] names the sibling `<FACE>.jpg`.
 */
internal fun fixtureRecord(
    device: DeviceInfo,
    cube: String,
    light: String,
    capturedAt: Instant,
    calibration: CalibrationCenters,
    sample: FaceSample,
    truth: List<Color>,
    sources: List<TruthSource>,
    hasFrame: Boolean,
): LiveFixtureRecord {
    require(truth.size == 9 && sources.size == 9) { "A capture needs nine truth colors and nine sources" }
    return LiveFixtureRecord(
        device = device,
        cube = cube.trim(),
        light = light.trim(),
        capturedAt = capturedAt.toString(),
        calibration = calibration,
        labeledSample = LabeledFaceSample(sample, truth),
        truthSources = sources,
        frame = if (hasFrame) LiveFixturePaths.frameName(sample.face) else null,
    )
}

/**
 * Writes [record] to `<root>/Fixtures/live/<device>_<cube>_<light>/<FACE>.json` and [frameJpeg], when the record names a
 * frame, to `<FACE>.jpg` beside it. A recapture replaces both; a recapture without a frame removes the stale image.
 */
internal fun writeFixture(
    root: File,
    record: LiveFixtureRecord,
    frameJpeg: ByteArray? = null,
): File {
    val face = record.labeledSample.sample.face
    require((record.frame == null) == (frameJpeg == null)) { "The record's frame name and the frame bytes must come together" }
    require(record.frame == null || record.frame == LiveFixturePaths.frameName(face)) {
        "Frame must be named ${LiveFixturePaths.frameName(face)}"
    }
    val directory = File(root, "Fixtures/live/${LiveFixturePaths.directoryName(record.device.model, record.cube, record.light)}")
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create $directory" }
    val image = File(directory, LiveFixturePaths.frameName(face))
    if (frameJpeg != null) image.writeBytes(frameJpeg) else image.delete()
    val file = File(directory, LiveFixturePaths.fileName(face))
    file.writeText(LiveFixtureJson.encode(record))
    return file
}

/** Story 10.20: writes [manifest] as the group folder's `group.json` unless the group already has one; returns the file. */
internal fun ensureGroupManifest(
    directory: File,
    manifest: CornerGroupManifest,
): File {
    val file = File(directory, CornerFixturePaths.MANIFEST)
    if (!file.isFile) file.writeText(CornerFixtureJson.encodeManifest(manifest))
    return file
}

/** Writes one corner hold's color record `corner-N.json` (with its diagnostics) and its frame `corner-N.jpg`; returns the record file. */
internal fun writeCornerHold(
    directory: File,
    record: CornerFixtureRecord,
    jpeg: ByteArray,
): File {
    File(directory, record.frame).writeBytes(jpeg)
    return File(directory, CornerFixturePaths.fileName(record.hold)).also { it.writeText(CornerFixtureJson.encode(record)) }
}

/**
 * Story 10.20: rewrites the group's `group.json` with hold [hold] recorded at torch state [torch] ([withHold]); the other
 * holds stay as they were. Throws when the manifest is missing or unreadable, so the caller can say so.
 */
internal fun recordHold(
    directory: File,
    hold: Int,
    torch: Boolean?,
): CornerGroupManifest {
    val file = File(directory, CornerFixturePaths.MANIFEST)
    val updated = withHold(CornerFixtureJson.decodeManifest(file.readText()), hold, torch)
    file.writeText(CornerFixtureJson.encodeManifest(updated))
    return updated
}

/**
 * Story 10.28: rewrites the group's `group.json` with the count [check]'s outcome ([withCountCheck]): passed, overridden
 * with the trimmed [reason], or refused. Throws when the manifest is missing or unreadable, like [recordHold].
 */
internal fun recordCountCheck(
    directory: File,
    check: ColorCountCheck,
    reason: String?,
): CornerGroupManifest {
    val file = File(directory, CornerFixturePaths.MANIFEST)
    val updated = withCountCheck(CornerFixtureJson.decodeManifest(file.readText()), check, reason)
    file.writeText(CornerFixtureJson.encodeManifest(updated))
    return updated
}
