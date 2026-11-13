package dev.cubentia.buildlogic.functional

import dev.cubentia.buildlogic.policy.PrivacyFactsPolicy
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipOutputStream

class PrivacyFactsFunctionalTest {
    private val baseline get() = File("../docs/privacy-facts.json").readText()

    private fun fixture(name: String) = AndroidFixture("privacy-$name", defaultConfig = """applicationId = "com.exxerpro.cubentia" """)

    private fun assertGuidance(fixture: AndroidFixture) {
        val result = fixture.buildAndFail("checkPrivacyFacts")
        assertEquals(TaskOutcome.FAILED, result.task(":checkPrivacyFacts")?.outcome, result.output)
        assertTrue(":writePrivacyFacts" in result.output, result.output)
        assertTrue(PrivacyFactsPolicy.SITE_URL in result.output, result.output)
        assertTrue("Expected:" in result.output, result.output)
        assertTrue("Committed:" in result.output, result.output)
    }

    @Test
    fun `clean release writes committed facts and check passes while debug differs`() {
        val fixture = fixture("clean")
        fixture.write(
            "src/debug/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <uses-permission android:name="android.permission.CAMERA"/>
            </manifest>
            """.trimIndent(),
        )
        val result = fixture.build("writePrivacyFacts", "checkPrivacyFacts")
        assertEquals(TaskOutcome.SUCCESS, result.task(":checkPrivacyFacts")?.outcome, result.output)
        assertEquals(baseline, fixture.projectDir.resolve("docs/privacy-facts.json").readText())
        val plan = fixture.build("build", "--dry-run").output
        assertTrue(":checkPrivacyFacts " in plan, plan)
        assertFalse(":writePrivacyFacts " in plan, plan)
    }

    @Test
    fun `manifest changes fail stale check and produce different facts`() {
        listOf(
            "internet" to ("""<uses-permission android:name="android.permission.INTERNET"/>""" to "false"),
            "backup" to ("" to "true"),
            "camera" to ("""<uses-permission android:name="android.permission.CAMERA"/>""" to "false"),
        ).forEach { (name, input) ->
            val fixture = fixture(name)
            fixture.write(
                "src/main/AndroidManifest.xml",
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                  ${input.first}
                  <application android:allowBackup="${input.second}"/>
                </manifest>
                """.trimIndent(),
            )
            fixture.write("docs/privacy-facts.json", baseline)
            assertGuidance(fixture)
            fixture.build("writePrivacyFacts")
            assertNotEquals(baseline, fixture.projectDir.resolve("docs/privacy-facts.json").readText())
            fixture.build("checkPrivacyFacts")
        }
    }

    @Test
    fun `release runtime dependencies and applicationId reach the facts`() {
        val fixture = AndroidFixture("privacy-dependencies", defaultConfig = """applicationId = "dev.cubentia.other" """)
        publishModule(fixture, "com.google.firebase", "firebase-fake")
        publishModule(fixture, "io.sentry", "sentry-fake")
        fixture.write(
            "build.gradle.kts",
            fixture.projectDir.resolve("build.gradle.kts").readText() +
                """

                repositories {
                    maven { url = uri("local-repo") }
                    google()
                    mavenCentral()
                }

                dependencies {
                    "implementation"("com.google.firebase:firebase-fake:1.0")
                    "debugImplementation"("io.sentry:sentry-fake:1.0")
                }
                """.trimIndent(),
        )
        fixture.write("docs/privacy-facts.json", baseline)
        assertGuidance(fixture)

        fixture.build("writePrivacyFacts")
        val facts = fixture.projectDir.resolve("docs/privacy-facts.json").readText()
        assertTrue("\"applicationId\": \"dev.cubentia.other\"" in facts, facts)
        assertTrue("\"denylistedSdks\": [\n    \"com.google.firebase:firebase-fake:1.0\"\n  ]" in facts, facts)
        assertFalse("sentry" in facts, facts)
        fixture.build("checkPrivacyFacts")
    }

    /** Publishes an empty jar module to the fixture's `local-repo` Maven repository. */
    private fun publishModule(
        fixture: AndroidFixture,
        group: String,
        module: String,
    ) {
        val dir = "local-repo/${group.replace('.', '/')}/$module/1.0"
        fixture.write(
            "$dir/$module-1.0.pom",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$group</groupId>
              <artifactId>$module</artifactId>
              <version>1.0</version>
            </project>
            """.trimIndent(),
        )
        ZipOutputStream(fixture.projectDir.resolve("$dir/$module-1.0.jar").outputStream()).use { it.finish() }
    }

    @Test
    fun `missing file fails with actionable guidance`() {
        val fixture = fixture("missing")
        assertGuidance(fixture)
        assertTrue("File is missing." in fixture.buildAndFail("checkPrivacyFacts").output)
    }
}
