package dev.cubentia.buildlogic.functional

import dev.cubentia.buildlogic.policy.AccessibilityLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** AD-14: the real `cubentia.android-app` plugin makes every a11y lint id an error and aborts on errors. */
class AccessibilityLintFunctionalTest {
    @Test
    fun `the app convention plugin sets a11y lint ids to error and aborts on error`() {
        val fixture = AndroidFixture("a11y-lint", plugins = listOf("cubentia.android-app"))
        // The convention plugin reads the `libs` catalog; tests run from build-logic/.
        fixture.write("gradle/libs.versions.toml", File("../gradle/libs.versions.toml").readText())
        fixture.projectDir.resolve("build.gradle.kts").appendText(
            """

            val lintErrors = android.lint.error.sorted().joinToString(",")
            val lintAbort = android.lint.abortOnError
            tasks.register("printLintPolicy") {
                doLast {
                    println("LINT_ERROR=" + lintErrors)
                    println("LINT_ABORT=" + lintAbort)
                }
            }
            """.trimIndent(),
        )

        val output = fixture.build("printLintPolicy").output
        val errors =
            output
                .lineSequence()
                .first { it.startsWith("LINT_ERROR=") }
                .removePrefix("LINT_ERROR=")
                .split(",")
                .toSet()
        assertTrue(errors.containsAll(AccessibilityLint.ERROR_ISSUES), "lint.error=$errors\n$output")
        assertEquals("LINT_ABORT=true", output.lineSequence().first { it.startsWith("LINT_ABORT=") }, output)
    }
}
