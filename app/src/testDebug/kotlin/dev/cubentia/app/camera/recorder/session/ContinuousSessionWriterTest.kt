package dev.cubentia.app.camera.recorder.session

import dev.cubentia.app.camera.ContinuousFrameGeometry
import dev.cubentia.app.camera.ContinuousModelIdentity
import dev.cubentia.app.camera.ContinuousSessionSetup
import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.app.camera.StickerCenterPeak
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.continuous.ContinuousScanCue
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.ContinuousScanHandoff
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.Point2D
import dev.cubentia.domain.recognition.continuous.Quad2D
import dev.cubentia.domain.recognition.continuous.QuadObservation
import dev.cubentia.domain.recognition.continuous.RgbColor
import dev.cubentia.domain.recognition.continuous.TrackedQuadOverlay
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionFormat
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionJson
import dev.cubentia.domain.recognition.continuous.session.SessionOutcome
import dev.cubentia.domain.recognition.continuous.session.SessionPartition
import dev.cubentia.domain.recognition.continuous.session.SessionStopReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDateTime
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/** Story 10.34: the debug continuous session writer (bounded queue, temp dir, manifest last, atomic rename). */
class ContinuousSessionWriterTest {
    @TempDir lateinit var root: File

    private val model = ContinuousModelIdentity("sticker_centers_v2_int8.tflite", "d18f", 256, "XNNPACK", 2)
    private val setup =
        ContinuousSessionSetup("20261126-120000-TB128XU-a", "TB128XU", "LENOVO", "stla", "studio", SessionPartition.TUNING, model)
    private val geometry = ContinuousFrameGeometry(90, 640, 480, 0, 0, 640, 480)
    private val quad = Quad2D(listOf(Point2D(10.0, 10.0), Point2D(60.0, 12.0), Point2D(58.0, 62.0), Point2D(9.0, 60.0)))
    private val observation = QuadObservation(quad, List(9) { RgbColor(200.0, 30.0 + it, 40.0) }, List(9) { 0.9 }, 0.8, false, 0.95)
    private val detection = StickerCenterDetection(listOf(StickerCenterPeak(100.0, 50.0, 0.9f, 0.1, 0.2)), 480, 640, 7.5, 0.4, 32, 0)

    private fun snapshot(
        index: Int,
        tracked: Boolean = true,
    ) = ContinuousScanSnapshot.Scanning(
        index,
        Face.entries.associateWith { List(9) { null } },
        emptyList(),
        if (tracked) listOf(TrackedQuadOverlay(null, Face.F, quad)) else emptyList(),
        false,
        ContinuousScanCue.TurnRemaining(6),
        0,
        3,
        0,
        0,
    )

    private val partial = ContinuousScanExport.Partial(ContinuousScanHandoff(emptyMap()))

    /** Runs writer work inline, so a tight test loop never fills the queue. */
    private class InlineExecutor : java.util.concurrent.AbstractExecutorService() {
        private var shut = false

        override fun execute(command: Runnable) = command.run()

        override fun shutdown() {
            shut = true
        }

        override fun shutdownNow(): MutableList<Runnable> = mutableListOf<Runnable>().also { shut = true }

        override fun isShutdown() = shut

        override fun isTerminated() = shut

        override fun awaitTermination(
            timeout: Long,
            unit: java.util.concurrent.TimeUnit,
        ) = true
    }

    private fun writer(
        sizeCap: Long = ContinuousSessionFormat.SIZE_CAP_BYTES,
        imageEvery: Int = ContinuousSessionFormat.IMAGE_EVERY,
        baseline: Double? = null,
        executor: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor(),
    ) = ContinuousSessionWriter(
        root = root,
        setup = setup,
        buildCommit = "c".repeat(40),
        litertVersion = "2.3.2",
        createdAt = "2026-11-26T12:00:00Z",
        sizeCapBytes = sizeCap,
        imageEvery = imageEvery,
        baselineP50Ms = { baseline },
        executor = executor,
    )

    private val image = ByteArray(256 * 256 * 3).also { Random(7).nextBytes(it) }

    private fun ContinuousSessionWriter.frame(
        index: Int,
        observations: List<QuadObservation> = listOf(observation),
        analyzerMs: Double = 10.0,
        tracked: Boolean = true,
    ) {
        onFrame(index, observations, snapshot(index, tracked), detection, if (wantsModelInput(index)) image else null)
        frameDone(1_000L + index * 33, analyzerMs, geometry)
    }

    @Test
    fun `a saved session has every manifest field, keeps empty frames and a PNG every third frame`() {
        val w = writer()
        repeat(7) { i -> w.frame(i, observations = if (i == 1) emptyList() else listOf(observation)) }
        val saved = w.save(partial)
        assertEquals(SessionOutcome.PARTIAL, saved.outcome)
        assertTrue(saved.defectReasons.isEmpty())
        val dir = File(root, setup.sessionId)
        assertEquals(dir.path, saved.path)
        assertFalse(w.tempDir.exists())
        val m = ContinuousSessionJson.readManifest(dir)
        assertEquals(setup.sessionId, m.sessionId)
        assertEquals("TB128XU", m.device.model)
        assertEquals("LENOVO", m.device.manufacturer)
        assertEquals("stla", m.cube)
        assertEquals("studio", m.lighting)
        assertEquals(SessionPartition.TUNING, m.partition)
        assertEquals("sticker_centers_v2_int8.tflite", m.model.file)
        assertEquals("d18f", m.model.sha256)
        assertEquals("2.3.2", m.model.litertVersion)
        assertEquals("XNNPACK", m.model.delegate)
        assertEquals(2, m.model.threads)
        assertEquals("c".repeat(40), m.buildCommit)
        assertEquals(7, m.frameCount)
        assertEquals(6, m.framesWithObservations)
        assertEquals(3, m.imageEvery)
        assertEquals(3, m.imageFrames)
        assertEquals(90, m.geometry?.rotationDegrees)
        assertEquals(480, m.geometry?.uprightWidth)
        assertEquals(0.4, m.geometry?.letterboxScale)
        assertEquals(32, m.geometry?.padLeft)
        assertEquals(1000L, m.timing.firstTimestampMs)
        assertEquals(1198L, m.timing.lastTimestampMs)
        assertEquals(0, m.captureSkips)
        assertEquals(0, m.droppedFrames)
        assertEquals(SessionStopReason.OPERATOR, m.stopReason)
        assertEquals(10.0, m.latencyP50Ms)
        assertEquals(10.0, m.latencyP95Ms)
        assertNull(m.latencyBaselineP50Ms)
        assertFalse(m.defective)
        assertTrue(m.bytes > 0)
        assertFalse(m.synthetic)
        assertNull(m.goldenRevision)
        val frames = ContinuousSessionJson.readFrames(dir)
        assertEquals((0..6).toList(), frames.map { it.frameIndex })
        assertTrue(frames[1].observations.isEmpty())
        assertEquals(observation, frames[0].observations.single().toDomain())
        assertEquals(10.0, frames[0].analyzerMs)
        assertEquals(7.5, frames[0].inferenceMs)
        // Peaks in model-input pixels: upright × scale + pad.
        assertEquals(100.0 * 0.4 + 32, frames[0].peaks.single().x, 1e-9)
        assertEquals(50.0 * 0.4, frames[0].peaks.single().y, 1e-9)
        assertEquals(listOf(0, 3, 6), frames.filter { it.image != null }.map { it.frameIndex })
        frames.mapNotNull { it.image }.forEach { name ->
            val png = ImageIO.read(File(dir, name))
            assertEquals(256, png.width)
            assertEquals(256, png.height)
            val rgb = png.getRGB(5, 3)
            val at = (3 * 256 + 5) * 3
            assertEquals(image[at].toInt() and 0xff, rgb shr 16 and 0xff)
            assertEquals(image[at + 1].toInt() and 0xff, rgb shr 8 and 0xff)
            assertEquals(image[at + 2].toInt() and 0xff, rgb and 0xff)
        }
        assertEquals(7, ContinuousSessionJson.readSnapshots(dir).size)
        assertEquals(listOf(dir), ContinuousSessionFormat.sessions(root))
    }

    @Test
    fun `a full queue skips the frame, counts it and marks the session defective`() {
        val executor = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        executor.execute { gate.await() }
        val w = writer(executor = executor)
        repeat(9) { w.frame(it) }
        assertEquals(1, w.status.skips)
        assertEquals(8, w.status.frames)
        gate.countDown()
        val saved = w.save(partial)
        assertEquals(listOf("captureSkips=1"), saved.defectReasons)
        val m = ContinuousSessionJson.readManifest(File(saved.path))
        assertEquals(1, m.captureSkips)
        assertTrue(m.defective)
        assertEquals(8, m.frameCount)
    }

    @Test
    fun `capture stops itself at 600 frames`() {
        val w = writer(imageEvery = 0, executor = InlineExecutor())
        repeat(605) { w.frame(it) }
        assertFalse(w.status.recording)
        assertEquals(SessionStopReason.FRAME_CAP, w.status.stopReason)
        assertFalse(w.wantsModelInput(0))
        val m = ContinuousSessionJson.readManifest(File(w.save(partial).path))
        assertEquals(600, m.frameCount)
        assertEquals(SessionStopReason.FRAME_CAP, m.stopReason)
        assertEquals(600, ContinuousSessionFormat.FRAME_CAP)
        assertEquals(40_000_000L, ContinuousSessionFormat.SIZE_CAP_BYTES)
        assertEquals(8, ContinuousSessionFormat.QUEUE_FRAMES)
    }

    @Test
    fun `capture stops itself before the byte cap is passed`() {
        val cap = ContinuousSessionWriter.MANIFEST_RESERVE_BYTES + 300_000
        val w = writer(sizeCap = cap, imageEvery = 1, executor = InlineExecutor())
        repeat(4) { w.frame(it) }
        val saved = w.save(partial)
        val m = ContinuousSessionJson.readManifest(File(saved.path))
        assertEquals(SessionStopReason.SIZE_CAP, m.stopReason)
        assertEquals(1, m.frameCount)
        assertTrue(m.bytes <= cap - ContinuousSessionWriter.MANIFEST_RESERVE_BYTES)
        val total = File(saved.path).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue(total <= cap, "$total > $cap")
    }

    @Test
    fun `an interrupted write leaves only the temp dir, which no reader takes as complete`() {
        val executor = Executors.newSingleThreadExecutor()
        val w = writer(executor = executor)
        repeat(3) { w.frame(it) }
        executor.submit {}.get()
        assertTrue(w.tempDir.isDirectory)
        assertFalse(File(w.tempDir, ContinuousSessionFormat.MANIFEST).exists())
        assertFalse(w.finalDir.exists())
        assertTrue(ContinuousSessionFormat.sessions(root).isEmpty())
        executor.shutdownNow()
    }

    @Test
    fun `discard deletes the temp dir and leaves nothing under the session id`() {
        val w = writer()
        repeat(4) { w.frame(it) }
        assertTrue(w.discard())
        assertFalse(w.tempDir.exists())
        assertFalse(w.finalDir.exists())
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `p50 more than 10 percent over the device baseline is defective, 10 percent or less is not`() {
        val slow = writer(baseline = 10.0)
        repeat(3) { slow.frame(it, analyzerMs = 11.5) }
        val saved = slow.save(partial)
        assertEquals(listOf("capture p50 11.50 ms > 1.10 × baseline p50 10.00 ms"), saved.defectReasons)
        assertEquals(10.0, ContinuousSessionJson.readManifest(File(saved.path)).latencyBaselineP50Ms)
        File(saved.path).deleteRecursively()
        val ok = writer(baseline = 10.0)
        repeat(3) { ok.frame(it, analyzerMs = 11.0) }
        assertTrue(ok.save(partial).defectReasons.isEmpty())
    }

    @Test
    fun `outcome follows the export and the last snapshot`() {
        val lost = writer()
        lost.frame(0, tracked = false)
        assertEquals(SessionOutcome.LOST_TRACKING, lost.save(partial).outcome)
        File(root, setup.sessionId).deleteRecursively()
        val failed = writer()
        failed.frame(0)
        assertEquals(SessionOutcome.FAILED, failed.save(ContinuousScanExport.TwistDetected(0, ContinuousScanHandoff(emptyMap()))).outcome)
    }

    @Test
    fun `latency statistics, dropped-frame estimate, session ids and baselines`() {
        assertEquals(2.0, SessionLatency.p50(listOf(3.0, 1.0, 2.0)))
        assertEquals(19.0, SessionLatency.p95((1..20).map { it.toDouble() }))
        assertNull(SessionLatency.p50(emptyList()))
        assertEquals(0, SessionLatency.droppedFrames(listOf(0, 33, 66, 99)))
        assertEquals(3, SessionLatency.droppedFrames(listOf(0, 33, 66, 165, 198, 264)))
        assertEquals("20261126-091502-TB128XU-run1", SessionFiles.sessionId(LocalDateTime.of(2026, 11, 26, 9, 15, 2), "TB128XU", "run 1"))
        assertEquals("20261126-091502-SM-S721B", SessionFiles.sessionId(LocalDateTime.of(2026, 11, 26, 9, 15, 2), "SM-S721B", null))
        val stored = SessionFiles.writeBaseline(root, "TB128XU", listOf(30.0, 32.0, 31.0), "2026-11-26T00:00:00Z")
        assertNotNull(stored)
        assertEquals(31.0, SessionFiles.readBaseline(root, "TB128XU")?.p50Ms)
        assertTrue(SessionFiles.baselineFile(root, "TB128XU").name.startsWith(".baseline-"))
        assertNull(SessionFiles.readBaseline(root, "other"))
        assertNull(SessionFiles.writeBaseline(root, "x", emptyList()))
        // Baselines sit outside any session dir and are never read as a session.
        assertTrue(ContinuousSessionFormat.sessions(root).isEmpty())
    }

    @Test
    fun `the debug factory writes under its root and keeps baselines there`() {
        val factory = DebugContinuousSessionWriterFactory({ root }, "b".repeat(40), "2.3.2")
        val session = factory.create(setup)
        session.onFrame(0, emptyList(), snapshot(0), detection, null)
        session.frameDone(1, 1.0, geometry)
        val saved = session.save(partial)
        assertEquals("b".repeat(40), ContinuousSessionJson.readManifest(File(saved.path)).buildCommit)
        assertEquals(20.0, factory.saveBaseline("TB128XU", listOf(20.0))?.p50Ms)
    }

    @Test
    fun `after the byte cap every queued frame is dropped and the outcome follows the last written frame`() {
        val executor = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        executor.execute { gate.await() }
        val cap = ContinuousSessionWriter.MANIFEST_RESERVE_BYTES + 300_000
        val w = writer(sizeCap = cap, imageEvery = 1, executor = executor)
        // Frame 0 (image, lost tracking) fits; frame 1 (image) passes the cap; frames 2-3 are small but must not be written.
        listOf(0 to image, 1 to image, 2 to null, 3 to null).forEach { (i, input) ->
            w.onFrame(i, listOf(observation), snapshot(i, tracked = i != 0), detection, input)
            w.frameDone(1_000L + i * 33, 10.0, geometry)
        }
        gate.countDown()
        val saved = w.save(partial)
        val dir = File(saved.path)
        assertEquals(listOf(0), ContinuousSessionJson.readFrames(dir).map { it.frameIndex })
        assertEquals(1, ContinuousSessionJson.readSnapshots(dir).size)
        val m = ContinuousSessionJson.readManifest(dir)
        assertEquals(SessionStopReason.SIZE_CAP, m.stopReason)
        assertEquals(1, m.frameCount)
        assertEquals(SessionOutcome.LOST_TRACKING, saved.outcome)
    }

    @Test
    fun `geometry is taken from the first frame with a letterbox, not from an empty detection`() {
        val w = writer(imageEvery = 0, executor = InlineExecutor())
        w.onFrame(0, emptyList(), snapshot(0), StickerCenterDetection.EMPTY, null)
        w.frameDone(1_000, 5.0, geometry)
        w.frame(1)
        val m = ContinuousSessionJson.readManifest(File(w.save(partial).path))
        assertEquals(0.4, m.geometry?.letterboxScale)
        assertEquals(32, m.geometry?.padLeft)
    }

    @Test
    fun `a frame write failure with a successful rename is saved as a defective session`() {
        val w = writer(imageEvery = 1, executor = InlineExecutor())
        // A directory where frame 1's PNG goes: that write throws after the streams are open.
        File(w.tempDir, ContinuousSessionFormat.imageName(1)).mkdirs()
        repeat(3) { w.frame(it) }
        assertFalse(w.status.recording)
        val saved = w.save(partial)
        val m = ContinuousSessionJson.readManifest(File(saved.path))
        assertTrue(m.defective)
        assertTrue(m.defectReasons.any { it.startsWith("WRITE_FAILED") }, m.defectReasons.toString())
        assertEquals(1, m.frameCount)
    }

    @Test
    fun `a write failure stops the session without throwing and frames after shutdown are not captured`() {
        root.mkdirs()
        // A file where the temp directory should be: every write fails.
        File(root, ContinuousSessionFormat.TEMP_PREFIX + setup.sessionId).writeText("blocker")
        val executor = Executors.newSingleThreadExecutor()
        val w = writer(executor = executor)
        w.frame(0)
        executor.submit {}.get()
        assertFalse(w.status.recording)
        org.junit.jupiter.api.Assertions
            .assertThrows(Exception::class.java) { w.save(partial) }
        assertTrue(executor.isShutdown)
        // After the executor is shut down a late frame is simply not captured.
        val late = writer(executor = executor)
        late.frame(1)
        assertEquals(0, late.status.frames)
    }
}
