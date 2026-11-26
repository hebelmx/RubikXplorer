package dev.cubentia.app.camera.continuous

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.cubentia.app.camera.ContinuousFrame
import dev.cubentia.app.camera.ContinuousScanEngine
import dev.cubentia.app.camera.ContinuousScanEngineFactory
import dev.cubentia.app.camera.StickerCenterDetector
import dev.cubentia.app.camera.YuvFrame

@Module
@InstallIn(SingletonComponent::class)
internal object ContinuousScanEngineModule {
    @Provides
    fun factory(detector: StickerCenterDetector): ContinuousScanEngineFactory =
        ContinuousScanEngineFactory { PipelineEngine(ContinuousScanPipeline(detector)) }
}

private class PipelineEngine(
    private val pipeline: ContinuousScanPipeline,
) : ContinuousScanEngine {
    private var frameIndex = 0

    override fun processFrame(
        frame: YuvFrame,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): ContinuousFrame {
        val result = pipeline.processFrame(frameIndex++, frame, rotationDegrees, viewWidth, viewHeight)
        return ContinuousFrame(result.snapshot, result.viewOverlays)
    }

    override fun reset() {
        frameIndex = 0
        pipeline.reset()
    }

    override fun export() = pipeline.export()
}
