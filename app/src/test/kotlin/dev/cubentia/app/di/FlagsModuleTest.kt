package dev.cubentia.app.di

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import dev.cubentia.app.flags.BuildConfigFeatureFlags
import dev.cubentia.app.scan.ScanLauncher
import dev.cubentia.app.scan.ScanPortGate
import dev.cubentia.application.port.FeatureFlag
import dev.cubentia.application.port.FeatureFlags
import dev.cubentia.application.port.FlagLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Optional

/** Story 10.9: the production `FeatureFlags` provider that `CubentiaApp` injects at startup. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FlagsModuleTest {
    /** Camera flags GA, every other flag BETA, so masking is distinguishable from pass-through. */
    private val buildFlags =
        BuildConfigFeatureFlags(
            FeatureFlag.entries.joinToString(",") { "$it=${if (it in ScanPortGate.cameraFlags) "GA" else "BETA"}" },
        )

    private fun context(debuggable: Boolean): Context =
        RuntimeEnvironment.getApplication().apply {
            applicationInfo.flags =
                if (debuggable) {
                    applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
                } else {
                    applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
                }
        }

    private fun launcher(hasCamera: Boolean): Optional<ScanLauncher> =
        Optional.of(
            object : ScanLauncher {
                override fun hasCamera(context: Context) = hasCamera

                override fun guidedScanIntent(context: Context) = Intent()
            },
        )

    private fun assertCameraFlags(
        expected: FlagLevel,
        flags: FeatureFlags,
    ) = FeatureFlag.entries.forEach {
        assertEquals(it.name, if (it in ScanPortGate.cameraFlags) expected else FlagLevel.BETA, flags.level(it))
    }

    @Test
    fun `no binding in a debuggable app fails at startup`() {
        assertThrows(IllegalStateException::class.java) {
            FlagsModule.featureFlags(buildFlags, Optional.empty(), context(debuggable = true))
        }
    }

    @Test
    fun `no binding in a non-debuggable app reads every camera flag OFF`() {
        assertCameraFlags(FlagLevel.OFF, FlagsModule.featureFlags(buildFlags, Optional.empty(), context(debuggable = false)))
    }

    @Test
    fun `a binding on a device without a camera reads camera flags OFF`() {
        assertCameraFlags(FlagLevel.OFF, FlagsModule.featureFlags(buildFlags, launcher(false), context(debuggable = true)))
    }

    @Test
    fun `a binding on a device with a camera keeps GA`() {
        assertCameraFlags(FlagLevel.GA, FlagsModule.featureFlags(buildFlags, launcher(true), context(debuggable = true)))
    }
}
