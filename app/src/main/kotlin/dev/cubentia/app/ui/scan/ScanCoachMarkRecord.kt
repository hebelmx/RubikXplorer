package dev.cubentia.app.ui.scan

import android.content.Context

/**
 * Whether the Learner has dismissed the scan review's one-time coach mark, so it appears until they
 * tap it away (PO 2026-11-16), then never again. A private preference holding this one boolean and nothing else, like the camera denial
 * record; call it off the main thread.
 */
object ScanCoachMarkRecord {
    private const val FILE = "scan_review"
    private const val KEY_SEEN = "coach_mark_shown"

    /** Whether the coach mark is still to be shown. */
    fun pending(context: Context): Boolean = !context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

    /** Records that the Learner tapped the coach mark to dismiss it, so it never shows again. */
    fun markSeen(context: Context) {
        context
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SEEN, true)
            .apply()
    }
}
