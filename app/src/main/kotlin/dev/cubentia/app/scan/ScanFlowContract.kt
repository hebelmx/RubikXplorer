package dev.cubentia.app.scan

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.ValidationResult

/**
 * Only validated colors (and a guided run's measured centers, GH #172) cross the Activity boundary; samples stay inside
 * the scan. A fallback to the Editor carries the scan's stickers as colour names (GH #321); [seedsEditor] marks a
 * Solve-tab scan ([EXTRA_SEEDS_EDITOR]), whose caller seeds its Editor from them.
 */
class ScanFlowContract(
    private val launcher: ScanLauncher,
    private val quick: Boolean = false,
    private val seedsEditor: Boolean = false,
) : ActivityResultContract<Unit, ScanOutcome>() {
    override fun createIntent(
        context: Context,
        input: Unit,
    ): Intent =
        (if (quick) launcher.quickScanIntent(context) else launcher.guidedScanIntent(context)).also {
            if (seedsEditor) it.putExtra(EXTRA_SEEDS_EDITOR, true)
        }

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): ScanOutcome = decode(resultCode, intent)

    companion object {
        const val RESULT_FELL_BACK_TO_EDITOR = Activity.RESULT_FIRST_USER
        const val EXTRA_FACELETS = "dev.cubentia.app.scan.FACELETS"

        /** The fallback's 54 home-frame stickers as colour names, "" for an unset sticker (GH #321). */
        const val EXTRA_FALLBACK_STICKERS = "dev.cubentia.app.scan.FALLBACK_STICKERS"

        /** Set only by a Solve-tab scan: its Editor is seeded from a fallback's stickers (GH #321). */
        const val EXTRA_SEEDS_EDITOR = "dev.cubentia.app.scan.SEEDS_EDITOR"

        fun encode(outcome: ScanOutcome): Pair<Int, Intent?> =
            when (outcome) {
                is ScanOutcome.Accepted -> {
                    Activity.RESULT_OK to
                        RunScanColorsExtra.put(
                            Intent().putExtra(
                                EXTRA_FACELETS,
                                outcome.state
                                    .toFacelets()
                                    .colors
                                    .map { it.name }
                                    .toTypedArray(),
                            ),
                            outcome.runScanColors,
                        )
                }

                ScanOutcome.Cancelled -> {
                    Activity.RESULT_CANCELED to null
                }

                is ScanOutcome.FellBackToEditor -> {
                    RESULT_FELL_BACK_TO_EDITOR to
                        outcome.stickers?.let { stickers ->
                            Intent().putExtra(EXTRA_FALLBACK_STICKERS, stickers.map { it?.name.orEmpty() }.toTypedArray())
                        }
                }
            }

        fun decode(
            resultCode: Int,
            intent: Intent?,
        ): ScanOutcome =
            when (resultCode) {
                Activity.RESULT_OK -> {
                    runCatching {
                        val colors = intent?.getStringArrayExtra(EXTRA_FACELETS)?.map(Color::valueOf)
                        if (colors?.size != Facelets.SIZE) {
                            ScanOutcome.Cancelled
                        } else {
                            (CubeValidator.validate(Facelets(colors)) as? ValidationResult.Valid)?.let {
                                ScanOutcome.Accepted(it.state, RunScanColorsExtra.get(intent))
                            }
                                ?: ScanOutcome.Cancelled
                        }
                    }.getOrDefault(ScanOutcome.Cancelled)
                }

                RESULT_FELL_BACK_TO_EDITOR -> {
                    ScanOutcome.FellBackToEditor(fallbackStickers(intent))
                }

                else -> {
                    ScanOutcome.Cancelled
                }
            }

        /** A fallback's stickers; null when missing, not 54 long or holding a name that is no colour (lenient). */
        private fun fallbackStickers(intent: Intent?): List<Color?>? =
            runCatching {
                intent
                    ?.getStringArrayExtra(EXTRA_FALLBACK_STICKERS)
                    ?.takeIf { it.size == Facelets.SIZE }
                    ?.map { name -> if (name.isEmpty()) null else Color.valueOf(name) }
            }.getOrNull()
    }
}
