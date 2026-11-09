package dev.cubentia.buildlogic.policy

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
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.exxerpro.cubentia">
            ${permissions.joinToString("\n") { """<uses-permission android:name="$it" />""" }}
            $application
        </manifest>
        """.trimIndent()

    private val release = ManifestPolicy.allowedRuntimePermissions(debuggable = false)
    private val debug = ManifestPolicy.allowedRuntimePermissions(debuggable = true)

    @Test
    fun `a debuggable variant allows only CAMERA, a non-debuggable variant allows none`() {
        assertEquals(setOf("android.permission.CAMERA"), debug)
        assertEquals(emptySet<String>(), release)
    }

    @Test
    fun `debuggable manifest with CAMERA and no backup passes`() {
        assertEquals(emptyList<String>(), ManifestPolicy.check(manifest(listOf("android.permission.CAMERA")), debug))
    }

    @Test
    fun `non-debuggable manifest with CAMERA fails naming it`() {
        val violations = ManifestPolicy.check(manifest(listOf("android.permission.CAMERA")), release)
        assertEquals(1, violations.size)
        assertTrue("android.permission.CAMERA" in violations.single())
    }

    @Test
    fun `no permissions passes`() {
        assertEquals(emptyList<String>(), ManifestPolicy.check(manifest(), release))
    }

    @Test
    fun `INTERNET fails naming it`() {
        val violations = ManifestPolicy.check(manifest(listOf("android.permission.INTERNET")), release)
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
        assertEquals(1, ManifestPolicy.check(xml, release).size)
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
    fun `runtime permission other than CAMERA fails naming it whether or not debuggable`(permission: String) {
        listOf(debug, release).forEach { allowed ->
            val violations = ManifestPolicy.check(manifest(listOf(permission)), allowed)
            assertEquals(1, violations.size)
            assertTrue(permission in violations.single())
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "com.exxerpro.cubentia.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            "android.permission.WAKE_LOCK",
            "android.permission.VIBRATE",
        ],
    )
    fun `normal and signature permissions pass`(permission: String) {
        assertEquals(emptyList<String>(), ManifestPolicy.check(manifest(listOf(permission)), release))
    }

    @Test
    fun `allowBackup missing fails`() {
        val violations = ManifestPolicy.check(manifest(application = "<application />"), release)
        assertEquals(1, violations.size)
        assertTrue("allowBackup" in violations.single())
    }

    @Test
    fun `allowBackup true fails`() {
        val violations = ManifestPolicy.check(manifest(application = """<application android:allowBackup="true" />"""), release)
        assertEquals(1, violations.size)
        assertTrue("allowBackup" in violations.single())
    }

    @Test
    fun `missing application element fails`() {
        assertEquals(1, ManifestPolicy.check(manifest(application = ""), release).size)
    }
}
