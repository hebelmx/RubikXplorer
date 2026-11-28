package dev.cubentia.app.camera.session

import android.app.Application
import android.content.Intent
import android.os.Looper
import dev.cubentia.app.camera.ContinuousFrameTap
import dev.cubentia.app.camera.ContinuousModelIdentity
import dev.cubentia.app.camera.QuickScanDebugView
import dev.cubentia.app.camera.QuickScanSessionHost
import dev.cubentia.domain.recognition.continuous.ContinuousScanExport
import dev.cubentia.domain.recognition.continuous.ContinuousScanHandoff
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * GH #409: the adb `QUICK_SCAN_DEBUG_VIEW` switch. `--ez on` sets the overlay toggle, no extra toggles it, another action
 * does nothing; only a sender holding `android.permission.DUMP` reaches it, and the debug binding registers it for a
 * Quick Scan screen's life only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class QuickScanDebugViewReceiverTest {
    @get:Rule val temp = TemporaryFolder()

    private val app: Application = RuntimeEnvironment.getApplication()

    @After fun off() = QuickScanDebugView.set(false)

    private fun send(on: Boolean? = null) {
        val intent = Intent(QuickScanDebugViewReceiver.ACTION).setPackage(app.packageName)
        if (on != null) intent.putExtra(QuickScanDebugViewReceiver.EXTRA_ON, on)
        app.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun theExtraSetsTheToggleAndItsAbsenceTogglesIt() {
        QuickScanDebugView.set(false)
        val receiver = QuickScanDebugViewReceiver.register(app)
        try {
            send(on = true)
            assertFalse("delivered without DUMP", QuickScanDebugView.enabled)
            shadowOf(app).grantPermissions(QuickScanSessionReceiver.SENDER_PERMISSION)
            send(on = true)
            assertTrue(QuickScanDebugView.enabled)
            send(on = true)
            assertTrue(QuickScanDebugView.enabled)
            send(on = false)
            assertFalse(QuickScanDebugView.enabled)
            send()
            assertTrue(QuickScanDebugView.enabled)
            send()
            assertFalse(QuickScanDebugView.enabled)
            // Another action is ignored, even sent straight to the receiver.
            receiver.onReceive(app, Intent("dev.cubentia.debug.OTHER").putExtra(QuickScanDebugViewReceiver.EXTRA_ON, true))
            assertFalse(QuickScanDebugView.enabled)
        } finally {
            app.unregisterReceiver(receiver)
        }
    }

    @Test
    fun theDebugBindingRegistersItForTheScreensLifeOnly() {
        QuickScanDebugView.set(false)
        shadowOf(app).grantPermissions(QuickScanSessionReceiver.SENDER_PERMISSION)
        val writers = DebugContinuousSessionWriterFactory({ temp.root }, "c".repeat(40), "2.3.2")
        val session = DebugQuickScanSessionFactory(app, writers).create(Host())
        assertTrue(shadowOf(app).registeredReceivers.any { it.broadcastReceiver is QuickScanDebugViewReceiver })
        send(on = true)
        assertTrue(QuickScanDebugView.enabled)
        session.release()
        assertTrue(shadowOf(app).registeredReceivers.none { it.broadcastReceiver is QuickScanDebugViewReceiver })
        send(on = false)
        assertTrue("reached after the screen went", QuickScanDebugView.enabled)
    }

    private class Host : QuickScanSessionHost {
        override val liveScanShown = true
        override val hostDialogShown = false
        override val modelIdentity: ContinuousModelIdentity? = null

        override fun startCapture(
            tap: ContinuousFrameTap,
            started: () -> Unit,
        ) = false

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
}
