package dev.cubentia.buildlogic.policy

/**
 * Story 10.34: checked-in continuous sessions stay small without Git LFS. Each session directory under
 * `Fixtures/continuous/` is at most [SESSION_LIMIT_BYTES] and the whole root at most [ROOT_LIMIT_BYTES] (decimal bytes).
 */
object FixtureSizePolicy {
    const val SESSION_LIMIT_BYTES = 15_000_000L
    const val ROOT_LIMIT_BYTES = 60_000_000L

    /** One violation per session directory over its limit ([sessions]: name to bytes) and one for the root total. */
    fun violations(
        sessions: Map<String, Long>,
        rootBytes: Long,
    ): List<String> =
        sessions.entries
            .sortedBy { it.key }
            .filter { it.value > SESSION_LIMIT_BYTES }
            .map { (name, bytes) -> "Fixtures/continuous/$name is $bytes B (limit $SESSION_LIMIT_BYTES B)" } +
            listOfNotNull(
                "Fixtures/continuous/ is $rootBytes B (limit $ROOT_LIMIT_BYTES B)".takeIf { rootBytes > ROOT_LIMIT_BYTES },
            )
}
