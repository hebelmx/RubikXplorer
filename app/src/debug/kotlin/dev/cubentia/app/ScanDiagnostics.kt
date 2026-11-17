package dev.cubentia.app

import android.util.Log
import dev.cubentia.domain.recognition.FrameInspection
import dev.cubentia.domain.recognition.FrameQuality
import dev.cubentia.domain.recognition.FrameStats
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * debug build (device session B2): when a frame with nine readable cells is not ACCEPTABLE for auto-capture, logs which
 * [FrameQuality] check failed and every measured value against its threshold under the [TAG] tag, at most one line per
 * [INTERVAL_MS], with the located face lattice (Story 10.14): its scale of the grid's side, center offset in grid cells
 * (frame axes) and search score, the face edges found and any framing coached. Numbers only, never pixels. Release and benchmark builds get a no-op.
 */
object ScanDiagnostics {
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
        if (inspection.failed == null || inspection.readableCells != FrameQuality.CELLS) return
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

    /** One line of `key=value` fields, comma-separated; `value/minN` or `value/maxN` shows the threshold. */
    private fun describe(inspection: FrameInspection): String =
        with(inspection) {
            listOf(
                "failed=$failed",
                "quality=$quality",
                "readable=$readableCells/${FrameQuality.CELLS}",
                "plainOuter=$plainOuterCells/min${FrameQuality.MIN_PLAIN_OUTER_CELLS}",
                "seams=$seams/min${FrameQuality.MIN_SEAMS}",
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
