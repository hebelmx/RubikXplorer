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
    ): Verdict {
        require(releaseEstimate >= 0 && classBytes >= 0 && runtimeBytes >= 0)
        val estimate = Math.addExact(Math.addExact(releaseEstimate, Math.multiplyExact(3, classBytes)), runtimeBytes)
        val forbidden =
            artifacts
                .filter { artifact ->
                    listOf("litert", "tensorflow", "opencv").any { artifact.contains(it, ignoreCase = true) }
                }.distinct()
                .sorted()
        return Verdict(estimate, forbidden)
    }
}
