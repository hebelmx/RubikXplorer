package dev.cubentia.buildlogic.policy

object CameraSizePolicy {
    /**
     * Debug-only camera developer tools that never ship, so they stay out of the projected camera class sum: the retired
     * shadow sticker reader package guard (Story 10.23 / 10.31) and the Quick Scan session recording (Story 10.34).
     */
    val debugOnlyClassGlobs: List<String> = listOf("dev/cubentia/app/camera/sticker/**", "dev/cubentia/app/camera/session/**")

    /**
     * Legacy 7-keypoint corner detector (`CASE_RECOGNITION`, OFF in 0.11.0 and v1.0.0; Story 10.31), excluded from
     * `checkCameraSize`'s 0.11.0 continuous Quick Scan projection.
     */
    val legacyCornerClassGlobs: List<String> = listOf("dev/cubentia/app/camera/corner/**")

    data class Verdict(
        val estimatedBytes: Long,
        val forbidden: List<String>,
    ) {
        val passes: Boolean get() = estimatedBytes < AppSizePolicy.BUDGET && forbidden.isEmpty()
    }

    fun evaluate(
        releaseEstimate: Long,
        classBytes: Long,
        runtimeBytes: Long,
        artifacts: Collection<String>,
        allowLiteRt: Boolean = false,
        assetBytes: Long = 0L,
    ): Verdict {
        require(releaseEstimate >= 0 && classBytes >= 0 && runtimeBytes >= 0 && assetBytes >= 0)
        val estimate =
            Math.addExact(
                Math.addExact(Math.addExact(releaseEstimate, Math.multiplyExact(3, classBytes)), runtimeBytes),
                assetBytes,
            )
        val forbiddenTokens = if (allowLiteRt) listOf("tensorflow", "opencv") else listOf("litert", "tensorflow", "opencv")
        val forbidden =
            artifacts
                .filter { artifact ->
                    forbiddenTokens.any { artifact.contains(it, ignoreCase = true) }
                }.distinct()
                .sorted()
        return Verdict(estimate, forbidden)
    }
}
