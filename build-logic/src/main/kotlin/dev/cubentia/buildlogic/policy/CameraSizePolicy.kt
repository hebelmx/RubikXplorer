package dev.cubentia.buildlogic.policy

object CameraSizePolicy {
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
