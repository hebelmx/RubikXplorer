package dev.cubentia.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.cubentia.application.port.FeatureFlags
import javax.inject.Inject

@HiltAndroidApp
class CubentiaApp : Application() {
    // Construct the gated flags during Hilt application injection, before any scan entry point.
    @Inject
    lateinit var featureFlags: FeatureFlags

    override fun onCreate() {
        DebugStrictMode.install()
        super.onCreate()
    }
}
