package dev.cubentia.app.camera

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cubentia.app.camera.continuous.ContinuousFaceSampler
import dev.cubentia.app.camera.continuous.LiteRtStickerCenterDetector
import dev.cubentia.app.camera.continuous.StickerCenterModelContract
import dev.cubentia.domain.recognition.continuous.FaceLatticeFitter
import dev.cubentia.domain.recognition.continuous.FittedFace
import dev.cubentia.domain.recognition.continuous.Point2D
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionFormat
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionFrame
import dev.cubentia.domain.recognition.continuous.session.ContinuousSessionJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * Story 10.34 image replay (AD-9: instrumented debug test only, LiteRT is Android-only). For every session pushed to
 * [SESSIONS_DIR], each stored model-input PNG goes through [LiteRtStickerCenterDetector.detectRgb] with the recorded
 * model (its SHA-256 must match the manifest) and must give the recorded peaks: same count, each within [TOLERANCE_PX]
 * model-input pixels after nearest matching, and the same per-sticker colour class (lattice fitted on both peak sets,
 * cells sampled from the same PNG, each classified to the nearest of that frame's centre-cell colours). Then a
 * candidate model pushed to [CANDIDATE_MODEL] runs the same frames and its differences are logged, never failed.
 *
 * Push: `adb push Fixtures/continuous/<id> /data/local/tmp/cubentia-10-34-sessions/<id>`. Skipped when nothing is pushed.
 */
@RunWith(AndroidJUnit4::class)
class ContinuousImageReplayDeviceTest {
    private data class ImageFrame(
        val sessionId: String,
        val frame: ContinuousSessionFrame,
        val rgb: ByteArray,
        val size: Int,
    )

    private fun imageFrames(): List<ImageFrame> {
        val root = File(SESSIONS_DIR)
        return ContinuousSessionFormat.sessions(root).flatMap { dir ->
            val manifest = ContinuousSessionJson.readManifest(dir)
            ContinuousSessionJson.readFrames(dir).mapNotNull { frame ->
                val name = frame.image ?: return@mapNotNull null
                val bitmap =
                    BitmapFactory.decodeFile(File(dir, name).path, BitmapFactory.Options().apply { inScaled = false })
                        ?: throw AssertionError("${manifest.sessionId}: image $name is missing or not a decodable PNG")
                val side = bitmap.width
                val argb = IntArray(side * bitmap.height)
                bitmap.getPixels(argb, 0, side, 0, 0, side, bitmap.height)
                bitmap.recycle()
                val rgb = ByteArray(argb.size * 3)
                argb.forEachIndexed { i, px ->
                    rgb[i * 3] = (px shr 16 and 0xFF).toByte()
                    rgb[i * 3 + 1] = (px shr 8 and 0xFF).toByte()
                    rgb[i * 3 + 2] = (px and 0xFF).toByte()
                }
                ImageFrame(manifest.sessionId, frame, rgb, side)
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Each of [a] matched to its nearest in [b]; the largest such distance (infinite when counts differ). */
    private fun maxNearest(
        a: List<Point2D>,
        b: List<Point2D>,
    ): Double {
        if (a.size != b.size) return Double.POSITIVE_INFINITY
        val free = b.toMutableList()
        return a.maxOfOrNull { p ->
            val nearest = free.minBy { hypot(it.x - p.x, it.y - p.y) }
            free.remove(nearest)
            hypot(nearest.x - p.x, nearest.y - p.y)
        } ?: 0.0
    }

    /** Per fitted face (ordered by quad centre), each cell's index of the nearest centre-cell colour in this frame. */
    private fun colourClasses(
        faces: List<FittedFace>,
        rgb: ByteArray,
        size: Int,
    ): List<List<Int>> {
        val sampler = ContinuousFaceSampler()
        val observations =
            faces
                .sortedWith(compareBy({ it.quad.corners.sumOf { c -> c.x } }, { it.quad.corners.sumOf { c -> c.y } }))
                .mapNotNull { sampler.sampleRgb(rgb, size, size, it) }
        val centres = observations.map { it.cellRgbs[4] }
        return observations.map { observation ->
            observation.cellRgbs.map { cell ->
                centres.indices.minBy { i ->
                    val c = centres[i]
                    (c.r - cell.r) * (c.r - cell.r) + (c.g - cell.g) * (c.g - cell.g) + (c.b - cell.b) * (c.b - cell.b)
                }
            }
        }
    }

    @Test
    fun recordedModelReproducesTheRecordedDetectionsAndACandidateIsReported() {
        val root = File(SESSIONS_DIR)
        val sessions = ContinuousSessionFormat.sessions(root)
        assumeTrue("No continuous session pushed under $SESSIONS_DIR; skipping image replay", sessions.isNotEmpty())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelBytes = context.assets.open(StickerCenterModelContract.MODEL_ASSET).use { it.readBytes() }
        val bundledSha = sha256(modelBytes)
        sessions.forEach { dir ->
            val manifest = ContinuousSessionJson.readManifest(dir)
            if (manifest.imageFrames > 0) {
                assertEquals("${manifest.sessionId} was recorded with another model", manifest.model.sha256, bundledSha)
            }
        }
        val frames = imageFrames()
        assumeTrue("Pushed sessions hold no model-input PNG; skipping image replay", frames.isNotEmpty())
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor
                .submit {
                    val recorded = LiteRtStickerCenterDetector(context)
                    frames.forEach { f ->
                        val replay = recorded.detectRgb(f.rgb, f.size, f.size)
                        val replayed = replay.centers
                        val expected = f.frame.peaks.map { Point2D(it.x, it.y) }
                        val id = "${f.sessionId} frame ${f.frame.frameIndex}"
                        assertEquals("$id center count", expected.size, replayed.size)
                        val worst = maxNearest(expected, replayed)
                        assertTrue("$id peak moved $worst px (> $TOLERANCE_PX)", worst <= TOLERANCE_PX)
                        val replayedClasses = colourClasses(FaceLatticeFitter.fit(replayed), f.rgb, f.size)
                        val recordedClasses = colourClasses(FaceLatticeFitter.fit(expected), f.rgb, f.size)
                        assertEquals("$id per-sticker colour", recordedClasses, replayedClasses)
                    }
                    recorded.close()
                    val candidateFile = File(CANDIDATE_MODEL)
                    if (!candidateFile.isFile) {
                        Log.i(TAG, "No candidate model at $CANDIDATE_MODEL; candidate replay skipped")
                        return@submit
                    }
                    val candidate =
                        LiteRtStickerCenterDetector(loadModel = {
                            RandomAccessFile(candidateFile, "r").use { file ->
                                file.channel
                                    .map(
                                        FileChannel.MapMode.READ_ONLY,
                                        0,
                                        file.length(),
                                    ).order(ByteOrder.nativeOrder()) as ByteBuffer
                            }
                        })
                    var differing = 0
                    frames.forEach { f ->
                        val got = candidate.detectRgb(f.rgb, f.size, f.size).centers
                        val expected = f.frame.peaks.map { Point2D(it.x, it.y) }
                        val worst = maxNearest(expected, got)
                        if (got.size != expected.size || worst > TOLERANCE_PX) {
                            differing++
                            Log.i(
                                TAG,
                                "CANDIDATE ${f.sessionId} frame ${f.frame.frameIndex}: centers ${expected.size} -> ${got.size}, worst $worst px",
                            )
                        }
                    }
                    candidate.close()
                    Log.i(
                        TAG,
                        "CANDIDATE sha=${sha256(
                            candidateFile.readBytes(),
                        )} differs on $differing/${frames.size} frames (reported, not failed)",
                    )
                }.get()
        } finally {
            executor.shutdown()
        }
    }

    private companion object {
        const val TAG = "CubentiaImageReplay"
        const val SESSIONS_DIR = "/data/local/tmp/cubentia-10-34-sessions"
        const val CANDIDATE_MODEL = "/data/local/tmp/cubentia-10-34-candidate.tflite"
        const val TOLERANCE_PX = 2.0
    }
}
