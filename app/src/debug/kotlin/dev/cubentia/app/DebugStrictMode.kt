package dev.cubentia.app

import android.os.StrictMode

/** debug build (AD-16): main-thread disk reads, disk writes and network access are logged under the `StrictMode` tag. */
object DebugStrictMode {
    fun install() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
    }
}
