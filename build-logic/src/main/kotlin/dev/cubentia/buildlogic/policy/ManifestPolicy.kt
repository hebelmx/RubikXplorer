package dev.cubentia.buildlogic.policy

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * AD-13 rules for a merged `AndroidManifest.xml`: offline (no `INTERNET`), no backup, and only the
 * runtime (dangerous) permissions the variant allows. A debuggable variant may declare `CAMERA`; a
 * non-debuggable one (`release`, `benchmark`) may declare `CAMERA` and nothing else only when its
 * build type has a camera flag above OFF (Story 10.9), and none otherwise. A camera `uses-feature`
 * is a violation wherever `CAMERA` is not allowed. The rule keys on `variant.debuggable`, never on
 * a build-type name, so no build type can opt out by what it is called.
 *
 * Every variant also rejects the background/network plumbing an offline app never needs (Story 10.18:
 * LiteRT's Play AI-pack download path merged it into corner-flag builds): [offlineForbiddenPermissions],
 * any `FOREGROUND_SERVICE*` permission, and Play asset/AI-delivery or WorkManager components.
 */
object ManifestPolicy {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    const val INTERNET = "android.permission.INTERNET"

    const val CAMERA = "android.permission.CAMERA"

    private const val FOREGROUND_SERVICE_PREFIX = "android.permission.FOREGROUND_SERVICE"

    /** Normal permissions that only serve background downloads or scheduling, which offline Cubentia never does (AD-13). */
    val offlineForbiddenPermissions: Set<String> =
        setOf(
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.WAKE_LOCK",
        )

    /** Component-name prefixes of Play Core asset/AI-pack delivery and WorkManager (AD-13). */
    val forbiddenComponentPrefixes: List<String> = listOf("com.google.android.play.core.", "androidx.work.")

    private val componentTags = listOf("activity", "activity-alias", "service", "receiver", "provider")

    /** Runtime permissions a variant may declare: `CAMERA` when [debuggable] or [cameraEnabled], none otherwise. */
    fun allowedRuntimePermissions(
        debuggable: Boolean,
        cameraEnabled: Boolean = false,
    ): Set<String> = if (debuggable || cameraEnabled) setOf(CAMERA) else emptySet()

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

                permission in offlineForbiddenPermissions || permission.startsWith(FOREGROUND_SERVICE_PREFIX) -> {
                    violations += "Manifest declares $permission; the offline app has no background downloads or services (AD-13)."
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

        if (CAMERA !in allowedRuntimePermissions) {
            root.childElements("uses-feature").forEach { feature ->
                val name = feature.getAttributeNS(ANDROID_NS, "name")
                if (name.startsWith("android.hardware.camera")) {
                    violations += "Manifest declares camera uses-feature $name while CAMERA is not allowed (AD-13)."
                }
            }
        }
        val application = root.childElements("application").firstOrNull()
        componentTags
            .flatMap { tag -> application?.childElements(tag).orEmpty() }
            .map { it.getAttributeNS(ANDROID_NS, "name") }
            .filter { name -> forbiddenComponentPrefixes.any(name::startsWith) }
            .forEach { name -> violations += "Manifest declares Play delivery/WorkManager component $name (AD-13)." }
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
