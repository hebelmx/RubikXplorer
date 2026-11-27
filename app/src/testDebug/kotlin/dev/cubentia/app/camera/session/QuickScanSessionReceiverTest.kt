package dev.cubentia.app.camera.session

import android.app.Application
import android.content.Intent
import android.os.Looper
import dev.cubentia.app.camera.ContinuousFrameTap
import dev.cubentia.app.camera.ContinuousModelIdentity
import dev.cubentia.app.camera.QuickScanSessionHost
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.ContinuousScanHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Story 10.34 (answers 12, 16): the adb `RECORDER_SESSION` receiver. It requires `android.permission.DUMP` of its
 * sender (the shell holds it, a third-party app cannot get it), is registered by the debug binding for a Quick Scan
 * screen's life only, and its commands mirror the on-screen controls, an unavailable one logged under `QuickScanSession`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class QuickScanSessionReceiverTest {
    @get:Rule val temp = TemporaryFolder()

    private val app: Application = RuntimeEnvironment.getApplication()

    private fun intent(
        command: String,
        vararg extras: Pair<String, String>,
    ) = Intent(
        QuickScanSessionReceiver.ACTION,
    ).setPackage(app.packageName).putExtra(QuickScanSessionReceiver.EXTRA_COMMAND, command).apply {
        extras.forEach { (key, value) -> putExtra(key, value) }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Idles the main looper until [done] (the session's io thread posts back to main). */
    private fun awaitMain(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            idle()
            Thread.sleep(5)
        }
    }

    private class Host : QuickScanSessionHost {
        var tap: ContinuousFrameTap? = null
        override val liveScanShown = true
        override val hostDialogShown = false
        override val modelIdentity: ContinuousModelIdentity? = null

        override fun startCapture(
            tap: ContinuousFrameTap,
            started: () -> Unit,
        ): Boolean {
            this.tap = tap
            started()
            return true
        }

        override fun export(consume: (ContinuousScanExport) -> Unit): Boolean {
            consume(ContinuousScanExport.Partial(ContinuousScanHandoff(emptyMap())))
            return true
        }

        override fun resetMap() = Unit

        override fun startBaseline(
            tap: ContinuousFrameTap,
            started: () -> Unit,
        ) = false

        override fun endBaseline(ended: () -> Unit) = ended()

        override fun releaseHandOff() = Unit
    }

    @Test
    fun aSenderWithoutDumpIsNotDeliveredAndOneWithDumpIs() {
        val received = mutableListOf<QuickScanSessionCommand>()
        val receiver = QuickScanSessionReceiver.register(app) { received += it }
        try {
            app.sendBroadcast(intent("start"))
            idle()
            assertTrue("delivered without DUMP: $received", received.isEmpty())
            shadowOf(app).grantPermissions(QuickScanSessionReceiver.SENDER_PERMISSION)
            app.sendBroadcast(
                intent(
                    "SAVE",
                    QuickScanSessionReceiver.EXTRA_CUBE to "stla",
                    QuickScanSessionReceiver.EXTRA_LIGHTING to "warm",
                    QuickScanSessionReceiver.EXTRA_PARTITION to "HELDOUT",
                    QuickScanSessionReceiver.EXTRA_LABEL to "run1",
                ),
            )
            idle()
            assertEquals(listOf(QuickScanSessionCommand("save", "stla", "warm", "HELDOUT", "run1")), received)
        } finally {
            app.unregisterReceiver(receiver)
        }
    }

    @Test
    fun theDebugBindingRegistersItForTheScreensLifeAndLogsIgnoredCommands() {
        shadowOf(app).grantPermissions(QuickScanSessionReceiver.SENDER_PERMISSION)
        val host = Host()
        val writers = DebugContinuousSessionWriterFactory({ temp.root }, "c".repeat(40), "2.3.2")
        val session = DebugQuickScanSessionFactory(app, writers).create(host) as QuickScanSessionController
        ShadowLog.clear()
        app.sendBroadcast(intent("save"))
        idle()
        val logs = ShadowLog.getLogsForTag(QuickScanSessionController.TAG).map { it.msg }
        assertTrue(logs.toString(), logs.any { it.startsWith("save ignored") })
        // An adb start shows the same indicator as Rec.
        app.sendBroadcast(intent("start", QuickScanSessionReceiver.EXTRA_LABEL to "adb"))
        awaitMain { session.phase is SessionPhase.Recording }
        assertEquals("● REC 0 · 0 skips", QuickScanSessionController.recIndicator(session.status))
        // A discard while recording stops first, then discards.
        app.sendBroadcast(intent("discard"))
        awaitMain { session.phase == SessionPhase.Idle }
        assertTrue(
            temp.root
                .listFiles()
                .orEmpty()
                .none { it.isDirectory },
        )
        // Gone with the screen: a later command reaches nothing.
        session.release()
        app.sendBroadcast(intent("start"))
        idle()
        assertEquals(SessionPhase.Idle, session.phase)
        assertTrue(shadowOf(app).registeredReceivers.none { it.broadcastReceiver is QuickScanSessionReceiver })
    }
}
