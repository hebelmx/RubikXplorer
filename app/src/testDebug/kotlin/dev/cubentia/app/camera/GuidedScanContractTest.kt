package dev.cubentia.app.camera

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.cubentia.app.scan.ScanFlowContract
import dev.cubentia.app.scan.ScanOutcome
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun `only the Settings calibration intent opens calibration mode`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val calibration = CameraScanLauncher().calibrationIntent(app)
        assertEquals(GuidedScanActivity::class.java.name, calibration.component?.className)
        assertEquals(true, calibration.getBooleanExtra(GuidedScanActivity.CALIBRATION_MODE, false))
        assertEquals(false, contract.createIntent(app, Unit).getBooleanExtra(GuidedScanActivity.CALIBRATION_MODE, false))
    }

    @Test fun `each typed outcome round trips`() {
        val scrambled = CubeState.SOLVED.apply((Notation.parse("R U F' L2 D B' R2 U'") as NotationResult.Parsed).moves)
        listOf(
            ScanOutcome.Accepted(CubeState.SOLVED),
            ScanOutcome.Accepted(scrambled),
            ScanOutcome.Cancelled,
            ScanOutcome.FellBackToEditor(),
            ScanOutcome.FellBackToEditor(stickers),
            ScanOutcome.FellBackToEditor(List(54) { null }),
        ).forEach {
            val (code, intent) = ScanFlowContract.encode(it)
            assertEquals(it, contract.parseResult(code, intent))
        }
    }

    /** A partial read (GH #321): face U read, the rest unset except the centers, which are always home colors. */
    private val stickers =
        List(54) { i ->
            when {
                i < 9 -> Color.WHITE
                i % 9 == 4 -> Color.home(Face.entries[i / 9])
                else -> null
            }
        }

    @Test fun `a fallback's sticker extra decodes leniently to no stickers`() {
        val names = stickers.map { it?.name.orEmpty() }
        listOf(
            null,
            Intent(),
            Intent().putExtra(ScanFlowContract.EXTRA_FALLBACK_STICKERS, names.dropLast(1).toTypedArray()),
            Intent().putExtra(ScanFlowContract.EXTRA_FALLBACK_STICKERS, (names + "WHITE").toTypedArray()),
            Intent().putExtra(ScanFlowContract.EXTRA_FALLBACK_STICKERS, names.map { if (it.isEmpty()) "PURPLE" else it }.toTypedArray()),
            Intent().putExtra(ScanFlowContract.EXTRA_FALLBACK_STICKERS, 42),
        ).forEach { intent ->
            assertEquals(ScanOutcome.FellBackToEditor(null), contract.parseResult(ScanFlowContract.RESULT_FELL_BACK_TO_EDITOR, intent))
        }
        // No stickers, no extra.
        assertNull(ScanFlowContract.encode(ScanOutcome.FellBackToEditor()).second)
    }

    @Test fun `only a Solve contract marks its scan as seeding the Editor`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val launcher = CameraScanLauncher()
        for (quick in listOf(false, true)) {
            assertTrue(
                ScanFlowContract(launcher, quick, seedsEditor = true).createIntent(app, Unit).hasExtra(ScanFlowContract.EXTRA_SEEDS_EDITOR),
            )
            assertTrue(
                ScanFlowContract(launcher, quick, seedsEditor = true)
                    .createIntent(app, Unit)
                    .getBooleanExtra(ScanFlowContract.EXTRA_SEEDS_EDITOR, false),
            )
            assertFalse(ScanFlowContract(launcher, quick).createIntent(app, Unit).hasExtra(ScanFlowContract.EXTRA_SEEDS_EDITOR))
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
