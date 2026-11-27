package dev.cubentia.domain.recognition.continuous.session

import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.StickerTruth
import dev.cubentia.domain.recognition.TruthSource
import dev.cubentia.domain.recognition.continuous.ContinuousFaceletMapper
import dev.cubentia.domain.recognition.continuous.ContinuousScanSnapshot
import dev.cubentia.domain.recognition.continuous.SyntheticCubeStream
import java.io.File

/**
 * Story 10.34: generates the **synthetic, honestly labelled** continuous sessions checked in under `Fixtures/continuous/`
 * (`synthetic: true`, device `synthetic (SyntheticCubeStream)`, no images, partition `UNASSIGNED`, labels from the
 * generator's known cube state). They stand in for curated device sessions until the PO's human gate, and stay as the
 * deterministic regression floor of state replay.
 *
 * Regenerate: `./gradlew :core:domain:generateSyntheticSessions` (then review the diff; the golden test
 * `ContinuousSessionReplayTest` checks them).
 */
internal object SyntheticSessionGenerator {
    const val COMPLETE_ID = "synthetic-complete-v1"
    const val PARTIAL_ID = "synthetic-partial-v1"

    /**
     * The partial session: scenario 4 (`4_mid_solve_f2l_symmetric_regrip`) cut after the frame where 46 stickers are
     * committed but the cube is not yet complete, so replay must keep its 8 unknowns unfilled.
     */
    const val PARTIAL_SCENARIO = 3
    const val PARTIAL_FRAMES = 96

    private const val FRAME_INTERVAL_MS = 33L
    private const val WIDTH = 640
    private const val HEIGHT = 480

    data class Session(
        val manifest: ContinuousSessionManifest,
        val frames: List<ContinuousSessionFrame>,
        val snapshots: List<SessionSnapshot>,
        val labels: ContinuousSessionLabels,
    )

    private fun scenario(index: Int) = SyntheticCubeStream.buildScenarios()[index]

    fun complete(buildCommit: String = "unknown"): Session = session(COMPLETE_ID, 0, frameLimit = null, buildCommit = buildCommit)

    fun partial(buildCommit: String = "unknown"): Session =
        session(PARTIAL_ID, PARTIAL_SCENARIO, frameLimit = PARTIAL_FRAMES, buildCommit = buildCommit)

    private fun session(
        id: String,
        scenarioIndex: Int,
        frameLimit: Int?,
        buildCommit: String,
    ): Session {
        val cfg = scenario(scenarioIndex)
        val synthetic = SyntheticCubeStream.generateSequence(cfg, WIDTH, HEIGHT).let { all -> frameLimit?.let { all.take(it) } ?: all }
        val mapper = ContinuousFaceletMapper()
        var last: ContinuousScanSnapshot? = null
        val frames = mutableListOf<ContinuousSessionFrame>()
        val snapshots = mutableListOf<SessionSnapshot>()
        synthetic.forEach { frame ->
            val snapshot = mapper.processFrame(frame.frameIndex, frame.detections)
            last = snapshot
            frames +=
                ContinuousSessionFrame(
                    frameIndex = frame.frameIndex,
                    timestampMs = frame.frameIndex * FRAME_INTERVAL_MS,
                    analyzerMs = 0.0,
                    observations = frame.detections.map(SessionObservation::of),
                )
            snapshots += SessionSnapshot.of(snapshot)
        }
        val truth = SyntheticCubeStream.stateToNet(cfg.state)
        val labels =
            ContinuousSessionLabels.of(
                Face.entries.flatMap { face -> truth.getValue(face).map { StickerTruth(it, TruthSource.ENTERED, reviewed = true) } },
                reason = null,
            )
        val lastMs = frames.lastOrNull()?.timestampMs
        val manifest =
            ContinuousSessionManifest(
                sessionId = id,
                synthetic = true,
                createdAt = "2026-11-26T00:00:00Z",
                device = SessionDevice(model = "synthetic (SyntheticCubeStream)", manufacturer = "synthetic"),
                cube = "SyntheticCubeStream scenario ${cfg.name} (seed ${cfg.seed})" + (frameLimit?.let { ", first $it frames" } ?: ""),
                lighting = "synthetic ${cfg.palette.name.lowercase()} palette",
                partition = SessionPartition.UNASSIGNED,
                model =
                    SessionModel(
                        file = "none (synthetic observations)",
                        sha256 = "none",
                        litertVersion = "none",
                        delegate = "none",
                        threads = 0,
                    ),
                buildCommit = buildCommit,
                frameCount = frames.size,
                framesWithObservations = frames.count { it.observations.isNotEmpty() },
                imageEvery = 0,
                imageFrames = 0,
                geometry =
                    SessionGeometry(
                        rotationDegrees = 0,
                        analysisWidth = WIDTH,
                        analysisHeight = HEIGHT,
                        cropLeft = 0,
                        cropTop = 0,
                        cropRight = WIDTH,
                        cropBottom = HEIGHT,
                        uprightWidth = WIDTH,
                        uprightHeight = HEIGHT,
                        letterboxScale = 0.4,
                        padLeft = 0,
                        padTop = 32,
                    ),
                timing =
                    SessionTiming(
                        firstTimestampMs = frames.firstOrNull()?.timestampMs,
                        lastTimestampMs = lastMs,
                        durationMs = lastMs ?: 0,
                        meanFrameIntervalMs = FRAME_INTERVAL_MS.toDouble(),
                    ),
                stopReason = SessionStopReason.OPERATOR,
                outcome = SessionOutcome.derive(mapper.export(), last),
            )
        return Session(manifest, frames, snapshots, labels)
    }

    /** Writes [session] into `<root>/<sessionId>/` the way the session writer finishes one: temp dir, manifest last, rename. */
    fun write(
        root: File,
        session: Session,
    ): File {
        val id = session.manifest.sessionId
        val temp = File(root, ContinuousSessionFormat.TEMP_PREFIX + id)
        temp.deleteRecursively()
        temp.mkdirs()
        File(
            temp,
            ContinuousSessionFormat.FRAMES,
        ).writeText(session.frames.joinToString("") { ContinuousSessionJson.encodeFrame(it) + "\n" })
        File(temp, ContinuousSessionFormat.SNAPSHOTS).writeText(
            session.snapshots.joinToString("") {
                ContinuousSessionJson.encodeSnapshot(it) +
                    "\n"
            },
        )
        // Bytes as the device writer counts them: frame lines + snapshot lines + PNGs (none here), never labels or manifest.
        val bytes = listOf(ContinuousSessionFormat.FRAMES, ContinuousSessionFormat.SNAPSHOTS).sumOf { File(temp, it).length() }
        File(temp, ContinuousSessionFormat.LABELS).writeText(ContinuousSessionJson.encodeLabels(session.labels) + "\n")
        File(
            temp,
            ContinuousSessionFormat.MANIFEST,
        ).writeText(ContinuousSessionJson.encodeManifest(session.manifest.copy(bytes = bytes)) + "\n")
        val target = File(root, id)
        target.deleteRecursively()
        check(temp.renameTo(target)) { "rename $temp -> $target failed" }
        return target
    }
}

fun main(args: Array<String>) {
    val root = File(args.firstOrNull() ?: "Fixtures/continuous").apply { mkdirs() }
    val commit =
        runCatching {
            ProcessBuilder("git", "rev-parse", "HEAD")
                .start()
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
                .takeIf { it.length == 40 }
        }.getOrNull() ?: "unknown"
    listOf(SyntheticSessionGenerator.complete(commit), SyntheticSessionGenerator.partial(commit)).forEach { session ->
        val dir = SyntheticSessionGenerator.write(root, session)
        println("Wrote ${dir.path} (${session.manifest.outcome}, ${session.frames.size} frames)")
    }
}
