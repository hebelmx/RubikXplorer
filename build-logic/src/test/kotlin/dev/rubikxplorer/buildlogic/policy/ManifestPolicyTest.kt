package dev.rubikxplorer.buildlogic.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ManifestPolicyTest {
    private fun manifest(
        permissions: List<String> = emptyList(),
        application: String = """<application android:allowBackup="false" />""",
    ): String =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.rubikxplorer">
            ${permissions.joinToString("\n") { """<uses-permission android:name="$it" />""" }}
            $application
        </manifest>
        """.trimIndent()

    @Test
    fun `clean manifest with CAMERA and no backup passes`() {
        assertEquals(emptyList<String>(), ManifestPolicy.check(manifest(listOf("android.permission.CAMERA"))))
    }

    @Test
    fun `no permissions passes`() {
        assertEquals(emptyList<String>(), ManifestPolicy.check(manifest()))
    }

    @Test
    fun `INTERNET fails naming it`() {
        val violations = ManifestPolicy.check(manifest(listOf("android.permission.INTERNET")))
        assertEquals(1, violations.size)
        assertTrue("android.permission.INTERNET" in violations.single())
    }

    @Test
    fun `INTERNET via uses-permission-sdk-23 fails`() {
        val xml =
            manifest(
                application =
                    """<uses-permission-sdk-23 android:name="android.permission.INTERNET" />""" +
                        """<application android:allowBackup="false" />""",
            )
        assertEquals(1, ManifestPolicy.check(xml).size)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_CONTACTS",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.health.READ_STEPS",
        ],
    )
    fun `runtime permission other than CAMERA fails naming it`(permission: String) {
        val violations = ManifestPolicy.check(manifest(listOf(permission)))
        assertEquals(1, violations.size)
        assertTrue(permission in violations.single())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "dev.rubikxplorer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            "android.permission.WAKE_LOCK",
            "android.permission.VIBRATE",
        ],
    )
    fun `normal and signature permissions pass`(permission: String) {
        assertEquals(emptyList<String>(), ManifestPolicy.check(manifest(listOf(permission))))
    }

    @Test
    fun `allowBackup missing fails`() {
        val violations = ManifestPolicy.check(manifest(application = "<application />"))
        assertEquals(1, violations.size)
        assertTrue("allowBackup" in violations.single())
    }

    @Test
    fun `allowBackup true fails`() {
        val violations = ManifestPolicy.check(manifest(application = """<application android:allowBackup="true" />"""))
        assertEquals(1, violations.size)
        assertTrue("allowBackup" in violations.single())
    }

    @Test
    fun `missing application element fails`() {
        assertEquals(1, ManifestPolicy.check(manifest(application = "")).size)
    }
}
