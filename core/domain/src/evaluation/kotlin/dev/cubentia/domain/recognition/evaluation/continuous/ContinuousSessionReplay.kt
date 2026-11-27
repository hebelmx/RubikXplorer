package dev.cubentia.domain.recognition.evaluation.continuous

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.continuous.ContinuousFaceletMapper
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionFormat
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionJson
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionManifest
import dev.cubentia.domain.recognition.continuous.session.SessionCountCheck
import dev.cubentia.domain.recognition.continuous.session.SessionOutcome
import dev.cubentia.domain.recognition.continuous.session.SessionSnapshot
import java.io.File

/**
 * Story 10.34 state replay (AD-9: neutral observations only, evaluation source set): the recorded observations of one
 * continuous session go through a fresh [ContinuousFaceletMapper], and every snapshot field and the cue stream are
 * compared with `snapshots.jsonl`. Screen timing (completion hold, hand-over) is outside state replay.
 *
 * This replaces the continuous use case of the old Quick Scan exporter (`exportKotlinStickerReads`).
 */
object ContinuousSessionReplay {
    /** The stage replay starts from: the mapper, fed the recorded observations (detector and sampler are not rerun). */
    const val STARTING_STAGE = "mapper (recorded observations)"

    /** One field of one frame where the replay differs from the recording. */
    data class FrameDiff(
        val frameIndex: Int,
        val field: String,
        val recorded: String,
        val replayed: String,
    ) {
        override fun toString(): String = "frame $frameIndex: $field recorded=$recorded replayed=$replayed"
    }

    /** One labelled sticker the replayed net committed to another colour. */
    data class LabelMismatch(
        val index: Int,
        val truth: Color,
        val predicted: Color,
    ) {
        val slot: String get() = "${Face.entries[index / 9]}${index % 9}"

        override fun toString(): String = "$slot truth=$truth predicted=$predicted"
    }

    data class Report(
        val sessionId: String,
        val directory: File,
        val manifest: ContinuousSessionManifest,
        val frames: Int,
        val framesWithObservations: Int,
        val sidesMapped: Int,
        val committedStickers: Int,
        val outcome: SessionOutcome,
        /** Complete: the exported net in canonical URFDLB order; otherwise the last confirmed net, unknowns null. */
        val net: List<Color?>,
        val lastCue: String?,
        val lastConflicts: List<String>,
        val diffs: List<FrameDiff>,
        val labelled: Boolean,
        val labelMismatches: List<LabelMismatch>,
        /** Labels left as explicit unknowns: reported, never compared. */
        val unknownLabels: Int,
        /** Labelled stickers the replay never committed (null in [net]): reported, never filled. */
        val uncommittedLabelled: Int,
        val replayed: List<SessionSnapshot>,
        /** Recorded frames the pipeline did not read (no observation, no mapper call); not gaps. */
        val skippedFrames: Int = 0,
        /** Frame indices missing between the first and last recorded frame (skipped or cap-dropped frames the live mapper saw). */
        val frameGaps: Int = 0,
        /** The labels' count check was refused: they are reported but never scored, so [labelMismatches] stays empty. */
        val labelsRefused: Boolean = false,
    ) {
        /** Frames the live mapper saw that the recording lacks; a session with any is not exactly replayable. */
        val missingFrames: Int get() = maxOf(frameGaps, manifest.captureSkips)

        val exactlyReplayable: Boolean get() = missingFrames == 0
        val startingStage: String get() = STARTING_STAGE
    }

    /** Replays the session in [dir] with a fresh mapper. */
    fun replay(dir: File): Report {
        val manifest = ContinuousSessionJson.readManifest(dir)
        val frames = ContinuousSessionJson.readFrames(dir).sortedBy { it.frameIndex }
        val recorded = ContinuousSessionJson.readSnapshots(dir)
        val mapper = ContinuousFaceletMapper()
        // A skipped frame (the pipeline did not read it) never reached the mapper live, so it does not here either.
        val replayed =
            frames.filterNot { it.skipped }.map { frame ->
                SessionSnapshot.of(mapper.processFrame(frame.frameIndex, frame.observations.map { it.toDomain() }))
            }
        val last = replayed.lastOrNull()
        val export = mapper.export()
        val outcome = SessionOutcome.derive(export, last?.toDomain())
        val net =
            if (export is ContinuousScanExport.Complete) {
                export.facelets.colors
            } else {
                last?.let { snapshot -> Face.entries.flatMap { snapshot.netColors().getValue(it) } } ?: List(54) { null }
            }
        val labels = ContinuousSessionJson.readLabels(dir)
        val refused = labels?.countCheck == SessionCountCheck.REFUSED
        val mismatches =
            labels
                ?.takeUnless { refused }
                ?.stickers
                ?.mapIndexedNotNull { index, label ->
                    val truth = label.color ?: return@mapIndexedNotNull null
                    val predicted = net[index] ?: return@mapIndexedNotNull null
                    LabelMismatch(index, truth, predicted).takeIf { predicted != truth }
                }.orEmpty()
        val outcomeDiff =
            listOfNotNull(
                FrameDiff(-1, "outcome", manifest.outcome.toString(), outcome.toString()).takeIf { manifest.outcome != outcome },
            )
        return Report(
            sessionId = manifest.sessionId,
            directory = dir,
            manifest = manifest,
            frames = frames.size,
            skippedFrames = frames.count { it.skipped },
            framesWithObservations = frames.count { it.observations.isNotEmpty() },
            sidesMapped = last?.mappedFacesCount ?: 0,
            committedStickers = last?.committedCount ?: 0,
            outcome = outcome,
            net = net,
            lastCue = last?.cue?.toString(),
            lastConflicts = last?.conflictedSlots.orEmpty(),
            diffs = diff(recorded, replayed) + outcomeDiff,
            labelled = labels != null,
            labelMismatches = mismatches,
            unknownLabels = labels?.stickers?.count { it.color == null } ?: 0,
            uncommittedLabelled = labels?.stickers?.indices?.count { labels.stickers[it].color != null && net[it] == null } ?: 0,
            replayed = replayed,
            frameGaps = frameGaps(frames.map { it.frameIndex }),
            labelsRefused = refused,
        )
    }

    /** Missing indices in [indices] from 0 (the engine's first frame) to the last recorded one. */
    fun frameGaps(indices: List<Int>): Int = if (indices.isEmpty()) 0 else indices.max() + 1 - indices.toSet().size

    /** Every complete session under [root], or [root] itself when it is a session directory. */
    fun sessionsUnder(root: File): List<File> =
        if (ContinuousSessionFormat.isComplete(root)) listOf(root) else ContinuousSessionFormat.sessions(root)

    /** Per-frame differences of every snapshot field, cue included, between [recorded] and [replayed]. */
    fun diff(
        recorded: List<SessionSnapshot>,
        replayed: List<SessionSnapshot>,
    ): List<FrameDiff> {
        val diffs = mutableListOf<FrameDiff>()
        if (recorded.size != replayed.size) {
            diffs += FrameDiff(-1, "snapshotCount", recorded.size.toString(), replayed.size.toString())
        }
        recorded.zip(replayed).forEach { (a, b) ->
            fun field(
                name: String,
                get: (SessionSnapshot) -> Any?,
            ) {
                val x = get(a)
                val y = get(b)
                if (x != y) diffs += FrameDiff(a.frameIndex, name, x.toString(), y.toString())
            }
            field("frameIndex") { it.frameIndex }
            field("kind") { it.kind }
            field("twistFrame") { it.twistFrame }
            field("net") { it.net }
            field("state") { it.state }
            field("conflictedSlots") { it.conflictedSlots }
            field("trackedQuads") { it.trackedQuads }
            field("globalAnchored") { it.globalAnchored }
            field("cue") { it.cue.toString() }
            field("committedCount") { it.committedCount }
            field("mappedFacesCount") { it.mappedFacesCount }
            field("invariantViolationCount") { it.invariantViolationCount }
            field("pendingFragmentFaces") { it.pendingFragmentFaces }
        }
        return diffs
    }

    /**
     * `--refresh-golden`: rewrites [dir]'s `snapshots.jsonl` from this revision's mapper and records [revision] as golden.
     * The manifest's recorded outcome is kept. A session that is not exactly replayable (missing frames) is refused: its
     * diffs may come from the missing frames rather than from the mapper, so they are never accepted as golden.
     */
    fun refreshGolden(
        dir: File,
        revision: String,
    ): Report {
        val report = replay(dir)
        require(report.exactlyReplayable) {
            "${report.sessionId}: not exactly replayable (${report.missingFrames} missing frames, a defective capture); " +
                "refusing --refresh-golden"
        }
        File(dir, ContinuousSessionFormat.SNAPSHOTS).writeText(
            report.replayed.joinToString("") {
                ContinuousSessionJson.encodeSnapshot(it) +
                    "\n"
            },
        )
        File(
            dir,
            ContinuousSessionFormat.MANIFEST,
        ).writeText(ContinuousSessionJson.encodeManifest(report.manifest.copy(goldenRevision = revision)) + "\n")
        return replay(dir)
    }

    /** The Markdown report of [reports]. */
    fun markdown(reports: List<Report>): String =
        buildString {
            appendLine("# Continuous session replay")
            appendLine()
            if (reports.isEmpty()) appendLine("No complete session found.")
            reports.forEach { r ->
                appendLine("## ${r.sessionId}${if (r.manifest.synthetic) " (synthetic)" else ""}")
                appendLine()
                appendLine("- Starting stage: ${r.startingStage}")
                if (!r.exactlyReplayable) {
                    appendLine(
                        "- **Not exactly replayable: ${r.missingFrames} missing frames (defective capture)**; diffs below may come from " +
                            "the missing frames, not from mapper drift.",
                    )
                }
                appendLine(
                    "- Partition: ${r.manifest.partition} · recorded outcome: ${r.manifest.outcome} · replayed outcome: ${r.outcome}",
                )
                appendLine(
                    "- Coverage: ${r.framesWithObservations}/${r.frames} frames with observations · ${r.skippedFrames} skipped · " +
                        "sides mapped ${r.sidesMapped}/6 · committed stickers ${r.committedStickers}/54",
                )
                val netTitle = if (r.outcome == SessionOutcome.COMPLETE) "Final net" else "Last confirmed net"
                appendLine("- $netTitle (URFDLB, . = unknown): `${SessionSnapshot.netString(r.net)}`")
                appendLine(
                    "- Last cue: ${r.lastCue ?: "none"} · conflicts: ${r.lastConflicts.ifEmpty { listOf("none") }.joinToString(", ")}",
                )
                appendLine("- Golden revision: ${r.manifest.goldenRevision ?: "as recorded"}")
                appendLine("- Snapshot/cue diffs: ${r.diffs.size}")
                r.diffs.forEach { appendLine("  - $it") }
                if (r.labelled && r.labelsRefused) {
                    appendLine(
                        "- Label mismatches: not scored (count check REFUSED) (unknown labels: ${r.unknownLabels}; " +
                            "labelled but not committed: ${r.uncommittedLabelled})",
                    )
                } else if (r.labelled) {
                    appendLine(
                        "- Label mismatches: ${r.labelMismatches.size} (unknown labels, not compared: ${r.unknownLabels}; " +
                            "labelled but not committed: ${r.uncommittedLabelled})",
                    )
                    r.labelMismatches.forEach { appendLine("  - $it") }
                } else {
                    appendLine("- Labels: none")
                }
                appendLine()
            }
        }
}
