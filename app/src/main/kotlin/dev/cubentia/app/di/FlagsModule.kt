package dev.cubentia.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.cubentia.app.flags.BuildConfigFeatureFlags
import dev.cubentia.application.port.FeatureFlags
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FlagsModule {
    @Binds
    @Singleton
    abstract fun bindFeatureFlags(impl: BuildConfigFeatureFlags): FeatureFlags
}
