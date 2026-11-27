package dev.cubentia.app.camera.session

import android.app.Application
import android.graphics.ImageFormat
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import dev.cubentia.app.camera.CameraXFrameSource
import dev.cubentia.app.camera.ContinuousModelIdentity
import dev.cubentia.app.camera.ContinuousSessionSetup
import dev.cubentia.app.camera.ContinuousSessionTags
import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.app.camera.StickerCenterDetector
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.app.camera.continuous.ContinuousScanEngineModule
import dev.cubentia.app.camera.continuous.StickerCenterModelTransform
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.ContinuousScanHandoff
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionJson
import dev.cubentia.domain.recognition.continuous.session.SessionGeometry
import dev.cubentia.domain.recognition.continuous.session.SessionPartition
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Story 10.34: a recorded session's geometry is the one Quick Scan processed. Frames of Quick Scan's two analysis sizes
 * (`analysisTarget`: 640x480 and 1280x960) go through the real [CameraXFrameSource.read] into the real
 * [ContinuousScanEngineModule.factory] engine with a recording tap; the detector stub computes its params with
 * [StickerCenterModelTransform.uprightDimensions] and [StickerCenterModelTransform.letterbox] as the LiteRT detector does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class QuickScanRecGeometryTest {
    @get:Rule val temp = TemporaryFolder()

    /** What the detector received and used for each frame. */
    private data class Used(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val rotation: Int,
        val uprightWidth: Int,
        val uprightHeight: Int,
        val scale: Double,
        val padLeft: Int,
        val padTop: Int,
    )

    private val used = mutableListOf<Used>()

    private val detector =
        object : StickerCenterDetector {
            override val unavailable = false

            override fun detect(
                frame: YuvFrame,
                rotationDegrees: Int,
                viewWidth: Int,
                viewHeight: Int,
            ): StickerCenterDetection {
                val (w, h) = StickerCenterModelTransform.uprightDimensions(frame.width, frame.height, rotationDegrees)
                val params = checkNotNull(StickerCenterModelTransform.letterbox(w, h))
                used +=
                    Used(
                        frame.left,
                        frame.top,
                        frame.right,
                        frame.bottom,
                        rotationDegrees,
                        w,
                        h,
                        params.scale,
                        params.padLeft,
                        params.padTop,
                    )
                return StickerCenterDetection(emptyList(), w, h, 1.0, params.scale, params.padLeft, params.padTop)
            }

            override fun copyModelInput(into: ByteArray): Boolean = true
        }

    /** A blank YUV_420_888 analysis image of [width]x[height] with [crop] and [rotation], as CameraX hands one over. */
    private class Image(
        private val width: Int,
        private val height: Int,
        private var crop: Rect,
        private val rotation: Int,
        private val timestampNs: Long,
    ) : ImageProxy {
        private val info =
            object : ImageInfo {
                override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()

                override fun getTimestamp() = timestampNs

                override fun getRotationDegrees() = rotation

                override fun populateExifData(exifBuilder: ExifData.Builder) = Unit
            }

        private fun plane(
            stride: Int,
            rows: Int,
        ) = object : ImageProxy.PlaneProxy {
            private val bytes = ByteBuffer.wrap(ByteArray(stride * rows) { 16 })

            override fun getRowStride() = stride

            override fun getPixelStride() = 1

            override fun getBuffer(): ByteBuffer = bytes
        }

        private val data = arrayOf(plane(width, height), plane(width / 2, height / 2), plane(width / 2, height / 2))

        override fun getCropRect() = Rect(crop)

        override fun setCropRect(rect: Rect?) {
            crop = rect ?: Rect(0, 0, width, height)
        }

        override fun getFormat() = ImageFormat.YUV_420_888

        override fun getHeight() = height

        override fun getWidth() = width

        override fun getPlanes() = data

        override fun getImageInfo() = info

        override fun getImage() = null

        override fun close() = Unit
    }

    private fun Used.geometry(
        width: Int,
        height: Int,
    ) = SessionGeometry(rotation, width, height, left, top, right, bottom, uprightWidth, uprightHeight, 256, scale, padLeft, padTop)

    @Test
    fun recordedGeometryIsTheCropTheEngineReceivedAndTheParamsTheDetectorUsed() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val executor = Executors.newSingleThreadExecutor()
        val source = CameraXFrameSource()
        val engine = ContinuousScanEngineModule.factory(detector).create()
        val model = ContinuousModelIdentity("m", "s", 256, "XNNPACK", 2)
        val writer =
            ContinuousSessionWriter(temp.root, ContinuousSessionSetup("geometry", "TB128XU", "LENOVO", model), "c".repeat(40), "2.3.2")
        try {
            source.start(activity.get(), executor, {}, {})
            executor.submit { engine.record(writer) }.get()
            source.continuousMode(engine) { true }
            // 640x480, full-frame crop, rotation 90 (a phone in portrait), then 1280x960 with a 16:9 viewport crop.
            val small = List(3) { Image(640, 480, Rect(0, 0, 640, 480), 90, 1_000_000_000L + it * 33_000_000L) }
            val large = List(3) { Image(1280, 960, Rect(0, 120, 1280, 840), 90, 2_000_000_000L + it * 33_000_000L) }
            (small + large).forEach { image -> executor.submit { source.read(image) }.get() }
            val saved =
                writer.save(
                    ContinuousScanExport.Partial(ContinuousScanHandoff(emptyMap())),
                    ContinuousSessionTags("c", "l", SessionPartition.UNASSIGNED),
                    null,
                )
            val manifest = ContinuousSessionJson.readManifest(File(saved.path))

            assertEquals(6, used.size)
            assertEquals(used.first().geometry(640, 480), manifest.geometry)
            assertEquals(listOf(3), manifest.geometryChanges.map { it.frameIndex })
            assertEquals(used.last().geometry(1280, 960), manifest.geometryChanges.single().geometry)
            // Quick Scan's 640x480 portrait frame: the model sees the whole 480x640 upright crop, letterboxed at 0.4.
            val first = checkNotNull(manifest.geometry)
            assertEquals(480 to 640, first.uprightWidth to first.uprightHeight)
            assertEquals(0.4, first.letterboxScale, 1e-12)
            assertEquals(32 to 0, first.padLeft to first.padTop)
            assertEquals(Rect(0, 0, 640, 480), Rect(first.cropLeft, first.cropTop, first.cropRight, first.cropBottom))
            val second = manifest.geometryChanges.single().geometry
            assertEquals(720 to 1280, second.uprightWidth to second.uprightHeight)
            assertEquals(0.2, second.letterboxScale, 1e-12)
            assertEquals(56 to 0, second.padLeft to second.padTop)
            // Every frame was written with the sensor timestamp; the PNG of every third frame is the model input.
            val frames = ContinuousSessionJson.readFrames(File(saved.path))
            assertEquals(listOf(1000L, 1033L, 1066L, 2000L, 2033L, 2066L), frames.map { it.timestampMs })
            assertEquals(listOf(0, 3), frames.filter { it.image != null }.map { it.frameIndex })
        } finally {
            source.stop()
            executor.shutdownNow()
            activity.pause().stop().destroy()
        }
    }
}
