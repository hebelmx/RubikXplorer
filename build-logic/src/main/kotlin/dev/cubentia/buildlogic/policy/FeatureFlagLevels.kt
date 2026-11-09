package dev.cubentia.buildlogic.policy

/**
 * AD-18: feature-flag levels per build type. This map is the only place levels are set; the
 * release plugin encodes it into the `FEATURE_FLAG_LEVELS` BuildConfig field read by `:app`.
 *
 * Flag and level names mirror `dev.cubentia.application.port.FeatureFlag` / `FlagLevel`;
 * the `:app` adapter rejects any mismatch at construction.
 */
object FeatureFlagLevels {
    val flags: List<String> =
        listOf("QUICK_SCAN", "PLACEMENT", "CASE_RECOGNITION", "TEST_ME", "CAMERA_SCAN", "ADAPTIVE_ASSISTANCE")

    val levels: Set<String> = setOf("OFF", "BETA", "GA")

    private val releaseLevels: Map<String, String> = flags.associateWith { "OFF" }

    /**
     * debug includes every feature (AD-15); release raises each flag in its own feature story.
     * benchmark measures what ships, so it always carries the release levels.
     */
    val byBuildType: Map<String, Map<String, String>> =
        mapOf(
            "debug" to flags.associateWith { "GA" },
            "release" to releaseLevels,
            "benchmark" to releaseLevels,
        )

    /** Encodes [levelsByFlag] as `FLAG=LEVEL,...` in [flags] order, rejecting a missing or unknown entry. */
    fun encode(levelsByFlag: Map<String, String>): String {
        val unknownFlags = levelsByFlag.keys - flags.toSet()
        require(unknownFlags.isEmpty()) { "Unknown feature flags: $unknownFlags" }
        return flags.joinToString(",") { flag ->
            val level = requireNotNull(levelsByFlag[flag]) { "Feature flag $flag has no level" }
            require(level in levels) { "Feature flag $flag has unknown level $level" }
            "$flag=$level"
        }
    }

    fun forBuildType(buildType: String): String {
        val levelsByFlag =
            requireNotNull(byBuildType[buildType]) {
                "Build type '$buildType' has no feature-flag levels; add it to FeatureFlagLevels.byBuildType."
            }
        return encode(levelsByFlag)
    }
}
