package dev.cubentia.app.scan

import android.content.Intent
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.Lab

/** Run-scan colors (GH #172) as an Intent extra: 18 doubles, L a b per color in [Color.entries] order. */
internal object RunScanColorsExtra {
    const val EXTRA_RUN_SCAN_COLORS = "dev.cubentia.app.scan.RUN_SCAN_COLORS"
    private const val CHANNELS = 3

    fun put(
        intent: Intent,
        colors: CalibrationCenters?,
    ): Intent = colors?.let { intent.putExtra(EXTRA_RUN_SCAN_COLORS, encode(it)) } ?: intent

    fun get(intent: Intent?): CalibrationCenters? =
        runCatching {
            intent?.getDoubleArrayExtra(EXTRA_RUN_SCAN_COLORS)?.let(::decode)
        }.getOrNull()

    fun encode(colors: CalibrationCenters): DoubleArray =
        Color.entries
            .flatMap { color ->
                val lab = colors.centers.getValue(color)
                listOf(lab.l, lab.a, lab.b)
            }.toDoubleArray()

    /** Lenient: a wrong length or an invalid Lab reads as null, so the caller keeps today's behavior. */
    fun decode(values: DoubleArray): CalibrationCenters? {
        if (values.size != Color.entries.size * CHANNELS) return null
        val centers =
            Color.entries
                .mapIndexed { i, color -> color to Lab(values[i * CHANNELS], values[i * CHANNELS + 1], values[i * CHANNELS + 2]) }
                .toMap()
        return CalibrationCenters(centers).takeIf { centers.values.all { it.isValid() } }
    }
}
