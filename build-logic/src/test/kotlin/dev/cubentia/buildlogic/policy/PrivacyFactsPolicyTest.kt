package dev.cubentia.buildlogic.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class PrivacyFactsPolicyTest {
    private fun manifest(
        backup: String = """android:allowBackup="false" """,
        permissions: String = "",
    ) = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">$permissions<application $backup /></manifest>"""

    private fun derive(
        xml: String = manifest(),
        coordinates: List<String> = emptyList(),
    ) = PrivacyFactsPolicy.derive("com.exxerpro.cubentia", xml, coordinates)

    @Test
    fun `v1 facts have exact committed shape and ordering`() {
        val facts = derive()
        assertFalse(facts.internetPermission)
        assertFalse(facts.backupAllowed)
        assertEquals(emptyList<String>(), facts.runtimePermissions)
        assertEquals(emptyList<String>(), facts.denylistedSdks)
        assertEquals(
            """
            {
              "schemaVersion": 1,
              "applicationId": "com.exxerpro.cubentia",
              "internetPermission": false,
              "backupAllowed": false,
              "runtimePermissions": [],
              "denylistedSdks": [],
              "account": false,
              "ads": false,
              "analytics": false,
              "crashReporting": false,
              "dataLeavesDevice": false,
              "storage": "app-private"
            }
            """.trimIndent() + "\n",
            facts.toJson(),
        )
        assertEquals(File("../docs/privacy-facts.json").readText(), facts.toJson())
    }

    @Test
    fun `internet and backup are derived including Android backup default`() {
        assertTrue(derive(manifest(permissions = """<uses-permission android:name="android.permission.INTERNET"/>""")).internetPermission)
        assertTrue(derive(manifest("""android:allowBackup="true" """)).backupAllowed)
        assertTrue(derive(manifest("")).backupAllowed)
    }

    @Test
    fun `permissions and denylisted coordinates are distinct and sorted`() {
        val permissions =
            listOf("RECORD_AUDIO", "CAMERA", "CAMERA").joinToString("") {
                """<uses-permission-sdk-23 android:name="android.permission.$it"/>"""
            }
        val facts =
            derive(
                manifest(permissions = permissions),
                listOf("io.sentry:sentry:2", "androidx.core:core:1", "com.google.firebase:analytics:1", "io.sentry:sentry:2"),
            )
        assertEquals(listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"), facts.runtimePermissions)
        assertEquals(listOf("com.google.firebase:analytics:1", "io.sentry:sentry:2"), facts.denylistedSdks)
        assertTrue(facts.toJson().contains("[\n    \"android.permission.CAMERA\",\n    \"android.permission.RECORD_AUDIO\"\n  ]"))
    }

    @Test
    fun `JSON escapes string values and rejects document types`() {
        assertTrue(derive().copy(applicationId = "a\"\\\n").toJson().contains("\"a\\\"\\\\\\u000a\""))
        assertThrows(Exception::class.java) { derive("""<!DOCTYPE manifest [<!ENTITY x "test">]><manifest/>""") }
    }
}
