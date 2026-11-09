package dev.cubentia.buildlogic.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SdkDenylistPolicyTest {
    @Test
    fun `clean runtime classpath passes`() {
        val classpath =
            listOf(
                "androidx.activity:activity-compose:1.13.0",
                "androidx.compose.material3:material3:1.4.0",
                "com.google.dagger:hilt-android:2.60.1",
                "org.jetbrains.kotlin:kotlin-stdlib:2.4.21",
                "com.google.android.gms:play-services-basement:18.5.0",
            )
        assertEquals(emptyList<String>(), SdkDenylistPolicy.check(classpath))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "com.google.firebase:firebase-analytics:23.0.0",
            "com.google.firebase:firebase-crashlytics:20.0.0",
            "com.google.android.gms:play-services-ads:24.0.0",
            "com.google.android.gms:play-services-ads-lite:24.0.0",
            "com.google.android.gms:play-services-analytics:18.1.0",
            "com.google.android.gms:play-services-measurement-api:23.0.0",
            "com.crashlytics.sdk.android:crashlytics:2.10.1",
            "io.sentry:sentry-android:8.0.0",
            "com.bugsnag:bugsnag-android:6.0.0",
            "com.flurry.android:analytics:14.0.0",
            "com.amplitude:analytics-android:1.0.0",
            "com.mixpanel.android:mixpanel-android:8.0.0",
            "com.segment.analytics.kotlin:android:1.0.0",
            "com.appsflyer:af-android-sdk:6.0.0",
            "com.adjust.sdk:adjust-android:5.0.0",
            "com.facebook.android:facebook-core:18.0.0",
            "com.applovin:applovin-sdk:13.0.0",
            "com.unity3d.ads:unity-ads:4.0.0",
            "com.ironsource.sdk:mediationsdk:8.0.0",
            "io.branch.sdk.android:library:5.0.0",
            "com.datadoghq:dd-sdk-android-rum:2.0.0",
            "com.newrelic.agent.android:android-agent:7.0.0",
            "com.instabug.library:instabug:14.0.0",
            "ly.count.android:sdk:24.0.0",
        ],
    )
    fun `denylisted SDK fails naming the coordinate`(coordinate: String) {
        val violations = SdkDenylistPolicy.check(listOf("androidx.core:core:1.17.0", coordinate))
        assertEquals(1, violations.size)
        assertTrue(coordinate in violations.single())
    }
}
