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
 * still satisfy `@Inject Optional<StickerCenterDetector>` bindings on camera activities (Story 10.31).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StickerCenterDetectorOptionalModule {
    @BindsOptionalOf
    internal abstract fun stickerCenterDetector(): StickerCenterDetector
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

/**
 * Story 10.34 (AD-9 Frames): the debug Quick Scan session recording (its continuous session writer and the Rec mode of
 * Quick Scan); both are bound only from the debug camera source set (`QuickScanSessionModule`), so every other variant
 * gets `Optional.empty()` and shows no Rec control. Separate from [CameraModule] like the optional detector modules.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class QuickScanSessionOptionalModule {
    @BindsOptionalOf
    internal abstract fun writerFactory(): ContinuousSessionWriterFactory

    @BindsOptionalOf
    internal abstract fun sessionFactory(): QuickScanSessionFactory
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ContinuousScanEngineOptionalModule {
    @BindsOptionalOf
    internal abstract fun factory(): ContinuousScanEngineFactory
}
