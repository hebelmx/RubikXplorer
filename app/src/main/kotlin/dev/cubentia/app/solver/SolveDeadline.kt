package dev.cubentia.app.solver

import android.content.pm.ApplicationInfo

/**
 * The one solve deadline of this app process (AD-8, AD-21; SCP 2026-11-27 solver-deadline-tier), passed to `SolveCube`,
 * `SolveByMethod` and `PlanRecovery`: [RELEASE_MILLIS] in release and benchmark builds, [DEBUGGABLE_MILLIS] when the
 * build is debuggable (`ApplicationInfo.FLAG_DEBUGGABLE`). There is no device tier. The Solver's own search cap is
 * separate and unchanged.
 */
data class SolveDeadline(
    val millis: Long,
) {
    companion object {
        const val RELEASE_MILLIS: Long = 3_000L
        const val DEBUGGABLE_MILLIS: Long = 5_000L

        /** The release and benchmark deadline; also the default where nothing is injected (tests, previews). */
        val RELEASE: SolveDeadline = SolveDeadline(RELEASE_MILLIS)

        fun of(debuggable: Boolean): SolveDeadline = if (debuggable) SolveDeadline(DEBUGGABLE_MILLIS) else RELEASE

        fun of(info: ApplicationInfo): SolveDeadline = of(isDebuggable(info))

        /** Whether [info] is a debuggable build. */
        fun isDebuggable(info: ApplicationInfo): Boolean = info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}
