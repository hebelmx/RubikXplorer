package dev.cubentia.app.camera.sticker

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cubentia.app.ScanDiagnostics
import dev.cubentia.app.camera.CapturedFaceObserver
import dev.cubentia.app.camera.YuvFrame
import dev.cubentia.domain.recognition.QuickScanClassification
import dev.cubentia.domain.recognition.QuickScanPlan
import dev.cubentia.domain.recognition.ScanTrace
import dev.cubentia.domain.recognition.StickerConfidence
import dev.cubentia.domain.recognition.StickerReading
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import javax.inject.Inject
import javax.inject.Singleton

/** One inference of [StickerModelContract]: fills [output] (`[1][9][6]`) from [input]. A test seam over LiteRT. */
internal fun interface StickerInterpreter {
    fun run(
        input: ByteBuffer,
        output: Array<Array<FloatArray>>,
    )
}

/**
 * Story 10.23, debug builds only: the shadow sticker reader. On each Quick Scan corner capture, while
 * [ShadowReaderToggle] is on, it reads the capturing frame's three unwarped faces once, on its own single-thread
 * executor (never the analyzer or main thread), and logs one `CubentiaScanDiag` line per face beside both Kotlin reads:
 *
 * `quickScan shadow hold=<n> face=<F> center=<C> inferMs=<ms|-> model=<9 letters>/<9 C|U>|none self=<..>/<..> second=<cal|norm>:<..>/<..>`
 *
 * It decides nothing: it returns nothing to the capture path, and only categorical results (letters and C/U, AD-9)
 * appear. The model is image-only (no Kotlin reading, centre or calibration goes in). The interpreter loads lazily, once
 * per process, like `LiteRtCornerDetector` (XNNPACK, 4 threads, no GPU or NNAPI delegate); a missing model logs one
 * `quickScan shadowReader error=model-missing` line per scan, an inference failure one `error=inference` line for that
 * capture, and neither ever reaches the caller. Crops stay in memory; [onScanEnded] drops queued reads and stops the
 * executor, so no crop outlives the scan (AD-13).
 */
@Singleton
internal class ShadowStickerReader
    internal constructor(
        private val enabled: () -> Boolean,
        private val loadInterpreter: () -> StickerInterpreter,
        private val log: (String) -> Unit,
    ) : CapturedFaceObserver {
        @Inject
        constructor(
            @ApplicationContext context: Context,
        ) : this({ ShadowReaderToggle.isOn(context) }, { liteRt(context) }, { line -> runCatching { Log.d(ScanDiagnostics.TAG, line) } })

        /** Guards the executor and the per-scan flag; never held during inference, so the analyzer thread never waits on it. */
        private val lock = Any()

        /** Guards the interpreter and its buffers (the reader's own thread; a replaced executor's last read may overlap). */
        private val inference = Any()
        private var executor: ExecutorService? = null
        private var interpreter: StickerInterpreter? = null
        private var loadAttempted = false
        private var missingLogged = false

        /** True from [onScanStarted] to [onScanEnded]: captures outside a scan are ignored, a re-entered hold 1 is not a new scan. */
        private var active = false
        private val input: ByteBuffer = ByteBuffer.allocateDirect(StickerModelContract.INPUT_BYTES).order(ByteOrder.nativeOrder())
        private val output = Array(1) { Array(StickerModelContract.CELLS) { FloatArray(StickerModelContract.COLOR_ORDER.size) } }

        override fun onScanStarted() {
            synchronized(lock) {
                // Re-entering hold 1's corner view (back-out, camera restart) within one scan starts nothing new.
                if (active) return
                active = true
                missingLogged = false
            }
            log("quickScan shadowReader state=${if (enabled()) "on" else "off"}")
        }

        override fun onCornerCaptured(
            hold: Int,
            crops: List<YuvFrame?>,
            reports: List<QuickScanClassification.FaceReport>,
        ) {
            // Read at each capture: a toggle flipped mid-scan takes effect at the next capture.
            if (!enabled()) return
            val steps = QuickScanPlan.holds.getOrNull(hold - 1) ?: return
            val faces =
                steps.mapIndexed { index, step ->
                    val report = reports.firstOrNull { it.face == step.face }
                    ShadowFace(step.face.name, ScanTrace.letter(step.centerColor), crops.getOrNull(index), kotlinReads(report))
                }
            val job = Runnable { read(hold, faces) }
            synchronized(lock) {
                // A capture after the scan ended (destroyed mid-frame) must not recreate the executor (AD-13).
                if (!active) return
                val running = executor ?: Executors.newSingleThreadExecutor { Thread(it, "ShadowStickerReader") }.also { executor = it }
                try {
                    running.execute(job)
                } catch (_: RejectedExecutionException) {
                    // The scan ended meanwhile: nothing to read.
                }
            }
        }

        override fun onScanEnded() {
            synchronized(lock) {
                active = false
                // Queued reads (and the crops they hold) are dropped; a read in progress finishes and is then released.
                executor?.shutdownNow()
                executor = null
            }
        }

        /** Test seam: waits until every read queued so far has run. */
        internal fun awaitIdle() {
            val running = synchronized(lock) { executor } ?: return
            runCatching { running.submit {}.get() }
        }

        private fun read(
            hold: Int,
            faces: List<ShadowFace>,
        ) {
            if (faces.all { it.crop == null }) {
                faces.forEach { log(line(hold, it, null, null)) }
                return
            }
            val model = synchronized(inference) { ensureInterpreter() }
            if (model == null) {
                val first = synchronized(lock) { !missingLogged.also { missingLogged = true } }
                if (first) log("quickScan shadowReader error=model-missing")
                return
            }
            val lines =
                try {
                    synchronized(inference) {
                        faces.map { face ->
                            val crop = face.crop ?: return@map line(hold, face, null, null)
                            StickerModelContract.fillInput(crop, input)
                            val started = SystemClock.elapsedRealtime()
                            model.run(input, output)
                            val elapsed = SystemClock.elapsedRealtime() - started
                            line(hold, face, StickerModelContract.readings(output[0]), elapsed)
                        }
                    }
                } catch (_: Exception) {
                    log("quickScan shadowReader error=inference hold=$hold")
                    return
                } catch (_: LinkageError) {
                    log("quickScan shadowReader error=inference hold=$hold")
                    return
                }
            lines.forEach(log)
        }

        private fun ensureInterpreter(): StickerInterpreter? {
            interpreter?.let { return it }
            if (loadAttempted) return null
            loadAttempted = true
            return try {
                loadInterpreter().also { interpreter = it }
            } catch (_: Exception) {
                null
            } catch (_: LinkageError) {
                // A missing or ABI-mismatched libLiteRt.so must not crash the reader's thread either.
                null
            }
        }

        private class ShadowFace(
            val face: String,
            val center: String,
            val crop: YuvFrame?,
            val kotlin: String,
        )

        internal companion object {
            /** Letters and C/U for nine readings, `-` where a sticker has no reading. */
            fun format(readings: List<StickerReading?>): String =
                if (readings.isEmpty()) {
                    "-"
                } else {
                    readings.joinToString("") { ScanTrace.letter(it?.color) } + "/" +
                        readings.joinToString("") {
                            when (it?.confidence) {
                                StickerConfidence.CONFIDENT -> "C"
                                StickerConfidence.UNCERTAIN -> "U"
                                null -> "-"
                            }
                        }
                }

            fun kotlinReads(report: QuickScanClassification.FaceReport?): String {
                if (report == null || report.self.isEmpty()) return "self=- second=-"
                val source =
                    when (report.secondRead) {
                        QuickScanClassification.SecondRead.CALIBRATION -> "cal"
                        QuickScanClassification.SecondRead.NORMALIZED -> "norm"
                        null -> "-"
                    }
                return "self=${format(report.self)} second=$source:${format(report.second)}"
            }

            private fun line(
                hold: Int,
                face: ShadowFace,
                model: List<StickerReading>?,
                inferMs: Long?,
            ): String =
                "quickScan shadow hold=$hold face=${face.face} center=${face.center} inferMs=${inferMs ?: "-"} " +
                    "model=${model?.let(::format) ?: "none"} ${face.kotlin}"

            private fun liteRt(context: Context): StickerInterpreter {
                val buffer =
                    try {
                        context.assets.openFd(StickerModelContract.MODEL_ASSET).use { fd ->
                            FileInputStream(fd.fileDescriptor).use { stream ->
                                stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                            }
                        }
                    } catch (_: Exception) {
                        // A compressed asset (test bundles) cannot be mapped: read it whole.
                        val bytes = context.assets.open(StickerModelContract.MODEL_ASSET).use { it.readBytes() }
                        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                            put(bytes)
                            rewind()
                        }
                    }
                val options =
                    Interpreter.Options().apply {
                        setNumThreads(NUM_THREADS)
                        setUseXNNPACK(true)
                    }
                val interpreter = Interpreter(buffer, options)
                return StickerInterpreter { inputBuffer, outputArray -> interpreter.run(inputBuffer, outputArray) }
            }

            private const val NUM_THREADS = 4
        }
    }
