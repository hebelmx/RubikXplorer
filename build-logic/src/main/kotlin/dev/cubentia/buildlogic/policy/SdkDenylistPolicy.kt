package dev.cubentia.buildlogic.policy

/**
 * AD-13 / NFR-8: no analytics, ads or crash-reporting SDK may reach the app's runtime classpath.
 * Patterns are globs over `group:name` where `*` matches any run of characters.
 */
object SdkDenylistPolicy {
    val patterns: List<String> =
        listOf(
            "com.google.firebase:*",
            "com.google.android.gms:play-services-ads*",
            "com.google.android.gms:play-services-analytics*",
            "com.google.android.gms:play-services-measurement*",
            "com.crashlytics*",
            "io.sentry*",
            "com.bugsnag*",
            "com.flurry*",
            "com.amplitude*",
            "com.mixpanel*",
            "com.segment*",
            "com.appsflyer*",
            "com.adjust*",
            "com.facebook.android*",
            "com.applovin*",
            "com.unity3d.ads*",
            "com.ironsource*",
            "io.branch*",
            "com.datadoghq*",
            "com.newrelic*",
            "com.instabug*",
            "ly.count*",
        )

    private val regexes: List<Pair<String, Regex>> =
        patterns.map { pattern ->
            pattern to Regex(pattern.split("*").joinToString(".*") { Regex.escape(it) })
        }

    /** The denylist pattern matching [coordinate] (`group:name[:version]`), or null. */
    fun match(coordinate: String): String? {
        val parts = coordinate.split(":")
        val groupAndName = if (parts.size >= 2) "${parts[0]}:${parts[1]}" else coordinate
        return regexes.firstOrNull { (_, regex) -> regex.matches(groupAndName) }?.first
    }

    /** Returns one violation per denylisted coordinate; empty when the classpath is clean. */
    fun check(coordinates: Collection<String>): List<String> =
        coordinates.sorted().distinct().mapNotNull { coordinate ->
            match(coordinate)?.let { pattern ->
                "Runtime classpath contains denylisted SDK $coordinate (matches $pattern); " +
                    "no analytics, ads or crash SDK is allowed (AD-13, NFR-8)."
            }
        }
}
