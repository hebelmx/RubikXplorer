package dev.cubentia.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.cubentia.app.clock.SystemClock
import dev.cubentia.application.port.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ClockModule {
    @Binds
    @Singleton
    abstract fun bindClock(impl: SystemClock): Clock
}
