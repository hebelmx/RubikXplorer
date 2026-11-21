package dev.cubentia.app.camera

import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cubentia.app.camera.corner.LiteRtCornerDetector
import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.FaceTurn
import dev.cubentia.domain.cube.Turn
import dev.cubentia.domain.recognition.QuickScanPlan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * On-device verification of [LiteRtCornerDetector] on the main app debug build (Lenovo TB128XU, Story 10.18 AC 2 & AC 3):
 * - Inference runs off the main thread in `< 40 ms` median per frame over >= 50 frames.
 * - Detects 7 visible corners across 0/90/180/270 rotations, stickered & stickerless, solved & scrambled cubes,
 *   reporting pixel error and validating end-to-end [CornerReader.read] unwarping.
 * - Rejects empty and dark/glossy reflection-only negative frames (#196) by returning `null`.
 *
 * With `-Pcubentia.cornerR8=true` it also runs against the R8-minified `cornerR8` build type
 * (`:app:connectedCornerR8AndroidTest`), proving the LiteRT keep rules (device gate D3).
 */
@RunWith(AndroidJUnit4::class)
class LiteRtCornerDetectorDeviceTest {
    @Test
    fun runsOffMainThreadUnder40msMedianWithHighAccuracyAndNegativeRejection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = LiteRtCornerDetector(context)
        val executor = Executors.newSingleThreadExecutor()
        try {
            // Production analysis frames are 640x480 or 1280x960 (ScanFrameSource DEFAULT_ANALYSIS / LARGE_ANALYSIS), so the
            // crop's decimation/aliasing is benchmarked at those sizes too, with the same asserts per size.
            for (scale in FRAME_SCALES) {
                val result =
                    executor
                        .submit<BenchmarkReport> {
                            assertFalse("Inference must run off the main thread", Looper.myLooper() == Looper.getMainLooper())
                            runBenchmark(detector, scale)
                        }.get()
                assertBenchmark(result, "${BASE_LONG * scale}x${BASE_SHORT * scale}")
            }
        } finally {
            detector.close()
            executor.shutdownNow()
        }
    }

    private fun assertBenchmark(
        result: BenchmarkReport,
        size: String,
    ) {
        Log.i(
            TAG,
            "LiteRtCornerDetector device benchmark: size=$size frames=${result.totalFrames} " +
                "medianMs=${"%.2f".format(result.medianMs)} p90Ms=${"%.2f".format(result.p90Ms)} " +
                "minMs=${"%.2f".format(result.minMs)} maxMs=${"%.2f".format(result.maxMs)} " +
                "posDetected=${result.positiveDetected}/${result.positiveTotal} " +
                "meanPxErr=${"%.2f".format(result.meanKeypointErrPx)} " +
                "realPaletteDetected=${result.realPaletteDetected}/${result.realPaletteTotal} " +
                "realPaletteMeanPxErr=${"%.2f".format(result.realPaletteMeanErrPx)} " +
                "negRejected=${result.negativeRejected}/${result.negativeTotal}",
        )

        assertTrue("[$size] Expected >= 50 benchmarked frames, got ${result.totalFrames}", result.totalFrames >= 50)
        // The latency budget is a minimum-device (Lenovo TB128XU) gate; an emulator's timing says nothing about it,
        // so the release workflow's CI emulator run checks accuracy and rejection only.
        if (!isEmulator()) {
            assertTrue(
                "[$size] Expected median latency < 40 ms on minimum device, got ${"%.2f".format(result.medianMs)} ms",
                result.medianMs < 40.0,
            )
        }
        assertTrue(
            "[$size] Expected >= 90% detection on synthetic 3D cubes, got ${result.positiveDetected}/${result.positiveTotal}",
            result.positiveDetected * 10 >= result.positiveTotal * 9,
        )
        assertTrue(
            "[$size] Expected mean keypoint error < 10 px (320x240-equivalent) on synthetic cubes, got ${"%.2f".format(
                result.meanKeypointErrPx,
            )} px",
            result.meanKeypointErrPx < 10.0,
        )
        assertTrue("[$size] End-to-end CornerReader.read check never ran (no positive detected)", result.endToEndChecked)
        assertTrue(
            "[$size] Expected >= 90% detection on real Lenovo/S24 FE palette cubes, got ${result.realPaletteDetected}/${result.realPaletteTotal}",
            result.realPaletteDetected * 10 >= result.realPaletteTotal * 9,
        )
        assertTrue(
            "[$size] Expected mean keypoint error < 10 px (320x240-equivalent) on real-palette cubes, got ${"%.2f".format(
                result.realPaletteMeanErrPx,
            )} px",
            result.realPaletteMeanErrPx < 10.0,
        )
        assertTrue(
            "[$size] Every empty & dark/glossy reflection frame must return null, got ${result.negativeRejected}/${result.negativeTotal}",
            result.negativeRejected == result.negativeTotal,
        )
    }

    /**
     * Benchmarks one frame size, [scale] x the 320x240 base. Keypoint errors are divided by [scale] (reported in
     * 320x240-equivalent px), so the < 10 px accuracy bar means the same relative accuracy at every size.
     */
    private fun runBenchmark(
        detector: LiteRtCornerDetector,
        scale: Int,
    ): BenchmarkReport {
        val long = BASE_LONG * scale
        val short = BASE_SHORT * scale
        val scramble =
            CubeState.SOLVED.apply(
                listOf(FaceTurn(Face.R, Turn.CLOCKWISE), FaceTurn(Face.U, Turn.HALF), FaceTurn(Face.F, Turn.COUNTERCLOCKWISE)),
            )
        val rng = Random(1018)
        val rotations = listOf(0, 90, 180, 270)
        val latenciesMs = mutableListOf<Double>()
        val keypointErrors = mutableListOf<Double>()
        var posDetected = 0
        val posTotal = 40
        var negRejected = 0
        val negTotal = 20
        var endToEndChecked = false
        var realDetected = 0
        val realErrors = mutableListOf<Double>()
        val realTotal = REAL_PALETTES.size * 3

        fun predicted(loc: CornerLocator.Location) =
            listOf(
                loc.quads[0][0], // T
                loc.quads[0][3], // UL
                loc.quads[1][3], // LL
                loc.quads[1][2], // B
                loc.quads[2][2], // LR
                loc.quads[0][1], // UR
                loc.quads[0][2], // C
            )

        // Warm up interpreter (lazy load + XNNPACK delegate init)
        run {
            val warmGuide = CornerGuide(ViewfinderGrid.inFrame(0.0, 0.0, 0, 0, long, short, 90), 90)
            val warmPts = CornerLocator.projectCube(warmGuide.vertices().last(), warmGuide.radius * 0.80, 90, 32.0, 0.0, 0.0, 0.20)
            val warmFrame =
                renderCubeYuv(long, short, warmGuide.quads(warmPts), scramble, 0, stickerless = false, bgRgb = 0xdcdad4, noise = 2)
            assertNotNull("Warmup cube should detect cleanly", detector.locate(warmFrame, warmGuide))
        }

        // 40 diverse positive 3D cube frames across 0/90/180/270 rotations, stickered + stickerless, solved + scrambled
        for (i in 0 until posTotal) {
            val rot = rotations[i % rotations.size]
            val w = if (rot % 180 == 0) short else long
            val h = if (rot % 180 == 0) long else short
            val guide = CornerGuide(ViewfinderGrid.inFrame(0.0, 0.0, 0, 0, w, h, rot), rot)
            val dx = (rng.nextDouble() - 0.5) * guide.radius * 0.45
            val dy = (rng.nextDouble() - 0.5) * guide.radius * 0.45
            val scale = 0.62 + rng.nextDouble() * 0.34
            // The full trained pose range (tools/corner-model: pitch 16-52, yaw +-26, roll +-28 degrees).
            val pitch = 16.0 + rng.nextDouble() * 36.0
            val yaw = (rng.nextDouble() - 0.5) * 52.0
            val roll = (rng.nextDouble() - 0.5) * 56.0
            val persp = 0.12 + rng.nextDouble() * 0.18
            val truthPts =
                CornerLocator.projectCube(
                    guide.vertices().last() + CornerPoint(dx, dy),
                    guide.radius * scale,
                    rot,
                    pitch,
                    yaw,
                    roll,
                    persp,
                )
            val cube = if (i % 4 == 0) CubeState.SOLVED else scramble
            val holdIdx = i % 2
            val stickerless = i % 2 == 1
            val bgRgb = if (i % 3 == 0) 0x181c22 else 0xd8d6d0
            val frame = renderCubeYuv(w, h, guide.quads(truthPts), cube, holdIdx, stickerless, bgRgb, noise = 3)

            val t0 = SystemClock.elapsedRealtimeNanos()
            val loc = detector.locate(frame, guide)
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
            latenciesMs += elapsedMs

            if (loc != null) {
                posDetected++
                val err = truthPts.zip(predicted(loc)).map { (a, b) -> hypot(a.x - b.x, a.y - b.y) }.average() / scale
                keypointErrors += err
                if (!endToEndChecked) {
                    val reading = CornerReader.read(frame, guide, detector = detector)
                    assertTrue("End-to-end CornerReader.read should detect via LiteRT", reading.detected)
                    endToEndChecked = true
                }
            }
        }

        // Real Lenovo TB128XU & Galaxy S24 FE palettes (Fixtures/live stka/stkb/stla/stlb), 3 poses each, scored separately.
        REAL_PALETTES.values.forEachIndexed { p, colors ->
            val palette = REAL_PALETTE_ORDER.zip(colors).toMap()
            repeat(3) { k ->
                val i = p * 3 + k
                val rot = rotations[i % rotations.size]
                val w = if (rot % 180 == 0) short else long
                val h = if (rot % 180 == 0) long else short
                val guide = CornerGuide(ViewfinderGrid.inFrame(0.0, 0.0, 0, 0, w, h, rot), rot)
                val truthPts =
                    CornerLocator.projectCube(
                        guide.vertices().last(),
                        guide.radius * (0.66 + rng.nextDouble() * 0.28),
                        rot,
                        24.0 + rng.nextDouble() * 18.0,
                        (rng.nextDouble() - 0.5) * 28.0,
                        (rng.nextDouble() - 0.5) * 28.0,
                        0.14 + rng.nextDouble() * 0.14,
                    )
                val frame =
                    renderCubeYuv(
                        w,
                        h,
                        guide.quads(truthPts),
                        scramble,
                        k % 2,
                        stickerless = p % 2 == 1,
                        bgRgb = 0xd8d6d0,
                        noise = 3,
                        palette = palette,
                    )
                val t0 = SystemClock.elapsedRealtimeNanos()
                val loc = detector.locate(frame, guide)
                latenciesMs += (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
                if (loc != null) {
                    realDetected++
                    realErrors += truthPts.zip(predicted(loc)).map { (a, b) -> hypot(a.x - b.x, a.y - b.y) }.average() / scale
                }
            }
        }

        // 20 negative frames: 10 empty plain/dark + 10 dark glossy reflection-only (#196)
        for (i in 0 until negTotal) {
            val rot = rotations[i % rotations.size]
            val w = if (rot % 180 == 0) short else long
            val h = if (rot % 180 == 0) long else short
            val guide = CornerGuide(ViewfinderGrid.inFrame(0.0, 0.0, 0, 0, w, h, rot), rot)
            val frame = if (i < 10) renderEmptyYuv(w, h, dark = i % 2 == 0) else renderDarkGlossyReflectionYuv(w, h, i)

            val t0 = SystemClock.elapsedRealtimeNanos()
            val loc = detector.locate(frame, guide)
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
            latenciesMs += elapsedMs

            if (loc == null) negRejected++
        }

        val sorted = latenciesMs.sorted()
        val median = sorted[sorted.size / 2]
        val p90 = sorted[(sorted.size * 9) / 10]
        return BenchmarkReport(
            totalFrames = sorted.size,
            medianMs = median,
            p90Ms = p90,
            minMs = sorted.first(),
            maxMs = sorted.last(),
            positiveDetected = posDetected,
            positiveTotal = posTotal,
            meanKeypointErrPx = if (keypointErrors.isEmpty()) Double.POSITIVE_INFINITY else keypointErrors.average(),
            negativeRejected = negRejected,
            negativeTotal = negTotal,
            endToEndChecked = endToEndChecked,
            realPaletteDetected = realDetected,
            realPaletteTotal = realTotal,
            realPaletteMeanErrPx = if (realErrors.isEmpty()) Double.POSITIVE_INFINITY else realErrors.average(),
        )
    }

    private data class BenchmarkReport(
        val totalFrames: Int,
        val medianMs: Double,
        val p90Ms: Double,
        val minMs: Double,
        val maxMs: Double,
        val positiveDetected: Int,
        val positiveTotal: Int,
        val meanKeypointErrPx: Double,
        val negativeRejected: Int,
        val negativeTotal: Int,
        val endToEndChecked: Boolean,
        val realPaletteDetected: Int,
        val realPaletteTotal: Int,
        val realPaletteMeanErrPx: Double,
    )

    private fun renderCubeYuv(
        width: Int,
        height: Int,
        quads: List<List<CornerPoint>>,
        cube: CubeState,
        holdIdx: Int,
        stickerless: Boolean,
        bgRgb: Int,
        noise: Int,
        palette: Map<Color, Int> = PALETTE,
    ): YuvFrame {
        val pixels = IntArray(width * height) { bgRgb }
        val expected = QuickScanPlan.holds[holdIdx].map { step -> expectedFace(cube, step.face) }
        val gap = if (stickerless) 0.025 else 0.09
        val shades = listOf(1.0, 0.88, 0.78)
        // 240 samples per face edge at 320x240; scale with the frame so larger frames have no unpainted holes.
        val samples = 240 * (maxOf(width, height) / BASE_LONG).coerceAtLeast(1)
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
                                palette.getValue(expected[face][(v * 3).toInt().coerceIn(0, 2) * 3 + (u * 3).toInt().coerceIn(0, 2)])
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
        return rgbToYuv(width, height, pixels, noise)
    }

    private fun renderEmptyYuv(
        width: Int,
        height: Int,
        dark: Boolean,
    ): YuvFrame {
        val base = if (dark) 0x16181c else 0xdcdad2
        val pixels = IntArray(width * height) { base }
        return rgbToYuv(width, height, pixels, noise = 3)
    }

    private fun renderDarkGlossyReflectionYuv(
        width: Int,
        height: Int,
        seed: Int,
    ): YuvFrame {
        val pixels = IntArray(width * height)
        val patchPx = 24 * (maxOf(width, height) / BASE_LONG).coerceAtLeast(1)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sheen = ((x + y + seed * 17) % width).toDouble() / width
                val base = (16 + sheen * 28).roundToInt()
                val glow =
                    if (x in width / 3..(2 * width / 3) && y in height / 3..(2 * height / 3)) {
                        val patch = ((x / patchPx + y / patchPx + seed) % 3)
                        when (patch) {
                            0 -> (base + 34 shl 16) or (base + 12 shl 8) or base + 10
                            1 -> (base + 10 shl 16) or (base + 30 shl 8) or base + 12
                            else -> (base + 10 shl 16) or (base + 14 shl 8) or base + 32
                        }
                    } else {
                        (base shl 16) or (base shl 8) or base
                    }
                pixels[y * width + x] = glow
            }
        }
        return rgbToYuv(width, height, pixels, noise = 3)
    }

    private fun rgbToYuv(
        width: Int,
        height: Int,
        pixels: IntArray,
        noise: Int,
    ): YuvFrame {
        val yBuf = ByteBuffer.allocate(width * height)
        val uBuf = ByteBuffer.allocate(width * height / 4)
        val vBuf = ByteBuffer.allocate(width * height / 4)
        val rng = Random(42)
        for (row in 0 until height) {
            for (col in 0 until width) {
                val rgb = pixels[row * width + col]
                val r = rgb shr 16 and 255
                val g = rgb shr 8 and 255
                val b = rgb and 255
                val n = if (noise > 0) rng.nextInt(2 * noise + 1) - noise else 0
                yBuf.put(row * width + col, (0.299 * r + 0.587 * g + 0.114 * b + n).roundToInt().coerceIn(0, 255).toByte())
                if (row % 2 == 0 && col % 2 == 0) {
                    val idx = (row / 2) * (width / 2) + (col / 2)
                    uBuf.put(idx, (128 - 0.168736 * r - 0.331264 * g + 0.5 * b).roundToInt().coerceIn(0, 255).toByte())
                    vBuf.put(idx, (128 + 0.5 * r - 0.418688 * g - 0.081312 * b).roundToInt().coerceIn(0, 255).toByte())
                }
            }
        }
        return YuvFrame(YuvPlane(yBuf, width, 1), YuvPlane(uBuf, width / 2, 1), YuvPlane(vBuf, width / 2, 1), 0, 0, width, height)
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

    private companion object {
        /** Android emulator images (CI's `google_apis` x86_64 AVD) report a ranchu/goldfish board or a generic build. */
        fun isEmulator(): Boolean =
            Build.HARDWARE in setOf("ranchu", "goldfish") ||
                Build.FINGERPRINT.startsWith("generic") ||
                Build.PRODUCT.startsWith("sdk")

        const val TAG = "LiteRtDeviceTest"

        const val BASE_LONG = 320
        const val BASE_SHORT = 240

        /** 320x240, plus the production analysis sizes 640x480 and 1280x960 (ScanFrameSource.kt). */
        val FRAME_SCALES = listOf(1, 2, 4)

        val REAL_PALETTE_ORDER = listOf(Color.WHITE, Color.YELLOW, Color.RED, Color.ORANGE, Color.GREEN, Color.BLUE)

        /** sRGB of each fixture group's calibrated centers (Fixtures/live/<group>/U.json), in [REAL_PALETTE_ORDER]. */
        val REAL_PALETTES =
            mapOf(
                "lenovo-tb128xu_stka_studio-a" to listOf(0xbdeaff, 0xfbeb00, 0xf14945, 0xffa83c, 0x00b10a, 0x0056e9),
                "lenovo-tb128xu_stkb_studio-a" to listOf(0xe6f1e2, 0xf0fe00, 0xcf241a, 0xff9a58, 0x00d900, 0x009bf1),
                "lenovo-tb128xu_stla_studio-a" to listOf(0xd7d2ca, 0xcbd100, 0xe12315, 0xff741e, 0x00b900, 0x0057b6),
                "lenovo-tb128xu_stlb_studio-a" to listOf(0xa1daef, 0xd4e200, 0xdd4453, 0xff913d, 0x4bcb00, 0x0bb3dd),
                "sm-s721u1_stka_studio-a" to listOf(0xced3da, 0xe6d60c, 0xe4152d, 0xfc8a32, 0x33a64c, 0x0247cc),
                "sm-s721u1_stkb_studio-a" to listOf(0xc4cfcc, 0xbfdd3f, 0xbd221f, 0xfc642e, 0x1bc64e, 0x0382d7),
                "sm-s721u1_stla_studio-a" to listOf(0xc2ccca, 0xb7d005, 0xc7151a, 0xed5804, 0x12bb22, 0x035db9),
                "sm-s721u1_stlb_studio-a" to listOf(0xbec8c8, 0xc3da05, 0xcd3342, 0xf9630a, 0x6ac925, 0x2e9dc8),
            )
        val PALETTE =
            mapOf(
                Color.WHITE to 0xf0eee8,
                Color.YELLOW to 0xe8d81c,
                Color.RED to 0xd22d30,
                Color.ORANGE to 0xeb8228,
                Color.GREEN to 0x3eb44a,
                Color.BLUE to 0x2d72da,
            )
    }
}
