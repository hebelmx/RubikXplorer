package dev.cubentia.app.scan

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import dev.cubentia.application.session.Effect
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.PhysicalObservation

/** Optional physical-mode capture; the caller owns the revisioned session effect. */
fun interface QuickCheckCapture {
    fun start(effect: Effect.QuickCheck)
}

sealed interface QuickCheckOutcome {
    data class Observed(
        val observation: PhysicalObservation,
    ) : QuickCheckOutcome

    data object Cancelled : QuickCheckOutcome
}

data class QuickCheckInput(
    val faces: Set<Face>,
    val fullScan: Boolean = false,
    val hasCalibration: Boolean = false,
)

/** Full scans use the existing review contract; subsets carry only the faces actually observed. */
class QuickCheckContract(
    private val launcher: ScanLauncher,
) : ActivityResultContract<QuickCheckInput, QuickCheckOutcome>() {
    override fun createIntent(
        context: Context,
        input: QuickCheckInput,
    ): Intent =
        if (input.fullScan || input.faces.size == Face.entries.size || !input.hasCalibration) {
            launcher.guidedScanIntent(context)
        } else {
            launcher.quickCheckIntent(context, input.faces)
        }

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): QuickCheckOutcome = decode(resultCode, intent)

    companion object {
        const val EXTRA_FACES = "dev.cubentia.app.scan.QUICK_CHECK_FACES"
        private const val EXTRA_COLORS = "dev.cubentia.app.scan.QUICK_CHECK_COLORS"

        fun encode(observation: PhysicalObservation.Partial): Pair<Int, Intent> =
            Activity.RESULT_OK to
                Intent()
                    .putExtra(
                        EXTRA_FACES,
                        observation.faces.keys
                            .map { it.name }
                            .toTypedArray(),
                    ).putExtra(
                        EXTRA_COLORS,
                        observation.faces.values
                            .flatten()
                            .map { it.name }
                            .toTypedArray(),
                    )

        fun decode(
            resultCode: Int,
            intent: Intent?,
        ): QuickCheckOutcome {
            if (resultCode != Activity.RESULT_OK || intent == null) return QuickCheckOutcome.Cancelled
            if (!intent.hasExtra(EXTRA_FACES)) {
                return when (val full = ScanFlowContract.decode(resultCode, intent)) {
                    is ScanOutcome.Accepted -> QuickCheckOutcome.Observed(PhysicalObservation.Full(full.state))
                    else -> QuickCheckOutcome.Cancelled
                }
            }
            return runCatching {
                val faces = intent.getStringArrayExtra(EXTRA_FACES)?.map(Face::valueOf) ?: return QuickCheckOutcome.Cancelled
                val colors = intent.getStringArrayExtra(EXTRA_COLORS)?.map(Color::valueOf) ?: return QuickCheckOutcome.Cancelled
                if (faces.distinct().size != faces.size || colors.size != faces.size * 9) return QuickCheckOutcome.Cancelled
                QuickCheckOutcome.Observed(
                    PhysicalObservation.Partial(
                        faces
                            .mapIndexed { i, face ->
                                face to
                                    colors.subList(i * 9, i * 9 + 9)
                            }.toMap(),
                    ),
                )
            }.getOrDefault(QuickCheckOutcome.Cancelled)
        }
    }
}
