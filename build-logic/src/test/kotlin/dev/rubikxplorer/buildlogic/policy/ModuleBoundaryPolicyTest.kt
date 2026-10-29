package dev.rubikxplorer.buildlogic.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ModuleBoundaryPolicyTest {
    private fun external(coordinate: String): DeclaredDependency.External {
        val (group, name, version) = coordinate.split(":")
        return DeclaredDependency.External("implementation", group, name, version)
    }

    private fun project(path: String) = DeclaredDependency.OnProject("implementation", path)

    @Test
    fun `core module with only Kotlin JVM dependencies passes`() {
        val violations =
            ModuleBoundaryPolicy.check(
                ":core:domain",
                listOf(
                    external("org.jetbrains.kotlin:kotlin-stdlib:2.4.21"),
                    external("org.junit.jupiter:junit-jupiter:6.1.3"),
                ),
                listOf("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlinx.kover"),
            )
        assertEquals(emptyList<String>(), violations)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "androidx.core:core:1.17.0",
            "com.android.tools:desugar_jdk_libs:2.1.5",
            "com.google.android.material:material:1.13.0",
            "com.google.dagger:hilt-android:2.60.1",
            "com.google.dagger:dagger:2.60.1",
            "androidx.room3:room3-runtime:3.0.3",
            "androidx.camera:camera-core:1.6.2",
            "androidx.compose.ui:ui:1.12.1",
            "org.jetbrains.compose.runtime:runtime:1.9.0",
        ],
    )
    fun `core module declaring an Android Compose Room CameraX or Hilt dependency fails naming it`(coordinate: String) {
        val violations = ModuleBoundaryPolicy.check(":core:domain", listOf(external(coordinate)))
        assertEquals(1, violations.size)
        assertTrue(":core:domain" in violations.single())
        assertTrue(coordinate in violations.single())
    }

    @Test
    fun `group roots match whole segments only`() {
        assertTrue(!ModuleBoundaryPolicy.isForbiddenGroup("androidxyz.tools"))
        assertTrue(!ModuleBoundaryPolicy.isForbiddenGroup("com.androidplot"))
        assertTrue(ModuleBoundaryPolicy.isForbiddenGroup("androidx"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "com.android.library",
            "com.android.application",
            "org.jetbrains.kotlin.android",
            "org.jetbrains.kotlin.plugin.compose",
            "com.google.dagger.hilt.android",
        ],
    )
    fun `core module applying an Android plugin fails naming it`(pluginId: String) {
        val violations = ModuleBoundaryPolicy.check(":core:solver", emptyList(), listOf(pluginId))
        assertEquals(1, violations.size)
        assertTrue(":core:solver" in violations.single())
        assertTrue(pluginId in violations.single())
    }

    @Test
    fun `AD-1 edges pass`() {
        val graph =
            mapOf(
                ":app" to listOf(":core:application", ":core:solver", ":core:domain"),
                ":core:application" to listOf(":core:domain"),
                ":core:solver" to listOf(":core:application", ":core:domain"),
                ":core:domain" to emptyList(),
            )
        graph.forEach { (module, targets) ->
            assertEquals(emptyList<String>(), ModuleBoundaryPolicy.check(module, targets.map(::project)), module)
        }
    }

    @Test
    fun `edges outside AD-1 fail`() {
        assertEquals(1, ModuleBoundaryPolicy.check(":core:domain", listOf(project(":core:application"))).size)
        assertEquals(1, ModuleBoundaryPolicy.check(":core:application", listOf(project(":core:solver"))).size)
        assertEquals(1, ModuleBoundaryPolicy.check(":core:domain", listOf(project(":app"))).size)
    }

    @Test
    fun `module outside the graph fails`() {
        assertEquals(1, ModuleBoundaryPolicy.check(":core:extra", emptyList()).size)
    }

    @Test
    fun `app may use Android dependencies`() {
        assertEquals(emptyList<String>(), ModuleBoundaryPolicy.check(":app", listOf(external("androidx.core:core:1.17.0"))))
    }
}
