package dev.cubentia.app.camera.recorder.session

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cubentia.app.camera.ContinuousCaptureStatus
import dev.cubentia.app.camera.ContinuousFrameGeometry
import dev.cubentia.app.camera.ContinuousLatencyBaseline
import dev.cubentia.app.camera.ContinuousSessionCapture
import dev.cubentia.app.camera.ContinuousSessionSaved
import dev.cubentia.app.camera.ContinuousSessionSetup
import dev.cubentia.app.camera.ContinuousSessionWriterFactory
import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.QuadObservation
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionFormat
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionFrame
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionJson
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionManifest
import dev.cubentia.domain.recognition.continuous.session.SessionDevice
import dev.cubentia.domain.recognition.continuous.session.SessionGeometry
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
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Story 10.34 (AD-9 Frames): the debug recorder's continuous session writer. On the analyzer thread it only copies the
 * frame's neutral data (and, every [imageEvery]th frame, the detector's model input into a pooled buffer) and offers it
 * to a bounded queue of [queueCapacity] frames; a full queue skips the frame and counts it in `captureSkips`. One writer
 * executor encodes JSON lines and PNGs into `<root>/.tmp-<sessionId>/`; [save] drains it, writes `manifest.json` last
 * and renames the directory to `<root>/<sessionId>/`, the only step that makes the group complete.
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
    private val baselineP50Ms: () -> Double? = { null },
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "cubentia-session-writer") },
) : ContinuousSessionCapture {
    val tempDir = File(root, ContinuousSessionFormat.TEMP_PREFIX + setup.sessionId)
    val finalDir = File(root, setup.sessionId)

    private val inputSize = setup.model?.inputSize ?: ContinuousSessionFormat.MODEL_INPUT_SIZE
    private val inputBytes = inputSize * inputSize * 3

    private class Pending(
        val frameIndex: Int,
        val observations: List<QuadObservation>,
        val snapshot: ContinuousScanSnapshot,
        val detection: StickerCenterDetection,
        val image: ByteArray?,
    ) {
        var timestampMs = 0L
        var analyzerMs = 0.0
    }

    private val queue = ArrayBlockingQueue<Pending>(queueCapacity)

    /** Model-input buffers: one per queue slot, the staged frame and the frame being encoded; allocated on demand, reused. */
    private val pool = ArrayBlockingQueue<ByteArray>(queueCapacity + 2)
    private var poolAllocated = 0

    @Volatile private var recording = true

    @Volatile private var stopReason: SessionStopReason? = null

    @Volatile private var lastSnapshot: ContinuousScanSnapshot? = null

    @Volatile private var geometry: SessionGeometry? = null
    private var staged: Pending? = null
    private val accepted = AtomicInteger(0)
    private val skips = AtomicInteger(0)
    private val bytes = AtomicLong(0)

    // Writer thread only.
    private var framesOut: BufferedWriter? = null
    private var snapshotsOut: BufferedWriter? = null
    private var written = 0
    private var withObservations = 0
    private var imageFrames = 0
    private val timestamps = mutableListOf<Long>()
    private val analyzerTimes = mutableListOf<Double>()
    private var finished = false

    /** The snapshot of the last frame actually written (the manifest outcome is derived from it). */
    private var lastWritten: ContinuousScanSnapshot? = null

    /** Set once the byte cap or a write failure ends writing: every frame still queued is dropped, never written. */
    @Volatile private var dropRest = false

    @Volatile private var writeFailure: String? = null

    override val status: ContinuousCaptureStatus
        get() =
            ContinuousCaptureStatus(
                recording = recording,
                frames = accepted.get(),
                bytes = bytes.get(),
                skips = skips.get(),
                sidesMapped = lastSnapshot?.mappedFacesCount ?: 0,
                stopReason = stopReason,
            )

    override fun wantsModelInput(frameIndex: Int): Boolean = recording && imageEvery > 0 && frameIndex % imageEvery == 0

    override fun onFrame(
        frameIndex: Int,
        observations: List<QuadObservation>,
        snapshot: ContinuousScanSnapshot,
        detection: StickerCenterDetection,
        modelInput: ByteArray?,
    ) {
        staged?.image?.let(::release)
        staged = null
        if (!recording) return
        lastSnapshot = snapshot
        val image =
            modelInput?.takeIf { it.size >= inputBytes }?.let { source ->
                borrow()?.also { System.arraycopy(source, 0, it, 0, inputBytes) }
            }
        staged = Pending(frameIndex, observations, snapshot, detection, image)
    }

    override fun frameDone(
        timestampMs: Long,
        analyzerMs: Double,
        geometry: ContinuousFrameGeometry,
    ) {
        val pending = staged ?: return
        staged = null
        if (!recording) return pending.image.let { if (it != null) release(it) }
        val known = this.geometry
        if (known == null || (known.letterboxScale <= 0.0 && pending.detection.inputScale > 0.0)) {
            this.geometry = sessionGeometry(geometry, pending.detection)
        }
        pending.timestampMs = timestampMs
        pending.analyzerMs = analyzerMs
        if (!queue.offer(pending)) {
            skips.incrementAndGet()
            pending.image?.let(::release)
            return
        }
        val count = accepted.incrementAndGet()
        try {
            executor.execute(::drainOne)
        } catch (_: RejectedExecutionException) {
            // Saved or discarded meanwhile: the frame is not captured.
            if (queue.remove(pending)) pending.image?.let(::release)
            accepted.decrementAndGet()
            return
        }
        if (count >= frameCap) stopWith(SessionStopReason.FRAME_CAP)
    }

    override fun stop() = stopWith(SessionStopReason.OPERATOR)

    private fun stopWith(reason: SessionStopReason) {
        if (stopReason == null) stopReason = reason
        recording = false
    }

    override fun save(export: ContinuousScanExport): ContinuousSessionSaved {
        stop()
        try {
            return executor.submit(Callable { finish(export) }).get()
        } finally {
            executor.shutdown()
        }
    }

    override fun discard(): Boolean {
        stop()
        val deleted =
            try {
                executor
                    .submit(
                        Callable {
                            finished = true
                            closeStreams()
                            !tempDir.exists() || tempDir.deleteRecursively()
                        },
                    ).get()
            } finally {
                executor.shutdown()
            }
        if (!deleted) runCatching { Log.w(TAG, "Could not delete ${tempDir.path}; it stays a .tmp- dir, never read as complete") }
        return deleted
    }

    private fun borrow(): ByteArray? =
        pool.poll() ?: if (poolAllocated < queueCapacity + 2) {
            poolAllocated++
            ByteArray(inputBytes)
        } else {
            null
        }

    private fun release(buffer: ByteArray) {
        pool.offer(buffer)
    }

    private fun sessionGeometry(
        frame: ContinuousFrameGeometry,
        detection: StickerCenterDetection,
    ): SessionGeometry {
        val quarter = Math.floorMod(frame.rotationDegrees, 180) == 90
        val cropWidth = frame.cropRight - frame.cropLeft
        val cropHeight = frame.cropBottom - frame.cropTop
        return SessionGeometry(
            rotationDegrees = frame.rotationDegrees,
            analysisWidth = frame.analysisWidth,
            analysisHeight = frame.analysisHeight,
            cropLeft = frame.cropLeft,
            cropTop = frame.cropTop,
            cropRight = frame.cropRight,
            cropBottom = frame.cropBottom,
            uprightWidth = detection.uprightWidth.takeIf { it > 0 } ?: if (quarter) cropHeight else cropWidth,
            uprightHeight = detection.uprightHeight.takeIf { it > 0 } ?: if (quarter) cropWidth else cropHeight,
            inputSize = inputSize,
            letterboxScale = detection.inputScale,
            padLeft = detection.inputPadLeft,
            padTop = detection.inputPadTop,
        )
    }

    /** Writer thread: one queued frame into the temp directory, unless the byte cap would be passed. */
    private fun drainOne() {
        val pending = queue.poll() ?: return
        if (finished || dropRest) return pending.image.let { if (it != null) release(it) }
        val encoded =
            try {
                encode(pending)
            } catch (e: Exception) {
                fail(e)
                return
            } finally {
                pending.image?.let(::release)
            }
        val frame = encoded.frame
        val frameLine = encoded.frameLine
        val snapshotLine = encoded.snapshotLine
        val png = encoded.png
        val size = frameLine.toByteArray().size + snapshotLine.toByteArray().size + (png?.size ?: 0)
        if (bytes.get() + size > sizeCapBytes - MANIFEST_RESERVE_BYTES) {
            dropRest = true
            // The cap ended the data even when Save's operator stop came first: record it as the stop reason.
            stopReason = SessionStopReason.SIZE_CAP
            recording = false
            return
        }
        try {
            open()
            if (png != null && frame.image != null) {
                File(tempDir, frame.image).writeBytes(png)
                imageFrames++
            }
            checkNotNull(framesOut).write(frameLine)
            checkNotNull(snapshotsOut).write(snapshotLine)
        } catch (e: Exception) {
            fail(e)
            return
        }
        lastWritten = pending.snapshot
        bytes.addAndGet(size.toLong())
        written++
        if (pending.observations.isNotEmpty()) withObservations++
        timestamps += pending.timestampMs
        analyzerTimes += pending.analyzerMs
    }

    private class Encoded(
        val frame: ContinuousSessionFrame,
        val frameLine: String,
        val snapshotLine: String,
        val png: ByteArray?,
    )

    /** Writer thread: the frame's JSON lines and PNG; may throw (e.g. a NaN the JSON encoder rejects). */
    private fun encode(pending: Pending): Encoded {
        val png = pending.image?.let { RgbPng.encode(it, inputSize, inputSize) }
        val scale = pending.detection.inputScale
        val frame =
            ContinuousSessionFrame(
                frameIndex = pending.frameIndex,
                timestampMs = pending.timestampMs,
                analyzerMs = pending.analyzerMs,
                inferenceMs = pending.detection.inferenceMs,
                observations = pending.observations.map(SessionObservation::of),
                peaks =
                    pending.detection.peaks.map { peak ->
                        if (scale > 0.0) {
                            SessionPeak(
                                peak.x * scale + pending.detection.inputPadLeft,
                                peak.y * scale + pending.detection.inputPadTop,
                                peak.score,
                            )
                        } else {
                            SessionPeak(peak.x, peak.y, peak.score)
                        }
                    },
                image = png?.let { ContinuousSessionFormat.imageName(pending.frameIndex) },
            )
        val frameLine = ContinuousSessionJson.encodeFrame(frame) + "\n"
        val snapshotLine = ContinuousSessionJson.encodeSnapshot(SessionSnapshot.of(pending.snapshot)) + "\n"
        return Encoded(frame, frameLine, snapshotLine, png)
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
    private fun finish(export: ContinuousScanExport): ContinuousSessionSaved {
        open()
        // A failed flush (e.g. disk full) leaves truncated JSON lines: record it so the manifest marks the session defective.
        closeStreams()?.let { if (writeFailure == null) writeFailure = it.message ?: it.toString() }
        finished = true
        val p50 = SessionLatency.p50(analyzerTimes)
        val baseline = baselineP50Ms()
        val reasons = SessionLatency.defectReasons(skips.get(), p50, baseline) + listOfNotNull(writeFailure?.let { "WRITE_FAILED: $it" })
        val outcome = SessionOutcome.derive(export, lastWritten)
        val first = timestamps.firstOrNull()
        val last = timestamps.lastOrNull()
        val model = setup.model
        val manifest =
            ContinuousSessionManifest(
                sessionId = setup.sessionId,
                createdAt = createdAt,
                device = SessionDevice(setup.deviceModel, setup.deviceManufacturer),
                cube = setup.cube,
                lighting = setup.lighting,
                partition = setup.partition,
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
                latencyP50Ms = p50,
                latencyP95Ms = SessionLatency.p95(analyzerTimes),
                latencyBaselineP50Ms = baseline,
                defective = reasons.isNotEmpty(),
                defectReasons = reasons,
                bytes = bytes.get(),
            )
        File(tempDir, ContinuousSessionFormat.MANIFEST).writeText(ContinuousSessionJson.encodeManifest(manifest) + "\n")
        check(!finalDir.exists()) { "${finalDir.path} already exists" }
        check(tempDir.renameTo(finalDir)) { "Could not rename ${tempDir.path} to ${finalDir.path}" }
        return ContinuousSessionSaved(setup.sessionId, outcome, reasons, finalDir.path)
    }

    companion object {
        const val TAG = "CubentiaSession"

        /** Room left under the byte cap for `manifest.json` (and `labels.json`). */
        const val MANIFEST_RESERVE_BYTES = 32_000L
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

/** Session ids and per-device baselines under `<externalFiles>/Fixtures/continuous/`. */
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

    /** The recorder's session root: `<externalFiles>/Fixtures/continuous`. */
    fun root(externalFiles: File): File = File(externalFiles, "Fixtures/continuous")

    private fun safe(text: String) = text.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")

    /** `yyyyMMdd-HHmmss-<deviceModel>[-label]`, file-name safe. */
    fun sessionId(
        now: LocalDateTime,
        deviceModel: String,
        label: String?,
    ): String {
        val stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val suffix =
            label
                ?.filter { it.isLetterOrDigit() || it == '-' }
                ?.takeIf { it.isNotEmpty() }
                ?.let { "-$it" }
                .orEmpty()
        return "$stamp-${safe(deviceModel)}$suffix"
    }

    fun baselineFile(
        root: File,
        deviceModel: String,
    ): File = File(root, ".baseline-${safe(deviceModel)}.json")

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
        val temp = File(root, target.name + ".tmp")
        temp.writeText(
            json.encodeToString(SessionBaselineFile.serializer(), SessionBaselineFile(deviceModel, analyzerMs.size, p50, p95, recordedAt)) +
                "\n",
        )
        check(temp.renameTo(target) || (target.delete() && temp.renameTo(target))) { "Could not write ${target.path}" }
        return ContinuousLatencyBaseline(deviceModel, analyzerMs.size, p50, p95)
    }
}

/**
 * The debug binding of [ContinuousSessionWriterFactory] (see `ContinuousSessionWriterModule`): sessions under
 * `<externalFiles>/Fixtures/continuous/`, stamped with this build's commit and LiteRT version.
 */
@Singleton
internal class DebugContinuousSessionWriterFactory(
    private val root: () -> File?,
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
    ) : this({ context.getExternalFilesDir(null)?.let(SessionFiles::root) }, info["commit"] ?: "unknown", info["litert"] ?: "unknown")

    private fun rootDir(): File = checkNotNull(root()) { "No external files directory" }

    override fun create(setup: ContinuousSessionSetup): ContinuousSessionCapture {
        val dir = rootDir().apply { mkdirs() }
        return ContinuousSessionWriter(
            root = dir,
            setup = setup,
            buildCommit = buildCommit,
            litertVersion = litertVersion,
            baselineP50Ms = { SessionFiles.readBaseline(dir, setup.deviceModel)?.p50Ms },
        )
    }

    override fun saveBaseline(
        deviceModel: String,
        analyzerMs: List<Double>,
    ): ContinuousLatencyBaseline? = SessionFiles.writeBaseline(rootDir(), deviceModel, analyzerMs)
}
