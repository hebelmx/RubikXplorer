package dev.cubentia.buildlogic.policy

/**
 * Flattens a [DeclaredDependency] to a string so the boundary task can carry it as a Gradle input:
 * `project|configuration|path` or `external|configuration|group|name|version` (empty version = none).
 */
object DeclaredDependencyCodec {
    private const val SEPARATOR = "|"

    fun encode(dependency: DeclaredDependency): String =
        when (dependency) {
            is DeclaredDependency.OnProject -> {
                listOf("project", dependency.configuration, dependency.path).joinToString(SEPARATOR)
            }

            is DeclaredDependency.External -> {
                listOf("external", dependency.configuration, dependency.group, dependency.name, dependency.version.orEmpty())
                    .joinToString(SEPARATOR)
            }
        }

    fun decode(encoded: String): DeclaredDependency {
        val parts = encoded.split(SEPARATOR)
        return when {
            parts[0] == "project" && parts.size == 3 -> {
                DeclaredDependency.OnProject(parts[1], parts[2])
            }

            parts[0] == "external" && parts.size == 5 -> {
                DeclaredDependency.External(parts[1], parts[2], parts[3], parts[4].ifEmpty { null })
            }

            else -> {
                throw IllegalArgumentException("Malformed encoded dependency '$encoded'")
            }
        }
    }
}
