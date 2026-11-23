package dev.cubentia.app.camera.sticker

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.app.camera.YuvPlane
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.QuickScanClassification
import dev.cubentia.domain.recognition.StickerConfidence
import dev.cubentia.domain.recognition.StickerReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Story 10.23: every I/O-matrix row of the shadow sticker reader below the route level (the replay is ShadowReaderReplayTest). */
@RunWith(RobolectricTestRunner::class)
class ShadowStickerReaderTest {
    private val lines = CopyOnWriteArrayList<String>()
    private val loads = AtomicInteger()
    private var on = true

    /** A model that reads every sticker as WHITE with probability 1 (CONFIDENT). */
    private val white =
        StickerInterpreter { _, output ->
            output[0].forEach { cell ->
                cell.fill(0f)
                cell[0] = 1f
            }
        }

    /** A reader with its scan already started (as the activity does at hold 1's view entry). */
    private fun reader(load: () -> StickerInterpreter = { white }) = unstarted(load).also { it.onScanStarted(1) }

    private fun unstarted(load: () -> StickerInterpreter = { white }) =
        ShadowStickerReader(
            enabled = { on },
            loadInterpreter = {
                loads.incrementAndGet()
                load()
            },
            log = { lines += it },
        )

    private fun crop(): YuvFrame {
        fun plane(width: Int) = YuvPlane(ByteBuffer.wrap(ByteArray(width * width) { 100 }), width, 1)
        return YuvFrame(plane(96), plane(48), plane(48), 0, 0, 96, 96)
    }

    private val reports =
        listOf(
            QuickScanClassification.FaceReport(
                Face.U,
                Color.WHITE,
                9,
                0,
                false,
                CaptureQuality.ACCEPTABLE,
                false,
                0,
                0,
                false,
                self = List(9) { StickerReading(Color.WHITE, StickerConfidence.CONFIDENT) },
                second = List(8) { StickerReading(Color.WHITE, StickerConfidence.CONFIDENT) } + null,
                secondRead = QuickScanClassification.SecondRead.CALIBRATION,
            ),
        )

    private fun shadowLines() = lines.filter { it.startsWith("quickScan shadow ") }

    @Test
    fun `each capture logs one line per face beside both Kotlin reads`() {
        val reader = reader()
        reader.onScanStarted(1)
        reader.onCornerCaptured(1, List(3) { crop() }, reports)
        reader.awaitIdle()
        assertEquals("quickScan shadowReader state=on", lines.first())
        val shadow = shadowLines()
        assertEquals(3, shadow.size)
        assertTrue(
            shadow[0],
            Regex(
                """quickScan shadow hold=1 face=U center=W inferMs=\d+ model=WWWWWWWWW/CCCCCCCCC """ +
                    """self=WWWWWWWWW/CCCCCCCCC second=cal:WWWWWWWW-/CCCCCCCC-""",
            ).matches(shadow[0]),
        )
        // F and R have no report at this capture (kept faces are not judged): their Kotlin reads are "-".
        assertTrue(shadow[1], shadow[1].startsWith("quickScan shadow hold=1 face=F center=G ") && shadow[1].endsWith(" self=- second=-"))
        assertTrue(shadow[2], shadow[2].startsWith("quickScan shadow hold=1 face=R center=R "))
        assertEquals(1, loads.get())
    }

    @Test
    fun `off logs the state and reads nothing`() {
        on = false
        val reader = reader()
        reader.onScanStarted(1)
        reader.onCornerCaptured(1, List(3) { crop() }, reports)
        reader.awaitIdle()
        assertEquals(listOf("quickScan shadowReader state=off"), lines.toList())
        assertEquals(0, loads.get())
    }

    @Test
    fun `a toggle flipped mid-scan takes effect at the next capture`() {
        val reader = reader()
        reader.onScanStarted(1)
        on = false
        reader.onCornerCaptured(1, List(3) { crop() }, reports)
        on = true
        reader.onCornerCaptured(2, List(3) { crop() }, emptyList())
        reader.awaitIdle()
        assertEquals("on at scan start", "quickScan shadowReader state=on", lines.first())
        assertTrue(shadowLines().toString(), shadowLines().size == 3 && shadowLines().all { it.contains(" hold=2 ") })
    }

    @Test
    fun `a missing crop is logged model=none and the other faces are read`() {
        val reader = reader()
        reader.onCornerCaptured(2, listOf(crop(), null, crop()), emptyList())
        reader.awaitIdle()
        val shadow = shadowLines()
        assertEquals(listOf("D", "L", "B"), shadow.map { it.substringAfter("face=").substringBefore(' ') })
        assertTrue(shadow[1], shadow[1].contains(" inferMs=- model=none "))
        assertTrue(shadow[0].contains(" model=WWWWWWWWW/CCCCCCCCC ") && shadow[2].contains(" model=WWWWWWWWW/CCCCCCCCC "))
    }

    @Test
    fun `no crop at all never loads the model`() {
        val reader = reader()
        reader.onCornerCaptured(1, List(3) { null }, reports)
        reader.awaitIdle()
        assertEquals(3, shadowLines().count { it.contains(" model=none ") })
        assertEquals(0, loads.get())
    }

    @Test
    fun `a missing model logs one error per scan, loads once per process and never throws`() {
        val reader = reader { throw java.io.FileNotFoundException("sticker_reader.tflite") }
        reader.onScanStarted(1)
        reader.onCornerCaptured(1, List(3) { crop() }, reports)
        reader.onCornerCaptured(2, List(3) { crop() }, emptyList())
        reader.awaitIdle()
        assertEquals(1, lines.count { it == "quickScan shadowReader error=model-missing" })
        reader.onScanEnded(1)
        reader.onScanStarted(2)
        reader.onCornerCaptured(1, List(3) { crop() }, reports)
        reader.awaitIdle()
        assertEquals("once per scan", 2, lines.count { it == "quickScan shadowReader error=model-missing" })
        assertEquals("one load per process", 1, loads.get())
        assertTrue(shadowLines().isEmpty())
    }

    @Test
    fun `a missing native runtime reads as a missing model`() {
        val reader = reader { throw UnsatisfiedLinkError("libLiteRt.so") }
        reader.onScanStarted(1)
        reader.onCornerCaptured(1, List(3) { crop() }, reports)
        reader.awaitIdle()
        assertEquals(1, lines.count { it == "quickScan shadowReader error=model-missing" })
    }

    @Test
    fun `an inference error logs one line for that capture and the next capture reads again`() {
        val calls = AtomicInteger()
        val flaky =
            StickerInterpreter { input, output ->
                if (calls.incrementAndGet() == 1) throw IllegalStateException("interpreter failed")
                white.run(input, output)
            }
        val reader = reader { flaky }
        reader.onCornerCaptured(1, List(3) { crop() }, reports)
        reader.onCornerCaptured(2, List(3) { crop() }, emptyList())
        reader.awaitIdle()
        assertEquals(listOf("quickScan shadowReader error=inference hold=1"), lines.filter { it.contains("error=") })
        assertEquals(3, shadowLines().size)
        assertTrue(shadowLines().all { it.contains(" hold=2 ") })
        val linkage = reader { StickerInterpreter { _, _ -> throw NoClassDefFoundError("org/tensorflow/lite/Interpreter") } }
        linkage.onCornerCaptured(1, List(3) { crop() }, reports)
        linkage.awaitIdle()
        assertEquals(2, lines.count { it == "quickScan shadowReader error=inference hold=1" })
    }

    @Test
    fun `the scan end drops queued reads and stops the executor`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking =
            StickerInterpreter { input, output ->
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                white.run(input, output)
            }
        val reader = reader { blocking }
        reader.onCornerCaptured(1, List(3) { crop() }, reports)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        // Queued behind the read in progress: dropped by the scan end, crops and all.
        reader.onCornerCaptured(2, List(3) { crop() }, emptyList())
        reader.onScanEnded(1)
        release.countDown()
        reader.awaitIdle()
        Thread.sleep(200)
        assertFalse(shadowLines().toString(), shadowLines().any { it.contains(" hold=2 ") })
        // A later scan starts a fresh executor.
        reader.onScanStarted(2)
        reader.onCornerCaptured(1, List(3) { crop() }, reports)
        reader.awaitIdle()
        assertTrue(shadowLines().any { it.contains(" hold=1 ") })
    }

    @Test
    fun `a start that lands after its scan ended is ignored and the next scan logs its state line`() {
        val reader = unstarted { throw java.io.FileNotFoundException("sticker_reader.tflite") }
        // Closed right after hold 1's view opened: the end (main) beats the start queued on the analyzer.
        reader.onScanEnded(1)
        reader.onScanStarted(1)
        reader.onCornerCaptured(1, List(3) { crop() }, reports)
        reader.awaitIdle()
        assertTrue(lines.toString(), lines.isEmpty())
        reader.onScanStarted(2)
        reader.onCornerCaptured(1, List(3) { crop() }, reports)
        reader.awaitIdle()
        assertEquals(listOf("quickScan shadowReader state=on", "quickScan shadowReader error=model-missing"), lines.toList())
    }

    @Test
    fun `a late end of an earlier scan leaves the running scan alone`() {
        val reader = reader()
        reader.onScanEnded(1)
        reader.onScanStarted(2)
        reader.onScanEnded(1)
        reader.onCornerCaptured(1, List(3) { crop() }, reports)
        reader.awaitIdle()
        assertEquals(3, shadowLines().size)
    }

    @Test
    fun `a capture after the scan ended is ignored and starts no thread`() {
        val reader = reader()
        reader.onScanEnded(1)
        reader.onCornerCaptured(1, List(3) { crop() }, reports)
        reader.awaitIdle()
        assertTrue(shadowLines().toString(), shadowLines().isEmpty())
        assertEquals(0, loads.get())
        val captureOutsideAnyScan = unstarted()
        captureOutsideAnyScan.onCornerCaptured(1, List(3) { crop() }, reports)
        captureOutsideAnyScan.awaitIdle()
        assertTrue(shadowLines().isEmpty())
    }

    @Test
    fun `re-entering hold 1 within a scan neither repeats the state line nor re-logs a missing model`() {
        val reader = reader { throw java.io.FileNotFoundException("sticker_reader.tflite") }
        reader.onCornerCaptured(1, List(3) { crop() }, reports)
        reader.awaitIdle()
        reader.onScanStarted(1)
        reader.onCornerCaptured(1, List(3) { crop() }, reports)
        reader.awaitIdle()
        assertEquals(1, lines.count { it.startsWith("quickScan shadowReader state=") })
        assertEquals(1, lines.count { it == "quickScan shadowReader error=model-missing" })
    }

    @Test
    fun `a non-boolean on extra is ignored and leaves the toggle unchanged`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val receiver = ShadowReaderToggleReceiver()
        assertTrue(ShadowReaderToggle.isOn(context))
        receiver.onReceive(context, Intent(ShadowReaderToggle.ACTION).putExtra(ShadowReaderToggle.EXTRA_ON, "false"))
        assertTrue("--es on false is ignored", ShadowReaderToggle.isOn(context))
        receiver.onReceive(context, Intent(ShadowReaderToggle.ACTION).putExtra(ShadowReaderToggle.EXTRA_ON, 0))
        assertTrue("--ei on 0 is ignored", ShadowReaderToggle.isOn(context))
        receiver.onReceive(context, Intent(ShadowReaderToggle.ACTION).putExtra(ShadowReaderToggle.EXTRA_ON, false))
        assertFalse(ShadowReaderToggle.isOn(context))
        receiver.onReceive(context, Intent(ShadowReaderToggle.ACTION).putExtra(ShadowReaderToggle.EXTRA_ON, "true"))
        assertFalse("--es on true is ignored too", ShadowReaderToggle.isOn(context))
    }

    @Test
    fun `the adb broadcast sets the toggle, which defaults to on and is registered in the debug manifest`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue("default on", ShadowReaderToggle.isOn(context))
        val receiver = ShadowReaderToggleReceiver()
        receiver.onReceive(context, Intent(ShadowReaderToggle.ACTION).putExtra(ShadowReaderToggle.EXTRA_ON, false))
        assertFalse(ShadowReaderToggle.isOn(context))
        receiver.onReceive(context, Intent(ShadowReaderToggle.ACTION))
        assertFalse("no extra changes nothing", ShadowReaderToggle.isOn(context))
        receiver.onReceive(context, Intent(ShadowReaderToggle.ACTION).putExtra(ShadowReaderToggle.EXTRA_ON, true))
        assertTrue(ShadowReaderToggle.isOn(context))
        val registered =
            context.packageManager.queryBroadcastReceivers(Intent(ShadowReaderToggle.ACTION).setPackage(context.packageName), 0)
        assertEquals(listOf(ShadowReaderToggleReceiver::class.java.name), registered.map { it.activityInfo.name })
        assertEquals("android.permission.DUMP", registered.single().activityInfo.permission)
    }
}
