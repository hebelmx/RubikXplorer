package dev.cubentia.app.camera

import android.app.Application
import android.graphics.ImageFormat
import android.graphics.Rect
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import dev.cubentia.app.ScanDiagnostics
import dev.cubentia.app.camera.FrameReader.asYuvFrame
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.FrameCheck
import dev.cubentia.domain.recognition.FrameQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.nio.ByteBuffer
import java.util.Optional
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CameraXFrameSourceTest {
    @Test fun continuousFramesUseTheAnalyzerAndStopDuringTheCompletionHold() {
        val source = CameraXFrameSource()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val executor = Executors.newSingleThreadExecutor()
        val delivered = mutableListOf<ScanFrame>()
        val calls = mutableListOf<String>()
        val snap = testSnapshot(testHandoff(emptyList()))
        val engine =
            object : ContinuousScanEngine {
                override val detectorUnavailable = false

                override fun processFrame(
                    frame: YuvFrame,
                    rotationDegrees: Int,
                    viewWidth: Int,
                    viewHeight: Int,
                ): ContinuousFrame {
                    assertNotEquals(
                        android.os.Looper
                            .getMainLooper()
                            .thread,
                        Thread.currentThread(),
                    )
                    assertTrue(frame.width > 0)
                    assertEquals(90, rotationDegrees)
                    assertEquals(360 to 480, viewWidth to viewHeight)
                    calls += "process"
                    return ContinuousFrame(snap, emptyList())
                }

                override fun reset() = error("unexpected reset")

                override fun export() = error("unexpected export")

                override fun analyzerStart() {
                    calls += "start"
                }

                override fun frameDone(
                    sensorTimestampNs: Long,
                    analyzerNs: Long,
                    rotation: Int,
                    imageWidth: Int,
                    imageHeight: Int,
                    cropLeft: Int,
                    cropTop: Int,
                    cropRight: Int,
                    cropBottom: Int,
                ) {
                    calls += "done"
                }

                override fun analyzerEnd() {
                    calls += "end"
                }
            }
        try {
            source.start(activity.get(), executor, {
                calls += "deliver"
                delivered.add(it)
            }, {})
            source.preview(activity.get()).layout(0, 0, 360, 480)
            source.continuousMode(engine) { true }
            executor.submit { source.read(dimFace()) }.get()
            source.continuousMode(engine) { false }
            executor.submit { source.read(dimFace()) }.get()
            assertEquals(snap, delivered.single().continuous?.snapshot)
            // GH #406: the timed analyzer interval opens before processing and closes after frameDone and delivery.
            assertEquals(listOf("start", "process", "done", "deliver", "end"), calls)
        } finally {
            source.stop()
            executor.shutdownNow()
            activity.pause().stop().destroy()
        }
    }

    /**
     * GH #415: Guided and corner reads get exactly the viewport crop; only the continuous read gets the whole image, with
     * the crop as its view region, and records the region it read.
     */
    @Test fun onlyTheContinuousReadReadsTheWholeFrame() {
        val crop = Rect(10, 20, 110, 100)

        fun image() = dimFace().also { it.setCropRect(crop) }

        fun YuvFrame.read() = listOf(left, top, right, bottom)

        fun YuvFrame.view() = listOf(viewLeft, viewTop, viewRight, viewBottom)
        val cropBounds = listOf(10, 20, 110, 100)
        val guided = image().asYuvFrame()
        assertEquals(cropBounds, guided.read())
        assertEquals(cropBounds, guided.view())
        val full = with(FrameReader) { image().asFullYuvFrame() }
        assertEquals(listOf(0, 0, 120, 120), full.read())
        assertEquals(cropBounds, full.view())

        val cornerFrames = mutableListOf<YuvFrame>()
        val continuousFrames = mutableListOf<YuvFrame>()
        val recorded = mutableListOf<List<Int>>()
        val snap = testSnapshot(testHandoff(emptyList()))
        val engine =
            object : ContinuousScanEngine {
                override val detectorUnavailable = false

                override fun processFrame(
                    frame: YuvFrame,
                    rotationDegrees: Int,
                    viewWidth: Int,
                    viewHeight: Int,
                ): ContinuousFrame {
                    continuousFrames += frame
                    return ContinuousFrame(snap, emptyList())
                }

                override fun reset() = error("unexpected reset")

                override fun export() = error("unexpected export")

                override fun frameDone(
                    sensorTimestampNs: Long,
                    analyzerNs: Long,
                    rotation: Int,
                    imageWidth: Int,
                    imageHeight: Int,
                    cropLeft: Int,
                    cropTop: Int,
                    cropRight: Int,
                    cropBottom: Int,
                ) {
                    recorded += listOf(cropLeft, cropTop, cropRight, cropBottom)
                }
            }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val executor = Executors.newSingleThreadExecutor()
        val source = CameraXFrameSource(Optional.of(CornerDetector { frame, _ -> null.also { cornerFrames += frame } }))
        val delivered = mutableListOf<ScanFrame>()
        try {
            source.start(activity.get(), executor, { delivered.add(it) }, { error("Camera unexpectedly unavailable") })
            // Guided (and calibration, the same read): the reading is the one of the crop's frame.
            val guidedImage = image()
            val rotation = guidedImage.imageInfo.rotationDegrees
            val grid = ViewfinderGrid.inFrame(0.0, 0.0, crop.left, crop.top, crop.right, crop.bottom, rotation)
            executor.submit { source.read(guidedImage) }.get()
            val expected = FrameReader.locate(guided, grid, rotation)
            assertEquals(expected.stats, delivered.single().stats)
            assertEquals(expected.samplingSquare(grid), source.meteringSquare)
            source.calibrationMode(true)
            executor.submit { source.read(image()) }.get()
            assertEquals(expected.stats, delivered.last().stats)
            source.calibrationMode(false)
            // Corner: the detector gets exactly the crop.
            source.cornerMode(true)
            executor.submit { source.read(image()) }.get()
            assertEquals(cropBounds, cornerFrames.single().read())
            source.cornerMode(false)
            // Continuous: the whole image, the crop kept as the view region, and the read bounds recorded.
            source.continuousMode(engine) { true }
            executor.submit { source.read(image()) }.get()
            assertEquals(listOf(0, 0, 120, 120), continuousFrames.single().read())
            assertEquals(cropBounds, continuousFrames.single().view())
            assertEquals(listOf(listOf(0, 0, 120, 120)), recorded)
        } finally {
            source.stop()
            executor.shutdownNow()
            activity.pause().stop().destroy()
        }
    }

    @Test fun meteringIncludesAfAeAndAwb() {
        val afAe = FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        assertEquals(afAe or FocusMeteringAction.FLAG_AWB, CameraXFrameSource.meteringFlags())
    }

    @Test fun cornerFramesUseTheInjectedDetectorInsteadOfTheHeuristic() {
        val guides = mutableListOf<CornerGuide>()
        val recording = CornerDetector { _, guide -> null.also { guides += guide } }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val executor = Executors.newSingleThreadExecutor()
        val source = CameraXFrameSource(Optional.of(recording))
        val delivered = mutableListOf<ScanFrame>()
        try {
            source.start(activity.get(), executor, { delivered.add(it) }, { error("Camera unexpectedly unavailable") })
            source.cornerMode(true)
            executor.submit { source.read(dimFace()) }.get()

            assertEquals(1, guides.size)
            assertEquals(90, guides.single().rotationDegrees)
            assertEquals(false, delivered.single().corner?.detected)
        } finally {
            source.stop()
            executor.shutdownNow()
            activity.pause().stop().destroy()
        }
    }

    @Test fun deliversLocatedStatsAndLogsTheLatticeForAReadableDimFace() {
        val image = dimFace()
        val crop = image.cropRect
        val rotation = image.imageInfo.rotationDegrees
        val grid = ViewfinderGrid.inFrame(0.0, 0.0, crop.left, crop.top, crop.right, crop.bottom, rotation)
        val expected = FrameReader.locate(image.asYuvFrame(), grid, rotation).stats
        val inspection = FrameQuality.inspect(expected)
        assertEquals(FrameQuality.CELLS, inspection.readableCells)
        assertEquals(FrameCheck.LIGHT, inspection.failed)

        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val executor = Executors.newSingleThreadExecutor()
        val source = CameraXFrameSource()
        val delivered = mutableListOf<ScanFrame>()
        try {
            // Without a preview, start registers the receiver without binding CameraX.
            source.start(activity.get(), executor, { delivered.add(it) }, { error("Camera unexpectedly unavailable") })
            SystemClock.sleep(1_000)
            ShadowLog.clear()
            executor.submit { source.read(image) }.get()

            assertEquals(grid, source.meteringSquare)
            assertEquals(expected, delivered.single().stats)
            assertEquals(rotation, delivered.single().rotationDegrees)
            val logs = ShadowLog.getLogsForTag(ScanDiagnostics.TAG)
            assertTrue(logs.single().msg.contains("lattice=scale"))
        } finally {
            source.stop()
            executor.shutdownNow()
            activity.pause().stop().destroy()
        }
    }

    @Test fun deliversTheLocatedLatticeReadingWhenTheFaceIsOffTheDrawnGrid() {
        val size = 240
        val image = offsetFace(size, offset = (size * FrameReader.GRID_FRACTION / 6).toInt())
        val crop = image.cropRect
        val rotation = image.imageInfo.rotationDegrees
        val grid = ViewfinderGrid.inFrame(0.0, 0.0, crop.left, crop.top, crop.right, crop.bottom, rotation)
        val yuv = image.asYuvFrame()
        val fixed = FrameReader.readAt(yuv, grid)
        val located = FrameReader.locate(yuv, grid, rotation)
        // The drawn grid alone reads it as misaligned; only the lattice search finds the face.
        assertEquals(CaptureQuality.MISALIGNED, FrameQuality.assess(fixed))
        assertEquals(CaptureQuality.ACCEPTABLE, FrameQuality.assess(located.stats))
        assertNotEquals(1.0 to 0.0, located.lattice.scale to located.lattice.dx)

        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val executor = Executors.newSingleThreadExecutor()
        val source = CameraXFrameSource()
        val delivered = mutableListOf<ScanFrame>()
        try {
            source.start(activity.get(), executor, { delivered.add(it) }, { error("Camera unexpectedly unavailable") })
            executor.submit { source.read(image) }.get()
            assertEquals(located.lattice.square(grid), source.meteringSquare)
            assertEquals(located.stats, delivered.single().stats)
            assertNotEquals(fixed, delivered.single().stats)
            // The corner view meters the drawn grid again, not the lattice the last Guided frame located.
            source.cornerMode(true)
            executor.submit { source.read(image) }.get()
            assertEquals(grid, source.meteringSquare)
        } finally {
            source.stop()
            executor.shutdownNow()
            activity.pause().stop().destroy()
        }
    }

    /** A [width]×[height] YUV_420_888 image with planar chroma; [chroma] gives (U, V) at a full-resolution pixel. */
    private class FakeImage(
        private val width: Int,
        private val height: Int,
        luma: (Int, Int) -> Int,
        chroma: (Int, Int) -> Pair<Int, Int>,
    ) : ImageProxy {
        private var crop = Rect(0, 0, width, height)
        private val info =
            object : ImageInfo {
                override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()

                override fun getTimestamp() = 0L

                override fun getRotationDegrees() = 90

                override fun populateExifData(exifBuilder: ExifData.Builder) = Unit
            }

        private val data =
            arrayOf(
                plane(width, height) { x, y -> luma(x, y) },
                plane(width / 2, height / 2) { x, y -> chroma(x * 2, y * 2).first },
                plane(width / 2, height / 2) { x, y -> chroma(x * 2, y * 2).second },
            )

        private fun plane(
            stride: Int,
            rows: Int,
            value: (Int, Int) -> Int,
        ): ImageProxy.PlaneProxy =
            object : ImageProxy.PlaneProxy {
                private val bytes =
                    ByteBuffer.wrap(ByteArray(stride * rows) { value(it % stride, it / stride).coerceIn(0, 255).toByte() })

                override fun getRowStride() = stride

                override fun getPixelStride() = 1

                override fun getBuffer(): ByteBuffer = bytes
            }

        override fun getCropRect() = Rect(crop)

        override fun setCropRect(rect: Rect?) {
            crop = rect?.let(::Rect) ?: Rect(0, 0, width, height)
        }

        override fun getFormat() = ImageFormat.YUV_420_888

        override fun getHeight() = height

        override fun getWidth() = width

        override fun getPlanes() = data

        override fun getImageInfo() = info

        override fun getImage() = null

        override fun close() = Unit
    }

    private companion object {
        /** Nine dim stickers with distinct luma values, separated by dark seams. */
        fun dimFace(): ImageProxy {
            val size = 120
            val cell = 32
            val left = (size - cell * 3) / 2
            return FakeImage(size, size, { x, y ->
                val fx = x - left
                val fy = y - left
                if (fx in 0 until cell * 3 && fy in 0 until cell * 3 && fx % cell in 2..29 && fy % cell in 2..29) {
                    25 + (fy / cell * 3 + fx / cell) * 3
                } else {
                    10
                }
            }, { _, _ -> 90 to 175 })
        }

        /** A bright stickered face (FrameReaderTest's colors) [offset] pixels right of and below the drawn grid. */
        fun offsetFace(
            size: Int,
            offset: Int,
        ): ImageProxy {
            val side = size * FrameReader.GRID_FRACTION
            val cell = side / 3
            val origin = (size - side) / 2 + offset
            val colors =
                listOf(
                    Triple(200, 128, 128),
                    Triple(80, 90, 200),
                    Triple(110, 70, 100),
                    Triple(180, 30, 150),
                    Triple(110, 70, 100),
                    Triple(60, 200, 110),
                    Triple(130, 70, 190),
                    Triple(200, 128, 128),
                    Triple(80, 90, 200),
                )

            fun sticker(
                x: Int,
                y: Int,
            ): Triple<Int, Int, Int>? {
                val fx = x - origin
                val fy = y - origin
                if (fx < 0 || fy < 0 || fx >= side || fy >= side) return null
                val inX = fx % cell
                val inY = fy % cell
                if (inX < 3 || inX >= cell - 3 || inY < 3 || inY >= cell - 3) return null
                return colors[(fy / cell).toInt() * 3 + (fx / cell).toInt()]
            }
            val noise = java.util.Random(7)
            return FakeImage(
                size,
                size,
                { x, y -> (sticker(x, y)?.first ?: 20) + noise.nextInt(7) - 3 },
                { x, y -> sticker(x, y)?.let { it.second to it.third } ?: (128 to 128) },
            )
        }
    }
}
