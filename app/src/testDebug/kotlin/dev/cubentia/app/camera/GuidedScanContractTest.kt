package dev.cubentia.app.camera

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.cubentia.app.scan.ScanFlowContract
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GuidedScanContractTest {
    private val contract = ScanFlowContract(CameraScanLauncher())

    @Test fun `launcher opens the camera Activity`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val intent = contract.createIntent(app, Unit)
        assertEquals(GuidedScanActivity::class.java.name, intent.component?.className)
        assertNotNull("GuidedScanActivity is declared in the merged manifest", app.packageManager.resolveActivity(intent, 0))
    }

    @Test fun `each typed outcome round trips`() {
        val scrambled = CubeState.SOLVED.apply((Notation.parse("R U F' L2 D B' R2 U'") as NotationResult.Parsed).moves)
        listOf(
            ScanOutcome.Accepted(CubeState.SOLVED),
            ScanOutcome.Accepted(scrambled),
            ScanOutcome.Cancelled,
            ScanOutcome.FellBackToEditor,
        ).forEach {
            val (code, intent) = ScanFlowContract.encode(it)
            assertEquals(it, contract.parseResult(code, intent))
        }
    }

    @Test fun `missing malformed incomplete oversized and invalid colors cancel`() {
        val solved =
            CubeState.SOLVED
                .toFacelets()
                .colors
                .map { it.name }
        val invalid = solved.toMutableList().also { it[0] = "RED" }
        listOf(
            null,
            emptyArray(),
            arrayOf(
                "{",
            ),
            solved.dropLast(1).toTypedArray(),
            (solved + "WHITE").toTypedArray(),
            invalid.toTypedArray(),
            solved
                .map {
                    "PURPLE"
                }.toTypedArray(),
        ).forEach { colors ->
            val intent = colors?.let { Intent().putExtra(ScanFlowContract.EXTRA_FACELETS, it) }
            assertEquals(ScanOutcome.Cancelled, contract.parseResult(Activity.RESULT_OK, intent))
        }
        assertEquals(
            ScanOutcome.Cancelled,
            contract.parseResult(Activity.RESULT_OK, Intent().putExtra(ScanFlowContract.EXTRA_FACELETS, 42)),
        )
        assertEquals(ScanOutcome.Cancelled, contract.parseResult(77, null))
    }
}
