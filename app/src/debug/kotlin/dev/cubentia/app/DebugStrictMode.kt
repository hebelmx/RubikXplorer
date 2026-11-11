package dev.cubentia.app

import android.os.StrictMode

/** debug build (AD-16): main-thread disk reads and writes are logged under the `StrictMode` tag. */
object DebugStrictMode {
    fun install() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .penaltyLog()
                .build(),
        )
    }
}
