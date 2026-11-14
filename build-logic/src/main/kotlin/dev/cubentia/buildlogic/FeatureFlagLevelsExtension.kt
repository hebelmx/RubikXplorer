package dev.cubentia.buildlogic

import dev.cubentia.buildlogic.policy.FeatureFlagLevels

/**
 * `cubentiaFeatureFlags { byBuildType }`: the per-project feature-flag levels shared by
 * `cubentia.release` (BuildConfig field, camera composition) and `cubentia.quality` (manifest and
 * camera-classpath gates). A TestKit seam only: it defaults to [FeatureFlagLevels.byBuildType],
 * which stays the only production source of levels, and `:app` never sets it.
 */
open class FeatureFlagLevelsExtension {
    var byBuildType: Map<String, Map<String, String>> = FeatureFlagLevels.byBuildType

    /** Set by `cubentia.release`, the only plugin that applies levels and composes the camera source set. */
    internal var composesCamera: Boolean = false

    /** Levels for [buildType]; an unknown build type counts as every flag OFF. */
    fun levels(buildType: String?): Map<String, String> = byBuildType[buildType] ?: FeatureFlagLevels.flags.associateWith { "OFF" }

    /** True when [buildType] has a camera flag above OFF and `cubentia.release` composes the camera into it. */
    fun isCameraEnabled(buildType: String?): Boolean = composesCamera && FeatureFlagLevels.isCameraEnabled(levels(buildType))
}
