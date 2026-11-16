package dev.cubentia.app.scan

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.GuidedScanPlan
import kotlinx.serialization.json.Json

/** What the Guided 6-face Scan returned; Story 10.3 maps it into a `ScanOutcome`. */
sealed interface GuidedScanCapture {
    /** Six samples, in `GuidedScanPlan` order. */
    data class Captured(
        val samples: List<FaceSample>,
    ) : GuidedScanCapture

    data object Cancelled : GuidedScanCapture

    data object FellBackToEditor : GuidedScanCapture
}

/**
 * The typed result of the Guided 6-face Scan (Story 10.2), and the only way main code starts it. The
 * result carries CIELAB samples as JSON, never pixels.
 */
class GuidedScanContract(
    private val launcher: ScanLauncher,
) : ActivityResultContract<Unit, GuidedScanCapture>() {
    override fun createIntent(
        context: Context,
        input: Unit,
    ): Intent = launcher.guidedScanIntent(context)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): GuidedScanCapture = decode(resultCode, intent)

    companion object {
        const val RESULT_FELL_BACK_TO_EDITOR = Activity.RESULT_FIRST_USER
        const val EXTRA_SAMPLES = "dev.cubentia.app.scan.SAMPLES"

        private const val STICKERS_PER_FACE = 9

        private val json = Json { encodeDefaults = true }

        /** The result code and data a scan activity finishes with for [capture]. */
        fun encode(capture: GuidedScanCapture): Pair<Int, Intent?> =
            when (capture) {
                is GuidedScanCapture.Captured -> {
                    Activity.RESULT_OK to Intent().putExtra(EXTRA_SAMPLES, json.encodeToString(capture.samples))
                }

                GuidedScanCapture.Cancelled -> {
                    Activity.RESULT_CANCELED to null
                }

                GuidedScanCapture.FellBackToEditor -> {
                    RESULT_FELL_BACK_TO_EDITOR to null
                }
            }

        /**
         * Decodes a scan result; anything malformed, or not exactly six samples whose centers follow `GuidedScanPlan`
         * and that each carry nine stickers, reads as [GuidedScanCapture.Cancelled].
         */
        fun decode(
            resultCode: Int,
            intent: Intent?,
        ): GuidedScanCapture =
            when (resultCode) {
                Activity.RESULT_OK -> {
                    intent
                        ?.getStringExtra(EXTRA_SAMPLES)
                        ?.let { runCatching { json.decodeFromString<List<FaceSample>>(it) }.getOrNull() }
                        ?.takeIf { samples -> samples.map { it.centerColor } == GuidedScanPlan.steps.map { it.centerColor } }
                        ?.takeIf { samples -> samples.all { it.stickers.size == STICKERS_PER_FACE } }
                        ?.let { GuidedScanCapture.Captured(it) }
                        ?: GuidedScanCapture.Cancelled
                }

                RESULT_FELL_BACK_TO_EDITOR -> {
                    GuidedScanCapture.FellBackToEditor
                }

                else -> {
                    GuidedScanCapture.Cancelled
                }
            }
    }
}
