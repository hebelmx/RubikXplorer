package dev.cubentia.app.camera.continuous

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.cubentia.app.camera.ContinuousFrame
import dev.cubentia.app.camera.ContinuousFrameTap
import dev.cubentia.app.camera.ContinuousModelIdentity
import dev.cubentia.app.camera.ContinuousScanEngine
import dev.cubentia.app.camera.ContinuousScanEngineFactory
import dev.cubentia.app.camera.StickerCenterDetector
import dev.cubentia.app.camera.YuvFrame

@Module
@InstallIn(SingletonComponent::class)
internal object ContinuousScanEngineModule {
    /** Story 10.34: the identity of the bundled detector, for the debug recorder's session manifest. */
    val MODEL_IDENTITY =
        ContinuousModelIdentity(
            file = StickerCenterModelContract.MODEL_ASSET,
            sha256 = StickerCenterModelContract.MODEL_SHA256,
            inputSize = StickerCenterModelContract.INPUT_SIZE,
            delegate = "XNNPACK",
            threads = StickerCenterModelContract.NUM_THREADS,
        )

    @Provides
    fun factory(detector: StickerCenterDetector): ContinuousScanEngineFactory =
        object : ContinuousScanEngineFactory {
            override fun create(tap: ContinuousFrameTap?): ContinuousScanEngine =
                PipelineEngine(ContinuousScanPipeline(detector, tap = tap))

            override val modelIdentity: ContinuousModelIdentity get() = MODEL_IDENTITY
        }
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
