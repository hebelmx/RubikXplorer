package dev.cubentia.app.camera

import dagger.Binds
import dagger.BindsOptionalOf
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

/**
 * Separate from [CameraModule] so Hilt tests that `@UninstallModules(CameraModule::class)` to fake [ScanFrameSource]
 * still satisfy `@Inject Optional<CornerDetector>` bindings on camera activities.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CornerDetectorOptionalModule {
    @BindsOptionalOf
    internal abstract fun cornerDetector(): CornerDetector
}
