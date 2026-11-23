package dev.cubentia.app.camera.recorder

import android.app.Application
import dev.cubentia.app.camera.CornerFrame
import dev.cubentia.app.camera.CornerPoint
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CellReading
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.SideLight
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Story 10.20: one adb burst line of the debug recorder (`frames.jsonl`), with its light check. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RecorderBurstTest {
    private fun face(lightness: Double) =
        FrameStats(
            List(9) { CellReading(Lab(lightness, 0.0, 0.0), true) },
            meanLuma = 120.0,
            glareFraction = 0.0,
            sharpness = 300.0,
            seams = 12,
        )

    private val quad = listOf(CornerPoint(1.0, 2.0), CornerPoint(3.0, 2.0), CornerPoint(3.0, 4.0), CornerPoint(1.0, 4.0))
    private val corner = CornerFrame(true, listOf(face(80.0), face(40.0), face(75.0)), score = 0.9, frameQuads = listOf(quad))
    private val exposure = ExposureState(aeState = 2, awbState = 2, exposureNs = 10_000_000L, iso = 200, gains = listOf(2f, 1f, 1f, 1.5f))

    private fun line(
        corner: CornerFrame?,
        light: SideLight.Verdict?,
    ) = JSONObject(RecorderBurst.line(3, 125L, corner, 640, 480, 90, exposure, "frame-003.jpg", light))

    @Test
    fun `a line keeps the frame, corner and exposure fields`() {
        val json = line(corner, null)
        assertEquals(3, json.getInt("i"))
        assertEquals(125L, json.getLong("tMs"))
        assertEquals(640, json.getInt("frameW"))
        assertEquals(480, json.getInt("frameH"))
        assertEquals(90, json.getInt("rotation"))
        assertTrue(json.getBoolean("detected"))
        assertEquals(0.9, json.getDouble("score"), 1e-9)
        assertEquals(
            3.0,
            json
                .getJSONArray("quads")
                .getJSONArray(0)
                .getJSONArray(1)
                .getDouble(0),
            1e-9,
        )
        val faces = json.getJSONArray("faces")
        assertEquals(3, faces.length())
        assertEquals(
            40.0,
            faces
                .getJSONObject(1)
                .getJSONArray("cells")
                .getJSONArray(0)
                .getDouble(0),
            1e-9,
        )
        assertEquals(200, json.getJSONObject("exposure").getInt("iso"))
        assertEquals("frame-003.jpg", json.getString("jpeg"))
    }

    @Test
    fun `a detected corner carries its light check, uncalibrated or calibrated`() {
        val uncalibrated = RecorderBurst.lightCheck(corner, null)!!
        val light = line(corner, uncalibrated).getJSONObject("light")
        assertEquals(80.0, light.getDouble("top"), 1e-9)
        assertEquals(40.0, light.getDouble("left"), 1e-9)
        assertEquals(75.0, light.getDouble("right"), 1e-9)
        assertEquals(40.0, light.getDouble("score"), 1e-9)
        assertEquals("uncalibrated", light.getString("mode"))
        assertFalse(light.getBoolean("passed"))

        val centers =
            CalibrationCenters(
                Color.entries.associateWith { color ->
                    if (color ==
                        Color.WHITE
                    ) {
                        Lab(80.0, 0.0, 0.0)
                    } else {
                        Lab(50.0, 60.0 + color.ordinal * 15.0, -40.0 * color.ordinal)
                    }
                },
            )
        val calibrated = line(corner, RecorderBurst.lightCheck(corner, centers)).getJSONObject("light")
        assertEquals("calibrated", calibrated.getString("mode"))
        assertEquals(1.0, calibrated.getDouble("top"), 1e-9)
        assertEquals(1.0 / ((40.0 / 80.0 + 75.0 / 80.0) / 2), calibrated.getDouble("score"), 1e-9)
        assertFalse(calibrated.getBoolean("passed"))
    }

    @Test
    fun `no detected corner writes a null light check, and an unmeasured face nulls its level and the score`() {
        assertNull(RecorderBurst.lightCheck(null, null))
        assertNull(RecorderBurst.lightCheck(CornerFrame(false, List(3) { null }), null))
        val none = line(null, null)
        assertTrue(none.isNull("light"))
        assertFalse(none.getBoolean("detected"))
        assertEquals(0, none.getJSONArray("faces").length())

        val partial = CornerFrame(true, listOf(face(80.0), null, face(75.0)))
        val light = line(partial, RecorderBurst.lightCheck(partial, null)).getJSONObject("light")
        assertTrue(light.isNull("left"))
        assertTrue(light.isNull("score"))
        assertTrue(light.isNull("passed"))
        assertEquals(80.0, light.getDouble("top"), 1e-9)
        assertTrue(line(partial, null).getJSONArray("faces").isNull(1))
    }

    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `add passes the recorder calibration into the written light check`() {
        val centers =
            CalibrationCenters(
                Color.entries.associateWith { color ->
                    if (color == Color.WHITE) Lab(80.0, 0.0, 0.0) else Lab(50.0, 60.0 + color.ordinal * 15.0, -40.0 * color.ordinal)
                },
            )
        listOf(centers to "calibrated", null to "uncalibrated").forEach { (calibration, mode) ->
            val directory = folder.newFolder()
            val burst = RecorderBurst(directory, frames = 1, jpegEvery = 0, startedMs = 0L)
            assertTrue(burst.add(10L, corner, 640, 480, 90, exposure, null, calibration))
            val written =
                JSONObject(
                    java.io
                        .File(directory, "frames.jsonl")
                        .readLines()
                        .single(),
                )
            assertEquals(mode, written.getJSONObject("light").getString("mode"))
        }
    }
}
