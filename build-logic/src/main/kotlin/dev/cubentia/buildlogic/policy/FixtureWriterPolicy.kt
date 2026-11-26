package dev.cubentia.buildlogic.policy

/**
 * Story 10.34 (AD-9 Frames, AD-15): the continuous-session fixture writer is a debug-only developer tool. Every
 * non-debuggable variant (release, benchmark, `cornerR8`, `release011`) must carry no class of its package
 * `dev/cubentia/app/camera/recorder/session/`, so no Learner-installable build can store frames.
 */
object FixtureWriterPolicy {
    const val CLASS_PACKAGE = "dev/cubentia/app/camera/recorder/session/"

    fun isWriterClass(path: String): Boolean = path.replace('\\', '/').let { it.startsWith(CLASS_PACKAGE) || "/$CLASS_PACKAGE" in it }

    /** One violation per offending compiled class ([classes], paths relative to a class root or jar). */
    fun violations(classes: Collection<String>): List<String> =
        classes.filter(::isWriterClass).sorted().map { "Non-debuggable variant contains fixture-writer class $it" }
}
