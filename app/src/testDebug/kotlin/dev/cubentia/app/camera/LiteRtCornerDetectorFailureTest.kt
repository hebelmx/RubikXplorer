package dev.cubentia.app.camera

import dev.cubentia.app.camera.corner.LiteRtCornerDetector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/** I/O matrix row "uncompressed .tflite missing/unreadable": the detector degrades to `null` and never throws. */
class LiteRtCornerDetectorFailureTest {
    private val frame = BufferedImage(240, 320, BufferedImage.TYPE_INT_RGB).toCornerFrame()
    private val guide = CornerGuide(grid(240, 320, 0), 0)

    @Test
    fun `a missing model asset returns null on every frame without crashing`() {
        var loads = 0
        val detector =
            LiteRtCornerDetector {
                loads++
                throw FileNotFoundException("corner_keypoints.tflite")
            }
        repeat(3) { assertNull(detector.locate(frame, guide)) }
        // Init is attempted once; later frames short-circuit instead of re-reading the asset on every frame.
        assertEquals(1, loads)
    }

    @Test
    fun `an unreadable model or unavailable runtime returns null without crashing`() {
        val detector = LiteRtCornerDetector { ByteBuffer.allocateDirect(16) }
        assertNull(detector.locate(frame, guide))
        assertNull(detector.locate(frame, guide))
    }
}
