package dev.cubentia.app.camera.session

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cubentia.app.camera.ContinuousCaptureLatency
import dev.cubentia.app.camera.ContinuousCaptureStatus
import dev.cubentia.app.camera.ContinuousLatencyBaseline
import dev.cubentia.app.camera.ContinuousSessionCapture
import dev.cubentia.app.camera.ContinuousSessionSaved
import dev.cubentia.app.camera.ContinuousSessionSetup
import dev.cubentia.app.camera.ContinuousSessionTags
import dev.cubentia.app.camera.ContinuousSessionWriterFactory
import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.QuadObservation
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionFormat
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionFrame
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionJson
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionLabels
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionManifest
import dev.cubentia.domain.recognition.continuous.session.SessionDevice
import dev.cubentia.domain.recognition.continuous.session.SessionGeometry
import dev.cubentia.domain.recognition.continuous.session.SessionGeometryChange
import dev.cubentia.domain.recognition.continuous.session.SessionModel
import dev.cubentia.domain.recognition.continuous.session.SessionObservation
import dev.cubentia.domain.recognition.continuous.session.SessionOutcome
import dev.cubentia.domain.recognition.continuous.session.SessionPeak
import dev.cubentia.domain.recognition.continuous.session.SessionSnapshot
import dev.cubentia.domain.recognition.continuous.session.SessionStopReason
import dev.cubentia.domain.recognition.continuous.session.SessionTiming
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Story 10.34 (AD-9 Frames): the debug Quick Scan session recording's writer.
 *
 * **Analyzer thread** ([ContinuousFrameTap]): the frame is staged into one of [queueCapacity] slots preallocated here (each
 * with its own model-input buffer the pipeline fills directly); a slot holds references to the frame's immutable
 * observations, snapshot and detection plus primitives, and is offered to a bounded [ArrayBlockingQueue]. Nothing is
 * allocated per frame and no task is submitted: a frame that finds no free slot or a full queue is not captured and is
 * counted in `captureSkips`. Counters are atomics the UI polls.
 *
 * **Writer thread** (one, long-lived): takes slots, encodes the JSON lines and every [imageEvery]th frame's model input as a
 * lossless PNG (one reused [RgbPng]/`Deflater`; bytes counted without copying), streams them into
 * `<root>/.tmp-<sessionId>/`, then frees the slot. [save] lets it drain, writes `manifest.json` last and renames the
 * directory to `<root>/<sessionId>/`, the only step that makes the group complete.
 */
internal class ContinuousSessionWriter(
    private val root: File,
    private val setup: ContinuousSessionSetup,
    private val buildCommit: String,
    private val litertVersion: String,
    private val createdAt: String = Instant.now().toString(),
    private val queueCapacity: Int = ContinuousSessionFormat.QUEUE_FRAMES,
    private val frameCap: Int = ContinuousSessionFormat.FRAME_CAP,
    private val sizeCapBytes: Long = ContinuousSessionFormat.SIZE_CAP_BYTES,
    private val imageEvery: Int = ContinuousSessionFormat.IMAGE_EVERY,
    threadFactory: ThreadFactory = ThreadFactory { Thread(it, "cubentia-session-writer") },
) : ContinuousSessionCapture {
    val tempDir = File(root, ContinuousSessionFormat.TEMP_PREFIX + setup.sessionId)
    val finalDir = File(root, setup.sessionId)

    private val inputSize = setup.model?.inputSize ?: ContinuousSessionFormat.MODEL_INPUT_SIZE
    private val inputBytes = inputSize * inputSize * 3

    /** One staged or queued frame; reused for the whole session. */
    private class Slot(
        inputBytes: Int,
    ) {
        val image = ByteArray(inputBytes)
        var frameIndex = 0
        var skipped = false
        var hasImage = false
        var observations: List<QuadObservation> = emptyList()
        var snapshot: ContinuousScanSnapshot? = null
        var detection: StickerCenterDetection = StickerCenterDetection.EMPTY
        var sensorTimestampNs = 0L
        var analyzerNs = 0L
        var rotation = 0
        var imageWidth = 0
        var imageHeight = 0
        var cropLeft = 0
        var cropTop = 0
        var cropRight = 0
        var cropBottom = 0

        /** Drops the frame's references once written (the slot keeps no frame data between uses). */
        fun clear() {
            observations = emptyList()
            snapshot = null
            detection = StickerCenterDetection.EMPTY
        }
    }

    /** Slots allocated: [queueCapacity], all in the constructor (the zero-allocation test reads it). */
    internal var slotAllocations = 0
        private set

    private val free = ArrayBlockingQueue<Slot>(queueCapacity)
    private val queue = ArrayBlockingQueue<Slot>(queueCapacity)

    init {
        repeat(queueCapacity) {
            free.offer(Slot(inputBytes))
            slotAllocations++
        }
    }

    @Volatile private var capturing = true

    /** The last snapshot the analyzer staged (a reference store, no copy): the sheet derives its outcome from it. */
    @Volatile var lastSnapshot: ContinuousScanSnapshot? = null
        private set

    @Volatile private var stopReason: SessionStopReason? = null
    private val accepted = AtomicInteger(0)
    private val skips = AtomicInteger(0)
    private val bytes = AtomicLong(0)

    /** Frames offered but not yet written (or dropped) by the writer thread. */
    private val inFlight = AtomicInteger(0)

    // Analyzer thread only.
    private var staged: Slot? = null
    private var stageFailed = false

    // Writer thread only.
    private val png = RgbPng(inputSize, inputSize)
    private var framesOut: BufferedWriter? = null
    private var snapshotsOut: BufferedWriter? = null
    private var written = 0
    private var withObservations = 0
    private var imageFrames = 0
    private var firstTimestampMs: Long? = null
    private var lastTimestampMs: Long? = null
    private val timestamps = mutableListOf<Long>()
    private var geometry: SessionGeometry? = null
    private val geometryChanges = mutableListOf<SessionGeometryChange>()
    private var lastWritten: ContinuousScanSnapshot? = null
    private var finished = false

    /** Analyzer times of the written frames; the writer adds, the UI reads [latency] (guarded by itself). */
    private val analyzerTimes = mutableListOf<Double>()

    /** Set once the byte cap or a write failure ends writing: every frame still queued is dropped, never written. */
    @Volatile private var dropRest = false

    @Volatile private var writeFailure: String? = null

    /** A save or discard for the writer thread, run once the queue is empty. */
    private class Command(
        val run: () -> Any,
    ) {
        val result = CompletableFuture<Any>()
    }

    @Volatile private var command: Command? = null

    private val thread: Thread = threadFactory.newThread(::runWriter).apply { isDaemon = true }

    init {
        thread.start()
    }

    override val recording: Boolean get() = capturing

    override val status: ContinuousCaptureStatus
        get() = ContinuousCaptureStatus(capturing, accepted.get(), bytes.get(), skips.get(), stopReason)

    override fun latency(): ContinuousCaptureLatency =
        synchronized(analyzerTimes) {
            ContinuousCaptureLatency(SessionLatency.p50(analyzerTimes), SessionLatency.p95(analyzerTimes))
        }

    // ---- Analyzer thread: copy into a preallocated slot and offer it; nothing else. ----

    private fun imageFrame(frameIndex: Int) = imageEvery > 0 && frameIndex % imageEvery == 0

    /** The slot of the frame in progress: taken from the free slots once per frame; null (a skip) when none is free. */
    private fun stage(): Slot? {
        staged?.let { return it }
        if (stageFailed) return null
        val slot = free.poll()
        if (slot == null) stageFailed = true else staged = slot
        return slot
    }

    override fun modelInputBuffer(frameIndex: Int): ByteArray? = if (capturing && imageFrame(frameIndex)) stage()?.image else null

    override fun onFrame(
        frameIndex: Int,
        observations: List<QuadObservation>,
        snapshot: ContinuousScanSnapshot,
        detection: StickerCenterDetection,
        modelInputFilled: Boolean,
    ) {
        if (!capturing) return
        lastSnapshot = snapshot
        val slot = stage() ?: return
        slot.frameIndex = frameIndex
        slot.skipped = false
        slot.hasImage = modelInputFilled && imageFrame(frameIndex)
        slot.observations = observations
        slot.snapshot = snapshot
        slot.detection = detection
    }

    override fun onSkippedFrame(frameIndex: Int) {
        if (!capturing) return
        val slot = stage() ?: return
        slot.frameIndex = frameIndex
        slot.skipped = true
        slot.hasImage = false
        slot.observations = emptyList()
        slot.snapshot = null
        slot.detection = StickerCenterDetection.EMPTY
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
        val slot = staged
        val failed = stageFailed
        staged = null
        stageFailed = false
        if (!capturing) {
            slot?.let(::release)
            return
        }
        if (slot == null) {
            // Every slot is staged, queued or being written: the frame is not captured, and it is counted.
            if (failed) skips.incrementAndGet()
            return
        }
        slot.sensorTimestampNs = sensorTimestampNs
        slot.analyzerNs = analyzerNs
        slot.rotation = rotation
        slot.imageWidth = imageWidth
        slot.imageHeight = imageHeight
        slot.cropLeft = cropLeft
        slot.cropTop = cropTop
        slot.cropRight = cropRight
        slot.cropBottom = cropBottom
        inFlight.incrementAndGet()
        if (!queue.offer(slot)) {
            inFlight.decrementAndGet()
            release(slot)
            skips.incrementAndGet()
            return
        }
        if (accepted.incrementAndGet() >= frameCap) stopWith(SessionStopReason.FRAME_CAP)
    }

    private fun release(slot: Slot) {
        slot.clear()
        free.offer(slot)
    }

    // ---- Main / io thread. ----

    override fun stop(reason: SessionStopReason) = stopWith(reason)

    private fun stopWith(reason: SessionStopReason) {
        if (stopReason == null) stopReason = reason
        capturing = false
    }

    override fun save(
        export: ContinuousScanExport,
        tags: ContinuousSessionTags,
        baselineP50Ms: Double?,
    ): ContinuousSessionSaved {
        stopWith(SessionStopReason.OPERATOR)
        return run(Command { finish(export, tags, baselineP50Ms) }) as ContinuousSessionSaved
    }

    override fun discard(): Boolean {
        stopWith(SessionStopReason.OPERATOR)
        val deleted =
            run(
                Command {
                    finished = true
                    closeStreams()
                    !tempDir.exists() || tempDir.deleteRecursively()
                },
            ) as Boolean
        if (!deleted) runCatching { Log.w(TAG, "Could not delete ${tempDir.path}; it stays a .tmp- dir, never read as complete") }
        return deleted
    }

    /** Runs [cmd] on the writer thread after every queued frame; inline when that thread is gone (it was stopped). */
    private fun run(cmd: Command): Any {
        check(command == null) { "Session ${setup.sessionId} already saved or discarded" }
        if (!thread.isAlive) {
            queue.clear()
            return cmd.run()
        }
        command = cmd
        try {
            return cmd.result.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            // The writer thread has returned: a retried Save or a Discard then runs inline instead of being refused.
            command = null
            throw e.cause ?: e
        }
    }

    /** Test only: blocks until every offered frame has been written or dropped. */
    internal fun awaitWritten() {
        while (inFlight.get() > 0 && thread.isAlive) Thread.sleep(1)
    }

    /** Test only: kills the writer thread as a process death would, before any save (the temp dir stays). */
    internal fun kill() {
        thread.interrupt()
        thread.join()
    }

    // ---- Writer thread. ----

    private fun runWriter() {
        try {
            while (true) {
                val slot = queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                if (slot != null) {
                    try {
                        // A frame the pipeline processed while recording but the writer could not keep is a skip, never lost silently.
                        if (!finished && (dropRest || !write(slot))) skips.incrementAndGet()
                    } finally {
                        release(slot)
                        inFlight.decrementAndGet()
                    }
                    continue
                }
                val cmd = command ?: continue
                if (queue.isNotEmpty()) continue
                try {
                    cmd.result.complete(cmd.run())
                } catch (e: Throwable) {
                    cmd.result.completeExceptionally(e)
                }
                return
            }
        } catch (_: InterruptedException) {
            // Killed: whatever was written stays in the temp dir, never read as a session.
        } finally {
            png.close()
        }
    }

    /** One slot into the temp directory, unless the byte cap would be passed; false when it was not written. Never throws. */
    private fun write(slot: Slot): Boolean {
        val frameLine: String
        val snapshotLine: String?
        val pngBytes: Int
        val imageName: String?
        try {
            pngBytes = if (slot.hasImage) png.encode(slot.image) else 0
            imageName = if (slot.hasImage) ContinuousSessionFormat.imageName(slot.frameIndex) else null
            frameLine = ContinuousSessionJson.encodeFrame(frameOf(slot, imageName)) + "\n"
            snapshotLine = slot.snapshot?.let { ContinuousSessionJson.encodeSnapshot(SessionSnapshot.of(it)) + "\n" }
        } catch (e: Exception) {
            fail(e)
            return false
        }
        val size = utf8Length(frameLine) + (snapshotLine?.let(::utf8Length) ?: 0) + pngBytes
        if (bytes.get() + size > sizeCapBytes - MANIFEST_RESERVE_BYTES) {
            dropRest = true
            // The cap ended the data even when an operator stop came first: record it as the stop reason.
            stopReason = SessionStopReason.SIZE_CAP
            capturing = false
            return false
        }
        try {
            open()
            if (imageName != null) {
                File(tempDir, imageName).outputStream().use(png::writeTo)
                imageFrames++
            }
            checkNotNull(framesOut).write(frameLine)
            snapshotLine?.let { checkNotNull(snapshotsOut).write(it) }
        } catch (e: Exception) {
            fail(e)
            return false
        }
        bytes.addAndGet(size.toLong())
        written++
        if (slot.observations.isNotEmpty()) withObservations++
        slot.snapshot?.let { lastWritten = it }
        val timestampMs = slot.sensorTimestampNs / NANOS_PER_MS
        if (firstTimestampMs == null) firstTimestampMs = timestampMs
        lastTimestampMs = timestampMs
        timestamps += timestampMs
        synchronized(analyzerTimes) { analyzerTimes += slot.analyzerNs / NANOS_PER_MS_DOUBLE }
        if (!slot.skipped) track(slot)
        return true
    }

    private fun frameOf(
        slot: Slot,
        imageName: String?,
    ): ContinuousSessionFrame {
        val detection = slot.detection
        val scale = detection.inputScale
        return ContinuousSessionFrame(
            frameIndex = slot.frameIndex,
            timestampMs = slot.sensorTimestampNs / NANOS_PER_MS,
            analyzerMs = slot.analyzerNs / NANOS_PER_MS_DOUBLE,
            inferenceMs = detection.inferenceMs,
            observations = slot.observations.map(SessionObservation::of),
            peaks =
                detection.peaks.map { peak ->
                    // Peaks in model-input pixels: upright × scale + pad (upright pixels when the letterbox is unknown).
                    if (scale > 0.0) {
                        SessionPeak(peak.x * scale + detection.inputPadLeft, peak.y * scale + detection.inputPadTop, peak.score)
                    } else {
                        SessionPeak(peak.x, peak.y, peak.score)
                    }
                },
            image = imageName,
            skipped = slot.skipped,
        )
    }

    /**
     * The session geometry from the frames the detector read: the first one sets it (an earlier frame without a
     * letterbox only stands in until one has it), and a later frame whose geometry differs appends a [geometryChanges]
     * entry (e.g. after a rotation). The crop and rotation are the analyzer's for that frame; the upright size and the
     * letterbox are the params the detector used.
     */
    private fun track(slot: Slot) {
        val detection = slot.detection
        val quarter = Math.floorMod(slot.rotation, QUARTER_TURN * 2) == QUARTER_TURN
        val cropWidth = slot.cropRight - slot.cropLeft
        val cropHeight = slot.cropBottom - slot.cropTop
        val frame =
            SessionGeometry(
                rotationDegrees = slot.rotation,
                analysisWidth = slot.imageWidth,
                analysisHeight = slot.imageHeight,
                cropLeft = slot.cropLeft,
                cropTop = slot.cropTop,
                cropRight = slot.cropRight,
                cropBottom = slot.cropBottom,
                uprightWidth = detection.uprightWidth.takeIf { it > 0 } ?: if (quarter) cropHeight else cropWidth,
                uprightHeight = detection.uprightHeight.takeIf { it > 0 } ?: if (quarter) cropWidth else cropHeight,
                inputSize = inputSize,
                letterboxScale = detection.inputScale,
                padLeft = detection.inputPadLeft,
                padTop = detection.inputPadTop,
            )
        val known = geometry
        val current = geometryChanges.lastOrNull()?.geometry ?: known
        when {
            known == null -> geometry = frame
            known.letterboxScale <= 0.0 && geometryChanges.isEmpty() -> if (frame.letterboxScale > 0.0) geometry = frame
            frame.letterboxScale > 0.0 && frame != current -> geometryChanges += SessionGeometryChange(slot.frameIndex, frame)
        }
    }

    /** Never throw on the writer thread: stop, keep what was written, and mark the session defective. */
    private fun fail(e: Exception) {
        writeFailure = e.message ?: e.toString()
        dropRest = true
        stopWith(SessionStopReason.OPERATOR)
    }

    private fun open() {
        if (framesOut != null) return
        File(tempDir, ContinuousSessionFormat.IMAGES).mkdirs()
        framesOut = File(tempDir, ContinuousSessionFormat.FRAMES).bufferedWriter()
        snapshotsOut = File(tempDir, ContinuousSessionFormat.SNAPSHOTS).bufferedWriter()
    }

    /** Closes (flushing) both streams; returns the first close failure, if any. */
    private fun closeStreams(): Throwable? {
        val failures = listOf(runCatching { framesOut?.close() }, runCatching { snapshotsOut?.close() })
        framesOut = null
        snapshotsOut = null
        return failures.firstNotNullOfOrNull { it.exceptionOrNull() }
    }

    /** Writer thread, after every queued frame: closes the files, writes the manifest last and renames the group. */
    private fun finish(
        export: ContinuousScanExport,
        tags: ContinuousSessionTags,
        baseline: Double?,
    ): ContinuousSessionSaved {
        open()
        // A failed flush (e.g. disk full) leaves truncated JSON lines: record it so the manifest marks the session defective.
        closeStreams()?.let { if (writeFailure == null) writeFailure = it.message ?: it.toString() }
        finished = true
        val latency = latency()
        val reasons =
            SessionLatency.defectReasons(skips.get(), latency.p50Ms, baseline) + listOfNotNull(writeFailure?.let { "WRITE_FAILED: $it" })
        val outcome = SessionOutcome.derive(export, lastWritten)
        val first = firstTimestampMs
        val last = lastTimestampMs
        val model = setup.model
        val manifest =
            ContinuousSessionManifest(
                sessionId = setup.sessionId,
                createdAt = createdAt,
                device = SessionDevice(setup.deviceModel, setup.deviceManufacturer),
                cube = tags.cube,
                lighting = tags.lighting,
                partition = tags.partition,
                model =
                    SessionModel(
                        file = model?.file ?: "unknown",
                        sha256 = model?.sha256 ?: "unknown",
                        litertVersion = litertVersion,
                        delegate = model?.delegate ?: "unknown",
                        threads = model?.threads ?: 0,
                    ),
                buildCommit = buildCommit,
                frameCount = written,
                framesWithObservations = withObservations,
                imageEvery = imageEvery,
                imageFrames = imageFrames,
                geometry = geometry,
                geometryChanges = geometryChanges.toList(),
                timing =
                    SessionTiming(
                        firstTimestampMs = first,
                        lastTimestampMs = last,
                        durationMs = if (first != null && last != null) last - first else 0,
                        meanFrameIntervalMs =
                            if (timestamps.size > 1 && first != null &&
                                last != null
                            ) {
                                (last - first).toDouble() / (timestamps.size - 1)
                            } else {
                                null
                            },
                    ),
                captureSkips = skips.get(),
                droppedFrames = SessionLatency.droppedFrames(timestamps),
                stopReason = stopReason ?: SessionStopReason.OPERATOR,
                outcome = outcome,
                latencyP50Ms = latency.p50Ms,
                latencyP95Ms = latency.p95Ms,
                latencyBaselineP50Ms = baseline,
                defective = reasons.isNotEmpty(),
                defectReasons = reasons,
                bytes = bytes.get(),
            )
        File(tempDir, ContinuousSessionFormat.MANIFEST).writeText(ContinuousSessionJson.encodeManifest(manifest) + "\n")
        check(!finalDir.exists()) { "${finalDir.path} already exists" }
        check(tempDir.renameTo(finalDir)) { "Could not rename ${tempDir.path} to ${finalDir.path}" }
        return ContinuousSessionSaved(setup.sessionId, outcome, reasons, finalDir.path, netOf(export, lastWritten))
    }

    companion object {
        const val TAG = "QuickScanSession"

        /** Room left under the byte cap for `manifest.json` (and `labels.json`). */
        const val MANIFEST_RESERVE_BYTES = 32_000L

        private const val POLL_MS = 20L
        private const val QUARTER_TURN = 90
        private const val NANOS_PER_MS = 1_000_000L
        private const val NANOS_PER_MS_DOUBLE = 1_000_000.0

        /** UTF-8 byte length of [text] without encoding it. */
        fun utf8Length(text: String): Int {
            var count = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                count +=
                    when {
                        c.code < 0x80 -> {
                            1
                        }

                        c.code < 0x800 -> {
                            2
                        }

                        Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                            i++
                            4
                        }

                        else -> {
                            3
                        }
                    }
                i++
            }
            return count
        }

        /** A complete export's canonical net (Orientation.HOME, as replay compares labels with it), else [last]'s net. */
        fun netOf(
            export: ContinuousScanExport,
            last: ContinuousScanSnapshot?,
        ): List<Color?> =
            (export as? ContinuousScanExport.Complete)?.facelets?.colors
                ?: last?.let { snapshot -> Face.entries.flatMap { snapshot.net[it] ?: List(9) { null } } }
                ?: List(54) { null }
    }
}

/** `.baseline-<deviceModel>.json`: a device's no-capture analyzer latency, kept outside any session directory. */
@Serializable
internal data class SessionBaselineFile(
    val deviceModel: String,
    val frames: Int,
    val p50Ms: Double,
    val p95Ms: Double,
    val recordedAt: String,
)

/** Session ids, per-device baselines and labels under the app's `files/continuous-sessions/`. */
internal object SessionFiles {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    /** The generated debug asset `cubentia-build-info.properties` (`commit`, `litert`); empty when absent. */
    fun buildInfo(context: Context): Map<String, String> =
        runCatching {
            context.assets.open(BUILD_INFO_ASSET).use { input ->
                java.util.Properties().apply { load(input) }.let { props ->
                    props.stringPropertyNames().associateWith { props.getProperty(it) }
                }
            }
        }.getOrDefault(emptyMap())

    const val BUILD_INFO_ASSET = "cubentia-build-info.properties"

    /** App-specific storage only (AD-9): `files/continuous-sessions`, pulled with `tools/cube-session.sh session pull`. */
    const val DIR = "continuous-sessions"

    fun root(filesDir: File): File = File(filesDir, DIR)

    /** Lower-case `[a-z0-9-]`, runs of anything else as one `-`, no leading or trailing `-`. */
    fun sanitize(text: String): String =
        text
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    /** `<yyyyMMdd-HHmmss>-<device>[-<label>]`, sanitised to `[a-z0-9-]`. */
    fun sessionId(
        now: LocalDateTime,
        deviceModel: String,
        label: String?,
    ): String {
        val stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val device = sanitize(deviceModel).ifEmpty { "device" }
        val suffix =
            label
                ?.let(::sanitize)
                ?.takeIf { it.isNotEmpty() }
                ?.let { "-$it" }
                .orEmpty()
        return "$stamp-$device$suffix"
    }

    fun baselineFile(
        root: File,
        deviceModel: String,
    ): File = File(root, ".baseline-${sanitize(deviceModel)}.json")

    fun readBaseline(
        root: File,
        deviceModel: String,
    ): SessionBaselineFile? =
        baselineFile(root, deviceModel).takeIf { it.isFile }?.let { file ->
            runCatching { json.decodeFromString(SessionBaselineFile.serializer(), file.readText()) }.getOrNull()
        }

    fun writeBaseline(
        root: File,
        deviceModel: String,
        analyzerMs: List<Double>,
        recordedAt: String = Instant.now().toString(),
    ): ContinuousLatencyBaseline? {
        val p50 = SessionLatency.p50(analyzerMs) ?: return null
        val p95 = checkNotNull(SessionLatency.p95(analyzerMs))
        root.mkdirs()
        val target = baselineFile(root, deviceModel)
        replace(
            target,
            json.encodeToString(SessionBaselineFile.serializer(), SessionBaselineFile(deviceModel, analyzerMs.size, p50, p95, recordedAt)),
        )
        return ContinuousLatencyBaseline(deviceModel, analyzerMs.size, p50, p95)
    }

    /** Deletes every `.tmp-*` session dir under [root] (an interrupted write never becomes a session); returns how many. */
    fun deleteOrphans(root: File): Int =
        root
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith(ContinuousSessionFormat.TEMP_PREFIX) }
            .count { it.deleteRecursively() }

    /** `labels.json` beside the session's predictions, written to a temp name and renamed. */
    fun writeLabels(
        dir: File,
        labels: ContinuousSessionLabels,
    ) = replace(File(dir, ContinuousSessionFormat.LABELS), ContinuousSessionJson.encodeLabels(labels))

    /** Writes [text] to [target] through `<target>.tmp` and a rename, so a reader never sees half a file. */
    private fun replace(
        target: File,
        text: String,
    ) {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(text + "\n")
        // Rename over the old file first; delete it only when the platform cannot rename over a file.
        check(temp.renameTo(target) || (target.delete() && temp.renameTo(target))) { "Could not write ${target.path}" }
    }
}

/**
 * The debug binding of [ContinuousSessionWriterFactory] (see `QuickScanSessionModule`): sessions under the app's
 * `files/continuous-sessions/`, stamped with this build's commit and LiteRT version.
 */
@Singleton
internal class DebugContinuousSessionWriterFactory(
    private val root: () -> File,
    private val buildCommit: String,
    private val litertVersion: String,
) : ContinuousSessionWriterFactory {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context, SessionFiles.buildInfo(context))

    private constructor(
        context: Context,
        info: Map<String, String>,
    ) : this({ SessionFiles.root(context.filesDir) }, info["commit"] ?: "unknown", info["litert"] ?: "unknown")

    /** Whether this process already swept the orphaned temp dirs (the factory is a process singleton). */
    private val swept =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /** Off main (io): the first session of a process first deletes `.tmp-*` dirs a process death left (no live session yet). */
    override fun create(setup: ContinuousSessionSetup): ContinuousSessionCapture {
        val dir = root().apply { mkdirs() }
        if (swept.compareAndSet(false, true)) SessionFiles.deleteOrphans(dir)
        return ContinuousSessionWriter(
            root = dir,
            setup = setup,
            buildCommit = buildCommit,
            litertVersion = litertVersion,
        )
    }

    override fun baseline(deviceModel: String): ContinuousLatencyBaseline? =
        SessionFiles.readBaseline(root(), deviceModel)?.let { ContinuousLatencyBaseline(it.deviceModel, it.frames, it.p50Ms, it.p95Ms) }

    override fun saveBaseline(
        deviceModel: String,
        analyzerMs: List<Double>,
    ): ContinuousLatencyBaseline? = SessionFiles.writeBaseline(root(), deviceModel, analyzerMs)

    override fun writeLabels(
        saved: ContinuousSessionSaved,
        labels: ContinuousSessionLabels,
    ) = SessionFiles.writeLabels(File(saved.path), labels)
}
