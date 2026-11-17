package dev.cubentia.app.ui.scan

import android.content.Context

/**
 * Whether the scan review's one-time coach mark has been shown, so it appears on the Learner's first
 * review only. A private preference holding this one boolean and nothing else, like the camera denial
 * record; call it off the main thread.
 */
object ScanCoachMarkRecord {
    private const val FILE = "scan_review"
    private const val KEY_SHOWN = "coach_mark_shown"

    /** Whether the coach mark is still to be shown. */
    fun pending(context: Context): Boolean = !context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_SHOWN, false)

    /** Records that the coach mark was shown, so it never shows again. */
    fun markShown(context: Context) {
        context
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOWN, true)
            .apply()
    }
}
