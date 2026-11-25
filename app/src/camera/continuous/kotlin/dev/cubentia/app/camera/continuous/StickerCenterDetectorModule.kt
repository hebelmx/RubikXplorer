package dev.cubentia.app.camera.continuous

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.cubentia.app.camera.StickerCenterDetector

/** Binds the LiteRT v2 INT8 sticker-center detector when `QUICK_SCAN` is above OFF (`app/src/camera/continuous/`). */
@Module
@InstallIn(SingletonComponent::class)
abstract class StickerCenterDetectorModule {
    @Binds
    internal abstract fun stickerCenterDetector(impl: LiteRtStickerCenterDetector): StickerCenterDetector
}
