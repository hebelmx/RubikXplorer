package dev.cubentia.app.camera

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.cubentia.app.scan.ScanLauncher

@Module
@InstallIn(SingletonComponent::class)
abstract class CameraModule {
    @Binds
    internal abstract fun frameSource(impl: CameraXFrameSource): ScanFrameSource

    @Binds
    abstract fun scanLauncher(impl: CameraScanLauncher): ScanLauncher
}
