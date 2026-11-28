package dev.cubentia.domain.recognition.evaluation.continuous

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.recognition.CubieInvariantResolver
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.RecognitionFixtures
import dev.cubentia.domain.recognition.ResolutionOutcome
import dev.cubentia.domain.recognition.StickerConfidence
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionJson
import dev.cubentia.domain.recognition.continuous.session.SessionOutcome
import dev.cubentia.domain.recognition.continuous.session.SessionSnapshot
import dev.cubentia.domain.recognition.continuous.session.SnapshotKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Spike measurement: evaluates pure-algebraic [CubieInvariantResolver.resolveNet] across all 11 Continuous Quick Scan
 * sessions in `Fixtures/continuous/` (including `mapper-parity/` worst-case scenarios: mid-scan twists, strong color
 * casts, pastel AWB drift, skin occluders, and long holds).
 *
 * Measures:
 * - Worst-case safety: whether algebraic uniqueness EVER produces a wrong cube state on any frame of any session.
 * - PARTIAL session rescue: whether stuck/partial sessions complete uniquely and accurately.
 * - Early completion: how many frames earlier `COMPLETE` sessions finish uniquely and accurately.
 * - Worst-case runtime latency per `resolveNet` call.
 */
class ContinuousCubieResolverSpikeTest {
    private val neutralLabs = List(Facelets.SIZE) { Lab(50.0, 0.0, 0.0) }
    private val neutralCenters = List(6) { RecognitionFixtures.LABS }

    private fun resolvePureAlgebraic(snapshot: SessionSnapshot): Pair<ResolutionOutcome, Long> {
        val netMap = snapshot.netColors()
        val colors = ArrayList<Color>(Facelets.SIZE)
        val conf = ArrayList<StickerConfidence>(Facelets.SIZE)
        for (face in Face.entries) {
            val cells = netMap.getValue(face)
            for (i in 0 until 9) {
                val c = cells[i]
                if (i == 4) {
                    colors.add(Color.home(face))
                    conf.add(StickerConfidence.CONFIDENT)
                } else if (c != null) {
                    colors.add(c)
                    conf.add(StickerConfidence.CONFIDENT)
                } else {
                    colors.add(Color.WHITE)
                    conf.add(StickerConfidence.UNCERTAIN)
                }
            }
        }
        val t0 = System.nanoTime()
        val outcome =
            CubieInvariantResolver.resolveNet(
                netColors = colors,
                netConfidence = conf,
                netLabs = neutralLabs,
                plausibilityLabs = neutralLabs,
                plausibilityCenters = neutralCenters,
                candidateWindowDeltaE = Double.POSITIVE_INFINITY,
                maxPlausibilityDeltaE = Double.POSITIVE_INFINITY,
            )
        val elapsedUs = (System.nanoTime() - t0) / 1_000L
        return outcome to elapsedUs
    }

    private fun discoverAllSessions(root: File): List<File> =
        root
            .walkTopDown()
            .filter { dir -> dir.isDirectory && File(dir, "manifest.json").isFile && File(dir, "frames.jsonl").isFile }
            .sortedBy { it.relativeTo(root).path }
            .toList()

    @Test
    fun `continuous spike measures early completion, partial rescue, worst-case safety, and latency`() {
        val root =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "Fixtures/continuous") }
                .firstOrNull { it.isDirectory }
        assumeTrue(root != null, "Fixtures/continuous not found")
        val sessions = discoverAllSessions(checkNotNull(root))
        assumeTrue(sessions.isNotEmpty(), "No continuous sessions found")

        var totalWrongStrict = 0
        var totalWrongAllowConflicts = 0
        var maxUs = 0L
        var calls = 0

        val report =
            buildString {
                appendLine("# Continuous Quick Scan Spike Report: CubieInvariantResolver (Pure Algebraic Uniqueness)")
                appendLine()
                appendLine(
                    "Sessions evaluated: **${sessions.size}** (from `Fixtures/continuous/` including `mapper-parity/` worst-case scenarios).",
                )
                appendLine()
                appendLine(
                    "## Mode A — Strict Gate (`globalAnchored && mappedFaces == 6 && conflictedSlots.isEmpty() && invariantViolations == 0`)",
                )
                appendLine()
                appendLine(
                    "| Session | Baseline Outcome | Native Complete Frame | First Resolved Frame (unknowns) | Frames Saved | Wrong Resolved Frames | End-of-Session Outcome | Max Latency (ms) |",
                )
                appendLine("|---|---|---|---|---|---|---|---|")

                val allowConflictNotes = mutableListOf<String>()

                sessions.forEach { dir ->
                    val name = dir.relativeTo(root).path
                    val replay = ContinuousSessionReplay.replay(dir)
                    val labels = ContinuousSessionJson.readLabels(dir)
                    val truth: List<Color?>? = labels?.stickers?.map { it.color }

                    val nativeCompleteFrame = replay.replayed.firstOrNull { it.kind == SnapshotKind.COMPLETE }?.frameIndex
                    var firstResolvedFrame: Int? = null
                    var firstResolvedUnknowns: Int? = null
                    var wrongStrictFrames = 0
                    var wrongAllowFrames = 0
                    var sessionMaxUs = 0L
                    var lastOutcome = replay.outcome.name
                    var endRescued = false

                    replay.replayed.forEach { snap ->
                        if (snap.kind != SnapshotKind.SCANNING) return@forEach
                        if (!snap.globalAnchored || snap.mappedFacesCount < 6) return@forEach
                        val unknowns = Facelets.SIZE - snap.committedCount
                        if (unknowns !in 1..CubieInvariantResolver.MAX_UNCERTAIN_STICKERS) return@forEach

                        val strictEligible = snap.conflictedSlots.isEmpty() && snap.invariantViolationCount == 0
                        val (outcome, elapsedUs) = resolvePureAlgebraic(snap)
                        calls++
                        if (elapsedUs > maxUs) maxUs = elapsedUs
                        if (elapsedUs > sessionMaxUs) sessionMaxUs = elapsedUs

                        if (outcome is ResolutionOutcome.Resolved) {
                            val mismatches =
                                if (truth != null) {
                                    (0 until Facelets.SIZE).count { i -> truth[i] != null && outcome.facelets.colors[i] != truth[i] }
                                } else {
                                    0
                                }
                            if (strictEligible) {
                                if (firstResolvedFrame == null) {
                                    firstResolvedFrame = snap.frameIndex
                                    firstResolvedUnknowns = outcome.resolvedIndices.size
                                }
                                if (mismatches > 0) {
                                    wrongStrictFrames++
                                    totalWrongStrict++
                                }
                            } else {
                                if (mismatches > 0) {
                                    wrongAllowFrames++
                                    totalWrongAllowConflicts++
                                }
                            }
                        }
                    }

                    // Check end-of-session rescue for PARTIAL sessions.
                    val lastSnap = replay.replayed.lastOrNull()
                    if (replay.outcome == SessionOutcome.PARTIAL && lastSnap != null && lastSnap.kind == SnapshotKind.SCANNING &&
                        lastSnap.globalAnchored && lastSnap.mappedFacesCount == 6 && lastSnap.conflictedSlots.isEmpty() &&
                        lastSnap.invariantViolationCount == 0
                    ) {
                        val (endOutcome, _) = resolvePureAlgebraic(lastSnap)
                        if (endOutcome is ResolutionOutcome.Resolved) {
                            val mismatches =
                                if (truth != null) {
                                    (0 until Facelets.SIZE).count { i -> truth[i] != null && endOutcome.facelets.colors[i] != truth[i] }
                                } else {
                                    0
                                }
                            endRescued = mismatches == 0
                            lastOutcome =
                                if (mismatches ==
                                    0
                                ) {
                                    "PARTIAL -> RESCUED (${endOutcome.resolvedIndices.size} stickers)"
                                } else {
                                    "PARTIAL -> WRONG ($mismatches)"
                                }
                        } else {
                            lastOutcome =
                                "PARTIAL (${(endOutcome as ResolutionOutcome.Unchanged).reason}, ${Facelets.SIZE - lastSnap.committedCount} unread)"
                        }
                    } else if (replay.outcome == SessionOutcome.PARTIAL && lastSnap != null) {
                        lastOutcome =
                            "PARTIAL (conflicts=${lastSnap.conflictedSlots.size}, unread=${Facelets.SIZE - lastSnap.committedCount})"
                    }

                    val framesSaved =
                        when {
                            nativeCompleteFrame != null && firstResolvedFrame != null -> "${nativeCompleteFrame - firstResolvedFrame}"
                            endRescued -> "PARTIAL rescued"
                            else -> "-"
                        }
                    val firstCol = if (firstResolvedFrame != null) "frame $firstResolvedFrame ($firstResolvedUnknowns)" else "-"
                    val nativeCol = nativeCompleteFrame?.let { "frame $it" } ?: "-"
                    val maxMs = "%.2f".format(java.util.Locale.ROOT, sessionMaxUs / 1000.0)
                    appendLine(
                        "| `$name` | ${replay.outcome} (${replay.committedStickers}/54) | $nativeCol | $firstCol | $framesSaved | $wrongStrictFrames | $lastOutcome | $maxMs |",
                    )
                    if (wrongAllowFrames > 0) {
                        allowConflictNotes +=
                            "- `$name`: $wrongAllowFrames wrong resolved frames when conflictedSlots were NOT required empty"
                    }
                }

                appendLine()
                appendLine("## Summary & Worst-Case Safety")
                appendLine(
                    "- Total `resolveNet` evaluations: **$calls**, worst-case latency: **${"%.2f".format(
                        java.util.Locale.ROOT,
                        maxUs / 1000.0,
                    )} ms**",
                )
                appendLine("- Mode A (strict, no active conflicts): **$totalWrongStrict** wrong resolved frames across all 11 sessions.")
                appendLine("- Mode B (allowing active conflicts): **$totalWrongAllowConflicts** wrong resolved frames.")
                if (allowConflictNotes.isNotEmpty()) {
                    allowConflictNotes.forEach { appendLine("  $it") }
                }
            }

        println(report)
        REPORT_FILE.parentFile.mkdirs()
        REPORT_FILE.writeText(report)

        assertEquals(0, totalWrongStrict, "Mode A strict algebraic resolution produced wrong frames!\n$report")
    }

    private companion object {
        val REPORT_FILE = File("build/reports/quick-scan/continuous-cubie-resolver-spike.md")
    }
}
