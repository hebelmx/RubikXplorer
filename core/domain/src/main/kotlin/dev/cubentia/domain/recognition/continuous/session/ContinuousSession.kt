package dev.cubentia.domain.recognition.continuous.session

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.cube.ValidationResult
import dev.cubentia.domain.recognition.StickerTruth
import dev.cubentia.domain.recognition.StickerTruthCheck
import dev.cubentia.domain.recognition.StickerTruthDecision
import dev.cubentia.domain.recognition.TruthSource
import dev.cubentia.domain.recognition.continuous.ContinuousScanCue
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.FaceSlot
import dev.cubentia.domain.recognition.continuous.Point2D
import dev.cubentia.domain.recognition.continuous.Quad2D
import dev.cubentia.domain.recognition.continuous.QuadObservation
import dev.cubentia.domain.recognition.continuous.RgbColor
import dev.cubentia.domain.recognition.continuous.TrackedQuadOverlay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/*
 * Story 10.34 (AD-9 Frames, amended SCP 2026-11-25): the one neutral, versioned format of a recorded continuous Quick
 * Scan session. The debug recorder writes it, state replay (evaluation source set) and the golden test read it. Only
 * neutral data is here: observations, snapshots, peaks and geometry; pixels stay in the session's `images/` PNGs, which
 * only the instrumented image replay reads.
 *
 * A session directory holds `manifest.json` (written last), `frames.jsonl` (one [ContinuousSessionFrame] per line),
 * `snapshots.jsonl` (one [SessionSnapshot] per line, the mapper's prediction), optional `labels.json` (operator truth,
 * kept apart from predictions) and optional `images/frame-<idx>.png` model inputs.
 */

/** Which split a whole session directory (and everything derived from it) belongs to. */
@Serializable
enum class SessionPartition { TUNING, HELDOUT, UNASSIGNED }

/** The session's derived outcome (never chosen by the operator), see [SessionOutcome.derive]. */
@Serializable
enum class SessionOutcome {
    COMPLETE,
    PARTIAL,
    FAILED,
    LOST_TRACKING,
    ;

    companion object {
        /**
         * `COMPLETE` for [ContinuousScanExport.Complete], `FAILED` for [ContinuousScanExport.TwistDetected],
         * `LOST_TRACKING` for a partial export whose [last] snapshot tracks no quad (or no frame at all), else `PARTIAL`.
         */
        fun derive(
            export: ContinuousScanExport,
            last: ContinuousScanSnapshot?,
        ): SessionOutcome =
            when (export) {
                is ContinuousScanExport.Complete -> COMPLETE
                is ContinuousScanExport.TwistDetected -> FAILED
                is ContinuousScanExport.Partial -> if (last == null || last.trackedQuads.isEmpty()) LOST_TRACKING else PARTIAL
            }
    }
}

/** Why capture stopped: the operator, or a budget ([ContinuousSessionFormat.FRAME_CAP], [ContinuousSessionFormat.SIZE_CAP_BYTES]). */
@Serializable
enum class SessionStopReason { OPERATOR, FRAME_CAP, SIZE_CAP }

@Serializable
data class SessionDevice(
    val model: String,
    val manufacturer: String,
)

/** The detector that produced the observations: model file and SHA-256, runtime version, delegate and threads. */
@Serializable
data class SessionModel(
    val file: String,
    val sha256: String,
    val litertVersion: String,
    val delegate: String = "XNNPACK",
    val threads: Int = 2,
)

/**
 * Frame geometry: the analysis image, its crop rect and rotation, the upright crop the lattice fitter works in, and the
 * letterbox into the detector's square model input (model-input = upright × [letterboxScale] + pad).
 */
@Serializable
data class SessionGeometry(
    val rotationDegrees: Int,
    val analysisWidth: Int,
    val analysisHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropRight: Int,
    val cropBottom: Int,
    val uprightWidth: Int,
    val uprightHeight: Int,
    val inputSize: Int = ContinuousSessionFormat.MODEL_INPUT_SIZE,
    val letterboxScale: Double,
    val padLeft: Int,
    val padTop: Int,
)

/** Session timing summary; per-frame timestamps and analyzer times are in `frames.jsonl`. */
@Serializable
data class SessionTiming(
    val firstTimestampMs: Long? = null,
    val lastTimestampMs: Long? = null,
    val durationMs: Long = 0,
    val meanFrameIntervalMs: Double? = null,
)

/** `manifest.json`, written last: a session directory without it is never complete. */
@Serializable
data class ContinuousSessionManifest(
    val schemaVersion: Int = ContinuousSessionFormat.SCHEMA_VERSION,
    val sessionId: String,
    /** True for a generated stand-in session (e.g. from `SyntheticCubeStream`), never a device recording. */
    val synthetic: Boolean = false,
    val createdAt: String,
    val device: SessionDevice,
    val cube: String,
    val lighting: String,
    val partition: SessionPartition = SessionPartition.UNASSIGNED,
    val model: SessionModel,
    /** Full `git rev-parse HEAD` of the build that recorded the session, or `unknown`. */
    val buildCommit: String,
    val frameCount: Int,
    val framesWithObservations: Int,
    /** A model-input PNG is stored for every [imageEvery]th frame (0: none). */
    val imageEvery: Int,
    val imageFrames: Int,
    val geometry: SessionGeometry? = null,
    val timing: SessionTiming = SessionTiming(),
    val captureSkips: Int = 0,
    val droppedFrames: Int = 0,
    val stopReason: SessionStopReason = SessionStopReason.OPERATOR,
    val outcome: SessionOutcome,
    val latencyP50Ms: Double? = null,
    val latencyP95Ms: Double? = null,
    /** The device's no-capture analyzer p50, when a baseline run exists for this device model. */
    val latencyBaselineP50Ms: Double? = null,
    val defective: Boolean = false,
    val defectReasons: List<String> = emptyList(),
    val bytes: Long = 0,
    /** The full revision whose mapper produced `snapshots.jsonl`, set by `--refresh-golden`; null as recorded. */
    val goldenRevision: String? = null,
)

/** One detected sticker centre in model-input pixels (`0..inputSize`). */
@Serializable
data class SessionPeak(
    val x: Double,
    val y: Double,
    val score: Float,
)

/** A neutral [QuadObservation]: [quad] as 8 values `x0,y0..x3,y3`, [cellRgbs] as 27 values `r,g,b` per cell. */
@Serializable
data class SessionObservation(
    val quad: List<Double>,
    val cellRgbs: List<Double>,
    val cellQualities: List<Double>,
    val sharpness: Double,
    val hasGlare: Boolean,
    val score: Double,
) {
    fun toDomain(): QuadObservation =
        QuadObservation(
            quad = quadOf(quad),
            cellRgbs = cellRgbs.chunked(3).map { (r, g, b) -> RgbColor(r, g, b) },
            cellQualities = cellQualities,
            sharpness = sharpness,
            hasGlare = hasGlare,
            score = score,
        )

    companion object {
        fun of(observation: QuadObservation): SessionObservation =
            SessionObservation(
                quad = flat(observation.quad),
                cellRgbs = observation.cellRgbs.flatMap { listOf(it.r, it.g, it.b) },
                cellQualities = observation.cellQualities,
                sharpness = observation.sharpness,
                hasGlare = observation.hasGlare,
                score = observation.score,
            )
    }
}

/** One `frames.jsonl` line: what the analyzer saw on one frame. Frames without observations are kept. */
@Serializable
data class ContinuousSessionFrame(
    val frameIndex: Int,
    val timestampMs: Long,
    val analyzerMs: Double,
    val inferenceMs: Double = 0.0,
    val observations: List<SessionObservation>,
    val peaks: List<SessionPeak> = emptyList(),
    /** `images/frame-<idx>.png` when this frame's model input was stored. */
    val image: String? = null,
)

/** A [ContinuousScanCue]: [type] is the cue's class name, [count] or [face] its argument when it has one. */
@Serializable
data class SessionCue(
    val type: String,
    val count: Int? = null,
    val face: Face? = null,
) {
    fun toDomain(): ContinuousScanCue =
        when (type) {
            "BringIntoView" -> ContinuousScanCue.BringIntoView
            "TurnRemaining" -> ContinuousScanCue.TurnRemaining(checkNotNull(count))
            "TiltAdjacent" -> ContinuousScanCue.TiltAdjacent
            "KeepTurningRealign" -> ContinuousScanCue.KeepTurningRealign
            "TiltAwayLight" -> ContinuousScanCue.TiltAwayLight
            "TiltAwayLightRemaining" -> ContinuousScanCue.TiltAwayLightRemaining(checkNotNull(count))
            "ResolveConflict" -> ContinuousScanCue.ResolveConflict(checkNotNull(face))
            "LayerTwistDetected" -> ContinuousScanCue.LayerTwistDetected
            "AllVerified" -> ContinuousScanCue.AllVerified
            "TiltRemainingStickers" -> ContinuousScanCue.TiltRemainingStickers(checkNotNull(count))
            else -> throw IllegalArgumentException("Unknown cue type $type")
        }

    override fun toString(): String = type + (count?.let { "($it)" } ?: face?.let { "($it)" } ?: "")

    companion object {
        fun of(cue: ContinuousScanCue): SessionCue =
            when (cue) {
                ContinuousScanCue.BringIntoView -> SessionCue("BringIntoView")
                is ContinuousScanCue.TurnRemaining -> SessionCue("TurnRemaining", count = cue.remainingSides)
                ContinuousScanCue.TiltAdjacent -> SessionCue("TiltAdjacent")
                ContinuousScanCue.KeepTurningRealign -> SessionCue("KeepTurningRealign")
                ContinuousScanCue.TiltAwayLight -> SessionCue("TiltAwayLight")
                is ContinuousScanCue.TiltAwayLightRemaining -> SessionCue("TiltAwayLightRemaining", count = cue.remainingSides)
                is ContinuousScanCue.ResolveConflict -> SessionCue("ResolveConflict", face = cue.face)
                ContinuousScanCue.LayerTwistDetected -> SessionCue("LayerTwistDetected")
                ContinuousScanCue.AllVerified -> SessionCue("AllVerified")
                is ContinuousScanCue.TiltRemainingStickers -> SessionCue("TiltRemainingStickers", count = cue.committedCount)
            }
    }
}

@Serializable
data class SessionTrackedQuad(
    val face: Face? = null,
    val localFace: Face,
    val quad: List<Double>,
)

@Serializable
enum class SnapshotKind { SCANNING, TWIST_DETECTED, COMPLETE }

/**
 * One `snapshots.jsonl` line, a [ContinuousScanSnapshot]: [net] is 54 characters in URFDLB row-major order (`W Y G B R O`,
 * `.` = not committed), [state] the 54-character facelets of a complete snapshot's cube, [conflictedSlots] as `U4`-style slots.
 */
@Serializable
data class SessionSnapshot(
    val frameIndex: Int,
    val kind: SnapshotKind,
    val twistFrame: Int? = null,
    val net: String,
    val state: String? = null,
    val conflictedSlots: List<String> = emptyList(),
    val trackedQuads: List<SessionTrackedQuad> = emptyList(),
    val globalAnchored: Boolean,
    val cue: SessionCue,
    val committedCount: Int,
    val mappedFacesCount: Int,
    val invariantViolationCount: Int,
    val pendingFragmentFaces: Int,
) {
    /** The [net] as colours per face; null = not committed. */
    fun netColors(): Map<Face, List<Color?>> =
        Face.entries.associateWith { face ->
            (0 until 9).map { colorOf(net[face.ordinal * 9 + it]) }
        }

    fun toDomain(): ContinuousScanSnapshot {
        val netMap = netColors()
        val slots = conflictedSlots.map { FaceSlot(Face.valueOf(it.take(1)), it.drop(1).toInt()) }
        val quads = trackedQuads.map { TrackedQuadOverlay(it.face, it.localFace, quadOf(it.quad)) }
        val domainCue = cue.toDomain()
        return when (kind) {
            SnapshotKind.SCANNING -> {
                ContinuousScanSnapshot.Scanning(
                    frameIndex,
                    netMap,
                    slots,
                    quads,
                    globalAnchored,
                    domainCue,
                    committedCount,
                    mappedFacesCount,
                    invariantViolationCount,
                    pendingFragmentFaces,
                )
            }

            SnapshotKind.TWIST_DETECTED -> {
                ContinuousScanSnapshot.TwistDetected(
                    frameIndex,
                    checkNotNull(twistFrame),
                    netMap,
                    slots,
                    quads,
                    globalAnchored,
                    domainCue,
                    committedCount,
                    mappedFacesCount,
                    invariantViolationCount,
                    pendingFragmentFaces,
                )
            }

            SnapshotKind.COMPLETE -> {
                val facelets = Facelets(checkNotNull(state).map { checkNotNull(colorOf(it)) { "state has an unknown sticker" } })
                val valid = CubeValidator.validate(facelets) as? ValidationResult.Valid ?: error("state is not a valid cube: $state")
                ContinuousScanSnapshot.Complete(
                    frameIndex,
                    valid.state.reoriented(Orientation.HOME),
                    netMap,
                    slots,
                    quads,
                    domainCue,
                    committedCount,
                    mappedFacesCount,
                    pendingFragmentFaces,
                )
            }
        }
    }

    companion object {
        fun of(snapshot: ContinuousScanSnapshot): SessionSnapshot =
            SessionSnapshot(
                frameIndex = snapshot.frameIndex,
                kind =
                    when (snapshot) {
                        is ContinuousScanSnapshot.Scanning -> SnapshotKind.SCANNING
                        is ContinuousScanSnapshot.TwistDetected -> SnapshotKind.TWIST_DETECTED
                        is ContinuousScanSnapshot.Complete -> SnapshotKind.COMPLETE
                    },
                twistFrame = (snapshot as? ContinuousScanSnapshot.TwistDetected)?.twistFrame,
                net = netString(Face.entries.flatMap { snapshot.net[it] ?: List(9) { null } }),
                state =
                    (snapshot as? ContinuousScanSnapshot.Complete)
                        ?.state
                        ?.toFacelets()
                        ?.colors
                        ?.let(::netString),
                conflictedSlots = snapshot.conflictedSlots.map { "${it.face}${it.cellIndex}" },
                trackedQuads = snapshot.trackedQuads.map { SessionTrackedQuad(it.face, it.localFace, flat(it.quad)) },
                globalAnchored = snapshot.globalAnchored,
                cue = SessionCue.of(snapshot.cue),
                committedCount = snapshot.committedCount,
                mappedFacesCount = snapshot.mappedFacesCount,
                invariantViolationCount = snapshot.invariantViolationCount,
                pendingFragmentFaces = snapshot.pendingFragmentFaces,
            )

        /** 54 (or any number of) stickers as characters; `.` = null. */
        fun netString(colors: List<Color?>): String = colors.joinToString("") { it?.let(::charOf)?.toString() ?: "." }

        fun charOf(color: Color): Char =
            when (color) {
                Color.WHITE -> 'W'
                Color.YELLOW -> 'Y'
                Color.GREEN -> 'G'
                Color.BLUE -> 'B'
                Color.RED -> 'R'
                Color.ORANGE -> 'O'
            }

        fun colorOf(char: Char): Color? =
            when (char) {
                'W' -> Color.WHITE
                'Y' -> Color.YELLOW
                'G' -> Color.GREEN
                'B' -> Color.BLUE
                'R' -> Color.RED
                'O' -> Color.ORANGE
                '.' -> null
                else -> throw IllegalArgumentException("Unknown sticker character '$char'")
            }
    }
}

/** One `labels.json` sticker: operator truth (null = explicit unknown), where it came from and whether it was reviewed. */
@Serializable
data class SessionLabel(
    val color: Color? = null,
    val source: TruthSource? = null,
    val reviewed: Boolean = false,
) {
    fun toTruth(): StickerTruth = StickerTruth(color, source, reviewed)
}

/** The labels' [StickerTruthCheck] outcome. */
@Serializable
enum class SessionCountCheck { PASSED, OVERRIDDEN, REFUSED }

/** `labels.json`: 54 truth stickers in URFDLB row-major order, separate from the predictions in `snapshots.jsonl`. */
@Serializable
data class ContinuousSessionLabels(
    val schemaVersion: Int = ContinuousSessionFormat.SCHEMA_VERSION,
    val stickers: List<SessionLabel>,
    val countCheck: SessionCountCheck,
    val overrideReason: String? = null,
) {
    init {
        require(stickers.size == StickerTruthCheck.STICKERS) { "labels need ${StickerTruthCheck.STICKERS} stickers, got ${stickers.size}" }
    }

    companion object {
        /** The labels for [stickers] with the [StickerTruthCheck] decision for the operator's [reason] (refused with none). */
        fun of(
            stickers: List<StickerTruth>,
            reason: String?,
        ): ContinuousSessionLabels {
            val decision = StickerTruthCheck.of(stickers).decide(reason)
            return ContinuousSessionLabels(
                stickers = stickers.map { SessionLabel(it.color, it.source, it.reviewed) },
                countCheck =
                    when (decision) {
                        StickerTruthDecision.Passed -> SessionCountCheck.PASSED
                        is StickerTruthDecision.Overridden -> SessionCountCheck.OVERRIDDEN
                        StickerTruthDecision.Refused -> SessionCountCheck.REFUSED
                    },
                overrideReason = (decision as? StickerTruthDecision.Overridden)?.reason,
            )
        }
    }
}

/** File names, budgets and the complete-directory rule of the session format. */
object ContinuousSessionFormat {
    const val SCHEMA_VERSION = 1
    const val MANIFEST = "manifest.json"
    const val FRAMES = "frames.jsonl"
    const val SNAPSHOTS = "snapshots.jsonl"
    const val LABELS = "labels.json"
    const val IMAGES = "images"
    const val TEMP_PREFIX = ".tmp-"

    /** Writer queue capacity in frames. */
    const val QUEUE_FRAMES = 8

    /** Frame cap per session. */
    const val FRAME_CAP = 600

    /** On-device byte cap per session (decimal). */
    const val SIZE_CAP_BYTES = 40_000_000L

    /** A model-input PNG is stored every N frames. */
    const val IMAGE_EVERY = 3

    const val MODEL_INPUT_SIZE = 256

    /** Checked-in limits (decimal bytes, enforced by `:checkFixtureSize`). */
    const val CHECKED_IN_SESSION_LIMIT_BYTES = 15_000_000L
    const val CHECKED_IN_ROOT_LIMIT_BYTES = 60_000_000L

    /** A capture p50 more than this factor over the device's no-capture baseline p50 marks the session defective. */
    const val LATENCY_DEFECT_FACTOR = 1.10

    fun imageName(frameIndex: Int): String = "$IMAGES/frame-${frameIndex.toString().padStart(5, '0')}.png"

    /** A session directory counts only once renamed from its `.tmp-` name with its manifest written. */
    fun isComplete(dir: File): Boolean = dir.isDirectory && !dir.name.startsWith(TEMP_PREFIX) && File(dir, MANIFEST).isFile

    /** The complete session directories directly under [root] (sorted by name); temp and manifest-less dirs are ignored. */
    fun sessions(root: File): List<File> =
        root
            .listFiles()
            .orEmpty()
            .filter(::isComplete)
            .sortedBy { it.name }
}

/** The JSON codec of the session format; decoding rejects another [ContinuousSessionFormat.SCHEMA_VERSION]. */
object ContinuousSessionJson {
    private val pretty =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }
    private val line = Json { encodeDefaults = true }

    fun encodeManifest(manifest: ContinuousSessionManifest): String =
        pretty.encodeToString(ContinuousSessionManifest.serializer(), manifest)

    fun decodeManifest(text: String): ContinuousSessionManifest =
        pretty.decodeFromString(ContinuousSessionManifest.serializer(), text).also { requireSchema(it.schemaVersion) }

    fun encodeLabels(labels: ContinuousSessionLabels): String = pretty.encodeToString(ContinuousSessionLabels.serializer(), labels)

    fun decodeLabels(text: String): ContinuousSessionLabels =
        pretty.decodeFromString(ContinuousSessionLabels.serializer(), text).also { requireSchema(it.schemaVersion) }

    fun encodeFrame(frame: ContinuousSessionFrame): String = line.encodeToString(ContinuousSessionFrame.serializer(), frame)

    fun decodeFrame(text: String): ContinuousSessionFrame = line.decodeFromString(ContinuousSessionFrame.serializer(), text)

    fun encodeSnapshot(snapshot: SessionSnapshot): String = line.encodeToString(SessionSnapshot.serializer(), snapshot)

    fun decodeSnapshot(text: String): SessionSnapshot = line.decodeFromString(SessionSnapshot.serializer(), text)

    fun readManifest(dir: File): ContinuousSessionManifest = decodeManifest(File(dir, ContinuousSessionFormat.MANIFEST).readText())

    fun readFrames(dir: File): List<ContinuousSessionFrame> = readLines(File(dir, ContinuousSessionFormat.FRAMES)).map(::decodeFrame)

    fun readSnapshots(dir: File): List<SessionSnapshot> = readLines(File(dir, ContinuousSessionFormat.SNAPSHOTS)).map(::decodeSnapshot)

    /** The session's labels, or null when it was never labelled. */
    fun readLabels(dir: File): ContinuousSessionLabels? =
        File(dir, ContinuousSessionFormat.LABELS).takeIf { it.isFile }?.let { decodeLabels(it.readText()) }

    private fun readLines(file: File): List<String> = if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()

    private fun requireSchema(version: Int) =
        require(version == ContinuousSessionFormat.SCHEMA_VERSION) {
            "Unsupported continuous session schema $version (expected ${ContinuousSessionFormat.SCHEMA_VERSION})"
        }
}

private fun flat(quad: Quad2D): List<Double> = quad.corners.flatMap { listOf(it.x, it.y) }

private fun quadOf(values: List<Double>): Quad2D {
    require(values.size == Quad2D.CORNERS * 2) { "quad needs ${Quad2D.CORNERS * 2} values, got ${values.size}" }
    return Quad2D(values.chunked(2).map { (x, y) -> Point2D(x, y) })
}
