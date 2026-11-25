package dev.cubentia.app.render

/**
 * Device speed tiers for the 10,000-step render sync test (GitHub #314), keyed on the device's total RAM.
 *
 * Measured runs of `tenThousandRandomStepsKeepTheCubeEqualToTheVirtualState`: the Galaxy S24 FE (mid-range
 * reference device, 8 GB, about 7.3e9 bytes reported) passes in 183 s; the Lenovo TB128XU (minimum reference
 * device, 4 GB, about 3.7e9 bytes reported) needs 464.6 s, so the old fixed 300 s limit always failed there.
 * MINIMUM (900 s) gives the Lenovo about 1.9x margin; anything the rule cannot place faster falls to MINIMUM.
 */
enum class DeviceSpeedTier(
    val renderSyncTimeoutMillis: Long,
) {
    MINIMUM(900_000),
    MID(600_000),
    HIGH(300_000),
    ;

    companion object {
        const val MID_MIN_BYTES: Long = 6L * 1024 * 1024 * 1024
        const val HIGH_MIN_BYTES: Long = 10L * 1024 * 1024 * 1024

        /** Unknown or non-positive RAM, and anything below 6 GiB, is MINIMUM; 6 GiB up to 10 GiB is MID; 10 GiB and up is HIGH. */
        fun fromTotalMemoryBytes(bytes: Long): DeviceSpeedTier =
            when {
                bytes >= HIGH_MIN_BYTES -> HIGH
                bytes >= MID_MIN_BYTES -> MID
                else -> MINIMUM
            }
    }
}
