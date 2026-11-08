package dev.rubikxplorer.buildlogic.policy

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * AD-13 rules for a merged `AndroidManifest.xml`: offline (no `INTERNET`), no backup, and only the
 * runtime (dangerous) permissions the build type allows — none for `release` (v1.0 is camera-free)
 * and any other non-debug build type such as `benchmark`; `debug` may declare `CAMERA` (the
 * debug-only `spikeScan` source set).
 */
object ManifestPolicy {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    const val INTERNET = "android.permission.INTERNET"

    const val CAMERA = "android.permission.CAMERA"

    private const val DEBUG_BUILD_TYPE = "debug"

    /** Runtime permissions a variant of [buildType] may declare: `CAMERA` for `debug`, none otherwise. */
    fun allowedRuntimePermissions(buildType: String?): Set<String> = if (buildType == DEBUG_BUILD_TYPE) setOf(CAMERA) else emptySet()

    /**
     * Android permissions with `protectionLevel="dangerous"` (runtime permissions).
     *
     * Source: the `android.Manifest.permission` entries whose protection level is `dangerous`, as of
     * API 37 (the current compileSdk). Refresh this list whenever compileSdk rises.
     */
    val dangerousPermissions: Set<String> =
        listOf(
            "ACCEPT_HANDOVER",
            "ACCESS_BACKGROUND_LOCATION",
            "ACCESS_COARSE_LOCATION",
            "ACCESS_FINE_LOCATION",
            "ACCESS_MEDIA_LOCATION",
            "ACTIVITY_RECOGNITION",
            "ADD_VOICEMAIL",
            "ANSWER_PHONE_CALLS",
            "BLUETOOTH_ADVERTISE",
            "BLUETOOTH_CONNECT",
            "BLUETOOTH_SCAN",
            "BODY_SENSORS",
            "BODY_SENSORS_BACKGROUND",
            "CALL_PHONE",
            "CAMERA",
            "GET_ACCOUNTS",
            "NEARBY_WIFI_DEVICES",
            "POST_NOTIFICATIONS",
            "PROCESS_OUTGOING_CALLS",
            "RANGING",
            "READ_CALENDAR",
            "READ_CALL_LOG",
            "READ_CONTACTS",
            "READ_EXTERNAL_STORAGE",
            "READ_MEDIA_AUDIO",
            "READ_MEDIA_IMAGES",
            "READ_MEDIA_VIDEO",
            "READ_MEDIA_VISUAL_USER_SELECTED",
            "READ_PHONE_NUMBERS",
            "READ_PHONE_STATE",
            "READ_SMS",
            "RECEIVE_MMS",
            "RECEIVE_SMS",
            "RECEIVE_WAP_PUSH",
            "RECORD_AUDIO",
            "SEND_SMS",
            "USE_SIP",
            "UWB_RANGING",
            "WRITE_CALENDAR",
            "WRITE_CALL_LOG",
            "WRITE_CONTACTS",
            "WRITE_EXTERNAL_STORAGE",
        ).mapTo(mutableSetOf()) { "android.permission.$it" }

    /** Health Connect data permissions are all runtime permissions. */
    private const val HEALTH_PERMISSION_PREFIX = "android.permission.health."

    fun isRuntimePermission(name: String): Boolean = name in dangerousPermissions || name.startsWith(HEALTH_PERMISSION_PREFIX)

    /**
     * Returns one violation per broken rule; empty when the manifest satisfies AD-13. Any runtime
     * permission not in [allowedRuntimePermissions] is a violation.
     */
    fun check(
        manifestXml: String,
        allowedRuntimePermissions: Set<String>,
    ): List<String> {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply {
                    isNamespaceAware = true
                    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }.newDocumentBuilder()
                .parse(InputSource(StringReader(manifestXml)))
        val root = document.documentElement
        val violations = mutableListOf<String>()

        val permissions =
            listOf("uses-permission", "uses-permission-sdk-23")
                .flatMap { root.childElements(it) }
                .map { it.getAttributeNS(ANDROID_NS, "name") }
                .distinct()
        permissions.forEach { permission ->
            when {
                permission == INTERNET -> {
                    violations += "Manifest declares $INTERNET; the app is offline (AD-13, NFR-8)."
                }

                isRuntimePermission(permission) && permission !in allowedRuntimePermissions -> {
                    val allowed =
                        if (allowedRuntimePermissions.isEmpty()) {
                            "no runtime permission is allowed"
                        } else {
                            "only ${allowedRuntimePermissions.sorted()} is allowed"
                        }
                    violations += "Manifest declares runtime permission $permission; $allowed (AD-13)."
                }
            }
        }

        val application = root.childElements("application").firstOrNull()
        val allowBackup = application?.getAttributeNS(ANDROID_NS, "allowBackup").orEmpty()
        when {
            allowBackup.isEmpty() -> {
                violations += "Manifest <application> must set android:allowBackup=\"false\" (AD-13); it is missing."
            }

            allowBackup != "false" -> {
                violations +=
                    "Manifest <application> must set android:allowBackup=\"false\" (AD-13); found \"$allowBackup\"."
            }
        }
        return violations
    }

    private fun Element.childElements(tag: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length)
            .map { nodes.item(it) }
            .filterIsInstance<Element>()
            .filter { (it.localName ?: it.tagName) == tag }
    }
}
