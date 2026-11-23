package dev.cubentia.app.camera.recorder

import android.util.Log
import dev.cubentia.app.camera.CornerFrame
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.FrameQuality
import dev.cubentia.domain.recognition.SideLight
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The camera's own exposure and white-balance state for one capture result, as far as the device reports it. */
internal data class ExposureState(
    val aeState: Int?,
    val awbState: Int?,
    val exposureNs: Long?,
    val iso: Int?,
    val gains: List<Float>?,
)

/**
 * Debug-only burst (Quick Scan reliability spike, 2026-11-22): the corner reading of [frames] consecutive analysis
 * frames, written as one JSON line per frame to `frames.jsonl` in [directory], with a JPEG of every [jpegEvery]-th
 * frame (0: none). Started over adb while the recorder is open:
 *
 *     adb shell am broadcast -p com.exxerpro.cubentia -a dev.cubentia.debug.RECORDER_BURST --ei frames 30 --ei jpegEvery 10 --ez meter true --es label still
 *
 * Story 10.20: each line also carries the light check (`light`: the three face levels, score, mode and verdict) from
 * [SideLight.check] against the recorder calibration, or uncalibrated before the recorder is calibrated; `null` when no
 * corner was detected.
 *
 * Analyzer thread only. Numbers and frames stay on the device until the operator pulls them.
 */
internal class RecorderBurst(
    val directory: File,
    private val frames: Int,
    private val jpegEvery: Int,
    private val startedMs: Long,
) {
    private val lines = StringBuilder()
    private var count = 0

    /** Whether the frame about to be added should carry a JPEG. */
    fun wantsJpeg(): Boolean = jpegEvery > 0 && count % jpegEvery == 0

    /** Adds one frame; returns true once the burst is complete and written. */
    fun add(
        nowMs: Long,
        corner: CornerFrame?,
        width: Int,
        height: Int,
        rotation: Int,
        exposure: ExposureState?,
        jpeg: ByteArray?,
        calibration: CalibrationCenters? = null,
    ): Boolean {
        val name = jpeg?.let { "frame-%03d.jpg".format(count) }
        if (jpeg != null && name != null) runCatching { File(directory, name).writeBytes(jpeg) }
        val light = lightCheck(corner, calibration)
        lines.append(line(count, nowMs - startedMs, corner, width, height, rotation, exposure, name, light)).append('\n')
        count++
        if (count < frames) return false
        runCatching { File(directory, "frames.jsonl").writeText(lines.toString()) }
            .onFailure { Log.w(TAG, "recorderBurst write failed: $it") }
        Log.d(TAG, "recorderBurst done frames=$count ms=${nowMs - startedMs} dir=${directory.absolutePath}")
        return true
    }

    companion object {
        const val ACTION = "dev.cubentia.debug.RECORDER_BURST"
        const val TAG = "CubentiaScanDiag"
        const val DEFAULT_FRAMES = 30
        const val MAX_FRAMES = 600

        /** The light check of [corner]'s three faces (calibrated against a valid [calibration]); null when no corner was detected. */
        internal fun lightCheck(
            corner: CornerFrame?,
            calibration: CalibrationCenters?,
        ): SideLight.Verdict? = corner?.takeIf { it.detected && it.faces.size == 3 }?.let { SideLight.check(it.faces, calibration) }

        internal fun line(
            index: Int,
            elapsedMs: Long,
            corner: CornerFrame?,
            width: Int,
            height: Int,
            rotation: Int,
            exposure: ExposureState?,
            jpeg: String?,
            light: SideLight.Verdict? = null,
        ): String {
            val json = JSONObject()
            json.put("i", index)
            json.put("tMs", elapsedMs)
            json.put("frameW", width)
            json.put("frameH", height)
            json.put("rotation", rotation)
            json.put("detected", corner?.detected == true)
            json.put("score", corner?.score ?: JSONObject.NULL)
            json.put(
                "quads",
                JSONArray(corner?.frameQuads.orEmpty().map { quad -> JSONArray(quad.map { JSONArray(listOf(it.x, it.y)) }) }),
            )
            json.put(
                "faces",
                JSONArray(
                    corner?.faces.orEmpty().map { face ->
                        face ?: return@map JSONObject.NULL
                        JSONObject()
                            .put("quality", FrameQuality.assessShutter(face).name)
                            .put("meanLuma", face.meanLuma)
                            .put("glare", face.glareFraction)
                            .put("sharpness", face.sharpness)
                            .put(
                                "cells",
                                JSONArray(
                                    face.cells.map { cell ->
                                        cell ?: return@map JSONObject.NULL
                                        JSONArray(listOf(cell.value.l, cell.value.a, cell.value.b, if (cell.plain) 1 else 0))
                                    },
                                ),
                            )
                    },
                ),
            )
            json.put(
                "exposure",
                exposure?.let {
                    JSONObject()
                        .put("ae", it.aeState ?: JSONObject.NULL)
                        .put("awb", it.awbState ?: JSONObject.NULL)
                        .put("exposureNs", it.exposureNs ?: JSONObject.NULL)
                        .put("iso", it.iso ?: JSONObject.NULL)
                        .put("gains", it.gains?.let { gains -> JSONArray(gains.map { gain -> gain.toDouble() }) } ?: JSONObject.NULL)
                } ?: JSONObject.NULL,
            )
            json.put("jpeg", jpeg ?: JSONObject.NULL)
            json.put(
                "light",
                light?.let {
                    JSONObject()
                        .put("top", it.topLevel ?: JSONObject.NULL)
                        .put("left", it.leftLevel ?: JSONObject.NULL)
                        .put("right", it.rightLevel ?: JSONObject.NULL)
                        .put("score", it.score ?: JSONObject.NULL)
                        .put("mode", it.mode.name.lowercase())
                        .put("passed", if (it.score == null) JSONObject.NULL else it.passed)
                } ?: JSONObject.NULL,
            )
            return json.toString()
        }
    }
}
