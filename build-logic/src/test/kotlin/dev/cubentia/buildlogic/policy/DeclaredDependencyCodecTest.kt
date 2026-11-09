package dev.cubentia.buildlogic.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeclaredDependencyCodecTest {
    private fun roundTrip(dependency: DeclaredDependency) =
        assertEquals(dependency, DeclaredDependencyCodec.decode(DeclaredDependencyCodec.encode(dependency)))

    @Test
    fun `project dependency round-trips`() {
        roundTrip(DeclaredDependency.OnProject("implementation", ":core:domain"))
    }

    @Test
    fun `external dependency round-trips`() {
        roundTrip(DeclaredDependency.External("testImplementation", "androidx.core", "core", "1.17.0"))
    }

    @Test
    fun `external dependency without version round-trips`() {
        roundTrip(DeclaredDependency.External("implementation", "org.junit.jupiter", "junit-jupiter", null))
    }

    @Test
    fun `decoded external dependency still trips the boundary policy`() {
        val encoded = DeclaredDependencyCodec.encode(DeclaredDependency.External("implementation", "androidx.core", "core", "1.17.0"))
        val violations = ModuleBoundaryPolicy.check(":core:domain", listOf(DeclaredDependencyCodec.decode(encoded)))
        assertEquals(1, violations.size)
    }

    @Test
    fun `malformed input is rejected`() {
        assertThrows<IllegalArgumentException> { DeclaredDependencyCodec.decode("external|implementation|androidx.core") }
        assertThrows<IllegalArgumentException> { DeclaredDependencyCodec.decode("other|x|y") }
    }
}
