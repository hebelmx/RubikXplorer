package dev.cubentia.app.camera.session

import dev.cubentia.app.camera.ContinuousModelIdentity
import dev.cubentia.app.camera.ContinuousSessionSetup
import dev.cubentia.app.camera.ContinuousSessionTags
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
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionLabels
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
import java.util.concurrent.ThreadFactory
import javax.imageio.ImageIO

/**
 * Story 10.34: the debug session writer. Analyzer side: 8 preallocated slots offered to a bounded queue, a skip counted
 * when none is free; writer side: one long-lived thread, temp dir, manifest last, atomic rename.
 */
class ContinuousSessionWriterTest {
    @TempDir lateinit var root: File

    private val model = ContinuousModelIdentity("sticker_centers_v2_int8.tflite", "d18f", 256, "XNNPACK", 2)
    private val setup = ContinuousSessionSetup("20261126-120000-tb128xu-a", "TB128XU", "LENOVO", model)
    private val tags = ContinuousSessionTags("stla", "studio", SessionPartition.TUNING)
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

    /** A writer thread that starts only once [gate] opens: a slow writer, so the analyzer side fills every slot. */
    private fun gated(gate: CountDownLatch) =
        ThreadFactory { work ->
            Thread {
                gate.await()
                work.run()
            }
        }

    private fun writer(
        sizeCap: Long = ContinuousSessionFormat.SIZE_CAP_BYTES,
        imageEvery: Int = ContinuousSessionFormat.IMAGE_EVERY,
        threads: ThreadFactory = ThreadFactory { Thread(it) },
    ) = ContinuousSessionWriter(
        root = root,
        setup = setup,
        buildCommit = "c".repeat(40),
        litertVersion = "2.3.2",
        createdAt = "2026-11-26T12:00:00Z",
        sizeCapBytes = sizeCap,
        imageEvery = imageEvery,
        threadFactory = threads,
    )

    private val image = ByteArray(256 * 256 * 3).also { Random(7).nextBytes(it) }

    /** One analyzer frame as the pipeline and ScanFrameSource drive the tap: buffer, onFrame, frameDone. */
    private fun ContinuousSessionWriter.frame(
        index: Int,
        observations: List<QuadObservation> = listOf(observation),
        analyzerMs: Double = 10.0,
        tracked: Boolean = true,
        detected: StickerCenterDetection = detection,
        crop: Int = 640,
    ) {
        val buffer = modelInputBuffer(index)
        buffer?.let { image.copyInto(it) }
        onFrame(index, observations, snapshot(index, tracked), detected, buffer != null)
        frameDone(1_000_000_000L + index * 33_000_000L, (analyzerMs * 1_000_000).toLong(), 90, 640, 480, 0, 0, crop, 480)
    }

    private fun ContinuousSessionWriter.skipped(index: Int) {
        onSkippedFrame(index)
        frameDone(1_000_000_000L + index * 33_000_000L, 1_000_000L, 90, 640, 480, 0, 0, 640, 480)
    }

    @Test
    fun `a saved session has every manifest field, keeps empty frames and a lossless 256 PNG every third frame`() {
        val w = writer()
        repeat(7) { i -> w.frame(i, observations = if (i == 1) emptyList() else listOf(observation)) }
        w.awaitWritten()
        assertTrue(File(w.tempDir, ContinuousSessionFormat.FRAMES).isFile, "frames stream into the temp dir")
        val saved = w.save(partial, tags, null)
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
        assertTrue(m.geometryChanges.isEmpty())
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
    fun `the analyzer side allocates its 8 slots once, for 600 frames, and every frame is written or skipped`() {
        val w = writer(imageEvery = 0)
        repeat(600) {
            w.frame(it)
            if (it % 4 == 3) w.awaitWritten()
        }
        assertEquals(8, w.slotAllocations)
        assertEquals(ContinuousSessionFormat.QUEUE_FRAMES, w.slotAllocations)
        val m = ContinuousSessionJson.readManifest(File(w.save(partial, tags, null).path))
        assertEquals(600, m.frameCount + m.captureSkips)
        assertEquals(8, w.slotAllocations)
    }

    @Test
    fun `a full queue skips the frame, counts it and marks the session defective`() {
        val gate = CountDownLatch(1)
        val w = writer(threads = gated(gate))
        repeat(8) { w.frame(it) }
        assertEquals(0, w.status.skips)
        // With the writer blocked all 8 slots are in use: the next image frame gets no buffer and is counted as a skip.
        assertNull(w.modelInputBuffer(9))
        w.frame(9)
        assertEquals(1, w.status.skips)
        assertEquals(8, w.status.frames)
        assertEquals(8, w.slotAllocations)
        gate.countDown()
        val saved = w.save(partial, tags, null)
        assertEquals(listOf("captureSkips=1"), saved.defectReasons)
        val m = ContinuousSessionJson.readManifest(File(saved.path))
        assertEquals(1, m.captureSkips)
        assertTrue(m.defective)
        assertEquals(8, m.frameCount)
    }

    @Test
    fun `capture stops itself at 600 frames`() {
        val w = writer(imageEvery = 0)
        repeat(605) {
            w.frame(it)
            w.awaitWritten()
        }
        assertFalse(w.status.recording)
        assertFalse(w.recording)
        assertEquals(SessionStopReason.FRAME_CAP, w.status.stopReason)
        assertEquals(null, w.modelInputBuffer(0))
        val m = ContinuousSessionJson.readManifest(File(w.save(partial, tags, null).path))
        assertEquals(600, m.frameCount)
        assertEquals(SessionStopReason.FRAME_CAP, m.stopReason)
        assertEquals(600, ContinuousSessionFormat.FRAME_CAP)
        assertEquals(40_000_000L, ContinuousSessionFormat.SIZE_CAP_BYTES)
        assertEquals(8, ContinuousSessionFormat.QUEUE_FRAMES)
    }

    @Test
    fun `capture stops itself before the byte cap is passed`() {
        val cap = ContinuousSessionWriter.MANIFEST_RESERVE_BYTES + 300_000
        val w = writer(sizeCap = cap, imageEvery = 1)
        repeat(4) {
            w.frame(it)
            w.awaitWritten()
        }
        val saved = w.save(partial, tags, null)
        val m = ContinuousSessionJson.readManifest(File(saved.path))
        assertEquals(SessionStopReason.SIZE_CAP, m.stopReason)
        assertEquals(1, m.frameCount)
        assertTrue(m.bytes <= cap - ContinuousSessionWriter.MANIFEST_RESERVE_BYTES)
        val total = File(saved.path).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue(total <= cap, "$total > $cap")
    }

    @Test
    fun `frames queued when the byte cap is hit are counted in captureSkips, not lost`() {
        val gate = CountDownLatch(1)
        val cap = ContinuousSessionWriter.MANIFEST_RESERVE_BYTES + 300_000
        val w = writer(sizeCap = cap, imageEvery = 1, threads = gated(gate))
        // All four are queued while recording; frame 0 fits, frame 1 passes the cap, frames 2-3 are then dropped.
        repeat(4) { w.frame(it) }
        assertEquals(4, w.status.frames)
        gate.countDown()
        val m = ContinuousSessionJson.readManifest(File(w.save(partial, tags, null).path))
        assertEquals(1, m.frameCount)
        assertEquals(3, m.captureSkips)
        assertEquals(m.frameCount + m.captureSkips, 4)
        assertTrue(m.defective)
    }

    @Test
    fun `after a failed save a discard still deletes the temp dir`() {
        val w = writer()
        repeat(3) { w.frame(it) }
        w.awaitWritten()
        // A directory already at the final name makes the rename step fail.
        w.finalDir.mkdirs()
        org.junit.jupiter.api.Assertions
            .assertThrows(IllegalStateException::class.java) { w.save(partial, tags, null) }
        assertTrue(w.tempDir.isDirectory)
        assertTrue(w.discard())
        assertFalse(w.tempDir.exists())
    }

    @Test
    fun `the first session of a process deletes temp dirs a process death left`() {
        val orphan = File(root, ContinuousSessionFormat.TEMP_PREFIX + "20261125-101010-tb128xu").apply { mkdirs() }
        File(orphan, ContinuousSessionFormat.FRAMES).writeText("{}\n")
        val kept = File(root, "20261125-101010-tb128xu-done").apply { mkdirs() }
        val factory = DebugContinuousSessionWriterFactory({ root }, "b".repeat(40), "2.3.2")
        val first = factory.create(setup) as ContinuousSessionWriter
        assertFalse(orphan.exists())
        assertTrue(kept.isDirectory)
        first.onFrame(0, emptyList(), snapshot(0), detection, false)
        first.frameDone(1, 1, 90, 640, 480, 0, 0, 640, 480)
        first.awaitWritten()
        // A later session of the same process never sweeps a live one.
        val second = factory.create(setup.copy(sessionId = "20261126-120001-tb128xu")) as ContinuousSessionWriter
        assertTrue(first.tempDir.isDirectory)
        first.discard()
        second.discard()
    }

    @Test
    fun `an interrupted write leaves only the temp dir, which no reader takes as complete`() {
        val w = writer()
        repeat(3) { w.frame(it) }
        w.awaitWritten()
        w.kill()
        assertTrue(w.tempDir.isDirectory)
        assertFalse(File(w.tempDir, ContinuousSessionFormat.MANIFEST).exists())
        assertFalse(w.finalDir.exists())
        assertTrue(ContinuousSessionFormat.sessions(root).isEmpty())
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
    fun `p50 more than 10 percent over the device baseline is defective, 10 percent or less is not, and none is null`() {
        val slow = writer()
        repeat(3) { slow.frame(it, analyzerMs = 11.5) }
        val saved = slow.save(partial, tags, 10.0)
        assertEquals(listOf("capture p50 11.50 ms > 1.10 × baseline p50 10.00 ms"), saved.defectReasons)
        assertEquals(10.0, ContinuousSessionJson.readManifest(File(saved.path)).latencyBaselineP50Ms)
        File(saved.path).deleteRecursively()
        val ok = writer()
        repeat(3) { ok.frame(it, analyzerMs = 11.0) }
        assertTrue(ok.save(partial, tags, 10.0).defectReasons.isEmpty())
        File(root, setup.sessionId).deleteRecursively()
        val noBaseline = writer()
        repeat(3) { noBaseline.frame(it, analyzerMs = 50.0) }
        val unjudged = noBaseline.save(partial, tags, null)
        assertTrue(unjudged.defectReasons.isEmpty())
        assertNull(ContinuousSessionJson.readManifest(File(unjudged.path)).latencyBaselineP50Ms)
    }

    @Test
    fun `outcome follows the export and the last snapshot, and the net is the labeller's pre-fill`() {
        val lost = writer()
        lost.frame(0, tracked = false)
        assertEquals(SessionOutcome.LOST_TRACKING, lost.save(partial, tags, null).outcome)
        File(root, setup.sessionId).deleteRecursively()
        val failed = writer()
        failed.frame(0)
        val saved = failed.save(ContinuousScanExport.TwistDetected(0, ContinuousScanHandoff(emptyMap())), tags, null)
        assertEquals(SessionOutcome.FAILED, saved.outcome)
        assertEquals(54, saved.net.size)
    }

    @Test
    fun `a skipped frame is a frames line without observations and without a snapshot line`() {
        val w = writer(imageEvery = 0)
        w.frame(0)
        w.skipped(1)
        w.frame(2)
        val dir = File(w.save(partial, tags, null).path)
        val frames = ContinuousSessionJson.readFrames(dir)
        assertEquals(listOf(false, true, false), frames.map { it.skipped })
        assertTrue(frames[1].observations.isEmpty())
        assertEquals(listOf(0, 2), ContinuousSessionJson.readSnapshots(dir).map { it.frameIndex })
        assertEquals(3, ContinuousSessionJson.readManifest(dir).frameCount)
    }

    @Test
    fun `geometry is taken from the first frame with a letterbox, and a later change is appended`() {
        val w = writer(imageEvery = 0)
        w.frame(0, observations = emptyList(), detected = StickerCenterDetection.EMPTY)
        w.frame(1)
        w.frame(2)
        val other = StickerCenterDetection(emptyList(), 480, 600, 1.0, 0.4, 0, 8)
        w.frame(3, detected = other, crop = 600)
        w.frame(4, detected = other, crop = 600)
        val m = ContinuousSessionJson.readManifest(File(w.save(partial, tags, null).path))
        assertEquals(0.4, m.geometry?.letterboxScale)
        assertEquals(32, m.geometry?.padLeft)
        assertEquals(640, m.geometry?.cropRight)
        assertEquals(listOf(3), m.geometryChanges.map { it.frameIndex })
        assertEquals(
            600,
            m.geometryChanges
                .single()
                .geometry.cropRight,
        )
        assertEquals(
            8,
            m.geometryChanges
                .single()
                .geometry.padTop,
        )
    }

    @Test
    fun `latency statistics, dropped-frame estimate, session ids and baselines`() {
        assertEquals(2.0, SessionLatency.p50(listOf(3.0, 1.0, 2.0)))
        assertEquals(19.0, SessionLatency.p95((1..20).map { it.toDouble() }))
        assertNull(SessionLatency.p50(emptyList()))
        assertEquals(0, SessionLatency.droppedFrames(listOf(0, 33, 66, 99)))
        assertEquals(3, SessionLatency.droppedFrames(listOf(0, 33, 66, 165, 198, 264)))
        // W8: <yyyyMMdd-HHmmss>-<device>-<label>, sanitised to [a-z0-9-].
        assertEquals("20261126-091502-tb128xu-run-1", SessionFiles.sessionId(LocalDateTime.of(2026, 11, 26, 9, 15, 2), "TB128XU", "Run 1"))
        assertEquals("20261126-091502-sm-s721b", SessionFiles.sessionId(LocalDateTime.of(2026, 11, 26, 9, 15, 2), "SM-S721B", null))
        assertEquals(
            "20261126-091502-lenovo-tb128xu",
            SessionFiles.sessionId(LocalDateTime.of(2026, 11, 26, 9, 15, 2), "Lenovo TB128XU", "  "),
        )
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
    fun `the debug factory writes under files continuous-sessions, keeps baselines and writes labels beside predictions`() {
        val sessions = SessionFiles.root(root)
        assertEquals("continuous-sessions", sessions.name)
        val factory = DebugContinuousSessionWriterFactory({ sessions }, "b".repeat(40), "2.3.2")
        val session = factory.create(setup)
        session.onFrame(0, emptyList(), snapshot(0), detection, false)
        session.frameDone(1, 1, 90, 640, 480, 0, 0, 640, 480)
        val saved = session.save(partial, tags, null)
        assertEquals(File(sessions, setup.sessionId).path, saved.path)
        assertEquals("b".repeat(40), ContinuousSessionJson.readManifest(File(saved.path)).buildCommit)
        assertNull(factory.baseline("TB128XU"))
        assertEquals(20.0, factory.saveBaseline("TB128XU", listOf(20.0))?.p50Ms)
        assertEquals(20.0, factory.baseline("TB128XU")?.p50Ms)
        val labels =
            ContinuousSessionLabels.of(
                List(54) {
                    dev.cubentia.domain.recognition
                        .StickerTruth(null, null, false)
                },
                null,
            )
        factory.writeLabels(saved, labels)
        assertEquals(labels, ContinuousSessionJson.readLabels(File(saved.path)))
        // labels.json sits beside the predictions, never inside them.
        assertEquals(1, ContinuousSessionJson.readSnapshots(File(saved.path)).size)
    }

    @Test
    fun `a frame write failure with a successful rename is saved as a defective session`() {
        val w = writer(imageEvery = 1)
        // A directory where frame 1's PNG goes: that write throws after the streams are open.
        File(w.tempDir, ContinuousSessionFormat.imageName(1)).mkdirs()
        repeat(3) {
            w.frame(it)
            w.awaitWritten()
        }
        assertFalse(w.status.recording)
        val saved = w.save(partial, tags, null)
        val m = ContinuousSessionJson.readManifest(File(saved.path))
        assertTrue(m.defective)
        assertTrue(m.defectReasons.any { it.startsWith("WRITE_FAILED") }, m.defectReasons.toString())
        assertEquals(1, m.frameCount)
    }

    @Test
    fun `a write failure stops the session without throwing on the writer thread`() {
        root.mkdirs()
        // A file where the temp directory should be: every write fails.
        File(root, ContinuousSessionFormat.TEMP_PREFIX + setup.sessionId).writeText("blocker")
        val w = writer()
        w.frame(0)
        w.awaitWritten()
        assertFalse(w.status.recording)
        org.junit.jupiter.api.Assertions
            .assertThrows(Exception::class.java) { w.save(partial, tags, null) }
    }

    @Test
    fun `the PNG encoder reuses its buffers across frames and stays lossless`() {
        val encoder = RgbPng(256, 256)
        val first = encoder.encode(image)
        val again = encoder.encode(image)
        assertEquals(first, again)
        val bytes =
            java.io
                .ByteArrayOutputStream()
                .also(encoder::writeTo)
                .toByteArray()
        assertEquals(first, bytes.size)
        val png = ImageIO.read(bytes.inputStream())
        assertEquals(image[0].toInt() and 0xff, png.getRGB(0, 0) shr 16 and 0xff)
        encoder.close()
        assertTrue(RgbPng.encode(image, 256, 256).contentEquals(bytes))
        assertEquals(5, ContinuousSessionWriter.utf8Length("a·bc") - 0)
    }
}
