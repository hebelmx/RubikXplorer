package dev.cubentia.app.camera.continuous

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cubentia.app.camera.StickerCenterDetection
import dev.cubentia.app.camera.StickerCenterDetector
import dev.cubentia.app.camera.YuvFrame
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LiteRT v2 INT8 sticker-center heatmap detector (`com.google.ai.edge.litert`, AD-9 / Story 10.31).
 *
 * Runs exclusively off the main thread on XNNPACK CPU with 2 threads using pre-allocated direct buffers.
 * Never persists, writes to disk, or transmits any camera frame (AD-13).
 */
@Singleton
internal class LiteRtStickerCenterDetector
    internal constructor(
        private val loadModel: () -> ByteBuffer,
    ) : StickerCenterDetector {
        @Inject
        constructor(
            @ApplicationContext context: Context,
        ) : this({ loadModelBuffer(context) })

        private val lock = Any()
        private var interpreter: Interpreter? = null
        private var initAttempted = false
        private var closed = false
        private val inputBytes = ByteArray(StickerCenterModelContract.INPUT_BYTES)
        private val yuvScratch = StickerCenterModelTransform.YuvInputScratch()
        private val inputBuffer: ByteBuffer =
            ByteBuffer.allocateDirect(StickerCenterModelContract.INPUT_BYTES).order(ByteOrder.nativeOrder())
        private val outputBuffer: ByteBuffer =
            ByteBuffer
                .allocateDirect(StickerCenterModelContract.OUTPUT_FLOATS * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
        private val outputFloatView = outputBuffer.asFloatBuffer()
        private val outputFloats = FloatArray(StickerCenterModelContract.OUTPUT_FLOATS)

        override fun detect(
            frame: YuvFrame,
            rotationDegrees: Int,
            viewWidth: Int,
            viewHeight: Int,
        ): StickerCenterDetection {
            if (frame.left < 0 || frame.top < 0 || frame.width <= 0 || frame.height <= 0) return StickerCenterDetection.EMPTY
            synchronized(lock) {
                val tflite = ensureInterpreter() ?: return StickerCenterDetection.EMPTY
                val params =
                    StickerCenterModelTransform.fillYuvInput(frame, rotationDegrees, inputBytes, yuvScratch)
                        ?: return StickerCenterDetection.EMPTY
                val dtMs = runInference(tflite) ?: return StickerCenterDetection.EMPTY
                val peaks =
                    StickerCenterModelTransform.decodePeaks(
                        output = outputFloats,
                        params = params,
                        cropLeft = frame.left,
                        cropTop = frame.top,
                        cropRight = frame.right,
                        cropBottom = frame.bottom,
                        rotationDegrees = rotationDegrees,
                        viewWidth = viewWidth,
                        viewHeight = viewHeight,
                    )
                return StickerCenterDetection(
                    peaks = peaks,
                    uprightWidth = params.uprightWidth,
                    uprightHeight = params.uprightHeight,
                    inferenceMs = dtMs,
                )
            }
        }

        /**
         * Packed RGB still-parity helper kept inside `app/src/camera/continuous/` so instrumented tests
         * verify exact PC-to-Android peak parity without ever importing `org.tensorflow.lite.Interpreter`.
         */
        internal fun detectRgb(
            rgb: ByteArray,
            width: Int,
            height: Int,
            threshold: Float = StickerCenterModelContract.DEFAULT_THRESHOLD,
            maxPeaks: Int = StickerCenterModelContract.MAX_PEAKS,
        ): StickerCenterDetection {
            if (width <= 0 || height <= 0 || rgb.size.toLong() < width.toLong() * height.toLong() * 3L) {
                return StickerCenterDetection.EMPTY
            }
            synchronized(lock) {
                val tflite = ensureInterpreter() ?: return StickerCenterDetection.EMPTY
                val params =
                    StickerCenterModelTransform.fillRgbArrayInput(rgb, width, height, inputBytes) ?: return StickerCenterDetection.EMPTY
                val dtMs = runInference(tflite) ?: return StickerCenterDetection.EMPTY
                val peaks =
                    StickerCenterModelTransform.decodePeaks(
                        output = outputFloats,
                        params = params,
                        cropLeft = 0,
                        cropTop = 0,
                        cropRight = width,
                        cropBottom = height,
                        rotationDegrees = 0,
                        viewWidth = width,
                        viewHeight = height,
                        threshold = threshold,
                        maxPeaks = maxPeaks,
                    )
                return StickerCenterDetection(
                    peaks = peaks,
                    uprightWidth = params.uprightWidth,
                    uprightHeight = params.uprightHeight,
                    inferenceMs = dtMs,
                )
            }
        }

        private fun runInference(tflite: Interpreter): Double? =
            try {
                inputBuffer.clear()
                inputBuffer.put(inputBytes)
                inputBuffer.rewind()
                outputBuffer.clear()
                val t0 = System.nanoTime()
                tflite.run(inputBuffer, outputBuffer)
                val dtMs = (System.nanoTime() - t0) / 1_000_000.0
                outputFloatView.rewind()
                outputFloatView.get(outputFloats)
                dtMs
            } catch (e: IllegalArgumentException) {
                logWarning("LiteRT sticker-center tensor mismatch; disabling detector", e)
                latchDisabled()
                null
            } catch (e: LinkageError) {
                logWarning("LiteRT runtime linkage error; disabling detector", e)
                latchDisabled()
                null
            } catch (e: Exception) {
                logWarning("LiteRT sticker-center inference failed", e)
                null
            }

        private fun latchDisabled() {
            closed = true
            runCatching { interpreter?.close() }
            interpreter = null
        }

        private fun ensureInterpreter(): Interpreter? {
            if (closed) return null
            interpreter?.let { return it }
            if (initAttempted) return null
            initAttempted = true
            return try {
                val modelBuffer = loadModel()
                val options =
                    Interpreter.Options().apply {
                        setNumThreads(StickerCenterModelContract.NUM_THREADS)
                        setUseXNNPACK(true)
                    }
                val created = Interpreter(modelBuffer, options)
                if (!matchesModelContract(created)) {
                    created.close()
                    return null
                }
                created.also { interpreter = it }
            } catch (e: Exception) {
                logWarning("Unable to load ${StickerCenterModelContract.MODEL_ASSET}; continuous detector disabled", e)
                null
            } catch (e: LinkageError) {
                logWarning("LiteRT runtime unavailable; continuous detector disabled", e)
                null
            }
        }

        private fun matchesModelContract(tflite: Interpreter): Boolean {
            if (tflite.inputTensorCount < 1 || tflite.outputTensorCount < 1) {
                logWarning(
                    "Model tensor count invalid",
                    IllegalStateException("in=${tflite.inputTensorCount} out=${tflite.outputTensorCount}"),
                )
                return false
            }
            val inTensor = tflite.getInputTensor(0)
            val outTensor = tflite.getOutputTensor(0)
            val expectedInShape =
                intArrayOf(
                    1,
                    StickerCenterModelContract.INPUT_SIZE,
                    StickerCenterModelContract.INPUT_SIZE,
                    StickerCenterModelContract.CHANNELS,
                )
            val expectedOutShape =
                intArrayOf(
                    1,
                    StickerCenterModelContract.HEATMAP_SIZE,
                    StickerCenterModelContract.HEATMAP_SIZE,
                    StickerCenterModelContract.OUTPUT_CHANNELS,
                )
            val ok =
                inTensor.dataType() == DataType.UINT8 &&
                    inTensor.shape().contentEquals(expectedInShape) &&
                    outTensor.dataType() == DataType.FLOAT32 &&
                    outTensor.shape().contentEquals(expectedOutShape)
            if (!ok) {
                logWarning(
                    "Model tensor contract mismatch",
                    IllegalArgumentException(
                        "in=${inTensor.dataType()}${inTensor.shape().contentToString()} " +
                            "out=${outTensor.dataType()}${outTensor.shape().contentToString()}",
                    ),
                )
            }
            return ok
        }

        internal fun close() {
            synchronized(lock) {
                closed = true
                interpreter?.close()
                interpreter = null
            }
        }

        internal companion object {
            private const val TAG = "LiteRtStickerCenterDet"

            private fun logWarning(
                message: String,
                error: Throwable,
            ) {
                runCatching { Log.w(TAG, message, error) }
            }

            private fun loadModelBuffer(context: Context): ByteBuffer =
                try {
                    context.assets.openFd(StickerCenterModelContract.MODEL_ASSET).use { fd ->
                        FileInputStream(fd.fileDescriptor).use { input ->
                            input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                        }
                    }
                } catch (_: Exception) {
                    val bytes = context.assets.open(StickerCenterModelContract.MODEL_ASSET).use { it.readBytes() }
                    ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                        put(bytes)
                        rewind()
                    }
                }
        }
    }
