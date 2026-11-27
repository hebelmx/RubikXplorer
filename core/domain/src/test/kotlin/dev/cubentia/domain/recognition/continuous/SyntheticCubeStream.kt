package dev.cubentia.domain.recognition.continuous

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.CubeState
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.cube.Notation
import dev.cubentia.domain.cube.NotationResult
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-JVM port of `pc_spike/synth_video.py` + `pc_spike/run_spike.py` + `_sample_face_cells`.
 *
 * Strictly obeys the honesty contract: `QuadObservation` exposes ONLY 2D clockwise corners
 * (randomly cyclically shifted + jittered) and sampled neutral per-cell RGBs/qualities.
 */
internal object SyntheticCubeStream {
    enum class Palette { STICKERED, STICKERLESS, PASTEL }

    private val PALETTES: Map<Palette, Map<Color, RgbColor>> =
        mapOf(
            Palette.STICKERED to
                mapOf(
                    Color.WHITE to RgbColor(236.0, 238.0, 240.0),
                    Color.YELLOW to RgbColor(235.0, 212.0, 32.0),
                    Color.GREEN to RgbColor(34.0, 168.0, 72.0),
                    Color.BLUE to RgbColor(28.0, 86.0, 206.0),
                    Color.RED to RgbColor(204.0, 38.0, 42.0),
                    Color.ORANGE to RgbColor(238.0, 118.0, 28.0),
                ),
            Palette.STICKERLESS to
                mapOf(
                    Color.WHITE to RgbColor(232.0, 236.0, 238.0),
                    Color.YELLOW to RgbColor(232.0, 224.0, 38.0),
                    Color.GREEN to RgbColor(46.0, 188.0, 82.0),
                    Color.BLUE to RgbColor(32.0, 108.0, 216.0),
                    Color.RED to RgbColor(214.0, 48.0, 56.0),
                    Color.ORANGE to RgbColor(242.0, 126.0, 48.0),
                ),
            Palette.PASTEL to
                mapOf(
                    Color.WHITE to RgbColor(228.0, 230.0, 228.0),
                    Color.YELLOW to RgbColor(226.0, 218.0, 124.0),
                    Color.GREEN to RgbColor(112.0, 192.0, 134.0),
                    Color.BLUE to RgbColor(104.0, 146.0, 210.0),
                    Color.RED to RgbColor(208.0, 96.0, 102.0),
                    Color.ORANGE to RgbColor(228.0, 148.0, 96.0),
                ),
        )

    data class Euler(
        val rx: Double,
        val ry: Double,
        val rz: Double,
    )

    data class ScenarioConfig(
        val name: String,
        val state: CubeState,
        val palette: Palette = Palette.STICKERED,
        val stickerless: Boolean = false,
        val logoOnWhite: Boolean = false,
        val glareStrength: Double = 0.0,
        val awbDrift: Boolean = false,
        val fingerOcclusion: Boolean = false,
        /** Finger ellipse radii (x, y) in screen pixels; the default is the S5 finger (issue #401 drives a larger one). */
        val fingerRadiiPx: Pair<Double, Double> = 36.0 to 22.0,
        val regripRange: IntRange? = null,
        val regripReturnEuler: Euler? = null,
        val startEuler: Euler = Euler(0.45, -0.55, 0.08),
        val waypoints: List<Euler>? = null,
        val jitterPx: Double = 0.8,
        val dropProb: Double = 0.0,
        val falseQuadProb: Double = 0.0,
        val motionBlurProb: Double = 0.0,
        val twistAtFrame: Int? = null,
        val twistMove: String = "U",
        val nFrames: Int = 120,
        val seed: Long = 20261124L,
    )

    data class SyntheticFrame(
        val frameIndex: Int,
        val detections: List<QuadObservation>,
        val visibleFaces: Map<Face, Quad2D>,
        val isRegrip: Boolean,
        val twistApplied: Boolean,
    )

    fun scramble(moves: String): CubeState {
        val parsed = Notation.parse(moves) as NotationResult.Parsed
        return CubeState.SOLVED.apply(parsed.moves)
    }

    fun stateToNet(state: CubeState): Map<Face, List<Color>> {
        val colors = state.toFacelets().colors
        return Face.entries.associateWith { f -> colors.subList(f.ordinal * 9, f.ordinal * 9 + 9) }
    }

    fun buildScenarios(): List<ScenarioConfig> {
        val scr1 = scramble("R U R' F' L2 D B' U2 R' F L D'")
        val scr2 = scramble("F R' D2 B U' L' F2 R U D' B2 L")
        val f2lSolved = scramble("R U R' U R U2 R' F R U R' U' F'")

        return listOf(
            ScenarioConfig(
                name = "1_scrambled_stickered_regrip",
                state = scr1,
                palette = Palette.STICKERED,
                regripRange = 52 until 66,
                regripReturnEuler = Euler(0.35, -2.6, 0.06),
                jitterPx = 1.2,
                nFrames = 130,
                seed = 101L,
            ),
            ScenarioConfig(
                name = "2_pastel_stickerless_awb_drift",
                state = scr2,
                palette = Palette.PASTEL,
                stickerless = true,
                awbDrift = true,
                jitterPx = 1.2,
                nFrames = 120,
                seed = 202L,
            ),
            ScenarioConfig(
                name = "3_logo_center_overhead_glare",
                state = scr1,
                palette = Palette.STICKERED,
                logoOnWhite = true,
                glareStrength = 0.95,
                jitterPx = 1.0,
                nFrames = 125,
                seed = 303L,
            ),
            ScenarioConfig(
                name = "4_mid_solve_f2l_symmetric_regrip",
                state = f2lSolved,
                palette = Palette.STICKERLESS,
                stickerless = true,
                regripRange = 55 until 68,
                regripReturnEuler = Euler(-1.55, 0.0, 0.0),
                jitterPx = 1.0,
                nFrames = 135,
                seed = 404L,
            ),
            ScenarioConfig(
                name = "5_finger_occlusion_jitter_clutter",
                state = scr2,
                palette = Palette.STICKERED,
                fingerOcclusion = true,
                jitterPx = 2.2,
                dropProb = 0.10,
                falseQuadProb = 0.25,
                motionBlurProb = 0.12,
                nFrames = 140,
                seed = 505L,
            ),
            ScenarioConfig(
                name = "6_start_no_white_yob_only",
                state = scr1,
                palette = Palette.STICKERLESS,
                stickerless = true,
                startEuler = Euler(0.55, -3.75, 0.05),
                waypoints =
                    listOf(
                        Euler(0.55, -3.75, 0.05),
                        Euler(1.25, -3.14, 0.04),
                        Euler(0.15, -3.14, 0.04),
                        Euler(0.10, -4.68, -0.04),
                        Euler(0.15, -6.15, 0.04),
                        Euler(0.55, -5.60, 0.05),
                        Euler(1.30, -6.20, 0.05),
                        Euler(0.12, -7.80, 0.03),
                    ),
                jitterPx = 1.0,
                nFrames = 120,
                seed = 606L,
            ),
            ScenarioConfig(
                name = "7_mid_scan_layer_twist_U",
                state = scr1,
                palette = Palette.STICKERED,
                twistAtFrame = 55,
                twistMove = "U",
                waypoints =
                    listOf(
                        Euler(0.48, -0.55, 0.05),
                        Euler(0.25, -0.20, 0.04),
                        Euler(0.55, -0.65, 0.05),
                        Euler(0.45, -0.55, 0.04),
                        Euler(0.35, -0.45, 0.05),
                        Euler(0.55, -0.75, 0.04),
                        Euler(0.20, -1.55, 0.03),
                    ),
                jitterPx = 0.8,
                nFrames = 95,
                seed = 707L,
            ),
        )
    }

    private fun defaultWaypoints(start: Euler): List<Euler> =
        listOf(
            start,
            Euler(0.18, -0.15, 0.04),
            Euler(0.65, -0.65, 0.05),
            Euler(1.32, -0.10, 0.05),
            Euler(0.55, -1.45, -0.05),
            Euler(0.12, -1.62, 0.03),
            Euler(0.30, -2.55, 0.06),
            Euler(0.15, -3.10, -0.04),
            Euler(-0.55, -3.75, 0.05),
            Euler(0.15, -4.68, -0.05),
            Euler(-1.28, -4.75, 0.04),
            Euler(-0.52, -5.65, 0.06),
            Euler(0.45, -6.28, 0.05),
        )

    private fun eulerMatrix(e: Euler): DoubleArray {
        val cx = cos(e.rx)
        val sx = sin(e.rx)
        val cy = cos(e.ry)
        val sy = sin(e.ry)
        val cz = cos(e.rz)
        val sz = sin(e.rz)
        // R = Rz * Ry * Rx
        return doubleArrayOf(
            cz * cy,
            cz * sy * sx - sz * cx,
            cz * sy * cx + sz * sx,
            sz * cy,
            sz * sy * sx + cz * cx,
            sz * sy * cx - cz * sx,
            -sy,
            cy * sx,
            cy * cx,
        )
    }

    private fun rotVec(
        r: DoubleArray,
        v: ContinuousCubeGeometry.Vec3,
    ): ContinuousCubeGeometry.Vec3 =
        ContinuousCubeGeometry.Vec3(
            r[0] * v.x + r[1] * v.y + r[2] * v.z,
            r[3] * v.x + r[4] * v.y + r[5] * v.z,
            r[6] * v.x + r[7] * v.y + r[8] * v.z,
        )

    private fun projectPoint(
        p: ContinuousCubeGeometry.Vec3,
        r: DoubleArray,
        tx: Double,
        ty: Double,
        tz: Double,
        fx: Double,
        cx0: Double,
        cy0: Double,
    ): Point2D {
        val w = rotVec(r, p)
        val xc = w.x + tx
        val yc = -(w.y + ty)
        val zc = tz - w.z
        return Point2D(fx * (xc / zc) + cx0, fx * (yc / zc) + cy0)
    }

    private data class RenderedFace(
        val face: Face,
        val quad: Quad2D,
        val hScreenToTex: DoubleArray,
        val tex: DoubleArray, // 180 * 180 * 3 RGB
    )

    private data class FingerEllipse(
        val cx: Double,
        val cy: Double,
        val rx: Double,
        val ry: Double,
        val cosA: Double,
        val sinA: Double,
        val rgb: RgbColor,
    ) {
        fun contains(
            x: Double,
            y: Double,
        ): Boolean {
            val dx = x - cx
            val dy = y - cy
            val lx = dx * cosA + dy * sinA
            val ly = -dx * sinA + dy * cosA
            val u = lx / rx
            val v = ly / ry
            return u * u + v * v <= 1.0
        }
    }

    fun generateSequence(
        cfg: ScenarioConfig,
        width: Int = 640,
        height: Int = 480,
    ): List<SyntheticFrame> {
        val rng = Random(cfg.seed)
        val pixelRng = Random(cfg.seed xor 0x1E3779B97F4A7C15L)
        val pal = PALETTES.getValue(cfg.palette)
        var curState = cfg.state
        val waypoints = cfg.waypoints ?: defaultWaypoints(cfg.startEuler)

        val fx = 620.0
        val cx0 = width / 2.0
        val cy0 = height / 2.0
        val lightLen = sqrt(0.22 * 0.22 + 0.42 * 0.42 + 0.88 * 0.88)
        val toLight = ContinuousCubeGeometry.Vec3(-0.22 / lightLen, 0.42 / lightLen, 0.88 / lightLen)

        val eulers = ArrayList<Euler>(cfg.nFrames)
        val segs = waypoints.size - 1
        for (i in 0 until cfg.nFrames) {
            val u = i.toDouble() / max(1, cfg.nFrames - 1) * segs
            val s = min(segs - 1, u.toInt())
            val alphaRaw = u - s
            val alpha = alphaRaw * alphaRaw * (3.0 - 2.0 * alphaRaw)
            val e0 = waypoints[s]
            val e1 = waypoints[s + 1]
            eulers.add(
                Euler(
                    (1.0 - alpha) * e0.rx + alpha * e1.rx,
                    (1.0 - alpha) * e0.ry + alpha * e1.ry,
                    (1.0 - alpha) * e0.rz + alpha * e1.rz,
                ),
            )
        }

        if (cfg.regripRange != null && cfg.regripReturnEuler != null) {
            val r1 = cfg.regripRange.last + 1
            val rem = cfg.nFrames - r1
            if (rem > 1) {
                val es = cfg.regripReturnEuler
                val subWp =
                    listOf(
                        es,
                        Euler(es.rx + 0.04, es.ry + 0.04, es.rz + 0.02),
                        Euler(es.rx + 0.62, es.ry + 0.55, es.rz - 0.04),
                        Euler(0.15, -3.10, -0.04),
                        Euler(0.15, -4.68, -0.05),
                        Euler(-1.28, -4.75, 0.04),
                        Euler(-0.45, -5.65, 0.05),
                        Euler(0.25, -6.28, 0.04),
                    )
                for (j in 0 until rem) {
                    val u = j.toDouble() / max(1, rem - 1) * (subWp.size - 1)
                    val s = min(subWp.size - 2, u.toInt())
                    val a = u - s
                    val e0 = subWp[s]
                    val e1 = subWp[s + 1]
                    eulers[r1 + j] =
                        Euler(
                            (1.0 - a) * e0.rx + a * e1.rx,
                            (1.0 - a) * e0.ry + a * e1.ry,
                            (1.0 - a) * e0.rz + a * e1.rz,
                        )
                }
            }
        }

        val texSize = 180
        val texCorners =
            listOf(
                Point2D(0.0, 0.0),
                Point2D((texSize - 1).toDouble(), 0.0),
                Point2D((texSize - 1).toDouble(), (texSize - 1).toDouble()),
                Point2D(0.0, (texSize - 1).toDouble()),
            )
        val cropSide = 96
        val cropCorners =
            listOf(
                Point2D(0.0, 0.0),
                Point2D((cropSide - 1).toDouble(), 0.0),
                Point2D((cropSide - 1).toDouble(), (cropSide - 1).toDouble()),
                Point2D(0.0, (cropSide - 1).toDouble()),
            )

        var twistDone = false
        val frames = ArrayList<SyntheticFrame>(cfg.nFrames)

        for (i in 0 until cfg.nFrames) {
            if (cfg.twistAtFrame != null && i == cfg.twistAtFrame && !twistDone) {
                val move = (Notation.parse(cfg.twistMove) as NotationResult.Parsed).moves
                curState = curState.apply(move)
                twistDone = true
            }

            val isRegrip = cfg.regripRange != null && i in cfg.regripRange
            if (isRegrip) {
                frames.add(SyntheticFrame(i, emptyList(), emptyMap(), isRegrip = true, twistApplied = twistDone))
                continue
            }

            val rMat = eulerMatrix(eulers[i])
            val tx = 0.35 * sin(2.0 * Math.PI * i / max(1, cfg.nFrames) * 1.7)
            val ty = 0.25 * cos(2.0 * Math.PI * i / max(1, cfg.nFrames) * 1.3)
            val tz = 6.2 + 0.45 * sin(2.0 * Math.PI * i / max(1, cfg.nFrames) * 1.1)
            val camWorld = ContinuousCubeGeometry.Vec3(-tx, -ty, tz)

            val awbR: Double
            val awbG: Double
            val awbB: Double
            if (cfg.awbDrift) {
                val phase = i.toDouble() / max(1, cfg.nFrames - 1)
                awbR = 0.90 + 0.24 * phase
                awbG = 0.98 + 0.02 * phase
                awbB = 1.14 - 0.34 * phase
            } else {
                awbR = 1.0
                awbG = 1.0
                awbB = 1.0
            }

            val curNet = stateToNet(curState)
            val visibleFaces = LinkedHashMap<Face, Quad2D>()
            val renderedFaces = ArrayList<RenderedFace>()
            val rawDetQuads = ArrayList<Pair<Quad2D, Double>>()

            for (f in Face.entries) {
                val nWorld = rotVec(rMat, ContinuousCubeGeometry.FACE_BASIS.getValue(f).normal)
                val vRaw = camWorld - nWorld
                val vLen = sqrt(vRaw.dot(vRaw))
                val viewDir = vRaw * (1.0 / vLen)
                val cosView = nWorld.dot(viewDir)
                if (cosView <= 0.22) continue

                val q3 = ContinuousCubeGeometry.faceQuad3d(f)
                val q2Pts = q3.map { p -> projectPoint(p, rMat, tx, ty, tz, fx, cx0, cy0) }
                val q2 = Quad2D(q2Pts)
                val q2Cw = ProjectiveGeometry.ensureClockwise(q2)
                visibleFaces[f] = q2

                val lambert = (0.48 + 0.58 * max(0.0, nWorld.dot(toLight))).coerceIn(0.45, 1.05)
                var spec = 0.0
                if (cfg.glareStrength > 0.0) {
                    val hvRaw = viewDir + toLight
                    val hvLen = sqrt(hvRaw.dot(hvRaw))
                    val hv = hvRaw * (1.0 / hvLen)
                    spec = max(0.0, nWorld.dot(hv)).pow(10.0)
                }
                val tex =
                    makeFaceTexture(
                        colors9 = curNet.getValue(f),
                        palette = pal,
                        stickerless = cfg.stickerless,
                        logoOnWhite = cfg.logoOnWhite,
                        texSize = texSize,
                        lambert = lambert,
                        awbR = awbR,
                        awbG = awbG,
                        awbB = awbB,
                        glareStrength = cfg.glareStrength,
                        spec = spec,
                        frameIndex = i,
                    )
                val hScreenToTex = ProjectiveGeometry.findHomography(q2Pts, texCorners)
                if (hScreenToTex != null) {
                    renderedFaces.add(RenderedFace(f, q2, hScreenToTex, tex))
                }

                if (rng.nextDouble() >= cfg.dropProb) {
                    val shift = rng.nextInt(4)
                    val shifted = ContinuousColorModel.rollQuad(q2Cw, -shift)
                    val jittered =
                        if (cfg.jitterPx > 0.0) {
                            Quad2D(
                                shifted.corners.map { p ->
                                    Point2D(
                                        p.x + rng.nextGaussian() * cfg.jitterPx,
                                        p.y + rng.nextGaussian() * cfg.jitterPx,
                                    )
                                },
                            )
                        } else {
                            shifted
                        }
                    val detQuad = ProjectiveGeometry.ensureClockwise(jittered)
                    rawDetQuads.add(detQuad to (0.75 + rng.nextDouble() * 0.23))
                }
            }

            val finger: FingerEllipse? =
                if (cfg.fingerOcclusion && (i % 24) < 10 && visibleFaces.isNotEmpty()) {
                    val allXs =
                        visibleFaces.values
                            .flatMap { q -> q.corners.map { it.x } }
                            .sorted()
                            .toDoubleArray()
                    val allYs =
                        visibleFaces.values
                            .flatMap { q -> q.corners.map { it.y } }
                            .sorted()
                            .toDoubleArray()
                    val bx = FaceLatticeFitter.percentile(allXs, 22.0)
                    val by = FaceLatticeFitter.percentile(allYs, 78.0)
                    val angleRad = Math.toRadians(-25.0)
                    FingerEllipse(
                        cx = bx,
                        cy = by,
                        rx = cfg.fingerRadiiPx.first,
                        ry = cfg.fingerRadiiPx.second,
                        cosA = cos(angleRad),
                        sinA = sin(angleRad),
                        rgb = RgbColor(196.0 * awbR, 150.0 * awbG, 128.0 * awbB),
                    )
                } else {
                    null
                }

            if (cfg.falseQuadProb > 0.0 && rng.nextDouble() < cfg.falseQuadProb) {
                val fx0 = 20.0 + rng.nextDouble() * 70.0
                val fy0 = 20.0 + rng.nextDouble() * 70.0
                val fq =
                    Quad2D(
                        listOf(
                            Point2D(fx0, fy0),
                            Point2D(fx0 + 48.0, fy0 + 5.0),
                            Point2D(fx0 + 44.0, fy0 + 52.0),
                            Point2D(fx0 - 4.0, fy0 + 47.0),
                        ),
                    )
                rawDetQuads.add(fq to 0.45)
            }

            val isBlurred = cfg.motionBlurProb > 0.0 && rng.nextDouble() < cfg.motionBlurProb

            val observations = ArrayList<QuadObservation>(rawDetQuads.size)
            for ((detQuad, score) in rawDetQuads) {
                val obs =
                    sampleQuadObservation(
                        detQuad = detQuad,
                        score = score,
                        cropSide = cropSide,
                        cropCorners = cropCorners,
                        renderedFaces = renderedFaces,
                        texSize = texSize,
                        finger = finger,
                        isBlurred = isBlurred,
                        height = height,
                        rng = pixelRng,
                    )
                observations.add(obs)
            }

            observations.shuffle(rng)
            frames.add(SyntheticFrame(i, observations, visibleFaces, isRegrip = false, twistApplied = twistDone))
        }
        return frames
    }

    private fun makeFaceTexture(
        colors9: List<Color>,
        palette: Map<Color, RgbColor>,
        stickerless: Boolean,
        logoOnWhite: Boolean,
        texSize: Int,
        lambert: Double,
        awbR: Double,
        awbG: Double,
        awbB: Double,
        glareStrength: Double,
        spec: Double,
        frameIndex: Int,
    ): DoubleArray {
        val tex = DoubleArray(texSize * texSize * 3)
        val bodyR = if (stickerless) 50.0 else 26.0
        val bodyG = if (stickerless) 50.0 else 26.0
        val bodyB = if (stickerless) 52.0 else 28.0
        for (p in 0 until texSize * texSize) {
            tex[p * 3] = bodyR
            tex[p * 3 + 1] = bodyG
            tex[p * 3 + 2] = bodyB
        }

        val cell = texSize / 3
        val margin = if (stickerless) 4 else 8
        for (idx in 0 until 9) {
            val r = idx / 3
            val c = idx % 3
            val col = colors9[idx]
            val rgb = palette.getValue(col)
            val y0 = r * cell + margin
            val y1 = (r + 1) * cell - margin
            val x0 = c * cell + margin
            val x1 = (c + 1) * cell - margin
            for (y in y0 until y1) {
                val rowOff = y * texSize
                for (x in x0 until x1) {
                    val off = (rowOff + x) * 3
                    tex[off] = rgb.r
                    tex[off + 1] = rgb.g
                    tex[off + 2] = rgb.b
                }
            }
            if (idx == 4 && logoOnWhite && col == Color.WHITE) {
                val cy = (y0 + y1) / 2
                val cx = (x0 + x1) / 2
                val rad = (cell * 0.21).toInt()
                val radSq = rad * rad
                val rectRx = rad / 2
                val rectRy = rad / 3
                for (y in (cy - rad)..(cy + rad)) {
                    for (x in (cx - rad)..(cx + rad)) {
                        val dx = x - cx
                        val dy = y - cy
                        if (dx * dx + dy * dy <= radSq) {
                            val off = (y * texSize + x) * 3
                            if (abs(dx) <= rectRx && abs(dy) <= rectRy) {
                                tex[off] = 210.0
                                tex[off + 1] = 50.0
                                tex[off + 2] = 48.0
                            } else {
                                tex[off] = 42.0
                                tex[off + 1] = 58.0
                                tex[off + 2] = 96.0
                            }
                        }
                    }
                }
            }
        }

        val hasSpec = glareStrength > 0.0 && spec > 0.15
        val gx = texSize * (0.3 + 0.4 * sin(frameIndex * 0.12))
        val gy = texSize * (0.35 + 0.3 * cos(frameIndex * 0.09))
        val invTwoSigmaSq = 1.0 / (2.0 * (texSize * 0.24) * (texSize * 0.24))
        val glareScale = 255.0 * glareStrength * spec

        for (y in 0 until texSize) {
            val dy = y - gy
            val dySq = dy * dy
            for (x in 0 until texSize) {
                val off = (y * texSize + x) * 3
                var rr = tex[off] * lambert * awbR
                var gg = tex[off + 1] * lambert * awbG
                var bb = tex[off + 2] * lambert * awbB
                if (hasSpec) {
                    val dx = x - gx
                    val add = glareScale * exp(-(dx * dx + dySq) * invTwoSigmaSq)
                    rr += add
                    gg += add
                    bb += add
                }
                tex[off] = rr
                tex[off + 1] = gg
                tex[off + 2] = bb
            }
        }
        return tex
    }

    private fun sampleScreenPixel(
        sx: Double,
        sy: Double,
        renderedFaces: List<RenderedFace>,
        texSize: Int,
        finger: FingerEllipse?,
        height: Int,
        outRgb: DoubleArray,
    ) {
        if (finger != null && finger.contains(sx, sy)) {
            outRgb[0] = finger.rgb.r
            outRgb[1] = finger.rgb.g
            outRgb[2] = finger.rgb.b
            return
        }
        for (rf in renderedFaces) {
            val pt = ProjectiveGeometry.perspectiveTransform(rf.hScreenToTex, Point2D(sx, sy)) ?: continue
            if (pt.x >= 0.0 && pt.x <= texSize - 1.0 && pt.y >= 0.0 && pt.y <= texSize - 1.0) {
                val x0 = pt.x.toInt().coerceIn(0, texSize - 2)
                val y0 = pt.y.toInt().coerceIn(0, texSize - 2)
                val fx = pt.x - x0
                val fy = pt.y - y0
                val o00 = (y0 * texSize + x0) * 3
                val o10 = (y0 * texSize + x0 + 1) * 3
                val o01 = ((y0 + 1) * texSize + x0) * 3
                val o11 = ((y0 + 1) * texSize + x0 + 1) * 3
                val w00 = (1.0 - fx) * (1.0 - fy)
                val w10 = fx * (1.0 - fy)
                val w01 = (1.0 - fx) * fy
                val w11 = fx * fy
                val tex = rf.tex
                outRgb[0] = tex[o00] * w00 + tex[o10] * w10 + tex[o01] * w01 + tex[o11] * w11
                outRgb[1] = tex[o00 + 1] * w00 + tex[o10 + 1] * w10 + tex[o01 + 1] * w01 + tex[o11 + 1] * w11
                outRgb[2] = tex[o00 + 2] * w00 + tex[o10 + 2] * w10 + tex[o01 + 2] * w01 + tex[o11 + 2] * w11
                return
            }
        }
        // Desk background gradient
        val grad = 55.0 + 40.0 * (sy.coerceIn(0.0, (height - 1).toDouble()) / max(1, height - 1))
        outRgb[0] = grad * 1.02
        outRgb[1] = grad * 0.96
        outRgb[2] = grad * 0.92
    }

    private fun sampleQuadObservation(
        detQuad: Quad2D,
        score: Double,
        cropSide: Int,
        cropCorners: List<Point2D>,
        renderedFaces: List<RenderedFace>,
        texSize: Int,
        finger: FingerEllipse?,
        isBlurred: Boolean,
        height: Int,
        rng: Random,
    ): QuadObservation {
        val hCropToScreen = ProjectiveGeometry.findHomography(cropCorners, detQuad.corners)
        val cropR = DoubleArray(cropSide * cropSide)
        val cropG = DoubleArray(cropSide * cropSide)
        val cropB = DoubleArray(cropSide * cropSide)
        val gray = DoubleArray(cropSide * cropSide)
        val tmp = DoubleArray(3)

        if (hCropToScreen != null) {
            for (y in 0 until cropSide) {
                for (x in 0 until cropSide) {
                    val sp = ProjectiveGeometry.perspectiveTransform(hCropToScreen, Point2D(x.toDouble(), y.toDouble()))
                    if (sp != null) {
                        if (isBlurred) {
                            var sr = 0.0
                            var sg = 0.0
                            var sb = 0.0
                            for (k in -4..4) {
                                sampleScreenPixel(sp.x + k, sp.y, renderedFaces, texSize, finger, height, tmp)
                                sr += (tmp[0] + rng.nextGaussian() * 2.0).coerceIn(0.0, 255.0)
                                sg += (tmp[1] + rng.nextGaussian() * 2.0).coerceIn(0.0, 255.0)
                                sb += (tmp[2] + rng.nextGaussian() * 2.0).coerceIn(0.0, 255.0)
                            }
                            tmp[0] = sr / 9.0
                            tmp[1] = sg / 9.0
                            tmp[2] = sb / 9.0
                        } else {
                            sampleScreenPixel(sp.x, sp.y, renderedFaces, texSize, finger, height, tmp)
                            tmp[0] = (tmp[0] + rng.nextGaussian() * 2.0).coerceIn(0.0, 255.0)
                            tmp[1] = (tmp[1] + rng.nextGaussian() * 2.0).coerceIn(0.0, 255.0)
                            tmp[2] = (tmp[2] + rng.nextGaussian() * 2.0).coerceIn(0.0, 255.0)
                        }
                    } else {
                        tmp[0] = 60.0
                        tmp[1] = 60.0
                        tmp[2] = 60.0
                    }
                    val idx = y * cropSide + x
                    val rr = tmp[0].roundToInt().toDouble()
                    val gg = tmp[1].roundToInt().toDouble()
                    val bb = tmp[2].roundToInt().toDouble()
                    cropR[idx] = rr
                    cropG[idx] = gg
                    cropB[idx] = bb
                    gray[idx] = 0.299 * rr + 0.587 * gg + 0.114 * bb
                }
            }
        }

        // Sharpness via Laplacian variance on crop gray
        var lapSum = 0.0
        var lapSqSum = 0.0
        val innerN = (cropSide - 2) * (cropSide - 2)
        for (y in 1 until cropSide - 1) {
            for (x in 1 until cropSide - 1) {
                val c = y * cropSide + x
                val lap = gray[c - cropSide] + gray[c + cropSide] + gray[c - 1] + gray[c + 1] - 4.0 * gray[c]
                lapSum += lap
                lapSqSum += lap * lap
            }
        }
        val lapMean = lapSum / innerN
        val lapVar = max(0.0, lapSqSum / innerN - lapMean * lapMean)
        val qSharp = ((lapVar - 25.0) / 140.0).coerceIn(0.20, 1.0)
        val qAngle = ProjectiveGeometry.quadQuality(detQuad).first

        val cellSz = cropSide / 3.0
        val rgbs = ArrayList<RgbColor>(9)
        val quals = ArrayList<Double>(9)
        var anyGlare = false

        for (idx in 0 until 9) {
            val r = idx / 3
            val c = idx % 3
            val pixR: DoubleArray
            val pixG: DoubleArray
            val pixB: DoubleArray

            if (idx == 4) {
                val y0 = ((r + 0.16) * cellSz).roundToInt()
                val y1 = ((r + 0.84) * cellSz).roundToInt()
                val x0 = ((c + 0.16) * cellSz).roundToInt()
                val x1 = ((c + 0.84) * cellSz).roundToInt()
                val ph = y1 - y0
                val pw = x1 - x0
                val bh = max(3, ph / 3)
                val bw = max(3, pw / 3)
                val boxes =
                    listOf(
                        intArrayOf(y0, y0 + bh, x0, x0 + bw),
                        intArrayOf(y0, y0 + bh, x1 - bw, x1),
                        intArrayOf(y1 - bh, y1, x0, x0 + bw),
                        intArrayOf(y1 - bh, y1, x1 - bw, x1),
                    )
                val cornerPatches = boxes.map { b -> extractBox(cropR, cropG, cropB, cropSide, b[0], b[1], b[2], b[3]) }
                val meds = cornerPatches.map { Triple(median(it.first), median(it.second), median(it.third)) }
                val distSums =
                    DoubleArray(4) { a ->
                        var s = 0.0
                        for (b in 0 until 4) {
                            val dr = meds[a].first - meds[b].first
                            val dg = meds[a].second - meds[b].second
                            val db = meds[a].third - meds[b].third
                            s += sqrt(dr * dr + dg * dg + db * db)
                        }
                        s
                    }
                val keep3 = (0 until 4).sortedBy { distSums[it] }.take(3)
                val total = keep3.sumOf { cornerPatches[it].first.size }
                pixR = DoubleArray(total)
                pixG = DoubleArray(total)
                pixB = DoubleArray(total)
                var pos = 0
                for (k in keep3) {
                    val (pr, pg, pb) = cornerPatches[k]
                    System.arraycopy(pr, 0, pixR, pos, pr.size)
                    System.arraycopy(pg, 0, pixG, pos, pg.size)
                    System.arraycopy(pb, 0, pixB, pos, pb.size)
                    pos += pr.size
                }
            } else {
                val iy0 = ((r + 0.26) * cellSz).roundToInt()
                val iy1 = ((r + 0.74) * cellSz).roundToInt()
                val ix0 = ((c + 0.26) * cellSz).roundToInt()
                val ix1 = ((c + 0.74) * cellSz).roundToInt()
                val (pr, pg, pb) = extractBox(cropR, cropG, cropB, cropSide, iy0, iy1, ix0, ix1)
                pixR = pr
                pixG = pg
                pixB = pb
            }

            val medR = median(pixR)
            val medG = median(pixG)
            val medB = median(pixB)
            val stdRgb = (stdDev(pixR) + stdDev(pixG) + stdDev(pixB)) / 3.0

            val nPix = pixR.size
            var clipMaxCnt = 0
            var clipMinCnt = 0
            val brights = DoubleArray(nPix)
            for (p in 0 until nPix) {
                val pr = pixR[p]
                val pg = pixG[p]
                val pb = pixB[p]
                if (max(pr, max(pg, pb)) > 248.0) clipMaxCnt++
                if (min(pr, min(pg, pb)) > 238.0) clipMinCnt++
                brights[p] = (pr + pg + pb) / 3.0
            }
            val clippedMax = clipMaxCnt.toDouble() / nPix
            val clippedMin = clipMinCnt.toDouble() / nPix
            val minMed = min(medR, min(medG, medB))
            val sortedBrights = brights.sortedArray()
            val brightP95 = FaceLatticeFitter.percentile(sortedBrights, 95.0)
            val brightP25 = FaceLatticeFitter.percentile(sortedBrights, 25.0)
            val grad = brightP95 - brightP25

            var glarePen = 0.0
            if (
                clippedMin > 0.15 ||
                (clippedMax > 0.12 && (minMed > 115.0 || grad > 22.0)) ||
                (brightP95 > 244.0 && grad > 20.0)
            ) {
                glarePen = min(0.95, 0.60 + max(clippedMax, clippedMin) * 1.2 + max(0.0, grad - 18.0) / 80.0)
                anyGlare = true
            }
            val occPen = ((stdRgb - 12.0) / 28.0).coerceIn(0.0, 0.85)
            val q = max(0.01, qAngle * qSharp * (1.0 - glarePen) * (1.0 - occPen))
            rgbs.add(RgbColor(medR, medG, medB))
            quals.add(q)
        }

        return QuadObservation(
            quad = detQuad,
            cellRgbs = rgbs,
            cellQualities = quals,
            sharpness = qSharp,
            hasGlare = anyGlare,
            score = score,
        )
    }

    private fun extractBox(
        cropR: DoubleArray,
        cropG: DoubleArray,
        cropB: DoubleArray,
        stride: Int,
        y0: Int,
        y1: Int,
        x0: Int,
        x1: Int,
    ): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val n = max(1, (y1 - y0) * (x1 - x0))
        val pr = DoubleArray(n)
        val pg = DoubleArray(n)
        val pb = DoubleArray(n)
        var k = 0
        for (y in y0 until y1) {
            val row = y * stride
            for (x in x0 until x1) {
                pr[k] = cropR[row + x]
                pg[k] = cropG[row + x]
                pb[k] = cropB[row + x]
                k++
            }
        }
        return Triple(pr, pg, pb)
    }

    private fun median(values: DoubleArray): Double {
        val s = values.sortedArray()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else 0.5 * (s[mid - 1] + s[mid])
    }

    private fun stdDev(values: DoubleArray): Double {
        val mean = values.average()
        var sumSq = 0.0
        for (v in values) {
            val d = v - mean
            sumSq += d * d
        }
        return sqrt(sumSq / values.size)
    }
}
