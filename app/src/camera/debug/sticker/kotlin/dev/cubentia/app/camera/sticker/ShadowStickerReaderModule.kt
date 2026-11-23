package dev.cubentia.app.camera.sticker

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.cubentia.app.camera.CapturedFaceObserver

/** Story 10.23: binds the debug shadow sticker reader to the capture path's optional [CapturedFaceObserver] seam. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ShadowStickerReaderModule {
    @Binds
    internal abstract fun capturedFaceObserver(impl: ShadowStickerReader): CapturedFaceObserver
}
