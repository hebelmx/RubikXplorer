package dev.cubentia.buildlogic.policy

object CameraClasspathPolicy {
    private val forbiddenGroups = listOf("com.google.ai.edge.litert", "org.tensorflow", "org.opencv")

    fun check(
        coordinates: Collection<String>,
        cameraEnabled: Boolean,
    ): List<String> =
        if (cameraEnabled) {
            emptyList()
        } else {
            coordinates
                .sorted()
                .distinct()
                .filter {
                    val group = it.substringBefore(":")
                    group == "androidx.camera" || group == "com.google.mlkit" || forbiddenGroups.any(group::startsWith)
                }.map { "Camera flags are OFF but runtime classpath contains $it" }
        }
}
