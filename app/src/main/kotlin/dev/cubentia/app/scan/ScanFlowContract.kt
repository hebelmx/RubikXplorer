package dev.cubentia.app.scan

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeValidator
import dev.cubentia.domain.cube.Facelets
import dev.cubentia.domain.cube.ValidationResult

/** Only validated colors cross the Activity boundary; samples stay inside the scan. */
class ScanFlowContract(
    private val launcher: ScanLauncher,
    private val quick: Boolean = false,
) : ActivityResultContract<Unit, ScanOutcome>() {
    override fun createIntent(
        context: Context,
        input: Unit,
    ): Intent = if (quick) launcher.quickScanIntent(context) else launcher.guidedScanIntent(context)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): ScanOutcome = decode(resultCode, intent)

    companion object {
        const val RESULT_FELL_BACK_TO_EDITOR = Activity.RESULT_FIRST_USER
        const val EXTRA_FACELETS = "dev.cubentia.app.scan.FACELETS"

        fun encode(outcome: ScanOutcome): Pair<Int, Intent?> =
            when (outcome) {
                is ScanOutcome.Accepted -> {
                    Activity.RESULT_OK to
                        Intent().putExtra(
                            EXTRA_FACELETS,
                            outcome.state
                                .toFacelets()
                                .colors
                                .map { it.name }
                                .toTypedArray(),
                        )
                }

                ScanOutcome.Cancelled -> {
                    Activity.RESULT_CANCELED to null
                }

                ScanOutcome.FellBackToEditor -> {
                    RESULT_FELL_BACK_TO_EDITOR to null
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
                            (CubeValidator.validate(Facelets(colors)) as? ValidationResult.Valid)?.let { ScanOutcome.Accepted(it.state) }
                                ?: ScanOutcome.Cancelled
                        }
                    }.getOrDefault(ScanOutcome.Cancelled)
                }

                RESULT_FELL_BACK_TO_EDITOR -> {
                    ScanOutcome.FellBackToEditor
                }

                else -> {
                    ScanOutcome.Cancelled
                }
            }
    }
}
