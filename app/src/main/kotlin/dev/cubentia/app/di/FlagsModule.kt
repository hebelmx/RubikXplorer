package dev.cubentia.app.di

import android.content.Context
import android.content.pm.ApplicationInfo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.cubentia.app.flags.BuildConfigFeatureFlags
import dev.cubentia.app.scan.ScanLauncher
import dev.cubentia.app.scan.ScanPortGate
import dev.cubentia.application.port.FeatureFlags
import java.util.Optional
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FlagsModule {
    @Provides
    @Singleton
    fun featureFlags(
        impl: BuildConfigFeatureFlags,
        launcher: Optional<ScanLauncher>,
        @ApplicationContext context: Context,
    ): FeatureFlags =
        ScanPortGate(
            impl,
            launcher.isPresent,
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            launcher.map { it.hasCamera(context) }.orElse(false),
        )
}
