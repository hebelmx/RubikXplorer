package dev.cubentia.app.scan

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.recognition.CornerView

data class CaseCaptureInput(
    val corners: Int,
) {
    init {
        require(corners in 1..2)
    }
}

sealed interface CaseCaptureOutcome {
    data class Captured(
        val views: List<CornerView>,
    ) : CaseCaptureOutcome

    data object Cancelled : CaseCaptureOutcome

    data object FellBackToEditor : CaseCaptureOutcome
}

class CaseCaptureContract(
    private val launcher: ScanLauncher,
) : ActivityResultContract<CaseCaptureInput, CaseCaptureOutcome>() {
    override fun createIntent(
        context: Context,
        input: CaseCaptureInput,
    ): Intent = launcher.caseCaptureIntent(context, input.corners)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): CaseCaptureOutcome = decode(resultCode, intent)

    companion object {
        private const val EXTRA_COLORS = "dev.cubentia.app.scan.CASE_COLORS"
        private const val EXTRA_EDITOR = "dev.cubentia.app.scan.CASE_EDITOR"
        private const val ABSENT = "-"
        private const val PER_FACE = 9
        private const val PER_CORNER = 3 * PER_FACE

        fun encode(views: List<CornerView>): Pair<Int, Intent> =
            Activity.RESULT_OK to
                Intent().putExtra(
                    EXTRA_COLORS,
                    views.flatMap { it.top + it.left + it.right }.map { it?.name ?: ABSENT }.toTypedArray(),
                )

        fun editor(): Pair<Int, Intent> = Activity.RESULT_OK to Intent().putExtra(EXTRA_EDITOR, true)

        fun decode(
            resultCode: Int,
            intent: Intent?,
        ): CaseCaptureOutcome {
            if (resultCode != Activity.RESULT_OK || intent == null) return CaseCaptureOutcome.Cancelled
            return runCatching {
                if (intent.getBooleanExtra(EXTRA_EDITOR, false)) {
                    if (intent.hasExtra(EXTRA_COLORS)) return CaseCaptureOutcome.Cancelled
                    return CaseCaptureOutcome.FellBackToEditor
                }
                val colors = intent.getStringArrayExtra(EXTRA_COLORS) ?: return CaseCaptureOutcome.Cancelled
                if (colors.size != PER_CORNER && colors.size != 2 * PER_CORNER) return CaseCaptureOutcome.Cancelled
                CaseCaptureOutcome.Captured(
                    colors.asList().chunked(PER_CORNER).map { corner ->
                        val faces = corner.map { if (it == ABSENT) null else Color.valueOf(it) }.chunked(PER_FACE)
                        CornerView(faces[0], faces[1], faces[2])
                    },
                )
            }.getOrDefault(CaseCaptureOutcome.Cancelled)
        }
    }
}
