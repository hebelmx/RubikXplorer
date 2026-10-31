package dev.rubikxplorer.buildlogic.policy

/** A dependency as declared in a build script, before resolution. */
sealed interface DeclaredDependency {
    val configuration: String

    data class External(
        override val configuration: String,
        val group: String,
        val name: String,
        val version: String?,
    ) : DeclaredDependency {
        val coordinate: String get() = listOfNotNull(group, name, version).joinToString(":")
    }

    data class OnProject(
        override val configuration: String,
        val path: String,
    ) : DeclaredDependency
}

/**
 * AD-1: the hexagonal module graph and the purity of `:core:*`.
 *
 * Pure: the Gradle task gathers declared dependencies and applied plugin ids and calls [check].
 */
object ModuleBoundaryPolicy {
    /**
     * Allowed project edges; nothing else may depend on anything else. `:macrobenchmark` targets
     * `:app` through `targetProjectPath`, never through a project dependency (see [testedTargets]).
     */
    val allowedEdges: Map<String, Set<String>> =
        mapOf(
            ":app" to setOf(":core:application", ":core:solver", ":core:domain"),
            ":core:application" to setOf(":core:domain"),
            ":core:solver" to setOf(":core:application", ":core:domain"),
            ":core:domain" to emptySet(),
            ":macrobenchmark" to emptySet(),
        )

    /** AGP's `com.android.test` wires `targetProjectPath` as a project edge on this configuration. */
    const val TESTED_APKS_CONFIGURATION = "testedApks"

    /**
     * The one target each test module may reach on [TESTED_APKS_CONFIGURATION]; that edge carries
     * the APK under test, not code. Any other project edge of a test module is still a violation.
     */
    val testedTargets: Map<String, String> = mapOf(":macrobenchmark" to ":app")

    /** Group prefixes matched on dot-separated segments (`androidx` matches `androidx.core`, not `androidxyz`). */
    private val forbiddenGroupRoots = listOf("androidx", "com.android", "com.google.android")

    /** Group prefixes matched on raw text (`com.google.dagger*`, `org.jetbrains.compose*`). */
    private val forbiddenGroupPrefixes = listOf("com.google.dagger", "org.jetbrains.compose")

    /** Plugin ids that make a module Android, Compose or Hilt; the boundary task probes exactly these. */
    val forbiddenPluginIds: List<String> =
        listOf(
            "com.android.application",
            "com.android.library",
            "com.android.test",
            "com.android.dynamic-feature",
            "com.android.kotlin.multiplatform.library",
            "com.android.base",
            "org.jetbrains.kotlin.android",
            "org.jetbrains.kotlin.plugin.compose",
            "org.jetbrains.compose",
            "com.google.dagger.hilt.android",
            "androidx.room",
            "androidx.room3",
        )

    fun isForbiddenGroup(group: String): Boolean =
        forbiddenGroupRoots.any { group == it || group.startsWith("$it.") } ||
            forbiddenGroupPrefixes.any { group.startsWith(it) }

    fun isForbiddenPlugin(id: String): Boolean = id in forbiddenPluginIds

    /**
     * Returns one human-readable violation per offending dependency or plugin of [module];
     * empty when the module respects AD-1.
     */
    fun check(
        module: String,
        dependencies: Collection<DeclaredDependency>,
        appliedPluginIds: Collection<String> = emptyList(),
    ): List<String> {
        val violations = mutableListOf<String>()
        val isCore = module.startsWith(":core:")
        val allowed = allowedEdges[module]
        if (allowed == null) {
            violations += "Module $module is not in the AD-1 module graph ${allowedEdges.keys}."
        }
        dependencies.forEach { dependency ->
            when (dependency) {
                is DeclaredDependency.OnProject -> {
                    val isTestedTarget =
                        dependency.configuration == TESTED_APKS_CONFIGURATION && testedTargets[module] == dependency.path
                    if (dependency.path != module && allowed != null && dependency.path !in allowed && !isTestedTarget) {
                        violations +=
                            "Module $module must not depend on ${dependency.path} (${dependency.configuration}); " +
                            "AD-1 allows only ${allowed.sorted()}."
                    }
                }

                is DeclaredDependency.External -> {
                    if (isCore && isForbiddenGroup(dependency.group)) {
                        violations +=
                            "Module $module must stay pure Kotlin/JVM (AD-1) but declares " +
                            "${dependency.coordinate} (${dependency.configuration}): " +
                            "no Android, Compose, Room, CameraX or Hilt in :core:*."
                    }
                }
            }
        }
        if (isCore) {
            appliedPluginIds.filter(::isForbiddenPlugin).distinct().forEach { id ->
                violations += "Module $module must stay pure Kotlin/JVM (AD-1) but applies plugin $id."
            }
        }
        return violations
    }
}
