package dev.cubentia.app

import dev.cubentia.domain.recognition.FrameStats

/** release and benchmark builds: no scan diagnostics are computed or logged. */
object ScanDiagnostics {
    @Suppress("UNUSED_PARAMETER")
    fun onFrame(
        stats: FrameStats,
        nowMs: Long,
    ) = Unit
}
