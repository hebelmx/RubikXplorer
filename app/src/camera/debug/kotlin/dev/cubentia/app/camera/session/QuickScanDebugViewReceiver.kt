package dev.cubentia.app.camera.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import dev.cubentia.app.camera.QuickScanDebugView

/**
 * GH #409: the adb switch of the Quick Scan debug overlay (detector peaks and fitted lattices):
 * `adb shell am broadcast -a dev.cubentia.debug.QUICK_SCAN_DEBUG_VIEW [--ez on true|false] -p com.exxerpro.cubentia`.
 * `--ez on` sets [QuickScanDebugView]; without the extra it toggles. Registered only from the debug binding while a
 * Quick Scan screen lives, and like [QuickScanSessionReceiver] only senders holding `android.permission.DUMP` (the
 * shell) reach it.
 */
internal class QuickScanDebugViewReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return
        val on =
            if (intent.hasExtra(EXTRA_ON)) {
                intent.getBooleanExtra(EXTRA_ON, false).also(QuickScanDebugView::set)
            } else {
                QuickScanDebugView.toggle()
            }
        runCatching { Log.i(QuickScanSessionController.TAG, "debugView ${if (on) "on" else "off"}") }
    }

    companion object {
        const val ACTION = "dev.cubentia.debug.QUICK_SCAN_DEBUG_VIEW"
        const val EXTRA_ON = "on"

        /** Registers a receiver on the main thread; unregister it with [Context.unregisterReceiver]. */
        fun register(context: Context): QuickScanDebugViewReceiver =
            QuickScanDebugViewReceiver().also { receiver ->
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(ACTION),
                    QuickScanSessionReceiver.SENDER_PERMISSION,
                    null,
                    ContextCompat.RECEIVER_EXPORTED,
                )
            }
    }
}
