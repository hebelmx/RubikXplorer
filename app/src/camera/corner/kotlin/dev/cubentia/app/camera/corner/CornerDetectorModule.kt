package dev.cubentia.app.camera.corner

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.cubentia.app.camera.CornerDetector

/** Binds the LiteRT 7-keypoint corner detector when a corner-model flag (`QUICK_SCAN` / `CASE_RECOGNITION`) is above OFF. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CornerDetectorModule {
    @Binds
    internal abstract fun cornerDetector(impl: LiteRtCornerDetector): CornerDetector
}
