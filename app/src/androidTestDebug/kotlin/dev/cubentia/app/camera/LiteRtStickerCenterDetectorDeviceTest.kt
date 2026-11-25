package dev.cubentia.app.camera

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cubentia.app.camera.continuous.ContinuousFaceSampler
import dev.cubentia.app.camera.continuous.ContinuousScanPipeline
import dev.cubentia.app.camera.continuous.LiteRtStickerCenterDetector
import dev.cubentia.app.camera.continuous.StickerCenterModelContract
import dev.cubentia.app.camera.continuous.StickerCenterModelTransform
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.recognition.QuickScanPlan
import dev.cubentia.domain.recognition.continuous.ContinuousFaceletMapper
import dev.cubentia.domain.recognition.continuous.FaceLatticeFitter
import dev.cubentia.domain.recognition.continuous.Point2D
import dev.cubentia.domain.recognition.continuous.ProjectiveGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * On-device verification of [LiteRtStickerCenterDetector] + [ContinuousScanPipeline] (Story 10.31 AC5):
 * - Runs all LiteRT inference and full continuous pipeline work off the main thread (`Executors.newSingleThreadExecutor`).
 * - Verifies exact PC-to-Android peak parity (`<= 1.0 px`) + 3/3 faces & 3/3 shared edges via [FaceLatticeFitter]
 *   on the 3 tuning `Fixtures/corner/` stills pushed to `/data/local/tmp/cubentia-10-31-tuning/` (skipped via `Assume`
 *   only when pushed fixtures are absent, e.g. CI without gitignored `Fixtures/corner/`).
 * - Benchmarks full end-to-end per-frame latency (`YUV→RGB + LiteRT + decode + FaceLatticeFitter + ContinuousFaceSampler +
 *   ContinuousFaceletMapper`) across 60 steady-state frames at both `640×480` and `1280×960`:
 *   `p50 <= 50 ms` on Lenovo TB128XU, `p50 <= 20 ms` on Galaxy S24 FE; zero frames persisted; negative rejection;
 *   `ViewfinderGrid.frameToViewFraction` inverse mapping.
 *
 * Notice: zero `org.tensorflow.lite.*` imports here (all `Interpreter` calls stay inside `app/src/camera/`).
 */
@RunWith(AndroidJUnit4::class)
class LiteRtStickerCenterDetectorDeviceTest {
    @Test
    fun pcToAndroidPeakParityAndThreeFaceLatticeRecoveryOnTuningStills() {
        val dir = File(TUNING_STILLS_DIR)
        val missing =
            TUNING_EXPECTATIONS
                .filterNot { File(dir, it.fileName).isFile || File(dir, it.fileName.replace(".jpg", ".png")).isFile }
                .map { it.fileName }
        assumeTrue(
            "Pushed tuning stills absent under $TUNING_STILLS_DIR; skipping file-backed parity",
            missing.size < TUNING_EXPECTATIONS.size,
        )
        assertTrue("Tuning stills only partly pushed under $TUNING_STILLS_DIR; missing $missing", missing.isEmpty())

        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = LiteRtStickerCenterDetector(context)
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor
                .submit {
                    assertFalse("Inference must run off the main thread", Looper.myLooper() == Looper.getMainLooper())
                    for (expected in TUNING_EXPECTATIONS) {
                        val pngFile = File(dir, expected.fileName.replace(".jpg", ".png"))
                        val file = if (pngFile.isFile) pngFile else File(dir, expected.fileName)
                        val opts = BitmapFactory.Options().apply { inScaled = false }
                        val bmp = requireNotNull(BitmapFactory.decodeFile(file.absolutePath, opts)) { "Failed to decode $file" }
                        val w = bmp.width
                        val h = bmp.height
                        assertEquals("${expected.id} width", expected.width, w)
                        assertEquals("${expected.id} height", expected.height, h)
                        val argb = IntArray(w * h)
                        bmp.getPixels(argb, 0, w, 0, 0, w, h)
                        bmp.recycle()

                        val rgb = ByteArray(w * h * 3)
                        for (i in argb.indices) {
                            val px = argb[i]
                            rgb[i * 3] = ((px shr 16) and 0xFF).toByte()
                            rgb[i * 3 + 1] = ((px shr 8) and 0xFF).toByte()
                            rgb[i * 3 + 2] = (px and 0xFF).toByte()
                        }

                        // 1. Exact RGB letterbox parity vs PC corner-peaks-parity.json
                        val rgbDet =
                            detector.detectRgb(
                                rgb = rgb,
                                width = w,
                                height = h,
                                threshold = StickerCenterModelContract.DEFAULT_THRESHOLD,
                                maxPeaks = StickerCenterModelContract.MAX_PEAKS,
                            )
                        val maxDistPx = maxPeakParityErrorPx(expected.peaks, rgbDet.centers)
                        val rgbFaces = FaceLatticeFitter.fit(rgbDet.centers)
                        val rgbSharedEdges = countSharedEdges(rgbFaces.map { it.quad })
                        Log.i(
                            TAG,
                            "PARITY RGB id=${expected.id} size=${w}x$h pcPeaks=${expected.peaks.size} " +
                                "androidPeaks=${rgbDet.peaks.size} maxPxErr=${"%.4f".format(maxDistPx)} " +
                                "faces=${rgbFaces.size}/3 sharedEdges=$rgbSharedEdges/3 detMs=${"%.2f".format(rgbDet.inferenceMs)}",
                        )
                        assertEquals(
                            "${expected.id}: Android RGB peak count must match PC parity JSON",
                            expected.peaks.size,
                            rgbDet.peaks.size,
                        )
                        assertTrue(
                            "${expected.id}: max PC-to-Android peak error ${"%.4f".format(maxDistPx)} px exceeds 1.0 px",
                            maxDistPx <= 1.0,
                        )
                        assertEquals("${expected.id} RGB FaceLatticeFitter faces", 3, rgbFaces.size)
                        assertEquals("${expected.id} RGB FaceLatticeFitter shared edges", 3, rgbSharedEdges)

                        // 2. Direct YUV path on the same still must also recover 3/3 faces & 3/3 shared edges
                        val yuv = argbToYuv(w, h, argb, noise = 0)
                        val yuvDet = detector.detect(yuv, rotationDegrees = 0, viewWidth = w, viewHeight = h)
                        val yuvFaces = FaceLatticeFitter.fit(yuvDet.centers)
                        val yuvSharedEdges = countSharedEdges(yuvFaces.map { it.quad })
                        Log.i(
                            TAG,
                            "PARITY YUV id=${expected.id} androidPeaks=${yuvDet.peaks.size} " +
                                "faces=${yuvFaces.size}/3 sharedEdges=$yuvSharedEdges/3 detMs=${"%.2f".format(yuvDet.inferenceMs)}",
                        )
                        assertEquals("${expected.id} YUV FaceLatticeFitter faces", 3, yuvFaces.size)
                        assertEquals("${expected.id} YUV FaceLatticeFitter shared edges", 3, yuvSharedEdges)
                    }
                }.get()
        } finally {
            detector.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun endToEndPipelineMeetsPhysicalR8LatencyBudgetAndRejectsNegatives() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filesBefore = snapshotFiles(context)
        val detector = LiteRtStickerCenterDetector(context)
        val executor = Executors.newSingleThreadExecutor()
        try {
            for ((width, height) in BENCHMARK_SIZES) {
                val report =
                    executor
                        .submit<PipelineBenchmarkReport> {
                            assertFalse("Pipeline must run off the main thread", Looper.myLooper() == Looper.getMainLooper())
                            runEndToEndBenchmark(detector, width, height)
                        }.get()

                Log.i(
                    TAG,
                    "BENCHMARK model=${Build.MODEL} size=${width}x$height posFrames=${report.posTotal} " +
                        "posE2eP50Ms=${"%.2f".format(report.posE2eP50Ms)} posE2eP90Ms=${"%.2f".format(report.posE2eP90Ms)} " +
                        "posE2eMinMs=${"%.2f".format(report.posE2eMinMs)} posE2eMaxMs=${"%.2f".format(report.posE2eMaxMs)} " +
                        "posDetP50Ms=${"%.2f".format(report.posDetP50Ms)} negE2eP50Ms=${"%.2f".format(report.negE2eP50Ms)} " +
                        "posWithFaces=${report.posWithFaces}/${report.posTotal} " +
                        "negClean=${report.negClean}/${report.negTotal} overlaysChecked=${report.overlaysChecked}",
                )

                assertEquals("Expected 60 steady-state positive frames at ${width}x$height", 60, report.posTotal)
                assertEquals("Expected 15 negative frames at ${width}x$height", 15, report.negTotal)
                assertTrue(
                    "[${width}x$height] Expected >= 80% positive frames to fit >= 1 face, got ${report.posWithFaces}/${report.posTotal}",
                    report.posWithFaces * 5 >= report.posTotal * 4,
                )
                assertTrue(
                    "[${width}x$height] Expected overlay inverse view mapping to be exercised",
                    report.overlaysChecked > 0,
                )
                assertEquals(
                    "[${width}x$height] Every negative (empty/dark/glossy) frame must fit 0 faces",
                    report.negTotal,
                    report.negClean,
                )
                assertTrue(
                    "[${width}x$height] Negative frames must decode 0 sticker-center peaks, got ${report.negWithPeaks}",
                    report.negWithPeaks.isEmpty(),
                )

                if (!isEmulator()) {
                    val budgetMs = if (isGalaxyS24Fe()) 20.0 else 50.0
                    assertTrue(
                        "[${width}x$height] Positive 5-stage E2E p50 ${"%.2f".format(report.posE2eP50Ms)} ms exceeds " +
                            "$budgetMs ms budget on ${Build.MODEL}",
                        report.posE2eP50Ms <= budgetMs,
                    )
                }
            }
        } finally {
            detector.close()
            executor.shutdownNow()
        }

        // AD-13: zero camera frames persisted to app storage or cache — no file added, removed, resized or rewritten.
        assertEquals("Zero frames may be persisted to app filesDir/cacheDir", filesBefore, snapshotFiles(context))
    }

    private fun runEndToEndBenchmark(
        detector: LiteRtStickerCenterDetector,
        longSide: Int,
        shortSide: Int,
    ): PipelineBenchmarkReport {
        val pipeline = ContinuousScanPipeline(detector, ContinuousFaceSampler(), ContinuousFaceletMapper())
        val scramble =
            CubeState.SOLVED.apply(
                listOf(FaceTurn(Face.R, Turn.CLOCKWISE), FaceTurn(Face.U, Turn.HALF), FaceTurn(Face.F, Turn.COUNTERCLOCKWISE)),
            )
        val rng = Random(1031)
        val rotations = listOf(0, 90, 180, 270)

        // Warmup (lazy interpreter + XNNPACK delegate init + JIT warmup)
        repeat(5) {
            val guide = CornerGuide(ViewfinderGrid.inFrame(0.0, 0.0, 0, 0, longSide, shortSide, 90), 90)
            val pts = CornerLocator.projectCube(guide.vertices().last(), guide.radius * 0.78, 90, 32.0, 0.0, 0.0, 0.20)
            val warm = renderCubeYuv(longSide, shortSide, guide.quads(pts), scramble, 0, stickerless = false, bgRgb = 0xd8d6d0, noise = 2)
            pipeline.processFrame(frameIndex = it, frame = warm, rotationDegrees = 90, viewWidth = 1080, viewHeight = 1920)
        }
        pipeline.reset()

        val posTotal = 60
        val negTotal = 15
        val posE2eMs = ArrayList<Double>(posTotal)
        val posDetMs = ArrayList<Double>(posTotal)
        val negE2eMs = ArrayList<Double>(negTotal)
        var posWithFaces = 0
        var overlaysChecked = 0
        var negClean = 0
        val negWithPeaks = ArrayList<String>()

        // 60 pure 5-stage positive cube frames across rotations 0/90/180/270, stickered & stickerless
        for (i in 0 until posTotal) {
            val rot = rotations[i % rotations.size]
            val w = if (rot % 180 == 0) shortSide else longSide
            val h = if (rot % 180 == 0) longSide else shortSide
            val guide = CornerGuide(ViewfinderGrid.inFrame(0.0, 0.0, 0, 0, w, h, rot), rot)
            val pts =
                CornerLocator.projectCube(
                    guide.vertices().last(),
                    guide.radius * (0.68 + rng.nextDouble() * 0.22),
                    rot,
                    24.0 + rng.nextDouble() * 20.0,
                    (rng.nextDouble() - 0.5) * 32.0,
                    (rng.nextDouble() - 0.5) * 32.0,
                    0.14 + rng.nextDouble() * 0.14,
                )
            val frame =
                renderCubeYuv(
                    width = w,
                    height = h,
                    quads = guide.quads(pts),
                    cube = if (i % 3 == 0) CubeState.SOLVED else scramble,
                    holdIdx = i % 2,
                    stickerless = i % 2 == 1,
                    bgRgb = if (i % 3 == 0) 0x20242a else 0xd8d6d0,
                    noise = 2,
                )
            val t0 = SystemClock.elapsedRealtimeNanos()
            val out = pipeline.processFrame(frameIndex = i, frame = frame, rotationDegrees = rot, viewWidth = 1080, viewHeight = 1920)
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
            posE2eMs += elapsedMs
            posDetMs += out.detection.inferenceMs

            if (out.fittedFaces.isNotEmpty()) posWithFaces++
            for (peak in out.detection.peaks) {
                val (fx, fy) = StickerCenterModelTransform.uprightToFrame(peak.x, peak.y, 0, 0, w, h, rot)
                val (expectedVx, expectedVy) = ViewfinderGrid.frameToViewFraction(fx, fy, 1080.0, 1920.0, 0, 0, w, h, rot)
                assertEquals("Peak viewX mismatch vs ViewfinderGrid", expectedVx, peak.viewX, 1e-6)
                assertEquals("Peak viewY mismatch vs ViewfinderGrid", expectedVy, peak.viewY, 1e-6)
            }
            assertEquals("Overlays must match trackedQuads 1:1", out.snapshot.trackedQuads.size, out.viewOverlays.size)
            out.viewOverlays.forEachIndexed { idx, overlay ->
                val trackedQuad = out.snapshot.trackedQuads[idx].quad
                assertEquals(4, overlay.viewCorners.size)
                overlay.viewCorners.forEachIndexed { cIdx, corner ->
                    assertTrue("Overlay corner must be finite: $corner", corner.isFinite())
                    val uc = trackedQuad.corners[cIdx]
                    val (fx, fy) = StickerCenterModelTransform.uprightToFrame(uc.x, uc.y, 0, 0, w, h, rot)
                    val (expectedVx, expectedVy) = ViewfinderGrid.frameToViewFraction(fx, fy, 1080.0, 1920.0, 0, 0, w, h, rot)
                    assertEquals("Overlay corner[$cIdx].x mismatch vs ViewfinderGrid", expectedVx, corner.x, 1e-6)
                    assertEquals("Overlay corner[$cIdx].y mismatch vs ViewfinderGrid", expectedVy, corner.y, 1e-6)
                }
                overlaysChecked++
            }
        }

        // 15 negative frames (empty plain, dark, and glossy reflections), timed separately
        for (i in 0 until negTotal) {
            val rot = rotations[i % rotations.size]
            val w = if (rot % 180 == 0) shortSide else longSide
            val h = if (rot % 180 == 0) longSide else shortSide
            val kind =
                if (i >= 8) {
                    "glossy"
                } else if (i % 2 == 0) {
                    "dark"
                } else {
                    "empty"
                }
            val frame = if (i < 8) renderEmptyYuv(w, h, dark = i % 2 == 0) else renderDarkGlossyYuv(w, h, i)

            val t0 = SystemClock.elapsedRealtimeNanos()
            val out =
                pipeline.processFrame(
                    frameIndex = posTotal + i,
                    frame = frame,
                    rotationDegrees = rot,
                    viewWidth = 1080,
                    viewHeight = 1920,
                )
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
            negE2eMs += elapsedMs
            if (out.fittedFaces.isEmpty()) negClean++
            if (out.detection.peaks.isNotEmpty()) negWithPeaks += "$kind#$i rot=$rot peaks=${out.detection.peaks.size}"
        }

        val sortedPosE2e = posE2eMs.sorted()
        val sortedPosDet = posDetMs.sorted()
        val sortedNegE2e = negE2eMs.sorted()
        return PipelineBenchmarkReport(
            posTotal = sortedPosE2e.size,
            posE2eP50Ms = sortedPosE2e[sortedPosE2e.size / 2],
            posE2eP90Ms = sortedPosE2e[(sortedPosE2e.size * 9) / 10],
            posE2eMinMs = sortedPosE2e.first(),
            posE2eMaxMs = sortedPosE2e.last(),
            posDetP50Ms = sortedPosDet[sortedPosDet.size / 2],
            negE2eP50Ms = sortedNegE2e[sortedNegE2e.size / 2],
            posWithFaces = posWithFaces,
            negClean = negClean,
            negTotal = negTotal,
            negWithPeaks = negWithPeaks,
            overlaysChecked = overlaysChecked,
        )
    }

    private data class PipelineBenchmarkReport(
        val posTotal: Int,
        val posE2eP50Ms: Double,
        val posE2eP90Ms: Double,
        val posE2eMinMs: Double,
        val posE2eMaxMs: Double,
        val posDetP50Ms: Double,
        val negE2eP50Ms: Double,
        val posWithFaces: Int,
        val negClean: Int,
        val negTotal: Int,
        val negWithPeaks: List<String>,
        val overlaysChecked: Int,
    )

    private fun maxPeakParityErrorPx(
        expected: List<Point2D>,
        actual: List<Point2D>,
    ): Double {
        if (expected.isEmpty() || actual.isEmpty()) return Double.POSITIVE_INFINITY
        return maxOf(directedHausdorffPx(expected, actual), directedHausdorffPx(actual, expected))
    }

    private fun directedHausdorffPx(
        from: List<Point2D>,
        to: List<Point2D>,
    ): Double {
        var maxErr = 0.0
        for (p in from) {
            var best = Double.POSITIVE_INFINITY
            for (q in to) {
                val d = hypot(p.x - q.x, p.y - q.y)
                if (d < best) best = d
            }
            if (best > maxErr) maxErr = best
        }
        return maxErr
    }

    private fun countSharedEdges(quads: List<dev.cubentia.domain.recognition.continuous.Quad2D>): Int {
        var count = 0
        for (i in quads.indices) {
            for (j in i + 1 until quads.size) {
                if (ProjectiveGeometry.sharedEdge(quads[i], quads[j]) != null) count++
            }
        }
        return count
    }

    /** Path → "length:lastModified" of every app-private file, so a rewritten existing file is caught too. */
    private fun snapshotFiles(context: Context): Map<String, String> {
        val dirs = listOfNotNull(context.filesDir, context.cacheDir)
        return dirs
            .flatMap { root ->
                root
                    .walkTopDown()
                    .filter { it.isFile && !isAndroidRuntimeBookkeeping(it) }
                    .map { it.absolutePath to "${it.length()}:${it.lastModified()}" }
                    .toList()
            }.toMap()
    }

    private fun isAndroidRuntimeBookkeeping(file: File): Boolean {
        val path = file.absolutePath
        return file.name.startsWith("profileinstaller_") ||
            file.name == "profileInstalled" ||
            path.contains("/oat_primary/") ||
            path.contains("/code_cache/")
    }

    private fun renderCubeYuv(
        width: Int,
        height: Int,
        quads: List<List<CornerPoint>>,
        cube: CubeState,
        holdIdx: Int,
        stickerless: Boolean,
        bgRgb: Int,
        noise: Int,
    ): YuvFrame {
        val pixels = IntArray(width * height) { bgRgb }
        val expected = QuickScanPlan.holds[holdIdx].map { step -> expectedFace(cube, step.face) }
        val gap = if (stickerless) 0.025 else 0.09
        val shades = listOf(1.0, 0.88, 0.78)
        val samples = 240 * (maxOf(width, height) / 320).coerceAtLeast(1)
        quads.forEachIndexed { face, quad ->
            val h = Homography.from(quad) ?: return@forEachIndexed
            for (row in 0 until samples) {
                val v = (row + 0.5) / samples
                for (col in 0 until samples) {
                    val u = (col + 0.5) / samples
                    val p = h.apply(u, v)
                    val x = p.x.toInt()
                    val y = p.y.toInt()
                    if (x in 0 until width && y in 0 until height) {
                        val onGap = abs(u * 3 - (u * 3).roundToInt()) < gap || abs(v * 3 - (v * 3).roundToInt()) < gap
                        val base =
                            if (onGap) {
                                0x222428
                            } else {
                                PALETTE.getValue(expected[face][(v * 3).toInt().coerceIn(0, 2) * 3 + (u * 3).toInt().coerceIn(0, 2)])
                            }
                        val s = shades[face]
                        val r = ((base shr 16 and 255) * s).roundToInt().coerceIn(0, 255)
                        val g = ((base shr 8 and 255) * s).roundToInt().coerceIn(0, 255)
                        val b = ((base and 255) * s).roundToInt().coerceIn(0, 255)
                        pixels[y * width + x] = (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }
        return argbToYuv(width, height, pixels, noise)
    }

    private fun renderEmptyYuv(
        width: Int,
        height: Int,
        dark: Boolean,
    ): YuvFrame {
        val base = if (dark) 0x16181c else 0xdcdad2
        return argbToYuv(width, height, IntArray(width * height) { base }, noise = 3)
    }

    private fun renderDarkGlossyYuv(
        width: Int,
        height: Int,
        seed: Int,
    ): YuvFrame {
        val pixels = IntArray(width * height)
        val cx = width * (0.40 + (seed % 3) * 0.10)
        val cy = height * (0.42 + ((seed / 3) % 3) * 0.08)
        val rx = width * 0.18
        val ry = height * 0.16
        for (y in 0 until height) {
            val dy = (y - cy) / ry
            for (x in 0 until width) {
                val dx = (x - cx) / rx
                val sheen = ((x + y + seed * 17) % width).toDouble() / width
                val base = 16.0 + sheen * 22.0
                val blob = kotlin.math.exp(-0.5 * (dx * dx + dy * dy))
                val r = (base + blob * (if (seed % 2 == 0) 36.0 else 18.0)).roundToInt().coerceIn(0, 255)
                val g = (base + blob * (if (seed % 3 == 0) 30.0 else 16.0)).roundToInt().coerceIn(0, 255)
                val b = (base + blob * (if (seed % 2 == 1) 34.0 else 14.0)).roundToInt().coerceIn(0, 255)
                pixels[y * width + x] = (r shl 16) or (g shl 8) or b
            }
        }
        return argbToYuv(width, height, pixels, noise = 3)
    }

    /**
     * Camera-shaped `YUV_420_888` planes: direct buffers, padded rows and `pixelStride = 2` chroma, so the timed
     * loops pay the same buffer access cost as frames from an `ImageProxy`.
     */
    private fun argbToYuv(
        width: Int,
        height: Int,
        pixels: IntArray,
        noise: Int,
    ): YuvFrame {
        val yStride = width + ROW_PADDING
        val uvW = (width + 1) / 2
        val uvH = (height + 1) / 2
        val uvStride = uvW * 2 + ROW_PADDING
        val yBuf = ByteBuffer.allocateDirect(yStride * height)
        val uBuf = ByteBuffer.allocateDirect(uvStride * uvH)
        val vBuf = ByteBuffer.allocateDirect(uvStride * uvH)
        val rng = Random(42)
        for (row in 0 until height) {
            for (col in 0 until width) {
                val rgb = pixels[row * width + col]
                val r = (rgb shr 16) and 255
                val g = (rgb shr 8) and 255
                val b = rgb and 255
                val n = if (noise > 0) rng.nextInt(2 * noise + 1) - noise else 0
                yBuf.put(row * yStride + col, (0.299 * r + 0.587 * g + 0.114 * b + n).roundToInt().coerceIn(0, 255).toByte())
                if (row % 2 == 0 && col % 2 == 0) {
                    val idx = (row / 2) * uvStride + (col / 2) * 2
                    uBuf.put(idx, (128 - 0.168736 * r - 0.331264 * g + 0.5 * b).roundToInt().coerceIn(0, 255).toByte())
                    vBuf.put(idx, (128 + 0.5 * r - 0.418688 * g - 0.081312 * b).roundToInt().coerceIn(0, 255).toByte())
                }
            }
        }
        return YuvFrame(YuvPlane(yBuf, yStride, 1), YuvPlane(uBuf, uvStride, 2), YuvPlane(vBuf, uvStride, 2), 0, 0, width, height)
    }

    private fun expectedFace(
        cube: CubeState,
        face: Face,
    ): List<Color> {
        val colors =
            cube
                .toFacelets()
                .colors
                .drop(face.ordinal * 9)
                .take(9)
        return when (face) {
            Face.D -> listOf(2, 5, 8, 1, 4, 7, 0, 3, 6).map(colors::get)
            Face.L, Face.B -> colors.reversed()
            else -> colors
        }
    }

    private data class TuningStillExpectation(
        val id: String,
        val fileName: String,
        val width: Int,
        val height: Int,
        val peaks: List<Point2D>,
    )

    private companion object {
        const val TAG = "LiteRtCenterDeviceTest"
        const val TUNING_STILLS_DIR = "/data/local/tmp/cubentia-10-31-tuning"
        const val ROW_PADDING = 32

        val BENCHMARK_SIZES = listOf(640 to 480, 1280 to 960)

        fun isEmulator(): Boolean =
            Build.HARDWARE in setOf("ranchu", "goldfish") ||
                Build.FINGERPRINT.startsWith("generic") ||
                Build.PRODUCT.startsWith("sdk")

        fun isGalaxyS24Fe(): Boolean = Build.MODEL.contains("SM-S721", ignoreCase = true)

        val PALETTE =
            mapOf(
                Color.WHITE to 0xf0eee8,
                Color.YELLOW to 0xe8d81c,
                Color.RED to 0xd22d30,
                Color.ORANGE to 0xeb8228,
                Color.GREEN to 0x3eb44a,
                Color.BLUE to 0x2d72da,
            )

        /** Exact PC expectations copied from `core/domain/src/test/resources/fixtures/continuous/corner-peaks-parity.json`. */
        val TUNING_EXPECTATIONS =
            listOf(
                TuningStillExpectation(
                    id = "lenovo-tb128xu_stka_bar-warm/corner-1",
                    fileName = "lenovo-tb128xu_stka_bar-warm_corner-1.jpg",
                    width = 206,
                    height = 206,
                    peaks =
                        listOf(
                            Point2D(137.324, 124.0),
                            Point2D(114.4218, 139.2542),
                            Point2D(89.7847, 140.0937),
                            Point2D(43.0627, 82.8395),
                            Point2D(47.2576, 109.9926),
                            Point2D(114.4999, 163.4059),
                            Point2D(88.4375, 112.0621),
                            Point2D(101.5468, 37.0156),
                            Point2D(66.902, 146.0041),
                            Point2D(67.9341, 124.1171),
                            Point2D(139.1371, 97.4886),
                            Point2D(124.7614, 49.4416),
                            Point2D(55.6448, 61.7699),
                            Point2D(159.4453, 108.6872),
                            Point2D(135.8597, 147.3317),
                            Point2D(115.1247, 112.1207),
                            Point2D(89.7261, 163.2107),
                            Point2D(79.367, 73.2809),
                            Point2D(90.7387, 164.5747),
                            Point2D(149.6328, 61.7894),
                            Point2D(63.4489, 92.9058),
                            Point2D(104.0432, 89.5894),
                            Point2D(63.7808, 94.2112),
                            Point2D(101.5078, 60.5426),
                            Point2D(129.7151, 75.2502),
                            Point2D(81.5705, 48.8364),
                            Point2D(81.6681, 47.9604),
                            Point2D(160.1676, 84.555),
                            Point2D(48.7192, 132.9143),
                            Point2D(158.9767, 131.1208),
                        ),
                ),
                TuningStillExpectation(
                    id = "lenovo-tb128xu_stlb_bar-warm/corner-1",
                    fileName = "lenovo-tb128xu_stlb_bar-warm_corner-1.jpg",
                    width = 206,
                    height = 206,
                    peaks =
                        listOf(
                            Point2D(146.0041, 60.2888),
                            Point2D(133.8319, 143.7615),
                            Point2D(68.6174, 120.4493),
                            Point2D(91.3439, 136.2307),
                            Point2D(131.1013, 98.3671),
                            Point2D(101.1563, 59.9373),
                            Point2D(66.1406, 94.7579),
                            Point2D(79.4256, 73.6909),
                            Point2D(88.7499, 156.3046),
                            Point2D(91.2268, 110.8907),
                            Point2D(55.8205, 61.9066),
                            Point2D(123.3947, 48.9144),
                            Point2D(104.5313, 86.4878),
                            Point2D(46.5157, 85.3359),
                            Point2D(126.633, 74.4887),
                            Point2D(159.1329, 79.1718),
                            Point2D(155.2503, 127.902),
                            Point2D(114.7928, 110.2855),
                            Point2D(155.9532, 104.9998),
                            Point2D(137.0312, 119.5903),
                            Point2D(140.0156, 117.3282),
                            Point2D(114.4218, 136.2697),
                            Point2D(113.8166, 159.4453),
                            Point2D(66.2382, 140.8551),
                            Point2D(66.902, 142.5511),
                            Point2D(98.3671, 39.5706),
                            Point2D(47.6481, 106.891),
                            Point2D(89.2966, 43.6874),
                            Point2D(52.6603, 130.7694),
                        ),
                ),
                TuningStillExpectation(
                    id = "sm-s721u1_stla_bar-cool-2/corner-2",
                    fileName = "sm-s721u1_stla_bar-cool-2_corner-2.jpg",
                    width = 422,
                    height = 422,
                    peaks =
                        listOf(
                            Point2D(245.8658, 109.9567),
                            Point2D(134.6519, 200.1895),
                            Point2D(183.0479, 281.7542),
                            Point2D(205.9434, 133.0122),
                            Point2D(144.2053, 294.5817),
                            Point2D(233.0383, 326.3106),
                            Point2D(137.5716, 248.8254),
                            Point2D(280.4743, 195.8355),
                            Point2D(232.1984, 280.6343),
                            Point2D(106.7171, 266.8069),
                            Point2D(270.881, 251.9796),
                            Point2D(181.968, 228.8042),
                            Point2D(213.217, 183.5678),
                            Point2D(268.8066, 292.102),
                            Point2D(160.387, 110.1567),
                            Point2D(97.3691, 222.7704),
                            Point2D(260.4531, 153.7933),
                            Point2D(88.9356, 175.0543),
                            Point2D(324.1909, 154.4332),
                            Point2D(236.8378, 228.8042),
                            Point2D(300.5356, 261.533),
                            Point2D(188.9617, 331.6246),
                            Point2D(309.0091, 221.2106),
                            Point2D(315.6028, 176.4941),
                            Point2D(293.5019, 133.7721),
                            Point2D(200.3895, 93.7296),
                            Point2D(115.9106, 136.2517),
                            Point2D(155.0331, 156.153),
                        ),
                ),
            )
    }
}
