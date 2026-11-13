package dev.cubentia.buildlogic.policy

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Release-derived facts and the fixed NFR-8 promises exported for the public privacy page. */
data class PrivacyFacts(
    val applicationId: String,
    val internetPermission: Boolean,
    val backupAllowed: Boolean,
    val runtimePermissions: List<String>,
    val denylistedSdks: List<String>,
) {
    fun toJson(): String =
        buildString {
            appendLine("{")
            appendLine("  \"schemaVersion\": 1,")
            appendLine("  \"applicationId\": ${quote(applicationId)},")
            appendLine("  \"internetPermission\": $internetPermission,")
            appendLine("  \"backupAllowed\": $backupAllowed,")
            appendLine("  \"runtimePermissions\": ${array(runtimePermissions)},")
            appendLine("  \"denylistedSdks\": ${array(denylistedSdks)},")
            appendLine("  \"account\": ${PrivacyFactsPolicy.ACCOUNT},")
            appendLine("  \"ads\": ${PrivacyFactsPolicy.ADS},")
            appendLine("  \"analytics\": ${PrivacyFactsPolicy.ANALYTICS},")
            appendLine("  \"crashReporting\": ${PrivacyFactsPolicy.CRASH_REPORTING},")
            appendLine("  \"dataLeavesDevice\": ${PrivacyFactsPolicy.DATA_LEAVES_DEVICE},")
            appendLine("  \"storage\": ${quote(PrivacyFactsPolicy.STORAGE)}")
            appendLine("}")
        }

    private fun array(values: List<String>): String =
        if (values.isEmpty()) "[]" else values.sorted().distinct().joinToString(",\n", "[\n", "\n  ]") { "    ${quote(it)}" }

    private fun quote(value: String): String =
        buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '"' -> {
                        append('\\')
                        append('"')
                    }

                    '\\' -> {
                        append("\\\\")
                    }

                    else -> {
                        if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
                    }
                }
            }
            append('"')
        }
}

object PrivacyFactsPolicy {
    const val ACCOUNT = false
    const val ADS = false
    const val ANALYTICS = false
    const val CRASH_REPORTING = false
    const val DATA_LEAVES_DEVICE = false
    const val STORAGE = "app-private"
    const val SITE_URL = "https://hebelmx.github.io/cubentia-site/privacy/"
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    fun derive(
        applicationId: String,
        manifestXml: String,
        runtimeCoordinates: Collection<String>,
    ): PrivacyFacts {
        val root =
            DocumentBuilderFactory
                .newInstance()
                .apply {
                    isNamespaceAware = true
                    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }.newDocumentBuilder()
                .parse(InputSource(StringReader(manifestXml)))
                .documentElement
        val permissions =
            listOf("uses-permission", "uses-permission-sdk-23")
                .flatMap { root.childElements(it) }
                .map { it.getAttributeNS(ANDROID_NS, "name") }
                .distinct()
        return PrivacyFacts(
            applicationId = applicationId,
            internetPermission = ManifestPolicy.INTERNET in permissions,
            backupAllowed = root.childElements("application").firstOrNull()?.getAttributeNS(ANDROID_NS, "allowBackup") != "false",
            runtimePermissions = permissions.filter(ManifestPolicy::isRuntimePermission).sorted(),
            denylistedSdks = runtimeCoordinates.filter { SdkDenylistPolicy.match(it) != null }.distinct().sorted(),
        )
    }

    private fun Element.childElements(tag: String): List<Element> =
        (0 until childNodes.length)
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { (it.localName ?: it.tagName) == tag }
}
