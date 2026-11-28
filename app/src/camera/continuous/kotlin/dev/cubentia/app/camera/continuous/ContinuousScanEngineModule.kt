package dev.cubentia.app.camera.continuous

import android.os.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.cubentia.app.ScanDiagnostics
import dev.cubentia.app.camera.ContinuousFrame
import dev.cubentia.app.camera.ContinuousFrameTap
import dev.cubentia.app.camera.ContinuousModelIdentity
import dev.cubentia.app.camera.ContinuousScanEngine
import dev.cubentia.app.camera.ContinuousScanEngineFactory
import dev.cubentia.app.camera.StickerCenterDetector
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.domain.cube.Face

@Module
@InstallIn(SingletonComponent::class)
internal object ContinuousScanEngineModule {
    /** Story 10.34: the identity of the bundled detector, for the debug Quick Scan session manifest. */
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
            override fun create(): ContinuousScanEngine = PipelineEngine(ContinuousScanPipeline(detector))

            override val modelIdentity: ContinuousModelIdentity get() = MODEL_IDENTITY
        }
}

private class PipelineEngine(
    private val pipeline: ContinuousScanPipeline,
) : ContinuousScanEngine {
    override val detectorUnavailable get() = pipeline.detectorUnavailable

    private var frameIndex = 0

    override fun processFrame(
        frame: YuvFrame,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): ContinuousFrame {
        val result = pipeline.processFrame(frameIndex++, frame, rotationDegrees, viewWidth, viewHeight)
        // GH #405: debug builds log why stickers stay unread once all six sides are mapped; release never calls the supplier.
        // The clock is read only for an anchored six-side frame, the only kind the log considers.
        val snapshot = result.snapshot
        if (snapshot.globalAnchored && snapshot.mappedFacesCount == Face.entries.size) {
            ScanDiagnostics.onQuickScanUnread(SystemClock.elapsedRealtime(), snapshot) { pipeline.unreadSlots() }
        }
        return ContinuousFrame(result.snapshot, result.viewOverlays, result.debug)
    }

    override fun reset() = record(null)

    /** Story 10.34: a new scan whose frames [tap] sees; the frame index restarts at 0. */
    override fun record(tap: ContinuousFrameTap?) {
        frameIndex = 0
        pipeline.tap = tap
        pipeline.reset()
    }

    override fun frameDone(
        sensorTimestampNs: Long,
        analyzerNs: Long,
        rotation: Int,
        imageWidth: Int,
        imageHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
    ) {
        pipeline.tap?.frameDone(sensorTimestampNs, analyzerNs, rotation, imageWidth, imageHeight, cropLeft, cropTop, cropRight, cropBottom)
    }

    override fun export() = pipeline.export()

    override fun timingRunLine(): String? = pipeline.timingRunLine()

    override fun analyzerStart() = pipeline.analyzerStart()

    override fun analyzerEnd() = pipeline.analyzerEnd()
}
