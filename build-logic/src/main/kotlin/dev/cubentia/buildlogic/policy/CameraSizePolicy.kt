package dev.cubentia.buildlogic.policy

object CameraSizePolicy {
    /**
     * Debug-only camera developer tools that never ship, so they stay out of the projected camera class sum: the fixture
     * recorder (Story 10.10) and the shadow sticker reader (Story 10.23).
     */
    val debugOnlyClassGlobs: List<String> = listOf("dev/cubentia/app/camera/recorder/**", "dev/cubentia/app/camera/sticker/**")

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
