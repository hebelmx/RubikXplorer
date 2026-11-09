package dev.cubentia.buildlogic.functional

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AD-13: the real `check<Variant>MergedManifest` tasks, wired by `cubentia.quality`, on a real merged manifest. */
class CheckMergedManifestFunctionalTest {
    private fun fixture(
        name: String,
        buildTypes: String = "",
    ) = AndroidFixture(name, permissions = listOf(CAMERA), buildTypes = buildTypes)

    private fun AndroidFixture.assertRejects(
        task: String,
        permission: String,
    ) {
        val result = buildAndFail(task)
        assertEquals(TaskOutcome.FAILED, result.task(":$task")?.outcome, result.output)
        assertTrue("Manifest declares runtime permission $permission" in result.output, result.output)
    }

    private fun AndroidFixture.assertRejectsCamera(task: String) = assertRejects(task, CAMERA)

    private fun AndroidFixture.assertAcceptsCamera(task: String) {
        val result = build(task)
        assertEquals(TaskOutcome.SUCCESS, result.task(":$task")?.outcome, result.output)
    }

    @Test
    fun `release fails on CAMERA and debug passes on the same fixture`() {
        val fixture = fixture("release-and-debug")
        fixture.assertRejectsCamera("checkReleaseMergedManifest")
        fixture.assertAcceptsCamera("checkDebugMergedManifest")
    }

    @Test
    fun `a custom non-debuggable build type fails on CAMERA`() {
        fixture("custom-non-debuggable", """create("staging") { isDebuggable = false }""")
            .assertRejectsCamera("checkStagingMergedManifest")
    }

    @Test
    fun `a custom debuggable build type passes with CAMERA`() {
        fixture("custom-debuggable", """create("qa") { isDebuggable = true }""")
            .assertAcceptsCamera("checkQaMergedManifest")
    }

    @Test
    fun `the name debug grants nothing when the variant is not debuggable`() {
        fixture("debug-not-debuggable", """getByName("debug") { isDebuggable = false }""")
            .assertRejectsCamera("checkDebugMergedManifest")
    }

    @Test
    fun `the real release and benchmark build types fail on CAMERA`() {
        val fixture =
            AndroidFixture(
                "release-plugin",
                permissions = listOf(CAMERA),
                plugins = listOf("cubentia.quality", "cubentia.release"),
            )
        fixture.assertRejectsCamera("checkReleaseMergedManifest")
        fixture.assertRejectsCamera("checkBenchmarkMergedManifest")
    }

    @Test
    fun `each task reads its own variant's merged manifest`() {
        val fixture = AndroidFixture("variant-manifests")
        fixture.write(
            "src/debug/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="$RECORD_AUDIO" />
            </manifest>
            """.trimIndent(),
        )
        val release = fixture.build("checkReleaseMergedManifest")
        assertEquals(TaskOutcome.SUCCESS, release.task(":checkReleaseMergedManifest")?.outcome, release.output)
        fixture.assertRejects("checkDebugMergedManifest", RECORD_AUDIO)
    }

    private companion object {
        const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
        const val CAMERA = "android.permission.CAMERA"
    }
}
