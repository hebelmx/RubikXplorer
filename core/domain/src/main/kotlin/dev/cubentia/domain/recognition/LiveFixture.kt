package dev.cubentia.domain.recognition

import dev.cubentia.domain.cube.Face
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val osRelease: String,
    val osBuild: String,
    val sdkInt: Int,
)

/** Where one sticker's truth came from: the operator set it, or it was a close-call-gated prefill the operator left. */
@Serializable
enum class TruthSource {
    @SerialName("entered")
    ENTERED,

    @SerialName("prefilled")
    PREFILLED,
}

/**
 * One recorded face. Schema 1 (Story 10.10) has no truth sources: every sticker was entered. Schema 2 (Story 10.12)
 * carries one [TruthSource] per sticker and optionally names a sibling [frame] image (e.g. `U.jpg`).
 */
@Serializable
data class LiveFixtureRecord(
    val schemaVersion: Int = LiveFixtureJson.CURRENT_SCHEMA,
    val device: DeviceInfo,
    val cube: String,
    val light: String,
    val capturedAt: String,
    val calibration: CalibrationCenters,
    val labeledSample: LabeledFaceSample,
    val truthSources: List<TruthSource> = emptyList(),
    val frame: String? = null,
) {
    /** True when the operator entered sticker [index]; schema 1 records count every sticker as entered. */
    fun entered(index: Int): Boolean = schemaVersion == 1 || truthSources.getOrNull(index) == TruthSource.ENTERED
}

object LiveFixturePaths {
    fun directoryName(
        device: String,
        cube: String,
        light: String,
    ): String =
        listOf(device, cube, light).joinToString("_") { part ->
            part.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').also {
                require(it.isNotEmpty()) { "Fixture directory part must contain letters or digits: '$part'" }
            }
        }

    fun fileName(face: Face): String = "${face.name}.json"

    fun frameName(face: Face): String = "${face.name}.jpg"
}

object LiveFixtureJson {
    const val CURRENT_SCHEMA = 2
    private const val STICKERS = 9

    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    fun encode(record: LiveFixtureRecord): String = json.encodeToString(record)

    fun decode(value: String): LiveFixtureRecord =
        json.decodeFromString<LiveFixtureRecord>(value).also {
            when (it.schemaVersion) {
                1 -> {
                    require(it.truthSources.isEmpty() && it.frame == null) { "Schema 1 fixtures carry no truth sources or frame" }
                }

                CURRENT_SCHEMA -> {
                    require(it.truthSources.size == STICKERS) {
                        "Schema 2 fixtures need $STICKERS truth sources, found ${it.truthSources.size}"
                    }
                    require(it.frame == null || it.frame == LiveFixturePaths.frameName(it.labeledSample.sample.face)) {
                        "Frame must be ${LiveFixturePaths.frameName(it.labeledSample.sample.face)}, found ${it.frame}"
                    }
                }

                else -> {
                    throw IllegalArgumentException("Unsupported live fixture schema version: ${it.schemaVersion}")
                }
            }
        }
}

/** Whether a corner capture shows a cube (positive) or not (negative, a no-cube capture for the detector's rejection). */
@Serializable
enum class CornerLabel {
    @SerialName("positive")
    POSITIVE,

    @SerialName("negative")
    NEGATIVE,
}

/** Where the light came from in a corner capture group (Story 10.19): from the camera side, or behind the cube. */
@Serializable
enum class LightDirection {
    @SerialName("front")
    FRONT,

    @SerialName("back")
    BACK,

    /** Story 10.20: light from one side of the cube. */
    @SerialName("side")
    SIDE,
}

/**
 * One corner capture with the three labeled, upright faces in hold order.
 *
 * Schema 3 (Story 10.18) always carries the three samples. Schema 4 (Story 10.19) adds the capture's [label], the
 * group's [lightDirection], the operator's manual [keypoints] and the detector's [detectorKeypoints] (diagnostics only,
 * never a label), each 7 points `[T, UL, LL, B, LR, UR, C]` as `[u, v]` normalized to the model-input crop; a schema-4
 * positive the detector missed may have no color samples, and a negative never has any.
 */
@Serializable
data class CornerFixtureRecord(
    val schemaVersion: Int = CornerFixtureJson.CURRENT_SCHEMA,
    val device: DeviceInfo,
    val cube: String,
    val light: String,
    val capturedAt: String,
    val calibration: CalibrationCenters,
    val hold: Int,
    val labeledSamples: List<LabeledFaceSample>,
    val truthSources: List<List<TruthSource>>,
    val frame: String = CornerFixturePaths.frameName(hold),
    val label: CornerLabel = CornerLabel.POSITIVE,
    val lightDirection: LightDirection? = null,
    val keypoints: List<List<Double>>? = null,
    val detectorKeypoints: List<List<Double>>? = null,
    /** Schema 4: whether the torch was on (null: unknown, or a camera without a flash unit). */
    val torch: Boolean? = null,
    /** Schema 4, Story 10.20: the hold's light check and metering settle as the recorder measured them (null: older records). */
    val diagnostics: CornerHoldDiagnostics? = null,
)

/**
 * How the 10.22 metering before a recorder capture ended: settled, at the settle cap, or still settling at the capture;
 * or [MANUAL_EV] (GH-330) when no cube metering ran since the last reset, so the hold was exposed by the camera's own
 * continuous AE with the operator's EV compensation ([CornerHoldDiagnostics.evIndex]). It only means no cube metering
 * ran, not that the operator changed EV: an [CornerHoldDiagnostics.evIndex] of 0 means no compensation was applied.
 */
@Serializable
enum class SettleOutcome {
    @SerialName("settled")
    SETTLED,

    @SerialName("timeout")
    TIMEOUT,

    @SerialName("settling")
    SETTLING,

    @SerialName("manual-ev")
    MANUAL_EV,
}

/**
 * The metering settle before a recorder capture (Story 10.20, the 10.22 settle signal): how long it took ([settleMs]),
 * how it ended, the exposure lock taken then ([lock]: `on`, `on+awb`, `unsupported`, or `none` for a capture taken while
 * still settling or with no metering at all, [SettleOutcome.MANUAL_EV], or `awb` for a [SettleOutcome.MANUAL_EV] capture
 * while the hold-1 white-balance lock is still held) and the metering future's part of the wait
 * ([futureMs], null when unknown).
 */
@Serializable
data class CornerSettleDiagnostics(
    val settleMs: Long?,
    val outcome: SettleOutcome,
    val lock: String,
    val futureMs: Long? = null,
)

/**
 * One recorded hold's diagnostics (Story 10.20), numbers only: the [SideLight] check of the three faces in hold order
 * (levels, [lightScore], [lightPassed]) in the recorder's mode, the [lightMode] word Quick Scan's diagnostics log
 * (`calibrated`, `uncalibrated`, or `torch` when the torch was lit), the uncalibrated score beside it, the torch and the
 * metering [settle]. A face not measured leaves its level and the scores null ([lightPassed] null then too). GH-330 adds
 * the EV compensation index the camera was set to at the capture (the requested index) ([evIndex]; null: no exposure
 * compensation, or older records).
 */
@Serializable
data class CornerHoldDiagnostics(
    val topLevel: Double?,
    val leftLevel: Double?,
    val rightLevel: Double?,
    val lightScore: Double?,
    val lightPassed: Boolean?,
    val lightMode: String,
    val uncalibratedScore: Double?,
    val torch: Boolean?,
    val settle: CornerSettleDiagnostics? = null,
    val evIndex: Int? = null,
)

/** One hold of a recorded group (Story 10.20): its number and the torch state at its capture (null: no flash unit). */
@Serializable
data class CornerHoldManifest(
    val hold: Int,
    val torch: Boolean? = null,
)

/**
 * The manifest `group.json` the recorder writes in each corner group folder. [pipeline] `"10.19"` marks a group
 * recorded with the 10.19 metering, which the side-face replay scores; `"10.22"` (Story 10.20) a group recorded with the
 * 10.19 lighting plus 10.22's settle-and-lock, reported as the unenforced baseline until Story 10.21 enforces it. A group
 * without a manifest is pre-10.19 and only reported. Story 10.20 adds the recorder [calibration] the group was recorded
 * with and the torch state per hold ([holds]); older manifests decode without them.
 */
@Serializable
data class CornerGroupManifest(
    val pipeline: String,
    val lightDirection: LightDirection,
    val device: DeviceInfo,
    val cube: String,
    val light: String,
    /** Whether the torch was on for the whole group (null: a camera without a flash unit, or unknown). */
    val torch: Boolean? = null,
    val calibration: CalibrationCenters? = null,
    val holds: List<CornerHoldManifest> = emptyList(),
)

/** Which split a recorded group belongs to (Story 10.20): held out (never tuned or trained on) or tuning. */
@Serializable
enum class CornerSplit {
    @SerialName("heldout")
    HELDOUT,

    @SerialName("tuning")
    TUNING,
}

/** One group of the [CornerGroupIndex]: its folder name under `Fixtures/corner/`, its split, and whether its cube is pastel. */
@Serializable
data class CornerGroupIndexEntry(
    val group: String,
    val split: CornerSplit,
    val pastel: Boolean = false,
)

/**
 * The group index `Fixtures/corner/groups.json` (Story 10.20), written after the recording session: every 10.22 group
 * once, with its held-out or tuning split. Whole groups are held out. [kind] tells it from fixtures and manifests.
 */
@Serializable
data class CornerGroupIndex(
    val kind: String = KIND,
    val schemaVersion: Int = SCHEMA,
    val groups: List<CornerGroupIndexEntry>,
) {
    /** The entry for folder [group]; null when the index does not list it. */
    fun entry(group: String): CornerGroupIndexEntry? = groups.firstOrNull { it.group == group }

    companion object {
        const val KIND = "corner-group-index"
        const val SCHEMA = 1
    }
}

/**
 * One keypoint fixture for the corner model evaluator (`tools/corner-model/evaluate_model.py --fixtures-corner`):
 * [frame] is the square, upright model-input crop next to the JSON; a positive carries the operator's 7 manual
 * [keypoints], a negative none. [detectorKeypoints] is what the detector found (diagnostics, never a label). [kind] lets
 * the evaluator tell these files from color records and manifests.
 */
@Serializable
data class CornerKeypointFixture(
    val kind: String = KIND,
    val frame: String,
    val label: CornerLabel,
    val keypoints: List<List<Double>>? = null,
    val detectorKeypoints: List<List<Double>>? = null,
    val device: DeviceInfo,
    val cube: String,
    val light: String,
    val lightDirection: LightDirection,
    val capturedAt: String,
    /** Whether the torch was on (null: a camera without a flash unit, or unknown). */
    val torch: Boolean? = null,
) {
    companion object {
        const val KIND = "corner-keypoints"
    }
}

object CornerFixturePaths {
    fun directoryName(
        device: String,
        cube: String,
        light: String,
    ): String = LiveFixturePaths.directoryName(device, cube, light)

    fun fileName(hold: Int): String = "corner-$hold.json"

    fun frameName(hold: Int): String = "corner-$hold.jpg"

    const val MANIFEST = "group.json"

    /** The pipeline tag of a group recorded with the Story 10.19 metering. */
    const val PIPELINE_10_19 = "10.19"

    /** The pipeline tag of a group recorded with the 10.19 lighting plus the Story 10.22 settle-and-lock (Story 10.20). */
    const val PIPELINE_10_22 = "10.22"

    /** The group index in `Fixtures/corner/` naming each 10.22 group's split (Story 10.20). */
    const val GROUP_INDEX = "groups.json"

    /** The keypoint fixtures' subfolder in a group folder. */
    const val KEYPOINTS_DIR = "keypoints"

    fun keypointFileName(index: Int): String = "%04d.json".format(java.util.Locale.ROOT, index)

    fun keypointFrameName(index: Int): String = "%04d.png".format(java.util.Locale.ROOT, index)
}

object CornerFixtureJson {
    const val CURRENT_SCHEMA = 4
    private const val LEGACY_SCHEMA = 3
    private const val KEYPOINTS = 7
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    fun encode(record: CornerFixtureRecord): String = json.encodeToString(record)

    fun encodeManifest(manifest: CornerGroupManifest): String = json.encodeToString(manifest)

    fun decodeManifest(value: String): CornerGroupManifest = json.decodeFromString<CornerGroupManifest>(value)

    fun encodeIndex(index: CornerGroupIndex): String = json.encodeToString(validIndex(index))

    /** Decodes and validates a group index: its kind and schema, a split per group, non-blank and unique group names. */
    fun decodeIndex(value: String): CornerGroupIndex = validIndex(json.decodeFromString<CornerGroupIndex>(value))

    private fun validIndex(index: CornerGroupIndex): CornerGroupIndex =
        index.also {
            require(it.kind == CornerGroupIndex.KIND) { "Not a corner group index: ${it.kind}" }
            require(it.schemaVersion == CornerGroupIndex.SCHEMA) { "Unsupported corner group index schema: ${it.schemaVersion}" }
            it.groups.forEach { entry -> require(entry.group.isNotBlank()) { "A group index entry needs a group name" } }
            val repeated =
                it.groups
                    .groupBy { entry -> entry.group }
                    .filterValues { entries -> entries.size > 1 }
                    .keys
            require(repeated.isEmpty()) { "Groups listed more than once in the group index: ${repeated.joinToString()}" }
        }

    fun encodeKeypoints(fixture: CornerKeypointFixture): String = json.encodeToString(fixture)

    fun decodeKeypoints(value: String): CornerKeypointFixture =
        json.decodeFromString<CornerKeypointFixture>(value).also { fixture ->
            require(fixture.kind == CornerKeypointFixture.KIND) { "Not a corner keypoint fixture: ${fixture.kind}" }
            when (fixture.label) {
                CornerLabel.POSITIVE -> require(validKeypoints(fixture.keypoints)) { "A positive needs 7 crop-normalized keypoints" }
                CornerLabel.NEGATIVE -> require(fixture.keypoints.isNullOrEmpty()) { "A negative carries no keypoints" }
            }
            require(fixture.detectorKeypoints == null || validKeypoints(fixture.detectorKeypoints))
        }

    /** Seven `[u, v]` points, each inside the crop (`[0, 1]`). */
    fun validKeypoints(points: List<List<Double>>?): Boolean =
        points != null && points.size == KEYPOINTS && points.all { it.size == 2 && it.all { c -> c.isFinite() && c in 0.0..1.0 } }

    fun decode(value: String): CornerFixtureRecord =
        json.decodeFromString<CornerFixtureRecord>(value).also { record ->
            require(record.schemaVersion == CURRENT_SCHEMA || record.schemaVersion == LEGACY_SCHEMA) {
                "Unsupported corner fixture schema: ${record.schemaVersion}"
            }
            val plan = requireNotNull(QuickScanPlan.holds.getOrNull(record.hold - 1)) { "Unknown corner hold: ${record.hold}" }
            require(record.frame == CornerFixturePaths.frameName(record.hold))
            if (record.schemaVersion == LEGACY_SCHEMA) {
                require(record.label == CornerLabel.POSITIVE && record.keypoints == null && record.detectorKeypoints == null)
                require(record.lightDirection == null && record.torch == null && record.diagnostics == null)
            } else {
                require(
                    record.keypoints == null || validKeypoints(record.keypoints),
                ) { "Manual keypoints must be 7 crop-normalized points" }
                require(record.detectorKeypoints == null || validKeypoints(record.detectorKeypoints))
                if (record.label == CornerLabel.NEGATIVE) require(record.labeledSamples.isEmpty() && record.keypoints == null)
            }
            // Schema 4: a positive the detector missed (or a negative) may carry no color samples.
            if (record.schemaVersion == CURRENT_SCHEMA && record.labeledSamples.isEmpty()) {
                require(record.truthSources.isEmpty())
                // Without color samples a positive is only useful for its manual corners.
                if (record.label == CornerLabel.POSITIVE) require(record.keypoints != null) { "A positive without samples needs keypoints" }
                if (record.calibration.centers.isNotEmpty()) require(StickerClassifier.isValid(record.calibration))
                return@also
            }
            require(record.labeledSamples.size == plan.size && record.truthSources.size == plan.size)
            require(StickerClassifier.isValid(record.calibration))
            record.labeledSamples.zip(plan).forEach { (labeled, step) ->
                require(labeled.truth.size == 9 && labeled.truth[FaceSample.CENTER_INDEX] == step.centerColor)
                val sample = labeled.sample
                require(
                    sample.face == step.face && sample.centerColor == step.centerColor && sample.upNeighborColor == step.upNeighborColor,
                )
                require(sample.orientationDegrees == 0 && sample.stickers.size == 9 && sample.stickers.all { it.isValid() })
            }
            require(record.truthSources.all { it.size == 9 })
        }
}
