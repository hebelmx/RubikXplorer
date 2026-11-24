package dev.cubentia.app.camera.recorder

import dev.cubentia.domain.cube.Color
import dev.cubentia.domain.cube.Face
import dev.cubentia.domain.recognition.CalibrationCenters
import dev.cubentia.domain.recognition.CaptureQuality
import dev.cubentia.domain.recognition.FaceSample
import dev.cubentia.domain.recognition.Lab
import dev.cubentia.domain.recognition.TruthSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Pure recorder rules (Story 10.12). Numeric bounds come from session A evidence and are named so Story 10.13 can retune
 * them; none of them changes the shipped NearestCenterClassifier.
 */

/** A sticker is prefilled only when its nearest center beats the second nearest by at least this CIE76 ΔE. */
internal const val CLOSE_CALL_DELTA_E = 10.0

/** Calibration ring: 12 points at 30% of the cell side around the center cell's middle, each a 3×3-pixel mean. */
internal const val RING_POINTS = 12
internal const val RING_RADIUS_FRACTION = 0.30

/** A ring point farther than this from the ring mean means the center cell is not one even color (logo, edge). */
internal const val RING_UNIFORMITY_DELTA_E = 12.0
internal const val WHITE_MIN_L = 60.0
internal const val WHITE_MAX_CHROMA = 25.0
internal const val COLOR_MIN_CHROMA = 20.0

/** A new center this close to another saved center would make the two colors indistinguishable. */
internal const val DISTINCT_CENTER_DELTA_E = 10.0

/** Auto re-meter: grid change versus the last settled frame, frame-to-frame steadiness, and how long it must hold. */
internal const val REMETER_CHANGE_DELTA_E = 15.0
internal const val REMETER_STEADY_DELTA_E = 5.0
internal const val REMETER_STEADY_MS = 600L

/** After a re-meter the image shifts as AE/AWB converge; that window is absorbed into the settled frame, not re-triggered. */
internal const val REMETER_SETTLE_MS = 1000L

internal const val FRAME_MAX_EDGE = 1024
internal const val FRAME_JPEG_QUALITY = 85

/** After a successful Capture the net keeps showing the saved truth this long before the next face is prefilled. */
internal const val CAPTURE_HOLD_MS = 1500L

/** After a calibration swatch is saved, Sample stays disabled this long (a plain timed hold, no detection). */
internal const val CALIBRATION_HOLD_MS = 1500L

/**
 * Recording convention: each face is named by its center color (blue-front), so the operator never tracks orientation.
 * Session A/A2 fixtures are named under this table. The evaluator (`LiveBaselineEvaluator` / `NearestCenterClassifier`)
 * classifies by calibration color, not by this table. The AGENTS.md home frame (GREEN on F) is deliberately not used
 * here: changing it would rename the meaning of every recorded fixture.
 */
internal val FACE_CENTER: Map<Face, Color> =
    mapOf(
        Face.U to Color.WHITE,
        Face.R to Color.ORANGE,
        Face.F to Color.BLUE,
        Face.D to Color.YELLOW,
        Face.L to Color.RED,
        Face.B to Color.GREEN,
    )

/** Operator-facing color name, e.g. `Green`. */
internal fun colorLabel(color: Color): String = color.name.lowercase().replaceFirstChar { it.uppercase() }

/** Face chip text: the face letter and its center color, e.g. `B · Green`, so B never reads as Blue. */
internal fun faceChipLabel(face: Face): String = "${face.name} · ${colorLabel(FACE_CENTER.getValue(face))}"

/** Lock reason when the entered center does not match the face, e.g. `Face B needs the Green center — this one is Blue`. */
internal fun centerMismatch(
    face: Face,
    center: Color,
): String = "Face ${face.name} needs the ${colorLabel(FACE_CENTER.getValue(face))} center — this one is ${colorLabel(center)}"

/** Why Capture is disabled, worded for the operator (e.g. `4/9 stickers set`, the center mismatch); empty when ready. */
internal fun captureLocks(
    saving: Boolean,
    holding: Boolean,
    metaReady: Boolean,
    calibratedCount: Int,
    entry: TruthEntry,
    face: Face,
): List<String> =
    buildList {
        if (saving) add("saving…")
        if (holding) add("hold")
        if (!metaReady) add("cube and light")
        if (calibratedCount < Color.entries.size) add("calibration $calibratedCount/${Color.entries.size}")
        if (!entry.complete) add("${entry.colors.count { it != null }}/${TruthEntry.STICKERS} stickers set")
        val center = entry.colors[FaceSample.CENTER_INDEX]
        if (center != null && center != FACE_CENTER.getValue(face)) add(centerMismatch(face, center))
    }

/** The face selected after a capture hold: the first face not yet captured, or [current] when all six are captured. */
internal fun nextFace(
    captured: Collection<Face>,
    current: Face,
): Face = Face.entries.firstOrNull { it !in captured } ?: current

/** Post-capture status: `Saved L · 1 entered by you`, plus ` · override MISALIGNED` when an override was used. */
internal fun savedSummary(
    face: Face,
    sources: List<TruthSource>,
    override: CaptureQuality?,
): String =
    "Saved ${face.name} · ${sources.count { it == TruthSource.ENTERED }} entered by you" +
        (override?.let { " · override ${it.name}" } ?: "")

internal fun deltaE(
    left: Lab,
    right: Lab,
): Double = sqrt((left.l - right.l).squared() + (left.a - right.a).squared() + (left.b - right.b).squared())

private fun Double.squared() = this * this

internal fun chroma(lab: Lab): Double = hypot(lab.a, lab.b)

internal fun mean(samples: List<Lab>): Lab {
    require(samples.isNotEmpty()) { "Cannot average no samples" }
    return Lab(samples.sumOf { it.l } / samples.size, samples.sumOf { it.a } / samples.size, samples.sumOf { it.b } / samples.size)
}

/** Mean per-cell ΔE between two 9-cell grids. */
internal fun meanDeltaE(
    left: List<Lab>,
    right: List<Lab>,
): Double {
    require(left.size == right.size && left.isNotEmpty()) { "Grids must have the same non-zero size" }
    return left.zip(right).sumOf { (a, b) -> deltaE(a, b) } / left.size
}

/** Close-call-gated prefill: nearest center when it beats the runner-up by [CLOSE_CALL_DELTA_E], otherwise blank. */
internal fun prefill(
    stickers: List<Lab>,
    calibration: CalibrationCenters,
): List<Color?> =
    stickers.map { sticker ->
        val ranked = calibration.centers.entries.sortedBy { (_, center) -> deltaE(sticker, center) }
        when {
            ranked.isEmpty() -> null
            ranked.size == 1 -> ranked.first().key
            deltaE(sticker, ranked[1].value) - deltaE(sticker, ranked[0].value) >= CLOSE_CALL_DELTA_E -> ranked.first().key
            else -> null
        }
    }

/**
 * Truth entry for the current face: tap a sticker to select it, tap a palette color to set it; the set sticker becomes
 * [TruthSource.ENTERED] and selection auto-advances to the next blank sticker (row-major, wrapping), or none.
 */
internal data class TruthEntry(
    val colors: List<Color?> = List(STICKERS) { null },
    val sources: List<TruthSource?> = List(STICKERS) { null },
    val selected: Int? = null,
) {
    val complete: Boolean get() = colors.all { it != null }
    val anyEntered: Boolean get() = sources.any { it == TruthSource.ENTERED }

    fun select(index: Int): TruthEntry = copy(selected = index.takeIf { it in 0 until STICKERS })

    fun set(color: Color): TruthEntry {
        val index = selected ?: return this
        val nextColors = colors.toMutableList().also { it[index] = color }
        val nextSources = sources.toMutableList().also { it[index] = TruthSource.ENTERED }
        return TruthEntry(nextColors, nextSources, nextBlank(nextColors, index))
    }

    /**
     * Refreshes prefilled cells from the latest acceptable frame while nothing has been entered on this face; after the
     * first entry the face is frozen. A pending selection is kept; with none, the first blank is selected.
     */
    fun withPrefill(prefilled: List<Color?>): TruthEntry {
        if (anyEntered) return this
        require(prefilled.size == STICKERS) { "Prefill needs nine stickers" }
        val nextSources = prefilled.map { color -> color?.let { TruthSource.PREFILLED } }
        return TruthEntry(prefilled, nextSources, selected ?: prefilled.indexOfFirst { it == null }.takeIf { it >= 0 })
    }

    /**
     * Re-prefills every cell the operator has not entered from [prefilled] (blank when the prefill is blank); entered
     * cells never change. Works after entries too, which is how a face frozen on a stale frame recovers. The selection is
     * kept while it points at a still-blank cell, otherwise the first blank (or none) is selected.
     */
    fun suggestAgain(prefilled: List<Color?>): TruthEntry {
        require(prefilled.size == STICKERS) { "Prefill needs nine stickers" }
        val nextColors = colors.indices.map { if (sources[it] == TruthSource.ENTERED) colors[it] else prefilled[it] }
        val nextSources =
            sources.indices.map { index ->
                if (sources[index] == TruthSource.ENTERED) TruthSource.ENTERED else prefilled[index]?.let { TruthSource.PREFILLED }
            }
        val keep = selected?.takeIf { nextColors[it] == null }
        return TruthEntry(nextColors, nextSources, keep ?: nextColors.indexOfFirst { it == null }.takeIf { it >= 0 })
    }

    /** The nine truth colors and their sources for a capture; null until every sticker is filled. */
    fun confirmed(): Pair<List<Color>, List<TruthSource>>? =
        if (complete && sources.all { it != null }) colors.filterNotNull() to sources.filterNotNull() else null

    companion object {
        const val STICKERS = 9

        private fun nextBlank(
            colors: List<Color?>,
            from: Int,
        ): Int? = (1..STICKERS).map { (from + it) % STICKERS }.firstOrNull { colors[it] == null }
    }
}

internal sealed interface SwatchWarning {
    data object NotUniform : SwatchWarning

    data class Implausible(
        val reason: String,
    ) : SwatchWarning

    data class TooClose(
        val other: Color,
        val deltaE: Double,
    ) : SwatchWarning
}

/** Offsets, in cell sides from the cell middle, of the [RING_POINTS] calibration ring points. */
internal fun ringOffsets(): List<Pair<Double, Double>> =
    List(RING_POINTS) { index ->
        val angle = 2 * PI * index / RING_POINTS
        RING_RADIUS_FRACTION * cos(angle) to RING_RADIUS_FRACTION * sin(angle)
    }

/** Warnings for a calibration [swatch] of [color] sampled from [ringPoints], given centers already [saved]. */
internal fun swatchWarnings(
    color: Color,
    swatch: Lab,
    ringPoints: List<Lab>,
    saved: Map<Color, Lab>,
): List<SwatchWarning> =
    buildList {
        if (ringPoints.any { deltaE(it, swatch) > RING_UNIFORMITY_DELTA_E }) add(SwatchWarning.NotUniform)
        val c = chroma(swatch)
        if (color == Color.WHITE) {
            if (swatch.l < WHITE_MIN_L) add(SwatchWarning.Implausible("white is too dark (L %.0f < %.0f)".format(swatch.l, WHITE_MIN_L)))
            if (c > WHITE_MAX_CHROMA) add(SwatchWarning.Implausible("white is too colorful (C %.0f > %.0f)".format(c, WHITE_MAX_CHROMA)))
        } else if (c < COLOR_MIN_CHROMA) {
            add(SwatchWarning.Implausible("${color.name.lowercase()} is too gray (C %.0f < %.0f)".format(c, COLOR_MIN_CHROMA)))
        }
        saved
            .filterKeys { it != color }
            .forEach { (other, center) ->
                val distance = deltaE(swatch, center)
                if (distance < DISTINCT_CENTER_DELTA_E) add(SwatchWarning.TooClose(other, distance))
            }
    }

internal fun SwatchWarning.describe(): String =
    when (this) {
        SwatchWarning.NotUniform -> "The center cell is not one even color (logo or edge?)."
        is SwatchWarning.Implausible -> "Implausible swatch: $reason."
        is SwatchWarning.TooClose -> "Within %.1f ΔE of the saved ${other.name.lowercase()} center.".format(deltaE)
    }

/**
 * Decides when to re-meter AF/AE/AWB on the grid after realignment: the 9-cell mean ΔE versus the last settled frame
 * exceeds [REMETER_CHANGE_DELTA_E], then the grid holds steady (< [REMETER_STEADY_DELTA_E] frame to frame) for
 * [REMETER_STEADY_MS]. Not thread-safe; feed it from one thread.
 */
internal class RemeterTrigger {
    private var settled: List<Lab>? = null
    private var previous: List<Lab>? = null
    private var armed = false
    private var steadySince: Long? = null
    private var settlingUntil: Long? = null

    /** Records [grid] as the settled reference, e.g. after a manual or face-selection re-meter at [nowMs]. */
    fun settle(
        grid: List<Lab>?,
        nowMs: Long,
    ) {
        settled = grid
        armed = false
        steadySince = null
        settlingUntil = nowMs + REMETER_SETTLE_MS
    }

    /** Feeds one frame's nine cell samples; returns true exactly when a re-meter should run now. */
    fun onFrame(
        grid: List<Lab>,
        nowMs: Long,
    ): Boolean {
        val last = previous
        previous = grid
        val until = settlingUntil
        if (settled == null || (until != null && nowMs < until)) {
            settled = grid
            return false
        }
        settlingUntil = null
        if (!armed && meanDeltaE(grid, settled!!) > REMETER_CHANGE_DELTA_E) armed = true
        if (!armed) return false
        val steady = last != null && meanDeltaE(grid, last) < REMETER_STEADY_DELTA_E
        if (!steady) {
            steadySince = nowMs
            return false
        }
        val since = steadySince ?: nowMs.also { steadySince = it }
        if (nowMs - since < REMETER_STEADY_MS) return false
        settle(grid, nowMs)
        return true
    }
}

/** Frame size scaled so the long edge is at most [maxEdge]; smaller frames are kept as they are. */
internal fun scaledSize(
    width: Int,
    height: Int,
    maxEdge: Int = FRAME_MAX_EDGE,
): Pair<Int, Int> {
    require(width > 0 && height > 0 && maxEdge > 0) { "Sizes must be positive" }
    val longEdge = maxOf(width, height)
    if (longEdge <= maxEdge) return width to height
    val scale = maxEdge.toDouble() / longEdge
    return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
}

/** Converts a captured corner to ordinary samples in the recorder's prompted order. */
internal fun cornerSamples(
    hold: Int,
    reading: dev.cubentia.app.camera.CornerFrame,
): List<dev.cubentia.domain.recognition.FaceSample>? {
    if (!reading.detected) return null
    val plan = dev.cubentia.domain.recognition.QuickScanPlan.holds[hold - 1]
    return plan.zip(reading.faces).map { (step, stats) ->
        val cells = stats?.cells?.filterNotNull()?.takeIf { it.size == 9 } ?: return null
        dev.cubentia.domain.recognition.FaceSample(
            face = step.face,
            orientationDegrees = 0,
            stickers = cells.map { it.value },
            quality =
                dev.cubentia.domain.recognition.FrameQuality
                    .assessShutter(stats),
            centerColor = step.centerColor,
            upNeighborColor = step.upNeighborColor,
            centerPlain = cells[4].plain,
        )
    }
}

/** Sticker-evidence cells a corner face needs to count as read: the same count `CornerReader.read` uses. */
internal const val CORNER_FACE_MIN_EVIDENCE_CELLS = 6

/** Why a corner capture has no color samples (GH-329): nothing located, faces too dark for colors, or an unread cell. */
internal enum class CornerMiss { NO_CUBE, TOO_DARK, UNREAD_CELL }

/**
 * Why [cornerSamples] has no samples for [reading], or null when it has. "Located" is read from the frame only:
 * `CornerReader` sets a zero score and three null faces exactly when the detector located nothing. A located frame is
 * too dark when it was not detected, or a face is missing or below the sticker-evidence floors.
 */
internal fun cornerMiss(
    hold: Int,
    reading: dev.cubentia.app.camera.CornerFrame?,
): CornerMiss? {
    if (reading == null) return CornerMiss.NO_CUBE
    if (cornerSamples(hold, reading) != null) return null
    val located = reading.detected || reading.score > 0.0 || reading.faces.any { it != null }
    if (!located) return CornerMiss.NO_CUBE
    if (!reading.detected) return CornerMiss.TOO_DARK
    val dark =
        reading.faces.any { face ->
            face == null ||
                face.cells.count {
                    it != null &&
                        dev.cubentia.domain.recognition.FrameQuality
                            .isStickerEvidence(it)
                } < CORNER_FACE_MIN_EVIDENCE_CELLS
        }
    return if (dark) CornerMiss.TOO_DARK else CornerMiss.UNREAD_CELL
}

/** The capture status for a corner capture without colors. */
internal fun cornerMissStatus(miss: CornerMiss): String =
    when (miss) {
        CornerMiss.NO_CUBE -> {
            "Detector missed: place the 7 corners (colors are not saved)."
        }

        CornerMiss.TOO_DARK -> {
            "Faces too dark for colors: tap EV+, then Retake and capture again, " +
                "or place the 7 corners (colors are not saved)."
        }

        CornerMiss.UNREAD_CELL -> {
            "A face had an unread cell: place the 7 corners (colors are not saved)."
        }
    }

/** The status after a keypoint-only positive is saved as fixture [index], with an EV+ hint when the faces were too dark. */
internal fun keypointsOnlySaved(
    index: Int,
    miss: CornerMiss?,
): String =
    "Saved positive %04d (keypoints only).".format(index) +
        (if (miss == CornerMiss.TOO_DARK) " Faces were too dark for colors: tap EV+ to record them." else "")

/*
 * Story 10.19 corner-mode rules: keypoint labels on the model-input crop, never-overwrite group folders, the manifest.
 */

/** The model-input crop's half-size as a multiple of the guide radius: the model transform's own value, defined once. */
internal const val MODEL_CROP_RADIUS_MULTIPLIER = dev.cubentia.app.camera.CORNER_MODEL_CROP_RADIUS_MULTIPLIER

/** The model-input crop side the recorder writes (the model's input size, so the evaluator does not resample). */
internal const val MODEL_CROP_SIZE = 128

/** The keypoint names, in the model's order. */
internal val KEYPOINT_NAMES = listOf("T", "UL", "LL", "B", "LR", "UR", "C")

/**
 * The model-input crop on a frame: square, upright, centred on the guide's centre ([cx], [cy]) with half-size [half] in
 * frame pixels, rotated by the sensor [rotationDegrees] exactly as `CornerModelTransform` maps it. `(u, v)` is
 * normalized to the crop (`[0, 1]`).
 */
internal data class ModelCrop(
    val cx: Double,
    val cy: Double,
    val half: Double,
    val rotationDegrees: Int,
) {
    fun toFrame(
        u: Double,
        v: Double,
    ): dev.cubentia.app.camera.CornerPoint {
        val dx = (u - 0.5) * 2.0 * half
        val dy = (v - 0.5) * 2.0 * half
        val (fx, fy) =
            when (Math.floorMod(rotationDegrees, 360)) {
                90 -> dy to -dx
                180 -> -dx to -dy
                270 -> -dy to dx
                else -> dx to dy
            }
        return dev.cubentia.app.camera
            .CornerPoint(cx + fx, cy + fy)
    }

    fun toCrop(point: dev.cubentia.app.camera.CornerPoint): List<Double> {
        val fx = point.x - cx
        val fy = point.y - cy
        val (dx, dy) =
            when (Math.floorMod(rotationDegrees, 360)) {
                90 -> -fy to fx
                180 -> -fx to -fy
                270 -> fy to -fx
                else -> fx to fy
            }
        return listOf(0.5 + dx / (2.0 * half), 0.5 + dy / (2.0 * half))
    }

    companion object {
        fun of(guide: dev.cubentia.app.camera.CornerGuide): ModelCrop {
            val centre = guide.vertices().last()
            return ModelCrop(centre.x, centre.y, guide.radius * MODEL_CROP_RADIUS_MULTIPLIER, guide.rotationDegrees)
        }
    }
}

/** The seven corners `[T, UL, LL, B, LR, UR, C]` of the detector's three face quads (`CornerGuide.quads` order); null unless three quads of four. */
internal fun keypointsOf(quads: List<List<dev.cubentia.app.camera.CornerPoint>>): List<dev.cubentia.app.camera.CornerPoint>? {
    if (quads.size != 3 || quads.any { it.size != 4 }) return null
    val (top, left, right) = quads
    // top = [t, ur, c, ul], left = [ul, c, b, ll], right = [c, ur, lr, b]
    return listOf(top[0], top[3], left[3], left[2], right[2], top[1], top[2])
}

/** The detector's keypoints on the crop, each clamped into it (diagnostics only, never a label); null when none. */
internal fun detectorKeypoints(
    quads: List<List<dev.cubentia.app.camera.CornerPoint>>,
    crop: ModelCrop,
): List<List<Double>>? = keypointsOf(quads)?.map { point -> crop.toCrop(point).map { it.coerceIn(0.0, 1.0) } }

/**
 * Where manual placement starts: always the Y-anchor guide's hexagon and centre on the crop (the cube held as the guide
 * asks), never the detector's points, so a point the operator leaves untouched never saves the detector's guess as a
 * label (the detector's points are kept only in the separate diagnostics field).
 */
internal fun initialKeypoints(): List<List<Double>> {
    val radius = 1.0 / (2.0 * MODEL_CROP_RADIUS_MULTIPLIER)
    return List(6) { index ->
        val angle = (-90 - index * 60) * PI / 180
        listOf(0.5 + cos(angle) * radius, 0.5 + sin(angle) * radius)
    } + listOf(listOf(0.5, 0.5))
}

/** A dragged keypoint, kept inside the crop. */
internal fun movedKeypoint(
    point: List<Double>,
    du: Double,
    dv: Double,
): List<Double> = listOf((point[0] + du).coerceIn(0.0, 1.0), (point[1] + dv).coerceIn(0.0, 1.0))

/**
 * The folder a new recording session writes to, never an existing group: [base] when it does not exist (or holds no
 * file), else the first free `base-2`, `base-3`, … (PO 2026-11-21: a saved group is never silently overwritten).
 */
internal fun freshGroupName(
    base: String,
    taken: (String) -> Boolean,
): String {
    if (!taken(base)) return base
    var index = 2
    while (taken("$base-$index")) index++
    return "$base-$index"
}

internal enum class CornerGroupSave { SUPPLEMENTAL, HOLD }

/** The current folder stays active for supplemental captures until the next session's first hold is written. */
internal data class CornerRecordingGroup(
    val name: String? = null,
    private val restartPending: Boolean = false,
) {
    fun restartHolds(): CornerRecordingGroup = copy(restartPending = true)

    fun targetName(
        save: CornerGroupSave,
        base: String,
        taken: (String) -> Boolean,
    ): String {
        val reusable = name.takeUnless { restartPending && save == CornerGroupSave.HOLD }
        return reusable ?: freshGroupName(base) { it == name || taken(it) }
    }

    fun saved(
        name: String,
        save: CornerGroupSave,
    ): CornerRecordingGroup = copy(name = name, restartPending = restartPending && save == CornerGroupSave.SUPPLEMENTAL)
}

/** The next keypoint fixture number in a group's `keypoints/` folder, after every `NNNN.json` already there. */
internal fun nextKeypointIndex(existing: List<String>): Int =
    (
        existing
            .mapNotNull {
                Regex("""(\d{4})\.json""")
                    .matchEntire(it)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
            }.maxOrNull() ?: 0
    ) + 1

/**
 * The manifest a recording session writes in its group folder when the group starts (Story 10.20): the 10.22 pipeline
 * (10.19 lighting plus 10.22 settle-and-lock), the recorder [calibration] the group is recorded with, and no hold yet.
 */
internal fun groupManifest(
    device: dev.cubentia.domain.recognition.DeviceInfo,
    cube: String,
    light: String,
    direction: dev.cubentia.domain.recognition.LightDirection,
    torch: Boolean? = null,
    calibration: CalibrationCenters? = null,
): dev.cubentia.domain.recognition.CornerGroupManifest =
    dev.cubentia.domain.recognition.CornerGroupManifest(
        pipeline = dev.cubentia.domain.recognition.CornerFixturePaths.PIPELINE_10_22,
        lightDirection = direction,
        device = device,
        cube = cube.trim(),
        light = light.trim(),
        torch = torch,
        calibration = calibration,
    )

/**
 * [manifest] with hold [hold] recorded at torch state [torch]: a new hold is added in hold order, a retaken hold replaces
 * its own entry, and every other entry is kept unchanged.
 */
internal fun withHold(
    manifest: dev.cubentia.domain.recognition.CornerGroupManifest,
    hold: Int,
    torch: Boolean?,
): dev.cubentia.domain.recognition.CornerGroupManifest =
    manifest.copy(
        holds =
            (
                manifest.holds.filter { it.hold != hold } +
                    dev.cubentia.domain.recognition
                        .CornerHoldManifest(hold, torch)
            ).sortedBy { it.hold },
    )

/**
 * One saved hold's diagnostics (Story 10.20): the [dev.cubentia.domain.recognition.SideLight] check of the three faces
 * in hold order against the recorder [calibration] (uncalibrated without a valid one), the uncalibrated score beside it,
 * and the light mode word by `ScanDiagnostics`' rule (`torch` when the torch was lit, else the check's mode), plus the
 * torch and metering [settle] and the EV compensation index at the capture ([evIndex], GH-330). A face not measured
 * leaves its level and the score null, and then nothing passed or failed.
 */
internal fun holdDiagnostics(
    faces: List<dev.cubentia.domain.recognition.FrameStats?>,
    calibration: CalibrationCenters?,
    torch: Boolean?,
    settle: dev.cubentia.domain.recognition.CornerSettleDiagnostics?,
    evIndex: Int? = null,
): dev.cubentia.domain.recognition.CornerHoldDiagnostics {
    val three = List(3) { faces.getOrNull(it) }
    val verdict =
        dev.cubentia.domain.recognition.SideLight
            .check(three, calibration)
    val uncalibrated =
        dev.cubentia.domain.recognition.SideLight
            .check(three, null)
    return dev.cubentia.domain.recognition.CornerHoldDiagnostics(
        topLevel = verdict.topLevel,
        leftLevel = verdict.leftLevel,
        rightLevel = verdict.rightLevel,
        lightScore = verdict.score,
        lightPassed = verdict.passed.takeIf { verdict.score != null },
        lightMode = lightMode(verdict, torch == true),
        uncalibratedScore = uncalibrated.score,
        torch = torch,
        settle = settle,
        evIndex = evIndex,
    )
}

/** The settle lock word when no lock is held: a capture while the metering still settles, or with no metering at all. */
internal const val SETTLE_LOCK_NONE = "none"

/**
 * The settle a corner capture records (GH-330), never null: the reported metering's when it [settled] (or hit the cap),
 * `settling` with the elapsed [settlingMs] and [futureMs] while it still settles, and `manual-ev` when no metering ran
 * since the last reset ([settled] null: after a capture or a camera rebind) or a settled metering left nothing reported.
 * A `manual-ev` settle's lock word is `awb` while the hold-1 white balance stays locked ([awbLocked]), else `none`.
 */
internal fun captureSettle(
    settled: Boolean?,
    last: dev.cubentia.domain.recognition.CornerSettleDiagnostics?,
    settlingMs: Long?,
    futureMs: Long?,
    awbLocked: Boolean,
): dev.cubentia.domain.recognition.CornerSettleDiagnostics =
    when (settled) {
        true -> {
            last ?: manualEvSettle(awbLocked)
        }

        false -> {
            dev.cubentia.domain.recognition.CornerSettleDiagnostics(
                settlingMs,
                dev.cubentia.domain.recognition.SettleOutcome.SETTLING,
                SETTLE_LOCK_NONE,
                futureMs,
            )
        }

        null -> {
            manualEvSettle(awbLocked)
        }
    }

/** The settle lock word of a `manual-ev` capture while the hold-1 white-balance lock is still held. */
internal const val SETTLE_LOCK_AWB = "awb"

private fun manualEvSettle(awbLocked: Boolean) =
    dev.cubentia.domain.recognition.CornerSettleDiagnostics(
        settleMs = null,
        outcome = dev.cubentia.domain.recognition.SettleOutcome.MANUAL_EV,
        lock = if (awbLocked) SETTLE_LOCK_AWB else SETTLE_LOCK_NONE,
        futureMs = null,
    )

/** The `lightMode` word of `ScanDiagnostics.cornerFrameLine`: `torch` when the torch is lit, else the check's mode. */
internal fun lightMode(
    verdict: dev.cubentia.domain.recognition.SideLight.Verdict,
    torch: Boolean,
): String = if (torch) "torch" else verdict.mode.name.lowercase()

/**
 * Whether a settings change clears the six calibrated colors (Story 10.20): a new cube, light or torch choice, or a mode
 * change that lights or darkens the torch on this camera ([recorderTorchOn] differs for [hasFlash]), so the colors are
 * always calibrated under the torch state the corners are recorded with.
 */
internal fun recalibrates(
    oldCube: String,
    newCube: String,
    oldLight: String,
    newLight: String,
    oldTorch: Boolean,
    newTorch: Boolean,
    oldMode: Boolean,
    newMode: Boolean,
    hasFlash: Boolean?,
): Boolean =
    oldCube.trim() != newCube.trim() ||
        oldLight.trim() != newLight.trim() ||
        oldTorch != newTorch ||
        recorderTorchOn(oldMode, hasFlash, oldTorch) != recorderTorchOn(newMode, hasFlash, newTorch)

/** The torch a saved hold records: null without a flash unit ([recorded] null), else the applied state, else the choice. */
internal fun holdTorch(
    recorded: Boolean?,
    applied: Boolean?,
): Boolean? = if (recorded == null) null else applied ?: recorded

/**
 * Whether the recorder's torch is lit (Story 10.20): in corner mode, from the moment it is set and so while calibrating
 * too, when the operator wants it and the camera has a flash unit; never in face (live) mode.
 */
internal fun recorderTorchOn(
    cornerMode: Boolean,
    hasFlash: Boolean?,
    torchOn: Boolean,
): Boolean = cornerMode && hasFlash == true && torchOn

/** The torch state a recording records: the operator's choice on a camera with a flash unit, else null (no torch). */
internal fun recordedTorch(
    hasFlash: Boolean?,
    torchOn: Boolean,
): Boolean? = if (hasFlash == true) torchOn else null
