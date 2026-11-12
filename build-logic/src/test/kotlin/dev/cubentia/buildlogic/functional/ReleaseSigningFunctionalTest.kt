package dev.cubentia.buildlogic.functional

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Story 7.8 (AD-15): the real `cubentia.release` plugin signs release only from a complete set of
 * `cubentia.signing.*` properties, and sets `versionCode`/`versionName` from `cubentia.version*`.
 */
class ReleaseSigningFunctionalTest {
    private fun fixture(name: String): AndroidFixture =
        AndroidFixture(name, plugins = listOf("cubentia.quality", "cubentia.release")).apply {
            projectDir.resolve("build.gradle.kts").appendText(
                """

                tasks.register("printReleaseSigning") {
                    val signing = android.buildTypes.getByName("release").signingConfig
                    val described = signing?.let { it.name + " " + it.storeType + " " + it.keyAlias } ?: "none"
                    doLast { println("SIGNING=" + described) }
                }
                """.trimIndent(),
            )
        }

    private fun AndroidFixture.mergedReleaseManifest(): String =
        projectDir
            .resolve("build/intermediates/merged_manifests/release")
            .walkTopDown()
            .first { it.name == "AndroidManifest.xml" }
            .readText()

    private val signingProps =
        listOf(
            "-Pcubentia.signing.storeFile=upload.p12",
            "-Pcubentia.signing.storePassword=pw",
            "-Pcubentia.signing.keyAlias=upload",
            "-Pcubentia.signing.keyPassword=pw",
        )

    @Test
    fun `without signing properties release stays unsigned`() {
        val output = fixture("release-unsigned").build("printReleaseSigning").output
        assertTrue(output.contains("SIGNING=none"), output)
    }

    @Test
    fun `complete signing and version properties sign release and set its version`() {
        val fixture = fixture("release-signed")
        val args = signingProps + listOf("-Pcubentia.versionCode=42", "-Pcubentia.versionName=1.2.3")
        val output = fixture.build("printReleaseSigning", "processReleaseManifest", *args.toTypedArray()).output
        assertTrue(output.contains("SIGNING=upload pkcs12 upload"), output)
        val manifest = fixture.mergedReleaseManifest()
        assertTrue(manifest.contains("android:versionCode=\"42\""), manifest)
        assertTrue(manifest.contains("android:versionName=\"1.2.3\""), manifest)
    }

    @Test
    fun `a partial signing set or a bad version fails configuration`() {
        val partial = fixture("release-partial").buildAndFail("printReleaseSigning", *signingProps.take(2).toTypedArray()).output
        assertTrue(partial.contains("missing: cubentia.signing.keyAlias, cubentia.signing.keyPassword"), partial)
        assertTrue(!partial.contains("=pw"), partial)

        val badCode = fixture("release-bad-code").buildAndFail("printReleaseSigning", "-Pcubentia.versionCode=0").output
        assertTrue(badCode.contains("cubentia.versionCode must be a positive integer"), badCode)
    }
}
