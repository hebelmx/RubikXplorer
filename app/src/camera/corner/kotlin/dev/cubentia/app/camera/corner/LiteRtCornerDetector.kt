package dev.cubentia.app.camera.corner

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cubentia.app.camera.CornerDetector
import dev.cubentia.app.camera.CornerGuide
import dev.cubentia.app.camera.CornerLocator
import dev.cubentia.app.camera.YuvFrame
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LiteRT 7-keypoint 3D corner detector (`com.google.ai.edge.litert`, AD-9 / SCP 2026-11-20).
 *
 * Lazily initializes the XNNPACK-accelerated [Interpreter] on first off-main analysis call using pre-allocated
 * direct buffers. Never falls back to the ~950 ms pure-Kotlin [CornerLocator] on a miss or asset load error.
 */
@Singleton
internal class LiteRtCornerDetector
    internal constructor(
        private val loadModel: () -> ByteBuffer,
    ) : CornerDetector {
        @Inject
        constructor(
            @ApplicationContext context: Context,
        ) : this({ loadModelBuffer(context) })

        private val lock = Any()
        private var interpreter: Interpreter? = null
        private var initAttempted = false
        private val inputBuffer: ByteBuffer =
            ByteBuffer.allocateDirect(CornerModelTransform.INPUT_BYTES).order(ByteOrder.nativeOrder())
        private val outputBuffer: Array<FloatArray> = arrayOf(FloatArray(CornerModelTransform.OUTPUT_FLOATS))

        override fun locate(
            frame: YuvFrame,
            guide: CornerGuide,
        ): CornerLocator.Location? {
            if (guide.radius < MIN_GUIDE_RADIUS || frame.width <= 0 || frame.height <= 0) return null
            synchronized(lock) {
                val tflite = ensureInterpreter() ?: return null
                try {
                    CornerModelTransform.fillRgbInput(frame, guide, inputBuffer)
                    tflite.run(inputBuffer, outputBuffer)
                } catch (e: Exception) {
                    logWarning("LiteRT corner inference failed", e)
                    return null
                }
                return CornerModelTransform.decode(outputBuffer[0], guide)
            }
        }

        private fun ensureInterpreter(): Interpreter? {
            interpreter?.let { return it }
            if (initAttempted) return null
            initAttempted = true
            return try {
                val modelBuffer = loadModel()
                val options =
                    Interpreter.Options().apply {
                        setNumThreads(NUM_THREADS)
                        setUseXNNPACK(true)
                    }
                Interpreter(modelBuffer, options).also { interpreter = it }
            } catch (e: Exception) {
                logWarning("Unable to load $MODEL_ASSET; corner detection disabled", e)
                null
            } catch (e: LinkageError) {
                // A missing or ABI-mismatched libLiteRt.so must not crash the analyzer thread either.
                logWarning("LiteRT runtime unavailable; corner detection disabled", e)
                null
            }
        }

        internal companion object {
            const val MODEL_ASSET = "corner_keypoints.tflite"
            private const val NUM_THREADS = 4
            private const val MIN_GUIDE_RADIUS = 18.0
            private const val TAG = "LiteRtCornerDetector"

            private fun logWarning(
                message: String,
                error: Throwable,
            ) {
                runCatching { Log.w(TAG, message, error) }
            }

            private fun loadModelBuffer(context: Context): ByteBuffer =
                try {
                    context.assets.openFd(MODEL_ASSET).use { fd ->
                        FileInputStream(fd.fileDescriptor).use { input ->
                            input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                        }
                    }
                } catch (_: Exception) {
                    // Fallback if the asset was compressed in a test bundle.
                    val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
                    ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                        put(bytes)
                        rewind()
                    }
                }
        }

        internal fun close() {
            synchronized(lock) {
                interpreter?.close()
                interpreter = null
                initAttempted = false
            }
        }
    }
