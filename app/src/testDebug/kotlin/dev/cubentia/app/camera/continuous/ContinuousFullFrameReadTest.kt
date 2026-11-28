package dev.cubentia.app.camera.continuous

import android.app.Application
import android.graphics.ImageFormat
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import dev.cubentia.app.camera.CameraXFrameSource
import dev.cubentia.app.camera.ContinuousFrame
import dev.cubentia.app.camera.ContinuousQuickScanFlow
import dev.cubentia.app.camera.FrameReader
import dev.cubentia.app.camera.QuickScanDebugView
import dev.cubentia.app.camera.ScanFrame
import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.app.camera.StickerCenterDetector
import dev.cubentia.app.camera.StickerCenterPeak
import dev.cubentia.app.camera.YuvFrame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * GH #415: in continuous mode a face painted outside the viewport crop is still read. A fake 640x480 image whose crop is
 * a narrow band on the left goes through the real [CameraXFrameSource.read] into the real [ContinuousScanEngineModule]
 * engine, whose stub detector finds bright blobs only within the frame bounds it is handed; the detector gets the whole
 * image, and peaks, a fitted face and a tracked quad come back, the quad's view corners outside the visible preview.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ContinuousFullFrameReadTest {
    @After fun debugViewOff() = QuickScanDebugView.set(false)

    /** The frames the detector was handed: read bounds, then view region. */
    private val handed = mutableListOf<List<Int>>()

    /** Bright 4-connected blobs (Y above [BRIGHT]) inside the frame's bounds only, as upright peaks (rotation 0). */
    private val detector =
        object : StickerCenterDetector {
            override val unavailable = false

            override fun detect(
                frame: YuvFrame,
                rotationDegrees: Int,
                viewWidth: Int,
                viewHeight: Int,
            ): StickerCenterDetection {
                check(rotationDegrees == 0)
                handed +=
                    listOf(
                        frame.left,
                        frame.top,
                        frame.right,
                        frame.bottom,
                        frame.viewLeft,
                        frame.viewTop,
                        frame.viewRight,
                        frame.viewBottom,
                    )
                val seen = BooleanArray(W * H)
                val peaks = mutableListOf<StickerCenterPeak>()
                for (y in frame.top until frame.bottom) {
                    for (x in frame.left until frame.right) {
                        if (seen[y * W + x] || frame.y.at(x, y) <= BRIGHT) continue
                        var sumX = 0.0
                        var sumY = 0.0
                        var n = 0
                        val stack = ArrayDeque(listOf(x to y))
                        seen[y * W + x] = true
                        while (stack.isNotEmpty()) {
                            val (px, py) = stack.removeLast()
                            sumX += px + 0.5
                            sumY += py + 0.5
                            n++
                            for ((nx, ny) in listOf(px + 1 to py, px - 1 to py, px to py + 1, px to py - 1)) {
                                if (nx !in frame.left until frame.right || ny !in frame.top until frame.bottom) continue
                                if (seen[ny * W + nx] || frame.y.at(nx, ny) <= BRIGHT) continue
                                seen[ny * W + nx] = true
                                stack.addLast(nx to ny)
                            }
                        }
                        val ux = sumX / n - frame.left
                        val uy = sumY / n - frame.top
                        val (vx, vy) =
                            StickerCenterModelTransform.uprightToViewFraction(
                                ux,
                                uy,
                                viewWidth,
                                viewHeight,
                                frame.left,
                                frame.top,
                                frame.right,
                                frame.bottom,
                                rotationDegrees,
                                viewLeft = frame.viewLeft,
                                viewTop = frame.viewTop,
                                viewRight = frame.viewRight,
                                viewBottom = frame.viewBottom,
                            )
                        peaks += StickerCenterPeak(ux, uy, 0.9f, vx, vy)
                    }
                }
                return StickerCenterDetection(peaks, frame.width, frame.height)
            }
        }

    @Test
    fun aFacePaintedOutsideTheCropIsReadFromTheFullFrame() {
        QuickScanDebugView.set(true)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val executor = Executors.newSingleThreadExecutor()
        val source = CameraXFrameSource()
        val delivered = mutableListOf<ContinuousFrame>()
        val engine = ContinuousScanEngineModule.factory(detector).create()
        try {
            source.start(activity.get(), executor, { frame: ScanFrame -> frame.continuous?.let(delivered::add) }, {})
            source.continuousMode(engine) { true }
            repeat(FRAMES) { executor.submit { source.read(FaceImage()) }.get() }

            // The detector read the whole image; the narrow crop is only the view region.
            assertEquals(List(FRAMES) { listOf(0, 0, W, H, CROP.left, CROP.top, CROP.right, CROP.bottom) }, handed)
            val debug = checkNotNull(delivered.last().debug)
            assertEquals(9, debug.peaks.size)
            assertTrue("peaks right of the visible preview: " + debug.peaks, debug.peaks.all { it.viewX > 1.0 })
            assertEquals(1, debug.lattices.size)
            val quads = delivered.flatMap { it.viewOverlays }
            assertTrue("a tracked quad came back", quads.isNotEmpty())
            assertTrue("its view corners lie outside [0, 1]", quads.all { q -> q.viewCorners.all { it.x > 1.0 } })
            // On screen: wholly off the preview, so no outline.
            val flow = delivered.fold(ContinuousQuickScanFlow()) { f, frame -> f.next(frame, 0L) }
            assertTrue(flow.outlines.isEmpty())

            // The Guided reader's crop holds no sticker: over it the same detector finds nothing.
            val cropOnly = with(FrameReader) { FaceImage().asYuvFrame() }
            assertEquals(0, detector.detect(cropOnly, 0, 0, 0).peaks.size)
        } finally {
            source.stop()
            executor.shutdownNow()
            activity.pause().stop().destroy()
        }
    }

    /** A 640x480 YUV_420_888 image (rotation 0) with a 3x3 face of distinct colours right of its narrow [CROP]. */
    private class FaceImage : ImageProxy {
        private var crop = Rect(CROP)
        private val info =
            object : ImageInfo {
                override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()

                override fun getTimestamp() = 0L

                override fun getRotationDegrees() = 0

                override fun populateExifData(exifBuilder: ExifData.Builder) = Unit
            }

        private fun plane(
            bytes: ByteArray,
            stride: Int,
        ) = object : ImageProxy.PlaneProxy {
            private val buffer = ByteBuffer.wrap(bytes)

            override fun getRowStride() = stride

            override fun getPixelStride() = 1

            override fun getBuffer(): ByteBuffer = buffer
        }

        private val data = arrayOf(plane(PLANES.y, W), plane(PLANES.u, W / 2), plane(PLANES.v, W / 2))

        override fun getCropRect() = Rect(crop)

        override fun setCropRect(rect: Rect?) {
            crop = rect ?: Rect(0, 0, W, H)
        }

        override fun getFormat() = ImageFormat.YUV_420_888

        override fun getHeight() = H

        override fun getWidth() = W

        override fun getPlanes() = data

        override fun getImageInfo() = info

        override fun getImage() = null

        override fun close() = Unit
    }

    private class Planes(
        val y: ByteArray,
        val u: ByteArray,
        val v: ByteArray,
    )

    private companion object {
        const val W = 640
        const val H = 480
        const val FRAMES = 6
        const val BRIGHT = 60
        const val CELL = 60
        const val FACE_LEFT = 340
        const val FACE_TOP = 150

        /** The viewport crop: a narrow band on the left that the face lies wholly outside of. */
        val CROP = Rect(0, 0, 200, H)

        val PLANES: Planes by lazy {
            val colors = List(9) { idx -> Triple(200 - idx * 12, 80 + idx * 12, 120) }

            fun rgbAt(
                x: Int,
                y: Int,
            ): Triple<Int, Int, Int> {
                val dx = x - FACE_LEFT
                val dy = y - FACE_TOP
                if (dx < 0 || dy < 0 || dx >= CELL * 3 || dy >= CELL * 3) return Triple(20, 20, 20)
                val (ix, iy) = dx % CELL to dy % CELL
                if (ix < 3 || iy < 3 || ix >= CELL - 3 || iy >= CELL - 3) return Triple(20, 20, 20)
                return colors[dy / CELL * 3 + dx / CELL]
            }
            val y = ByteArray(W * H)
            val u = ByteArray(W / 2 * (H / 2))
            val v = ByteArray(W / 2 * (H / 2))
            for (py in 0 until H) {
                for (px in 0 until W) {
                    val (r, g, b) = rgbAt(px, py)
                    y[py * W + px] = (0.299 * r + 0.587 * g + 0.114 * b).roundToInt().coerceIn(0, 255).toByte()
                    if (py % 2 == 0 && px % 2 == 0) {
                        val uv = py / 2 * (W / 2) + px / 2
                        u[uv] = (128 - 0.168736 * r - 0.331264 * g + 0.5 * b).roundToInt().coerceIn(0, 255).toByte()
                        v[uv] = (128 + 0.5 * r - 0.418688 * g - 0.081312 * b).roundToInt().coerceIn(0, 255).toByte()
                    }
                }
            }
            Planes(y, u, v)
        }
    }
}
