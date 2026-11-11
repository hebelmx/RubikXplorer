package dev.cubentia.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CubentiaApp : Application() {
    override fun onCreate() {
        DebugStrictMode.install()
        super.onCreate()
    }
}
