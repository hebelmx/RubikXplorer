package dev.cubentia.application.port

/** The closed set of feature flags (AD-18). */
enum class FeatureFlag {
    QUICK_SCAN,
    PLACEMENT,
    CASE_RECOGNITION,
    TEST_ME,
    CAMERA_SCAN,
    ADAPTIVE_ASSISTANCE,
}

/** Release level of a [FeatureFlag]; anything above [OFF] is enabled. */
enum class FlagLevel {
    OFF,
    BETA,
    GA,
}

/** Read-only port to the feature-flag levels set per build type in build-logic (AD-18). */
interface FeatureFlags {
    fun level(flag: FeatureFlag): FlagLevel

    fun isEnabled(flag: FeatureFlag): Boolean = level(flag) != FlagLevel.OFF
}
