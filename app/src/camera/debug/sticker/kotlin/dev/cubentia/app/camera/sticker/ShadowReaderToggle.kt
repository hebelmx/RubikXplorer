package dev.cubentia.app.camera.sticker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.cubentia.app.ScanDiagnostics

/**
 * Story 10.23: the shadow sticker reader's debug-only runtime switch, default **on**. A private preference holding this one
 * boolean; it persists across restarts and is cleared on uninstall. It is read at each capture and at each scan start.
 * Set it without reinstalling:
 *
 * `adb shell am broadcast -p com.exxerpro.cubentia -a dev.cubentia.debug.SHADOW_READER --ez on false`
 */
internal object ShadowReaderToggle {
    const val ACTION = "dev.cubentia.debug.SHADOW_READER"
    const val EXTRA_ON = "on"
    private const val FILE = "debug_shadow_reader"
    private const val KEY_ON = "on"

    fun isOn(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)

    fun set(
        context: Context,
        on: Boolean,
    ) {
        context
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ON, on)
            .apply()
    }
}

/**
 * The adb broadcast receiver for [ShadowReaderToggle] (debug manifest only; like `RECORDER_BURST`, a developer tool, never
 * Learner UI). Guarded by `android.permission.DUMP`, which the adb shell holds and apps do not. A broadcast without a
 * boolean `on` extra (missing, or sent as a string or int) is logged as ignored and changes nothing.
 */
class ShadowReaderToggleReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ShadowReaderToggle.ACTION) return
        // Only a boolean extra (--ez) counts: a mistyped --es/--ei "off" must never read as the default "on".
        @Suppress("DEPRECATION")
        val on = intent.extras?.get(ShadowReaderToggle.EXTRA_ON) as? Boolean
        if (on == null) {
            runCatching { Log.d(ScanDiagnostics.TAG, "quickScan shadowReader toggle ignored (needs --ez on true|false)") }
            return
        }
        ShadowReaderToggle.set(context, on)
        runCatching { Log.d(ScanDiagnostics.TAG, "quickScan shadowReader toggle state=${if (on) "on" else "off"}") }
    }
}
