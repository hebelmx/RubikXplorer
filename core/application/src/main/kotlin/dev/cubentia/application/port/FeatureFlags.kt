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

/** The camera features that carry a BETA label whenever [FeatureFlag.CAMERA_SCAN] is BETA. */
private val cameraFeatures =
    setOf(FeatureFlag.CAMERA_SCAN, FeatureFlag.QUICK_SCAN, FeatureFlag.PLACEMENT, FeatureFlag.CASE_RECOGNITION)

/**
 * Whether [flag]'s entry point shows the BETA label: the one rule for every site (EXPERIENCE *Feature flag BETA*).
 * True when [flag] is enabled and either is BETA itself or is a camera feature while [FeatureFlag.CAMERA_SCAN] is BETA.
 */
fun FeatureFlags.showsBeta(flag: FeatureFlag): Boolean {
    val own = level(flag)
    if (own == FlagLevel.OFF) return false
    if (own == FlagLevel.BETA) return true
    return flag in cameraFeatures && level(FeatureFlag.CAMERA_SCAN) == FlagLevel.BETA
}
