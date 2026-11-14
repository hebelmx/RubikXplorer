package dev.cubentia.app.di

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.cubentia.app.scan.ScanLauncher

@Module
@InstallIn(SingletonComponent::class)
abstract class ScanPortModule {
    @BindsOptionalOf
    abstract fun scanLauncher(): ScanLauncher
}
