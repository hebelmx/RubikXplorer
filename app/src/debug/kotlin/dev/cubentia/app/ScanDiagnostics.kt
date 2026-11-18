package dev.cubentia.app

import android.util.Log
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.Orientation
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.FrameInspection
import dev.cubentia.domain.recognition.FrameQuality
import dev.cubentia.domain.recognition.FrameStats
import dev.cubentia.domain.recognition.PhysicalObservation
import dev.cubentia.domain.recognition.ScanAssembler
import dev.cubentia.domain.recognition.ScanTrace
import dev.cubentia.domain.recognition.SelfCalibration
import dev.cubentia.domain.tutor.Certainty
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * debug build (device session B2): when a frame with nine readable cells is not ACCEPTABLE for auto-capture, logs which
 * [FrameQuality] check failed and every measured value against its threshold under the [TAG] tag, at most one line per
 * [INTERVAL_MS], with the located face lattice (Story 10.14): its scale of the grid's side, center offset in grid cells
 * (frame axes) and search score, the face edges found and any framing coached. It also logs an ACCEPTABLE frame accepted
 * only as an outlined face, which waits the longer [dev.cubentia.domain.recognition.GuidedScanController.OUTLINED_STEADY_MS]
 * hold (PO 2026-11-16); `path=` names the path that accepts the seam check: `seams`, `outlined` or `-`. Numbers only, never pixels. Release and benchmark builds get a no-op.
 *
 * Story 10.7 device-fix round 1 (PO 2026-11-17): [onAssembly] logs one `assembly round=…` line per 6-face scan
 * assembly ([ScanTrace.describe]: branch, valid-state count, involved faces, chosen turns, each sample's read), and
 * [onQuickCheck] one `quickCheck …` line per applied result (54-letter observed vs expected, and the Certainty).
 */
object ScanDiagnostics {
    private val lastCornerLogMs = AtomicLong(Long.MIN_VALUE / 2)

    fun onCornerFrame(
        nowMs: Long,
        detected: Boolean,
        faces: List<FrameStats?>,
        score: Double,
    ) {
        val last = lastCornerLogMs.get()
        if (nowMs - last < INTERVAL_MS || !lastCornerLogMs.compareAndSet(last, nowMs)) return
        val qualities = faces.joinToString(",") { it?.let(FrameQuality::assessShutter)?.name ?: "-" }
        val fields = listOf("quickScan", "frame", "detected=$detected", "quality=$qualities", "score=${format(score)}")
        Log.d(TAG, fields.joinToString(" "))
    }

    fun onQuickScanRun(
        elapsedMs: Long,
        fallbackFaces: Int,
        repromptFaces: Int,
        corrections: Int,
        outcome: String,
    ) {
        val fields =
            listOf(
                "quickScan",
                "run",
                "elapsedMs=$elapsedMs",
                "fallbackFaces=$fallbackFaces",
                "repromptFaces=$repromptFaces",
                "corrections=$corrections",
                "outcome=$outcome",
            )
        Log.d(TAG, fields.joinToString(" "))
    }

    const val TAG = "CubentiaScanDiag"
    private const val INTERVAL_MS = 500L
    private val lastLogMs = AtomicLong(Long.MIN_VALUE / 2)

    fun onFrame(
        stats: FrameStats,
        nowMs: Long,
        latticeScale: Double,
        latticeDx: Double,
        latticeDy: Double,
        latticeScore: Double,
    ) {
        val inspection = FrameQuality.inspect(stats)
        if ((inspection.failed == null && !inspection.outlinedOnly) || inspection.readableCells != FrameQuality.CELLS) return
        val last = lastLogMs.get()
        if (nowMs - last < INTERVAL_MS || !lastLogMs.compareAndSet(last, nowMs)) return
        val lattice =
            listOf(
                "lattice=scale${format(latticeScale, 2)}/dx${format(latticeDx, 2)}/dy${format(latticeDy, 2)}/score${format(latticeScore)}",
                "faceEdges=${stats.faceEdges}/${FrameQuality.FACE_EDGES}",
                "framing=${stats.framing ?: "-"}",
            )
        Log.d(TAG, (listOf(describe(inspection)) + lattice).joinToString(","))
    }

    /**
     * Logs how [samples] assemble at [round] (`first`: the first round, which decides the re-prompt; `final`: what review
     * gets), calibrated as the scan is (self-calibration over [saved]).
     */
    fun onAssembly(
        round: String,
        samples: List<FaceSample>,
        saved: CalibrationCenters?,
    ) {
        val line =
            when (val calibration = SelfCalibration.from(samples, saved)) {
                is SelfCalibration.Calibrated -> ScanAssembler.trace(samples, calibration.calibration).describe()
                is SelfCalibration.NeedsCalibration -> "branch=NEEDS_CALIBRATION"
            }
        Log.d(TAG, listOf("assembly", "round=$round", "samples=${samples.size}", line).joinToString(","))
    }

    /** Logs a Quick Check result against [expected]: the observation's colors (54 for Full, per face for Partial) and the [certainty]. */
    fun onQuickCheck(
        expected: CubeState,
        observation: PhysicalObservation,
        certainty: Certainty?,
    ) {
        val seen =
            when (observation) {
                is PhysicalObservation.Full -> {
                    "observed=${letters(observation.state.toFacelets())}"
                }

                is PhysicalObservation.Partial -> {
                    "observedFaces=" +
                        observation.faces.entries.joinToString(",") { (face, colors) ->
                            "$face:${colors.joinToString("") { ScanTrace.letter(it) }}"
                        }
                }
            }
        val home = expected.reoriented(Orientation.HOME).toFacelets()
        val fields =
            listOf(
                "quickCheck",
                "kind=${observation::class.simpleName}",
                seen,
                "expected=${letters(expected.toFacelets())}",
                "expectedHome=${letters(home)}",
                "certainty=${certainty ?: "-"}",
            )
        Log.d(TAG, fields.joinToString(","))
    }

    private fun letters(facelets: Facelets): String = facelets.colors.joinToString("") { ScanTrace.letter(it) }

    /** One line of `key=value` fields, comma-separated; `value/minN` or `value/maxN` shows the threshold. */
    private fun describe(inspection: FrameInspection): String =
        with(inspection) {
            listOf(
                "failed=$failed",
                "quality=$quality",
                "readable=$readableCells/${FrameQuality.CELLS}",
                "plainOuter=$plainOuterCells/min${FrameQuality.MIN_PLAIN_OUTER_CELLS}",
                "seams=$seams/min${FrameQuality.MIN_SEAMS}",
                "path=${if (seams >= FrameQuality.MIN_SEAMS) {
                    "seams"
                } else if (outlinedOnly) {
                    "outlined"
                } else {
                    "-"
                }}",
                "stickerCells=$stickerCells/min${FrameQuality.MIN_STICKER_CELLS}",
                "stickerIf=C>=${FrameQuality.STICKER_MIN_CHROMA}|L>=${FrameQuality.WHITE_STICKER_MIN_L}",
                "glare=${format(glareFraction, 3)}/max${FrameQuality.MAX_GLARE_FRACTION}",
                "sharpness=${format(sharpness)}/min${FrameQuality.MIN_SHARPNESS}",
                "luma=${format(meanLuma)}/min${FrameQuality.MIN_MEAN_LUMA}",
            ).plus(chroma.indices.map { "c$it=C${format(chroma[it])}/L${format(lightness[it])}" })
                .joinToString(",")
        }

    private fun format(
        value: Double?,
        decimals: Int = 1,
    ): String = value?.let { String.format(Locale.ROOT, "%.${decimals}f", it) } ?: "-"
}
