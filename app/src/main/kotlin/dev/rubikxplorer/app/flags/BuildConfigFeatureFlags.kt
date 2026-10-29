package dev.rubikxplorer.app.flags

import dev.rubikxplorer.app.BuildConfig
import dev.rubikxplorer.application.port.FeatureFlag
import dev.rubikxplorer.application.port.FeatureFlags
import dev.rubikxplorer.application.port.FlagLevel
import javax.inject.Inject

/**
 * The only reader of `BuildConfig` in `:app` (AD-18): parses the `FLAG=LEVEL,...` string that
 * build-logic sets per build type. A missing, duplicate or unknown entry is a programmer error and
 * throws [IllegalStateException] at construction.
 */
class BuildConfigFeatureFlags(
    encodedLevels: String,
) : FeatureFlags {
    @Inject
    constructor() : this(BuildConfig.FEATURE_FLAG_LEVELS)

    private val levels: Map<FeatureFlag, FlagLevel> = parse(encodedLevels)

    override fun level(flag: FeatureFlag): FlagLevel = levels.getValue(flag)

    internal companion object {
        fun parse(encoded: String): Map<FeatureFlag, FlagLevel> {
            val entries =
                encoded
                    .split(",")
                    .filter { it.isNotBlank() }
                    .map { entry ->
                        val parts = entry.split("=")
                        check(parts.size == 2) { "Malformed feature-flag entry '$entry' in '$encoded'" }
                        val flag =
                            FeatureFlag.entries.firstOrNull { it.name == parts[0].trim() }
                                ?: error("Unknown feature flag '${parts[0]}' in '$encoded'")
                        val level =
                            FlagLevel.entries.firstOrNull { it.name == parts[1].trim() }
                                ?: error("Unknown level '${parts[1]}' for $flag in '$encoded'")
                        flag to level
                    }
            val duplicates = entries.groupBy({ it.first }).filterValues { it.size > 1 }.keys
            check(duplicates.isEmpty()) { "Duplicate feature flags $duplicates in '$encoded'" }
            val levels = entries.toMap()
            val missing = FeatureFlag.entries - levels.keys
            check(missing.isEmpty()) { "Feature flags $missing have no level in '$encoded'" }
            return levels
        }
    }
}
