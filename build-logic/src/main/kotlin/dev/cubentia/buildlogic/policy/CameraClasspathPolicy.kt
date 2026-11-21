package dev.cubentia.buildlogic.policy

object CameraClasspathPolicy {
    private val alwaysForbiddenWhenCameraOn = listOf("com.google.mlkit", "org.tensorflow", "org.opencv")
    private val liteRtGroup = "com.google.ai.edge.litert"
    private val forbiddenWhenCameraOff = listOf("androidx.camera", liteRtGroup) + alwaysForbiddenWhenCameraOn

    fun check(
        coordinates: Collection<String>,
        cameraEnabled: Boolean,
        cornerModelEnabled: Boolean = cameraEnabled,
    ): List<String> =
        coordinates
            .sorted()
            .distinct()
            .mapNotNull { coordinate ->
                val group = coordinate.substringBefore(":")
                when {
                    !cameraEnabled && forbiddenWhenCameraOff.any(group::startsWith) -> {
                        "Camera flags are OFF but runtime classpath contains $coordinate"
                    }

                    cameraEnabled && !cornerModelEnabled && group.startsWith(liteRtGroup) -> {
                        "Corner-detection flags are OFF but runtime classpath contains $coordinate"
                    }

                    cameraEnabled && alwaysForbiddenWhenCameraOn.any(group::startsWith) -> {
                        "Runtime classpath contains forbidden CV/ML dependency $coordinate"
                    }

                    else -> {
                        null
                    }
                }
            }
}
